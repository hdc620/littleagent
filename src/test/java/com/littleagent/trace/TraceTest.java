package com.littleagent.trace;

import com.littleagent.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** trace/日志：事件记录、耗时、异常、JSONL 落盘。 */
class TraceTest {

    @Test
    @DisplayName("事件按序编号，内存可回溯，可渲染成时间线")
    void recordsEvents() {
        Tracer tracer = Tracer.of("w1");
        tracer.event(TraceTypes.USER_INPUT, "用户说话", "text", "你好");
        tracer.event(TraceTypes.LLM_RESPONSE, "HTTP 200", Tracer.data("tokens", 12));

        List<TraceEvent> events = tracer.events();
        assertEquals(2, events.size());
        assertEquals(1, events.get(0).seq());
        assertEquals(2, events.get(1).seq());
        assertEquals("你好", events.get(0).data().get("text"));
        assertEquals(12, events.get(1).data().get("tokens"));
        assertTrue(tracer.render().contains("llm_response"));
    }

    @Test
    @DisplayName("timed 记录成功与失败，并保留耗时字段")
    void timesOperations() {
        Tracer tracer = Tracer.of("w1");
        String value = tracer.timed(TraceTypes.TOOL_CALL, "调用工具", () -> "结果");
        assertEquals("结果", value);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> tracer.timed(TraceTypes.TOOL_CALL, "会失败", () -> {
                    throw new IllegalStateException("boom");
                })).getMessage().contains("boom"));

        List<TraceEvent> events = tracer.events();
        assertEquals(2, events.size());
        assertEquals(true, events.get(0).data().get("ok"));
        assertEquals(false, events.get(1).data().get("ok"));
        assertTrue(events.get(1).data().get("error").toString().contains("boom"));
    }

    @Test
    @DisplayName("noop Tracer 不记录任何内容（显式关闭观测）")
    void noopRecordsNothing() {
        Tracer tracer = Tracer.noop();
        tracer.event(TraceTypes.USER_INPUT, "x");
        assertTrue(tracer.events().isEmpty());
        assertFalse(tracer.enabled());
    }

    @Test
    @DisplayName("data() 自动忽略 null，避免 Map.copyOf 抛 NPE")
    void dataHelperSkipsNulls() {
        Map<String, Object> data = Tracer.data("a", 1, "b", null, null, "c", "d", "e");
        assertEquals(Map.of("a", 1, "d", "e"), data);
    }

    @Test
    @DisplayName("JSONL sink：每行一条合法 JSON，时间戳为 ISO 字符串（回归测试：曾因 Instant 序列化失败）")
    void writesJsonLines(@TempDir Path dir) throws IOException {
        Path file;
        try (Tracer tracer = Tracer.of("w1", new JsonlTraceSink(dir, "w1"))) {
            tracer.event(TraceTypes.USER_INPUT, "你好", Tracer.data("historySize", 0));
            tracer.event(TraceTypes.CONTEXT_BUILT, "组装上下文",
                    Tracer.data("recalledSeqs", List.of(1, 2), "nested", Map.of("k", "v")));
        }
        file = dir.resolve("w1.jsonl");
        assertTrue(Files.exists(file));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        for (String line : lines) {
            var node = Json.MAPPER.readTree(line);
            assertEquals("w1", node.path("sessionId").asText());
            assertTrue(node.hasNonNull("at"));
            assertTrue(node.path("at").asText().contains("T"));
            assertTrue(node.has("data"));
        }
        assertEquals("user_input", Json.MAPPER.readTree(lines.get(0)).path("type").asText());
        var recalled = Json.MAPPER.readTree(lines.get(1)).path("data").path("recalledSeqs");
        assertEquals(1, recalled.get(0).asInt());
        assertEquals(2, recalled.get(1).asInt());
    }

    @Test
    @DisplayName("JSONL sink 对文件名的非法字符做安全化")
    void sanitizesSessionIdInFileName(@TempDir Path dir) {
        new JsonlTraceSink(dir, "../../etc/passwd").accept(
                new TraceEvent(1, java.time.Instant.now(), "x", "t", "s", Map.of(), 0));
        try (var files = Files.list(dir)) {
            String name = files.findFirst().orElseThrow().getFileName().toString();
            assertFalse(name.contains("/"));
            assertFalse(name.contains(".."));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("sink 抛异常不会影响主流程（观测不能拖垮业务）")
    void sinkFailureIsIsolated() {
        TraceSink broken = event -> {
            throw new IllegalStateException("sink 坏了");
        };
        Tracer tracer = Tracer.of("w1", broken);
        tracer.event(TraceTypes.USER_INPUT, "依然要成功");
        assertEquals(1, tracer.events().size());
    }

    @Test
    @DisplayName("ConsoleTraceSink 可输出事件摘要")
    void consoleSinkPrints() {
        new ConsoleTraceSink(false).accept(new TraceEvent(1, java.time.Instant.now(), "w1",
                TraceTypes.FINAL_ANSWER, "完成", Map.of("steps", 2), 15));
        // 只要求不抛异常（输出内容属人工检查范围）
        assertTrue(true);
    }
}
