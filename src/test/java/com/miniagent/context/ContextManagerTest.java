package com.miniagent.context;

import com.miniagent.llm.Message;
import com.miniagent.llm.Role;
import com.miniagent.llm.ToolCall;
import com.miniagent.session.Session;
import com.miniagent.session.SessionManager;
import com.miniagent.trace.TraceTypes;
import com.miniagent.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文管理（记忆系统）的核心行为：
 * 三段式组装、按完整轮次切窗口、超预算压缩、工作记忆与摘要的注入、相关历史片段召回。
 */
class ContextManagerTest {

    private static final String SYSTEM_PROMPT = "你是测试用的 Agent。";

    /** 使用带内存 trace 的 Tracer 工厂，才能断言 memory_recall / context_compressed 事件 */
    private final SessionManager sessions = new SessionManager(Tracer::of);

    @Test
    @DisplayName("组装顺序：system prompt -> 摘要 -> 工作记忆 -> 召回片段 -> 对话消息（保序）")
    void assemblesInExpectedOrder() {
        Session session = sessions.create("A", "w1", "t");
        session.memory().addTodo("给张总发周报");
        session.updateSummary("用户此前问过天气", 0);
        appendTurn(session, "你好", "你好，有什么可以帮你？");

        ContextManager manager = manager(6000, 4, 3, 0);
        ContextPackage context = manager.build(session, SYSTEM_PROMPT, List.of());

        List<Message> messages = context.messages();
        assertTrue(messages.get(0).content().contains("你是测试用的 Agent"));
        assertTrue(messages.get(1).content().contains(ContextManager.SUMMARY_HEADER));
        assertTrue(messages.get(2).content().contains(ContextManager.WORKING_MEMORY_HEADER));
        assertTrue(messages.get(2).content().contains("给张总发周报"));
        // 最后两条必须是原始对话，且顺序不变
        assertEquals(Role.USER, messages.get(messages.size() - 2).role());
        assertEquals(Role.ASSISTANT, messages.get(messages.size() - 1).role());
        assertTrue(context.stats().hasSummary());
        assertTrue(context.stats().hasWorkingMemory());
    }

    @Test
    @DisplayName("窗口按完整轮次切分：只保留最近 N 轮原文，其余滑出")
    void keepsOnlyRecentTurnsVerbatim() {
        Session session = sessions.create("A", "w1", "t");
        for (int i = 1; i <= 5; i++) {
            appendTurn(session, "第 " + i + " 轮提问", "第 " + i + " 轮回答");
        }

        ContextPackage context = manager(6000, 2, 0, 0).build(session, SYSTEM_PROMPT, List.of());

        assertEquals(10, context.stats().historyMessages());
        assertEquals(4, context.stats().verbatimMessages());
        assertEquals(6, context.stats().droppedMessages());
        String all = String.join("\n", context.messages().stream().map(Message::content).toList());
        assertFalse(all.contains("第 1 轮提问"));
        assertFalse(all.contains("第 3 轮提问"));
        assertTrue(all.contains("第 4 轮提问"));
        assertTrue(all.contains("第 5 轮提问"));
    }

