package com.miniagent.tool.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数学表达式求值器（递归下降，自研，<b>不使用脚本引擎</b>）。
 *
 * <p>为什么不用 {@code ScriptEngine} / {@code eval}：那等于把任意代码执行权交给模型输出，
 * 是明确的安全漏洞。这里只实现四则运算、幂、取模、括号、白名单函数与常量。
 *
 * <pre>
 * expression := term (('+' | '-') term)*
 * term       := unary (('*' | '/' | '%') unary)*
 * unary      := ('+' | '-') unary | power
 * power      := primary ('^' unary)?          // 右结合
 * primary    := number | constant | function '(' expression (',' expression)* ')' | '(' expression ')'
 * </pre>
 */
public final class ExpressionEvaluator {

    private final String src;
    private int pos;

    private ExpressionEvaluator(String src) {
        this.src = src;
    }

    /** @throws IllegalArgumentException 表达式非法（含除以零、括号不匹配等） */
    public static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式为空");
        }
        String normalized = expression
                .replace("×", "*").replace("÷", "/")
                .replace("（", "(").replace("）", ")")
                .replace("，", ",").replace("－", "-").replace("＋", "+");
        ExpressionEvaluator parser = new ExpressionEvaluator(normalized);
        double value = parser.expression();
        parser.skipSpaces();
        if (parser.pos < normalized.length()) {
            throw new IllegalArgumentException("无法解析的内容: '" + normalized.substring(parser.pos) + "'");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("计算结果未定义或溢出");
        }
        return value;
    }

    private double expression() {
        double value = term();
        while (true) {
            skipSpaces();
            if (eat('+')) {
                value += term();
            } else if (eat('-')) {
                value -= term();
            } else {
                return value;
            }
        }
    }

    private double term() {
        double value = unary();
        while (true) {
            skipSpaces();
            if (eat('*')) {
                value *= unary();
            } else if (eat('/')) {
                double divisor = unary();
                if (divisor == 0d) {
                    throw new IllegalArgumentException("除数不能为 0");
                }
                value /= divisor;
            } else if (eat('%')) {
                double divisor = unary();
                if (divisor == 0d) {
                    throw new IllegalArgumentException("取模的除数不能为 0");
                }
                value %= divisor;
            } else {
                return value;
            }
        }
    }

    private double unary() {
        skipSpaces();
        if (eat('-')) {
            return -unary();
        }
        if (eat('+')) {
            return unary();
        }
        return power();
    }

    private double power() {
        double base = primary();
        skipSpaces();
        if (eat('^')) {
            return Math.pow(base, unary());
        }
        return base;
    }

    private double primary() {
        skipSpaces();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("表达式意外结束");
        }
        if (eat('(')) {
            double value = expression();
            skipSpaces();
            if (!eat(')')) {
                throw new IllegalArgumentException("括号不匹配，缺少 ')'");
            }
            return value;
        }
        char c = src.charAt(pos);
        if (Character.isDigit(c) || c == '.') {
            return number();
        }
        if (Character.isLetter(c)) {
            String identifier = identifier();
            skipSpaces();
            if (eat('(')) {
                List<Double> args = arguments();
                return function(identifier, args);
            }
            return constant(identifier);
        }
        throw new IllegalArgumentException("位置 " + pos + " 处期望数字、常量或括号，实际是 '" + c + "'");
    }

    private double number() {
        int start = pos;
        boolean dotSeen = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.' && !dotSeen) {
                dotSeen = true;
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法数字: " + text);
        }
    }

    private String identifier() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        return src.substring(start, pos).toLowerCase(Locale.ROOT);
    }

    private List<Double> arguments() {
        List<Double> args = new ArrayList<>();
        skipSpaces();
        if (eat(')')) {
            return args;
        }
        while (true) {
            args.add(expression());
            skipSpaces();
            if (eat(',')) {
                continue;
            }
            if (eat(')')) {
                return args;
            }
            throw new IllegalArgumentException("函数参数列表缺少 ')'");
        }
    }

    private double constant(String name) {
        return switch (name) {
            case "pi" -> Math.PI;
            case "e" -> Math.E;
            default -> throw new IllegalArgumentException("未知常量: " + name);
        };
    }

    private double function(String name, List<Double> args) {
        switch (name) {
            case "sqrt":
                requireArgs(name, args, 1);
                if (args.get(0) < 0) {
                    throw new IllegalArgumentException("sqrt 的参数不能为负数");
                }
                return Math.sqrt(args.get(0));
            case "abs":
                requireArgs(name, args, 1);
                return Math.abs(args.get(0));
            case "round":
                requireArgs(name, args, 1);
                return Math.round(args.get(0));
            case "floor":
                requireArgs(name, args, 1);
                return Math.floor(args.get(0));
            case "ceil":
                requireArgs(name, args, 1);
                return Math.ceil(args.get(0));
            case "exp":
                requireArgs(name, args, 1);
                return Math.exp(args.get(0));
            case "ln":
                requireArgs(name, args, 1);
                return Math.log(args.get(0));
            case "log":
                requireArgs(name, args, 1);
                return Math.log10(args.get(0));
            case "sin":
                requireArgs(name, args, 1);
                return Math.sin(args.get(0));
            case "cos":
                requireArgs(name, args, 1);
                return Math.cos(args.get(0));
            case "tan":
                requireArgs(name, args, 1);
                return Math.tan(args.get(0));
            case "pow":
                requireArgs(name, args, 2);
                return Math.pow(args.get(0), args.get(1));
            case "min":
                requireAtLeast(name, args, 1);
                return args.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            case "max":
                requireAtLeast(name, args, 1);
                return args.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            case "sum":
                requireAtLeast(name, args, 1);
                return args.stream().mapToDouble(Double::doubleValue).sum();
            default:
                throw new IllegalArgumentException("未知函数: " + name);
        }
    }

    private static void requireArgs(String name, List<Double> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(name + "() 需要 " + expected + " 个参数，实际 " + args.size() + " 个");
        }
    }

    private static void requireAtLeast(String name, List<Double> args, int min) {
        if (args.size() < min) {
            throw new IllegalArgumentException(name + "() 至少需要 " + min + " 个参数");
        }
    }

    private void skipSpaces() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean eat(char expected) {
        if (pos < src.length() && src.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    /** 结果格式化：整数不带小数点，小数去掉多余 0。 */
    public static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9 && Math.abs(value) < 1e15) {
            return Long.toString((long) Math.rint(value));
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
