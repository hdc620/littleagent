package com.miniagent.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 路径解析：从任意工作目录向上找到「项目内」的资源。
 *
 * <h2>为什么需要它（一个真实的坑）</h2>
 * IDE（IntelliJ 默认 {@code $MODULE_WORKING_DIR$}）、Maven/IDEA 的测试运行器、从子目录执行命令时，
 * 进程的 CWD 都**不一定**是项目根目录。若只按 CWD 解析相对路径，会静默降级：
 * .env.local 读不到（报「未检测到 API Key」）、docs/knowledge 找不到（search 一直返回「知识库为空」），
 * 而且不会有任何报错，极难排查。
 *
 * <h2>为什么不用「向上找 N 层」</h2>
 * 早期实现写死了「向上 4 层」。问题是这个数字**同时**是上界和实现细节：
 * <ul>
 *   <li>从 {@code src/main/java/com/miniagent}（距根 4 层）启动就刚好差一层，症状与被修复前一模一样；</li>
 *   <li>写太大又会命中项目之外的无关目录（例如用户家目录里的 .env.local）。</li>
 * </ul>
 * 正确做法是找一个**语义锚点**：一路向上，直到看见 {@code pom.xml} / {@code .git} 这类
 * 「这里是项目根」的标志为止。锚点是自解释的，不依赖某个人数出来的层数。
 */
public final class ProjectPaths {

    /** 认定「到达项目根」的标志文件/目录。 */
    private static final List<String> ROOT_ANCHORS = List.of(
            "pom.xml", "build.gradle", "settings.gradle", "build.gradle.kts", ".git");

    /** 安全上界：即使一个锚点都没有（例如 jar 被拷到 /tmp 运行），也不会无限向上走。 */
    public static final int MAX_UPWARD_DEPTH = 12;

    private ProjectPaths() {
    }

    /** 该目录是否是项目根（含任一锚点）。 */
    public static boolean isProjectRoot(Path dir) {
        if (dir == null) {
            return false;
        }
        for (String anchor : ROOT_ANCHORS) {
            if (Files.exists(dir.resolve(anchor))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code startDir} 向上查找 {@code relative}（相对于某一层的路径）。
     *
     * <p>查找顺序：先看 CWD 自身，再逐级向上；命中**目录**即返回。
     * 处理完「项目根」那一层之后停止（再往上就与项目无关了），最多 {@link #MAX_UPWARD_DEPTH} 层。
     *
     * @param startDir 起始目录（通常是 CWD），可为 null
     * @param relative 要找的相对路径，例如 {@code docs/knowledge}
     * @return 找到的绝对路径；没找到时返回 {@code relative} 本身（由调用方决定如何降级）
     */
    public static Path findUpwards(Path startDir, Path relative) {
        if (relative == null) {
            return null;
        }
        if (relative.isAbsolute() || startDir == null) {
            return relative;
        }
        Path dir = startDir;
        for (int depth = 0; dir != null && depth < MAX_UPWARD_DEPTH; depth++) {
            Path probe = dir.resolve(relative);
            if (Files.isDirectory(probe)) {
                return probe;
            }
            if (isProjectRoot(dir)) {
                // 已到项目根仍未命中：继续向上只会命中项目之外的无关目录
                break;
            }
            dir = dir.getParent();
        }
        return relative;
    }

    /**
     * 从 {@code startDir} 向上枚举「值得读配置」的目录，直到项目根（含）为止，最多 {@link #MAX_UPWARD_DEPTH} 层。
     *
     * <p>返回顺序为「由近到远」，调用方按此顺序覆盖同名键即可让上层覆盖下层。
     */
    public static List<Path> configSearchDirs(Path startDir) {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        Path dir = startDir;
        for (int depth = 0; dir != null && depth < MAX_UPWARD_DEPTH; depth++) {
            dirs.add(dir);
            if (isProjectRoot(dir)) {
                break;
            }
            dir = dir.getParent();
        }
        return dirs;
    }
}
