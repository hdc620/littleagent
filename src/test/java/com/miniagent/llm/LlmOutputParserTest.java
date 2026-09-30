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

    // ------------------------------------------------ 思考链剥离 与 工具名交叉校验

    @Test
    @DisplayName("中文回答以「分析：/思考：/推理：」开头时不能被当成思维链删掉")
    void keepsChineseAnswerStartingWithThoughtLikeMarker() {
        // 回归：早期实现无条件剥离 ^(Thought|思考|推理|分析): 的整行，
        // 于是一句完全正常的中文回答被删空 -> 解析成 EMPTY -> Runtime 走「空响应自愈」，
        // 连续两次整轮失败。用户的问题被彻底吞掉（比误判工具调用更严重）。
        for (String marker : List.of("分析", "思考", "推理")) {
            String content = marker + "：接口超时是因为连接池太小。";
            ParsedOutput parsed = LlmOutputParser.parse(LlmResponse.text(content));

            assertEquals(ParsedOutput.Mode.FINAL_ANSWER, parsed.mode(), marker + " 开头的回答不应被判成空响应");
            assertEquals(content, parsed.finalAnswer(), "答案必须原样返回，不能被删除");
        }
    }

    @Test
    @DisplayName("<thought> 标签仍然被剥离（思维链不回灌、也不当答案）")
    void stillStripsThoughtTagFromAnswer() {
        ParsedOutput parsed = LlmOutputParser.parse(
                LlmResponse.text("<thought>先看连接池配置</thought>\n连接池太小，建议调到 20。"));

        assertEquals(ParsedOutput.Mode.FINAL_ANSWER, parsed.mode());
        assertEquals("连接池太小，建议调到 20。", parsed.finalAnswer());
        assertEquals("先看连接池配置", parsed.thought());
    }

    @Test
    @DisplayName("文本兜底只接受已注册的工具名：普通作答里的 name 字段不会被当成工具调用")
    void textFallbackOnlyAcceptsRegisteredToolNames() {
        java.util.Set<String> known = java.util.Set.of("calculator", "search", "todo");

        // 1) 名字不在注册表里 -> 回落成普通正文（否则会被执行成「调用工具 张三」）
        ParsedOutput prose = LlmOutputParser.parse(LlmResponse.text(
                "这是接口示例：\n```json\n{\"name\": \"张三\", \"age\": 30}\n```\n请按这个结构填。"), known);
        assertEquals(ParsedOutput.Mode.FINAL_ANSWER, prose.mode());
        assertTrue(prose.finalAnswer().contains("张三"));

        // 2) 名字在注册表里 -> 正常解析成工具调用
        ParsedOutput call = LlmOutputParser.parse(LlmResponse.text(
                "<tool_call>{\"name\":\"search\",\"arguments\":{\"query\":\"周报模板\"}}</tool_call>"), known);
        assertEquals(ParsedOutput.Mode.TEXT_TOOL_CALLS, call.mode());
        assertEquals("search", call.toolCalls().get(0).name());

        // 3) 完全不认识的名字也不该变成工具调用
        ParsedOutput unknown = LlmOutputParser.parse(LlmResponse.text(
                "{\"tool\": \"delete_all_files\", \"parameters\": {}}"), known);
        assertEquals(ParsedOutput.Mode.FINAL_ANSWER, unknown.mode());
    }

    @Test
    @DisplayName("原生 tool_calls 不做名字过滤（幻觉工具应交给 UNKNOWN_TOOL 回灌去纠正）")
    void nativeToolCallsAreNotFilteredByName() {
        LlmResponse response = new LlmResponse(null, null,
                List.of(ToolCall.of("send_email", "{\"to\":\"boss\"}")), "tool_calls", Usage.ZERO, null);

        ParsedOutput parsed = LlmOutputParser.parse(response, java.util.Set.of("calculator"));

        assertEquals(ParsedOutput.Mode.NATIVE_TOOL_CALLS, parsed.mode());
        assertEquals("send_email", parsed.toolCalls().get(0).name());
    }

    @Test
    @DisplayName("不传注册表时保持旧行为（不校验），便于单测与离线回放")
    void withoutKnownToolsNoFiltering() {
        ParsedOutput parsed = LlmOutputParser.parse(
                LlmResponse.text("{\"name\":\"张三\",\"arguments\":{}}"));
        assertEquals(ParsedOutput.Mode.TEXT_TOOL_CALLS, parsed.mode());
    }
}
