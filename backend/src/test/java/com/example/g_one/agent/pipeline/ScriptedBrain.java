package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Language;
import com.example.g_one.core.Turn;
import com.example.g_one.fakes.FakeLeg;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An {@link AgentBrain} for pipeline tests. It greets with {@link #GREETING} and answers a turn "x" with "reply to x",
 * ending the call when the turn is "end now" (as the end_call tool would; a short "bye" goes to the end-of-call check instead) and switching to English for {@link #SWITCH_TO_ENGLISH}. It records every
 * call, can throw on purpose, and can run a hook while "thinking" so a test can make another turn arrive mid-reply.
 */
final class ScriptedBrain implements AgentBrain {
    static final String GREETING = "Namaste, G-One mein aapka swaagat hai.";
    /** A turn the brain answers in English, as if the farmer switched language. */
    static final String SWITCH_TO_ENGLISH = "english please";

    record RespondCall(CallContext ctx, List<Line> history, Turn latest) {}

    final List<CallContext> greets = new CopyOnWriteArrayList<>();
    /** The greetings asked for right after an English-first language choice (also recorded in {@link #greets}). */
    final List<CallContext> greetsAfterLanguage = new CopyOnWriteArrayList<>();
    final List<RespondCall> responds = new CopyOnWriteArrayList<>();
    volatile boolean failGreet;
    volatile boolean failRespond;
    /** When set, every reply is in this language, whatever the turn was. */
    volatile Language replyIn;
    /** Runs inside {@link #respond} before it returns, with the turn text. */
    volatile Consumer<String> whileThinking = text -> {};
    /** When set, every reply says exactly this instead of "reply to x". */
    volatile String replyText;
    /** When set, the greeting says exactly this, as a returning farmer's name check would. */
    volatile String greetText;
    /** True from a {@link #greetText} greeting until a yes or no answers it, as a name check does. */
    volatile boolean identityOpen;

    @Override
    public boolean awaitingIdentity(com.example.g_one.core.CallId callId) {
        return identityOpen;
    }

    /** What the layered reader would say about an answer the words do not settle; null reads words only. */
    volatile java.util.function.Function<String, com.example.g_one.agent.deal.ReplyGuard.Reading> modelReads;
    /** The questions the pipeline asked to read an answer to, in order. */
    final List<String> yesNoQuestions = new CopyOnWriteArrayList<>();

    @Override
    public com.example.g_one.agent.deal.ReplyGuard.Reading readYesNo(com.example.g_one.core.CallId callId,
                                                                     String question, String reply) {
        yesNoQuestions.add(question);
        // The contract of the real reader: words first, and a vetoed reply never reaches a model.
        com.example.g_one.agent.deal.ReplyGuard.Verdict words = com.example.g_one.agent.deal.ReplyGuard.verdict(reply);
        if (words.reading() != com.example.g_one.agent.deal.ReplyGuard.Reading.UNCLEAR || words.vetoed()
                || modelReads == null) {
            return words.reading();
        }
        return modelReads.apply(reply);
    }

    static String replyTo(String turn) {
        return "reply to " + turn;
    }

    @Override
    public Reply greet(CallContext ctx) {
        greets.add(ctx);
        if (failGreet) {
            throw new IllegalStateException("brain down (test)");
        }
        identityOpen = greetText != null;
        String text = greetText != null ? greetText : ctx.memory().isEmpty() ? GREETING : GREETING + " Welcome back.";
        return new Reply(text, ctx.language(), false);
    }

    @Override
    public Reply greetAfterLanguageChoice(CallContext ctx) {
        greetsAfterLanguage.add(ctx);
        return greet(ctx);
    }

    @Override
    public Reply respond(CallContext ctx, List<Line> history, Turn latest) {
        responds.add(new RespondCall(ctx, List.copyOf(history), latest));
        if (List.of("हाँ", "नहीं", "yes", "no").contains(latest.text())) identityOpen = false;
        whileThinking.accept(latest.text());
        if (failRespond) {
            throw new IllegalStateException("brain down (test)");
        }
        Language language = replyIn != null ? replyIn : latest.text().equals(SWITCH_TO_ENGLISH) ? FakeLeg.ENGLISH : ctx.language();
        return new Reply(replyText != null ? replyText : replyTo(latest.text()), language, latest.text().equals("end now"));
    }
}
