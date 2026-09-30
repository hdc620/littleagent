package com.littleagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 暴露给模型的工具声明（对应 OpenAI tools[].function）。
 *
 * @param name        工具名
 * @param description 工具描述：模型判断「何时调用」的唯一依据
 * @param parameters  JSON Schema
 */
public record ToolSpec(String name, String description, JsonNode parameters) {
}
