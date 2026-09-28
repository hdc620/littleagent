package com.miniagent.tool.impl;

import com.miniagent.tool.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 默认工具装配。把「Runtime 需要哪些工具」这件事集中在一个地方，
 * 便于替换实现（例如把 mock search 换成真实搜索）。
 */
public final class DefaultTools {

    /** 向上查找的层数，与 {@code EnvLoader} 找 .env 的策略保持一致。 */
    private static final int MAX_LOOKUP_DEPTH = 4;

    private DefaultTools() {
    }

    /** 5 个内置工具：calculator / search / read_docs / todo / weather。 */
    public static ToolRegistry registry(Path knowledgeDir) {
        KnowledgeBase knowledgeBase = KnowledgeBase.load(resolveKnowledgeDir(knowledgeDir));
        return new ToolRegistry()
                .register(new CalculatorTool())
                .register(new SearchTool(knowledgeBase))
                .register(new ReadDocsTool(knowledgeBase))
                .register(new TodoTool())
                .register(new WeatherTool());
    }

    public static ToolRegistry standardRegistry() {
        return registry(Path.of("docs", "knowledge"));
    }

    /**
     * 解析知识库目录：配置值优先，其次从当前工作目录向上查找同名目录。
     *
     * <p>为什么需要向上查找：IDE（IntelliJ 默认 {@code $MODULE_WORKING_DIR$}）、
     * 从子目录执行、或测试运行器的 CWD 都可能不是项目根目录。若只按 CWD 解析，
     * 知识库会静默降级为空库 —— 表现是 search 一直返回「没有检索到」，很难排查。
     *
     * @param configured 配置里的目录（可为相对路径或绝对路径，null 表示默认 docs/knowledge）
     * @return 实际可用的目录；全部找不到时返回原配置值，由 {@link KnowledgeBase} 降级为空库
     */
    public static Path resolveKnowledgeDir(Path configured) {
        return resolveKnowledgeDir(configured, Paths.get("").toAbsolutePath());
    }

    static Path resolveKnowledgeDir(Path configured, Path startDir) {
        Path candidate = configured == null ? Path.of("docs", "knowledge") : configured;
        if (candidate.isAbsolute() || startDir == null) {
            return candidate;
        }
        Path dir = startDir;
        for (int depth = 0; depth < MAX_LOOKUP_DEPTH && dir != null; depth++) {
            Path probe = dir.resolve(candidate);
            if (Files.isDirectory(probe)) {
                return probe;
            }
            dir = dir.getParent();
        }
        return candidate;
    }
}
