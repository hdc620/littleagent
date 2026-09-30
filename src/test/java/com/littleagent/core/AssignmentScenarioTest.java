package com.littleagent.core;

import com.littleagent.llm.MockLlmClient;
import com.littleagent.session.Session;
import com.littleagent.tool.impl.DefaultTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端场景测试（离线，不消耗 API）：把题目里的验收场景固定成回归用例。
 *
 * <pre>
 * 用户 A 窗口 1：查天气 + 记待办       —— 一轮内多工具并发
 * 用户 A 窗口 2：写周报 + 记待办       —— 检索知识库 + 记待办
 * 窗口 1 追问（纯对话）                —— 跨轮次记忆
 * 窗口 1 追问（带工具）                —— 上下文指代 + 工具调用
 * 窗口 2 查待办                        —— session 隔离
 * 长对话                               —— 上下文压缩后记忆仍可召回
 * </pre>
 */
class AssignmentScenarioTest {

    private AgentRuntime runtime;

    @BeforeEach
    void setUp() {
        AgentConfig config = AgentConfig.builder()
                .mockMode(true)
                .logDir(null)
                .maxSteps(6)
                .maxContextTokens(1200)
                .keepRecentTurns(2)
                .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"))
                .build();
        runtime = AgentRuntime.builder(config)
                .llm(new MockLlmClient())
                .toolRegistry(DefaultTools.registry(config.knowledgeDir()))
                .summarizer(new com.littleagent.context.DeterministicSummarizer())
                .build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    @DisplayName("窗口 1：一轮内并发调用 weather + todo，并把结果回灌给模型")
    void windowOneAsksWeatherAndTodo() {
        AgentResult result = runtime.run("A", "w1", "帮我查一下北京今天的天气，然后记个待办：给张总发周报");

        assertEquals(AgentResult.Status.OK, result.status());
        List<String> tools = result.steps().get(0).toolCalls().stream()
                .map(com.littleagent.llm.ToolCall::name).toList();
        assertTrue(tools.containsAll(List.of("weather", "todo")), "实际调用：" + tools);
        assertTrue(result.answer().contains("北京"));
        assertTrue(result.answer().contains("给张总发周报"));
    }

    @Test
    @DisplayName("窗口 2：检索知识库模板 + 记一条属于窗口 2 的待办")
    void windowTwoSearchesDocsAndAddsTodo() {
        AgentResult result = runtime.run("A", "w2", "我要写本周周报，先查一下知识库里的周报模板，然后记个待办：周五前提交周报");

        List<String> tools = result.steps().get(0).toolCalls().stream()
                .map(com.littleagent.llm.ToolCall::name).toList();
        assertTrue(tools.contains("search"), "实际调用：" + tools);
        assertTrue(tools.contains("todo"), "实际调用：" + tools);
        assertTrue(result.steps().get(0).outcomes().stream().allMatch(AgentResult.ToolOutcome::ok));
    }

    @Test
    @DisplayName("追问（纯对话）：不调用工具也能答出之前记的待办")
    void followUpWithoutToolsRemembersTodo() {
        runtime.run("A", "w1", "帮我查一下北京今天的天气，然后记个待办：给张总发周报");
        AgentResult followUp = runtime.run("A", "w1", "我刚才让你记的待办是什么？");

        assertTrue(followUp.answer().contains("给张总发周报"), "实际回答：" + followUp.answer());
    }

    @Test
    @DisplayName("追问（带工具）：理解「它」指代 #1 并调用 todo(done)")
    void followUpWithToolUsesContext() {
        runtime.run("A", "w1", "帮我记个待办：给张总发周报");
        AgentResult followUp = runtime.run("A", "w1", "帮我把它标记成已完成");

        assertTrue(followUp.toolCallCount() >= 1);
        assertTrue(runtime.sessions().require("A", "w1").memory().todos().get(0).done(),
                "待办应被标记完成");
        assertTrue(followUp.answer().contains("已完成") || followUp.answer().contains("完成"));
    }

    @Test
    @DisplayName("session 隔离：窗口 2 看不到窗口 1 的待办，反之亦然")
    void sessionsDoNotLeakTodos() {
        runtime.run("A", "w1", "记个待办：给张总发周报");
        runtime.run("A", "w2", "记个待办：周五前提交周报");

        AgentResult window1 = runtime.run("A", "w1", "我有哪些待办？");
        AgentResult window2 = runtime.run("A", "w2", "我有哪些待办？");

        assertTrue(window1.answer().contains("给张总发周报"));
        assertFalse(window1.answer().contains("周五前提交周报"));
        assertTrue(window2.answer().contains("周五前提交周报"));
        assertFalse(window2.answer().contains("给张总发周报"));
    }

    @Test
    @DisplayName("多用户隔离：用户 B 无法访问用户 A 的窗口")
    void otherUserCannotAccessSession() {
        runtime.run("A", "w1", "记个待办：给张总发周报");

        AgentResult denied = runtime.run("B", "w1", "我有哪些待办？");
        assertTrue(denied.failed(), "越权访问应被拒绝");
        assertEquals("SESSION_ERROR", denied.errorCode());
        assertFalse(denied.answer().contains("给张总发周报"));

        AgentResult own = runtime.run("B", "b1", "记个待办：B 自己的任务");
        assertFalse(own.failed());
        assertEquals(1, runtime.sessions().list("A").size());
        assertEquals(1, runtime.sessions().list("B").size());
    }

    @Test
    @DisplayName("长对话：上下文被压缩后，早期待办依然通过工作记忆生效")
    void compactionKeepsLongTermMemory() {
        runtime.run("A", "w1", "帮我记个待办：给张总发周报");
        for (int i = 1; i <= 5; i++) {
            runtime.run("A", "w1", "第 " + i + " 个补充问题：请查一下上海明天的天气，并给出出行建议。");
        }
        Session session = runtime.sessions().require("A", "w1");

        assertTrue(session.summarizedUpTo() > 0, "应当已经触发压缩");
        assertFalse(session.summary().isBlank());
        assertTrue(session.memory().render().contains("给张总发周报"), "工作记忆不应被压缩");
        assertTrue(runtime.contextManager().estimate(session) > 0);

        AgentResult result = runtime.run("A", "w1", "我最早让你记的那条待办还在吗？");
        assertTrue(result.answer().contains("给张总发周报"), "实际回答：" + result.answer());
    }

    @Test
    @DisplayName("每一轮都产出可解释的 trace")
    void everyTurnIsTraceable() {
        runtime.run("A", "w1", "帮我算一下 (12+8)*3，再记个待办：对账");
        Session session = runtime.sessions().require("A", "w1");

        assertTrue(session.tracer().events().size() >= 8);
        assertTrue(session.tracer().events().stream().anyMatch(e -> e.type().equals("tool_result")));
        assertNotNull(session.tracer().render());
    }
}
