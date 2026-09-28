# mini-agent Java 接口文档

> 版本：v1.0.0 ｜ 语言：Java 17 ｜ 包根：`com.miniagent`
> 适用对象：想接入 mini-agent、替换其中某一层、或新增工具/LLM/Session 存储的开发者。
> 本文件描述的是**当前代码的真实签名**，与 `src/main/java` 一一对应。

---

## 0. 文档约定

| 约定 | 说明 |
| --- | --- |
| 异常风格 | 全部为 **unchecked**（继承 `AgentException extends RuntimeException`），方法签名不出现 `throws`（`Tool#execute` 例外，允许抛出后由 `ToolInvoker` 收敛） |
| 不可变数据 | 传输对象均为 `record`；集合字段在构造时 `List.copyOf` 防御性拷贝 |
| 线程安全 | 标注「线程安全」的类可被多 session/多线程共享；`Session` 内部加锁，同一 session 串行、不同 session 并行 |
| 空值 | 方法不接受 `null` 输入时会显式抛 `IllegalArgumentException`/`AgentException`；可选字段为 `null` 表示「不设置」 |
| 包依赖方向 | `error ← util ← llm/tool/session ← context ← core ← cli`，无循环依赖 |

### 包结构总览

```
com.miniagent
├── Main                          命令行入口
├── cli/  Repl, DemoScenarios     交互式多窗口 REPL / 演示脚本
├── core/ AgentRuntime, AgentConfig, AgentResult    Agent 主循环与配置
├── llm/  LlmClient, OpenAiCompatibleClient, MockLlmClient, ScriptedLlmClient,
│         LlmRequest, LlmResponse, Message, Role, ToolCall, ToolSpec, Usage,
│         LlmOutputParser, ParsedOutput, LlmException
├── tool/ Tool, ToolResult, ToolContext, ToolRegistry, ToolInvoker, SchemaValidator
│   └── impl/ CalculatorTool, SearchTool, ReadDocsTool, TodoTool, WeatherTool,
│             KnowledgeBase, ExpressionEvaluator, DefaultTools
├── session/ Session, SessionManager, WorkingMemory, TodoItem
├── context/ ContextManager, ContextPackage, Summarizer, LlmSummarizer,
│            DeterministicSummarizer, MemoryRecaller
├── trace/ Tracer, TraceEvent, TraceSink, JsonlTraceSink, ConsoleTraceSink, TraceTypes
├── error/ AgentException, ConfigurationException, SessionAccessException
└── util/ Json, Texts, TokenEstimator, EnvLoader
```

---

## 1. 快速接入（最小可用示例）

```java
import com.miniagent.core.*;

AgentConfig config = AgentConfig.fromEnv();          // 读 DEEPSEEK_API_KEY / .env.local
try (AgentRuntime runtime = AgentRuntime.createDefault(config)) {

    // 用户 A 的窗口 1
    AgentResult r1 = runtime.run("A", "w1", "帮我查一下北京今天的天气，再记个待办：给张总发周报");
    System.out.println(r1.answer());
    System.out.println("工具调用次数 = " + r1.toolCallCount() + "，步数 = " + r1.steps().size());

    // 用户 A 的窗口 2（与窗口 1 完全隔离）
    AgentResult r2 = runtime.run("A", "w2", "我有哪些待办？");

    // 回到窗口 1 继续追问（历史、工作记忆、trace 都在 Session 里）
    AgentResult r3 = runtime.run("A", "w1", "把它标记成已完成");
}
```

---

## 2. 核心接口：`AgentRuntime`

`com.miniagent.core.AgentRuntime` —— Agent 主循环（自研，不依赖任何 Agent 框架）。

```java
public final class AgentRuntime implements AutoCloseable {

    public static AgentRuntime createDefault(AgentConfig config);
    public static AgentRuntime.Builder builder(AgentConfig config);

    /** 处理一次用户输入；同步阻塞直到返回最终回答。 */
    public AgentResult run(String userId, String sessionId, String userInput);

    public AgentConfig    config();
    public SessionManager sessions();
    public ToolRegistry   tools();
    public ContextManager contextManager();
    public LlmClient      llmClient();
    public void close();                       // 关闭工具执行线程池

    public static final class Builder {
        public Builder llm(LlmClient llm);                 // 不设置则用 config.createLlmClient()
        public Builder toolRegistry(ToolRegistry registry); // 不设置则注册 5 个内置工具
        public Builder summarizer(Summarizer summarizer);   // 不设置则真实模式用 LlmSummarizer
        public AgentRuntime build();
    }
}
```

### 2.1 `run` 的契约

| 项 | 说明 |
| --- | --- |
| `userId` | 用户标识，不能为空白；用于 session 归属校验（多用户隔离） |
| `sessionId` | 窗口标识；`null`/空白 ⇒ 新建会话；已存在 ⇒ 续写该会话；属于他人 ⇒ 返回失败结果（`SESSION_ERROR`） |
| `userInput` | 空白 ⇒ 立即返回 `Status.FAILED` + `errorCode=EMPTY_INPUT`，不产生任何 LLM 调用 |
| 返回 | 永不抛异常：LLM 故障、工具故障、上下文故障都被收敛为 `AgentResult` |
| 幂等性 | 非幂等：每次调用都会向 session 追加 user 消息 |
| 并发语义 | **同一 session 串行**（内部持锁，保证历史与工具配对一致）；**不同 session 完全并行** |
| 超时 | 由 `AgentConfig.llmTimeoutSeconds`（单次 LLM）与 `toolTimeoutMs`（单次工具）共同约束 |

### 2.2 循环步骤（对应题目要求）

