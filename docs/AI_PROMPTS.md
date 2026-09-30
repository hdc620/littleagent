# AI Prompt 与问题解决记录

> 本文档记录两件事：
> ① **产品内的 Prompt**（Agent 系统提示词、压缩提示词、工具描述）及其设计意图；
> ② **开发过程的问题解决记录**（真实踩坑 → 定位 → 修复 → 沉淀成测试），以及 AI 工具在其中的使用方式。

---

## 第一部分：产品内 Prompt 设计与迭代

### P1. Agent 系统提示词

`com.littleagent.core.AgentConfig#DEFAULT_SYSTEM_PROMPT`（节选）：

```
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

`com.littleagent.context.LlmSummarizer#SUMMARY_SYSTEM_PROMPT`：

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
> 下表是从 0 到 216 个单元测试全绿之间，**真实发生且被修复**的问题。
>
> **问题 1–11** 来自「边写边撞上」：跑真实 API / 跑测试时暴露出来的问题。
> **问题 13–21** 来自一次**定向的代码审计**：不再依赖「撞上」，而是逐文件读代码 + 用
> `jshell` 直接调 `target/classes` 复现（例如「空响应计数是累计还是连续」「中文回答会不会被当思维链删掉」），
> 再补回归测试。这属于「AI 写完的代码，人必须自己过一遍」的部分：
> 其中至少两条（空响应计数、中文答案被删空）在人工使用中是**偶发且难复现**的，靠自测几乎撞不上。
> 每个修复都配了回归用例（测试名见各条的「沉淀」）。

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
> 补充（审计时发现）：这条用例早期是**空转**的 —— 当时把工具调用放在第一轮且 `keepRecentTurns=1`，
> 组装出来的窗口里只有 `[user, assistant]`，**根本没有 TOOL 消息**，配对断言那个循环从未执行。
> 现在改为把工具调用放在最后一轮，并断言「窗口内确实含 1 条 TOOL」+「`toolCallId` 与 assistant 的 calls 对得上」。

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

### 问题 13：空响应保护统计的是「累计」而不是「连续」（一行之差，丢掉了整轮结果）

**现象**：对代码做逐行审计时发现 `emptyResponses` 声明在 `for` 循环**外面**，而且任何成功的一步之后
都**没有归零**。用剧本化替身复现：`[空响应, 调 calculator(1+1), 空响应]` →
`status=FAILED, code=EMPTY_MODEL_OUTPUT, toolCallCount=1` —— 第 2 步的工具**真的执行成功了**，
整轮却因为第 3 步的空响应被判失败，那个工具结果永远没讲给用户听。
而类注释、`DESIGN.md`、`API.md` 三处写的都是「**连续**两次」。

**根因**：计数器语义是「连续次数」，实现却写成了「累计次数」。这是典型的**注释与代码不一致**，
而且因为两条空响应之间恰好夹着成功步骤的概率不低，真实场景下会偶发地把好结果丢掉。

**修复**：任何有产出的一步（执行工具成功 / 拿到最终答案）之后把计数器归零；同时把错误文案从
「模型连续返回空内容」改成「模型连续两次返回空内容」，并让 trace 里的事件说明写「连续第 N 次」。

**沉淀**：`AgentLoopTest#emptyResponsesMustBeConsecutive`（断言「空→工具成功→空→正常回答」必须 `OK`
且工具产出不丢），与之配对的 `#failsAfterTwoEmptyResponses` 保证「真连续两次」仍然失败。

---

### 问题 14：中文回答以「分析：」开头会被当成思维链删掉（比误判工具调用更严重）

**现象**：审计时用 `jshell` 直接调解析器复现：输入 `分析：接口超时是因为连接池太小。`（一句完全正常的中文回答）
得到 `mode=EMPTY, finalAnswer=null, thought=接口超时是因为连接池太小。` —— 回答被**整行删空**，
于是 Runtime 走「空响应自愈」分支、注入「你上一条回复为空」，连续两次就整轮失败。**用户的问题被彻底吞掉。**

