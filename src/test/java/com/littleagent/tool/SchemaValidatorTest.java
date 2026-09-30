package com.littleagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数 Schema 校验与类型纠偏：把模型的参数错误挡在执行之前。 */
class SchemaValidatorTest {

    private final ObjectNode schema = SchemaValidator.objectSchema(Map.of(
            "city", Json.MAPPER.createObjectNode().put("type", "string").put("minLength", 1),
            "top_k", Json.MAPPER.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 10),
            "action", actionSchema()
    ), List.of("city", "action"));

    private static ObjectNode actionSchema() {
        ObjectNode node = Json.obj();
        node.put("type", "string");
        node.putArray("enum").add("add").add("list");
        return node;
    }

    @Test
    @DisplayName("合法参数通过校验")
    void acceptsValidArguments() {
        ObjectNode args = Json.obj();
        args.put("city", "北京");
        args.put("action", "add");
        args.put("top_k", 3);
        assertTrue(SchemaValidator.validate(schema, args).isEmpty());
    }

    @Test
    @DisplayName("缺少必填参数 / 空字符串都算缺失")
    void reportsMissingRequired() {
        ObjectNode args = Json.obj();
        args.put("action", "add");
        List<String> violations = SchemaValidator.validate(schema, args);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("city"));

        ObjectNode blank = Json.obj();
        blank.put("city", "   ");
        blank.put("action", "add");
        assertTrue(SchemaValidator.validate(schema, blank).get(0).contains("city"));
    }

    @Test
    @DisplayName("类型错误、枚举越界、数值越界都被检出")
    void reportsTypeEnumAndRangeViolations() {
        ObjectNode args = Json.obj();
        args.put("city", 123);
        args.put("action", "delete");
        args.put("top_k", 99);
        List<String> violations = SchemaValidator.validate(schema, args);
        assertEquals(3, violations.size());
        assertTrue(String.join(" ", violations).contains("string"));
        assertTrue(String.join(" ", violations).contains("delete"));
        assertTrue(String.join(" ", violations).contains("10"));
    }

    @Test
    @DisplayName("arguments 不是对象时直接报错")
    void rejectsNonObjectArguments() {
        JsonNode array = Json.arr();
        List<String> violations = SchemaValidator.validate(schema, array);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("JSON 对象"));
    }

    @Test
    @DisplayName("类型纠偏：字符串数字被归一化成数字，省掉一轮交互")
    void coercesNumericStrings() {
        ObjectNode args = Json.obj();
        args.put("city", "上海");
        args.put("action", "list");
        args.put("top_k", "5");

        ObjectNode coerced = SchemaValidator.coerce(schema, args);
        assertTrue(coerced.path("top_k").isNumber());
        assertEquals(5, coerced.path("top_k").asInt());
        // 纠偏后应通过校验
        assertTrue(SchemaValidator.validate(schema, coerced).isEmpty());
    }

    @Test
    @DisplayName("coerce 不修改原始参数对象")
    void coerceIsPure() {
        ObjectNode args = Json.obj();
        args.put("top_k", "7");
        SchemaValidator.coerce(schema, args);
        assertTrue(args.path("top_k").isTextual());
    }
}