| 步骤 | 代码位置 | 行为 |
| --- | --- | --- |
| step 1 接收用户输入 | `run` 开头 | 取/建 Session → 追加 `USER` 消息 → 写 trace |
| step 2 判断直接回复还是调用工具 | `ContextManager.build` + `llm.chat` + `LlmOutputParser.parse` | 组装上下文 → 调 LLM → 解析出 `thought / toolCalls / finalAnswer` |
| step 3 调用工具 | `ToolInvoker.invoke` | Schema 校验 → 超时保护 → 执行 → 截断 → trace |
| step 4 判断继续还是收尾 | `run` 循环尾部 | 有 `toolCalls` ⇒ 回灌 `TOOL` 消息并进入下一轮；否则返回最终答案 |

### 2.3 终止条件（硬边界）

1. 模型给出不含工具调用的最终答案 → `Status.OK`；
2. 达到 `maxSteps` → 禁用工具强制收尾 → `Status.MAX_STEPS`（再失败则返回确定性兜底文案）；
3. LLM 连续两次返回空内容 → `Status.FAILED` + `EMPTY_MODEL_OUTPUT`；
4. LLM 异常重试耗尽 → `Status.FAILED` + `LLM_*` 错误码；
5. 同一 `工具名 + 相同参数` 超过 `maxRepeatedToolCalls` → 拦截并回灌 `REPEATED_CALL`（循环继续，不会死循环）。

---

## 3. 配置：`AgentConfig`

```java
public final class AgentConfig {
    public static AgentConfig fromEnv();            // 环境变量优先，其次 .env / .env.local
    public static AgentConfig mock();               // 离线配置（MockLlmClient，不落盘 trace）
    public static Builder builder();
    public Builder toBuilder();

    public LlmClient createLlmClient();             // mock 模式返回 MockLlmClient，否则 OpenAiCompatibleClient
    public boolean hasApiKey();
    public String describe();                       // 一行摘要，用于启动日志
    // getters: apiKey, baseUrl, model, maxSteps, maxContextTokens, keepRecentTurns, recallTopK,
    //          llmMaxRetries, llmTimeoutSeconds, temperature, toolTimeoutMs, maxToolResultChars,
    //          maxRepeatedToolCalls, maxAssistantContentChars, maxHistoricalToolChars,
    //          logDir, traceConsole, knowledgeDir, systemPrompt, mockMode
    public static final String DEFAULT_SYSTEM_PROMPT;
}
```

### 3.1 Builder 参数与默认值

| 方法 | 默认 | 作用 |
| --- | --- | --- |
| `apiKey(String)` | 环境变量 | API Key；缺失且非 mock ⇒ 构造客户端时抛 `ConfigurationException` |
| `baseUrl(String)` | `https://api.deepseek.com` | OpenAI 兼容网关地址，自动补 `/v1/chat/completions` |
| `model(String)` | `deepseek-chat` | 模型名 |
| `maxSteps(int)` | 6 | 单轮最多「LLM→工具」迭代次数 |
| `maxContextTokens(int)` | 6000 | 上下文预算（估算 token），超过触发压缩 |
| `keepRecentTurns(int)` | 4 | 压缩时保留的最近完整轮数 |
| `recallTopK(int)` | 3 | 每轮召回的历史片段上限 |
| `llmMaxRetries(int)` | 2 | 网络/429/5xx 重试次数（指数退避 + 抖动） |
| `llmTimeoutSeconds(int)` | 60 | 单次 LLM 请求超时 |
| `temperature(double)` | 0.3 | 采样温度 |
| `toolTimeoutMs(int)` | 5000 | 单次工具执行超时 |
| `maxToolResultChars(int)` | 2000 | 工具结果写入上下文前的截断长度 |
| `maxRepeatedToolCalls(int)` | 2 | 相同工具+相同参数的最大执行次数 |
| `maxAssistantContentChars(int)` | 400 | 历史 assistant 正文的截断长度 |
| `maxHistoricalToolChars(int)` | 800 | 历史工具结果的截断长度 |
| `logDir(Path)` | `logs` | JSONL trace 目录；`null` 关闭落盘 |
| `traceConsole(boolean)` | false | 是否把 trace 打到控制台 |
| `knowledgeDir(Path)` | `docs/knowledge` | search / read_docs 的知识库目录 |
| `systemPrompt(String)` | `DEFAULT_SYSTEM_PROMPT` | 覆盖系统提示词 |
| `mockMode(boolean)` | false | 离线假模型模式 |

### 3.2 对应环境变量

`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`、`MINI_AGENT_MAX_STEPS`、
`MINI_AGENT_MAX_CONTEXT_TOKENS`、`MINI_AGENT_KEEP_RECENT_TURNS`、`MINI_AGENT_RECALL_TOP_K`、
`MINI_AGENT_TOOL_TIMEOUT_MS`、`MINI_AGENT_MAX_TOOL_RESULT_CHARS`、`MINI_AGENT_TEMPERATURE`、
`MINI_AGENT_LOG_DIR`、`MINI_AGENT_TRACE_CONSOLE`、`MINI_AGENT_MOCK`。

---

## 4. 结果模型：`AgentResult`

```java
public record AgentResult(
        String sessionId, String answer, Status status,
        List<StepRecord> steps, Usage totalUsage,
        long latencyMs, String errorCode) {

    public enum Status { OK, MAX_STEPS, FAILED }
    public boolean failed();
    public boolean ok();
    public int toolCallCount();

    public record StepRecord(int index, Instant at, String thought,
                             List<ToolCall> toolCalls, List<ToolOutcome> outcomes,
                             String finalAnswer, Usage usage) {
        public boolean isFinal();
    }

    public record ToolOutcome(String tool, boolean ok, String errorCode,
                              String resultPreview, long latencyMs) {}
}
```

