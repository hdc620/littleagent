package com.littleagent.tool.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.tool.SchemaValidator;
import com.littleagent.tool.Tool;
import com.littleagent.tool.ToolContext;
import com.littleagent.tool.ToolResult;
import com.littleagent.util.Json;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 工具 5：天气查询（mock 数据源，不联网）。
 *
 * <p>mock 但「确定性可复现」：同样的城市 + 日期永远得到同样的结果（种子来自 city+date 的 hash），
 * 这样测试可以断言，演示也不会每次胡说八道。未知城市会返回<b>可读错误 + 支持列表</b>，
 * 用来演示「工具报错 -&gt; 模型自我修正」的闭环。
 */
public final class WeatherTool implements Tool {

    /** 城市 -> 基准气温区间与常见天气（mock 数据） */
    private static final Map<String, String[]> CITIES = new LinkedHashMap<>();
    private static final String[] CONDITIONS = {"晴", "多云", "阴", "小雨", "阵雨", "雷阵雨"};

    static {
        CITIES.put("北京", new String[]{"6", "18"});
        CITIES.put("上海", new String[]{"14", "22"});
        CITIES.put("广州", new String[]{"21", "29"});
        CITIES.put("深圳", new String[]{"22", "30"});
        CITIES.put("杭州", new String[]{"13", "23"});
        CITIES.put("成都", new String[]{"12", "20"});
        CITIES.put("武汉", new String[]{"13", "23"});
        CITIES.put("西安", new String[]{"7", "19"});
        CITIES.put("南京", new String[]{"12", "22"});
        CITIES.put("重庆", new String[]{"15", "24"});
        CITIES.put("苏州", new String[]{"13", "22"});
        CITIES.put("天津", new String[]{"6", "17"});
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "查询指定城市的天气（mock 数据源，不访问真实气象接口）。"
                + "city 传中文城市名（如 北京、上海），date 可选：today（默认）/ tomorrow / YYYY-MM-DD。"
                + "支持的城市有限，若返回不支持提示，请改用提示中的城市或告知用户无法查询。";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode city = Json.obj();
        city.put("type", "string");
        city.put("description", "城市名，中文，例如 北京");
        city.put("minLength", 1);

        ObjectNode date = Json.obj();
        date.put("type", "string");
        date.put("description", "日期：today / tomorrow / YYYY-MM-DD，默认 today");

        return SchemaValidator.objectSchema(Map.of("city", city, "date", date), List.of("city"));
    }

    @Override
    public ToolResult execute(ObjectNode arguments, ToolContext context) {
        String rawCity = arguments.path("city").asText("").strip();
        String city = normalizeCity(rawCity);
        String[] range = CITIES.get(city);
        if (range == null) {
            return ToolResult.error("CITY_NOT_SUPPORTED", "暂不支持城市 `" + rawCity + "`（mock 数据源）。支持的城市："
                    + String.join("、", CITIES.keySet()) + "。请改用其中之一，或告知用户当前数据源不支持该城市。");
        }
        String dateArg = arguments.path("date").asText("today").strip();
        LocalDate date;
        try {
            date = resolveDate(dateArg);
        } catch (DateTimeParseException e) {
            return ToolResult.error("INVALID_DATE",
                    "date 格式应为 today / tomorrow / YYYY-MM-DD，实际是 `" + dateArg + "`。");
        }

        Random random = new Random((city + date).hashCode());
        int low = Integer.parseInt(range[0]) + random.nextInt(5) - 2;
        int high = Integer.parseInt(range[1]) + random.nextInt(5) - 2;
        if (low > high) {
            int tmp = low;
            low = high;
            high = tmp;
        }
        String condition = CONDITIONS[random.nextInt(CONDITIONS.length)];
        int humidity = 35 + random.nextInt(55);
        int windLevel = 1 + random.nextInt(4);
        int aqi = 30 + random.nextInt(120);

        String advice = switch (condition) {
            case "小雨", "阵雨", "雷阵雨" -> "出门带伞，注意路面湿滑。";
            case "晴" -> "适合外出，注意防晒。";
            default -> "天气一般，适合正常安排。";
        };
        String summary = String.format(Locale.ROOT,
                "%s %s：%s，%d~%d℃，湿度 %d%%，%d 级风，AQI %d。%s",
                city, date, condition, low, high, humidity, windLevel, aqi, advice);

        if (context != null && context.session() != null) {
            context.session().memory().putFact("最近查询天气", summary);
        }
        return ToolResult.ok("[mock 数据] " + summary);
    }

    private static String normalizeCity(String raw) {
        return raw.replaceAll("[市省]$", "").replace(" ", "").strip();
    }

    private static LocalDate resolveDate(String arg) {
        String value = arg == null ? "today" : arg.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "today", "今天", "now" -> LocalDate.now();
            case "tomorrow", "明天" -> LocalDate.now().plusDays(1);
            case "yesterday", "昨天" -> LocalDate.now().minusDays(1);
            default -> LocalDate.parse(value);
        };
    }

    /** 测试与文档使用：支持的城市列表。 */
    public static List<String> supportedCities() {
        return List.copyOf(CITIES.keySet());
    }
}
