# AI Prompt 与问题解决记录

> 本文档记录两件事：
> ① **产品内的 Prompt**（Agent 系统提示词、压缩提示词、工具描述）及其设计意图；
> ② **开发过程的问题解决记录**（真实踩坑 → 定位 → 修复 → 沉淀成测试），以及 AI 工具在其中的使用方式。

---

## 第一部分：产品内 Prompt 设计与迭代

### P1. Agent 系统提示词

`com.miniagent.core.AgentConfig#DEFAULT_SYSTEM_PROMPT`（节选）：

```
你是 mini-agent，一个以工具为中心的 AI 助手。运行循环由 Runtime 控制，你只负责决策与作答。

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
```

**每条规则的来由**（都是实际观察到的问题反推出来的）：

| 规则 | 解决的问题 |
| --- | --- |
| 1「能直接答就直接答」 | 初版模型逢问必调工具，连「你好」都要 `search` 一下，浪费一次往返 |
| 3「一轮可并发多工具」 | 「查天气 + 记待办」若不提示，模型会拆成两轮，延迟翻倍 |
| 4「报错修正后最多重试一次」 | 不加限制时模型会反复重试同一个失败调用，直接撞上 Runtime 的重复调用拦截 |
| 5「mock 数据要标来源」 | `weather` 是 mock 数据源，不声明会让用户误以为是真实天气 |
| 6「不要输出工具调用原始 JSON」 | 模型有时会把 `{"name":...}` 当答案回给用户 |
| 8「信息不足要说明缺什么」 | 避免模型含糊作答；实测模型会因此主动反问用户（见 DEMO 场景 2） |

### P2. 历史压缩提示词

`com.miniagent.context.LlmSummarizer#SUMMARY_SYSTEM_PROMPT`：

```
你是一个对话历史压缩器，为 Agent 的长期记忆服务。
请把给定的历史对话压缩成一段中文摘要，要求：
1. 保留用户的目标、约束、偏好，以及已经确认的关键事实（城市、数字、待办事项、文档编号等）；
2. 保留尚未解决的问题，以及已经失败过的尝试（避免重复踩坑）；
3. 丢弃寒暄、重复内容、工具调用的原始 JSON 与推理过程；
4. 控制在 300 字以内，用「- 」开头的要点列表组织；
5. 只输出摘要正文，不要任何解释、前缀或 Markdown 代码块。
```

**设计要点**

- 第 1 条列出的「城市/数字/待办/文档编号」就是实测中真正被追问的内容（比如「我最早让你记的那条待办还在吗」）；
- 第 2 条的「失败尝试」是为了避免压缩后模型重复调用已失败的工具；
- 第 3 条明确丢弃「工具调用原始 JSON」与「推理过程」，因为它们占 token 但不提供新信息；
- 第 5 条防止模型输出「好的，以下是摘要：」这类前后缀污染长期记忆；
- 摘要请求是**增量**的：调用时会把旧摘要一起放进 user 消息，要求「在其基础上增量合并，不要丢失其中的事实」。

### P3. 工具描述（模型判断「何时调用」的唯一依据）

以 `todo` 为例：

```
管理当前会话的待办清单（按会话隔离，窗口之间互不影响）。
action=add 新增（需要 item），action=list 查看全部，action=done 标记完成（需要 id），
action=clear 清除已完成项。
当用户说「记一下 / 加个待办 / 提醒我 / 别忘了我还要…」时用 add；
当用户问「我还有什么没做 / 待办有哪些」时用 list。
```

**写法规范**（所有 5 个工具统一遵守）：

1. 说明**做什么**（能力边界），再说明**什么时候用**（触发语料）；
2. 明确写出**不适用场景**（如 `calculator` 写「不要用它处理非数学问题」、`weather` 写「不访问真实气象接口」）；
3. 参数描述写清格式与取值范围（模型据此生成参数）；
4. 中文触发词直接写进描述 —— 实测这比英文描述对中文用户的意图识别更准。

---