| 字段 | 含义 |
| --- | --- |
| `answer` | 给用户的最终回答；失败时是可读的错误说明（可直接展示） |
| `status` | `OK` 正常收尾 / `MAX_STEPS` 强制收尾 / `FAILED` 不可恢复错误 |
| `steps` | 逐步记录：每步的思考、发起的工具调用、工具结果摘要、该步 token |
| `totalUsage` | 本轮累计 token（多次 LLM 调用求和） |
| `latencyMs` | 端到端耗时 |
| `errorCode` | 失败或强制收尾时的稳定错误码，见附录 A |

---

## 5. LLM 层

### 5.1 `LlmClient`（可替换点）

```java
public interface LlmClient {
    LlmResponse chat(LlmRequest request, Tracer tracer);      // 抽象方法
    default LlmResponse chat(LlmRequest request);             // 便捷重载（noop tracer）
    default String clientName();
}
```

三个实现：

| 实现 | 用途 | 是否发网络请求 |
| --- | --- | --- |
| `OpenAiCompatibleClient` | 生产：DeepSeek / 任意 OpenAI 兼容网关 | 是 |
| `ScriptedLlmClient` | 测试：按剧本返回预设响应并记录请求，精确断言循环行为 | 否 |
| `MockLlmClient` | 离线演示 `--mock`：关键词规则假模型 | 否 |

### 5.2 `OpenAiCompatibleClient`

```java
public class OpenAiCompatibleClient implements LlmClient {
    public OpenAiCompatibleClient(String baseUrl, String apiKey, String defaultModel,
                                  Duration requestTimeout, int maxRetries, Duration initialBackoff);
    public static OpenAiCompatibleClient deepSeek(String apiKey, String model);
    public String endpoint();
    public LlmResponse chat(LlmRequest request, Tracer tracer);
}
```

**契约**

* 端点规整：`https://api.deepseek.com`、`.../`、`.../v1` 均归一到 `.../v1/chat/completions`；已含 `/chat/completions` 的原样使用。
* 报文：`model / messages / tools / tool_choice(auto) / temperature / max_tokens / stream=false`。
* **思维链永不回灌**：`Message.reasoning` 不写入请求体（DeepSeek 官方要求，且属 Runtime 观测信息）。
* 工具消息严格携带 `tool_call_id`；assistant 工具调用消息带 `tool_calls[].id/type/function{name,arguments}`。
* 重试：仅对 **网络异常 / 429 / 5xx** 重试，指数退避 + 抖动，429 额外加倍；`maxRetries` 次后抛出。
* 不重试：401/403（`auth`）、400/404/422（`badRequest`）。
* 响应解析：兼容 `content=null`、`reasoning_content`/`reasoning`、`arguments` 为对象或字符串两种形态。
* 失败返回 `LlmException`（unchecked），error code 见附录 A。
* 构造时 `apiKey` 为空白 ⇒ 立即抛 `ConfigurationException`。

### 5.3 请求 / 响应模型

```java
public record LlmRequest(String model, List<Message> messages, List<ToolSpec> tools,
                         Double temperature, Integer maxTokens, String toolChoice) {
    public static Builder builder();          // model / messages / addMessage / tools /
                                              // temperature / maxTokens / toolChoice / build
    public int estimatedInputTokens();
}

public record LlmResponse(String content, String reasoning, List<ToolCall> toolCalls,
                          String finishReason, Usage usage, String rawResponseBody) {
    public static LlmResponse text(String content);
    public static LlmResponse withToolCalls(String content, List<ToolCall> calls);
    public boolean hasNativeToolCalls();
}

public record ToolSpec(String name, String description, JsonNode parameters);   // parameters 为 JSON Schema
public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    public static final Usage ZERO;
    public static Usage of(int prompt, int completion);
}
```

### 5.4 `Message`

```java
public record Message(Role role, String content, String reasoning,
                      List<ToolCall> toolCalls, String toolCallId, String toolName,
                      long seq, Instant at) {

    public static Message system(String content);
    public static Message user(String content);
    public static Message assistant(String content, String reasoning);
    public static Message assistantToolCalls(String content, String reasoning, List<ToolCall> calls);
    public static Message tool(String toolCallId, String toolName, String content);

    public Message withSeq(long seq);
    public Message withContent(String content);
    public boolean hasToolCalls();
    public boolean isUser();
    public int estimatedTokens();
    public String brief();
}

public enum Role { SYSTEM, USER, ASSISTANT, TOOL; public String wireName(); public static Role fromWire(String); }
```

> `seq` 是会话内自增流水号（用于窗口切分、召回打分、trace 关联）；`at` 为产生时间。`reasoning` 只用于 UI/trace/摘要，**不参与** wire 报文。

### 5.5 `ToolCall`

```java
public record ToolCall(String id, String name, String argumentsJson) {
    public static ToolCall of(String name, String argumentsJson);   // 自动生成 id
    public static String newId();                                   // call_xxxxxxxxxxxx
    public JsonNode arguments();                                    // 解析失败返回 null
    public String signature();                                      // name|args，用于去重与重复调用保护
}
```

### 5.6 输出解析：`LlmOutputParser` / `ParsedOutput`

```java
public final class LlmOutputParser {
    public static ParsedOutput parse(LlmResponse response);
}

public record ParsedOutput(String thought, String finalAnswer,
                           List<ToolCall> toolCalls, Mode mode) {
    public enum Mode { NATIVE_TOOL_CALLS, TEXT_TOOL_CALLS, FINAL_ANSWER, EMPTY }
    public boolean hasToolCalls();
    public boolean hasFinalAnswer();
}
```

**解析优先级**

