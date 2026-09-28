package com.miniagent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON 工具门面。整个项目只依赖 Jackson 做 JSON 编解码（不是 Agent 框架），
 * Agent Runtime / 循环 / 工具调度全部自研。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    /** 解析 JSON，失败抛 {@link IllegalArgumentException}。 */
    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("非法 JSON: " + abbreviate(text), e);
        }
    }

    /** 宽松解析：失败返回 null（用于解析 LLM 的自由文本输出）。 */
    public static JsonNode parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败: " + value, e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败: " + value, e);
        }
    }

    /** 读取字符串字段，缺失或 null 时返回 defaultValue。 */
    public static String string(JsonNode node, String field, String defaultValue) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? defaultValue : v.asText();
    }

    public static Integer integer(JsonNode node, String field, Integer defaultValue) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.isNumber() ? defaultValue : v.asInt();
    }

    public static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.isBoolean() ? defaultValue : v.asBoolean();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }
}
