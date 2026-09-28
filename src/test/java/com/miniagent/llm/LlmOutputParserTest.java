package com.miniagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 输出解析：把「模型输出」翻译成 Runtime 能执行的结构。
 * 覆盖原生 tool_calls、三类文本兜底（&lt;tool_call&gt; / JSON 代码块 / ReAct），以及思维过程抽取。
 */
class LlmOutputParserTest {

    @Test
    @DisplayName("原生 tool_calls 优先，正文里的说明归入思考")
    void parsesNativeToolCalls() {
        LlmResponse response = new LlmResponse("我先查一下天气", "用户想知道天气",
                List.of(ToolCall.of("weather", "{\"city\":\"北京\"}")), "tool_calls", Usage.ZERO, null);

        ParsedOutput parsed = LlmOutputParser.parse(response);
        assertTrue(parsed.hasToolCalls());
        assertEquals(ParsedOutput.Mode.NATIVE_TOOL_CALLS, parsed.mode());
        assertEquals("weather", parsed.toolCalls().get(0).name());
        assertTrue(parsed.thought().contains("用户想知道天气"));
        assertTrue(parsed.thought().contains("我先查一下天气"));
        assertNull(parsed.finalAnswer());
    }

    @Test
    @DisplayName("无工具调用时是最终答案，并剥离 thought 标记")
    void parsesFinalAnswer() {
        LlmResponse response = LlmResponse.text("<thought>内部推理</thought>\n北京今天晴，12~22℃。");
        ParsedOutput parsed = LlmOutputParser.parse(response);

        assertEquals(ParsedOutput.Mode.FINAL_ANSWER, parsed.mode());
        assertEquals("北京今天晴，12~22℃。", parsed.finalAnswer());
        assertEquals("内部推理", parsed.thought());
        assertFalse(parsed.hasToolCalls());
    }

    @Test
    @DisplayName("兜底 1：<tool_call>{...}</tool_call> 标签")
    void parsesToolCallTag() {
        String content = """
                我需要计算一下。
                <tool_call>{"name": "calculator", "arguments": {"expression": "12*7"}}</tool_call>
                """;
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(content));
        assertEquals(ParsedOutput.Mode.TEXT_TOOL_CALLS, parsed.mode());
        assertEquals("calculator", parsed.toolCalls().get(0).name());
        assertEquals("{\"expression\":\"12*7\"}", parsed.toolCalls().get(0).argumentsJson());
    }

    @Test
    @DisplayName("兜底 2：```json 代码块里的工具调用（含嵌套参数对象）")
    void parsesFencedJsonBlock() {
        String content = """
                好的，我来查。
                ```json
                {"tool": "weather", "parameters": {"city": "上海", "date": "tomorrow"}}
                ```
                """;
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(content));
        assertEquals(ParsedOutput.Mode.TEXT_TOOL_CALLS, parsed.mode());
        assertEquals("weather", parsed.toolCalls().get(0).name());
        assertTrue(parsed.toolCalls().get(0).argumentsJson().contains("上海"));
    }

    @Test
    @DisplayName("兜底 3：ReAct 风格 Action / Action Input")
    void parsesReActStyle() {
        String content = """
                Thought: 用户要记待办
                Action: todo
                Action Input: {"action": "add", "item": "写周报"}
                """;
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(content));
        assertEquals(ParsedOutput.Mode.TEXT_TOOL_CALLS, parsed.mode());
        assertEquals("todo", parsed.toolCalls().get(0).name());
        assertTrue(parsed.thought().contains("用户要记待办"));
    }

    @Test
    @DisplayName("兜底 3：整段正文就是一个工具调用 JSON")
    void parsesWholeContentJson() {
        ParsedOutput parsed = LlmOutputParser.parse(
                LlmResponse.text("{\"name\":\"search\",\"arguments\":{\"query\":\"周报模板\"}}"));
        assertEquals(ParsedOutput.Mode.TEXT_TOOL_CALLS, parsed.mode());
        assertEquals("search", parsed.toolCalls().get(0).name());
    }

    @Test
    @DisplayName("同一文本里的多个工具调用都会被解析出来，且按内容去重")
    void parsesMultipleCallsAndDeduplicates() {
        String content = """
                <tool_call>{"name":"weather","arguments":{"city":"北京"}}</tool_call>
                <tool_call>{"name":"todo","arguments":{"action":"add","item":"发周报"}}</tool_call>
                <tool_call>{"name":"weather","arguments":{"city":"北京"}}</tool_call>
                """;
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(content));
        assertEquals(2, parsed.toolCalls().size());
        assertEquals("weather", parsed.toolCalls().get(0).name());
        assertEquals("todo", parsed.toolCalls().get(1).name());
    }

    @Test
    @DisplayName("普通作答里的 JSON 不会被误判成工具调用")
    void doesNotMistakePlainJsonForToolCall() {
        String content = """
                这是周报的关键指标配置：
                ```json
                {"指标": "P99", "目标": 210}
                ```
                你需要按这个结构填写。
                """;
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(content));
        assertEquals(ParsedOutput.Mode.FINAL_ANSWER, parsed.mode());
        assertTrue(parsed.finalAnswer().contains("P99"));
    }

    @Test
    @DisplayName("空响应解析为 EMPTY，由 Runtime 决定如何自愈")
    void parsesEmptyResponse() {
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text("   "));
        assertEquals(ParsedOutput.Mode.EMPTY, parsed.mode());
        assertFalse(parsed.hasFinalAnswer());
        assertFalse(parsed.hasToolCalls());
    }

    @Test
    @DisplayName("括号配平扫描能处理嵌套对象与字符串里的花括号")
    void extractsBalancedJson() {
        List<String> objects = LlmOutputParser.extractJsonObjects(
                "前缀 {\"a\": {\"b\": 1}, \"c\": \"}\"} 后缀 {\"d\": 2}");
        assertEquals(2, objects.size());
        assertEquals("{\"a\": {\"b\": 1}, \"c\": \"}\"}", objects.get(0));
        assertEquals("{\"d\": 2}", objects.get(1));
    }

    @Test
    @DisplayName("参数被模型序列化成字符串时也能解析")
    void handlesStringifiedArguments() {
        ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(
                "<tool_call>{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\\\"1+1\\\"}\"}</tool_call>"));
        assertEquals(1, parsed.toolCalls().size());
        assertEquals("{\"expression\":\"1+1\"}", parsed.toolCalls().get(0).argumentsJson());
    }
}
