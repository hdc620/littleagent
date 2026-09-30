package com.littleagent.cli;

import com.littleagent.context.DeterministicSummarizer;
import com.littleagent.core.AgentConfig;
import com.littleagent.core.AgentRuntime;
import com.littleagent.llm.MockLlmClient;
import com.littleagent.session.Session;
import com.littleagent.tool.impl.DefaultTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 多窗口 REPL 的行为测试（离线，用 StringReader 脚本化驱动）。
 *
 * <p>回归用例：`/new` 必须真正切换当前窗口 —— 曾经只打印了「已切换到窗口 w2」却没有更新
 * {@code currentSessionId}，导致后续消息仍然写进旧窗口，直接破坏「两窗口互不影响」的演示。
 */
class ReplTest {

    private AgentRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    @DisplayName("/new 真正切换窗口：新窗口的消息不会写进旧窗口")
    void newWindowActuallySwitches() {
        String output = run(String.join("\n", List.of(
                "记个待办：w1 的任务",
                "/new 周报窗口",
                "记个待办：w2 的任务",
                "/exit")));

        List<Session> sessions = runtime.sessions().list("A");
        assertEquals(2, sessions.size(), "应创建两个窗口");
        Session w1 = runtime.sessions().require("A", "w1");
        Session w2 = runtime.sessions().require("A", "w2");

        assertTrue(w1.memory().render().contains("w1 的任务"));
        assertFalse(w1.memory().render().contains("w2 的任务"), "新窗口的消息不能落到旧窗口");
        assertTrue(w2.memory().render().contains("w2 的任务"));
        assertEquals("周报窗口", w2.title());
        assertTrue(output.contains("已新建并切换到窗口 w2"));
    }

    @Test
    @DisplayName("/use 能切回旧窗口，消息按窗口归属")
    void useSwitchesBack() {
        run(String.join("\n", List.of(
                "记个待办：w1 的任务",
                "/new 第二个窗口",
                "记个待办：w2 的任务",
                "/use 1",
                "记个待办：切回 w1 的任务",
                "/exit")));

        Session w1 = runtime.sessions().require("A", "w1");
        Session w2 = runtime.sessions().require("A", "w2");

        assertTrue(w1.memory().render().contains("w1 的任务"));
        assertTrue(w1.memory().render().contains("切回 w1 的任务"));
        assertFalse(w1.memory().render().contains("w2 的任务"));
        assertFalse(w2.memory().render().contains("切回 w1 的任务"));
    }

    @Test
    @DisplayName("管道输入带 UTF-8 BOM 时首条命令仍能正确解析")
    void toleratesUtf8BomOnFirstLine() {
        String output = run("\uFEFF记个待办：带 BOM 的任务\n/sessions\n/exit");

        Session w1 = runtime.sessions().require("A", "w1");
        assertTrue(w1.memory().render().contains("带 BOM 的任务"), "BOM 不应破坏首条命令：" + output);
        assertFalse(output.contains("未知命令"));
    }

    @Test
    @DisplayName("/memory、/history、/trace、/sessions 都能正常输出且不抛异常")
    void diagnosticCommandsWork() {
        String output = run(String.join("\n", List.of(
                "记个待办：诊断用",
                "/memory",
                "/history",
                "/trace",
                "/sessions",
                "/compact",
                "/help",
                "/exit")));

        assertTrue(output.contains("工作记忆"));
        assertTrue(output.contains("诊断用"));
        assertTrue(output.contains("trace"));
        assertTrue(output.contains("命令："));
        assertFalse(output.contains("未知命令"), output);
    }

    @Test
    @DisplayName("输入流结束（无 /exit）时优雅退出")
    void exitsOnEndOfInput() {
        String output = run("你好");
        assertTrue(output.contains("再见。"));
    }

    private String run(String script) {
        AgentConfig config = AgentConfig.builder()
                .mockMode(true)
                .logDir(null)
                .knowledgeDir(java.nio.file.Path.of("docs", "knowledge"))
                .build();
        runtime = AgentRuntime.builder(config)
                .llm(new MockLlmClient())
                .toolRegistry(DefaultTools.registry(config.knowledgeDir()))
                .summarizer(new DeterministicSummarizer())
                .build();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new Repl(runtime, "A", "w1", out, new StringReader(script)).start();
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