**根因**：`stripThoughtMarkers` 无条件剥离匹配 `^(Thought|思考|推理|分析):` 的整行，而且这个函数在
**最终答案路径**上也会被调用。抽出思维链（用于 trace）和从答案里删掉思维链是两件事，被合成了一个函数。
中文模型极爱用「分析：」「结论：」开头，所以这是最容易在真实使用中触发的假阳性。

**修复**：只剥 `<thought>…</thought>` 标签，不再剥裸的中文行。ReAct 的 `Thought:` 行本来就不需要在这里处理——
只要正文里出现 `Action:`，解析器已经走工具调用分支了。`thought` 字段仍由 `extractThought` 抽取（只用于 trace/UI，不回灌）。

**沉淀**：`LlmOutputParserTest#keepsChineseAnswerStartingWithThoughtLikeMarker`（三种中文标记逐个断言答案原样返回）、
`#stillStripsThoughtTagFromAnswer`（标签路径不退化）。

---

### 问题 15：工具执行段没有异常隔离 → 一条异常会**永久毒化**整个会话

**现象**：`executor.submit` 在 `ToolInvoker.close()` 之后会抛 `RejectedExecutionException`。
因为工具执行段外面没有 `try/catch`，这个异常直接穿过 `run()` 抛给调用方（违反 README/API 文档里写的「永不抛异常」）。
**但真正严重的是它留下的状态**：异常发生时最后一条 `assistant(tool_calls)` 已经写进历史，
配对的 `tool` 消息还没写 —— 之后这个 session 的**每一次**请求都会被 API 以
`400 … must be followed by tool messages responding to each tool_call_id` 拒绝，而且代码里没有任何自愈路径。

**修复**（分两层，都在写入侧）：
1. `ToolInvoker.invoke` 把 `submit` 包进 try，`RejectedExecutionException` 折叠成 `TOOL_EXECUTOR_CLOSED` 结果
   —— 工具层故障就该是「可回灌的工具结果」，让模型知道并换路；契约因此真的成立；
2. `AgentRuntime` 的工具执行段加 `try/catch`，异常时用 `appendUnansweredToolPlaceholders` 为**每个**没有回灌结果的
   `tool_call_id` 补一条占位 `tool` 消息，然后返回 `TOOL_DISPATCH_ERROR`；
3. `run()` 最外层再兜一层，把锁/executor 这类边界异常收敛成 `RUNTIME_ERROR`。

**关键认知**：「窗口切片不切断配对」只是第一层保证，**写入侧也必须保证不产生孤儿**——
配对是协议级不变量，不能只在读取路径上维护。

**沉淀**：`AgentLoopTest#toolDispatchFailureDoesNotPoisonSession`（关掉 runtime 后仍不抛异常、工具故障收敛、
之后同一 session 仍能正常对话）、`#repairsOrphanToolCalls`（直接断言占位消息补齐且 `toolCallId` 对得上）、
`ToolInvokerTest#invokeAfterCloseReturnsErrorResult`。

---

### 问题 16：压缩信号里混进了「已经压缩掉的历史」→ 压缩后估算反而变大

**现象**：用真实 API 跑 10 轮对话，`context_compressed` 事件显示 6 次压缩里有 **4 次 `after > before`**
（1615→1694、1927→1962、2438→2549、3005→3035）——「压缩」让上下文估算变大了。

**根因**：压缩**不删除** history 里的原始消息（保留给召回用），只把 `summarizedUpTo` 往前推；
但 `Session.estimatedTokens()` 累加的是**整条** history，`ContextManager.estimate()` 又在它基础上加固定开销。
于是已被折进摘要、**永远不会再进请求**的消息被重复计了一遍。后果不只是数字难看：
只要原始历史超过预算，`keepFrom` 每轮都会前进一格 → **每一轮都多付一次 LLM 摘要调用**
（实测 10 轮触发 6 次，每次 0.6–1.6 秒）。

