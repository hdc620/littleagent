package com.littleagent.context;

import com.littleagent.llm.LlmClient;
import com.littleagent.llm.LlmRequest;
import com.littleagent.llm.LlmResponse;
import com.littleagent.llm.Message;
import com.littleagent.llm.Usage;
import com.littleagent.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆召回器与摘要降级策略。 */
class MemoryRecallerTest {

    private final MemoryRecaller recaller = new MemoryRecaller();

    @Test
    @DisplayName("按关键词相关度召回，与问题无关的历史不进入上下文")
    void recallsByRelevance() {
        List<Message> pool = List.of(
                Message.user("帮我查一下杭州的天气").withSeq(1),
                Message.assistant("杭州今天多云", null).withSeq(2),
                Message.user("帮我记个待办：买咖啡").withSeq(3),
                Message.assistant("已记录", null).withSeq(4));

        List<Message> recalled = recaller.recall(pool, "杭州那边天气怎么样？", 3);

        assertFalse(recalled.isEmpty());
        assertTrue(recalled.stream().anyMatch(m -> m.content().contains("杭州")));
        assertTrue(recalled.stream().noneMatch(m -> m.content().contains("咖啡")));
    }

    @Test
    @DisplayName("召回结果按时间正序返回，便于模型理解先后关系")
    void keepsChronologicalOrder() {
        List<Message> pool = List.of(
                Message.user("杭州天气").withSeq(1),
                Message.user("杭州美食推荐").withSeq(2),
                Message.user("杭州交通").withSeq(3));

        List<Message> recalled = recaller.recall(pool, "杭州", 3);
        assertEquals(List.of(1L, 2L, 3L), recalled.stream().map(Message::seq).toList());
    }

    @Test
    @DisplayName("topK 限制生效，且只取分数最高的若干条")
    void respectsTopK() {
        List<Message> pool = List.of(
                Message.user("杭州").withSeq(1),
                Message.user("杭州杭州杭州").withSeq(2),
                Message.user("杭州西湖").withSeq(3));
        assertEquals(1, recaller.recall(pool, "杭州", 1).size());
    }

    @Test
    @DisplayName("空 query / 空池 / topK=0 都安全返回")
    void handlesEdgeCases() {
        assertTrue(recaller.recall(List.of(), "杭州", 3).isEmpty());
        assertTrue(recaller.recall(List.of(Message.user("x")), "", 3).isEmpty());
        assertTrue(recaller.recall(List.of(Message.user("杭州")), "杭州", 0).isEmpty());
    }

    @Test
    @DisplayName("渲染出的召回块带来源标注（序号 + 角色）")
    void rendersFragmentsWithProvenance() {
        String rendered = recaller.render(List.of(Message.user("杭州天气").withSeq(7)));
        assertTrue(rendered.contains("#7"));
        assertTrue(rendered.contains("user"));
        assertTrue(rendered.contains("杭州天气"));
    }

    @Test
    @DisplayName("LLM 摘要失败时降级为确定性摘要，绝不阻塞对话")
    void summarizerFallsBackOnFailure() {
        LlmClient broken = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request, Tracer tracer) {
                throw new IllegalStateException("模拟网络故障");
            }
        };
        List<Message> toCompress = List.of(
                Message.user("帮我查杭州天气"),
                Message.tool("c1", "weather", "杭州多云 18~25℃"),
                Message.assistant("杭州今天多云。", null));

        LlmSummarizer summarizer = new LlmSummarizer(broken, "test-model");
        String summary = summarizer.summarize("", toCompress, null);

        assertTrue(summary.contains("用户诉求"));
        assertTrue(summary.contains("杭州"));
        assertTrue(summary.contains("确定性压缩"));
    }

    @Test
    @DisplayName("LLM 摘要返回空内容时同样降级")
    void summarizerFallsBackOnEmptyOutput() {
        LlmClient emptyClient = (request, tracer) -> LlmResponse.text("   ");
        String summary = new LlmSummarizer(emptyClient, "m").summarize("旧摘要", List.of(Message.user("问题")), null);
        assertTrue(summary.contains("确定性压缩") || summary.contains("旧摘要"));
    }

    @Test
    @DisplayName("LLM 摘要正常时使用模型输出，并与已有摘要一起送进提示词")
    void summarizerUsesModelOutput() {
        StringBuilder captured = new StringBuilder();
        LlmClient client = (request, tracer) -> {
            request.messages().stream().filter(m -> m.content().contains("已有摘要"))
                    .findFirst().ifPresent(m -> captured.append(m.content()));
            return new LlmResponse("- 用户想查天气\n- 待办：发周报", null, List.of(), "stop", Usage.ZERO, null);
        };
        String summary = new LlmSummarizer(client, "m")
                .summarize("旧摘要：用户问过北京", List.of(Message.user("再查一次")), null);

        assertTrue(summary.startsWith("- 用户想查天气"));
        assertTrue(captured.toString().contains("旧摘要：用户问过北京"));
    }

    @Test
    @DisplayName("压缩用的 LLM 调用必须写进会话 trace（否则「一次问答打了几次 API」对不上账）")
    void summarizerWritesTraceWithSessionTracer() {
        LlmClient client = (request, tracer) -> {
            tracer.event("llm_request", "summarizer");
            return new LlmResponse("- 用户想查天气", null, List.of(), "stop",
                    Usage.of(123, 45), null);
        };
        Tracer tracer = Tracer.of("s-compress");

        String summary = new LlmSummarizer(client, "m").summarize("", List.of(Message.user("查天气")), tracer);

        assertEquals("- 用户想查天气", summary);
        // 回归：早期实现传的是 Tracer.noop()，实测一次 10 轮对话里 19 次真实 API 调用
        // 有 6 次在 trace 里完全查不到，压缩耗的 1s+ 也没有归属。
        assertTrue(tracer.events().stream().anyMatch(e -> e.type().equals("llm_request")),
                "压缩调用必须落到会话级 tracer 上");
    }

    @Test
    @DisplayName("tracer 为 null 时压缩依然可用（降级为 noop，不抛 NPE）")
    void summarizerToleratesNullTracer() {
        LlmClient client = (request, tracer) -> LlmResponse.text("- 摘要");
        assertEquals("- 摘要", new LlmSummarizer(client, "m").summarize("", List.of(Message.user("x")), null));
    }

    @Test
    @DisplayName("确定性摘要保留用户诉求与工具结论（压缩不丢事实）")
    void deterministicSummaryKeepsFacts() {
        List<Message> messages = List.of(
                Message.user("帮我查北京天气并记待办：发周报"),
                Message.tool("c1", "weather", "北京阵雨 7~17℃"),
                Message.tool("c2", "todo", "已加入待办 #1：发周报"),
                Message.assistant("已记录。", null));

        String summary = new DeterministicSummarizer().summarize("", messages, null);

        assertTrue(summary.contains("发周报"));
        assertTrue(summary.contains("北京阵雨"));
        assertTrue(summary.contains("用户诉求"));
        assertTrue(summary.length() <= 1300);
    }
}
