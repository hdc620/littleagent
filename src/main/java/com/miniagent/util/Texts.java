package com.miniagent.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文本处理：CJK 友好的分词（用于记忆召回打分）、截断、判断。 */
public final class Texts {

    private Texts() {
    }

    /**
     * 是否属于「按字成词」的东亚文字（汉字 / 假名 / 谚文）。
     *
     * <p>刻意<b>不</b>把全角标点、CJK 标点（如 {@code ，。、}）算进来：
     * 它们是分隔符而不是词，算进来会让「我，你」被切成 bigram「我，」「，你」，召回直接失效。
     */
    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 谚文
    }

    /**
     * 极简分词：ASCII 按非字母数字切分并小写化；连续 CJK 串切成 bigram（中文无空格，
     * bigram 在召回场景下性价比最高），长度为 1 的 CJK 串保留单字。
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder buf, List<String> out) {
        if (buf.length() >= 2) {
            out.add(buf.toString());
        }
        buf.setLength(0);
    }

    private static void flushCjk(StringBuilder buf, List<String> out) {
        String s = buf.toString();
        if (s.length() == 1) {
            out.add(s);
        } else {
            for (int i = 0; i + 1 < s.length(); i++) {
                out.add(s.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    /** 去重后的 token 集合（召回打分用）。 */
    public static Set<String> tokenSet(String text) {
        return new LinkedHashSet<>(tokenize(text));
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...[已截断 " + (text.length() - maxChars) + " 字]";
    }

    public static String blankToEmpty(String text) {
        return text == null ? "" : text.strip();
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    public static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return maxChars > 0 && flat.length() > maxChars ? flat.substring(0, maxChars) + "..." : flat;
    }

    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
