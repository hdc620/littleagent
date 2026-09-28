package com.miniagent.llm;

/** token 用量（来自 API usage 字段；某些网关不返回时为 0）。 */
public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0);

    public static Usage of(int prompt, int completion) {
        return new Usage(prompt, completion, prompt + completion);
    }
}
