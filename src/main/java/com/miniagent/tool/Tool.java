package com.miniagent.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具契约。一个可被 LLM 自主调用的工具 = 名称 + 描述 + 参数 Schema + 执行逻辑。
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #description()} 是模型判断「何时调用」的唯一依据，必须写清适用场景与边界；</li>
 *   <li>{@link #parametersSchema()} 返回标准 JSON Schema（type=object + properties + required）；</li>
 *   <li>{@link #execute} 允许抛异常 —— {@link ToolInvoker} 会把异常转成「工具错误结果」回灌给模型，
 *       而不是让整个 Agent 崩掉；</li>
 *   <li>实现必须线程安全：同一个工具实例会被 多个 session / 多个线程 并发调用。</li>
 * </ul>
 */
public interface Tool {

    String name();

    String description();

    ObjectNode parametersSchema();

    ToolResult execute(ObjectNode arguments, ToolContext context) throws Exception;

    /** Schema 是否声明为必填的字段（默认取 schema.required）。 */
    default java.util.List<String> requiredParameters() {
        var schema = parametersSchema();
        var required = schema == null ? null : schema.get("required");
        if (required == null || !required.isArray()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        required.forEach(node -> out.add(node.asText()));
        return out;
    }
}
