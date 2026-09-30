package com.littleagent.tool.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.session.Session;
import com.littleagent.session.SessionManager;
import com.littleagent.tool.ToolContext;
import com.littleagent.tool.ToolResult;
import com.littleagent.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 天气（mock 数据源）、待办（有状态）、检索与读文档（本地知识库）四个工具的行为。 */
class BuiltinToolsTest {

    /** 同一个测试内共享，才能验证「同一 session 状态延续 / 不同 session 隔离」 */
    private final SessionManager sessions = new SessionManager();

    // ------------------------------------------------------------------ 天气

    @Test
    @DisplayName("天气：支持城市返回确定性结果，同一城市+日期结果稳定")
    void weatherIsDeterministic() {
        WeatherTool tool = new WeatherTool();
        ToolResult first = tool.execute(args("city", "北京"), context("w1"));
        ToolResult second = tool.execute(args("city", "北京"), context("w1"));
        assertTrue(first.ok());
        assertEquals(first.content(), second.content());
        assertTrue(first.content().contains("北京"));
        assertTrue(first.content().contains("mock 数据"));
    }

    @Test
    @DisplayName("天气：未知城市返回可读错误 + 支持列表（模型据此自我修正）")
    void weatherUnknownCity() {
        ToolResult result = new WeatherTool().execute(args("city", "火星"), context("w1"));
        assertFalse(result.ok());
        assertEquals("CITY_NOT_SUPPORTED", result.errorCode());
        assertTrue(result.content().contains("北京"));
    }

    @Test
    @DisplayName("天气：date 支持 today/tomorrow/具体日期，非法日期返回错误")
    void weatherDateHandling() {
        WeatherTool tool = new WeatherTool();
        ObjectNode args = args("city", "上海");
        args.put("date", "tomorrow");
        assertTrue(tool.execute(args, context("w1")).ok());

        ObjectNode bad = args("city", "上海");
        bad.put("date", "下周三");
        ToolResult result = tool.execute(bad, context("w1"));
        assertEquals("INVALID_DATE", result.errorCode());
    }

    @Test
    @DisplayName("天气：查询结果写入工作记忆（供后续追问使用）")
    void weatherWritesWorkingMemory() {
        Session session = session("w1");
        new WeatherTool().execute(args("city", "杭州"), new ToolContext("A", "w1", "c", session, session.tracer()));
        assertTrue(session.memory().fact("最近查询天气").contains("杭州"));
    }

    // ------------------------------------------------------------------ 待办

    @Test
    @DisplayName("待办：add / list / done / clear 全流程")
    void todoLifecycle() {
        Session session = session("w1");
        TodoTool tool = new TodoTool();
        ToolContext ctx = new ToolContext("A", "w1", "c", session, session.tracer());

        ToolResult added = tool.execute(args("action", "add", "item", "给张总发周报"), ctx);
        assertTrue(added.ok());
        assertTrue(added.content().contains("#1"));

        ToolResult list = tool.execute(args("action", "list"), ctx);
        assertTrue(list.content().contains("给张总发周报"));
        assertTrue(list.content().contains("[未完成]"));

        ToolResult done = tool.execute(args("action", "done", "id", "1"), ctx);
        assertTrue(done.content().contains("已完成 #1"));

        ToolResult cleared = tool.execute(args("action", "clear"), ctx);
        assertTrue(cleared.content().contains("已清除 1 项"));
        assertTrue(tool.execute(args("action", "list"), ctx).content().contains("为空"));
    }

    @Test
    @DisplayName("待办：add 缺 item、done 缺 id、id 不存在都返回可读错误")
    void todoArgumentErrors() {
        TodoTool tool = new TodoTool();
        ToolContext ctx = context("w1");

        ToolResult missingItem = tool.execute(args("action", "add"), ctx);
        assertEquals("MISSING_ITEM", missingItem.errorCode());
        assertTrue(missingItem.content().contains("item"));

        ToolResult missingId = tool.execute(args("action", "done"), ctx);
        assertEquals("MISSING_ID", missingId.errorCode());

        ToolResult notFound = tool.execute(args("action", "done", "id", "42"), ctx);
        assertEquals("TODO_NOT_FOUND", notFound.errorCode());

        ToolResult badAction = tool.execute(args("action", "explode"), ctx);
        assertEquals("INVALID_ACTION", badAction.errorCode());
    }

    @Test
    @DisplayName("待办：不同 session 的待办互不可见（session 隔离的基础保证）")
    void todoIsSessionScoped() {
        TodoTool tool = new TodoTool();
        tool.execute(args("action", "add", "item", "窗口1的待办"), context("w1"));
        tool.execute(args("action", "add", "item", "窗口2的待办"), context("w2"));

        ToolResult window1 = tool.execute(args("action", "list"), context("w1"));
        ToolResult window2 = tool.execute(args("action", "list"), context("w2"));
        assertTrue(window1.content().contains("窗口1的待办"));
        assertFalse(window1.content().contains("窗口2的待办"));
        assertTrue(window2.content().contains("窗口2的待办"));
        assertFalse(window2.content().contains("窗口1的待办"));
    }

