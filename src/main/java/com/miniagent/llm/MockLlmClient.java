package com.miniagent.llm;

import com.miniagent.trace.Tracer;
import com.miniagent.util.Json;
import com.miniagent.util.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线演示用的「假 LLM」：用关键词规则模拟一个会决策的模型，<b>不发任何网络请求</b>。
 *
 * <p>存在意义：
 * <ul>
 *   <li>评审者没有 API Key 时，也能把「输入 -&gt; 决策 -&gt; 工具 -&gt; 结果回灌 -&gt; 收尾」整条链路跑通；</li>
 *   <li>演示 session 隔离、待办记忆、上下文压缩、trace 等与模型能力无关的 Runtime 特性。</li>
 * </ul>
 *
 * <p><b>它不是真实 LLM</b>：不做推理，只做模式匹配，也无法处理开放式问题。
 * 真实运行必须使用 {@link OpenAiCompatibleClient}。本类在 CLI 中通过 {@code --mock} 显式启用。
 */
public class MockLlmClient implements LlmClient {

    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆", "苏州", "天津");
    /** 数学片段：允许字母（函数名）、数字、运算符、括号、逗号、空白 */
    private static final Pattern MATH_FRAGMENT = Pattern.compile("[0-9a-zA-Z_+\\-*/().^%\\s,]+");
    private static final Pattern DOC_ID = Pattern.compile("\\b([A-Z]{3,}-\\d{3})\\b");
    private static final Pattern TODO_ID = Pattern.compile("#(\\d+)");

    @Override
    public LlmResponse chat(LlmRequest request, Tracer tracer) {
        List<Message> messages = request.messages();
        if (messages.isEmpty()) {
            return LlmResponse.text("（mock）没有收到任何输入。");
        }
        Message last = messages.get(messages.size() - 1);
        if (last.role() == Role.TOOL) {
            LlmResponse followUp = planFollowUpToolCall(messages);
            return followUp != null ? followUp : summarizeToolResults(messages);
        }
        String userText = lastUserText(messages);
        List<ToolCall> calls = planCalls(userText, request.tools());
        if (!calls.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (ToolCall call : calls) {
                names.append(names.length() == 0 ? "" : "、").append(call.name());
            }
            return LlmResponse.withToolCalls("（mock）判断需要调用工具：" + names, calls);
        }
        return LlmResponse.text("（mock 模式回复）收到：「" + Texts.oneLine(userText, 60) + "」。"
                + "当前是离线 mock 模式，只做关键词决策、不会真正推理；"
                + "请配置 DEEPSEEK_API_KEY 体验真实模型（或改用 --demo 查看完整场景演示）。");
    }

    @Override
    public String clientName() {
        return "MockLlmClient";
    }

    // ------------------------------------------------------------ 第一跳决策

    /** 规则决策：识别「查天气 / 计算 / 记待办 / 查待办 / 完成待办 / 检索知识库 / 读文档」等意图。 */
    public static List<ToolCall> planCalls(String userText, List<ToolSpec> availableTools) {
        List<ToolCall> calls = new ArrayList<>();
        if (userText == null) {
            return calls;
        }
        List<String> names = new ArrayList<>();
        for (ToolSpec spec : availableTools) {
            names.add(spec.name());
        }

        if (names.contains("weather")) {
            String city = detectCity(userText);
            if (city != null && containsAny(userText, "天气", "气温", "weather", "下雨", "冷不冷", "热不热")) {
                calls.add(ToolCall.of("weather", args(weatherArgs(userText, city))));
            }
        }
        if (names.contains("calculator")) {
            String expression = detectExpression(userText);
            if (expression != null
                    && containsAny(userText, "算", "计算", "等于", "多少", "+", "-", "*", "/", "^", "%")) {
                calls.add(ToolCall.of("calculator", args(Map.of("expression", expression))));
            }
        }
        if (names.contains("todo")) {
            String item = detectTodoItem(userText);
            if (item != null) {
                calls.add(ToolCall.of("todo", args(Map.of("action", "add", "item", item))));
            } else if (wantsTodoList(userText) || wantsTodoDone(userText)) {
                // 想「查看」或「完成」待办，先 list 拿到编号（真实模型也会这么做）
                calls.add(ToolCall.of("todo", args(Map.of("action", "list"))));
            }
        }
        if (names.contains("search") && wantsSearch(userText, calls.isEmpty())) {
            calls.add(ToolCall.of("search", args(Map.of("query", Texts.oneLine(searchQuery(userText), 40)))));
        }
        if (names.contains("read_docs") && calls.isEmpty() && wantsDocBody(userText)) {
            Matcher matcher = DOC_ID.matcher(userText);
            if (matcher.find()) {
                calls.add(ToolCall.of("read_docs", args(Map.of("doc_id", matcher.group(1)))));
            }
        }
        return calls.size() > 3 ? new ArrayList<>(calls.subList(0, 3)) : calls;
    }

