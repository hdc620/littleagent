package com.miniagent.core;

import com.miniagent.llm.LlmException;
import com.miniagent.llm.LlmResponse;
import com.miniagent.llm.Message;
import com.miniagent.llm.Role;
import com.miniagent.llm.ScriptedLlmClient;
import com.miniagent.llm.ToolCall;
import com.miniagent.session.Session;
import com.miniagent.trace.TraceEvent;
import com.miniagent.trace.TraceTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 主循环的行为测试。
 *
 * <p>用 {@link ScriptedLlmClient} 精确控制「模型每一步输出什么」，
 * 从而断言「Runtime 做了什么」—— 这是循环逻辑可以被测试的关键。
 */
class AgentLoopTest {

    private AgentRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    // ------------------------------------------------------------ step 1 / 4

    @Test
    @DisplayName("step 1+4：能直接回答时不调用工具，一轮结束")
    void directAnswerWithoutTools() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("你好，我是 mini-agent。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "你好");

        assertEquals(AgentResult.Status.OK, result.status());
        assertEquals(1, result.steps().size());
        assertEquals(0, result.toolCallCount());
        assertEquals("你好，我是 mini-agent。", result.answer());
        assertEquals(1, llm.callCount());
        // 历史：user + assistant
        List<Message> history = runtime.sessions().require("A", "w1").history();
        assertEquals(2, history.size());
        assertEquals(Role.USER, history.get(0).role());
        assertEquals(Role.ASSISTANT, history.get(1).role());
    }

    // ------------------------------------------------------- step 2 / 3 / 4

    @Test
    @DisplayName("step 2-4：模型请求工具 -> 执行 -> 结果回灌 -> 模型收尾")
    void executesToolAndFeedsResultBack() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("calculator", "{\"expression\":\"12*7\"}"),
                ScriptedLlmClient.text("12*7 等于 84。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "12*7 等于几");

        assertEquals(AgentResult.Status.OK, result.status());
        assertEquals(2, result.steps().size());
        assertEquals(1, result.toolCallCount());
        assertEquals("12*7 等于 84。", result.answer());
        assertEquals(1, result.steps().get(0).outcomes().size());
        assertTrue(result.steps().get(0).outcomes().get(0).ok());

        // 第二次请求必须包含工具结果，且仍然开放工具（多步任务可能还要继续调用）
        var secondRequest = llm.requests().get(1);
        List<String> toolMessages = secondRequest.messages().stream()
                .filter(m -> m.role() == Role.TOOL).map(Message::content).toList();
        assertEquals(1, toolMessages.size());
        assertTrue(toolMessages.get(0).contains("84"));
        assertEquals(5, secondRequest.tools().size());

        // 历史里必须保留 assistant(tool_calls) + tool 的配对（OpenAI 协议要求）
        List<Message> history = runtime.sessions().require("A", "w1").history();
        assertEquals(Role.ASSISTANT, history.get(1).role());
        assertTrue(history.get(1).hasToolCalls());
        assertEquals(Role.TOOL, history.get(2).role());
        assertEquals(history.get(1).toolCalls().get(0).id(), history.get(2).toolCallId());
    }

    @Test
    @DisplayName("一轮内可以并发发起多个工具调用")
    void executesMultipleToolsInOneStep() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.calls(
                        ToolCall.of("weather", "{\"city\":\"北京\"}"),
                        ToolCall.of("todo", "{\"action\":\"add\",\"item\":\"给张总发周报\"}")),
                ScriptedLlmClient.text("天气已查，待办已记。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "查北京天气并记待办");

        assertEquals(2, result.toolCallCount());
        assertEquals(2, result.steps().get(0).outcomes().size());
        assertTrue(result.steps().get(0).outcomes().stream().allMatch(AgentResult.ToolOutcome::ok));
    }

    // ------------------------------------------------------------- 边界与防护

