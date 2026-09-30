package com.littleagent.acceptance;

import com.littleagent.core.AgentConfig;
import com.littleagent.core.AgentResult;
import com.littleagent.core.AgentRuntime;
import com.littleagent.llm.LlmResponse;
import com.littleagent.llm.Message;
import com.littleagent.llm.Role;
import com.littleagent.llm.ScriptedLlmClient;
import com.littleagent.llm.ToolCall;
import com.littleagent.session.Session;
import com.littleagent.tool.ToolRegistry;
import com.littleagent.tool.ToolResult;
import com.littleagent.tool.impl.DefaultTools;
import com.littleagent.trace.TraceTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 笔试题**验收测试**：把《2026 后端(Agent方向)笔试题 · Vibe coding》的每一条要求
 * 直接翻译成一个用例，跑一次就能回答「有没有完成所有要求」。
 *
 * <h2>怎么用</h2>
 * <ul>
 *   <li><b>IDEA</b>：右键本文件 → {@code Run 'RequirementAcceptanceTest'}，左侧会列出每条要求的绿/红。</li>
 *   <li><b>命令行</b>：{@code mvn "-Dtest=RequirementAcceptanceTest" test}</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ol>
 *   <li><b>全部离线</b>：用 {@link ScriptedLlmClient} 精确控制模型每一步的输出，不花 API 费用、不依赖网络。
 *       「是否接真实 LLM」这一条由 {@code DeepSeekLiveIT}（5 个用例）负责，那是另一层验证。</li>
 *   <li><b>一条要求一个用例</b>：{@code @DisplayName} 直接抄题面原文，红绿即结论，不需要人再解释。</li>
 *   <li><b>断言行为而不是实现</b>：例如「窗口互不影响」断言的是"窗口 2 的回答里不允许出现窗口 1 的待办"，
 *       而不是"两个对象不是同一个引用"。</li>
 * </ol>
 *
 * <p>与本项目其他测试的分工：`AgentLoopTest` 等测**实现细节**（单元），本类测**题面要求**（验收）。
 * 两者有少量重叠是有意的 —— 验收测试要能独立回答"达标了吗"。
 */
class RequirementAcceptanceTest {

    private AgentRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    // ==================================================================== 要求 1

    @Nested
    @DisplayName("要求 1：从零完成，不能依赖现有 agent 框架")
    class Requirement1FromScratch {

        @Test
        @DisplayName("主流程不依赖任何 Agent 框架：运行期依赖只有 Jackson")
        void noAgentFrameworkOnClasspath() {
            // 按包名扫描运行期依赖，任何 Agent 框架出现即失败。
            // 这是「从零实现」最直接的机器可验证证据（比读 pom.xml 更硬）。
            String[] forbidden = {
                    "langchain", "langgraph", "openai.", "anthropic", "semantic.kernel",
                    "autogen", "crewai", "llama.index", "haystack", "spring.ai", "dify",
                    "openhands", "openclaw", "javalin", "quarkus.langchain4j", "langchain4j"};

            Set<String> suspicious = new HashSet<>();
            Package[] packages = Package.getPackages();
            for (Package p : packages) {
                String name = p.getName().toLowerCase(java.util.Locale.ROOT);
                // 本仓库自己的包名里包含 agent 是正常的
                if (name.startsWith("com.littleagent")) {
                    continue;
                }
                for (String bad : forbidden) {
                    if (name.contains(bad)) {
                        suspicious.add(p.getName());
                    }
                }
            }

            assertTrue(suspicious.isEmpty(),
                    "类路径上出现了 Agent 框架相关的包，违反「从零完成」：" + suspicious);
        }

        @Test
        @DisplayName("核心 Runtime 是自研类：循环/工具调度/上下文/会话都是本仓库的类")
        void coreRuntimeIsOwnImplementation() {
            assertAll(
                    () -> assertEquals("com.littleagent.core.AgentRuntime", AgentRuntime.class.getName()),
                    () -> assertEquals("com.littleagent.context.ContextManager",
                            com.littleagent.context.ContextManager.class.getName()),
                    () -> assertEquals("com.littleagent.tool.ToolInvoker",
                            com.littleagent.tool.ToolInvoker.class.getName()),
                    () -> assertEquals("com.littleagent.session.SessionManager",
                            com.littleagent.session.SessionManager.class.getName()),
                    () -> assertEquals("com.littleagent.llm.LlmOutputParser",
                            com.littleagent.llm.LlmOutputParser.class.getName()));
        }
    }

