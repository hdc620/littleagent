package com.miniagent.error;

/**
 * session 访问异常：请求的 sessionId 不存在，或不属于该 userId。
 *
 * <p>多用户隔离的关键防线：用户 A 不能通过猜 id 读到用户 B 的会话。
 */
public class SessionAccessException extends AgentException {

    public SessionAccessException(String message) {
        super("SESSION_ACCESS_DENIED", message);
    }
}
