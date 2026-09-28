# 测试用例说明

> 目标：让「AI 写的代码」也具备可验证性 —— 循环逻辑用确定性替身精确断言，协议层用本地 HTTP 桩验证，
> 模型协作能力用真实 API 集成测试兜底。

---

## 1. 运行方式

```bash
mvn test                       # 163 个单元测试（离线、约 3 秒、零 API 费用）
mvn verify                     # 额外运行 5 个真实 API 集成测试（DeepSeekLiveIT，无 Key 自动跳过）
mvn "-Dtest=AgentLoopTest" test             # 单个测试类
mvn "-Dtest=AgentLoopTest#forcesWrapUpAtMaxSteps" test   # 单个用例
mvn "-Dmaven.repo.local=.m2repo" test       # 仅在受限沙箱中需要（本地仓库可写）
```

**当前结果**

| 阶段 | 用例数 | 结果 |
| --- | --- | --- |
| `mvn test`（单元） | 163 | ✅ 全部通过 |
| `mvn verify`（含真实 API） | +5 | ✅ 全部通过（无 Key 时 skip） |

---

## 2. 分层策略：为什么这样测

| 层次 | 手法 | 理由 |
| --- | --- | --- |
| 主循环（`AgentRuntime`） | `ScriptedLlmClient` 剧本化 LLM | 只有控制住「模型每一步输出什么」，才能断言 Runtime 的决策行为 |
| HTTP 协议层 | JDK 内置 `HttpServer` 打桩 | 验证真实 wire format、重试与错误分类，且不依赖外网 |
| 工具与上下文 | 真实实现 + 临时目录 | 逻辑本身确定，无需替身；`@TempDir` 构建知识库 |
| 模型协作能力 | 真实 API 集成测试（`*IT`） | 单元测试证明「逻辑对」，真实 API 证明「协议与模型协作对」，二者不可互替 |
| 端到端场景 | `MockLlmClient` + 真实工具/会话 | 无 Key 也能锁死题目要求的 6 个场景 |

---

## 3. 用例清单

### 3.1 `AgentLoopTest`（15 例）— 主循环四步与边界

| 用例 | 断言要点 |
| --- | --- |
| `directAnswerWithoutTools` | 能直接答就不调工具；1 步结束；历史 = user + assistant |
| `executesToolAndFeedsResultBack` | 第 2 次请求必须包含 `TOOL` 消息且内容含结果；仍开放 5 个工具；历史保留 `assistant(tool_calls)` + `tool` 配对且 `toolCallId` 一致 |
| `executesMultipleToolsInOneStep` | 一轮内两个工具都执行成功 |
| `forcesWrapUpAtMaxSteps` | 状态 `MAX_STEPS`；**收尾请求 tools 为空**；trace 有 `max_steps_reached` |
| `deterministicWrapUpWhenModelFails` | 收尾也失败时返回确定性兜底文案（含已获得的工具结论），不抛异常 |
| `toolErrorIsFedBackAndLoopContinues` | 工具错误前缀 `[TOOL_ERROR]`、错误码、错误文案回灌；循环继续 |
| `unknownToolIsReportedBack` | 幻觉工具名 → `UNKNOWN_TOOL`；模型据此改口 |
| `blocksRepeatedIdenticalCalls` | 相同工具+参数超阈值 → `REPEATED_CALL`；trace 有 `tool_blocked` |
| `repairsEmptyModelOutput` | 空输出注入系统提醒后自愈；历史含提醒消息 |
| `failsAfterTwoEmptyResponses` | 连续两次空输出 → `FAILED` + `EMPTY_MODEL_OUTPUT` |
| `llmFailureBecomesResult` | `LlmException` → `FAILED` + 错误码，不向调用方抛异常 |
| `unexpectedExceptionBecomesResult` | 未预期异常同样收敛 |
| `rejectsBlankInput` | 空白输入直接 `EMPTY_INPUT`，**零 LLM 调用** |
| `recordsFullTrace` | 8 类 trace 事件齐全 |
| `recordsPromptTokens` | 真实 `prompt_tokens` 被记入 Session（供压缩信号使用） |

### 3.2 `OpenAiCompatibleClientTest`（16 例）— 真实协议的桩验证

