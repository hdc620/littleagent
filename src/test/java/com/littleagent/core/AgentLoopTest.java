package com.littleagent.core;

import com.littleagent.llm.LlmException;
import com.littleagent.llm.LlmResponse;
import com.littleagent.llm.Message;
import com.littleagent.llm.Role;
import com.littleagent.llm.ScriptedLlmClient;
import com.littleagent.llm.ToolCall;
import com.littleagent.session.Session;
import com.littleagent.trace.TraceEvent;
import com.littleagent.trace.TraceTypes;
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
        ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("你好，我是 little-agent。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "你好");

        assertEquals(AgentResult.Status.OK, result.status());
        assertEquals(1, result.steps().size());
        assertEquals(0, result.toolCallCount());
        assertEquals("你好，我是 little-agent。", result.answer());
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
    @DisplayName("一轮内一次请求发起多个工具调用（当前按顺序串行执行，尚未并行化）")
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
    @DisplayName("确定性兜底只汇总本轮的工具结果，不把上一轮的混进来")
    void deterministicWrapUpOnlySummarizesCurrentTurn() {
        // 第一轮：正常记一条待办（产生 todo 工具结果，留在历史里）
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("todo", "{\"action\":\"add\",\"item\":\"第一轮的任务\"}"),
                ScriptedLlmClient.text("已记录。"))
                .onExhausted(null);
        runtime = runtime(llm, 6);
        runtime.run("A", "w1", "记个待办：第一轮的任务");

        // 第二轮：模型请求工具后达到 maxSteps=1，强制收尾也失败 -> 走确定性兜底
        // 用一个新的 scripted client，第一个响应是工具调用，之后 null（收尾失败）
        ScriptedLlmClient llm2 = ScriptedLlmClient.create(
                ScriptedLlmClient.call("weather", "{\"city\":\"上海\"}"))
                .onExhausted(null);
        AgentConfig config = config(1).build();
        runtime = AgentRuntime.builder(config).llm(llm2)
                .toolRegistry(com.littleagent.tool.impl.DefaultTools.registry(null))
                .summarizer(new com.littleagent.context.DeterministicSummarizer()).build();

        AgentResult result = runtime.run("A", "w1", "查上海天气");

        assertEquals(AgentResult.Status.MAX_STEPS, result.status());
        // 关键断言：兜底文案只能含本轮的 weather（上海），不能含上一轮的 todo（第一轮的任务）
        assertTrue(result.answer().contains("上海"), "应汇总本轮 weather 结果");
        assertFalse(result.answer().contains("第一轮的任务"),
                "不应把上一轮的 todo 结果当成当前轮汇总给用户");
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
                .toolRegistry(com.littleagent.tool.impl.DefaultTools.registry(null))
                .summarizer(new com.littleagent.context.DeterministicSummarizer()).build();

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
    @DisplayName("空响应计数是「连续」而不是「累计」：中间有产出就归零")
    void emptyResponsesMustBeConsecutive() {
        // 空 -> 工具调用成功 -> 空 -> 正常回答
        // 早期实现里 emptyResponses 从不归零，第 3 步就被判 EMPTY_MODEL_OUTPUT 失败，
        // 而且第 2 步拿到的工具结果被直接丢掉（与注释/文档写的「连续两次」不符）。
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                LlmResponse.text(""),
                ScriptedLlmClient.call("calculator", "{\"expression\":\"1+1\"}"),
                LlmResponse.text(""),
                ScriptedLlmClient.text("1+1 等于 2。"));
        runtime = runtime(llm, 6);

        AgentResult result = runtime.run("A", "w1", "1+1 等于几");

        assertEquals(AgentResult.Status.OK, result.status(),
                "空响应之间夹着一次成功的工具调用，不应该被判失败");
        assertEquals("1+1 等于 2。", result.answer());
        assertEquals(1, result.toolCallCount(), "工具结果不应被丢弃");
        assertEquals(4, llm.callCount());
    }

    @Test
    @DisplayName("Runtime 被 close() 之后：工具故障收敛成结果，不抛异常、不毒化 session")
    void toolDispatchFailureDoesNotPoisonSession() {
        ScriptedLlmClient llm = ScriptedLlmClient.create(
                ScriptedLlmClient.call("calculator", "{\"expression\":\"1+1\"}"))
                .onExhausted(ScriptedLlmClient.text("好的。"));
        runtime = runtime(llm, 6);
        // 关掉 runtime => ToolInvoker 的线程池已 shutdown，
        // executor.submit 会抛 RejectedExecutionException（真实存在的故障路径）
        runtime.close();

        AgentResult result = runtime.run("A", "w1", "1+1 等于几");

        // 1) 不抛异常；工具故障被折叠成结构化结果并回灌，循环照常收尾
        assertEquals(AgentResult.Status.OK, result.status());
        assertEquals(1, result.toolCallCount());
        AgentResult.ToolOutcome outcome = result.steps().get(0).outcomes().get(0);
        assertFalse(outcome.ok());
        assertEquals("TOOL_EXECUTOR_CLOSED", outcome.errorCode());
        // 2) 配对不变式：每个 tool_call_id 都有对应的 tool 消息
        Session session = runtime.sessions().require("A", "w1");
        assertEquals(0, countOrphanToolCalls(session), "不应留下没有 tool 回应的 assistant(tool_calls)");
        // 3) session 没被毒化：再问一轮仍然能正常走完
        AgentResult next = runtime.run("A", "w1", "那 2+2 呢");
        assertEquals(AgentResult.Status.OK, next.status());
        assertEquals(0, countOrphanToolCalls(session));
    }

    @Test
    @DisplayName("写入侧守住配对不变式：补占位 tool 消息，消灭孤儿的 assistant(tool_calls)")
    void repairsOrphanToolCalls() {
        runtime = runtime(ScriptedLlmClient.create(ScriptedLlmClient.text("x")), 6);
        Session session = runtime.sessions().create("A", "w1", "t");
        ToolCall first = ToolCall.of("calculator", "{\"expression\":\"1+1\"}");
        ToolCall second = ToolCall.of("weather", "{\"city\":\"北京\"}");
        // 模拟「模型请求了两个工具，但执行段在第一个之前就抛了」的中间状态
        session.append(Message.user("查天气并算 1+1"));
        session.append(Message.assistantToolCalls(null, null, List.of(first, second)));

        AgentRuntime.appendUnansweredToolPlaceholders(session, List.of(first, second), 0,
                new IllegalStateException("模拟调度故障"));

        List<Message> history = session.history();
        assertEquals(4, history.size(), "1 条 user + 1 条 assistant(tool_calls) + 2 条占位 tool");
        assertEquals(Role.ASSISTANT, history.get(1).role());
        assertEquals(2, history.get(1).toolCalls().size());
        assertEquals(Role.TOOL, history.get(2).role());
        assertEquals(Role.TOOL, history.get(3).role());
        assertEquals(first.id(), history.get(2).toolCallId());
        assertEquals(second.id(), history.get(3).toolCallId());
        assertTrue(history.get(2).content().startsWith("[TOOL_ERROR]"));
        assertTrue(history.get(2).content().contains("未执行"));
        assertEquals(0, countOrphanToolCalls(session));

        // 只补「未回灌」的那部分：answered=1 时第一条不应被重复补
        Session second_ = runtime.sessions().create("A", "w2", "t");
        second_.append(Message.assistantToolCalls(null, null, List.of(first, second)));
        AgentRuntime.appendUnansweredToolPlaceholders(second_, List.of(first, second), 1,
                new IllegalStateException("模拟调度故障"));
        assertEquals(2, second_.historySize(), "已回灌的调用不应再补占位消息");
        assertEquals(second.id(), second_.history().get(1).toolCallId());
    }

    /** 统计「带 tool_calls 的 assistant 消息里，没有任何 tool 消息回应的调用」数量。 */
    private static int countOrphanToolCalls(Session session) {
        List<Message> history = session.history();
        java.util.Set<String> answered = new java.util.HashSet<>();
        for (Message m : history) {
            if (m.role() == Role.TOOL && m.toolCallId() != null) {
                answered.add(m.toolCallId());
            }
        }
        int orphans = 0;
        for (Message m : history) {
            if (m.role() == Role.ASSISTANT && m.hasToolCalls()) {
                for (ToolCall call : m.toolCalls()) {
                    if (!answered.contains(call.id())) {
                        orphans++;
                    }
                }
            }
        }
        return orphans;
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
                        com.littleagent.llm.Usage.of(1234, 56), null));
        runtime = runtime(llm, 6);

        runtime.run("A", "w1", "1+1");

        assertEquals(1234, runtime.sessions().require("A", "w1").lastPromptTokens());
    }

    // ------------------------------------------------------------------ 工具

    private boolean hasEvent(String sessionId, String type) {
        return runtime.sessions().require("A", sessionId).tracer().events().stream()
                .anyMatch(e -> e.type().equals(type));
    }

    private AgentRuntime runtime(com.littleagent.llm.LlmClient llm, int maxSteps) {
        AgentConfig config = config(maxSteps).build();
        return AgentRuntime.builder(config)
                .llm(llm)
                .toolRegistry(com.littleagent.tool.impl.DefaultTools.registry(null))
                .summarizer(new com.littleagent.context.DeterministicSummarizer())
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