| 优先级 | 输入形态 | 结果 `mode` |
| --- | --- | --- |
| 1 | 原生 `tool_calls` 字段 | `NATIVE_TOOL_CALLS` |
| 2 | `<tool_call>{...}</tool_call>` 标签 | `TEXT_TOOL_CALLS` |
| 3 | ```json 代码块中形如 `{name|tool, arguments|parameters}` 的对象 | `TEXT_TOOL_CALLS` |
| 4 | ReAct 文本 `Action: xxx` + `Action Input: {...}` | `TEXT_TOOL_CALLS` |
| 5 | 整段正文就是一个工具调用 JSON | `TEXT_TOOL_CALLS` |
| 6 | 其余非空正文 | `FINAL_ANSWER`（剥离 `<thought>` / `Thought:` 行） |
| 7 | 正文为空 | `EMPTY` |

* 支撑函数：`extractJsonObjects(String)` 用**括号配平扫描**（识别字符串与转义），避免正则被嵌套参数对象打败。
* 防误判：只有对象同时含「名称字段」与「参数类字段」才认定为工具调用，普通作答里的 JSON 不会被误执行。
* 思维过程来源：`reasoning_content` → `<thought>` → `Thought:/思考:/推理:` 行。

### 5.7 `LlmException`

```java
public class LlmException extends AgentException {
    public boolean retryable();
    public static LlmException network(String message, Throwable cause);  // LLM_NETWORK_ERROR
    public static LlmException rateLimited(String message);               // LLM_RATE_LIMITED
    public static LlmException serverError(String message);               // LLM_SERVER_ERROR
    public static LlmException auth(String message);                      // LLM_AUTH_ERROR
    public static LlmException badRequest(String message);                // LLM_BAD_REQUEST
    public static LlmException badResponse(String message);               // LLM_BAD_RESPONSE
}
```

---

## 6. 工具层（SPI）

### 6.1 `Tool` —— 新增工具只需实现该接口

```java
public interface Tool {
    String name();                                     // 全局唯一，动词化
    String description();                              // 模型判断「何时调用」的唯一依据
    ObjectNode parametersSchema();                     // JSON Schema（type=object + properties + required）
    ToolResult execute(ObjectNode arguments, ToolContext context) throws Exception;
    default List<String> requiredParameters();
}
```

**实现契约**

1. **线程安全**：同一实例会被多 session/多线程并发调用，禁止在工具里保存可变共享状态（状态请放 `ToolContext.session().memory()`）。
2. **失败即返回**：可抛异常（由 `ToolInvoker` 转成错误结果），也可直接返回 `ToolResult.error(code, msg)`。
3. **错误信息写给模型看**：含「哪个参数错了 / 正确取值是什么 / 如何重试」，模型据此自愈。
4. **不得回显敏感信息**：错误信息会进入上下文。

### 6.2 `ToolResult` / `ToolContext`

```java
public record ToolResult(boolean ok, String content, String errorCode) {
    public static ToolResult ok(String content);
    public static ToolResult error(String errorCode, String message);
    public String toObservation();          // 失败时加 "[TOOL_ERROR] " 前缀（回灌给模型）
}

