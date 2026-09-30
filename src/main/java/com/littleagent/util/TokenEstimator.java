package com.littleagent.util;

/**
 * 上下文 token 估算（启发式，不引入 tokenizer 依赖）。
 *
 * <p>经验系数：中文 1 字 ≈ 0.6~0.8 token；英文 1 token ≈ 4 字符。
 * 该估算只用于「是否触发压缩」的阈值判断与 trace 观测，不用于计费。
 */
public final class TokenEstimator {

    private static final double CJK_PER_TOKEN = 0.75d;
    private static final double ASCII_CHARS_PER_TOKEN = 4.0d;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Texts.isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk * CJK_PER_TOKEN + other / ASCII_CHARS_PER_TOKEN);
    }

    public static int estimate(Iterable<String> parts) {
        int total = 0;
        for (String p : parts) {
            total += estimate(p);
        }
        return total;
    }
}
