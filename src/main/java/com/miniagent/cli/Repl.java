package com.miniagent.cli;

import com.miniagent.core.AgentConfig;
import com.miniagent.core.AgentResult;
import com.miniagent.core.AgentRuntime;
import com.miniagent.llm.Message;
import com.miniagent.session.Session;
import com.miniagent.trace.TraceEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 交互式多窗口 REPL：一个「窗口」= 一个 session。
 *
 * <p>它同时是「多 session 隔离」的演示入口：用户 A 可以并行开 w1 / w2，
 * 用 {@code /use} 切换，各自的历史、工作记忆、trace 完全独立。
 */
public final class Repl {

    private static final String PROMPT_FORMAT = "%s@%s> ";

    private final AgentRuntime runtime;
    private final PrintStream out;
    private final BufferedReader reader;
    private final String userId;
    private String currentSessionId;
    private boolean verboseSteps = true;
    private int windowCounter = 0;

    public Repl(AgentRuntime runtime, String userId, String initialSessionId, PrintStream out) {
        this.runtime = runtime;
        this.userId = userId;
        this.out = out;
        this.reader = new BufferedReader(openStdin());
        this.currentSessionId = initialSessionId;
    }

    private static java.io.Reader openStdin() {
        try {
            java.io.Console console = System.console();
            if (console != null) {
                return console.reader();
            }
        } catch (RuntimeException e) {
            // 无终端（管道输入）时退回标准输入
        }
        return new InputStreamReader(System.in, StandardCharsets.UTF_8);
    }

    public void start() {
        printBanner();
        if (currentSessionId == null || currentSessionId.isBlank()) {
            currentSessionId = newWindow(null);
        } else {
            runtime.sessions().getOrCreate(userId, currentSessionId, "初始窗口");
            out.println("已进入窗口 " + currentSessionId);
        }
        while (true) {
            out.print(String.format(PROMPT_FORMAT, userId, currentSessionId));
            out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (Exception e) {
                out.println("读取输入失败：" + e.getMessage());
                return;
            }
            if (line == null) {
                out.println("\n再见。");
                return;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if (input.startsWith("/")) {
                if (!handleCommand(input)) {
                    return;
                }
                continue;
            }
            ask(input);
        }
    }

    private void printBanner() {
        AgentConfig config = runtime.config();
        out.println("=====================================================================");
        out.println(" mini-agent v1.0.0 —— 自研最小可用 Agent Runtime");
        out.println(" 用户: " + userId + " | 模型: " + (config.mockMode() ? "MockLlmClient（离线，不发请求）" : config.model()));
        out.println(" 工具: " + String.join(", ", runtime.tools().names()));
        out.println(" 参数: maxSteps=" + config.maxSteps() + " maxContextTokens=" + config.maxContextTokens()
                + " keepRecentTurns=" + config.keepRecentTurns());
        out.println(" 命令: /new [标题] /use <序号|id> /sessions /history /memory /trace /compact /verbose /help /exit");
        out.println("=====================================================================");
    }

    /** @return false 表示退出 */
    private boolean handleCommand(String command) {
        String[] parts = command.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (name) {
            case "/exit", "/quit" -> {
                out.println("再见。");
                return false;
            }
            case "/help" -> out.println("""
                    命令：
                      /new [标题]      新建窗口（session）
                      /use <序号|id>   切换窗口
                      /sessions        列出当前用户的所有窗口
                      /history [n]     查看当前窗口最近 n 条消息（默认 10）
                      /memory          查看工作记忆与压缩摘要
                      /trace [n]       查看最近 n 条 trace（默认 15）
                      /compact         手动触发一次上下文压缩
                      /verbose         切换步骤详情显示
                      /exit            退出
                    直接输入内容即与当前窗口对话。""");
            case "/new" -> out.println("已新建并切换到窗口 " + newWindow(arg.isEmpty() ? null : arg));
            case "/use" -> switchWindow(arg);
            case "/sessions" -> listSessions();
            case "/history" -> printHistory(parseInt(arg, 10));
            case "/memory" -> printMemory();
            case "/trace" -> printTrace(parseInt(arg, 15));
            case "/compact" -> {
                Session session = currentSession();
                boolean compressed = runtime.contextManager().compact(session, true);
                out.println(compressed
                        ? "压缩完成，当前上下文约 " + runtime.contextManager().estimate(session) + " tokens。"
                        : "没有可压缩的历史（需要超过 keepRecentTurns=" + runtime.config().keepRecentTurns() + " 轮）。");
            }
            case "/verbose" -> {
                verboseSteps = !verboseSteps;
                out.println("步骤详情：" + (verboseSteps ? "开" : "关"));
            }
            default -> out.println("未知命令：" + name + "（输入 /help 查看帮助）");
        }
        return true;
    }

    /** 一轮问答。 */
    public AgentResult ask(String input) {
        AgentResult result = runtime.run(userId, currentSessionId, input);
        printResult(result);
        return result;
    }

    private void printResult(AgentResult result) {
        if (verboseSteps) {
            for (AgentResult.StepRecord step : result.steps()) {
                if (!step.toolCalls().isEmpty()) {
                    out.println("  ├─ step " + step.index() + " 调用工具：" + step.toolCalls());
                    for (AgentResult.ToolOutcome outcome : step.outcomes()) {
                        out.println("  │    " + (outcome.ok() ? "✔" : "✘") + " " + outcome.tool()
                                + (outcome.ok() ? "" : " [" + outcome.errorCode() + "]")
                                + " → " + outcome.resultPreview());
                    }
                }
            }
        }
        String status = switch (result.status()) {
            case OK -> "完成";
            case MAX_STEPS -> "达到最大步数，已强制收尾";
            case FAILED -> "失败";
        };
        out.println("  └─ " + status + " | 步数 " + result.steps().size()
                + " | " + result.latencyMs() + "ms"
                + " | tokens " + result.totalUsage().totalTokens());
        out.println();
        out.println(result.answer());
        out.println();
    }

    private void switchWindow(String arg) {
        if (arg.isEmpty()) {
            out.println("用法：/use <序号|id>");
            return;
        }
        List<Session> sessions = runtime.sessions().list(userId);
        try {
            int index = Integer.parseInt(arg);
            if (index >= 1 && index <= sessions.size()) {
                currentSessionId = sessions.get(index - 1).id();
                out.println("已切换到窗口 " + currentSessionId + "（" + sessions.get(index - 1).title() + "）");
                return;
            }
            out.println("序号超出范围（1-" + sessions.size() + "）");
            return;
        } catch (NumberFormatException ignored) {
            // 按 id 处理
        }
        try {
            Session session = runtime.sessions().require(userId, arg);
            currentSessionId = session.id();
            out.println("已切换到窗口 " + currentSessionId + "（" + session.title() + "）");
        } catch (RuntimeException e) {
            out.println("切换失败：" + e.getMessage());
        }
    }

    private void listSessions() {
        List<Session> sessions = runtime.sessions().list(userId);
        if (sessions.isEmpty()) {
            out.println("当前用户还没有窗口。");
            return;
        }
        out.println("用户 " + userId + " 的窗口：");
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            String marker = session.id().equals(currentSessionId) ? "*" : " ";
            out.println(marker + " " + (i + 1) + ") " + session.brief());
        }
    }

