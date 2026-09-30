package com.littleagent.core;

import com.littleagent.llm.LlmClient;
import com.littleagent.llm.MockLlmClient;
import com.littleagent.llm.OpenAiCompatibleClient;
import com.littleagent.util.EnvLoader;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Agent Runtime 的全部可调参数。
 *
 * <p>默认值可从环境变量 / {@code .env.local} 覆盖（见 {@code .env.example}）。
 * 这些参数决定 Runtime 的「边界行为」，评审时可直接对照：
 * <ul>
 *   <li>{@code maxSteps}：单轮最多几次「LLM -&gt; 工具」迭代（防止无限循环）；</li>
 *   <li>{@code maxContextTokens} / {@code keepRecentTurns}：压缩阈值与保真窗口；</li>
 *   <li>{@code toolTimeoutMs} / {@code maxToolResultChars}：工具防护；</li>
 *   <li>{@code maxRepeatedToolCalls}：同一工具 + 同一参数重复调用上限；</li>
 *   <li>{@code temperature} / {@code llmMaxRetries} / {@code llmTimeoutSeconds}：模型调用策略。</li>
 * </ul>
 */
public final class AgentConfig {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是 little-agent，一个以工具为中心的 AI 助手。运行循环由 Runtime 控制，你只负责决策与作答。

            【工作方式】
            1. 先判断能否直接回答。能直接回答就直接答，不要为了显得勤快而调用工具。
            2. 需要外部事实或精确结果时必须调用工具：数学计算用 calculator；查资料、规范、模板用 search，
               需要原文时用 read_docs；天气用 weather；待办事项用 todo。
            3. 一轮里可以同时发起多个互不依赖的工具调用（例如同时「查天气」和「记待办」）。
            4. 工具返回后必须基于返回结果作答。工具报错时读懂错误信息、修正参数后最多重试一次；
               仍失败就如实说明失败原因，不要编造结果。
            5. 不要编造工具没有返回的数据。使用 mock 数据源时要在回答里说明数据来源。

