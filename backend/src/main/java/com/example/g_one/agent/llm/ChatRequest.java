package com.example.g_one.agent.llm;

import com.example.g_one.llm.ModelRouter;
import java.util.List;

/**
 * One /chat/completions request. {@code tools} may be empty (plain chat). {@code maxTokens} caps the answer; spoken
 * replies are one or two sentences, so a small cap also bounds latency.
 */
public record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, double temperature, int maxTokens,
                          ModelRouter.Task task) {
    public static final double DEFAULT_TEMPERATURE = 0.3;
    public static final int DEFAULT_MAX_TOKENS = 300;

    public ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, double temperature, int maxTokens) {
        this(messages, tools, temperature, maxTokens, ModelRouter.Task.CHAT);
    }

    public ChatRequest {
        task = task == null ? ModelRouter.Task.CHAT : task;
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("A chat needs at least one message.");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** The same request with a different answer budget. */
    public ChatRequest withMaxTokens(int newMaxTokens) {
        return new ChatRequest(messages, tools, temperature, newMaxTokens, task);
    }

    /** The same request, for figures: it must not reach a model that gets numbers wrong. */
    public ChatRequest numeric() {
        return new ChatRequest(messages, tools, temperature, maxTokens, ModelRouter.Task.NUMERIC);
    }

    public static ChatRequest of(List<ChatMessage> messages, List<ToolSpec> tools) {
        return new ChatRequest(messages, tools, DEFAULT_TEMPERATURE, DEFAULT_MAX_TOKENS);
    }
}
