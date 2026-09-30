package com.littleagent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径向上查找：终止条件必须是「项目根锚点」，不能是写死的层数。
 *
 * <p>这是实测出来的坑：早期实现写死「向上 4 层」（含 CWD），从
 * {@code src/main/java/com/miniagent}（距根 4 层）启动时刚好差一层 ——
 * {@code search} 静默返回「知识库为空」、{@code .env.local} 读不到，
 * 而且症状与「README 声称已修复的那个问题」一模一样。
 */
class ProjectPathsTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("从深层子目录也能定位到项目根下的相对目录（4 层以上）")
    void findsDirectoryFromDeepWorkingDirectory() throws Exception {
        Files.createFile(root.resolve("pom.xml"));
        Path knowledge = Files.createDirectories(root.resolve("docs/knowledge"));
        Path deep = Files.createDirectories(root.resolve("src/main/java/com/miniagent"));

        Path found = ProjectPaths.findUpwards(deep, Path.of("docs", "knowledge"));

        assertEquals(knowledge, found, "距根 4 层的 CWD 也必须能找到（旧实现只上溯 3 层）");
        // 再深一点同样成立
        Path deeper = Files.createDirectories(root.resolve("a/b/c/d/e/f/g"));
        assertEquals(knowledge, ProjectPaths.findUpwards(deeper, Path.of("docs", "knowledge")));
    }

    @Test
    @DisplayName("越过项目根就不再向上找（避免命中项目之外的无关目录）")
    void stopsAtProjectRoot() throws Exception {
        // 外层有一个同名目录，但它不属于本项目
        Path outerKnowledge = Files.createDirectories(root.resolve("docs/knowledge"));
        Path project = Files.createDirectories(root.resolve("project"));
        Files.createFile(project.resolve("pom.xml"));
        Path workdir = Files.createDirectories(project.resolve("src/main"));

        Path found = ProjectPaths.findUpwards(workdir, Path.of("docs", "knowledge"));

        assertFalse(found.isAbsolute(), "已到项目根仍未命中时不能继续向上，应返回原相对路径由调用方降级");
        assertEquals(Path.of("docs", "knowledge"), found);
        assertTrue(Files.isDirectory(outerKnowledge));
    }

    @Test
    @DisplayName("绝对路径原样返回，不受 CWD 影响")
    void keepsAbsolutePathUntouched() {
        Path absolute = root.resolve("docs/knowledge").toAbsolutePath();
        assertEquals(absolute, ProjectPaths.findUpwards(root, absolute));
    }

    @Test
    @DisplayName("配置查找目录从近到远，且包含项目根（含）为止")
    void configSearchDirsGoesUpToProjectRoot() throws Exception {
        Files.createFile(root.resolve("pom.xml"));
        Path deep = Files.createDirectories(root.resolve("src/main/java"));

        List<Path> dirs = ProjectPaths.configSearchDirs(deep);

        assertEquals(deep, dirs.get(0), "最近的目录必须排在最前（后读的可以覆盖先读的）");
        assertTrue(dirs.contains(root), "必须包含项目根");
        assertEquals(root, dirs.get(dirs.size() - 1), "项目根必须是最后一个（到此为止）");
        assertFalse(dirs.contains(root.getParent()), "不应越过项目根继续向上读配置");
    }

    @Test
    @DisplayName("没有任何锚点时也不会无限向上（有安全上界）")
    void boundedWithoutAnchors() throws Exception {
        Path deep = Files.createDirectories(root.resolve("x/y/z"));
        List<Path> dirs = ProjectPaths.configSearchDirs(deep);
        assertTrue(dirs.size() <= ProjectPaths.MAX_UPWARD_DEPTH);
        assertTrue(dirs.contains(deep));
    }
}