public record ToolContext(String userId, String sessionId, String callId,
                          Session session, Tracer tracer) {
    public Tracer tracer();                 // 为空时返回 Tracer.noop()
}
```

### 6.3 `ToolRegistry` —— 注册机制

```java
public final class ToolRegistry {
    public synchronized ToolRegistry register(Tool tool);       // 重名/空名/空 Schema ⇒ AgentException
    public synchronized ToolRegistry registerAll(Tool... tools);
    public synchronized Optional<Tool> find(String name);
    public synchronized List<Tool> all();
    public synchronized List<String> names();
    public synchronized int size();
    public synchronized boolean contains(String name);
    public synchronized List<ToolSpec> specs();                 // 导出给 LLM 的工具声明
    public synchronized String describeAvailable();             // "a, b, c"，用于错误回灌
}
```

* 用 `LinkedHashMap` 保证顺序稳定（Schema 顺序稳定 ⇒ 提示词前缀稳定 ⇒ 更易命中 KV cache）。
* 重复注册抛 `AgentException("TOOL_REGISTRATION_ERROR")`，避免线上出现两个同名工具。

### 6.4 `ToolInvoker` —— 调用防护

```java
public final class ToolInvoker implements AutoCloseable {
    public ToolInvoker(ToolRegistry registry, int timeoutMs, int maxResultChars);
    public ToolResult invoke(ToolCall call, ToolContext context);   // 永不抛异常
    public void close();
}
```

| 防护 | 触发条件 | 回灌内容 / 错误码 |
| --- | --- | --- |
| 未知工具 | 模型幻觉工具名 | 可用工具列表 / `UNKNOWN_TOOL` |
| 参数非 JSON | 参数拼坏 | 解析错误 + 原参数预览 / `BAD_ARGUMENTS_JSON` |
| 参数非对象 | 传了数组/标量 | 实际类型 / `BAD_ARGUMENTS_TYPE` |
| Schema 校验 | 缺必填、类型错、枚举越界、越界值 | 逐字段可读错误 / `INVALID_ARGUMENTS` |
| 类型纠偏 | `"top_k": "3"` 等 | 自动归一化后继续执行（不报错） |
| 执行超时 | 超过 `timeoutMs` | `TOOL_TIMEOUT`（线程被中断） |
| 执行异常 | 工具抛错 | 异常类型 + 消息（**不含堆栈**）/ `TOOL_EXECUTION_ERROR` |
| 调用被中断 | 线程中断 | `TOOL_INTERRUPTED` |
| 结果过大 | 超过 `maxResultChars` | 截断并附「已截断 N 字」 |

每次调用必定写两条 trace：`tool_call` 与 `tool_result`（含 `ok / errorCode / latencyMs / truncated`）。

### 6.5 `SchemaValidator`

```java
public final class SchemaValidator {
    public static List<String> validate(JsonNode schema, JsonNode arguments);   // 空列表 = 通过
    public static ObjectNode coerce(JsonNode schema, ObjectNode arguments);     // 数字/布尔字符串归一化（纯函数）
    public static String describe(List<String> violations);
    public static ObjectNode objectSchema(Map<String, JsonNode> properties, List<String> required);
}
```

支持子集：`type`（object/string/integer/number/boolean/array）、`required`、`properties`、`enum`、
`minimum`/`maximum`、`minLength`/`maxLength`、`items`。未声明的额外参数忽略（与 JSON Schema 默认一致）。

### 6.6 内置工具（5 个）

装配入口：

```java
public final class DefaultTools {
    public static ToolRegistry registry(Path knowledgeDir);   // null ⇒ 使用 docs/knowledge
    public static ToolRegistry standardRegistry();
}
```

| # | 名称 | 参数 Schema | 成功返回 | 主要错误码 |
| --- | --- | --- | --- | --- |
| 1 | `calculator` | `expression: string`（必填，minLength=1） | `表达式 = 结果` | `EXPRESSION_INVALID`、`EMPTY_EXPRESSION` |
| 2 | `search` | `query: string`（必填）、`top_k: integer`（1..10，默认 3） | 命中段落：`[docId § 章节] 相关度 x`+片段；无命中返回可用文档列表 | —（无命中是 `ok`） |
| 3 | `read_docs` | `doc_id: string`（必填）、`offset: integer ≥0`、`limit: integer 1..200`（默认 80） | 带页眉的正文 + 续读提示 | `DOC_NOT_FOUND` |
| 4 | `todo` | `action: enum{add,list,done,clear}`（必填）、`item: string`（add 必填，1..200）、`id: integer ≥1`（done 必填） | 待办清单 / 操作结果 | `MISSING_ITEM`、`MISSING_ID`、`TODO_NOT_FOUND`、`INVALID_ACTION`、`NO_SESSION` |
| 5 | `weather` | `city: string`（必填）、`date: string`（`today`/`tomorrow`/`YYYY-MM-DD`，默认 today） | `[mock 数据] 城市 日期：天气 …` | `CITY_NOT_SUPPORTED`、`INVALID_DATE` |

**实现说明（诚信标注 mock 边界）**

* `search` / `read_docs`：真实实现，数据源是本地 Markdown 目录，按 `## ` 切段 + 关键词打分（标题/短语命中加权 + 长度归一），匹配到的片段按「命中行居中」截取。
* `weather`：**mock 数据源**（12 个城市，种子 = `city+date` 的 hash ⇒ 同城同日结果可复现），返回值以 `[mock 数据]` 开头，且工具描述里写明不访问真实气象接口。
* `calculator`：自研递归下降求值器（`ExpressionEvaluator`），**不使用 `ScriptEngine`/`eval`**，避免把任意代码执行权交给模型输出；支持 `+ - * / % ^`、括号、`sqrt/abs/min/max/pow/round/floor/ceil/log/ln/exp/sin/cos/tan/sum`、常量 `pi/e`，容忍全角符号。
* `todo`：有状态工具，状态写在 `ToolContext.session().memory()`，**按 session 隔离**。

---

## 7. 会话层

### 7.1 `Session` —— 隔离单位

```java
public final class Session {
    public String id();
    public String userId();
    public String title();  public void title(String);
    public Tracer tracer();
    public WorkingMemory memory();
    public Instant createdAt();  public Instant updatedAt();
    public int turnCount();
    public String summary();            // 压缩摘要（长期记忆）
    public int summarizedUpTo();        // summary 覆盖 history[0, summarizedUpTo)
    public int lastPromptTokens();      // 最近一次真实 prompt_tokens
    public void lastPromptTokens(int);

    public Message append(Message message);        // 打流水号；user 消息推进轮次
    public List<Message> history();                // 快照（不可变）
    public int historySize();
    public List<Message> historyFrom(int index);
    public void updateSummary(String summary, int summarizedUpTo);
    public int estimatedTokens();
    public <T> T withLock(Supplier<T> body);       // 会话级临界区
    public String brief();
}
```

* 一个 Session = 一个窗口，独占：消息历史、工作记忆、压缩摘要与压缩位点、trace。
* 所有写操作走同一把 `ReentrantLock`；读取返回快照，调用方无需再同步。
* 状态常驻内存，因此「窗口可以随时被接着聊」。

### 7.2 `SessionManager`

```java
public final class SessionManager {
    public SessionManager();                                  // tracer 为 noop
    public SessionManager(Function<String, Tracer> tracerFactory);

    public Session create(String userId, String title);
    public Session create(String userId, String sessionId, String title);  // 指定可读 id（w1/w2）
    public Session getOrCreate(String userId, String sessionId, String title); // 幂等
    public Session require(String userId, String sessionId);  // 不存在/越权 ⇒ SessionAccessException
    public Optional<Session> find(String sessionId);
    public List<Session> list(String userId);                 // 创建顺序
    public int size();
    public void delete(String userId, String sessionId);
    public Map<String, Integer> sessionCountByUser();
}
```

**归属校验**：`sessionId` 属于其他用户时抛 `SessionAccessException`（`SESSION_ACCESS_DENIED`）。
`AgentRuntime.run` 会把它转成失败结果，因此用户 B 既读不到、也抢占不了用户 A 的窗口 id。

### 7.3 `WorkingMemory` / `TodoItem`

