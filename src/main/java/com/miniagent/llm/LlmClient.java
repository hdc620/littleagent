package com.miniagent.llm;

import com.miniagent.trace.Tracer;

/**
 * LLM 客户端抽象。整个 Runtime 只依赖这个接口，因此：
 * <ul>
 *   <li>生产用 {@link OpenAiCompatibleClient}（真实 HTTP 调用 DeepSeek / 任意 OpenAI 兼容网关）；</li>
 *   <li>测试用 {@link ScriptedLlmClient}（按脚本返回响应，精确断言 Runtime 行为）；</li>
 *   <li>离线演示用 {@link MockLlmClient}（关键词规则假模型，无 Key 也能跑通全流程）。</li>
 * </ul>
 *
 * <p>实现约定：网络错误、429、5xx 由实现内部按退避策略重试；重试耗尽后抛 {@link LlmException}（unchecked）。
 * 传入的 {@link Tracer} 用于记录 wire 级别事件（请求体摘要、重试、耗时、token 用量）。
 */
public interface LlmClient {

    LlmResponse chat(LlmRequest request, Tracer tracer);

    /** 便捷重载：不关心 trace 的调用方。 */
    default LlmResponse chat(LlmRequest request) {
        return chat(request, Tracer.noop());
    }

    /** 用于 trace 的客户端标识。 */
    default String clientName() {
        return getClass().getSimpleName();
    }
}
