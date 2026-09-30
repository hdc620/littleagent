package com.littleagent.tool;

import com.littleagent.error.AgentException;
import com.littleagent.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：Runtime 与工具之间的唯一入口。
 *
 * <p>它负责三件事：
 * <ol>
 *   <li>登记工具（名称唯一，重复注册直接报错，避免线上「两个同名工具」的幽灵问题）；</li>
 *   <li>把注册结果导出成 LLM 需要的 {@link ToolSpec} 列表（名称 + 描述 + JSON Schema），
 *       模型的自主决策完全基于这份声明；</li>
 *   <li>按名称查找工具（执行期）。</li>
 * </ol>
 *
 * <p>线程安全：注册发生在启动阶段，之后只读；内部用 {@link LinkedHashMap} 保证顺序稳定
 * （Schema 顺序稳定 = 提示词前缀稳定 = 更容易命中 KV cache）。
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public synchronized ToolRegistry register(Tool tool) {
        if (tool == null) {
            throw new AgentException("TOOL_REGISTRATION_ERROR", "工具不能为 null");
        }
        String name = tool.name();
        if (name == null || name.isBlank()) {
            throw new AgentException("TOOL_REGISTRATION_ERROR", "工具名不能为空: " + tool.getClass().getName());
        }
        if (tools.containsKey(name)) {
            throw new AgentException("TOOL_REGISTRATION_ERROR", "工具名重复: " + name);
        }
        if (tool.parametersSchema() == null) {
            throw new AgentException("TOOL_REGISTRATION_ERROR", "工具 " + name + " 的 parametersSchema 不能为 null");
        }
        tools.put(name, tool);
        return this;
    }

    public synchronized ToolRegistry registerAll(Tool... tools) {
        for (Tool tool : tools) {
            register(tool);
        }
        return this;
    }

    public synchronized Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public synchronized List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public synchronized List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public synchronized int size() {
        return tools.size();
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 导出给 LLM 的工具声明列表。 */
    public synchronized List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(new ToolSpec(tool.name(), tool.description(), tool.parametersSchema()));
        }
        return Collections.unmodifiableList(specs);
    }

    /** 供错误提示使用：把可用工具列给模型看。 */
    public synchronized String describeAvailable() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(tool.name());
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }
}