```java
public final class WorkingMemory {
    public void putFact(String key, String value);       // LRU，最多 20 条
    public String fact(String key);
    public Map<String, String> facts();
    public TodoItem addTodo(String text);                // 上限 50 条
    public Optional<TodoItem> completeTodo(int id);
    public boolean removeTodo(int id);
    public int clearCompleted();
    public void clearAll();
    public List<TodoItem> todos();
    public boolean isEmpty();
    public String render();                              // 注入上下文的紧凑文本
    public int estimatedTokens();
}

public record TodoItem(int id, String text, boolean done, Instant createdAt) {
    public TodoItem complete();
    public String render();                              // "#1 给张总发周报 [未完成]"
}
```

---

## 8. 上下文与记忆层

### 8.1 `ContextManager`

```java
public final class ContextManager {
    public static final String WORKING_MEMORY_HEADER;
    public static final String SUMMARY_HEADER;

    public ContextManager(Summarizer summarizer, int maxContextTokens, int keepRecentTurns, int recallTopK);
    public ContextManager(Summarizer summarizer, int maxContextTokens, int keepRecentTurns, int recallTopK,
                          int maxAssistantContentChars, int maxHistoricalToolChars);
    public ContextManager(Summarizer summarizer, int maxContextTokens, int keepRecentTurns, int recallTopK,
                          int maxAssistantContentChars, int maxHistoricalToolChars, int staticOverheadTokens);

    public ContextPackage build(Session session, String systemPrompt, List<ToolSpec> tools);
    public boolean compactIfNeeded(Session session);
    public boolean compact(Session session, boolean force);
    public int compactionSignal(Session session);
    public int estimate(Session session);
    public int staticOverheadTokens();
    public int maxContextTokens();
    public int keepRecentTurns();
}
```

**`build` 产出的消息顺序（每轮请求 LLM 前重建）**

```
[0] system  角色与规则（AgentConfig.systemPrompt）
[1] system  【长期记忆｜历史对话摘要】        ← 有摘要则必带
[2] system  【工作记忆｜结构化状态…】          ← 有待办/事实则必带
[3] system  【相关历史片段（自动召回）】        ← 按当前问题召回 Top-K
[4..] 对话消息：最近 keepRecentTurns 轮原文（保序、按 user 边界切完整轮次）
        · 本轮（最后一条 user 之后）保持原样
        · 历史轮：assistant 正文截断至 maxAssistantContentChars，tool 结果截断至 maxHistoricalToolChars
```

**记忆的写入与召回时机**

| 记忆类型 | 写入时机 | 召回时机 | 放置位置 |
| --- | --- | --- | --- |
| 短期（对话原文） | 每轮 user/assistant/tool 消息追加 | 每轮固定保留最近 N 轮 | 对话消息区（保序） |
| 工作记忆（待办/事实） | 工具执行时写入 | **每轮无条件注入** | system 块 |
| 长期（压缩摘要） | 超预算触发压缩时增量合并 | **每轮无条件注入** | system 块 |
| 情节（历史片段） | ——（沉淀在历史里） | 每轮以本轮用户输入为 query 打分召回 Top-K | system 块 |

**压缩策略**

* 触发信号：`compactionSignal = max(本地估算 + 固定开销, 最近一次真实 prompt_tokens)`。
  固定开销 = system prompt + 全部工具 Schema 的估算（工具 Schema 常占 1000+ token，漏算会导致压缩触发过晚——这是实测踩过的坑）。
* 压缩边界：从 `summarizedUpTo` 到「最近 `keepRecentTurns` 轮的起点」，**按 user 消息切分**，绝不切散 `assistant(tool_calls)` 与 `tool` 的配对。
* 增量合并：新摘要以旧摘要为前提（提示词里显式传入），避免早期信息被反复稀释。
* 降级：LLM 摘要失败/返回空 ⇒ `DeterministicSummarizer` 兜底（抽取用户诉求 + 工具结论），压缩动作永不阻塞对话。
* 已知边界：若预算小于「最近 N 轮的固有开销」，压缩后仍会超预算——属配置问题，应调大 `maxContextTokens` 或调小 `keepRecentTurns`。

### 8.2 `ContextPackage`

```java
public record ContextPackage(List<Message> messages, ContextStats stats) {
    public int estimatedTokens();

    public record ContextStats(int historyMessages, int verbatimMessages, int recalledFragments,
                               int droppedMessages, int summaryTokens, int workingMemoryTokens,
                               int estimatedTokens, boolean hasSummary, boolean hasWorkingMemory) {
        public static ContextStats of(List<Message> assembled, int historyMessages, int verbatimMessages,
                                      int recalledFragments, int droppedMessages, int summaryTokens,
                                      int workingMemoryTokens);
    }
}
```

组装统计会写入 trace 事件 `context_built`，可直接回答「这轮上下文里到底放了什么」。

### 8.3 `Summarizer` 及其实现

```java
public interface Summarizer {
    String summarize(String previousSummary, List<Message> messagesToCompress);
    default String name();
}

public final class LlmSummarizer implements Summarizer {
    public static final String SUMMARY_SYSTEM_PROMPT;
    public LlmSummarizer(LlmClient llm, String model);
    public LlmSummarizer(LlmClient llm, String model, Summarizer fallback);
}

public final class DeterministicSummarizer implements Summarizer { }
```

### 8.4 `MemoryRecaller`

```java
public final class MemoryRecaller {
    public MemoryRecaller();                        // 片段 400 字
    public MemoryRecaller(int fragmentMaxChars);
    public List<Message> recall(List<Message> pool, String query, int topK);   // 按时间正序返回
    public String render(List<Message> recalled);   // 带 "#seq role" 来源标注
    public int estimatedTokens(List<Message> recalled);
}
```

打分 = 关键词重叠（CJK bigram / 英文词，长度≥2 权重 1.0） × 角色权重（user 1.6 / tool 1.2 / assistant 1.0）
× 时间衰减（越新越高） ÷ 长度惩罚（长文降权，避免长工具输出霸榜）。仅保留分数 > 0 的片段。

