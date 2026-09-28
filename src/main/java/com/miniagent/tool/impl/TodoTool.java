package com.miniagent.tool.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.session.TodoItem;
import com.miniagent.session.WorkingMemory;
import com.miniagent.tool.SchemaValidator;
import com.miniagent.tool.Tool;
import com.miniagent.tool.ToolContext;
import com.miniagent.tool.ToolResult;
import com.miniagent.util.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具 4：待办管理（有状态工具，状态存在 session 的工作记忆里）。
 *
 * <p>这个工具是「session 隔离 + 跨轮次记忆」最有说服力的载体：
 * <ul>
 *   <li>窗口 1 里 add 的待办，只存在于窗口 1 的 Session 中；窗口 2 看不到；</li>
 *   <li>下一轮用户问「我有哪些待办」，即使历史消息已经被压缩滑出窗口，
 *       Runtime 仍然把工作记忆注入了上下文，所以 Agent 答得出来。</li>
 * </ul>
 *
 * <p>注意 Schema 表达不了「action=add 时 item 必填」这种条件约束，
 * 所以这里在运行时校验并返回<b>可读的错误</b>，让模型自己补上参数 —— 这正是工具错误处理的意义。
 */
public final class TodoTool implements Tool {

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String description() {
        return "管理当前会话的待办清单（按会话隔离，窗口之间互不影响）。"
                + "action=add 新增（需要 item），action=list 查看全部，action=done 标记完成（需要 id），"
                + "action=clear 清除已完成项。"
                + "当用户说「记一下 / 加个待办 / 提醒我 / 别忘了我还要…」时用 add；"
                + "当用户问「我还有什么没做 / 待办有哪些」时用 list。";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode action = Json.obj();
        action.put("type", "string");
        action.put("description", "操作类型");
        var enumNode = action.putArray("enum");
        enumNode.add("add").add("list").add("done").add("clear");

        ObjectNode item = Json.obj();
        item.put("type", "string");
        item.put("description", "待办内容，action=add 时必填");
        item.put("minLength", 1);
        item.put("maxLength", 200);

        ObjectNode id = Json.obj();
        id.put("type", "integer");
        id.put("description", "待办编号，action=done 时必填");
        id.put("minimum", 1);

        return SchemaValidator.objectSchema(
                Map.of("action", action, "item", item, "id", id), List.of("action"));
    }

    @Override
    public ToolResult execute(ObjectNode arguments, ToolContext context) {
        if (context == null || context.session() == null) {
            return ToolResult.error("NO_SESSION", "待办工具必须绑定会话上下文");
        }
        String action = arguments.path("action").asText("list").strip().toLowerCase(java.util.Locale.ROOT);
        WorkingMemory memory = context.session().memory();
        return switch (action) {
            case "add" -> add(arguments, memory);
            case "list" -> list(memory);
            case "done" -> done(arguments, memory);
            case "clear" -> clear(memory);
            default -> ToolResult.error("INVALID_ACTION",
                    "不支持的 action=`" + action + "`，只能是 add / list / done / clear。");
        };
    }

    private ToolResult add(ObjectNode arguments, WorkingMemory memory) {
        String item = arguments.path("item").asText("").strip();
        if (item.isEmpty()) {
            return ToolResult.error("MISSING_ITEM", "action=add 时必须提供 item，例如 {\"action\":\"add\",\"item\":\"给张总发周报\"}。");
        }
        TodoItem todo = memory.addTodo(item);
        long open = memory.todos().stream().filter(t -> !t.done()).count();
        return ToolResult.ok("已加入待办 #" + todo.id() + "：" + todo.text() + "（当前未完成 " + open + " 项）");
    }

    private ToolResult list(WorkingMemory memory) {
        List<TodoItem> todos = memory.todos();
        if (todos.isEmpty()) {
            return ToolResult.ok("当前会话的待办清单为空。");
        }
        StringBuilder sb = new StringBuilder("当前会话待办清单：\n");
        for (TodoItem item : todos) {
            sb.append("  ").append(item.render()).append('\n');
        }
        long open = todos.stream().filter(t -> !t.done()).count();
        sb.append("共 ").append(todos.size()).append(" 项，未完成 ").append(open).append(" 项。");
        return ToolResult.ok(sb.toString().strip());
    }

    private ToolResult done(ObjectNode arguments, WorkingMemory memory) {
        if (!arguments.hasNonNull("id")) {
            return ToolResult.error("MISSING_ID", "action=done 时必须提供 id，例如 {\"action\":\"done\",\"id\":1}。");
        }
        int id = arguments.path("id").asInt(-1);
        Optional<TodoItem> completed = memory.completeTodo(id);
        if (completed.isEmpty()) {
            return ToolResult.error("TODO_NOT_FOUND", "不存在编号 #" + id + " 的待办。当前待办："
                    + renderIds(memory) + "。请先 list 确认编号。");
        }
        long open = memory.todos().stream().filter(t -> !t.done()).count();
        return ToolResult.ok("已完成 #" + id + "：" + completed.get().text() + "（剩余未完成 " + open + " 项）");
    }

    private ToolResult clear(WorkingMemory memory) {
        int cleared = memory.clearCompleted();
        long open = memory.todos().stream().filter(t -> !t.done()).count();
        return ToolResult.ok("已清除 " + cleared + " 项已完成待办（剩余未完成 " + open + " 项）。");
    }

    private static String renderIds(WorkingMemory memory) {
        List<TodoItem> todos = memory.todos();
        if (todos.isEmpty()) {
            return "（空）";
        }
        StringBuilder sb = new StringBuilder();
        for (TodoItem item : todos) {
            sb.append(sb.length() == 0 ? "" : ", ").append('#').append(item.id()).append(' ').append(item.text());
        }
        return sb.toString();
    }
}
