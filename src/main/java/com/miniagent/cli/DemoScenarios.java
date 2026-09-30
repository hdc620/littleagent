package com.miniagent.cli;

import com.miniagent.core.AgentConfig;
import com.miniagent.core.AgentResult;
import com.miniagent.core.AgentRuntime;
import com.miniagent.session.Session;

import java.io.PrintStream;
import java.util.List;

/**
 * 内置演示脚本：把题目里的场景原样跑一遍，便于评审者「一条命令看懂全部能力」。
 *
 * <pre>
 * 场景 1  用户 A 窗口 1：查天气 + 记待办
 * 场景 2  用户 A 窗口 2：写周报（检索知识库模板）+ 记待办
 * 场景 3  回到窗口 1 纯对话追问（验证跨轮次记忆）
 * 场景 4  窗口 1 带工具追问（把待办标记完成）
 * 场景 5  窗口 2 查看待办（验证 session 隔离：看不到窗口 1 的待办）
 * 场景 6  多轮对话后触发上下文压缩
 * </pre>
 *
 * 运行：{@code java -jar target/mini-agent.jar --demo}（加 {@code --mock} 则完全不调用 API）
 */
public final class DemoScenarios {

    private final AgentRuntime runtime;
    private final PrintStream out;
    private final String userId;

    public DemoScenarios(AgentRuntime runtime, String userId, PrintStream out) {
        this.runtime = runtime;
        this.userId = userId;
        this.out = out;
    }

    public void run() {
        out.println("################ mini-agent 演示脚本 ################");
        out.println("模型: " + (runtime.config().mockMode() ? "MockLlmClient（离线演示）" : runtime.config().model()));
        out.println("用户: " + userId);
        out.println("上下文预算: " + runtime.config().maxContextTokens() + " tokens"
                + "（demo 默认收紧到 2000，以便第 6 个场景真的演示出自动压缩；用 --max-context-tokens 可覆盖）");
        out.println();

        section("场景 1：用户 A 的窗口 1 —— 查天气 + 记待办",
                "展示：一轮内一次请求发起两个工具调用（weather + todo，当前按顺序串行执行），工具结果回灌后模型再作答");
        turn("w1", "帮我查一下北京今天的天气，然后记个待办：给张总发周报");

        section("场景 2：用户 A 的窗口 2 —— 写周报 + 记待办",
                "展示：search 检索知识库模板（或 read_docs 读原文），再记一条属于窗口 2 的待办");
        turn("w2", "我要写本周周报，先查一下知识库里的周报模板，然后记个待办：周五前提交周报");

        section("场景 3：回到窗口 1 —— 纯对话追问",
                "展示：跨轮次记忆 —— 待办存在 session 工作记忆里，历史被压缩后依然能答出来"
                        + "（真实模型通常不调工具直接回答；离线 mock 是关键词规则，会先 list 一下）");
        turn("w1", "我刚才让你记的待办是什么？");

        section("场景 4：窗口 1 —— 带工具的追问",
                "展示：模型基于上下文知道「那个待办」指 #1，并调用 todo(done)");
        turn("w1", "帮我把它标记成已完成");

        section("场景 5：窗口 2 —— 查看自己的待办",
                "展示：session 隔离 —— 窗口 2 只能看到自己的待办，看不到窗口 1 的");
        turn("w2", "我有哪些待办？");

        section("场景 6：长对话 —— 上下文压缩与记忆召回",
                "展示：超过 maxContextTokens 后自动把旧历史压成摘要，摘要与工作记忆每轮继续注入");
        for (int i = 1; i <= 6; i++) {
            turn("w1", "第 " + i + " 个补充问题：请基于上海的天气，给我一份 200 字左右的出行建议，"
                    + "分别从穿衣、交通、健康三个方面展开。");
        }
        reportContext("w1");
        out.println("（窗口 1 再次追问，验证压缩后仍能接上话题）");
        turn("w1", "我最早让你记的那条待办还在吗？");

        section("收尾：会话总览", "展示：一个用户下的多个独立窗口");
        List<Session> sessions = runtime.sessions().list(userId);
        for (Session session : sessions) {
            out.println("  " + session.brief());
        }
        out.println();
        out.println("################ 演示结束 ################");
    }

    private void turn(String sessionId, String input) {
        out.println("▶ 用户@" + sessionId + "：" + input);
        AgentResult result = runtime.run(userId, sessionId, input);
        for (AgentResult.StepRecord step : result.steps()) {
            if (!step.toolCalls().isEmpty()) {
                out.println("  · step " + step.index() + " 工具调用：" + step.toolCalls());
                for (AgentResult.ToolOutcome outcome : step.outcomes()) {
                    out.println("      " + (outcome.ok() ? "✔" : "✘") + " " + outcome.tool()
                            + (outcome.ok() ? "" : " [" + outcome.errorCode() + "]")
                            + " → " + outcome.resultPreview());
                }
            }
        }
        out.println("  · 状态：" + result.status() + " | 步数 " + result.steps().size()
                + " | tokens " + result.totalUsage().totalTokens() + " | " + result.latencyMs() + "ms");
        out.println("  ◀ Agent：" + indentBlock(result.answer()));
        out.println();
    }

    private void reportContext(String sessionId) {
        Session session = runtime.sessions().require(userId, sessionId);
        out.println("  ── 上下文状态：历史 " + session.historySize() + " 条，已压缩前 "
                + session.summarizedUpTo() + " 条，估算 " + runtime.contextManager().estimate(session)
                + " tokens / 预算 " + runtime.config().maxContextTokens());
        if (!session.summary().isBlank()) {
            out.println("  ── 当前摘要（前 200 字）：" + truncate(session.summary(), 200));
        }
        out.println("  ── 工作记忆：" + (session.memory().render().isBlank() ? "（空）" : truncate(session.memory().render(), 200)));
        out.println();
    }

    private void section(String title, String hint) {
        out.println("─────────────────────────────────────────────────────────────");
        out.println("【" + title + "】");
        out.println("  " + hint);
    }

    private static String indentBlock(String text) {
        return text.replace("\n", "\n     ");
    }

    private static String truncate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** 入口：便于测试直接构造。 */
    public static void runWith(AgentConfig config, String userId, PrintStream out) {
        try (AgentRuntime runtime = AgentRuntime.createDefault(config)) {
            new DemoScenarios(runtime, userId, out).run();
        }
    }
}
