package com.miniagent.tool.impl;

import com.miniagent.tool.Tool;
import com.miniagent.tool.ToolContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认工具装配与知识库目录解析。
 *
 * <p>回归用例：IDE / 子目录执行时 CWD 可能不是项目根目录，知识库必须仍能被找到
 * （否则 search 会静默返回「知识库为空」，这是很难排查的故障）。
 */
class DefaultToolsTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    @DisplayName("默认注册表能命中内置知识库（docs/knowledge 下确有文档）")
    void defaultRegistrySeesBundledKnowledge() throws Exception {
        var registry = DefaultTools.registry(null);
        Tool search = registry.find("search").orElseThrow();
        var context = new ToolContext("A", "w1", "call_1", null, null);

        var result = search.execute(
                com.miniagent.util.Json.obj().put("query", "周报模板"), context);

        assertTrue(result.ok());
        assertFalse(result.content().contains("知识库为空"), "知识库不应为空：" + result.content());
        assertTrue(result.content().contains("REPORT-004"), "应命中周报模板文档：" + result.content());
    }

    @Test
    @DisplayName("从项目根目录解析：返回真实存在的知识库目录")
    void resolvesFromProjectRoot() {
        Path resolved = DefaultTools.resolveKnowledgeDir(Path.of("docs", "knowledge"), PROJECT_ROOT);
        assertTrue(Files.isDirectory(resolved), "应解析到存在的目录：" + resolved);
    }

    @Test
    @DisplayName("从子目录解析：向上查找仍能找到知识库（IDEA/测试运行器 CWD 不同的场景）")
    void resolvesFromNestedDirectory() {
        Path nested = PROJECT_ROOT.resolve("src").resolve("main").resolve("java");
        Path resolved = DefaultTools.resolveKnowledgeDir(Path.of("docs", "knowledge"), nested);
        assertTrue(Files.isDirectory(resolved), "向上查找应命中项目根下的 docs/knowledge：" + resolved);
    }

    @Test
    @DisplayName("找不到时原样返回配置值，由 KnowledgeBase 优雅降级为空库")
    void returnsConfiguredPathWhenNotFound() throws Exception {
        Path missing = Path.of("no", "such", "knowledge");
        assertEquals(missing, DefaultTools.resolveKnowledgeDir(missing, PROJECT_ROOT));
        assertFalse(Files.isDirectory(missing));
        // 空库不会让工具报错
        var registry = DefaultTools.registry(missing);
        var result = registry.find("search").orElseThrow()
                .execute(com.miniagent.util.Json.obj().put("query", "任何"), new ToolContext("A", "w", "c", null, null));
        assertTrue(result.ok());
        assertTrue(result.content().contains("知识库为空"));
    }

    @Test
    @DisplayName("绝对路径原样透传（不做向上查找）")
    void keepsAbsolutePath() {
        Path absolute = PROJECT_ROOT.resolve("docs").resolve("knowledge").toAbsolutePath();
        assertEquals(absolute, DefaultTools.resolveKnowledgeDir(absolute, PROJECT_ROOT.resolve("src")));
    }

    @Test
    @DisplayName("null 配置等价于默认 docs/knowledge")
    void nullMeansDefault() {
        Path resolved = DefaultTools.resolveKnowledgeDir(null, PROJECT_ROOT);
        assertTrue(resolved.endsWith(Path.of("docs", "knowledge")));
        assertTrue(Files.isDirectory(resolved));
    }
}
