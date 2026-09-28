package com.miniagent.llm;

import com.miniagent.error.AgentException;

/**
 * LLM 调用异常。子类区分「可重试」与「不可重试」，重试策略在
 * {@link OpenAiCompatibleClient} 内部实现。
 */
public class LlmException extends AgentException {

    private final boolean retryable;

    public LlmException(String code, String message, boolean retryable) {
        super(code, message);
        this.retryable = retryable;
    }

    public LlmException(String code, String message, boolean retryable, Throwable cause) {
        super(code, message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 网络层错误（连接超时、读超时、连接被重置）。 */
    public static LlmException network(String message, Throwable cause) {
        return new LlmException("LLM_NETWORK_ERROR", message, true, cause);
    }

    /** 限流 429。 */
    public static LlmException rateLimited(String message) {
        return new LlmException("LLM_RATE_LIMITED", message, true);
    }

    /** 服务端 5xx。 */
    public static LlmException serverError(String message) {
        return new LlmException("LLM_SERVER_ERROR", message, true);
    }

    /** 鉴权失败 401/403 —— 重试没意义。 */
    public static LlmException auth(String message) {
        return new LlmException("LLM_AUTH_ERROR", message, false);
    }

    /** 请求本身不合法 400/404/422。 */
    public static LlmException badRequest(String message) {
        return new LlmException("LLM_BAD_REQUEST", message, false);
    }

    /** 响应无法解析。 */
    public static LlmException badResponse(String message) {
        return new LlmException("LLM_BAD_RESPONSE", message, false);
    }
}
