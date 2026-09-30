package com.littleagent.session;

import java.time.Instant;

/**
 * 待办条目。作为「结构化工作记忆」的示例：它由工具写入、由 Runtime 每轮注入上下文，
 * 因此跨轮次、跨窗口都不会丢，并且天然按 session 隔离。
 */
public record TodoItem(int id, String text, boolean done, Instant createdAt) {

    public TodoItem complete() {
        return new TodoItem(id, text, true, createdAt);
    }

    public String render() {
        return "#" + id + " " + text + (done ? " [已完成]" : " [未完成]");
    }
}
