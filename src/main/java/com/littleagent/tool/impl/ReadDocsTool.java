package com.littleagent.tool.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.tool.SchemaValidator;
import com.littleagent.tool.Tool;
import com.littleagent.tool.ToolContext;
import com.littleagent.tool.ToolResult;
import com.littleagent.util.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具 3：读文档。
 *
 * <p>与 search 的分工：search 负责「找到哪几段相关」，read_docs 负责「把原文按需读全」。
 * 这种「先检索后精读」的两段式是控制上下文预算的常用手法（只把真正需要的原文塞进 context）。
 * 支持按行分页（offset/limit），避免一次把长文档灌满上下文。
 */
public final class ReadDocsTool implements Tool {

    private static final int DEFAULT_LIMIT = 80;
    private static final int MAX_LIMIT = 200;

    private final KnowledgeBase knowledgeBase;

    public ReadDocsTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public String name() {
        return "read_docs";
    }

    @Override
    public String description() {
        return "按 doc_id 读取本地知识库文档正文，支持分页（offset/limit），用于需要引用规范、模板原文的场景。"
                + "doc_id 形如 AGENT-001 / CTX-002，可先用 search 获得。"
                + "如果返回内容被截断，可用更大的 offset 继续读下一页。";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode docId = Json.obj();
        docId.put("type", "string");
        docId.put("description", "文档 id，例如 AGENT-001");
        docId.put("minLength", 1);

        ObjectNode offset = Json.obj();
        offset.put("type", "integer");
        offset.put("description", "起始行号（从 0 开始），默认 0");
        offset.put("minimum", 0);

        ObjectNode limit = Json.obj();
        limit.put("type", "integer");
        limit.put("description", "读取行数，默认 " + DEFAULT_LIMIT + "，最多 " + MAX_LIMIT);
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);

        return SchemaValidator.objectSchema(
                Map.of("doc_id", docId, "offset", offset, "limit", limit), List.of("doc_id"));
    }

    @Override
    public ToolResult execute(ObjectNode arguments, ToolContext context) {
        String docId = arguments.path("doc_id").asText("").strip();
        int offset = Math.max(0, arguments.path("offset").asInt(0));
        int limit = arguments.path("limit").asInt(DEFAULT_LIMIT);
        limit = Math.min(Math.max(1, limit), MAX_LIMIT);

        Optional<KnowledgeBase.Document> maybeDoc = knowledgeBase.findById(docId);
        if (maybeDoc.isEmpty()) {
            return ToolResult.error("DOC_NOT_FOUND", "找不到文档 `" + docId + "`。可用文档: "
                    + String.join(", ", knowledgeBase.documentIds()) + "。请换一个 doc_id。");
        }
        KnowledgeBase.Document document = maybeDoc.get();
        List<String> lines = document.lines();
        int from = Math.min(offset, lines.size());
        int to = Math.min(lines.size(), from + limit);
        StringBuilder sb = new StringBuilder();
        sb.append("文档 ").append(document.id()).append("（").append(document.title()).append("）")
                .append(" 第 ").append(from).append('-').append(to).append(" 行 / 共 ").append(lines.size()).append(" 行\n");
        sb.append(String.join("\n", lines.subList(from, to)));
        if (to < lines.size()) {
            sb.append("\n...（未读完，可继续 offset=").append(to).append("）");
        }
        if (context != null && context.session() != null) {
            context.session().memory().putFact("最近阅读", document.id());
        }
        return ToolResult.ok(sb.toString());
    }
}