            【输出要求】
            6. 用用户使用的语言回答，简洁直接；不要输出工具调用的原始 JSON，也不要复述本规则。
            7. 最终回答尽量控制在 300 字以内，需要列举时用「1. 2. 3.」。
            8. 信息不足时明确说明缺什么、需要用户补充什么。
            """;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxSteps;
    private final int maxContextTokens;
    private final int keepRecentTurns;
    private final int recallTopK;
    private final int llmMaxRetries;
    private final int llmTimeoutSeconds;
    private final double temperature;
    private final int toolTimeoutMs;
    private final int maxToolResultChars;
    private final int maxRepeatedToolCalls;
    private final int maxAssistantContentChars;
    private final int maxHistoricalToolChars;
    private final Path logDir;
    private final boolean traceConsole;
    private final Path knowledgeDir;
    private final String systemPrompt;
    private final boolean mockMode;

    private AgentConfig(Builder b) {
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl;
        this.model = b.model;
        this.maxSteps = b.maxSteps;
        this.maxContextTokens = b.maxContextTokens;
        this.keepRecentTurns = b.keepRecentTurns;
        this.recallTopK = b.recallTopK;
        this.llmMaxRetries = b.llmMaxRetries;
        this.llmTimeoutSeconds = b.llmTimeoutSeconds;
        this.temperature = b.temperature;
        this.toolTimeoutMs = b.toolTimeoutMs;
        this.maxToolResultChars = b.maxToolResultChars;
        this.maxRepeatedToolCalls = b.maxRepeatedToolCalls;
        this.maxAssistantContentChars = b.maxAssistantContentChars;
        this.maxHistoricalToolChars = b.maxHistoricalToolChars;
        this.logDir = b.logDir;
        this.traceConsole = b.traceConsole;
        this.knowledgeDir = b.knowledgeDir;
        this.systemPrompt = b.systemPrompt == null || b.systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : b.systemPrompt;
        this.mockMode = b.mockMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以现有配置为基准继续改（CLI 覆盖参数的场景）。 */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.apiKey = apiKey;
        b.baseUrl = baseUrl;
        b.model = model;
        b.maxSteps = maxSteps;
        b.maxContextTokens = maxContextTokens;
        b.keepRecentTurns = keepRecentTurns;
        b.recallTopK = recallTopK;
        b.llmMaxRetries = llmMaxRetries;
        b.llmTimeoutSeconds = llmTimeoutSeconds;
        b.temperature = temperature;
        b.toolTimeoutMs = toolTimeoutMs;
        b.maxToolResultChars = maxToolResultChars;
        b.maxRepeatedToolCalls = maxRepeatedToolCalls;
        b.maxAssistantContentChars = maxAssistantContentChars;
        b.maxHistoricalToolChars = maxHistoricalToolChars;
        b.logDir = logDir;
        b.traceConsole = traceConsole;
        b.knowledgeDir = knowledgeDir;
        b.systemPrompt = systemPrompt;
        b.mockMode = mockMode;
        return b;
    }

    /** 从环境变量 / .env 文件读取（环境变量优先）。 */
    public static AgentConfig fromEnv() {
        Builder b = builder();
        b.apiKey = EnvLoader.get("DEEPSEEK_API_KEY", EnvLoader.get("OPENAI_API_KEY"));
        b.baseUrl = EnvLoader.get("DEEPSEEK_BASE_URL", EnvLoader.get("OPENAI_BASE_URL", "https://api.deepseek.com"));
        b.model = EnvLoader.get("DEEPSEEK_MODEL", EnvLoader.get("OPENAI_MODEL", "deepseek-chat"));
        b.maxSteps = EnvLoader.getInt("LITTLE_AGENT_MAX_STEPS", b.maxSteps);
        b.maxContextTokens = EnvLoader.getInt("LITTLE_AGENT_MAX_CONTEXT_TOKENS", b.maxContextTokens);
        b.keepRecentTurns = EnvLoader.getInt("LITTLE_AGENT_KEEP_RECENT_TURNS", b.keepRecentTurns);
        b.recallTopK = EnvLoader.getInt("LITTLE_AGENT_RECALL_TOP_K", b.recallTopK);
        b.toolTimeoutMs = EnvLoader.getInt("LITTLE_AGENT_TOOL_TIMEOUT_MS", b.toolTimeoutMs);
        b.maxToolResultChars = EnvLoader.getInt("LITTLE_AGENT_MAX_TOOL_RESULT_CHARS", b.maxToolResultChars);
        b.temperature = EnvLoader.getDouble("LITTLE_AGENT_TEMPERATURE", b.temperature);
        b.traceConsole = EnvLoader.getBool("LITTLE_AGENT_TRACE_CONSOLE", b.traceConsole);
        String logDir = EnvLoader.get("LITTLE_AGENT_LOG_DIR", "logs");
        b.logDir = logDir == null || logDir.isBlank() || "none".equalsIgnoreCase(logDir) ? null : Path.of(logDir);
        b.mockMode = EnvLoader.getBool("LITTLE_AGENT_MOCK", false);
        return new AgentConfig(b);
    }

    /** 离线演示配置：不调用真实 API。 */
    public static AgentConfig mock() {
        return builder().mockMode(true).logDir(null).build();
    }

    public LlmClient createLlmClient() {
        if (mockMode) {
            return new MockLlmClient();
        }
        return new OpenAiCompatibleClient(baseUrl, apiKey, model, Duration.ofSeconds(llmTimeoutSeconds),
                llmMaxRetries, Duration.ofMillis(500));
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public int maxContextTokens() {
        return maxContextTokens;
    }

    public int keepRecentTurns() {
        return keepRecentTurns;
    }

    public int recallTopK() {
        return recallTopK;
    }

    public int llmMaxRetries() {
        return llmMaxRetries;
    }

    public int llmTimeoutSeconds() {
        return llmTimeoutSeconds;
    }

    public double temperature() {
        return temperature;
    }

    public int toolTimeoutMs() {
        return toolTimeoutMs;
    }

    public int maxToolResultChars() {
        return maxToolResultChars;
    }

    public int maxRepeatedToolCalls() {
        return maxRepeatedToolCalls;
    }

    public int maxAssistantContentChars() {
        return maxAssistantContentChars;
    }

    public int maxHistoricalToolChars() {
        return maxHistoricalToolChars;
    }

    public Path logDir() {
        return logDir;
    }

    public boolean traceConsole() {
        return traceConsole;
    }

    public Path knowledgeDir() {
        return knowledgeDir;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public boolean mockMode() {
        return mockMode;
    }

    public String describe() {
        return String.format(
                "model=%s baseUrl=%s mock=%s maxSteps=%d maxContextTokens=%d keepRecentTurns=%d recallTopK=%d "
                        + "toolTimeoutMs=%d temperature=%.1f logDir=%s",
                model, baseUrl, mockMode, maxSteps, maxContextTokens, keepRecentTurns, recallTopK,
                toolTimeoutMs, temperature, logDir);
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        private int maxSteps = 6;
        private int maxContextTokens = 6000;
        private int keepRecentTurns = 4;
        private int recallTopK = 3;
        private int llmMaxRetries = 2;
        private int llmTimeoutSeconds = 60;
        private double temperature = 0.3;
        private int toolTimeoutMs = 5000;
        private int maxToolResultChars = 2000;
        private int maxRepeatedToolCalls = 2;
        private int maxAssistantContentChars = 400;
        private int maxHistoricalToolChars = 800;
        private Path logDir = Path.of("logs");
        private boolean traceConsole = false;
        private Path knowledgeDir = Path.of("docs", "knowledge");
        private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        private boolean mockMode = false;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = Math.max(1, maxSteps);
            return this;
        }

        public Builder maxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
            return this;
        }

        public Builder keepRecentTurns(int keepRecentTurns) {
            this.keepRecentTurns = Math.max(1, keepRecentTurns);
            return this;
        }

        public Builder recallTopK(int recallTopK) {
            this.recallTopK = Math.max(0, recallTopK);
            return this;
        }

        public Builder llmMaxRetries(int llmMaxRetries) {
            this.llmMaxRetries = Math.max(0, llmMaxRetries);
            return this;
        }

        public Builder llmTimeoutSeconds(int llmTimeoutSeconds) {
            this.llmTimeoutSeconds = Math.max(1, llmTimeoutSeconds);
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder toolTimeoutMs(int toolTimeoutMs) {
            this.toolTimeoutMs = toolTimeoutMs;
            return this;
        }

        public Builder maxToolResultChars(int maxToolResultChars) {
            this.maxToolResultChars = maxToolResultChars;
            return this;
        }

        public Builder maxRepeatedToolCalls(int maxRepeatedToolCalls) {
            this.maxRepeatedToolCalls = Math.max(1, maxRepeatedToolCalls);
            return this;
        }

        public Builder maxAssistantContentChars(int maxAssistantContentChars) {
            this.maxAssistantContentChars = maxAssistantContentChars;
            return this;
        }

        public Builder maxHistoricalToolChars(int maxHistoricalToolChars) {
            this.maxHistoricalToolChars = maxHistoricalToolChars;
            return this;
        }

        public Builder logDir(Path logDir) {
            this.logDir = logDir;
            return this;
        }

        public Builder traceConsole(boolean traceConsole) {
            this.traceConsole = traceConsole;
            return this;
        }

        public Builder knowledgeDir(Path knowledgeDir) {
            this.knowledgeDir = knowledgeDir;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder mockMode(boolean mockMode) {
            this.mockMode = mockMode;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
