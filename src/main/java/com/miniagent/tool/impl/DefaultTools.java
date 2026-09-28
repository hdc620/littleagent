package com.miniagent.tool.impl;

import com.miniagent.tool.ToolRegistry;

import java.nio.file.Path;

/**
 * 默认工具装配。把「Runtime 需要哪些工具」这件事集中在一个地方，
 * 便于替换实现（例如把 mock search 换成真实搜索）。
 */
public final class DefaultTools {

    private DefaultTools() {
    }

    /** 5 个内置工具：calculator / search / read_docs / todo / weather。 */
    public static ToolRegistry registry(Path knowledgeDir) {
        KnowledgeBase knowledgeBase = knowledgeDir == null
                ? KnowledgeBase.loadDefault()
                : KnowledgeBase.load(knowledgeDir);
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
}