| 用例 | 断言要点 |
| --- | --- |
| `buildsCorrectRequestBody` | messages 顺序/角色、`tool_calls[].id/type/function{name,arguments}`、`tool_call_id`、`tools[].function.parameters`、`tool_choice=auto`、`stream=false` |
| `neverSendsReasoningBack` | 请求体搜不到思维链文本 |
| `omitsToolsWhenEmpty` | 未开放工具时不发送 `tools`/`tool_choice` |
| `parsesResponse` | `reasoning_content` / `tool_calls` / `finish_reason` / `usage` 全部解析正确 |
| `handlesNullContent` | `content=null`（纯工具调用）不抛 NPE |
| `normalizesObjectArguments` | `arguments` 为对象时归一化成字符串 |
| `retriesOn429` | 429 后重试成功，调用次数 = 2 |
| `retriesServerErrorsThenFails` | 5xx 重试到上限抛 `LLM_SERVER_ERROR`，调用次数 = 3（1+2 重试） |
| `doesNotRetryAuthErrors` | 401 → `LLM_AUTH_ERROR` 且**只调用 1 次** |
| `doesNotRetryBadRequest` | 400 → 不重试 |
| `rejectsMalformedResponse` / `rejectsMissingChoices` | `LLM_BAD_RESPONSE` |
| `normalizesEndpoint` | 4 种 base url 形态归一到同一端点 |
| `requiresApiKey` | 空 Key → `ConfigurationException`（不发请求） |
| `reportsNetworkError` | 不可达端口 → `LLM_NETWORK_ERROR`（可重试标记） |
| `recordsWireTrace` | 写 `llm_request` / `llm_response` |

### 3.3 `LlmOutputParserTest`（11 例）— 输出解析