    // -------------------------------------------------------- 工具结果后决策

    /**
     * 第二跳：拿到工具结果后，是否需要再调一次工具。
     *
     * <p>典型场景：用户说「把它标记完成」，但模型还不知道待办编号，
     * 于是先 list（第一跳），拿到 {@code #1 ... [未完成]} 后这里再发 done。
     */
    static LlmResponse planFollowUpToolCall(List<Message> messages) {
        int lastUserIdx = lastUserIndex(messages);
        String userText = lastUserIdx >= 0 ? messages.get(lastUserIdx).content() : "";
        if (!wantsTodoDone(userText)) {
            return null;
        }
        boolean alreadyCompleted = false;
        String pendingListResult = null;
        for (int i = lastUserIdx + 1; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.role() != Role.TOOL || !"todo".equals(m.toolName())) {
                continue;
            }
            if (m.content().contains("已完成 #")) {
                alreadyCompleted = true;
            }
            if (m.content().contains("[未完成]")) {
                pendingListResult = m.content();
            }
        }
        if (alreadyCompleted || pendingListResult == null) {
            return null;
        }
        Matcher matcher = TODO_ID.matcher(pendingListResult);
        if (!matcher.find()) {
            return null;
        }
        int id = Integer.parseInt(matcher.group(1));
        return LlmResponse.withToolCalls("（mock）从清单里拿到待办 #" + id + "，现在标记完成",
                List.of(ToolCall.of("todo", args(Map.of("action", "done", "id", id)))));
    }

    private static LlmResponse summarizeToolResults(List<Message> messages) {
        int lastUserIdx = lastUserIndex(messages);
        List<Message> toolMessages = new ArrayList<>();
        boolean hasError = false;
        for (int i = lastUserIdx + 1; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.role() == Role.TOOL) {
                toolMessages.add(m);
                hasError |= m.content().startsWith("[TOOL_ERROR]");
            }
        }
        if (toolMessages.isEmpty()) {
            return LlmResponse.text("（mock）没有拿到工具结果。");
        }
        // 单一 todo 结果直接回显，读起来更像人话
        if (toolMessages.size() == 1 && "todo".equals(toolMessages.get(0).toolName())) {
            return LlmResponse.text("（mock 模式）本窗口的待办：\n" + toolMessages.get(0).content());
        }
        StringBuilder sb = new StringBuilder("（mock 模式汇总）工具执行完成，共 ")
                .append(toolMessages.size()).append(" 项：\n");
        for (Message m : toolMessages) {
            sb.append("- [").append(m.toolName()).append("] ").append(Texts.oneLine(m.content(), 200)).append('\n');
        }
        if (hasError) {
            sb.append("其中存在失败项；真实模型会据此修正参数后重试。");
        }
        return LlmResponse.text(sb.toString().strip());
    }

    // -------------------------------------------------------------- 规则细节

    private static Map<String, Object> weatherArgs(String text, String city) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("city", city);
        if (text.contains("明天") || text.contains("明日")) {
            values.put("date", "tomorrow");
        } else if (text.contains("昨天")) {
            values.put("date", "yesterday");
        }
        return values;
    }

    private static boolean wantsSearch(String text, boolean noOtherIntent) {
        if (containsAny(text, "知识库", "模板", "文档", "资料", "规范", "read_docs")) {
            return true;
        }
        return noOtherIntent && containsAny(text, "搜", "查资料", "怎么写", "什么是", "介绍", "设计要点");
    }

    private static String searchQuery(String text) {
        String cleaned = text;
        int todoIdx = cleaned.indexOf("待办");
        if (todoIdx > 4) {
            cleaned = cleaned.substring(0, todoIdx);
        }
        cleaned = cleaned.replaceAll("^(帮我|请|麻烦)?(先)?(查一下|查查|搜一下|搜搜|检索)", "");
        return cleaned.strip();
    }

    static boolean wantsTodoList(String text) {
        if (!containsAny(text, "待办", "todo", "TODO")) {
            return false;
        }
        return containsAny(text, "有哪些", "是什么", "有什么", "还在", "清单", "查看", "看看", "列出", "全部", "没做");
    }

    static boolean wantsTodoDone(String text) {
        return containsAny(text, "标记成已完成", "标记完成", "标记为完成", "已经完成", "完成了", "做完了", "勾掉", "打完收工");
    }

    private static boolean wantsDocBody(String text) {
        return containsAny(text, "读", "看", "打开", "正文", "全文") && DOC_ID.matcher(text).find();
    }

    static String lastUserText(List<Message> messages) {
        int idx = lastUserIndex(messages);
        return idx < 0 ? "" : messages.get(idx).content();
    }

    private static int lastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return i;
            }
        }
        return -1;
    }

    static String detectCity(String text) {
        for (String city : CITIES) {
            if (text.contains(city)) {
                return city;
            }
        }
        return null;
    }

    /**
     * 从自然语言里抠出数学表达式片段（mock 专用）。
     *
     * <p>要点：中文与算式之间没有空格分隔，所以先按「允许的字符集」切出候选片段，
     * 再要求片段同时含数字与运算符（或函数调用），取最长者。
     * 例如「帮我算一下 sqrt(144)+pow(2,10)」-&gt; {@code sqrt(144)+pow(2,10)}。
     */
    static String detectExpression(String text) {
        Matcher matcher = MATH_FRAGMENT.matcher(text);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group().strip().replaceAll("[,，]+$", "").strip();
            if (candidate.isEmpty() || candidate.chars().noneMatch(Character::isDigit)) {
                continue;
            }
            boolean hasOperator = candidate.chars().anyMatch(c -> "+-*/^%".indexOf(c) >= 0);
            boolean hasFunctionCall = candidate.matches(".*[a-zA-Z_]\\s*\\(.*");
            if (!hasOperator && !hasFunctionCall) {
                continue;
            }
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    static String detectTodoItem(String text) {
        String[] triggers = {"待办：", "待办:", "加到待办", "记个待办", "记一下", "记个", "提醒我", "别忘了", "todo:", "TODO:"};
        for (String trigger : triggers) {
            int idx = text.indexOf(trigger);
            if (idx < 0) {
                continue;
            }
            String rest = text.substring(idx + trigger.length()).strip();
            for (String stop : new String[]{"。", "，", ",", "；", ";", "\n"}) {
                int stopIdx = rest.indexOf(stop);
                if (stopIdx > 0) {
                    rest = rest.substring(0, stopIdx);
                }
            }
            rest = rest.strip();
            if (!rest.isEmpty()) {
                return Texts.oneLine(rest, 60);
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String args(Map<String, Object> values) {
        return Json.write(new LinkedHashMap<>(values));
    }
}
