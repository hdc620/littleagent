package com.littleagent.trace;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 把 trace 事件追加写入 {@code <dir>/<sessionId>.jsonl}，每行一条 JSON。
 *
 * <p>写盘失败不能让主流程失败（磁盘满/无权限），因此这里只记录到 stderr 后吞掉异常。
 */
public final class JsonlTraceSink implements TraceSink {

    private static final System.Logger LOG = System.getLogger(JsonlTraceSink.class.getName());

    private final Path file;

    public JsonlTraceSink(Path dir, String sessionId) {
        Path target = dir.resolve(sanitize(sessionId) + ".jsonl");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "创建 trace 目录失败: " + dir + " -> " + e.getMessage());
        }
        this.file = target;
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void accept(TraceEvent event) {
        try {
            Files.writeString(file, toJson(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "写 trace 失败: " + e.getMessage());
        }
    }

    /**
     * 手工构造 JSON 而不是直接序列化 record：
     * {@code Instant} 需要额外的 jackson-datatype-jsr310 模块，为一个时间戳引入依赖不划算，
     * 这里统一转成 ISO-8601 字符串（也更便于 grep / jq）。
     */
    static String toJson(TraceEvent event) {
        ObjectNode node = Json.obj();
        node.put("seq", event.seq());
        node.put("at", event.at().toString());
        node.put("sessionId", event.sessionId());
        node.put("type", event.type());
        node.put("summary", event.summary());
        node.put("durationMs", event.durationMs());
        node.set("data", Json.MAPPER.valueToTree(event.data()));
        return Json.write(node);
    }

    /** 只保留安全字符，并消掉 {@code ..}（防止 sessionId 造成目录穿越）。 */
    private static String sanitize(String sessionId) {
        if (sessionId == null) {
            return "unknown";
        }
        return sessionId.replaceAll("[^a-zA-Z0-9._-]", "_").replace("..", "_");
    }
}