原生 `tool_calls`；`<thought>` 抽取与剥离；`<tool_call>` 标签；```json 代码块（含**嵌套参数对象**）；
ReAct `Action/Action Input`；整段 JSON；多调用解析 + 去重；**防误判**（普通作答里的 JSON 不被当工具调用）；
空响应 → `EMPTY`；括号配平扫描（含字符串内 `}`）；`arguments` 为字符串时解析。

### 3.4 `ContextManagerTest`（11 例）— 上下文与记忆

| 用例 | 断言要点 |
| --- | --- |
| `assemblesInExpectedOrder` | 顺序：system 规则 → 摘要 → 工作记忆 → 召回 → 对话原文（保序） |
| `keepsOnlyRecentTurnsVerbatim` | 5 轮对话、保留 2 轮 ⇒ 原文 4 条、丢弃 6 条，且丢弃的是最早轮次 |
| `neverSplitsToolCallPairs` | 窗口起点必为 user；每条 `tool` 前必须是 `assistant(tool_calls)` 或 `tool` |
| `compactsWhenOverBudget` | 触发压缩、摘要非空、压缩位点停在 user 边界（=6）、写 `context_compressed` 事件、幂等（再压无变化） |
| `keepsRecentTurnsAndMemoryAfterCompaction` | 压缩后：工作记忆仍注入（system 块）、摘要仍注入、最近轮原文保留、久远轮次不在对话区 |
| `countsStaticOverheadInEstimate` | 估算差值 == 固定开销（回归「漏算工具 Schema」） |
| `usesRealPromptTokensAsSignal` | 真实 `prompt_tokens` 覆盖估算并触发压缩 |
| `trimsHistoricalAssistantContent` | 历史轮截断（含「已截断」），本轮不截断 |
| `recallsRelevantFragments` | 召回命中 + 片段进入上下文 + 写 `memory_recall` 事件 |
| `forceCompactWithoutEnoughTurns` | 轮次不足时安全返回 false |
| `handlesEmptyHistory` | 空会话只产出 1 条 system 消息 |

### 3.5 `AssignmentScenarioTest`（8 例）— 题目验收场景（端到端，离线）

| 用例 | 对应题目场景 |
| --- | --- |
| `windowOneAsksWeatherAndTodo` | 窗口 1：一轮内并发 `weather` + `todo`，回答含城市与待办 |
| `windowTwoSearchesDocsAndAddsTodo` | 窗口 2：`search` 检索知识库 + 记属于窗口 2 的待办 |
| `followUpWithoutToolsRemembersTodo` | **纯对话追问**：答出之前记的待办 |
| `followUpWithToolUsesContext` | **带工具追问**：理解「它」= #1 并标记完成 |
| `sessionsDoNotLeakTodos` | 窗口 2 回答里不得出现窗口 1 的待办（反向也不得出现） |
| `otherUserCannotAccessSession` | 用户 B 用 A 的 sessionId → `FAILED` + `SESSION_ERROR`；B 自建窗口正常 |
| `compactionKeepsLongTermMemory` | 触发压缩后，早期待办仍通过工作记忆生效 |
| `everyTurnIsTraceable` | 每轮 ≥8 条 trace，含 `tool_result` |

### 3.6 `ToolInvokerTest`（10 例）— 工具防护链条

未知工具（附可用清单）、坏 JSON、非对象参数、Schema 违规（逐字段提示）、类型纠偏后正常执行、
超时中断（`TOOL_TIMEOUT`）、异常折叠（含异常类型、**不含堆栈**）、超大结果截断、
每次调用两条 trace（`ok=false` 与 `errorCode` 正确）、**永不抛异常**。

### 3.7 `BuiltinToolsTest`（13 例）— 5 个内置工具

天气：确定性可复现、未知城市错误（附支持列表）、`today/tomorrow` 与非法日期、结果写入工作记忆；
待办：`add/list/done/clear` 全流程、缺参/ID 不存在/非法 action 的可读错误、**跨 session 不可见**；
检索与读文档：命中（返回 docId/章节/片段）、无命中不是错误（给可用文档）、分页与续读提示、
doc_id 不存在（附可用文档）、知识库目录缺失时降级为空库；内置工具清单稳定（防止漏注册）。

### 3.8 `CalculatorToolTest`（29 例）— 计算器

18 组表达式参数化正确性；全角符号容错；结果格式化（整数无小数点）；7 类非法表达式抛 `IllegalArgumentException`
（含除零、`sqrt(-1)`、括号不匹配、未知函数）；工具层返回 `EXPRESSION_INVALID` 而非抛异常；Schema 与描述校验。

### 3.9 `SessionManagerTest`（9 例）— 会话隔离

窗口间历史/工作记忆互不可见；`getOrCreate` 幂等且保留原标题；空 sessionId 新建；跨用户访问抛
`SessionAccessException`；重复创建同 id 失败；`list` 按用户与创建顺序、删除生效；
**8 线程 × 50 次并发写入**（断言总条数与流水号严格递增）；工作记忆生命周期与 LRU 上限；摘要位点增量更新。

### 3.10 `MemoryRecallerTest`（9 例）— 召回与摘要降级

按关键词相关度召回（问到杭州就召回杭州、不召回咖啡）；结果按时间正序；TopK 生效；
空 query/空池/topK=0 安全；渲染带来源标注；
摘要三级降级：LLM 失败 → 确定性摘要、LLM 返回空 → 降级、LLM 正常 → 用模型输出且提示词含旧摘要；
确定性摘要保留用户诉求与工具结论且长度受限。

### 3.11 `TraceTest`（8 例）— 可观测性

事件序号递增、内存可回溯、可渲染；`timed` 记录成功/失败与耗时；noop 不记录；
`data()` 忽略 null；**JSONL 每行合法 JSON 且 `at` 为 ISO 串**（回归 `Instant` 序列化问题）；
文件名安全化（拒绝 `..` 与路径分隔符）；sink 抛异常不影响主流程。

### 3.12 `SchemaValidatorTest`（6 例）与 `ToolRegistryTest`（5 例）

必填缺失/空串、类型错误、枚举越界、数值越界、非对象参数、类型纠偏、`coerce` 纯函数（不改原对象）；
注册/重名/非法工具、`specs()` 导出、默认注册表含 5 个工具且描述与 Schema 合法、`describeAvailable`。

### 3.13 `UtilTest`（7 例）

CJK bigram 分词与**标点排除**（回归问题 3）、单字保留、token 估算单调性与空值、截断与提示、
单行化、JSON 严格/宽松解析、`EnvLoader` 默认值与类型转换。

### 3.14 `DeepSeekLiveIT`（5 例）— 真实 API 集成

| 用例 | 断言要点 |
| --- | --- |
| `realModelUsesCalculator` | 模型**自主**调用 `calculator`，答案数字正确（容忍千分位） |
| `realModelRemembersAcrossTurns` | 第二轮追问能答出第一轮记的待办（工作记忆生效） |
| `realModelUsesKnowledgeBase` | 检索场景能给出周报相关字段 |
| `realSessionIsolation` | 两个 session 的工作记忆互不可见 |
| `realCallIsObservable` | 拿到真实 `prompt_tokens`，且 trace 有 `llm_response` |

> 无 Key 时通过 `Assumptions.assumeTrue` 跳过，CI 不会变红。

---

## 4. 测试替身与桩

| 替身 | 位置 | 作用 |
| --- | --- | --- |
| `ScriptedLlmClient` | `src/main`（生产代码内，供测试与离线回放复用） | 剧本化响应 + 记录请求 + 写 trace，行为与真实客户端对齐 |
| `MockLlmClient` | `src/main` | 关键词规则假模型，支撑 `--mock` 离线演示 |
| `StubTool` / `NumericTool` / `SlowTool` / `FailingTool` / `VerboseTool` | `src/test` | 分别验证注册、类型纠偏、超时、异常折叠、结果截断 |
| JDK `HttpServer` 桩 | `OpenAiCompatibleClientTest` | 验证真实 wire format 与重试策略 |

---

## 5. 覆盖度自检

```bash
mvn test                       # 163 passed
mvn verify                     # + 5 live passed（需 Key）
git grep -c "assert" -- src/test   # 断言密度检查
```

**未覆盖/已知缺口**（诚实声明）：

1. 真实 API 的**限流与超时**路径没有在 live 测试中主动触发（成本与不可控性），仅由桩测试覆盖；
2. 流式输出、多模态、工具并行执行尚未实现，因此无对应测试；
3. 长期运行的**内存增长**未做压测（Session 常驻内存，见 README 第 13 节限制）。
