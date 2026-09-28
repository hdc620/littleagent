package com.miniagent.llm;

import com.miniagent.trace.TraceTypes;
import com.miniagent.trace.Tracer;
import com.miniagent.util.Texts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试替身：按剧本依次返回预设的 {@link LlmResponse}，并记录收到的每一次请求。
 *
 * <p>Agent Runtime 的测试关键点在于「模型做了什么」必须完全可控 —— 只有这样才能断言
 * 「第 2 步把工具结果放进了上下文」「达到 maxSteps 时强制收尾」这类行为。
 * 因此循环测试全部使用本类，而不是真实 API。
 */
public class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private LlmResponse fallback;
    private String name = "ScriptedLlmClient";

    public static ScriptedLlmClient create(LlmResponse... responses) {
        ScriptedLlmClient client = new ScriptedLlmClient();
        for (LlmResponse r : responses) {
            client.then(r);
        }
        return client;
    }

    public ScriptedLlmClient then(LlmResponse response) {
        script.addLast(response);
        return this;
    }

    /** 剧本用尽后固定返回的响应；不设置则抛异常（便于发现「循环多跑了一轮」）。 */
    public ScriptedLlmClient onExhausted(LlmResponse response) {
        this.fallback = response;
        return this;
    }

    public ScriptedLlmClient named(String name) {
        this.name = name;
        return this;
    }

    public List<LlmRequest> requests() {
        return new ArrayList<>(requests);
    }

    public LlmRequest lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    public int callCount() {
        return requests.size();
    }

    /** 取出某个 role 的消息文本，便于断言上下文组装结果。 */
    public List<String> messageContentsOf(LlmRequest request, Role role) {
        List<String> out = new ArrayList<>();
        for (Message m : request.messages()) {
            if (m.role() == role) {
                out.add(m.content());
            }
        }
        return out;
    }

    @Override
    public LlmResponse chat(LlmRequest request, Tracer tracer) {
        requests.add(request);
        // 与真实客户端保持一致：也写 wire 级 trace，这样「完整 trace」这类断言对两种客户端都成立
        tracer.event(TraceTypes.LLM_REQUEST, "scripted#" + requests.size(),
                Tracer.data("messages", request.messages().size(),
                        "tools", request.tools().size(),
                        "toolChoice", request.toolChoice()));
        LlmResponse next = script.pollFirst();
        if (next == null) {
            next = fallback;
        }
        if (next == null) {
            throw LlmException.badRequest("ScriptedLlmClient 剧本已用尽（第 " + requests.size() + " 次调用）");
        }
        tracer.event(TraceTypes.LLM_RESPONSE, "scripted response",
                Tracer.data("finishReason", next.finishReason(),
                        "toolCalls", next.toolCalls().size(),
                        "promptTokens", next.usage().promptTokens(),
                        "contentPreview", Texts.oneLine(next.content(), 100)));
        return next;
    }

    @Override
    public String clientName() {
        return name;
    }

    // ------------------------------------------------------------- 剧本构造器

    public static LlmResponse text(String content) {
        return LlmResponse.text(content);
    }

    public static LlmResponse reasoning(String reasoning, String content) {
        return new LlmResponse(content, reasoning, List.of(), "stop", Usage.ZERO, null);
    }

    public static LlmResponse call(String toolName, String argumentsJson) {
        return LlmResponse.withToolCalls(null, List.of(ToolCall.of(toolName, argumentsJson)));
    }

    public static LlmResponse calls(ToolCall... calls) {
        return LlmResponse.withToolCalls(null, List.of(calls));
    }

    public static LlmResponse callWithThought(String thought, String toolName, String argumentsJson) {
        return new LlmResponse(thought, null, List.of(ToolCall.of(toolName, argumentsJson)), "tool_calls", Usage.ZERO, null);
    }
}