**修复**：新增 `Session.estimatedTokensFrom(int)`，压缩阈值改为从 `summarizedUpTo` 开始统计；
`context_compressed` 事件增加 `shrank` 标记，让「这次压缩有没有真的变小」一眼可见。

**沉淀**：`ContextManagerTest#estimateExcludesCompressedHistory`（断言压缩后估算严格变小 + `shrank=true`）。
修复后用真实 API 复跑：6 次压缩里 5 次 `shrank=true`。

---

### 问题 17：`lastPromptTokens` 是只增不减的高水位 → 每轮都白压缩一次

**现象**：脚本化复现：设 `lastPromptTokens=9000`、预算 6000、真实估算只有 538 →
`compactIfNeeded` 在**每一轮**都返回 true（signal 恒为 9000），历史被逐轮折叠进有损摘要，
而实际上下文只有预算的 1/11。

**根因**：`Session.lastPromptTokens(int)` 只在 `tokens > 0` 时赋值，**从不衰减、从不清零**；
而压缩路径自己用的是 `Tracer.noop()` 且丢弃 usage，所以它**永远刷不新高水位**。

**修复**：压缩成功后调用 `Session.clearPromptTokensSignal()` 让过期的真实用量失效，
由下一次 LLM 调用写入新值（自校正）。

**沉淀**：`ContextManagerTest#clearsStalePromptTokensAfterCompaction`（断言压缩后信号归零、
且上下文远低于预算时不再压缩）。

---

### 问题 18：压缩调用的 LLM 用量在 trace 里完全不可见（32% 的 API 调用查不到）

**现象**：逐条统计真实运行产生的 `logs/<session>.jsonl`：13 次被记录的 LLM 请求，
但同一次运行里实际发生了 **19 次** API 调用（6 次压缩），**32% 的调用在 trace 里查不到**；
每次压缩 613–1633ms 落在用户等待里却没有归属。

**根因**：`LlmSummarizer` 固定传 `Tracer.noop()`，既不写 trace 事件，也丢弃了 `response.usage()`。
「一个 session 一个 tracer、15 类事件全链路可重放」这条承诺因此不成立——
面试官只要把 trace 里的耗时加起来对不上用户实际等的时间，就能问穿。

**修复**：`Summarizer.summarize(...)` 增加 `Tracer` 参数，`ContextManager.compact` 传入 `session.tracer()`，
压缩调用与主循环调用在 trace 里同等可见（含 prompt/completion tokens）。
诚实保留一条口径说明：`AgentResult.totalUsage()` 仍只统计主循环用量。

**沉淀**：`MemoryRecallerTest#summarizerWritesTraceWithSessionTracer` / `#summarizerToleratesNullTracer`；
修复后真实 API 复跑同一次 demo，`llm_request` 从 13 条变成 20 条。

---

### 问题 19：文本兜底解析不校验工具名 → 普通作答里的 JSON 被执行成工具调用

**现象**：`jshell` 复现：`{"name": "张三", "age": 30}` 被解析成「调用工具 张三」。
模型只要在答案里贴一段带 `name` 字段的 JSON（接口示例、人名记录……），用户拿到的就不是答案，
而是一段 `UNKNOWN_TOOL` 失败回灌。而 `API.md` 当时写的是「只有同时含名称字段与参数类字段才认定」——
文档比实现更强。

**修复**：`LlmOutputParser.parse(response, Set<String> knownTools)`——文本兜底**只接受注册表里存在的工具名**，
名字不匹配就回落成普通正文。两条有意的边界：**原生 `tool_calls` 不做名字过滤**（那是模型通过 API 正式发起的调用，
名字错也应该走 `UNKNOWN_TOOL` 回灌去纠正）；`knownTools == null` 表示不校验（单测/离线回放）。