    // ------------------------------------------------------- 检索 / 读文档

    @Test
    @DisplayName("检索：命中知识库并返回文档 id、章节与片段")
    void searchHitsKnowledgeBase(@TempDir Path dir) throws IOException {
        writeDoc(dir, "DOC-001-demo.md", "# 演示文档\n\n## 上下文压缩\n\n当 token 超过预算时压缩旧历史。\n");
        KnowledgeBase base = KnowledgeBase.load(dir);
        ToolResult result = new SearchTool(base).execute(args("query", "上下文压缩"), context("w1"));

        assertTrue(result.ok());
        assertTrue(result.content().contains("DOC-001-demo"));
        assertTrue(result.content().contains("上下文压缩"));
    }

    @Test
    @DisplayName("检索：无命中时给出可用文档列表而不是报错")
    void searchMissIsNotError(@TempDir Path dir) throws IOException {
        writeDoc(dir, "DOC-002-other.md", "# 其他\n\n## 内容\n\n无关内容。\n");
        ToolResult result = new SearchTool(KnowledgeBase.load(dir))
                .execute(args("query", "完全不相关的关键词xyz"), context("w1"));
        assertTrue(result.ok());
        assertTrue(result.content().contains("没有检索到"));
        assertTrue(result.content().contains("DOC-002-other"));
    }

    @Test
    @DisplayName("读文档：支持分页，超出范围给出提示")
    void readDocsPaginates(@TempDir Path dir) throws IOException {
        StringBuilder body = new StringBuilder("# 长文档\n\n## 正文\n\n");
        for (int i = 1; i <= 40; i++) {
            body.append("第 ").append(i).append(" 行内容\n");
        }
        writeDoc(dir, "DOC-003-long.md", body.toString());
        ReadDocsTool tool = new ReadDocsTool(KnowledgeBase.load(dir));

        ObjectNode args = args("doc_id", "DOC-003-long");
        args.put("offset", 0);
        args.put("limit", 10);
        ToolResult firstPage = tool.execute(args, context("w1"));
        assertTrue(firstPage.ok());
        assertTrue(firstPage.content().contains("第 1 行内容"));
        assertTrue(firstPage.content().contains("未读完"));

        ObjectNode second = args("doc_id", "DOC-003-long");
        second.put("offset", 10);
        second.put("limit", 10);
        // 行序：0=标题 1=空 2=## 正文 3=空 4=第 1 行 ⇒ offset=10 从「第 7 行内容」开始
        assertTrue(tool.execute(second, context("w1")).content().contains("第 7 行内容"));
    }

    @Test
    @DisplayName("读文档：doc_id 不存在返回错误 + 可用文档列表")
    void readDocsNotFound(@TempDir Path dir) throws IOException {
        writeDoc(dir, "DOC-004-x.md", "# x\n");
        ToolResult result = new ReadDocsTool(KnowledgeBase.load(dir))
                .execute(args("doc_id", "NOPE-999"), context("w1"));
        assertEquals("DOC_NOT_FOUND", result.errorCode());
        assertTrue(result.content().contains("DOC-004-x"));
    }

    @Test
    @DisplayName("知识库：目录不存在时降级为空库，Agent 不会因此崩溃")
    void knowledgeBaseMissingDirectory(@TempDir Path dir) {
        KnowledgeBase base = KnowledgeBase.load(dir.resolve("not-exists"));
        assertTrue(base.documents().isEmpty());
        ToolResult result = new SearchTool(base).execute(args("query", "任何"), context("w1"));
        assertTrue(result.ok());
        assertTrue(result.content().contains("知识库为空"));
    }

    // ------------------------------------------------------------------ 工具

    private static ObjectNode args(String... keyValues) {
        ObjectNode node = Json.obj();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = keyValues[i];
            String value = keyValues[i + 1];
            if (value.chars().allMatch(Character::isDigit) && (key.equals("id") || key.equals("offset") || key.equals("limit"))) {
                node.put(key, Integer.parseInt(value));
            } else {
                node.put(key, value);
            }
        }
        return node;
    }

    private ToolContext context(String sessionId) {
        Session session = session(sessionId);
        return new ToolContext("A", sessionId, "call_1", session, session.tracer());
    }

    private Session session(String sessionId) {
        return sessions.getOrCreate("A", sessionId, "测试窗口");
    }

    private static void writeDoc(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** 覆盖所有内置工具都可被检索到（防止忘记注册） */
    @Test
    @DisplayName("内置工具清单稳定")
    void builtinToolNames() {
        assertEquals(List.of("calculator", "search", "read_docs", "todo", "weather"),
                DefaultTools.standardRegistry().names());
    }
}
