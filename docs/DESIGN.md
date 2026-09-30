# 系统设计

> 对应代码：`src/main/java/com/miniagent`。本文档说明**为什么这样设计**，接口细节见 [`API.md`](API.md)。

---

## 1. 设计目标与约束

| 目标 | 约束 |
| --- | --- |
| 从零实现 Agent Runtime | 不使用 LangGraph / OpenHands / OpenClaw / PI 等任何 Agent 框架 |
| 最小可用 | 只引入 `jackson-databind` 做 JSON 编解码；其余自研 |
| 可讲清楚 | 每一步决策都有 trace 事件，能回答「Agent 为什么这么做」 |
| 真实可用 | 对接真实 LLM API（DeepSeek / OpenAI 兼容），并能在真实网络故障下不崩 |
| 可测试 | 循环逻辑必须能用确定性测试替身精确断言 |

---

## 2. 分层与依赖方向

```
cli ──▶ core ──▶ context ──▶ session
                 │           tool
                 │           llm
                 └──────────▶ trace / error / util
```

依赖单向（无循环）：`llm` 只依赖 `trace/error/util`，`tool` 依赖 `session`（为了工具能读写会话级状态），
`context` 依赖 `session + llm`，`core` 组装全部。

---

## 3. 主循环（核心）

`AgentRuntime.run(userId, sessionId, input)`：

```
session = sessions.getOrCreate(userId, sessionId, title)   // 归属校验 + 幂等
session.append(USER(input))
session.withLock {                                          // 同一会话串行
  repeat up to maxSteps:
     contextManager.compactIfNeeded(session)                // 超预算则压缩旧历史
     context  = contextManager.build(session, prompt, tools) // 组装三段式上下文
     response = llm.chat(request(context, tools))            // 真实/替身 LLM
     parsed   = LlmOutputParser.parse(response)              // 思考 / 工具调用 / 最终答案
     if parsed.hasToolCalls():
         session.append(ASSISTANT(tool_calls))
         for call in parsed.toolCalls():                     // 重复调用保护
             result = invoker.invoke(call, ToolContext)
             session.append(TOOL(call.id, result.observation))
         continue                                            // ← 继续 loop
     if parsed.hasFinalAnswer():
         session.append(ASSISTANT(answer))
         return AgentResult(OK)                              // ← 返回结果
     else: 空输出自愈（最多一次）
  }
  forceWrapUp(): 禁用工具再问一次 → AgentResult(MAX_STEPS)
}
```

### 3.1 为什么循环控制权必须在 Runtime

如果让模型决定「还能调几次工具」，就无法工程化地保证成本与延迟上界。Runtime 保留四个硬边界：

1. 最终答案（正常终止）；
2. `maxSteps` 上限（强制收尾，且收尾请求禁用工具）；
3. LLM 重试耗尽（失败返回，不抛异常）；
4. 相同工具 + 相同参数重复调用超阈值（拦截并回灌提示）。

### 3.2 空输出自愈

真实 API 偶发返回空 `content`（尤其在高并发/长上下文时）。Runtime 的处理是：注入一条
`[系统] 你上一条回复为空…` 的 user 消息让模型重试一次；连续两次才判定 `EMPTY_MODEL_OUTPUT` 失败。
这样把偶发抖动和真实故障区分开。

---

## 4. 工具子系统

```
Tool 实现 ──▶ ToolRegistry ──specs()──▶ LlmRequest.tools ──▶ OpenAI tools[]
                   ▲                                              │
                   │                                      模型自主决策
             ToolInvoker.invoke ◀── ToolCall ◀───── LlmResponse.tool_calls
                   │
        ┌──────────┴──────────┐
   9 项防护              写 trace（tool_call / tool_result）
```

**防护链条**（任一环节失败都变成「可读的工具结果」而不是异常，`invoke` 契约上永不抛异常）：

| # | 防护 | 失败后回灌 |
| --- | --- | --- |
| 1 | 工具存在性 | 可用工具清单 |
| 2 | 参数是合法 JSON | 解析错误 + 原参数预览 |
| 3 | 参数是对象 | 实际类型 |
| 4 | Schema 校验 | 逐字段错误（缺哪个必填、类型该是什么） |
| 5 | 类型纠偏 | 静默修正 `"3"` → `3`，省一轮交互 |
| 6 | 超时控制 | 超时毫秒数（**只是请求中断**：不响应 interrupt 的工具仍会跑完，所以写工具不能靠超时兜底，要靠幂等键） |
| 7 | 异常折叠 | 异常类型 + 消息（**不含堆栈**，省 token） |
| 8 | 执行器已关闭 | `TOOL_EXECUTOR_CLOSED`（`close()` 之后调用不再抛 `RejectedExecutionException`） |
| 9 | 结果截断 | 截断长度提示 |

