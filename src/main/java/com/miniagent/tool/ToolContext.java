package com.miniagent.tool;

import com.miniagent.session.Session;
import com.miniagent.trace.Tracer;

/**
 * 工具执行上下文：把「这次调用属于谁」传给工具。
 *
 * <p>有了它，工具才能做到 session 级状态（例如待办列表写在当前 session 的工作记忆里），
 * 而不是全局共享 —— 这是多窗口互不干扰的前提。
 *
 * @param userId    用户标识
 * @param sessionId 会话标识
 * @param callId    本次工具调用 id（与 trace 关联）
 * @param session   会话对象，工具可读写其工作记忆
 * @param tracer    会话级 trace
 */
public record ToolContext(String userId, String sessionId, String callId, Session session, Tracer tracer) {

    public Tracer tracer() {
        return tracer == null ? Tracer.noop() : tracer;
    }
}
