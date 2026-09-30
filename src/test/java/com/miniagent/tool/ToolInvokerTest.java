package com.miniagent.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.llm.ToolCall;
import com.miniagent.session.Session;
import com.miniagent.session.SessionManager;
import com.miniagent.trace.TraceEvent;
import com.miniagent.trace.TraceTypes;
import com.miniagent.trace.Tracer;
import com.miniagent.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用器的防护清单：未知工具、坏参数、校验失败、超时、异常、超大结果、trace。
 * 这些用例对应「工具出错不该炸掉 Agent，而应回灌可读错误让模型自愈」的设计目标。
 */
class ToolInvokerTest {

    private ToolRegistry registry;
    private ToolInvoker invoker;
    private Tracer tracer;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry()
                .register(new ToolRegistryTest.StubTool("echo", "回显参数"))
                .register(new NumericTool())
                .register(new SlowTool())
                .register(new FailingTool())
                .register(new VerboseTool());
        invoker = new ToolInvoker(registry, 200, 120);
        tracer = Tracer.of("w-test");
        Session session = new SessionManager().create("A", "w-test", "测试会话");
        context = new ToolContext("A", "w-test", "call_1", session, tracer);
    }

    @AfterEach
    void tearDown() {
        invoker.close();
    }

    @Test
    @DisplayName("未知工具：返回可用工具列表，让模型自己改正")
    void unknownTool() {
        ToolResult result = invoker.invoke(ToolCall.of("no_such_tool", "{}"), context);
        assertFalse(result.ok());
        assertEquals("UNKNOWN_TOOL", result.errorCode());
        assertTrue(result.content().contains("echo"));
        assertTrue(result.content().contains("可用工具"));
    }

    @Test
    @DisplayName("参数不是合法 JSON：返回解析错误")
    void badJsonArguments() {
        ToolResult result = invoker.invoke(new ToolCall("c1", "echo", "{不是 json"), context);
        assertEquals("BAD_ARGUMENTS_JSON", result.errorCode());
        assertTrue(result.content().contains("合法 JSON"));
    }

    @Test
    @DisplayName("参数不是对象：返回类型错误")
    void nonObjectArguments() {
        ToolResult result = invoker.invoke(new ToolCall("c1", "echo", "[1,2,3]"), context);
        assertEquals("BAD_ARGUMENTS_TYPE", result.errorCode());
    }

    @Test
    @DisplayName("Schema 校验失败：逐字段错误回灌给模型")
    void schemaViolation() {
        ToolResult result = invoker.invoke(new ToolCall("c1", "numeric", "{\"count\":\"abc\"}"), context);
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
        assertTrue(result.content().contains("count"));
        assertTrue(result.content().contains("重试"));
    }

    @Test
    @DisplayName("类型纠偏：字符串数字自动转换后正常执行")
    void coercesAndExecutes() {
        ToolResult result = invoker.invoke(new ToolCall("c1", "numeric", "{\"count\":\"7\"}"), context);
        assertTrue(result.ok());
        assertEquals("count=7", result.content());
    }

    @Test
    @DisplayName("工具执行超时：中断并回灌超时信息")
    void toolTimeout() {
        ToolResult result = invoker.invoke(ToolCall.of("slow", "{}"), context);
        assertEquals("TOOL_TIMEOUT", result.errorCode());
        assertTrue(result.content().contains("超时"));
    }

    @Test
    @DisplayName("工具抛异常：折叠成可读错误（含异常类型，不含堆栈）")
    void toolThrows() {
        ToolResult result = invoker.invoke(ToolCall.of("failing", "{}"), context);
        assertEquals("TOOL_EXECUTION_ERROR", result.errorCode());
        assertTrue(result.content().contains("IllegalStateException"));
        assertFalse(result.content().contains("\tat "));
    }

    @Test
    @DisplayName("超大结果被截断，保护上下文预算")
    void truncatesOversizedResult() {
        ToolResult result = invoker.invoke(ToolCall.of("verbose", "{}"), context);
        assertTrue(result.ok());
        assertTrue(result.content().contains("已截断"));
        assertTrue(result.content().length() < 400);
    }

    @Test
    @DisplayName("每次调用都留下 tool_call / tool_result 两条 trace")
    void recordsTrace() {
        invoker.invoke(ToolCall.of("failing", "{}"), context);
        List<TraceEvent> events = tracer.events();
        assertEquals(2, events.size());
        assertEquals(TraceTypes.TOOL_CALL, events.get(0).type());
        assertEquals(TraceTypes.TOOL_RESULT, events.get(1).type());
        assertEquals(false, events.get(1).data().get("ok"));
        assertEquals("TOOL_EXECUTION_ERROR", events.get(1).data().get("errorCode"));
    }

    @Test
    @DisplayName("工具失败不抛异常给调用方（Agent 循环不中断）")
    void neverThrows() {
        assertTrue(invoker.invoke(ToolCall.of("failing", "{}"), context).errorCode() != null);
        assertTrue(invoker.invoke(ToolCall.of("unknown", "{}"), context).errorCode() != null);
    }

    @Test
    @DisplayName("close() 之后再调用：折叠成结构化错误而不是抛 RejectedExecutionException")
    void invokeAfterCloseReturnsErrorResult() {
        ToolInvoker closed = new ToolInvoker(registry, 1000, 2000);
        closed.close();

        ToolResult result = closed.invoke(ToolCall.of("echo", "{}"), context);

        // API.md 里 invoke 的契约是「永不抛异常」——executor 已关闭属于工具层故障，
        // 必须折叠成可回灌的结果（早期实现会抛 RejectedExecutionException 穿透到调用方）
        assertAll(
                () -> assertFalse(result.ok()),
                () -> assertEquals("TOOL_EXECUTOR_CLOSED", result.errorCode()),
                () -> assertTrue(result.toObservation().startsWith("[TOOL_ERROR]")));
    }

    // ------------------------------------------------------------- 测试工具

    /** 声明 integer 参数，用于验证纠偏 */
    static final class NumericTool implements Tool {
        @Override
        public String name() {
            return "numeric";
        }

        @Override
        public String description() {
            return "需要整数参数";
        }

        @Override
        public ObjectNode parametersSchema() {
            ObjectNode count = Json.obj();
            count.put("type", "integer");
            return SchemaValidator.objectSchema(Map.of("count", count), List.of("count"));
        }

        @Override
        public ToolResult execute(ObjectNode arguments, ToolContext context) {
            return ToolResult.ok("count=" + arguments.path("count").asInt());
        }
    }

    static final class SlowTool implements Tool {
        @Override
        public String name() {
            return "slow";
        }

        @Override
        public String description() {
            return "会阻塞的工具";
        }

        @Override
        public ObjectNode parametersSchema() {
            return SchemaValidator.objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult execute(ObjectNode arguments, ToolContext context) throws Exception {
            Thread.sleep(3000);
            return ToolResult.ok("不该返回");
        }
    }

    static final class FailingTool implements Tool {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public String description() {
            return "总是失败";
        }

        @Override
        public ObjectNode parametersSchema() {
            return SchemaValidator.objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult execute(ObjectNode arguments, ToolContext context) {
            throw new IllegalStateException("下游服务不可用");
        }
    }

    static final class VerboseTool implements Tool {
        @Override
        public String name() {
            return "verbose";
        }

        @Override
        public String description() {
            return "返回超长内容";
        }

        @Override
        public ObjectNode parametersSchema() {
            return SchemaValidator.objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult execute(ObjectNode arguments, ToolContext context) {
            return ToolResult.ok("数据".repeat(500));
        }
    }
}
