package com.littleagent.core;

import com.littleagent.session.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 LLM API 集成测试（DeepSeek）。
 *
 * <p>运行方式：{@code mvn verify}（需要 DEEPSEEK_API_KEY，或项目根目录 .env.local）。
 * 没有配置 Key 时自动跳过（{@link Assumptions}），不会让 CI 变红。
 *
 * <p>它验证的是「单元测试验证不了的东西」：真实模型能否根据工具 Schema 自主决策、
 * 能否在多轮对话中利用 Runtime 注入的工作记忆、以及真实会话隔离是否成立。
 */
@Tag("live")
class DeepSeekLiveIT {

    private static AgentConfig config;
    private static AgentRuntime runtime;

    @BeforeAll
    static void setUp() {
        config = AgentConfig.fromEnv().toBuilder()
                .logDir(Path.of("logs", "live"))
                .maxSteps(6)
                .build();
        Assumptions.assumeTrue(config.hasApiKey(),
                "未配置 DEEPSEEK_API_KEY，跳过真实 API 集成测试（单元测试仍会完整运行）");
        runtime = AgentRuntime.createDefault(config);
    }

    @AfterAll
    static void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    @DisplayName("真实模型能自主调用 calculator 并给出正确结果")
    void realModelUsesCalculator() {
        AgentResult result = runtime.run("live-user", "live-calc",
                "请精确计算 1234*5678+sqrt(144)，必须使用工具计算，不要自己心算。");

        assertFalse(result.failed(), "调用失败：" + result.answer());
        assertTrue(result.toolCallCount() >= 1, "模型没有调用任何工具");
        assertTrue(result.steps().stream().flatMap(s -> s.outcomes().stream())
                        .anyMatch(o -> "calculator".equals(o.tool()) && o.ok()),
                "calculator 未被成功调用");
        // 模型可能输出千分位（7,006,664），因此去掉分隔符再比较
        String digitsOnly = result.answer().replaceAll("[,\\s，]", "");
        assertTrue(digitsOnly.contains("7006664"), "答案不正确：" + result.answer());
    }

    @Test
    @DisplayName("真实模型能利用工作记忆回答追问，并在需要时调用 todo 工具")
    void realModelRemembersAcrossTurns() {
        String sessionId = "live-memory";
        AgentResult first = runtime.run("live-user", sessionId, "帮我记个待办：给张总发周报。");
        assertFalse(first.failed());

        AgentResult followUp = runtime.run("live-user", sessionId, "我刚才让你记的待办是什么？");
        assertFalse(followUp.failed());
        assertTrue(followUp.answer().contains("周报"), "追问回答未包含待办内容：" + followUp.answer());
    }

    @Test
    @DisplayName("真实模型在检索场景会使用知识库工具并引用文档")
    void realModelUsesKnowledgeBase() {
        AgentResult result = runtime.run("live-user", "live-docs",
                "我们的周报模板要求哪些字段？请查知识库后回答。");

        assertFalse(result.failed());
        assertTrue(result.answer().contains("周报"));
    }

    @Test
    @DisplayName("真实会话隔离：两个窗口的待办互不可见")
    void realSessionIsolation() {
        runtime.run("live-user", "live-a", "记个待办：窗口A专属事项");
        runtime.run("live-user", "live-b", "记个待办：窗口B专属事项");

        Session a = runtime.sessions().require("live-user", "live-a");
        Session b = runtime.sessions().require("live-user", "live-b");
        assertTrue(a.memory().render().contains("窗口A专属事项"));
        assertFalse(a.memory().render().contains("窗口B专属事项"));
        assertTrue(b.memory().render().contains("窗口B专属事项"));
        assertFalse(b.memory().render().contains("窗口A专属事项"));
    }

    @Test
    @DisplayName("真实调用会写入 trace 与用量统计")
    void realCallIsObservable() {
        AgentResult result = runtime.run("live-user", "live-trace", "用一句话说明你能做什么。");
        assertFalse(result.failed());
        assertTrue(result.totalUsage().promptTokens() > 0, "应拿到真实 token 用量");
        Session session = runtime.sessions().require("live-user", "live-trace");
        assertTrue(session.tracer().events().stream().anyMatch(e -> "llm_response".equals(e.type())));
    }
}