**沉淀**：`LlmOutputParserTest#textFallbackOnlyAcceptsRegisteredToolNames`、
`#nativeToolCallsAreNotFilteredByName`、`#withoutKnownToolsNoFiltering`。

---

### 问题 20：「向上查找 4 层」差一层，深层 CWD 下知识库静默失效

**现象**：README 声称「`.env.local` 与 `docs/knowledge` 都会从 CWD 向上查找最多 4 层，只要 CWD 在项目树内即可定位」。
实际把 CWD 设成 `src/main/java/com/miniagent`（距根 4 层）再跑，`search` 返回
「知识库为空（docs/knowledge 下没有文档）」——**恰好就是 README 说"已修复"的那个失败模式**。
根因：`for (depth = 0; depth < 4; depth++)` 里的 4 是**含 CWD 在内**的目录数，实际只能上溯 3 层。

**修复**：新增 `com.littleagent.util.ProjectPaths`，改为**锚点式**查找 —— 一路向上直到看见
`pom.xml` / `.git` / `build.gradle` 这类「这里是项目根」的标志为止（安全上界 12 层），
`.env` 与知识库目录共用同一套逻辑。锚点是自解释的，不依赖某个人数出来的层数。

**沉淀**：`ProjectPathsTest`（距根 4 层以上仍能定位、越过项目根就不再向上、绝对路径透传、目录顺序从近到远、无锚点时有上界）。

---

### 问题 21：`calculator` 把小数值打印成 0、把近整数四舍五入成整数

**现象**：真实 API 实测 `1/10000000000` 返回 `= 0`（非零结果变成 0），
`3-0.0000000001` 会打印成 `3`。根因是判「是不是整数」用了绝对阈值 `|v - rint(v)| < 1e-9`，
与量级无关。有意思的是**模型自己发现了这个问题**并在答案里补了一句
「按精度取整显示为 0；实际值为 1×10⁻¹⁰」——这既坐实了 bug，也是个「模型会用工具输出自我纠错」的好例子。

**修复**：改为精确比较 `value == Math.rint(value)`，其余交给 `BigDecimal.valueOf(...).toPlainString()`
十进制展开；并挡住 NaN/Infinity（`BigDecimal.valueOf` 会抛异常）。
顺带在文档里写清取舍：求值仍是 IEEE 754 double（`0.1+0.2` 就是 `0.30000000000000004`），
要精确十进制得全程 BigDecimal，但那样无法直接支持 `sin/log/pow` 这类超越函数。

**沉淀**：`CalculatorToolTest#formatsSmallAndNearIntegerValuesExactly`、`#toolReportsSmallValueNotZero`。

---

### 问题 22：工程环境相关（Windows + 沙箱）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `mvn -Dmaven.test.skip=true` 报 `Unknown lifecycle phase ".test.skip=true"` | PowerShell 把 `-D` 参数在点号处拆开 | 用引号包裹：`mvn "-DskipTests" compile` |
| Maven 报 `AccessDeniedException: C:\Maven\maven-repository\...` | 本地仓库在工作区之外，被文件沙箱拒绝写入；且 `-Dmaven.repo.local=...` 会被 settings.xml 里的 `localRepository` 覆盖 | 用项目内 settings 显式指定：`mvn -s .mvn-settings.xml`（`.mvn-settings.xml` 与 `.m2repo/` 都只用于受限环境，不提交） |
| 控制台中文乱码 | Windows 控制台默认 GBK | `chcp 65001` + `-Dstdout.encoding=UTF-8` |
| 管道读取中文输入 | `System.in` 使用本地编码 | CLI 优先用 `System.console()`（JDK 22+ 还要判 `isTerminal()`，否则重定向时也会非 null），回退 UTF-8 |

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
2. **每个修复都要有回归测试**：上表 20 个技术问题，全部在 `src/test` 里有对应断言（这是「测试用例」要求的一部分）。
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
