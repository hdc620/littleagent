package com.miniagent.tool.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.tool.SchemaValidator;
import com.miniagent.tool.Tool;
import com.miniagent.tool.ToolContext;
import com.miniagent.tool.ToolResult;
import com.miniagent.util.Json;

import java.util.List;
import java.util.Map;

/**
 * 工具 2：检索（mock 实现：本地知识库全文打分检索，不联网）。
 *
 * <p>真实项目里这里会换成向量库 / 搜索引擎；接口形状（query + top_k -&gt; 片段列表）保持一致，
 * 因此替换实现不影响 Runtime。
 */
public final class SearchTool implements Tool {

    private final KnowledgeBase knowledgeBase;

    public SearchTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "在本地知识库中检索资料片段（模拟搜索引擎，数据源为 docs/knowledge 下的 Markdown）。"
                + "当问题涉及「什么是 / 怎么做 / 有没有模板或规范 / 设计要点」等需要查文档的内容时使用。"
                + "返回文档 id、章节标题与片段；如需完整原文，再用 read_docs 按 doc_id 读取。"
                + "不要用它查询天气或做数学计算。";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode query = Json.obj();
        query.put("type", "string");
        query.put("description", "检索关键词或自然语言问题，越具体越好");
        query.put("minLength", 1);

        ObjectNode topK = Json.obj();
        topK.put("type", "integer");
        topK.put("description", "返回条数，默认 3，最多 10");
        topK.put("minimum", 1);
        topK.put("maximum", 10);

        return SchemaValidator.objectSchema(
                Map.of("query", query, "top_k", topK), List.of("query"));
    }

    @Override
    public ToolResult execute(ObjectNode arguments, ToolContext context) {
        String query = arguments.path("query").asText("").strip();
        int topK = arguments.path("top_k").asInt(3);
        if (knowledgeBase.documents().isEmpty()) {
            return ToolResult.ok("知识库为空（docs/knowledge 下没有文档），无法检索。请直接根据已有知识回答，或告知用户知识库未配置。");
        }
        List<KnowledgeBase.Hit> hits = knowledgeBase.search(query, topK);
        if (context != null && context.session() != null) {
            context.session().memory().putFact("最近检索", query);
        }
        if (hits.isEmpty()) {
            return ToolResult.ok("没有检索到与「" + query + "」相关的片段。可用文档: "
                    + String.join(", ", knowledgeBase.documentIds()) + "。可以换关键词再试，或用 read_docs 直接读取某篇文档。");
        }
        StringBuilder sb = new StringBuilder("检索「").append(query).append("」命中 ").append(hits.size()).append(" 段：\n");
        int i = 1;
        for (KnowledgeBase.Hit hit : hits) {
            sb.append(i++).append(". [").append(hit.section().docId()).append(" § ")
                    .append(hit.section().heading()).append("] 相关度 ").append(hit.score()).append('\n')
                    .append("   片段：").append(hit.snippet().replace("\n", "\n   ")).append('\n');
        }
        return ToolResult.ok(sb.toString().strip());
    }
}