---

## 9. 可观测性：trace

```java
public final class Tracer implements AutoCloseable {
    public static Tracer noop();
    public static Tracer of(String sessionId, TraceSink... sinks);
    public static Builder builder(String sessionId);

    public String sessionId();
    public boolean enabled();
    public TraceEvent event(String type, String summary, Map<String, Object> data);
    public TraceEvent event(String type, String summary);
    public TraceEvent event(String type, String summary, Object... keyValues);
    public <T> T timed(String type, String summary, Supplier<T> body);
    public <T> T timed(String type, String summary, Map<String, Object> extra, Supplier<T> body);
    public void failure(String type, String summary, Throwable error);
    public List<TraceEvent> events();      // 内存快照（上限 2000 条，FIFO）
    public void clear();
    public String render();                // 人读时间线
    public void close();
    public static Map<String, Object> data(Object... keyValues);   // 自动忽略 null
}

public record TraceEvent(long seq, Instant at, String sessionId, String type,
                         String summary, Map<String, Object> data, long durationMs) {
    public String render();
}

@FunctionalInterface
public interface TraceSink { void accept(TraceEvent event); }      // 实现必须线程安全

public final class JsonlTraceSink implements TraceSink {
    public JsonlTraceSink(Path dir, String sessionId);   // 写 <dir>/<sessionId>.jsonl，文件名安全化
    public Path file();
}

public final class ConsoleTraceSink implements TraceSink {
    public ConsoleTraceSink(boolean verbose);
}
```

**事件类型常量**（`TraceTypes`）：`session_created`、`user_input`、`context_built`、`memory_recall`、
`context_compressed`、`context_compress_failed`、`llm_request`、`llm_response`、`tool_call`、`tool_result`、
`tool_blocked`、`loop_step`、`final_answer`、`max_steps_reached`、`error`。

**保证**

* sink 抛异常不影响主流程；JSONL 写盘失败只记 warning。
* `Tracer.of(...)` 即使不带 sink 也会内存记录（便于 CLI `/trace` 与断言）。
* `JsonlTraceSink` 时间戳输出 ISO-8601 字符串（手工构造 JSON，避免为 `Instant` 引入额外 Jackson 模块）。

---

## 10. 异常体系

```java
public class AgentException extends RuntimeException {
    public AgentException(String code, String message);
    public AgentException(String code, String message, Throwable cause);
    public String code();                    // 稳定错误码，会写入 trace
}

public class ConfigurationException extends AgentException { }        // CONFIG_ERROR
public class SessionAccessException extends AgentException { }        // SESSION_ACCESS_DENIED
public class LlmException          extends AgentException { public boolean retryable(); }   // LLM_*
```

---

## 11. CLI 接口

### 11.1 启动参数（`Main`）

| 参数 | 说明 |
| --- | --- |
| `--mock` | 使用 `MockLlmClient`，不发任何网络请求 |
| `--demo` | 运行 6 个内置场景（多窗口 + 压缩演示） |
| `--once <文本>` | 非交互：处理一轮后退出（位置参数亦可） |
| `--user <id>` | 用户标识，默认 `A` |
| `--session <id>` | 窗口标识，默认 `w1` |
| `--max-steps <n>` | 覆盖单轮最大迭代次数 |
| `--max-context-tokens <n>` | 覆盖上下文预算 |
| `--log-dir <dir>` | trace 目录，`none` 关闭落盘 |
| `--trace` | 单轮结束后打印 trace 时间线 |
| `--verbose-trace` | 同时把 trace 打到控制台 |
| `--system-prompt <s>` | 覆盖系统提示词 |
| `-h` / `--help` | 帮助 |

### 11.2 交互命令（`Repl`）

| 命令 | 说明 |
| --- | --- |
| `/new [标题]` | 新建窗口（session）并切换 |
| `/use <序号\|id>` | 切换窗口 |
| `/sessions` | 列出当前用户全部窗口（含轮次/历史条数/压缩状态） |
| `/history [n]` | 查看最近 n 条消息（默认 10） |
| `/memory` | 查看工作记忆、压缩摘要、当前估算 token |
| `/trace [n]` | 查看最近 n 条 trace（默认 15） |
| `/compact` | 手动触发一次压缩 |
| `/verbose` | 切换步骤详情显示 |
| `/exit` | 退出 |

直接输入内容即与当前窗口对话。

---

## 12. 扩展指南

### 12.1 新增一个工具

```java
public final class ExchangeRateTool implements Tool {
    @Override public String name() { return "exchange_rate"; }

    @Override public String description() {
        return "查询两种货币之间的汇率（数据源：内部账务服务）。"
             + "当用户询问汇率、换算金额时使用；不含手续费计算。";
    }

    @Override public ObjectNode parametersSchema() {
        ObjectNode from = Json.obj().put("type", "string").put("description", "源币种，如 CNY");
        ObjectNode to   = Json.obj().put("type", "string").put("description", "目标币种，如 USD");
        return SchemaValidator.objectSchema(Map.of("from", from, "to", to), List.of("from", "to"));
    }

    @Override public ToolResult execute(ObjectNode args, ToolContext ctx) {
        String from = args.path("from").asText();
        String to = args.path("to").asText();
        if (from.equalsIgnoreCase(to)) {
            return ToolResult.error("SAME_CURRENCY", "源币种与目标币种相同，请换一个目标币种。");
        }
        double rate = lookUp(from, to);                       // 失败请抛异常或返回 error 结果
        ctx.session().memory().putFact("最近汇率", from + "/" + to + "=" + rate);
        return ToolResult.ok(from + " -> " + to + " = " + rate);
    }
}

// 装配
ToolRegistry registry = DefaultTools.registry(Path.of("docs", "knowledge"))
        .register(new ExchangeRateTool());
AgentRuntime runtime = AgentRuntime.builder(config).toolRegistry(registry).build();
```