    @Test
    @DisplayName("达到 maxSteps：禁用工具强制收尾，状态为 MAX_STEPS")
    void forcesWrapUpAtMaxSteps() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("weather", "{\"city\":\"北京\"}"),
                ScriptedLlmClient.call("weather", "{\"city\":\"上海\"}"),
                ScriptedLlmClient.text("基于已查到的情况：北京有雨，上海多云。"));
        runtime = runtime(llm, 2);

        AgentResult result = runtime.run("A", "w1", "帮我把所有城市天气都查一遍");

        assertEquals(AgentResult.Status.MAX_STEPS, result.status());
        assertEquals("MAX_STEPS_REACHED", result.errorCode());
        assertEquals("基于已查到的情况：北京有雨，上海多云。", result.answer());
        // 收尾那次请求必须不带工具
        assertTrue(llm.lastRequest().tools().isEmpty());
        assertTrue(hasEvent(result.sessionId(), TraceTypes.MAX_STEPS_REACHED));
    }

    @Test
    @DisplayName("强制收尾也失败时，返回确定性兜底文案（不抛异常）")
    void deterministicWrapUpWhenModelFails() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("weather", "{\"city\":\"北京\"}"))
                .onExhausted(null);
        runtime = runtime(llm, 1);

        AgentResult result = runtime.run("A", "w1", "查北京天气");

        assertEquals(AgentResult.Status.MAX_STEPS, result.status());
        assertTrue(result.answer().contains("已达到单轮工具调用上限"));
        assertTrue(result.answer().contains("北京"));
    }

    @Test
    @DisplayName("工具报错时循环继续，错误作为 observation 回灌给模型")
    void toolErrorIsFedBackAndLoopContinues() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("calculator", "{\"expression\":\"1/0\"}"),
                ScriptedLlmClient.text("这个表达式除数为 0，无法计算。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "帮我算 1/0");

        assertEquals(AgentResult.Status.OK, result.status());
        assertFalse(result.steps().get(0).outcomes().get(0).ok());
        assertEquals("EXPRESSION_INVALID", result.steps().get(0).outcomes().get(0).errorCode());
        String toolMessage = llm.requests().get(1).messages().stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow().content();
        assertTrue(toolMessage.startsWith("[TOOL_ERROR]"));
        assertTrue(toolMessage.contains("除数不能为 0"));
    }

    @Test
    @DisplayName("模型幻觉出不存在的工具：回灌可用工具列表")
    void unknownToolIsReportedBack() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("send_email", "{\"to\":\"boss\"}"),
                ScriptedLlmClient.text("抱歉，我暂时不能发邮件。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "给老板发邮件");

        assertEquals("UNKNOWN_TOOL", result.steps().get(0).outcomes().get(0).errorCode());
        assertTrue(result.answer().contains("抱歉"));
    }

    @Test
    @DisplayName("重复调用保护：相同工具 + 相同参数超过阈值被 Runtime 拦截")
    void blocksRepeatedIdenticalCalls() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("weather", "{\"city\":\"北京\"}"),
                ScriptedLlmClient.call("weather", "{\"city\":\"北京\"}"),
                ScriptedLlmClient.text("北京今天有阵雨。"));
        AgentConfig config = config(6).maxRepeatedToolCalls(1).build();
        runtime = AgentRuntime.builder(config).llm(llm)
                .toolRegistry(com.miniagent.tool.impl.DefaultTools.registry(null))
                .summarizer(new com.miniagent.context.DeterministicSummarizer()).build();

        AgentResult result = runtime.run("A", "w1", "查北京天气");

        assertEquals(2, result.toolCallCount());
        assertEquals("REPEATED_CALL", result.steps().get(1).outcomes().get(0).errorCode());
        assertTrue(hasEvent("w1", TraceTypes.TOOL_BLOCKED));
    }

    @Test
    @DisplayName("空响应自愈：注入系统提醒后继续，最多自愈一次")
    void repairsEmptyModelOutput() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                LlmResponse.text(""),
                ScriptedLlmClient.text("抱歉刚才没答上，北京今天有雨。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "北京天气");

        assertEquals(AgentResult.Status.OK, result.status());
        assertTrue(result.answer().contains("北京"));
        List<Message> history = runtime.sessions().require("A", "w1").history();
        assertTrue(history.stream().anyMatch(m -> m.content().contains("[系统] 你上一条回复为空")));
    }

    @Test
    @DisplayName("连续两次空响应判定失败，返回可读错误")
    void failsAfterTwoEmptyResponses() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(LlmResponse.text(""))
                .onExhausted(LlmResponse.text(" "));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "在吗");

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals("EMPTY_MODEL_OUTPUT", result.errorCode());
    }

    @Test
    @DisplayName("LLM 异常被收敛成失败结果，不向调用方抛异常")
    void llmFailureBecomesResult() {
        runtime = runtime((request, tracer) -> {
            throw LlmException.serverError("HTTP 503 服务端错误");
        }, 6);

        AgentResult result = runtime.run("A", "w1", "你好");

        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals("LLM_SERVER_ERROR", result.errorCode());
        assertTrue(result.answer().contains("503"));
    }

    @Test
    @DisplayName("未预期异常同样被收敛")
    void unexpectedExceptionBecomesResult() {
        runtime = runtime((request, tracer) -> {
            throw new IllegalStateException("序列化炸了");
        }, 6);

        AgentResult result = runtime.run("A", "w1", "你好");
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals("LLM_UNEXPECTED_ERROR", result.errorCode());
    }

    @Test
    @DisplayName("空输入直接拒绝，不产生任何模型调用")
    void rejectsBlankInput() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("x"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "   ");

        assertEquals("EMPTY_INPUT", result.errorCode());
        assertEquals(0, llm.callCount());
    }

    // ------------------------------------------------------------------ trace

    @Test
    @DisplayName("每一轮都留下完整 trace：user_input / context_built / llm_request / tool_* / final_answer")
    void recordsFullTrace() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("calculator", "{\"expression\":\"1+1\"}"),
                ScriptedLlmClient.text("等于 2"));
        runtime = runtime(llm, 6);

        runtime.run("A", "w1", "1+1");

        List<String> types = runtime.sessions().require("A", "w1").tracer().events()
                .stream().map(TraceEvent::type).toList();
        assertTrue(types.contains(TraceTypes.USER_INPUT));
        assertTrue(types.contains(TraceTypes.CONTEXT_BUILT));
        assertTrue(types.contains(TraceTypes.LLM_REQUEST));
        assertTrue(types.contains(TraceTypes.LLM_RESPONSE));
        assertTrue(types.contains(TraceTypes.TOOL_CALL));
        assertTrue(types.contains(TraceTypes.TOOL_RESULT));
        assertTrue(types.contains(TraceTypes.LOOP_STEP));
        assertTrue(types.contains(TraceTypes.FINAL_ANSWER));
    }

    @Test
    @DisplayName("trace 记录了压缩阈值用的真实 prompt_tokens")
    void recordsPromptTokens() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                new LlmResponse("等于 2", null, List.of(), "stop",
                        com.miniagent.llm.Usage.of(1234, 56), null));
        runtime = runtime(llm, 6);

        runtime.run("A", "w1", "1+1");

        assertEquals(1234, runtime.sessions().require("A", "w1").lastPromptTokens());
    }

    // ------------------------------------------------------------------ 工具

    private boolean hasEvent(String sessionId, String type) {
        return runtime.sessions().require("A", sessionId).tracer().events().stream()
                .anyMatch(e -> e.type().equals(type));
    }

    private AgentRuntime runtime(com.miniagent.llm.LlmClient llm, int maxSteps) {
        AgentConfig config = config(maxSteps).build();
        return AgentRuntime.builder(config)
                .llm(llm)
                .toolRegistry(com.miniagent.tool.impl.DefaultTools.registry(null))
                .summarizer(new com.miniagent.context.DeterministicSummarizer())
                .build();
    }

    /** 测试用配置：离线（不走真实 API）、不落盘 trace。 */
    static AgentConfig.Builder config(int maxSteps) {
        return AgentConfig.builder()
                .mockMode(true)
                .logDir(null)
                .maxSteps(maxSteps)
                .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"));
    }
}
