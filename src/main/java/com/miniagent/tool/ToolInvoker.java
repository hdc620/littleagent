package com.miniagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.llm.ToolCall;
import com.miniagent.trace.TraceEvent;
import com.miniagent.trace.TraceTypes;
import com.miniagent.trace.Tracer;
import com.miniagent.util.Json;
import com.miniagent.util.Texts;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用器：把「模型想调用工具」变成「一次可观测、有超时、不会炸掉 Agent 的执行」。
 *
 * <p>防护清单（每一条都对应一个真实事故场景）：
 * <ol>
 *   <li><b>未知工具</b>：模型幻觉出不存在的工具名 -&gt; 回灌可用工具列表，让它改；</li>
 *   <li><b>参数非法 JSON</b>：模型拼坏 JSON -&gt; 回灌解析错误；</li>
 *   <li><b>Schema 校验</b>：缺必填/类型错 -&gt; 回灌逐字段错误；</li>
 *   <li><b>类型纠偏</b>：{@code "top_k":"3"} 这类常见错误自动归一化，省一轮交互；</li>
 *   <li><b>超时</b>：工具卡死 -&gt; 中断等待并回灌超时，循环继续；</li>
 *   <li><b>异常</b>：工具抛错 -&gt; 折叠成可读错误（不含堆栈，省 token）；</li>
 *   <li><b>结果过大</b>：结果超长 -&gt; 截断，保护上下文预算。</li>
 * </ol>
 */
public final class ToolInvoker implements AutoCloseable {

    private final ToolRegistry registry;
    private final int timeoutMs;
    private final int maxResultChars;
    private final ExecutorService executor;

    public ToolInvoker(ToolRegistry registry, int timeoutMs, int maxResultChars) {
        this.registry = registry;
        this.timeoutMs = timeoutMs <= 0 ? 5000 : timeoutMs;
        this.maxResultChars = maxResultChars <= 0 ? 4000 : maxResultChars;
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    public ToolResult invoke(ToolCall call, ToolContext context) {
        Tracer tracer = context == null ? Tracer.noop() : context.tracer();
        Tracer t = tracer;
        tracer.event(TraceTypes.TOOL_CALL, call.name() + " " + Texts.oneLine(call.argumentsJson(), 120),
                Tracer.data("tool", call.name(), "callId", call.id(), "arguments", call.argumentsJson()));

        long start = System.nanoTime();
        ToolResult result = doInvoke(call, context);
        long costMs = (System.nanoTime() - start) / 1_000_000L;

        String trimmed = Texts.truncate(result.content(), maxResultChars);
        ToolResult finalResult = result.ok() ? ToolResult.ok(trimmed) : ToolResult.error(result.errorCode(), trimmed);

        t.event(TraceTypes.TOOL_RESULT,
                call.name() + (finalResult.ok() ? " 成功" : " 失败") + " (" + costMs + "ms)",
                Tracer.data("tool", call.name(), "callId", call.id(), "ok", finalResult.ok(),
                        "errorCode", finalResult.errorCode(), "latencyMs", costMs,
                        "resultPreview", Texts.oneLine(trimmed, 200),
                        "truncated", result.content() != null && result.content().length() > trimmed.length()));
        return finalResult;
    }

    private ToolResult doInvoke(ToolCall call, ToolContext context) {
        Optional<Tool> maybeTool = registry.find(call.name());
        if (maybeTool.isEmpty()) {
            return ToolResult.error("UNKNOWN_TOOL",
                    "不存在名为 `" + call.name() + "` 的工具。可用工具: " + registry.describeAvailable()
                            + "。请改用可用工具，或直接回答用户。");
        }
        Tool tool = maybeTool.get();

        JsonNode arguments = Json.parseOrNull(call.argumentsJson());
        if (arguments == null) {
            return ToolResult.error("BAD_ARGUMENTS_JSON",
                    "参数不是合法 JSON: " + Texts.oneLine(call.argumentsJson(), 200) + "。请按 Schema 重新生成。");
        }
        if (!arguments.isObject()) {
            return ToolResult.error("BAD_ARGUMENTS_TYPE",
                    "参数必须是 JSON 对象，实际是 " + arguments.getNodeType());
        }
        ObjectNode args = SchemaValidator.coerce(tool.parametersSchema(), (ObjectNode) arguments);

        List<String> violations = SchemaValidator.validate(tool.parametersSchema(), args);
        if (!violations.isEmpty()) {
            return ToolResult.error("INVALID_ARGUMENTS",
                    "参数校验失败: " + SchemaValidator.describe(violations) + "。请修正后重试。");
        }

        Future<ToolResult> future = executor.submit(() -> {
            ToolResult r = tool.execute(args, context);
            return r == null ? ToolResult.ok("") : r;
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.error("TOOL_TIMEOUT", "工具 `" + tool.name() + "` 执行超时（>" + timeoutMs + "ms），已中断。");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IllegalArgumentException iae) {
                return ToolResult.error("INVALID_ARGUMENTS", "工具 `" + tool.name() + "` 拒绝该参数: " + iae.getMessage());
            }
            return ToolResult.error("TOOL_EXECUTION_ERROR", "工具 `" + tool.name() + "` 执行异常: "
                    + cause.getClass().getSimpleName() + ": " + Texts.oneLine(cause.getMessage(), 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.error("TOOL_INTERRUPTED", "工具 `" + tool.name() + "` 调用被中断。");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** trace 事件流（测试用）。 */
    public static List<TraceEvent> eventsOf(Tracer tracer) {
        return tracer.events();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mini-agent-tool-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