## 第二部分：开发过程的问题解决记录

> 开发方式：以 AI 编码代理完成实现，人工负责定架构、验收与关键决策。
> 下表是从 0 到 163 个单元测试全绿之间，**真实发生且被修复**的问题。

### 问题 1：`Instant` 让 trace 落盘全部失败

**现象**：运行 `--once` 时 stderr 刷出 15 条「写 trace 失败：JSON 序列化失败」，`logs/w1.jsonl` 始终为空文件。

**定位**：`TraceEvent` 含 `java.time.Instant` 字段，Jackson 默认不支持 Java 8 时间类型，需要 `jackson-datatype-jsr310`。

**决策与修复**：不为一个时间戳引入新依赖，改为在 `JsonlTraceSink#toJson` 里手工构造 `ObjectNode`，
把 `at` 写成 ISO-8601 字符串（`event.at().toString()`）。副作用是 JSONL 更易 `grep`/`jq`。

**沉淀**：`TraceTest#writesJsonLines` 逐行解析 JSON 并断言 `at` 含 `T`（回归测试，防止再次退化）。

---

### 问题 2：上下文估算漏算工具 Schema，压缩触发过晚（最有价值的一个坑）

**现象**：真实 API 实测发现，本地估算「上下文约 1082 tokens」，而 API 返回的 `prompt_tokens` 是 **2261**。预算 2000 的配置下，上下文早就超了却不触发压缩。

**定位**：`ContextManager.estimate()` 只统计了 `history + summary + workingMemory`，**完全没算 system prompt 与 5 个工具的 JSON Schema**，而这部分每轮固定占用千级 token。

**修复**（两处结构性改动）：

1. `AgentRuntime` 在装配时计算 `staticOverheadTokens = systemPrompt + 全部工具 Schema`，注入 `ContextManager`；
   压缩估算变为 `session.estimatedTokens() + staticOverheadTokens`；
2. 新增 `Session.lastPromptTokens`：每次 LLM 调用后记录 API 返回的**真实** `prompt_tokens`，
   压缩触发信号取 `max(本地估算, 真实值)` —— 有精确值时优先信精确值。

```java
public int compactionSignal(Session session) {
    return Math.max(estimate(session), session.lastPromptTokens());
}
```

**沉淀**：`ContextManagerTest#countsStaticOverheadInEstimate`（断言估算差值 = 固定开销）、
`#usesRealPromptTokensAsSignal`（断言真实值超预算必定触发压缩）、`AgentLoopTest#recordsPromptTokens`。

---

### 问题 3：CJK 标点被当成汉字，把记忆召回彻底搞坏

**现象**：`UtilTest` 断言 `tokenize("我，你。")` 应产出 `["我","你"]`，实际产出 `["我，","，你","你。"]`。

**定位**：`Texts.isCjk` 的字符范围包含了全角区块 `0xFF00–0xFFEF` 与 CJK 标点，于是 `，`、`。` 被当作汉字，
`我，你。` 被整体当成一个连续 CJK 串去切 bigram。而 bigram 分词正是 `MemoryRecaller` 打分的基础 ——
标点混入会直接稀释相关度、破坏召回。

**修复**：把 `isCjk` 收窄为「汉字 + 假名 + 谚文」，标点回归分隔符语义：

```java
return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
        || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF);
```

**沉淀**：`UtilTest#handlesSingleCharAndPunctuation`；`MemoryRecallerTest` 全部用例都依赖这个性质。

---

### 问题 4：`assistant(tool_calls)` 与 `tool` 消息可能被切散

**风险**：上下文窗口如果按「最近 K 条消息」滑动，很容易把 `assistant(tool_calls)` 和紧随其后的 `tool` 消息切开，
OpenAI 兼容接口会直接返回 400。

**修复**：窗口以 **user 消息为边界**切「完整轮次」（`ContextManager#verbatimWindowStart`），
保证任何切分点都是轮次起点；压缩同样只在 user 边界停下。