**为什么错误信息要写得像给人看**：它是给模型看的「修正指令」，含糊的错误（如「参数错误」）会让模型原地打转。

**配对不变式**：模型一次请求多个工具时，`AgentRuntime` 必须为**每一个** `tool_call_id` 回灌一条 `tool` 消息。
代码里用「已回灌计数 + 异常时补占位消息」来保证；缺少这道保障会让历史留下孤儿 `assistant(tool_calls)`，
之后该 session 每次请求都被 API 以 400 拒绝（永久毒化，无法自愈）。

---

## 5. Session 模型

```
SessionManager
 ├── Map<sessionId, Session>          // 常驻，可随时续聊
 └── Map<userId, List<sessionId>>     // 归属索引

Session
 ├── List<Message> history            // 对话原文（含 tool 配对）
 ├── WorkingMemory memory             // 结构化状态：事实 + 待办
 ├── String summary + int summarizedUpTo   // 长期记忆与压缩位点
 ├── int lastPromptTokens             // 真实用量（压缩触发信号）
 └── Tracer tracer                    // 会话级 trace
```

隔离强度：
- 数据隔离：不同 Session 不共享任何可变状态；
- 归属隔离：`sessionId` 属于他人时抛 `SessionAccessException`（用户 B 连 A 的窗口 id 都占不到）；
- 并发隔离：Session 内部单锁 ⇒ 同会话串行、跨会话并行。

---

## 6. 上下文与记忆（本项目的核心设计）

### 6.1 组装的四段结构

```
system : 角色与规则
system : 【长期记忆】压缩摘要          ← 每轮无条件注入
system : 【工作记忆】待办/事实          ← 每轮无条件注入
system : 【相关历史片段】Top-K 召回     ← 按当前问题召回
dialog : 最近 N 轮原文（按 user 边界切完整轮次）
```

### 6.2 为什么把「记忆」放在 system 而不是塞进对话流

1. **顺序约束**：OpenAI 要求 `tool` 消息紧跟对应的 `assistant(tool_calls)`，往对话流中间插东西极易触发 400；
2. **注意力**：system 位于最前，模型对规则的遵守度更高；
3. **可解释**：记忆块有固定标题（`SUMMARY_HEADER` / `WORKING_MEMORY_HEADER`），trace 与日志一眼可辨。

### 6.3 压缩的设计细节

| 细节 | 做法 | 原因 |
| --- | --- | --- |
| 触发信号 | `max(本地估算 + 固定开销, 真实 prompt_tokens)` | 本地估算漏算工具 Schema（可达 1000+ token），只看它会「悄悄超预算」 |
| 估算口径 | 只统计 `history[summarizedUpTo, size)` + 摘要 + 工作记忆 + 固定开销 | 压缩不删原始消息（留给召回），用全量历史会把已折进摘要的部分**重复计数**：实测表现为「压缩后估算反而变大」，且原始历史一旦超预算就每轮都触发压缩 |
| 信号失效 | 压缩成功后 `clearPromptTokensSignal()` | `lastPromptTokens` 是只增不减的高水位；不清掉的话，一次大 prompt 会让信号永远停在峰值（实测 signal 恒 9000 而真实估算只剩 538 → 每轮白付一次摘要调用） |
| 可观测性 | 压缩那次 LLM 调用写进**会话级** tracer（早期传 `Tracer.noop()`，导致 32% 的 API 调用在 trace 里查不到）；事件带 `shrank` 标记 | 「一次问答打了几次 API、慢在哪、花了多少 token」必须能对账 |
| 切分边界 | 以 user 消息为界，切完整轮次 | 保住 `assistant(tool_calls)` + `tool` 配对 |
| 摘要方式 | 增量合并（提示词里带旧摘要） | 避免多轮压缩逐步稀释早期事实 |
| 摘要内容 | 目标/约束/已确认事实/未决问题/失败尝试 | 这几类是后续追问真正需要的 |
| 失败降级 | `DeterministicSummarizer`（抽取用户诉求 + 工具结论） | 压缩永不阻塞对话 |
| 压缩位点 | `summarizedUpTo`，只向前推进 | 幂等、可增量 |
| 召回池 | `history[0, 最近窗口起点)` | 只召回已滑出窗口的内容，避免与原文重复 |

