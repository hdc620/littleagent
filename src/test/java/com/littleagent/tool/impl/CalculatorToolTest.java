package com.littleagent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计算器与表达式求值：正确性 + 安全边界（拒绝非法表达式而不是执行任意代码）。 */
class CalculatorToolTest {

    @ParameterizedTest
    @CsvSource({
            "'1+1', 2",
            "'2*3+4', 10",
            "'(12+8)*3/4', 15",
            "'2^10', 1024",
            "'10%3', 1",
            "'-5+3', -2",
            "'sqrt(144)', 12",
            "'pow(2,10)', 1024",
            "'min(3,7,2)', 2",
            "'max(3,7,2)', 7",
            "'abs(-9)', 9",
            "'round(2.6)', 3",
            "'floor(2.9)', 2",
            "'ceil(2.1)', 3",
            "'sum(1,2,3,4)', 10",
            "'2+3*4^2', 50",
            "'(1+2)*(3+4)', 21"
    })
    @DisplayName("表达式求值结果正确")
    void evaluatesExpressions(String expression, double expected) {
        assertEquals(expected, ExpressionEvaluator.evaluate(expression), 1e-9);
    }

    @Test
    @DisplayName("中文全角符号与空格容错")
    void toleratesFullWidthCharacters() {
        assertAll(
                () -> assertEquals(10, ExpressionEvaluator.evaluate("（3＋7）"), 1e-9),
                () -> assertEquals(6, ExpressionEvaluator.evaluate(" 2 × 3 "), 1e-9),
                () -> assertEquals(4, ExpressionEvaluator.evaluate("8 ÷ 2"), 1e-9)
        );
    }

    @Test
    @DisplayName("整数结果不输出小数点，小数结果去掉多余 0")
    void formatsResult() {
        assertAll(
                () -> assertEquals("1036", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("sqrt(144)+pow(2,10)"))),
                () -> assertEquals("0.5", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("1/2"))),
                () -> assertEquals("2.5", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("5/2")))
        );
    }

    @Test
    @DisplayName("极小值不能被打印成 0，近整数不能被四舍五入成整数")
    void formatsSmallAndNearIntegerValuesExactly() {
        // 回归：早期实现用绝对阈值 |v - rint(v)| < 1e-9 判「是不是整数」，
        // 于是 1/1e10 被打印成 0（把非零结果变成 0），3-1e-10 被打印成 3（把非整数变成整数）。
        assertAll(
                () -> assertEquals("0.0000000001", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("1/10000000000"))),
                () -> assertEquals("2.9999999999", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("3-0.0000000001"))),
                () -> assertEquals("0.000001", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("1/1000000"))),
                // 真正的整数仍然不带小数点
                () -> assertEquals("0", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("0*5"))),
                () -> assertEquals("10000000000", ExpressionEvaluator.format(ExpressionEvaluator.evaluate("10^10"))),
                // NaN / Infinity 不能抛异常
                () -> assertEquals("NaN", ExpressionEvaluator.format(Double.NaN)),
                () -> assertEquals("Infinity", ExpressionEvaluator.format(Double.POSITIVE_INFINITY))
        );
    }

    @Test
    @DisplayName("工具返回的小数值是完整小数，不是 0")
    void toolReportsSmallValueNotZero() {
        CalculatorTool tool = new CalculatorTool();
        var result = tool.execute(args("expression", "1/10000000000"), null);

        assertAll(
                () -> assertTrue(result.ok()),
                () -> assertEquals("1/10000000000 = 0.0000000001", result.content())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"1/0", "5%0", "sqrt(-1)", "(1+2", "1+", "abc(3)", "1 $ 2"})
    @DisplayName("非法表达式抛 IllegalArgumentException（不执行任意代码）")
    void rejectsInvalidExpressions(String expression) {
        assertThrows(IllegalArgumentException.class, () -> ExpressionEvaluator.evaluate(expression));
    }

    @Test
    @DisplayName("工具返回可读的成功结果，并写入工作记忆")
    void executesAsTool() {
        CalculatorTool tool = new CalculatorTool();
        var result = tool.execute(args("expression", "(12+8)*3"), null);
        assertAll(
                () -> assertTrue(result.ok()),
                () -> assertEquals("(12+8)*3 = 60", result.content())
        );
    }

    @Test
    @DisplayName("工具对无法计算的表达式返回错误结果而不是抛异常")
    void returnsErrorResultForBadExpression() {
        CalculatorTool tool = new CalculatorTool();
        var result = tool.execute(args("expression", "1/0"), null);
        assertAll(
                () -> assertTrue(!result.ok()),
                () -> assertEquals("EXPRESSION_INVALID", result.errorCode()),
                () -> assertTrue(result.content().contains("除数不能为 0"))
        );
    }

    @Test
    @DisplayName("Schema 声明 expression 为必填，且工具名/描述可用于模型决策")
    void exposesSchema() {
        CalculatorTool tool = new CalculatorTool();
        assertAll(
                () -> assertEquals("calculator", tool.name()),
                () -> assertTrue(tool.description().contains("计算")),
                () -> assertEquals("object", tool.parametersSchema().path("type").asText()),
                () -> assertEquals("expression",
                        tool.parametersSchema().path("required").get(0).asText()),
                () -> assertEquals("string",
                        tool.parametersSchema().path("properties").path("expression").path("type").asText())
        );
    }

    static com.fasterxml.jackson.databind.node.ObjectNode args(String key, String value) {
        var node = com.littleagent.util.Json.obj();
        node.put(key, value);
        return node;
    }
}