**沉淀**：`ContextManagerTest#neverSplitsToolCallPairs`（遍历上下文，断言每条 `tool` 消息前面必须是
`assistant(tool_calls)` 或另一条 `tool`）。

---

### 问题 5：测试替身不写 trace，导致「完整 trace」测试形同虚设

**现象**：`AgentLoopTest#recordsFullTrace` 断言 `llm_request/llm_response` 事件存在，却总是失败。

**定位**：`ScriptedLlmClient`（测试替身）只把请求记进自己的 list，**没有**像 `OpenAiCompatibleClient`
那样写 trace 事件。测试因此测不到真实运行时的事件流。

**修复**：让替身也写 `llm_request` / `llm_response` 事件（含消息数、工具数、token、finishReason），
与真实客户端行为对齐。测试替身应当「行为等价、实现正交」，而不是「能跑就行」。

**沉淀**：`AgentLoopTest#recordsFullTrace` 现在真实覆盖 8 类事件。

---

### 问题 6：mock 模型解析不出函数形式的算式

**现象**：`--mock --once "帮我算一下 sqrt(144)+pow(2,10)，再记个待办：写周报"` 只调用了 `todo`，没调用 `calculator`。

**定位**：早期正则 `[0-9][0-9\s+\-*/().^%]*[0-9)]` 要求以数字开头，`sqrt(144)+pow(2,10)` 只能匹配到 `144)`，
被判为「无运算符」而丢弃。

**修复**：改为「按允许字符集切出候选片段 → 要求含数字且（含运算符或函数调用）→ 取最长者」，
能正确提取 `sqrt(144)+pow(2,10)`。

**沉淀**：`MockLlmClient#detectExpression` 的行为由 `--mock` 端到端用例（`AssignmentScenarioTest`）覆盖。

---

### 问题 7：思维链回灌会污染推理并违反官方约定

**风险**：DeepSeek 明确要求不要把 `reasoning_content` 传回下一轮；且思维链只对本轮有价值。

**修复**：`Message` 拆出独立的 `reasoning` 字段，`OpenAiCompatibleClient#toWire` **只序列化 `content` 与 `toolCalls`**，
`reasoning` 仅用于 trace / UI / 压缩输入。

**沉淀**：`OpenAiCompatibleClientTest#neverSendsReasoningBack`（断言请求体里搜不到思维链文本）。

---

### 问题 8：trace 文件名可被 sessionId 造成目录穿越

**风险**：`sessionId` 来自外部，`new JsonlTraceSink(dir, "../../etc/passwd")` 若直接拼接可能写出目录外文件。

**修复**：文件名安全化 —— 白名单字符 + 消掉 `..`：

```java
sessionId.replaceAll("[^a-zA-Z0-9._-]", "_").replace("..", "_")
```

**沉淀**：`TraceTest#sanitizesSessionIdInFileNames`。

---

### 问题 9：真实测试断言被「千分位」打败

**现象**：`DeepSeekLiveIT#realModelUsesCalculator` 失败，但模型答案 `1234 × 5678 + √144 = 7,006,652 + 12 = **7,006,664**` 其实是**正确**的。

**定位**：断言写的是 `answer.contains("7006664")`，而真实模型输出了千分位分隔符。

**修复**：先去掉 `,`、空格与全角逗号再比较。教训：对 LLM 的自由文本做断言，必须先归一化格式，再比对语义。

---

### 问题 10：工作记忆自身也可能撑爆上下文

**风险**：`facts` 若无上限，工具每轮写一条事实，几十轮后工作记忆反而成为上下文的主要占用者。

**修复**：硬上限 + LRU —— `facts` 最多 20 条（淘汰最旧）、`todos` 最多 50 条。

**沉淀**：`SessionManagerTest#workingMemoryBehaviour`（写入 30 条后断言只剩最新 20 条）。

---

### 问题 11：压缩失败不能阻塞对话

**风险**：压缩本身要调 LLM，而 LLM 恰好在此时限流/超时 —— 如果让异常冒泡，用户这一轮就彻底失败了。