要点：描述写清适用与不适用场景；Schema 声明类型与必填；错误信息可执行（模型能据此重试）；不要保存实例级可变状态。

### 12.2 替换 LLM

实现 `LlmClient#chat(LlmRequest, Tracer)`，通过 `AgentRuntime.builder(config).llm(client)` 注入即可（OpenAI 兼容网关、本地模型、录制回放皆可）。

### 12.3 替换 session 存储

`SessionManager` 的方法签名即为存储接口契约；把内部 `ConcurrentHashMap` 换成 Redis/DB 时保持：
`getOrCreate` 幂等、`require` 做归属校验、`list` 按创建顺序返回。

### 12.4 替换记忆召回

`MemoryRecaller` 是独立类，可整体替换为向量检索；只需保持 `recall(pool, query, topK)` 返回按时间正序的 `List<Message>`。

---

## 13. 附录 A：错误码一览

| 错误码 | 抛出/返回位置 | 含义与处置 |
| --- | --- | --- |
| `CONFIG_ERROR` | `ConfigurationException` | 缺少 API Key 等配置问题 |
| `SESSION_ACCESS_DENIED` | `SessionAccessException` | 会话不存在或不属于该用户 |
| `SESSION_ERROR` | `AgentResult.errorCode` | 打开会话失败（含越权） |
| `EMPTY_INPUT` | `AgentResult` | 用户输入为空 |
| `CONTEXT_ERROR` | `AgentResult` | 上下文组装失败 |
| `EMPTY_MODEL_OUTPUT` | `AgentResult` | 模型连续两次返回空内容 |
| `MAX_STEPS_REACHED` | `AgentResult` | 达到最大步数，已强制收尾 |
| `LLM_NETWORK_ERROR` | `LlmException` | 网络异常（可重试） |
| `LLM_RATE_LIMITED` | `LlmException` | 429 限流（可重试，退避加倍） |
| `LLM_SERVER_ERROR` | `LlmException` | 5xx（可重试） |
| `LLM_AUTH_ERROR` | `LlmException` | 401/403（不可重试） |
| `LLM_BAD_REQUEST` | `LlmException` | 400/404/422（不可重试） |
| `LLM_BAD_RESPONSE` | `LlmException` | 响应非 JSON / 缺 choices / 网关返回 error |
| `LLM_UNEXPECTED_ERROR` | `AgentResult` | 客户端未预期异常 |
| `TOOL_REGISTRATION_ERROR` | `AgentException` | 工具名为空或重复 |
| `UNKNOWN_TOOL` | 工具结果 | 模型请求了不存在的工具 |
| `BAD_ARGUMENTS_JSON` / `BAD_ARGUMENTS_TYPE` | 工具结果 | 参数不是合法 JSON / 不是对象 |
| `INVALID_ARGUMENTS` | 工具结果 | Schema 校验失败 |
| `TOOL_TIMEOUT` | 工具结果 | 工具执行超时 |
| `TOOL_EXECUTION_ERROR` | 工具结果 | 工具执行抛异常 |
| `TOOL_INTERRUPTED` | 工具结果 | 工具调用被中断 |
| `REPEATED_CALL` | 工具结果 | 重复调用被 Runtime 拦截 |
| `EXPRESSION_INVALID` / `EMPTY_EXPRESSION` | `calculator` | 表达式非法 / 为空 |
| `DOC_NOT_FOUND` | `read_docs` | doc_id 不存在 |
| `CITY_NOT_SUPPORTED` / `INVALID_DATE` | `weather` | 城市不支持 / 日期格式错 |
| `MISSING_ITEM` / `MISSING_ID` / `TODO_NOT_FOUND` / `INVALID_ACTION` / `NO_SESSION` | `todo` | 参数缺失或状态不存在 |

---

## 14. 附录 B：线程安全与性能契约

| 组件 | 线程安全 | 说明 |
| --- | --- | --- |
| `AgentRuntime` | ✅ | 内部无共享可变状态；`run` 可并发调用 |
| `Session` | ✅（内部加锁） | 同一 session 的 `run` 串行；不同 session 并行 |
| `SessionManager` | ✅ | `ConcurrentHashMap` + 每用户 id 列表同步块 |
| `ToolRegistry` | ✅ | 注册期与读取期方法均为 `synchronized` |
| `Tool` 实现 | ⚠️ 需自行保证 | 无状态或状态放 session 工作记忆 |
| `ToolInvoker` | ✅ | 守护线程池；每调用独立 `Future` |
| `Tracer` / `TraceSink` | ✅ | 内存列表用 `CopyOnWriteArrayList`；sink 自行同步 |
| `OpenAiCompatibleClient` | ✅ | `HttpClient` 线程安全，无每请求可变状态 |
| `LlmOutputParser` / `SchemaValidator` / `Texts` / `TokenEstimator` | ✅ | 纯静态无状态 |

**token 估算精度**：`TokenEstimator` 为启发式（中文 ≈0.75 token/字，英文 ≈4 字符/token），
偏差约 ±30%。压缩阈值判断优先采用 API 上报的精确 `prompt_tokens`，估算仅作兜底。

---

## 15. 版本与兼容性

* Java 17+（编译目标 `release 17`）；运行依赖仅 `jackson-databind`（JSON 编解码），**不引入任何 Agent 框架**。
* 公开 API 的兼容性承诺：`AgentRuntime`、`AgentConfig`、`AgentResult`、`Tool`、`ToolResult`、
  `ToolRegistry`、`Session`、`SessionManager`、`LlmClient`、`ContextManager` 的签名在 1.x 内保持向后兼容；
  其余为内部实现，可能调整。
