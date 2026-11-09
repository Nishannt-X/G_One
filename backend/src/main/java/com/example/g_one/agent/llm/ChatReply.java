package com.example.g_one.agent.llm;

import java.util.List;

/**
 * The model's answer: spoken text, tool calls, or both. {@code finishReason} is passed through as the provider sent it
 * ("stop", "tool_calls", "length", ...), or empty when absent.
 */
public record ChatReply(String content, List<ToolCall> toolCalls, String finishReason, Usage usage) {
    /** What one model call cost, from the response's {@code usage}. Zero when the provider sent none. */
    public record Usage(String model, int promptTokens, int completionTokens, long latencyMs) {
        public static final Usage NONE = new Usage("", 0, 0, 0);

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    public ChatReply(String content, List<ToolCall> toolCalls, String finishReason) {
        this(content, toolCalls, finishReason, Usage.NONE);
    }

    public ChatReply {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? "" : finishReason;
        usage = usage == null ? Usage.NONE : usage;
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
