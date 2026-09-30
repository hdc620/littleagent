package com.littleagent.context;

import com.littleagent.llm.Message;
import com.littleagent.llm.Role;
import com.littleagent.trace.Tracer;
import com.littleagent.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性压缩：不调用 LLM，纯代码抽取「用户诉求 + 工具结论」。
 *
 * <p>两个用途：
 * <ol>
 *   <li>LLM 摘要失败时的兜底（网络抖动、超预算都不该让 Agent 停摆）；</li>
 *   <li>离线 mock 模式下的压缩路径（不消耗 API）。</li>
 * </ol>
 *
 * <p>因为不调 LLM，它**不需要 tracer**（参数被有意忽略）。
 */
public final class DeterministicSummarizer implements Summarizer {

    private static final int MAX_CHARS = 1200;

    @Override
    public String summarize(String previousSummary, List<Message> messagesToCompress, Tracer tracer) {
        List<String> userAsks = new ArrayList<>();
        List<String> toolOutcomes = new ArrayList<>();
        List<String> answers = new ArrayList<>();

        for (Message message : messagesToCompress) {
            switch (message.role()) {
                case USER -> {
                    String text = Texts.oneLine(message.content(), 120);
                    if (!text.isBlank()) {
                        userAsks.add(text);
                    }
                }
                case TOOL -> {
                    String text = Texts.oneLine(message.content(), 120);
                    if (!text.isBlank()) {
                        toolOutcomes.add("[" + message.toolName() + "] " + text);
                    }
                }
                case ASSISTANT -> {
                    String text = Texts.oneLine(message.content(), 120);
                    if (!text.isBlank()) {
                        answers.add(text);
                    }
                }
                default -> {
                    // system 消息不参与压缩
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append(previousSummary.strip()).append('\n');
        }
        sb.append("（确定性压缩，共 ").append(messagesToCompress.size()).append(" 条消息）\n");
        appendList(sb, "用户诉求", userAsks, 6);
        appendList(sb, "工具结论", toolOutcomes, 8);
        appendList(sb, "已给出的回答要点", answers, 4);
        String text = sb.toString().strip();
        return Texts.truncate(text, MAX_CHARS);
    }

    private static void appendList(StringBuilder sb, String title, List<String> items, int limit) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(title).append("：\n");
        int count = Math.min(items.size(), limit);
        for (int i = 0; i < count; i++) {
            sb.append("  ").append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
        if (items.size() > count) {
            sb.append("  ...（另有 ").append(items.size() - count).append(" 条已省略）\n");
        }
    }

    /** 判断消息是否值得进入摘要（过滤掉空的 assistant 消息）。 */
    static boolean isMeaningful(Message message) {
        return message.role() != Role.SYSTEM && !Texts.isBlank(message.content());
    }
}
