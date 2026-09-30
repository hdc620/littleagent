package com.littleagent.context;

import com.littleagent.llm.LlmClient;
import com.littleagent.llm.LlmRequest;
import com.littleagent.llm.LlmResponse;
import com.littleagent.llm.Message;
import com.littleagent.llm.ToolCall;
import com.littleagent.trace.Tracer;
import com.littleagent.util.Texts;

import java.util.List;

/**
 * 用真实 LLM 做历史压缩。
 *
 * <p>压缩提示词的三条纪律：
 * <ol>
 *   <li>只保留「目标 / 结论 / 未决问题」，丢掉寒暄与重复；</li>
 *   <li>工具结论里的<b>事实</b>（待办、城市、数值）必须保留，否则后续追问会答错；</li>
 *   <li>与已有摘要增量合并，而不是每次从零重写，避免早期信息在多轮压缩中被逐步稀释。</li>
 * </ol>
 *
 * <p>失败降级：任何异常（网络、限流、输出为空）都退化为 {@link DeterministicSummarizer}，
 * 保证「压缩」这个动作永远不会阻塞对话。
 */
public final class LlmSummarizer implements Summarizer {

    public static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个对话历史压缩器，为 Agent 的长期记忆服务。
            请把给定的历史对话压缩成一段中文摘要，要求：
            1. 保留用户的目标、约束、偏好，以及已经确认的关键事实（城市、数字、待办事项、文档编号等）；
            2. 保留尚未解决的问题，以及已经失败过的尝试（避免重复踩坑）；
            3. 丢弃寒暄、重复内容、工具调用的原始 JSON 与推理过程；
            4. 控制在 300 字以内，用「- 」开头的要点列表组织；
            5. 只输出摘要正文，不要任何解释、前缀或 Markdown 代码块。
            """;

    private static final int TRANSCRIPT_CHARS_PER_MESSAGE = 300;

    private final LlmClient llm;
    private final String model;
    private final Summarizer fallback;

    public LlmSummarizer(LlmClient llm, String model) {
        this(llm, model, new DeterministicSummarizer());
    }

    public LlmSummarizer(LlmClient llm, String model, Summarizer fallback) {
        this.llm = llm;
        this.model = model;
        this.fallback = fallback == null ? new DeterministicSummarizer() : fallback;
    }

    @Override
    public String summarize(String previousSummary, List<Message> messagesToCompress, Tracer tracer) {
        if (messagesToCompress == null || messagesToCompress.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        try {
            String transcript = renderTranscript(messagesToCompress);
            StringBuilder user = new StringBuilder();
            if (previousSummary != null && !previousSummary.isBlank()) {
                user.append("已有摘要（请在其基础上增量合并，不要丢失其中的事实）：\n")
                        .append(previousSummary.strip()).append("\n\n");
            }
            user.append("待压缩对话：\n").append(transcript);

            LlmRequest request = LlmRequest.builder()
                    .model(model)
                    .temperature(0.0)
                    .maxTokens(700)
                    .addMessage(Message.system(SUMMARY_SYSTEM_PROMPT))
                    .addMessage(Message.user(user.toString()))
                    .build();

            // 用会话级 tracer（不是 noop）：压缩调用必须和主循环调用一样在 trace 里可见，
            // 否则「一次问答实际打了几次 API、花了多少 token、慢在哪」全都对不上账
            // （实测曾经有 32% 的 API 调用在 trace 里查不到）。
            LlmResponse response = llm.chat(request, tracer == null ? Tracer.noop() : tracer);
            String text = response.content();
            if (Texts.isBlank(text)) {
                return fallback.summarize(previousSummary, messagesToCompress, tracer);
            }
            return text.strip();
        } catch (RuntimeException e) {
            return fallback.summarize(previousSummary, messagesToCompress, tracer);
        }
    }

    @Override
    public String name() {
        return "LlmSummarizer";
    }

    static String renderTranscript(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append('[').append(message.role().wireName()).append("] ");
            if (!Texts.isBlank(message.content())) {
                sb.append(Texts.oneLine(message.content(), TRANSCRIPT_CHARS_PER_MESSAGE));
            }
            if (message.hasToolCalls()) {
                for (ToolCall call : message.toolCalls()) {
                    sb.append(" (调用工具 ").append(call.name()).append(' ')
                            .append(Texts.oneLine(call.argumentsJson(), 120)).append(')');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