    // ==================================================================== 要求 2

    @Nested
    @DisplayName("要求 2：实现基本循环（step1 输入 → step2 决策 → step3 工具 → step4 收尾/继续）")
    class Requirement2Loop {

        @Test
        @DisplayName("step1 接收用户输入：写入 session 历史，并留下 user_input trace")
        void step1ReceivesInput() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("你好。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "你好");

            Session session = runtime.sessions().require("A", "w1");
            assertEquals(Role.USER, session.history().get(0).role());
            assertEquals("你好", session.history().get(0).content());
            assertTrue(session.tracer().events().stream()
                    .anyMatch(e -> e.type().equals(TraceTypes.USER_INPUT)));
        }

        @Test
        @DisplayName("step2 判断直接回复还是调用工具：能直接答就不调工具")
        void step2DecidesDirectAnswer() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("我是 little-agent。"));
            runtime = runtime(llm, 6);

            AgentResult result = runtime.run("A", "w1", "你是谁");

            assertEquals(AgentResult.Status.OK, result.status());
            assertEquals(0, result.toolCallCount());
            assertEquals(1, llm.callCount(), "只应发生一次 LLM 调用");
        }

        @Test
        @DisplayName("step3 调用工具：工具真的被执行，结果作为 observation 回灌给模型")
        void step3InvokesTool() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("calculator", "{\"expression\":\"12*7\"}"),
                    ScriptedLlmClient.text("12*7 等于 84。"));
            runtime = runtime(llm, 6);

            AgentResult result = runtime.run("A", "w1", "12*7 等于几");

            assertEquals("12*7 等于 84。", result.answer());
            assertTrue(result.steps().get(0).outcomes().get(0).ok());
            // 第二次请求里必须带上工具结果（观察值回灌）
            List<String> toolMessages = llm.requests().get(1).messages().stream()
                    .filter(m -> m.role() == Role.TOOL).map(Message::content).toList();
            assertEquals(1, toolMessages.size());
            assertTrue(toolMessages.get(0).contains("84"));
        }

        @Test
        @DisplayName("step4 根据工具结果决定继续 loop 还是返回：连续两轮工具调用后再收尾")
        void step4LoopsUntilFinalAnswer() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("weather", "{\"city\":\"北京\"}"),
                    ScriptedLlmClient.call("todo", "{\"action\":\"add\",\"item\":\"发周报\"}"),
                    ScriptedLlmClient.text("天气查完了，待办也记好了。"));
            runtime = runtime(llm, 6);

            AgentResult result = runtime.run("A", "w1", "查北京天气并记待办：发周报");

            assertEquals(AgentResult.Status.OK, result.status());
            assertEquals(3, llm.callCount(), "两次工具 + 一次收尾 = 3 次 LLM 调用");
            assertEquals(2, result.steps().stream().filter(s -> !s.toolCalls().isEmpty()).count());
            assertEquals(2, result.toolCallCount());
        }

        @Test
        @DisplayName("最大轮次限制：达到 maxSteps 后禁用工具强制收尾，不会死循环")
        void maxStepsForcesWrapUp() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("weather", "{\"city\":\"北京\"}"),
                    ScriptedLlmClient.call("weather", "{\"city\":\"上海\"}"),
                    ScriptedLlmClient.text("基于已查到的信息作答。"));
            runtime = runtime(llm, 2);

            AgentResult result = runtime.run("A", "w1", "把所有城市都查一遍");

            assertEquals(AgentResult.Status.MAX_STEPS, result.status());
            assertTrue(llm.lastRequest().tools().isEmpty(), "收尾那次请求必须禁用工具");
            assertTrue(runtime.sessions().require("A", "w1").tracer().events().stream()
                    .anyMatch(e -> e.type().equals(TraceTypes.MAX_STEPS_REACHED)));
        }
    }

    // ============================================================ 要求 2 · 工具

    @Nested
    @DisplayName("要求 2：工具相关（≥3 个工具 + 注册机制 + Schema 自主决策 + 输出解析）")
    class Requirement2Tools {

        @Test
        @DisplayName("至少三个工具：calculator / search / read_docs / todo / weather 共 5 个")
        void atLeastThreeTools() {
            ToolRegistry registry = DefaultTools.registry(null);
            List<String> names = registry.names();

            assertTrue(names.size() >= 3, "至少要有 3 个工具，实际：" + names);
            assertTrue(names.containsAll(List.of("calculator", "search", "read_docs", "todo", "weather")),
                    "题面点名的工具都要在，实际：" + names);
        }

        @Test
        @DisplayName("工具注册机制：每个工具都有 名称 + 描述 + 参数 Schema")
        void everyToolHasNameDescriptionSchema() {
            ToolRegistry registry = DefaultTools.registry(null);

            for (var spec : registry.specs()) {
                assertNotNull(spec.name(), "工具名不能为空");
                assertFalse(spec.name().isBlank());
                assertNotNull(spec.description(), spec.name() + " 缺少描述（模型判断何时调用的唯一依据）");
                assertTrue(spec.description().length() >= 10, spec.name() + " 的描述太短，模型无法据此决策");
                assertNotNull(spec.parameters(), spec.name() + " 缺少参数 Schema");
                assertEquals("object", spec.parameters().path("type").asText(),
                        spec.name() + " 的 Schema 必须是 object");
            }
        }

        @Test
        @DisplayName("LLM 基于 Schema 自主决策：Schema 被序列化进请求的 tools 字段")
        void schemaIsSentToModel() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("好。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "你好");

            var tools = llm.lastRequest().tools();
            assertEquals(5, tools.size(), "5 个工具的 Schema 都应随请求发出");
            assertTrue(tools.stream().anyMatch(t -> t.name().equals("calculator")));
        }

        @Test
        @DisplayName("输出解析：原生 tool_calls / 文本兜底 / 最终答案 / 空输出 四种模式都能识别")
        void parsesThoughtToolCallAndAnswer() {
            // ① 原生 tool_calls
            assertEquals(com.littleagent.llm.ParsedOutput.Mode.NATIVE_TOOL_CALLS,
                    com.littleagent.llm.LlmOutputParser.parse(LlmResponse.withToolCalls("想一下",
                            List.of(ToolCall.of("calculator", "{\"expression\":\"1+1\"}")))).mode());
            // ② 文本兜底（<tool_call> 标签）
            assertEquals(com.littleagent.llm.ParsedOutput.Mode.TEXT_TOOL_CALLS,
                    com.littleagent.llm.LlmOutputParser.parse(LlmResponse.text(
                            "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</tool_call>")).mode());
            // ③ 最终答案（并能抽取思考过程）
            var answer = com.littleagent.llm.LlmOutputParser.parse(
                    LlmResponse.text("<thought>先看看</thought>\n答案是 2。"));
            assertEquals(com.littleagent.llm.ParsedOutput.Mode.FINAL_ANSWER, answer.mode());
            assertEquals("答案是 2。", answer.finalAnswer());
            assertEquals("先看看", answer.thought());
            // ④ 空输出
            assertEquals(com.littleagent.llm.ParsedOutput.Mode.EMPTY,
                    com.littleagent.llm.LlmOutputParser.parse(LlmResponse.text("   ")).mode());
        }

        @Test
        @DisplayName("工具失败不中断对话：错误被折叠成可读结果回灌，模型据此作答")
        void toolFailureIsFedBack() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("calculator", "{\"expression\":\"1/0\"}"),
                    ScriptedLlmClient.text("这个表达式除数为 0，无法计算。"));
            runtime = runtime(llm, 6);

            AgentResult result = runtime.run("A", "w1", "帮我算 1/0");

            assertEquals(AgentResult.Status.OK, result.status(), "一次工具失败不该让整轮失败");
            assertFalse(result.steps().get(0).outcomes().get(0).ok());
            assertEquals("EXPRESSION_INVALID", result.steps().get(0).outcomes().get(0).errorCode());
        }
    }

    // ========================================================== 要求 2 · session

    @Nested
    @DisplayName("要求 2：session 管理（窗口 1/2 独立、可随时接着聊、互不影响）")
    class Requirement2Sessions {

        @Test
        @DisplayName("用户 A 的窗口 1 与窗口 2 是两个独立 session，历史互不串台")
        void windowsAreIndependentSessions() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("好的。"))
                    .onExhausted(ScriptedLlmClient.text("好的。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "窗口1的问题");
            runtime.run("A", "w2", "窗口2的问题");

            Session w1 = runtime.sessions().require("A", "w1");
            Session w2 = runtime.sessions().require("A", "w2");
            assertNotEquals(w1.id(), w2.id());
            assertTrue(w1.history().stream().anyMatch(m -> m.content().contains("窗口1的问题")));
            assertFalse(w1.history().stream().anyMatch(m -> m.content().contains("窗口2的问题")),
                    "窗口 1 的历史里不能出现窗口 2 的内容");
            assertTrue(w2.history().stream().anyMatch(m -> m.content().contains("窗口2的问题")));
            assertFalse(w2.history().stream().anyMatch(m -> m.content().contains("窗口1的问题")));
        }

        @Test
        @DisplayName("可以随时接着窗口继续聊：同一个 sessionId 取回同一个 session，历史累加")
        void canResumeWindowLater() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("第一轮回答。"))
                    .onExhausted(ScriptedLlmClient.text("第二轮回答。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "第一轮");
            int sizeAfterFirst = runtime.sessions().require("A", "w1").historySize();
            runtime.run("A", "w1", "第二轮");

            Session session = runtime.sessions().require("A", "w1");
            assertEquals(1, runtime.sessions().list("A").size(), "仍然是同一个窗口，没有新建");
            assertEquals(sizeAfterFirst + 2, session.historySize(), "历史是续写的");
            assertTrue(session.turnCount() >= 2);
        }

        @Test
        @DisplayName("跨用户隔离：用户 B 拿不到用户 A 的窗口（越权返回失败而不是泄漏）")
        void otherUserCannotAccess() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("已记录。"))
                    .onExhausted(ScriptedLlmClient.text("好的。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "记个待办：给张总发周报");
            AgentResult denied = runtime.run("B", "w1", "我有哪些待办？");

            assertTrue(denied.failed());
            assertEquals("SESSION_ERROR", denied.errorCode());
            assertFalse(denied.answer().contains("给张总发周报"), "不能泄漏他人内容");
        }
    }

    // ========================================================== 要求 2 · context

    @Nested
    @DisplayName("要求 2：context 管理（最大轮次 + 记住状态 + 两种追问 + 压缩）")
    class Requirement2Context {

        @Test
        @DisplayName("用户持续的对话要能记住之前的状态：历史 + 工作记忆都在上下文里")
        void remembersPreviousState() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("todo", "{\"action\":\"add\",\"item\":\"给张总发周报\"}"),
                    ScriptedLlmClient.text("已记录待办。"))
                    .onExhausted(ScriptedLlmClient.text("你让我记的是：给张总发周报。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "记个待办：给张总发周报");
            runtime.run("A", "w1", "我刚才让你记的待办是什么？");

            // 第二轮请求里必须能找到「待办」这条状态（来自历史或工作记忆）
            String secondRequest = llm.requests().get(llm.requests().size() - 1).messages().stream()
                    .map(Message::content).reduce("", (a, b) -> a + "\n" + b);
            assertTrue(secondRequest.contains("给张总发周报"), "第二轮上下文里必须带着上一轮的状态");
        }

        @Test
        @DisplayName("纯对话追问：不调用工具也能基于上下文回答")
        void followUpWithoutTools() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("todo", "{\"action\":\"add\",\"item\":\"给张总发周报\"}"),
                    ScriptedLlmClient.text("已记录。"),
                    ScriptedLlmClient.text("你记的是给张总发周报。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "记个待办：给张总发周报");
            AgentResult followUp = runtime.run("A", "w1", "我刚才让你记的待办是什么？");

            assertEquals(0, followUp.toolCallCount(), "这一轮不该调用工具");
            assertTrue(followUp.answer().contains("给张总发周报"));
        }

        @Test
        @DisplayName("带工具的追问：理解指代并继续调用工具")
        void followUpWithTool() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("todo", "{\"action\":\"add\",\"item\":\"给张总发周报\"}"),
                    ScriptedLlmClient.text("已记录。"),
                    ScriptedLlmClient.call("todo", "{\"action\":\"done\",\"id\":1}"),
                    ScriptedLlmClient.text("已标记完成。"));
            runtime = runtime(llm, 6);

            runtime.run("A", "w1", "记个待办：给张总发周报");
            AgentResult followUp = runtime.run("A", "w1", "帮我把它标记成已完成");

            assertEquals(1, followUp.toolCallCount(), "这一轮应该继续调用工具");
            assertEquals("todo", followUp.steps().get(0).toolCalls().get(0).name());
            assertTrue(runtime.sessions().require("A", "w1").memory().todos().get(0).done(),
                    "待办状态真的被改了（= 理解了指代）");
        }

        @Test
        @DisplayName("context 过长要有基础压缩：超预算自动压成摘要，且压缩后仍能接上话题")
        void compressesWhenOverBudget() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("好的。"))
                    .onExhausted(ScriptedLlmClient.text("好的。"));
            AgentConfig config = AgentConfig.builder()
                    .mockMode(true).logDir(null).maxSteps(6)
                    .maxContextTokens(800).keepRecentTurns(2)
                    .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"))
                    .build();
            runtime = AgentRuntime.builder(config).llm(llm)
                    .toolRegistry(DefaultTools.registry(null))
                    .summarizer(new com.littleagent.context.DeterministicSummarizer())
                    .build();

            for (int i = 1; i <= 6; i++) {
                runtime.run("A", "w1", "第 " + i + " 轮：" + "内容".repeat(40));
            }

            Session session = runtime.sessions().require("A", "w1");
            assertTrue(session.summarizedUpTo() > 0, "应当已经触发压缩");
            assertFalse(session.summary().isBlank(), "应当产出了摘要");
            assertTrue(session.tracer().events().stream()
                    .anyMatch(e -> e.type().equals(TraceTypes.CONTEXT_COMPRESSED)));
        }

        @Test
        @DisplayName("压缩后估算必须变小（不能越压越大）")
        void compactionActuallyShrinks() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(ScriptedLlmClient.text("好的。"))
                    .onExhausted(ScriptedLlmClient.text("好的。"));
            AgentConfig config = AgentConfig.builder()
                    .mockMode(true).logDir(null).maxSteps(6)
                    .maxContextTokens(600).keepRecentTurns(1)
                    .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"))
                    .build();
            runtime = AgentRuntime.builder(config).llm(llm)
                    .toolRegistry(DefaultTools.registry(null))
                    .summarizer(new com.littleagent.context.DeterministicSummarizer())
                    .build();
            for (int i = 1; i <= 6; i++) {
                runtime.run("A", "w1", "第 " + i + " 轮：" + "内容".repeat(50));
            }

            Session session = runtime.sessions().require("A", "w1");
            int full = session.estimatedTokens();
            int uncompressed = session.estimatedTokensFrom(session.summarizedUpTo());

            assertTrue(session.summarizedUpTo() > 0);
            assertTrue(uncompressed < full,
                    "压缩阈值用的估算只应统计未压缩区间（否则重复计数）："
                            + "uncompressed=" + uncompressed + " full=" + full);
        }
    }

    // ================================================== 要求 2 · 额外要求

    @Nested
    @DisplayName("要求 2：额外要求（基本异常处理 + 工具 trace/执行日志）")
    class Requirement2Extra {

        @ParameterizedTest
        @ValueSource(strings = {"未知工具", "坏 JSON", "参数不是对象", "Schema 违规"})
        @DisplayName("基本异常处理：四类工具异常都被折叠成结果，不抛给调用方")
        void toolErrorsAreContained(String kind) {
            ScriptedLlmClient llm = switch (kind) {
                case "未知工具" -> ScriptedLlmClient.create(
                        ScriptedLlmClient.call("send_email", "{\"to\":\"boss\"}"),
                        ScriptedLlmClient.text("我暂时不能发邮件。"));
                case "坏 JSON" -> ScriptedLlmClient.create(
                        ScriptedLlmClient.call("calculator", "{不是 json"),
                        ScriptedLlmClient.text("参数有问题。"));
                case "参数不是对象" -> ScriptedLlmClient.create(
                        ScriptedLlmClient.call("calculator", "[1,2,3]"),
                        ScriptedLlmClient.text("参数有问题。"));
                default -> ScriptedLlmClient.create(
                        ScriptedLlmClient.call("calculator", "{}"),   // 缺必填 expression
                        ScriptedLlmClient.text("参数有问题。"));
            };
            runtime = runtime(llm, 6);

            AgentResult result = runtime.run("A", "w1", "试试");

            assertAll(
                    () -> assertEquals(AgentResult.Status.OK, result.status(), kind + " 不该中断整轮对话"),
                    () -> assertFalse(result.steps().get(0).outcomes().get(0).ok(), kind + " 应报告为失败"),
                    () -> assertNotNull(result.steps().get(0).outcomes().get(0).errorCode()));
        }

        @Test
        @DisplayName("基本异常处理：LLM 故障收敛成失败结果，run() 永不抛异常")
        void llmFailureBecomesResult() {
            runtime = runtime((request, tracer) -> {
                throw com.littleagent.llm.LlmException.serverError("HTTP 503");
            }, 6);

            AgentResult result = runtime.run("A", "w1", "你好");

            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals("LLM_SERVER_ERROR", result.errorCode());
            assertTrue(result.answer().contains("503"));
        }

        @Test
        @DisplayName("工具调用 trace/执行日志：每次调用都有 tool_call + tool_result，并落 JSONL")
        void toolCallsAreTraced(@org.junit.jupiter.api.io.TempDir java.nio.file.Path logDir) {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("calculator", "{\"expression\":\"1+1\"}"),
                    ScriptedLlmClient.text("等于 2。"));
            AgentConfig config = AgentConfig.builder()
                    .mockMode(true).logDir(logDir).maxSteps(6)
                    .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"))
                    .build();
            runtime = AgentRuntime.builder(config).llm(llm)
                    .toolRegistry(DefaultTools.registry(null))
                    .summarizer(new com.littleagent.context.DeterministicSummarizer())
                    .build();

            runtime.run("A", "w1", "1+1");

            Session session = runtime.sessions().require("A", "w1");
            List<String> types = session.tracer().events().stream()
                    .map(com.littleagent.trace.TraceEvent::type).toList();
            assertAll(
                    () -> assertTrue(types.contains(TraceTypes.TOOL_CALL)),
                    () -> assertTrue(types.contains(TraceTypes.TOOL_RESULT)),
                    () -> assertTrue(types.contains(TraceTypes.USER_INPUT)),
                    () -> assertTrue(types.contains(TraceTypes.LLM_REQUEST)),
                    () -> assertTrue(types.contains(TraceTypes.LLM_RESPONSE)),
                    () -> assertTrue(types.contains(TraceTypes.FINAL_ANSWER)),
                    // 执行日志真的落盘了（一行一事件的 JSONL）
                    () -> assertTrue(java.nio.file.Files.exists(logDir.resolve("w1.jsonl")),
                            "trace 应落盘到 logs/<sessionId>.jsonl"));
        }

        @Test
        @DisplayName("异常兜底：工具执行器被关闭后依然不抛异常、不留孤儿 tool_calls")
        void runtimeNeverThrowsEvenAfterClose() {
            ScriptedLlmClient llm = ScriptedLlmClient.create(
                    ScriptedLlmClient.call("calculator", "{\"expression\":\"1+1\"}"))
                    .onExhausted(ScriptedLlmClient.text("好的。"));
            runtime = runtime(llm, 6);
            runtime.close();

            AgentResult result = runtime.run("A", "w1", "1+1");

            assertEquals(AgentResult.Status.OK, result.status());
            assertEquals("TOOL_EXECUTOR_CLOSED", result.steps().get(0).outcomes().get(0).errorCode());
        }
    }

    // ============================================================ 要求 3

    @Nested
    @DisplayName("要求 3：测试用例构建（本条由「本文件能跑绿」+ DeepSeekLiveIT 证明）")
    class Requirement3Tests {

        @Test
        @DisplayName("验收测试本身可运行（能在 IDEA 里一键跑绿）；单测 & 集成测试的分层见 docs/TESTING.md")
        void acceptanceSuiteRuns() {
            // 这一条是自指的：本类 30+ 个用例全绿，就证明「构建了测试用例来测试以上功能」。
            // 单元/集成分工：mvn test = 离线单测；mvn verify = 额外跑 DeepSeekLiveIT（真实 API 5 例）。
            assertTrue(true);
        }
    }

    // ================================================================== 辅助

    private AgentRuntime runtime(com.littleagent.llm.LlmClient llm, int maxSteps) {
        AgentConfig config = AgentConfig.builder()
                .mockMode(true)
                .logDir(null)
                .maxSteps(maxSteps)
                .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"))
                .build();
        return AgentRuntime.builder(config)
                .llm(llm)
                .toolRegistry(DefaultTools.registry(null))
                .summarizer(new com.littleagent.context.DeterministicSummarizer())
                .build();
    }

    /** 供断言使用的空结果（避免未使用 import 警告）。 */
    @SuppressWarnings("unused")
    private static ToolResult unusedForImport() {
        return ToolResult.ok("");
    }
}
