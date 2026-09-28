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
 * 工具 1：计算器。
 *
 * <p>为什么模型需要它：LLM 的算术是概率性的，大数、幂、小数运算经常出错。
 * 把计算交给确定性代码是「用工具弥补模型短板」的典型场景。
 */
public final class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "做精确的数学计算。当用户需要算术、百分比、乘方、开方、取模、比较大小等计算时使用。"
                + "expression 为纯数学表达式，支持 + - * / %（取模）^（幂）与括号，"
                + "函数 sqrt/abs/min/max/pow/round/floor/ceil/log/ln/exp/sin/cos/tan/sum，常量 pi/e。"
                + "百分数请直接写成除法（15% 写成 0.15）。不要用它处理单位换算之外的非数学问题。";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode expression = Json.obj();
        expression.put("type", "string");
        expression.put("description", "要计算的数学表达式，例如 (12+8)*3/4 或 sqrt(144)+pow(2,10)");
        expression.put("minLength", 1);

        return SchemaValidator.objectSchema(Map.of("expression", expression), List.of("expression"));
    }

    @Override
    public ToolResult execute(ObjectNode arguments, ToolContext context) {
        String expression = arguments.path("expression").asText("").strip();
        if (expression.isEmpty()) {
            return ToolResult.error("EMPTY_EXPRESSION", "expression 不能为空");
        }
        double value;
        try {
            value = ExpressionEvaluator.evaluate(expression);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("EXPRESSION_INVALID",
                    "无法计算表达式 `" + expression + "`：" + e.getMessage() + "。请改用合法的数学表达式重试。");
        }
        String formatted = ExpressionEvaluator.format(value);
        if (context != null && context.session() != null) {
            context.session().memory().putFact("最近计算", expression + " = " + formatted);
        }
        return ToolResult.ok(expression + " = " + formatted);
    }
}