> **配对不变式的第二道保障**：切片起点落在 user 边界只保证「不会主动切散」配对；
> 写入侧还必须保证「不会留下孤儿」——工具执行段异常时，`AgentRuntime` 会为每个没有回灌结果的
> `tool_call_id` 补一条占位 `tool` 消息。否则历史里会留下一条「有 `tool_calls`、没有 `tool` 回应」的
> assistant 消息，之后该 session 的每次请求都会被 API 以 400 拒绝，且无法自愈。
> 回归用例：`AgentLoopTest#repairsOrphanToolCalls`、`#toolDispatchFailureDoesNotPoisonSession`。

### 6.4 召回打分

```
score = Σ(关键词重叠 × 词长权重) × 角色权重(user 1.6 / tool 1.2 / assistant 1.0)
        × 时间衰减(0.6 ~ 1.0) ÷ 长度惩罚(log 归一)
```

只用关键词（CJK bigram + 英文词）而不引向量库，是为了**可解释与可测试**：
`MemoryRecallerTest` 能直接断言「问杭州时召回杭州、不召回咖啡」。生产替换为向量检索只需换掉这一个类。

---

## 7. LLM 客户端设计

- **单一职责**：只做「序列化 → HTTP（含重试）→ 反序列化 → trace」，不含任何 Agent 逻辑。
- **重试策略**：网络异常 / 429 / 5xx 重试（指数退避 + 抖动，429 加倍）；401/400 不重试（重试没有意义）。
- **思维链处理**：`reasoning_content` 解析后存入 `Message.reasoning` 供 trace/UI 使用，**绝不回灌**请求体。
- **两个替身**：
  - `ScriptedLlmClient`：按剧本返回，用于精确断言循环行为（也写 trace，与真实客户端对齐）；
  - `MockLlmClient`：关键词规则假模型，让没有 Key 的人也能跑通全链路（`--mock`）。

---

## 8. 可观测性设计

- **一个 session 一个 Tracer**：事件天然按会话聚合，JSONL 文件名即 sessionId。
- **15 类事件**覆盖：输入 → 上下文（含召回/压缩统计）→ LLM（含 token/耗时/重试）→ 工具（含 ok/错误码/耗时/截断）→ 收尾/错误。
- **失败隔离**：sink 抛异常被吞掉并只打 warning；JSONL 写盘失败不影响对话。
- **两种消费方式**：内存快照（CLI `/trace`、测试断言）+ JSONL 落盘（`jq` 分析）。

---

## 9. 关键权衡记录（Trade-off）

| 选择 | 放弃的方案 | 理由 |
| --- | --- | --- |
| 手写 JSON Schema 子集校验 | 引入 `networknt/json-schema-validator` | 工具 Schema 形状有限；自研可定制「给模型看的错误措辞」 |
| 手写递归下降表达式求值 | `ScriptEngine`/`eval` | 后者等于把任意代码执行权交给模型输出 |
| 关键词召回 | 向量检索 | 零依赖、可测试、可解释；接口形状已为替换预留 |
| 内存 Session 存储 | Redis/DB | 面试题规模够用；方法签名即存储契约 |
| 同一 session 串行 | 完全并发 | 会话状态一致性优先；跨会话仍并行 |
| 启发式 token 估算 + 真实用量校准 | 引入 tokenizer | 首次请求前只能估算；之后以 API 真实值为准 |
| 工具串行执行 | 并行执行 | 首版优先可解释与顺序确定；并行化留作扩展 |

---

## 10. 数据流全景（含记忆生命周期）

```
              ┌──────────────── 写入 ────────────────┐
用户输入 ──▶ history ──▶ [超预算] 压缩 ──▶ summary ──┤
              │                                      │
工具执行 ──▶ tool result ──▶ 关键结论 ──▶ workingMemory
              │                                      │
              └────────────── 召回（每次 LLM 调用前）─┘
                              │
                    ┌─────────┴─────────┐
                    │  ContextManager   │
                    │  build():         │
                    │   摘要 + 工作记忆  │
                    │   + Top-K 召回     │
                    │   + 最近 N 轮原文  │
                    └─────────┬─────────┘
                              ▼
                        LlmRequest → 模型
```