    private void printHistory(int limit) {
        List<Message> history = currentSession().history();
        int from = Math.max(0, history.size() - Math.max(1, limit));
        out.println("窗口 " + currentSessionId + " 的历史（共 " + history.size() + " 条，展示最后 "
                + (history.size() - from) + " 条）：");
        for (int i = from; i < history.size(); i++) {
            Message message = history.get(i);
            out.println("  #" + message.seq() + " [" + message.role().wireName() + "] "
                    + abbreviate(message.content()));
            for (var call : message.toolCalls()) {
                out.println("        -> " + call.name() + " " + abbreviate(call.argumentsJson()));
            }
        }
    }

    private void printMemory() {
        Session session = currentSession();
        out.println("窗口 " + currentSessionId + " 的工作记忆：");
        String memory = session.memory().render();
        out.println(memory.isEmpty() ? "  （空）" : indent(memory));
        out.println("压缩摘要（覆盖前 " + session.summarizedUpTo() + " 条历史）：");
        out.println(session.summary().isBlank() ? "  （尚未压缩）" : indent(session.summary()));
        out.println("估算上下文：" + runtime.contextManager().estimate(session) + " tokens / 预算 "
                + runtime.config().maxContextTokens() + " tokens");
    }

    private void printTrace(int limit) {
        List<TraceEvent> events = currentSession().tracer().events();
        int from = Math.max(0, events.size() - Math.max(1, limit));
        out.println("窗口 " + currentSessionId + " 的 trace（共 " + events.size() + " 条，展示最后 "
                + (events.size() - from) + " 条）：");
        for (int i = from; i < events.size(); i++) {
            out.println("  " + events.get(i).render());
        }
    }

    private Session currentSession() {
        return runtime.sessions().require(userId, currentSessionId);
    }

    private String newWindow(String title) {
        windowCounter++;
        if (title == null) {
            title = "窗口 " + windowCounter;
        }
        Session session = runtime.sessions().create(userId, "w" + windowCounter, title);
        return session.id();
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    private static int parseInt(String arg, int defaultValue) {
        try {
            return arg.isEmpty() ? defaultValue : Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
