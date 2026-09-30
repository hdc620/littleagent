package com.littleagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.littleagent.util.Texts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出解析器：把「模型输出」翻译成 Runtime 能执行的结构。
 *
 * <p>为什么需要两层解析？
 * <ol>
 *   <li><b>原生 tool_calls</b>：正规路径，OpenAI 兼容接口直接返回结构化调用。</li>
 *   <li><b>文本兜底</b>：真实项目里经常遇到「模型把工具调用写在正文里」的情况 —— 比如模型不支持
 *       function calling、网关降级、或模型自己拼了 JSON/ReAct 文本。只认原生字段会导致 Agent
 *       直接把这段 JSON 当答案回给用户，体验很差。这里实现三类兜底：
 *       <ul>
 *         <li>{@code <tool_call>{...}</tool_call>} 标签；</li>
 *         <li>```json ...``` 代码块里符合 {name|tool, arguments|parameters} 结构的对象；</li>
 *         <li>ReAct 风格 {@code Action: xxx} + {@code Action Input: {...}}。</li>
 *       </ul>
 *   </li>
 * </ol>
 * 同时抽取思维过程（reasoning_content / &lt;thought&gt; / Thought: 行），供 trace 与 UI 展示。
 */
public final class LlmOutputParser {

    private static final Pattern TOOL_CALL_TAG = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
    private static final Pattern THOUGHT_TAG = Pattern.compile("<thought>(.*?)</thought>", Pattern.DOTALL);
    private static final Pattern THOUGHT_LINE = Pattern.compile("(?m)^\\s*(?:Thought|思考|推理|分析)\\s*[:：]\\s*(.+)$");
    private static final Pattern FENCED = Pattern.compile("```(?:json|tool_call|json5)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern ACTION = Pattern.compile("(?m)^\\s*(?:Action|动作|工具)\\s*[:：]\\s*([A-Za-z0-9_\\-]+)\\s*$");
    private static final Pattern ACTION_INPUT = Pattern.compile("(?m)^\\s*(?:Action\\s*Input|ActionInput|参数|动作输入)\\s*[:：]\\s*(.+)$");

    private LlmOutputParser() {
    }

    /**
     * 解析模型输出（不做工具名过滤，等价于 {@code parse(response, null)}）。
     *
     * <p>生产路径请用 {@link #parse(LlmResponse, java.util.Set)} 并传入已注册的工具名 —— 见那个方法的说明。
     */
    public static ParsedOutput parse(LlmResponse response) {
        return parse(response, null);
    }

    /**
     * 解析模型输出，并用「已注册工具名」校验文本兜底解析出来的调用。
     *
     * <h2>为什么要传 knownTools（实测出来的坑）</h2>
     * 文本兜底只要在 JSON 里看到一个名称字段就认定是工具调用。于是模型一句完全正常的回答
     * <pre>这是接口示例：{"name": "张三", "department": "市场部"}</pre>
     * 会被解析成「调用工具 张三」，Runtime 回灌 {@code UNKNOWN_TOOL}，
     * **用户拿到的不是答案，而是一段失败的工具回灌**。
     *
     * <p>所以文本兜底必须做交叉校验：**名字不在注册表里就当普通正文**。
     * 注意两条边界（都是有意的）：
     * <ul>
     *   <li><b>原生 tool_calls 不做过滤</b>：那是模型通过 API 正式发起的调用，名字错也应该走
     *       {@code UNKNOWN_TOOL} 回灌让模型自我修正，不能悄悄丢掉；</li>
     *   <li>{@code knownTools == null} 表示「不校验」（保持单测与离线回放的可用性）。</li>
     * </ul>
     *
     * @param knownTools 已注册的工具名；null 表示不校验
     */
    public static ParsedOutput parse(LlmResponse response, Set<String> knownTools) {
        if (response == null) {
            return new ParsedOutput(null, null, List.of(), ParsedOutput.Mode.EMPTY);
        }
        String content = response.content() == null ? "" : response.content();
        String thought = Texts.blankToEmpty(response.reasoning());

        // 1) 原生 tool_calls 优先（不做名字过滤：让模型收到 UNKNOWN_TOOL 反馈去自我修正）
        if (response.hasNativeToolCalls()) {
            String fromContent = Texts.blankToEmpty(content);
            String merged = thought.isEmpty() ? fromContent : (fromContent.isEmpty() ? thought : thought + "\n" + fromContent);
            return ParsedOutput.toolCalls(merged.isEmpty() ? null : merged, response.toolCalls(),
                    ParsedOutput.Mode.NATIVE_TOOL_CALLS);
        }

        // 2) 文本兜底解析工具调用（只接受注册表里存在的名字）
        List<ToolCall> fallback = parseTextToolCalls(content, knownTools);
        String extractedThought = extractThought(content);
        String mergedThought = joinThought(thought, extractedThought);
        if (!fallback.isEmpty()) {
            return ParsedOutput.toolCalls(mergedThought, fallback, ParsedOutput.Mode.TEXT_TOOL_CALLS);
        }

        // 3) 最终答案
        String answer = stripThoughtMarkers(content).strip();
        if (answer.isEmpty()) {
            // 正文为空：有思考过程时交给 Runtime 判定（保留思考，便于排查），否则明确报告 EMPTY
            return mergedThought == null
                    ? new ParsedOutput(null, null, List.of(), ParsedOutput.Mode.EMPTY)
                    : new ParsedOutput(mergedThought, null, List.of(), ParsedOutput.Mode.EMPTY);
        }
        return ParsedOutput.finalAnswer(mergedThought, answer);
    }

    // ------------------------------------------------------------ 文本兜底解析

    static List<ToolCall> parseTextToolCalls(String content) {
        return parseTextToolCalls(content, null);
    }

    static List<ToolCall> parseTextToolCalls(String content, Set<String> knownTools) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // (a) <tool_call>...</tool_call>
        Matcher tagMatcher = TOOL_CALL_TAG.matcher(content);
        while (tagMatcher.find()) {
            collect(calls, seen, tagMatcher.group(1), knownTools);
        }

        // (b) ReAct: Action: name  +  Action Input: {...}
        Matcher actionMatcher = ACTION.matcher(content);
        if (actionMatcher.find()) {
            String name = actionMatcher.group(1).strip();
            Matcher inputMatcher = ACTION_INPUT.matcher(content);
            String arguments = "{}";
            if (inputMatcher.find()) {
                arguments = normalizeArguments(inputMatcher.group(1).strip());
            }
            add(calls, seen, name, arguments, knownTools);
        }

        // (c) ```json ... ``` 代码块中的工具调用对象
        Matcher fenced = FENCED.matcher(content);
        while (fenced.find()) {
            collect(calls, seen, fenced.group(1), knownTools);
        }

        // (d) 整段正文就是一个工具调用 JSON
        if (calls.isEmpty()) {
            String trimmed = content.strip();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                collect(calls, seen, trimmed, knownTools);
            }
        }
        return calls;
    }

    private static void collect(List<ToolCall> calls, Set<String> seen, String jsonFragment, Set<String> knownTools) {
        for (String candidate : extractJsonObjects(jsonFragment)) {
            JsonNode node = com.littleagent.util.Json.parseOrNull(candidate);
            if (node == null || !node.isObject()) {
                continue;
            }
            ToolCall call = toToolCall(node);
            if (call != null) {
                add(calls, seen, call.name(), call.argumentsJson(), knownTools);
            }
        }
    }

    /**
     * @param knownTools 已注册工具名；非 null 时**名字不在其中就不算工具调用**（回落成普通正文）
     */
    private static void add(List<ToolCall> calls, Set<String> seen, String name, String argumentsJson,
                            Set<String> knownTools) {
        if (knownTools != null && !knownTools.contains(name)) {
            return;
        }
        ToolCall call = ToolCall.of(name, argumentsJson);
        if (seen.add(call.signature())) {
            calls.add(call);
        }
    }

    /** 从若干命名约定中还原一次工具调用；不符合结构时返回 null。 */
    static ToolCall toToolCall(JsonNode node) {
        String name = firstText(node, "name", "tool", "tool_name", "toolName");
        JsonNode args = firstNode(node, "arguments", "parameters", "args", "input", "action_input", "actionInput");
        if (name == null) {
            JsonNode function = node.get("function");
            if (function != null && function.isObject()) {
                name = firstText(function, "name", "tool");
                args = firstNode(function, "arguments", "parameters", "args");
            }
        }
        if (name == null) {
            return null;
        }
        if (args == null) {
            args = com.littleagent.util.Json.obj();
        }
        String argumentsJson = args.isTextual() ? args.asText() : args.toString();
        return new ToolCall(null, name.strip(), argumentsJson.isBlank() ? "{}" : argumentsJson);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static JsonNode firstNode(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static String normalizeArguments(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            text = text.replace("```json", "").replace("```", "").strip();
        }
        return text.isEmpty() ? "{}" : text;
    }

    /** 扫描文本中所有「括号配平」的 JSON 对象（考虑字符串与转义），避免正则被嵌套对象打败。 */
    static List<String> extractJsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '{') {
                int end = matchBrace(text, i);
                if (end > i) {
                    objects.add(text.substring(i, end + 1));
                    i = end + 1;
                    continue;
                }
            }
            i++;
        }
        return objects;
    }

    private static int matchBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- 思考过程

    static String extractThought(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher tag = THOUGHT_TAG.matcher(content);
        if (tag.find()) {
            return tag.group(1).strip();
        }
        Matcher line = THOUGHT_LINE.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (line.find()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.group(1).strip());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 去掉思维链标记，避免把思维过程当答案回给用户。
     *
     * <p><b>只剥 {@code <thought>} 标签，不剥裸的「Thought/思考/推理/分析」行 —— 这是一个实测出来的坑。</b>
     * 早期实现无条件剥离匹配 {@code ^(Thought|思考|推理|分析):} 的整行，于是模型一句完全正常的回答
     * <pre>分析：接口超时是因为连接池太小。</pre>
     * 会被整行删空 → 解析成 {@link ParsedOutput.Mode#EMPTY} → Runtime 走「空响应自愈」分支、注入
     * 「你上一条回复为空」，连续两次就整轮失败。**用户的问题被彻底吞掉，比误判工具调用更糟**，
     * 而中文模型极爱用「分析：」「结论：」这类开头。
     *
     * <p>ReAct 风格的 {@code Thought:} 行不需要在这里处理：只要正文里出现 {@code Action:}，
     * {@link #parseTextToolCalls} 就会把它判定成工具调用、走另一条分支（见 {@link #ACTION}），
     * 根本不会走到最终答案路径。{@link #extractThought} 仍然会把这类行抽出来放进 thought 字段
     * （只用于 trace/UI，不回灌模型），所以观测能力不受影响。
     */
    static String stripThoughtMarkers(String content) {
        if (content == null) {
            return "";
        }
        return THOUGHT_TAG.matcher(content).replaceAll("");
    }

    private static String joinThought(String a, String b) {
        String left = Texts.blankToEmpty(a);
        String right = Texts.blankToEmpty(b);
        if (left.isEmpty()) {
            return right.isEmpty() ? null : right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "\n" + right;
    }
}
