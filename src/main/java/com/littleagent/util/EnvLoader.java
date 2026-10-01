package com.littleagent.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置来源解析：先读 {@code .env} / {@code .env.local} 文件，再让真实环境变量覆盖（环境变量优先级最高）。
 *
 * <p>这样既能「clone 下来填 .env.local 即可运行」，也兼容 CI/容器注入环境变量的做法。
 */
public final class EnvLoader {

    private static volatile Map<String, String> cached;

    private EnvLoader() {
    }

    public static String get(String key) {
        return get(key, null);
    }

    public static String get(String key, String defaultValue) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        String fromFile = all().get(key);
        return fromFile == null || fromFile.isBlank() ? defaultValue : fromFile;
    }

    public static int getInt(String key, int defaultValue) {
        String raw = get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double getDouble(String key, double defaultValue) {
        String raw = get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBool(String key, boolean defaultValue) {
        String raw = get(key);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw.strip());
    }

    /** 文件配置快照（不含环境变量覆盖）。 */
    public static Map<String, String> all() {
        Map<String, String> local = cached;
        if (local == null) {
            synchronized (EnvLoader.class) {
                local = cached;
                if (local == null) {
                    local = loadFiles();
                    cached = local;
                }
            }
        }
        return local;
    }

    /** 仅供测试：清空缓存。 */
    public static void reset() {
        cached = null;
    }

    private static Map<String, String> loadFiles() {
        Map<String, String> values = new HashMap<>();
        // 从 CWD 向上读到项目根（pom.xml/.git）为止，而不是写死「向上 N 层」：
        // 写死层数时，从 src/main/java/com/littleagent 这类深层目录启动就会差一层、静默读不到配置。
        for (Path dir : ProjectPaths.configSearchDirs(Paths.get("").toAbsolutePath())) {
            readInto(values, dir.resolve(".env"));
            readInto(values, dir.resolve(".env.local"));
        }
        return values;
    }

    private static void readInto(Map<String, String> values, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).strip();
                String value = line.substring(idx + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            // 配置文件读取失败不影响主流程（可能只是没权限），静默降级
        }
    }
}
