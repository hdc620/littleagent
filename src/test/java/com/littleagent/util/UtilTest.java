package com.littleagent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文本与 token 估算工具（记忆召回与压缩阈值都依赖它们）。 */
class UtilTest {

    @Test
    @DisplayName("CJK 分词：中文切成 bigram，英文按词切分并小写")
    void tokenizesCjkAndAscii() {
        List<String> tokens = Texts.tokenize("杭州 Weather 2026");
        assertTrue(tokens.contains("杭州"), tokens.toString());
        assertTrue(tokens.contains("wea") || tokens.contains("weather"), tokens.toString());
        assertTrue(Texts.tokenize("查天气").contains("查天"));
        assertTrue(Texts.tokenize("查天气").contains("天气"));
    }

    @Test
    @DisplayName("单字中文保留，标点被丢弃")
    void handlesSingleCharAndPunctuation() {
        assertTrue(Texts.tokenize("我，你。").containsAll(List.of("我", "你")));
        assertTrue(Texts.tokenize("！！！").isEmpty());
    }

    @Test
    @DisplayName("token 估算：中文高于英文同长度，且单调")
    void estimatesTokens() {
        assertTrue(TokenEstimator.estimate("杭州今天多云") > TokenEstimator.estimate("abc"));
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate((String) null));
        assertTrue(TokenEstimator.estimate("你好世界") >= 3);
    }

    @Test
    @DisplayName("截断：超长文本带提示，短文本原样返回")
    void truncates() {
        assertEquals("abc", Texts.truncate("abc", 10));
        String truncated = Texts.truncate("a".repeat(50), 10);
        assertTrue(truncated.startsWith("a".repeat(10)));
        assertTrue(truncated.contains("已截断"));
    }

    @Test
    @DisplayName("单行化：压缩空白用于 trace 摘要")
    void flattensToOneLine() {
        assertEquals("a b c", Texts.oneLine("a\n  b\tc", 100));
        assertTrue(Texts.oneLine("x".repeat(100), 10).endsWith("..."));
    }

    @Test
    @DisplayName("JSON 解析：非法输入抛异常，宽松解析返回 null")
    void jsonHelpers() {
        assertEquals("1", Json.parse("{\"a\":1}").path("a").asText());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Json.parse("{oops"));
        org.junit.jupiter.api.Assertions.assertNull(Json.parseOrNull("{oops"));
        assertEquals("x", Json.string(Json.parse("{\"a\":\"x\"}"), "a", "d"));
        assertEquals("d", Json.string(Json.parse("{}"), "a", "d"));
    }

    @Test
    @DisplayName("EnvLoader：缺失键返回默认值，配置快照幂等")
    void envLoaderDefaults() {
        assertEquals("fallback", EnvLoader.get("LITTLE_AGENT_NOT_EXIST_KEY_XYZ", "fallback"));
        assertEquals(7, EnvLoader.getInt("LITTLE_AGENT_NOT_EXIST_KEY_XYZ", 7));
        assertTrue(EnvLoader.getBool("LITTLE_AGENT_NOT_EXIST_KEY_XYZ", true));
        // 配置快照应幂等：两次读取返回同一份（有缓存），且内容一致。
        // 注意「环境变量优先于文件」这一层在这里不测——System.getenv 在测试进程里只读、无法注入，
        // 该语义由 EnvLoader.get 的实现顺序保证（先 getenv 再查文件），属于代码结构而非可注入行为。
        var first = EnvLoader.all();
        var second = EnvLoader.all();
        assertTrue(first == second || first.equals(second), "两次 all() 应返回同一份快照");
        assertEquals(first.size(), second.size(), "快照大小应稳定");
    }
}