**修复**：三级降级 —— `LlmSummarizer` 失败 → `DeterministicSummarizer`（纯代码抽取用户诉求 + 工具结论）；
摘要为空 → 保留旧摘要继续。压缩动作永不抛出。

**沉淀**：`MemoryRecallerTest#summarizerFallsBackOnFailure` / `#summarizerFallsBackOnEmptyOutput`。

---

### 问题 12：工程环境相关（Windows + 沙箱）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `mvn -Dmaven.test.skip=true` 报 `Unknown lifecycle phase ".test.skip=true"` | PowerShell 把 `-D` 参数在点号处拆开 | 用引号包裹：`mvn "-DskipTests" compile` |
| Maven 报 `AccessDeniedException: C:\Maven\maven-repository\...` | 本地仓库在工作区之外，被文件沙箱拒绝写入 | 指向工作区内仓库：`-Dmaven.repo.local=.m2repo`（已 gitignore）；沙箱外正常开发无需该参数 |
| 控制台中文乱码 | Windows 控制台默认 GBK | `chcp 65001` + `-Dstdout.encoding=UTF-8` |
| 管道读取中文输入 | `System.in` 使用本地编码 | CLI 优先用 `System.console().reader()`，回退 UTF-8 |

---

## 第三部分：AI 工具的使用方式（可复现的做法）

### 3.1 用于「生成」的有效提示词模式

| 场景 | 提示词模式 | 效果 |
| --- | --- | --- |
| 生成接口骨架 | 「给出 X 的接口签名 + 契约（线程安全/异常/空值）+ 一段最小调用示例」 | 一次成型，减少来回 |
| 生成测试 | 「为 X 写测试，覆盖正常/边界/失败三类，失败路径必须断言错误码与错误文案」 | 直接得到可用的负向用例 |
| 排查问题 | 「这是现象 + 日志 + 相关代码，列出不超过 5 个可能根因，并给出区分它们的验证方法」 | 避免盲目改代码 |
| 评审设计 | 「以 Staff 工程师视角，指出这个 Agent 循环在并发/成本/超时/上下文四方面的风险」 | 提前发现「压缩阈值漏算工具 Schema」这类结构问题 |
| 生成文档 | 「基于以下真实签名生成接口文档，禁止臆造未实现的方法」 | 保证文档与代码一致 |

### 3.2 使用的纪律

1. **禁止臆造 API**：所有涉及库调用的代码必须实际编译通过；文档中的签名必须能在源码里找到。
2. **每个修复都要有回归测试**：上表 11 个技术问题，全部在 `src/test` 里有对应断言（这是「测试用例」要求的一部分）。
3. **真实 API 验证不可省略**：单元测试用替身证明「Runtime 逻辑正确」，真实 API 测试证明「协议与模型协作正确」——
   两者缺一不可（问题 9 就是只有真实 API 才能暴露的）。
4. **保留失败证据**：`docs/DEMO.md` 是真实运行的原始输出，不做美化。
5. **密钥不落库**：`DEEPSEEK_API_KEY` 只存在于 `.env.local`（gitignore）或环境变量，README/代码/提交中均无密钥。

---

## 第四部分：测试用例与需求的对应

完整的用例清单见 [`TESTING.md`](TESTING.md)。这里只强调三个「测试即设计」的例子：

| 测试 | 它固化的设计约束 |
| --- | --- |
| `AgentLoopTest#forcesWrapUpAtMaxSteps` | 收尾那一次 LLM 请求**必须不带 tools**（否则模型会继续请求工具，永远收不了尾） |
| `ContextManagerTest#keepsRecentTurnsAndMemoryAfterCompaction` | 摘要与工作记忆**每轮无条件注入**；滑出窗口的历史只允许存在于摘要里 |
| `AssignmentScenarioTest#sessionsDoNotLeakTodos` | 窗口 2 的回答里**不允许出现**窗口 1 的待办内容（隔离是行为约束，不是实现细节） |
