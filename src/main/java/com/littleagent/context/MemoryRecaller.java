package com.littleagent.context;

import com.littleagent.llm.Message;
import com.littleagent.util.Texts;
import com.littleagent.util.TokenEstimator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 记忆召回器（情节记忆）。
 *
 * <p>召回时机：<b>每次调用 LLM 之前</b>，用「当前用户输入」作为 query，
 * 在被压缩/滑出窗口的旧历史里检索相关片段，作为 system 消息补充进上下文。
 *
 * <p>为什么需要它：压缩摘要是「全局而有损」的，一旦用户翻旧账（「刚才说的那个城市」），
 * 摘要里可能只剩一句概括。按 query 精确召回原始片段，能补回细节。
 *
 * <p>打分 = 关键词重叠（CJK bigram / 英文词） × 角色权重 × 时间衰减。
 * 这是刻意保持简单的「可解释召回」：不引向量库，便于测试与讲解；生产可整体替换为向量检索。
 */
public final class MemoryRecaller {

    private final int fragmentMaxChars;

    public MemoryRecaller() {
        this(400);
    }

    public MemoryRecaller(int fragmentMaxChars) {
        this.fragmentMaxChars = fragmentMaxChars;
    }

    /**
     * @param pool  候选历史（通常是「最近窗口之前」的全部消息）
     * @param query 当前用户输入
     * @param topK  最多召回多少条
     * @return 按时间正序排列的命中消息
     */
    public List<Message> recall(List<Message> pool, String query, int topK) {
        if (pool == null || pool.isEmpty() || topK <= 0 || Texts.isBlank(query)) {
            return List.of();
        }
        var queryTokens = Texts.tokenSet(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        record Scored(Message message, double score, int index) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            Message message = pool.get(i);
            String text = message.content();
            if (Texts.isBlank(text)) {
                continue;
            }
            var tokens = Texts.tokenSet(text);
            double overlap = 0;
            for (String token : queryTokens) {
                if (tokens.contains(token)) {
                    overlap += token.length() >= 2 ? 1.0 : 0.4;
                }
            }
            if (overlap <= 0) {
                continue;
            }
            double roleWeight = switch (message.role()) {
                case USER -> 1.6;
                case TOOL -> 1.2;
                default -> 1.0;
            };
            double recency = 0.6 + 0.4 * (pool.size() <= 1 ? 1.0 : (double) i / (pool.size() - 1));
            // 长消息做轻度惩罚，避免一段长工具输出压过精准的短句
            double lengthPenalty = 1.0 / (1.0 + Math.log10(1.0 + text.length() / 500.0));
            scored.add(new Scored(message, overlap * roleWeight * recency * lengthPenalty, i));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> top = scored.size() > topK ? scored.subList(0, topK) : scored;
        List<Scored> chronological = new ArrayList<>(top);
        chronological.sort(Comparator.comparingInt(Scored::index));
        List<Message> result = new ArrayList<>(chronological.size());
        for (Scored s : chronological) {
            result.add(s.message());
        }
        return result;
    }

    /** 把召回片段渲染成一段 system 文本（带来源标注，方便模型引用也方便人排查）。 */
    public String render(List<Message> recalled) {
        if (recalled == null || recalled.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【相关历史片段（按当前问题自动召回，可能不完整）】\n");
        for (Message message : recalled) {
            sb.append("- #").append(message.seq()).append(' ').append(message.role().wireName())
                    .append(": ").append(Texts.oneLine(message.content(), fragmentMaxChars)).append('\n');
        }
        return sb.toString().strip();
    }

    public int estimatedTokens(List<Message> recalled) {
        return TokenEstimator.estimate(render(recalled));
    }
}
