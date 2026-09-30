package com.littleagent.llm;

import com.littleagent.util.Json;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次工具调用请求（模型输出）。
 *
 * @param id            模型给出的调用 id，工具结果必须用同一个 id 回灌
 * @param name          工具名
 * @param argumentsJson 参数 JSON 字符串（保持字符串形态，便于原样回灌与校验）
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(name, "name");
        if (id == null || id.isBlank()) {
            id = newId();
        }
        if (argumentsJson == null || argumentsJson.isBlank()) {
            argumentsJson = "{}";
        }
    }

    public static ToolCall of(String name, String argumentsJson) {
        return new ToolCall(newId(), name, argumentsJson);
    }

    public static String newId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 参数解析为 JsonNode，失败返回 null（交由调用方按「参数非法」处理）。 */
    public com.fasterxml.jackson.databind.JsonNode arguments() {
        return Json.parseOrNull(argumentsJson);
    }

    /** 用于日志/去重的稳定签名。 */
    public String signature() {
        return name + "|" + argumentsJson.strip();
    }

    @Override
    public String toString() {
        return name + "(" + argumentsJson + ")";
    }
}