    @Test
    @DisplayName("窗口不会切散 assistant(tool_calls) 与 tool 的配对（否则 API 会 400）")
    void neverSplitsToolCallPairs() {
        Session session = sessions.create("A", "w1", "t");
        appendTurn(session, "第一轮问题", "第一轮回答");
        session.append(Message.assistantToolCalls("查一下", null,
                List.of(ToolCall.of("calculator", "{\"expression\":\"1+1\"}"))));
        session.append(Message.tool("call_1", "calculator", "1+1 = 2"));
        session.append(Message.assistant("结果是 2", null));
        appendTurn(session, "第二轮问题", "第二轮回答");

        ContextPackage context = manager(6000, 1, 0, 0).build(session, SYSTEM_PROMPT, List.of());

        List<Message> messages = context.messages();
        int firstNonSystem = 0;
        while (messages.get(firstNonSystem).role() == Role.SYSTEM) {
            firstNonSystem++;
        }
        // 窗口起点必须是 user 消息，且后续 tool 消息都能找到配对的 assistant(tool_calls)
        assertEquals(Role.USER, messages.get(firstNonSystem).role());
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role() == Role.TOOL) {
                assertTrue(i > 0 && messages.get(i - 1).role() == Role.ASSISTANT
                                || messages.get(i - 1).role() == Role.TOOL,
                        "tool 消息前必须是 assistant(tool_calls) 或另一个 tool 消息");
            }
        }
    }

    @Test
    @DisplayName("超过预算触发压缩：历史被压成摘要，压缩位点前移")
    void compactsWhenOverBudget() {
        Session session = sessions.create("A", "w1", "t");
        for (int i = 1; i <= 4; i++) {
            appendTurn(session, "第 " + i + " 轮提问：" + "内容".repeat(50), "第 " + i + " 轮回答：" + "答复".repeat(50));
        }
        // 固定开销 1000 + 预算 1000：任何历史都会越界
        ContextManager manager = manager(1000, 1, 0, 1000);

        assertTrue(manager.compactIfNeeded(session));
        assertFalse(session.summary().isBlank());
        assertTrue(session.summarizedUpTo() > 0);
        assertEquals(6, session.summarizedUpTo(), "压缩应停在最近 1 轮的 user 消息边界");
        assertTrue(session.tracer().events().stream()
                .anyMatch(e -> e.type().equals(TraceTypes.CONTEXT_COMPRESSED)));
        // 再压一次不应有变化（没有新内容）
        assertFalse(manager.compactIfNeeded(session));
    }

    @Test
    @DisplayName("压缩后仍保留最近轮次原文，工作记忆与摘要继续注入")
    void keepsRecentTurnsAndMemoryAfterCompaction() {
        Session session = sessions.create("A", "w1", "t");
        session.memory().addTodo("给张总发周报");
        for (int i = 1; i <= 4; i++) {
            appendTurn(session, "第 " + i + " 轮提问：" + "内容".repeat(50), "第 " + i + " 轮回答：" + "答复".repeat(50));
        }
        ContextManager manager = manager(1000, 1, 0, 1000);
        manager.compactIfNeeded(session);

        ContextPackage context = manager.build(session, SYSTEM_PROMPT, List.of());
        String systemBlocks = context.messages().stream()
                .filter(m -> m.role() == Role.SYSTEM).map(Message::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        String conversation = context.messages().stream()
                .filter(m -> m.role() != Role.SYSTEM).map(Message::content)
                .collect(java.util.stream.Collectors.joining("\n"));

        assertTrue(systemBlocks.contains("给张总发周报"), "工作记忆必须每轮注入");
        assertTrue(context.stats().hasSummary(), "摘要必须每轮注入");
        assertEquals(2, context.stats().verbatimMessages(), "只保留最近 1 轮原文");
        assertTrue(conversation.contains("第 4 轮提问"), "最近一轮必须保留原文");
        assertFalse(conversation.contains("第 1 轮提问"), "久远轮次已滑出窗口（只留在摘要里）");
    }

    @Test
    @DisplayName("固定开销（工具 Schema）计入估算，避免压缩触发过晚")
    void countsStaticOverheadInEstimate() {
        Session session = sessions.create("A", "w1", "t");
        appendTurn(session, "短问题", "短回答");

        int withoutOverhead = manager(6000, 4, 0, 0).estimate(session);
        int withOverhead = manager(6000, 4, 0, 1500).estimate(session);
        assertEquals(1500, withOverhead - withoutOverhead);
    }

    @Test
    @DisplayName("压缩信号优先采用 API 上报的真实 prompt_tokens")
    void usesRealPromptTokensAsSignal() {
        Session session = sessions.create("A", "w1", "t");
        for (int i = 1; i <= 6; i++) {
            appendTurn(session, "第 " + i + " 轮提问", "第 " + i + " 轮回答");
        }
        ContextManager manager = manager(6000, 4, 0, 0);
        int estimated = manager.compactionSignal(session);
        assertTrue(estimated < 9000, "本地估算应远小于真实用量");

        session.lastPromptTokens(9000);
        assertEquals(9000, manager.compactionSignal(session));
        assertTrue(manager.compactIfNeeded(session), "真实用量超预算时必须压缩");
        assertTrue(session.summarizedUpTo() > 0);
    }

    @Test
    @DisplayName("历史 assistant 的长正文被截断，本轮保持原样")
    void trimsHistoricalAssistantContent() {
        Session session = sessions.create("A", "w1", "t");
        String longAnswer = "很长的历史回答".repeat(100);
        appendTurn(session, "第一轮", longAnswer);            // 历史轮：应被截断
        appendTurn(session, "第二轮（本轮）", longAnswer);     // 本轮：保持原样

        ContextPackage context = manager(6000, 2, 0, 0).build(session, SYSTEM_PROMPT, List.of());

        List<Message> assistants = context.messages().stream()
                .filter(m -> m.role() == Role.ASSISTANT).toList();
        assertEquals(2, assistants.size(), "两轮原文都应在窗口内");
        assertTrue(assistants.get(0).content().contains("已截断"), "历史轮应被截断");
        assertTrue(assistants.get(1).content().length() > 500, "本轮不截断");
    }

    @Test
    @DisplayName("情节记忆召回：久远的相关片段被召回并标注来源")
    void recallsRelevantFragments() {
        Session session = sessions.create("A", "w1", "t");
        appendTurn(session, "帮我查一下杭州的天气", "杭州今天多云，18~25℃。");
        appendTurn(session, "再帮我记个待办：买咖啡", "已记录待办。");
        // 当前问题（召回 query）与第一轮相关；第二轮已滑出窗口，靠召回补回细节
        appendTurn(session, "杭州那边现在适合出门吗？", "（占位回答）");

        ContextManager manager = manager(6000, 1, 3, 0);
        ContextPackage context = manager.build(session, SYSTEM_PROMPT, List.of());

        assertTrue(context.stats().recalledFragments() >= 1);
        String all = String.join("\n", context.messages().stream().map(Message::content).toList());
        assertTrue(all.contains("杭州今天多云"));
        assertTrue(session.tracer().events().stream()
                .anyMatch(e -> e.type().equals(TraceTypes.MEMORY_RECALL)));
    }

    @Test
    @DisplayName("force 压缩在没有足够轮次时安全返回 false")
    void forceCompactWithoutEnoughTurns() {
        Session session = sessions.create("A", "w1", "t");
        appendTurn(session, "只有一轮", "回答");
        assertFalse(manager(6000, 4, 0, 0).compact(session, true));
        assertTrue(session.summary().isBlank());
    }

    @Test
    @DisplayName("空会话也能组装出合法上下文（只有 system prompt）")
    void handlesEmptyHistory() {
        Session session = sessions.create("A", "w1", "t");
        ContextPackage context = manager(6000, 4, 3, 0).build(session, SYSTEM_PROMPT, List.of());
        assertEquals(1, context.messages().size());
        assertEquals(0, context.stats().historyMessages());
    }

    // ------------------------------------------------------------------ 工具

    private ContextManager manager(int maxContextTokens, int keepRecentTurns, int recallTopK, int staticOverhead) {
        return new ContextManager(new DeterministicSummarizer(), maxContextTokens, keepRecentTurns, recallTopK,
                400, 800, staticOverhead);
    }

    private static void appendTurn(Session session, String user, String assistant) {
        session.append(Message.user(user));
        session.append(Message.assistant(assistant, null));
    }
}
