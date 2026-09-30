package com.littleagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON Schema 校验器（只实现工具参数真正用得到的子集）。
 *
 * <p>为什么自己写而不引第三方校验库：工具 Schema 的形状非常有限（object + 标量/数组属性），
 * 自己实现可以做到「零反射、错误信息可定制」，而错误信息是要回灌给模型让它自我修正的，
 * 措辞比规范完备性更重要。
 *
 * <p>支持的约束：{@code type}、{@code required}、{@code properties}、{@code enum}、
 * {@code minimum}/{@code maximum}、{@code minLength}/{@code maxLength}、{@code items}（数组元素类型）。
 * 未声明的额外参数默认忽略（与 JSON Schema 默认行为一致）。
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    /** @return 违规说明列表；空列表表示通过 */
    public static List<String> validate(JsonNode schema, JsonNode arguments) {
        List<String> violations = new ArrayList<>();
        if (schema == null || !schema.isObject()) {
            return violations;
        }
        if (arguments == null || arguments.isNull()) {
            if (schema.has("required") && !schema.get("required").isEmpty()) {
                violations.add("缺少参数对象；必填字段: " + requiredNames(schema));
            }
            return violations;
        }
        if (!arguments.isObject() && "object".equals(schema.path("type").asText())) {
            violations.add("arguments 必须是 JSON 对象，实际是 " + arguments.getNodeType());
            return violations;
        }

        for (String required : requiredNames(schema)) {
            JsonNode value = arguments.get(required);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                violations.add("缺少必填参数 `" + required + "`");
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                JsonNode value = arguments.get(entry.getKey());
                if (value == null || value.isNull()) {
                    return;
                }
                checkValue(entry.getKey(), entry.getValue(), value, violations);
            });
        }
        return violations;
    }

    private static void checkValue(String name, JsonNode spec, JsonNode value, List<String> violations) {
        String expectedType = spec.path("type").asText(null);
        if (expectedType != null && !typeMatches(expectedType, value)) {
            violations.add("参数 `" + name + "` 类型应为 " + expectedType + "，实际是 "
                    + value.getNodeType() + "（值: " + abbreviate(value) + "）");
            return;
        }
        JsonNode enumValues = spec.get("enum");
        if (enumValues != null && enumValues.isArray() && !enumValues.isEmpty()) {
            boolean hit = false;
            for (JsonNode candidate : enumValues) {
                if (candidate.equals(value)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                violations.add("参数 `" + name + "` 只能是 " + enumValues + " 之一，实际是 " + abbreviate(value));
            }
        }
        if (value.isNumber()) {
            if (spec.has("minimum") && value.asDouble() < spec.get("minimum").asDouble()) {
                violations.add("参数 `" + name + "` 不能小于 " + spec.get("minimum").asDouble());
            }
            if (spec.has("maximum") && value.asDouble() > spec.get("maximum").asDouble()) {
                violations.add("参数 `" + name + "` 不能大于 " + spec.get("maximum").asDouble());
            }
            if ("integer".equals(expectedType) && value.asDouble() != Math.floor(value.asDouble())) {
                violations.add("参数 `" + name + "` 必须是整数，实际是 " + value.asDouble());
            }
        }
        if (value.isTextual()) {
            int length = value.asText().length();
            if (spec.has("minLength") && length < spec.get("minLength").asInt()) {
                violations.add("参数 `" + name + "` 至少 " + spec.get("minLength").asInt() + " 个字符");
            }
            if (spec.has("maxLength") && length > spec.get("maxLength").asInt()) {
                violations.add("参数 `" + name + "` 最多 " + spec.get("maxLength").asInt() + " 个字符");
            }
        }
        if (value.isArray() && spec.has("items")) {
            JsonNode itemSpec = spec.get("items");
            String itemType = itemSpec.path("type").asText(null);
            if (itemType != null) {
                for (JsonNode element : value) {
                    if (!typeMatches(itemType, element)) {
                        violations.add("参数 `" + name + "` 的元素类型应为 " + itemType);
                        break;
                    }
                }
            }
        }
    }

    private static boolean typeMatches(String expectedType, JsonNode value) {
        return switch (expectedType) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() || (value.isTextual() && isNumeric(value.asText()));
            case "number" -> value.isNumber() || (value.isTextual() && isNumeric(value.asText()));
            case "boolean" -> value.isBoolean() || (value.isTextual() && isBooleanLike(value.asText()));
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
    }

    /**
     * 类型纠偏：模型经常把数字写成字符串（{@code "top_k": "3"}）。
     * 与其报错浪费一轮，不如在类型声明允许时直接归一化。
     */
    public static ObjectNode coerce(JsonNode schema, ObjectNode arguments) {
        if (schema == null || arguments == null) {
            return arguments;
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return arguments;
        }
        ObjectNode result = arguments.deepCopy();
        properties.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode value = result.get(name);
            if (value == null || !value.isTextual()) {
                return;
            }
            String type = entry.getValue().path("type").asText("");
            String text = value.asText().strip();
            switch (type) {
                case "integer" -> {
                    if (isNumeric(text)) {
                        result.put(name, (long) Double.parseDouble(text));
                    }
                }
                case "number" -> {
                    if (isNumeric(text)) {
                        result.put(name, Double.parseDouble(text));
                    }
                }
                case "boolean" -> {
                    if (isBooleanLike(text)) {
                        result.put(name, Boolean.parseBoolean(text.toLowerCase(java.util.Locale.ROOT)));
                    }
                }
                default -> {
                    // 其他类型不动
                }
            }
        });
        return result;
    }

    private static List<String> requiredNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            required.forEach(node -> names.add(node.asText()));
        }
        return names;
    }

    private static boolean isNumeric(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(text.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isBooleanLike(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        return t.equals("true") || t.equals("false");
    }

    private static String abbreviate(JsonNode value) {
        String text = value.isTextual() ? value.asText() : value.toString();
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    /** 便捷方法：构造一段包含 violations 的可读错误信息。 */
    public static String describe(List<String> violations) {
        return String.join("；", violations);
    }

    /** 便捷方法：构造 object 类型的 Schema。 */
    public static ObjectNode objectSchema(Map<String, JsonNode> properties, List<String> required) {
        ObjectNode schema = Json.obj();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.forEach(props::set);
        if (required != null && !required.isEmpty()) {
            var arr = schema.putArray("required");
            required.forEach(arr::add);
        }
        return schema;
    }
}
