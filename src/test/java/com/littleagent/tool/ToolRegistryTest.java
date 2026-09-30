package com.littleagent.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.error.AgentException;
import com.littleagent.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具注册机制：名称唯一、Schema 导出、按名查找。 */
class ToolRegistryTest {

    @Test
    @DisplayName("注册后可通过名称查找，并导出给 LLM 的 ToolSpec 列表")
    void registersAndExportsSpecs() {
        ToolRegistry registry = new ToolRegistry().register(new StubTool("alpha", "第一个工具"));
        registry.register(new StubTool("beta", "第二个工具"));

        assertEquals(2, registry.size());
        assertEquals(List.of("alpha", "beta"), registry.names());
        assertTrue(registry.contains("alpha"));
        assertTrue(registry.find("alpha").isPresent());
        assertTrue(registry.find("nope").isEmpty());

        List<com.littleagent.llm.ToolSpec> specs = registry.specs();
        assertEquals(2, specs.size());
        assertEquals("alpha", specs.get(0).name());
        assertEquals("第一个工具", specs.get(0).description());
        assertEquals("object", specs.get(0).parameters().path("type").asText());
    }

    @Test
    @DisplayName("重名工具直接注册失败（避免线上幽灵工具）")
    void rejectsDuplicateNames() {
        ToolRegistry registry = new ToolRegistry().register(new StubTool("dup", "a"));
        AgentException error = assertThrows(AgentException.class,
                () -> registry.register(new StubTool("dup", "b")));
        assertEquals("TOOL_REGISTRATION_ERROR", error.code());
        assertTrue(error.getMessage().contains("dup"));
    }

    @Test
    @DisplayName("非法工具（空名 / 空 Schema）注册失败")
    void rejectsInvalidTools() {
        assertThrows(AgentException.class, () -> new ToolRegistry().register(new StubTool(" ", "x")));
        assertThrows(AgentException.class, () -> new ToolRegistry().register(null));
    }

    @Test
    @DisplayName("describeAvailable 用于把可用工具回灌给模型")
    void describesAvailableTools() {
        ToolRegistry registry = new ToolRegistry().register(new StubTool("a", "x")).register(new StubTool("b", "y"));
        assertEquals("a, b", registry.describeAvailable());
        assertEquals("（无）", new ToolRegistry().describeAvailable());
    }

    @Test
    @DisplayName("默认注册表包含题目要求的 5 个工具")
    void defaultRegistryHasFiveTools() {
        ToolRegistry registry = com.littleagent.tool.impl.DefaultTools.registry(null);
        assertTrue(registry.names().containsAll(
                List.of("calculator", "search", "read_docs", "todo", "weather")));
        assertEquals(5, registry.size());
        // 每个工具都必须有非空描述与合法 Schema，否则模型无法正确决策
        for (Tool tool : registry.all()) {
            assertFalse(tool.description().isBlank(), tool.name() + " 缺少描述");
            assertEquals("object", tool.parametersSchema().path("type").asText(), tool.name() + " Schema 非法");
        }
    }

    /** 测试替身 */
    static final class StubTool implements Tool {
        private final String name;
        private final String description;

        StubTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public ObjectNode parametersSchema() {
            return SchemaValidator.objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult execute(ObjectNode arguments, ToolContext context) {
            return ToolResult.ok(Json.write(arguments));
        }
    }
}
