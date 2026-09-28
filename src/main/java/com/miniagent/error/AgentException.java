package com.miniagent.error;

/**
 * 项目统一异常基类。
 *
 * <p>约定：{@code code} 是稳定的机器可读错误码，会写进 trace，方便线上定位与统计。
 */
public class AgentException extends RuntimeException {

    private final String code;

    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
