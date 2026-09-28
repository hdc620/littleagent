# mini-agent —— 从零实现的最小可用 Agent（Java）

> 一个**不依赖任何 Agent 框架**的最小可用 Agent Runtime：自研 ReAct 主循环、工具注册与 Schema 驱动决策、
> LLM 输出解析、多 session 隔离、上下文旅程压缩与记忆召回、异常处理与全链路 trace。
> 使用**真实 LLM API**（DeepSeek / 任意 OpenAI 兼容网关）。

[![Java](https://img.shields.io/badge/Java-17+-blue)]() [![Tests](https://img.shields.io/badge/tests-163%20unit%20%2B%205%20live-green)]() [![Deps](https://img.shields.io/badge/runtime%20deps-jackson%20only-orange)]()

---

## 目录

- [1. 这是什么](#1-这是什么)
- [2. 5 分钟跑起来](#2-5-分钟跑起来)
- [3. 需求对照表](#3-需求对照表)
- [4. 系统设计](#4-系统设计)
- [5. 工具机制](#5-工具机制)
- [6. Session 管理与多窗口隔离](#6-session-管理与多窗口隔离)
- [7. Context 与 Memory（召回时机与放置方式）](#7-context-与-memory召回时机与放置方式)
- [8. 异常处理与可观测性](#8-异常处理与可观测性)
- [9. 测试](#9-测试)
- [10. 真实 API 运行记录](#10-真实-api-运行记录)
- [11. 目录结构](#11-目录结构)
- [12. 提交到 GitHub](#12-提交到-github)
- [13. 已知限制](#13-已知限制)
- [14. 文档索引](#14-文档索引)

---

## 1. 这是什么

一个**可以讲清楚每一步为什么这么做**的 Agent 实现。全部核心逻辑（循环、工具调度、上下文组装、压缩、记忆召回）
都是本项目自己写的，只用了 JDK + Jackson（JSON 编解码）。

| 能力 | 实现 |
| --- | --- |
| Agent 主循环 | `com.miniagent.core.AgentRuntime`（step1 输入 → step2 决策 → step3 工具 → step4 收尾/继续） |
| 工具注册机制 | `Tool` SPI + `ToolRegistry`（名称/描述/JSON Schema），LLM 基于 Schema 自主决策 |
| 输出解析 | `LlmOutputParser`：原生 `tool_calls` + 三类文本兜底（`<tool_call>` / JSON 代码块 / ReAct），抽取思考过程 |
| 3+ 工具 | `calculator`（自研表达式求值器）、`search`（mock 检索）、`read_docs`、`todo`（有状态）、`weather`（mock 数据源） |
| session 管理 | `Session` / `SessionManager`：一窗口一 session，历史+工作记忆+trace 全隔离，可随时接着聊 |
| context 管理 | `ContextManager`：三段式组装、按完整轮次切窗口、超预算增量压缩、真实 token 用量触发 |
| memory | 工作记忆（结构化，每轮注入）+ 摘要（长期）+ 关键词召回（情节记忆） |
| 异常处理 | `AgentException` 体系 + 工具调用 8 项防护 + LLM 重试退避 + 循环边界，**永不把异常抛给调用方** |
| trace/日志 | `Tracer`：15 类事件、JSONL 落盘、CLI 可回放时间线 |

真实运行证据（DeepSeek `deepseek-chat`，2026-09-28 实测）见 [第 10 节](#10-真实-api-运行记录) 与 [`docs/DEMO.md`](docs/DEMO.md)。

---

## 2. 5 分钟跑起来

### 2.1 环境要求

- JDK 17+（开发验证于 JDK 23）
- Maven 3.8+
- 一个 DeepSeek API Key（[申请地址](https://platform.deepseek.com/)）；**没有 Key 也能用 `--mock` 跑通全流程**

### 2.2 配置 Key

```bash
cp .env.example .env.local      # Windows: copy .env.example .env.local
# 编辑 .env.local，填入 DEEPSEEK_API_KEY=sk-xxxx
```

`.env.local` 已在 `.gitignore` 中，不会进版本库。也可以直接用环境变量 `DEEPSEEK_API_KEY`（优先级更高）。

### 2.3 编译与测试

```bash
mvn clean package                 # 编译 + 跑 163 个单元测试 + 打可执行 jar → target/mini-agent.jar
mvn verify                        # 额外跑 5 个真实 API 集成测试（无 Key 时自动跳过）
mvn "-Dtest=AgentLoopTest" test    # 只跑某个测试类
```

### 2.4 运行

```bash
# ① 交互式多窗口 REPL（真实 API）
java -jar target/mini-agent.jar
A@w1> 帮我查一下北京今天的天气，然后记个待办：给张总发周报
A@w1> /new 周报窗口          # 新开窗口 w2
A@w2> 我要写本周周报，先查一下知识库里的周报模板，然后记个待办：周五前提交周报
A@w2> /use 1                 # 切回窗口 1
A@w1> 我刚才让你记的待办是什么？

# ② 一键跑完题目里的 6 个场景（推荐给评审）
java -jar target/mini-agent.jar --demo

# ③ 没有 API Key：离线假模型跑通全链路（不产生任何网络请求）
java -jar target/mini-agent.jar --mock --demo

# ④ 单轮问答 + 查看 trace
java -jar target/mini-agent.jar --once "帮我算一下 1234*5678+sqrt(144)，再记个待办：对账"
java -jar target/mini-agent.jar --trace --once "北京天气怎么样"

# 中文乱码时（Windows 控制台）：先 chcp 65001，或加 -Dstdout.encoding=UTF-8
java "-Dstdout.encoding=UTF-8" -jar target/mini-agent.jar --demo
```

REPL 命令：`/new`（新窗口）、`/use`（切换窗口）、`/sessions`（列出窗口）、`/history`、`/memory`（看工作记忆与摘要）、
`/trace`、`/compact`（手动压缩）、`/verbose`、`/exit`。

### 2.5 在 IntelliJ IDEA 中运行

**可以直接用 IDEA 运行**，标准 Maven 工程，无需额外配置。

1. **导入**：`File → Open` → 选中项目根目录的 `pom.xml` → `Open as Project`（IDEA 自动 Maven 导入）。
2. **确认 SDK**：`File → Project Structure → Project`，SDK 选 **JDK 17 或更高**（`pom.xml` 里编译目标是 `release 17`），
   Language level 选 17。建议同时确认 `Settings → Build → Build Tools → Maven → Runner` 的 JRE 与项目一致。
3. **运行入口**：右键 `src/main/java/com/miniagent/Main.java` → `Run 'Main.main()'`。
   不传参数即进入**交互式多窗口 REPL**（IDEA 控制台可以直接输入中文）。
4. **已内置 5 个 Run Configuration**（`.idea/runConfigurations/`，导入后自动出现在运行下拉框里）：

   | 运行配置 | 效果 | 需要 API Key |
   | --- | --- | --- |
   | `mini-agent · REPL（真实 API，多窗口）` | 交互式多窗口对话 | 是 |
   | `mini-agent · 演示脚本（真实 API，6 个场景）` | `--demo`，一键跑完题目场景 | 是 |
   | `mini-agent · 演示脚本（离线 mock，无需 API Key）` | `--mock --demo`，零网络请求跑通全链路 | 否 |
   | `mini-agent · 单轮问答 + trace` | `--trace --once "..."` | 是 |
   | `mini-agent · 真实 API 集成测试（需 Key）` | 直接跑 `DeepSeekLiveIT` 5 个用例 | 是 |

   > 若下拉框提示 `Module not specified`，手动选一下 `mini-agent` 模块即可（IDEA 按 artifactId 生成模块名）。

5. **跑测试**：右键 `src/test/java` → `Run 'All Tests'`（163 个单元测试，离线，几秒）。
   `DeepSeekLiveIT` 也可以直接右键运行——IDEA 不区分 failsafe，`@Test` 就会执行；没有 Key 时它会自动 skip。

6. **三个常见坑**（前两个已修复，列出来便于排查）：

   | 现象 | 原因 | 处理 |
   | --- | --- | --- |
   | 启动即报「未检测到 DEEPSEEK_API_KEY」 | Working directory 不在项目内，`.env.local` 找不到 | Run Configuration 的 `Working directory` 设为 `$PROJECT_DIR$`（内置配置已设好）；`.env.local` 与 `docs/knowledge` 都会从 CWD 向上查找最多 4 层 |
   | `search` 一直返回「知识库为空」 | 同上，工作目录不在项目内 | 同上；只要 CWD 在项目目录树内即可自动定位，已由 `DefaultToolsTest` 覆盖 |
   | 控制台中文乱码（少数老版本） | 控制台编码非 UTF-8 | `Help → Edit Custom VM Options` 加 `-Dfile.encoding=UTF-8`，或 `Settings → Editor → File Encodings` 全部设为 UTF-8 |

7. **可以不用的东西**：项目根目录的 `.m2repo/`（约 25 MB）是我在受限沙箱里临时使用的隔离 Maven 仓库，
   正常用 IDEA/本机 Maven 时**可以直接删掉**，它已被 `.gitignore` 忽略，也没有进版本库。

---

## 3. 需求对照表

| 题目要求 | 实现位置 | 验证方式 |
| --- | --- | --- |
| 从零实现，不依赖 Agent 框架 | 全部自研；依赖仅 `jackson-databind` + JUnit | `pom.xml` 依赖清单 |
| step1 接收用户输入 | `AgentRuntime#run` | `AgentLoopTest#directAnswerWithoutTools` |
| step2 判断直接回复还是调用工具 | `ContextManager#build` → `LlmClient#chat` → `LlmOutputParser#parse` | `LlmOutputParserTest`（11 例） |
| step3 调用工具 | `ToolInvoker#invoke` | `ToolInvokerTest`（10 例） |
| step4 判断继续 loop 还是返回 | `AgentRuntime` 循环尾部 | `AgentLoopTest#executesToolAndFeedsResultBack` |
| ≥3 个工具（计算器/搜索/天气/待办…） | 5 个：`calculator` `search` `read_docs` `todo` `weather` | `BuiltinToolsTest`（13 例）+ `CalculatorToolTest`（29 例） |
| 工具注册机制（名称/描述/参数 Schema） | `Tool` + `ToolRegistry` + `SchemaValidator` | `ToolRegistryTest`、`SchemaValidatorTest` |
| LLM 基于 Schema 自主决策 | `ToolRegistry#specs` 注入 OpenAI `tools` 字段 | 真实 API 实测：模型自主选择 weather+todo（见第 10 节） |
| 解析思考过程 / 工具调用 / 最终答案 | `LlmOutputParser` + `ParsedOutput.Mode` | `LlmOutputParserTest` |
| 多 session 隔离（窗口 1 / 窗口 2） | `Session` + `SessionManager`（含跨用户越权拒绝） | `SessionManagerTest`、`AssignmentScenarioTest#sessionsDoNotLeakTodos` |
| 最大轮次限制 | `AgentConfig.maxSteps` + 强制收尾 | `AgentLoopTest#forcesWrapUpAtMaxSteps` |
| 用户持续对话记住之前状态 | 工作记忆 + 摘要，每轮注入 | `AssignmentScenarioTest#followUpWithoutToolsRemembersTodo` |
| 支持纯对话追问 | 同上（模型不调工具直接回答） | `AssignmentScenarioTest#followUpWithoutToolsRemembersTodo` |
| 支持带工具的追问 | 上下文 + 工具组合 | `AssignmentScenarioTest#followUpWithToolUsesContext` |
| 判断哪些信息塞入 context | `ContextManager` 三段式 + 历史瘦身策略 | `ContextManagerTest#trimsHistoricalAssistantContent` |
| 过长要压缩 | `ContextManager#compactIfNeeded` + `LlmSummarizer`（带确定性降级） | `ContextManagerTest#compactsWhenOverBudget` |
| 异常处理 | 异常体系 + 8 项工具防护 + LLM 重试 | `ToolInvokerTest`、`AgentLoopTest#llmFailureBecomesResult` |
| 工具 trace / 执行日志 | `Tracer` + `JsonlTraceSink` | `TraceTest`（8 例） |
| 真实 LLM API | `OpenAiCompatibleClient` → DeepSeek | `DeepSeekLiveIT`（5 例）+ 第 10 节实测记录 |
| 测试用例 | 163 单元测试 + 5 真实 API 集成测试 | `mvn verify` |

---

## 4. 系统设计

### 4.1 分层

```
┌─────────────────────────────────────────────────────────────────────┐
│ cli / Main        Repl（多窗口）、DemoScenarios、参数解析            │
├─────────────────────────────────────────────────────────────────────┤
│ core              AgentRuntime（主循环）· AgentConfig · AgentResult  │
├───────────────┬──────────────────┬───────────────┬──────────────────┤
│ context       │ session          │ tool          │ llm              │
│ ContextManager│ Session          │ ToolRegistry  │ LlmClient        │
│ Summarizer    │ SessionManager   │ ToolInvoker   │ OpenAiCompatible │
│ MemoryRecaller│ WorkingMemory    │ SchemaValidator│ LlmOutputParser │
│               │                  │ 5 个内置工具   │ Mock/Scripted    │
├───────────────┴──────────────────┴───────────────┴──────────────────┤
│ trace（Tracer/TraceEvent/JSONL）· error（异常体系）· util（JSON/文本）│
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 一次问答的完整时序

```
用户输入
  │
  ├─ SessionManager.getOrCreate(userId, sessionId) ── 归属校验 ─┐
  │                                                          │
  ▼                                            （同一 session 串行加锁）
┌─ AgentRuntime.run ────────────────────────────────────────────────────┐
│ 1. session.append(USER)                          → trace user_input   │
│                                                                       │
│ 2. while step <= maxSteps:                                            │
│    a. ContextManager.compactIfNeeded(session)    → trace compressed   │
│    b. ContextManager.build(...)                  → trace context_built│
│       [system 规则][system 摘要][system 工作记忆][system 召回片段]     │
│       + 最近 N 轮原文（按 user 边界切完整轮次）                        │
│    c. LlmClient.chat(request)                    → trace llm_*        │
│    d. LlmOutputParser.parse(response) → thought / toolCalls / answer  │
│                                                                       │
│    e. 若 toolCalls 非空:                                              │
│         session.append(ASSISTANT(tool_calls))                         │
│         for each call: ToolInvoker.invoke → trace tool_call/result    │
│         session.append(TOOL(result))                                  │
│         continue                        ← step 4：继续 loop            │
│       否则:                                                            │
│         session.append(ASSISTANT(answer)) → trace final_answer        │
│         return AgentResult(OK)          ← step 4：返回结果            │
│                                                                       │
│ 3. 超出 maxSteps → 禁用工具强制收尾 → AgentResult(MAX_STEPS)          │
└───────────────────────────────────────────────────────────────────────┘
  │
  ▼
AgentResult{ answer, status, steps[], totalUsage, latencyMs, errorCode }
```

### 4.3 关键设计决策（以及为什么）

| 决策 | 理由 |
| --- | --- |
| 循环控制权留在 Runtime，模型只能「请求」调用工具 | 否则无法做步数限制、重复调用拦截、超时与预算控制 |
| 工具结果一律作为 observation 回灌，不直接返回用户 | 模型需要基于结果组织语言；失败也要让它看到原因并自我修正 |
| 工具失败不抛异常，而是结构化错误结果 | 一次工具失败不该中断对话；错误信息是给模型看的「可执行提示」 |
| `reasoning`（思维链）持久化但**绝不回灌 API** | DeepSeek 官方要求 + 思维链只对本轮有价值，回灌既烧钱又干扰推理 |
| 上下文按**完整轮次**切窗口 | `assistant(tool_calls)` 与 `tool` 消息必须成对，从中间切断会导致 API 400 |
| 压缩阈值用 `max(本地估算, API 真实 prompt_tokens)` | 本地估算会漏算工具 Schema 开销（实测低估近 1 倍），只看估算会「悄悄超预算」 |
| 摘要增量合并而非每轮重写 | 避免早期关键信息在多轮压缩中被逐步稀释 |
| 压缩失败降级为确定性摘要 | 压缩动作永不阻塞对话 |
| 同一 session 串行、不同 session 并行 | 保证历史与工具配对一致，同时多窗口互不阻塞 |
| trace 事件与业务解耦（sink 失败被吞掉） | 观测不能拖垮业务 |

---

## 5. 工具机制

### 5.1 一个工具要提供什么

```java
public interface Tool {
    String name();                                // 唯一标识，模型用它发起调用
    String description();                         // 模型判断「何时调用」的唯一依据
    ObjectNode parametersSchema();                // JSON Schema：类型/必填/枚举/范围
    ToolResult execute(ObjectNode args, ToolContext ctx) throws Exception;
}
```

`ToolContext` 把「这次调用属于谁」传给工具（`userId` / `sessionId` / `callId` / `Session` / `Tracer`），
因此工具可以安全地读写**当前会话**的工作记忆（`todo` 就是这么实现 session 级状态的）。

### 5.2 注册与决策链路

```
Tool 实现 ──register──▶ ToolRegistry ──specs()──▶ LlmRequest.tools ──▶ OpenAI tools 字段
                              │                                              │
                              │                                    模型自主决定调用哪个
                              ▼                                              ▼
                       find(name) ◀──── ToolCall(name, argsJson) ◀── LlmResponse.tool_calls
```

### 5.3 内置工具

| 工具 | 类型 | 说明 |
| --- | --- | --- |
| `calculator` | 真实 | 自研递归下降求值器（**不用 eval/ScriptEngine**）：四则、取模、幂、括号、`sqrt/pow/min/max/round/floor/ceil/log/ln/exp/sin/cos/tan/sum`、常量 `pi/e`，容忍全角符号 |
| `search` | mock（本地真实检索） | 对 `docs/knowledge/*.md` 按 `##` 切段后做关键词打分（标题/短语命中加权 + 长度归一），返回 docId/章节/片段 |
| `read_docs` | 真实 | 按 `doc_id` + `offset/limit` 分页读原文，配合 search 形成「先检索后精读」，控制预算 |
| `todo` | 真实（有状态） | `add/list/done/clear`，状态存 session 工作记忆 ⇒ 天然按窗口隔离 |
| `weather` | mock | 12 个城市，种子 = `city+date` hash ⇒ 同城同日结果可复现；返回以 `[mock 数据]` 开头，描述里明确写不访问真实气象接口 |

### 5.4 工具调用防护（8 项）

未知工具 → 坏 JSON → 非对象参数 → Schema 校验（缺必填/类型/枚举/范围）→ 类型纠偏（`"3"` → `3`）→
超时（默认 5s，线程中断）→ 异常折叠（不含堆栈）→ 结果截断（默认 2000 字）。
每一次调用都会写 `tool_call` + `tool_result` 两条 trace。

详见 [`docs/API.md`](docs/API.md) 第 6 节。

---

## 6. Session 管理与多窗口隔离

**一个窗口 = 一个 `Session` 对象**，独占四样东西：消息历史、工作记忆、压缩摘要与压缩位点、trace。

```java
SessionManager manager = runtime.sessions();
Session w1 = manager.getOrCreate("A", "w1", "窗口1");   // 幂等：反复取到同一个对象
Session w2 = manager.getOrCreate("A", "w2", "窗口2");   // 与 w1 完全隔离
manager.require("B", "w1");                            // ✗ 抛 SessionAccessException（跨用户越权）
```

- **窗口可以随时接着聊**：Session 常驻内存，下一轮直接续写历史（`AgentRuntime.run("A","w1", ...)`）。
- **隔离强度**：用户 B 用同样的 `sessionId` 也拿不到 A 的数据（`AgentRuntime` 会把它转成 `SESSION_ERROR` 失败结果）。
- **并发语义**：同一 session 内串行（内部锁），不同 session 并行；`SessionManagerTest` 有 8 线程 × 50 次的并发用例。
- **存储可替换**：`SessionManager` 的方法签名即存储契约（换成 Redis/DB 不影响 Runtime）。

CLI 侧：`/new` 开窗口、`/sessions` 看全部窗口、`/use` 切换 —— 直观对应「用户 A 开了窗口 1 和窗口 2」。

---

## 7. Context 与 Memory（召回时机与放置方式）

这是本题的核心考点，单独展开。

### 7.1 每次调用 LLM 前的上下文拼装

```
┌ system ─ 角色与规则（工具使用纪律、输出要求）
├ system ─ 【长期记忆｜历史对话摘要】        ← 有摘要则必带
├ system ─ 【工作记忆｜结构化状态】          ← 有待办/事实则必带
├ system ─ 【相关历史片段（自动召回 Top-K）】← 与当前问题相关才带
└ 对话消息 ─ 最近 keepRecentTurns 轮原文（按 user 边界切完整轮次、保序）
              · 本轮（最后一条 user 之后）：原样保留
              · 历史轮：assistant 正文截断 400 字，工具结果截断 800 字
```

### 7.2 记忆的写入 / 召回时机 / 放置方式

| 记忆类型 | 内容 | 写入时机 | 召回时机 | 放置位置 | 为什么这样放 |
| --- | --- | --- | --- | --- | --- |
| 短期记忆 | 原始对话与工具结果 | 每轮 `session.append` | 每轮固定保留最近 N 轮 | 对话消息区（保序） | 满足 OpenAI 对 `tool` 消息的顺序约束 |
| 工作记忆 | 待办清单、最近工具结论 | 工具执行时 `memory.putFact/addTodo` | **每轮无条件注入** | system 块 | 体量小、价值高、绝不能滑出窗口 |
| 长期记忆 | 历史压缩摘要 | 超预算触发压缩时增量合并 | **每轮无条件注入** | system 块 | 保证久远事实不随窗口滑动丢失 |
| 情节记忆 | 被压缩/滑出窗口的历史片段 | ——（沉淀在历史里） | 每轮以**本轮用户输入**为 query 打分召回 Top-K | system 块（带 `#seq role` 来源标注） | 用户翻旧账时补回细节，且不污染主对话流 |

> **一句话总结召回时机**：召回只发生在 `ContextManager.build()`，也就是**每次请求 LLM 之前**；
> 摘要与工作记忆是无条件注入，历史片段是按相关度注入。

### 7.3 压缩策略

- **触发**：`max(本地估算 + 固定开销, 最近一次真实 prompt_tokens) > maxContextTokens`（默认 6000）。
  - 固定开销 = system prompt + 全部工具 Schema 的估算。**这是实测踩过的坑**：工具 Schema 常占 1000+ token，
    只看历史长度会低估近 1 倍，导致上下文早已超预算却不触发压缩（见 [`docs/AI_PROMPTS.md`](docs/AI_PROMPTS.md) 问题 3）。
- **范围**：`[summarizedUpTo, 最近 keepRecentTurns 轮的起点)`，按 user 消息切分，绝不切散工具配对。
- **方式**：`LlmSummarizer` 用独立提示词做「增量合并摘要」，明确要求保留目标/事实（城市、数字、待办、文档号）与未决问题。
- **降级**：LLM 摘要失败或返回空 ⇒ `DeterministicSummarizer`（抽取用户诉求 + 工具结论），压缩永不阻塞对话。
- **边界**：若预算小于最近 N 轮的固有开销，压缩后仍会超预算 —— 属配置问题，应调大预算或调小 `keepRecentTurns`。

### 7.4 哪些信息「不」塞进 context（同样重要）

| 信息 | 处理 | 理由 |
| --- | --- | --- |
| 思维链 `reasoning` | 持久化供 trace，**不回灌 API** | 官方要求 + 只对本轮有价值 |
| 历史轮的 assistant 长正文 | 截断到 400 字 | 结论保留、过程丢弃 |
| 历史轮的超长工具结果 | 截断到 800 字 | 本轮之外的工具输出多为噪声 |
| 被压缩的原始消息 | 只留摘要 + 可召回索引 | 预算有限，摘要 + 召回 > 全量保留 |

---

## 8. 异常处理与可观测性

### 8.1 异常分层

| 层 | 异常 | 处置 |
| --- | --- | --- |
| 配置 | `ConfigurationException` | 启动即失败（缺 Key） |
| 会话 | `SessionAccessException` | 转成 `AgentResult(FAILED, SESSION_ERROR)` |
| LLM | `LlmException`（`retryable` 标记） | 网络/429/5xx 指数退避重试；401/400 不重试；耗尽后转失败结果 |
| 工具 | 任意 `Exception` | 折叠成 `ToolResult.error`，回灌给模型自愈 |
| 循环 | 空输出 / 重复调用 / 超步数 | 空输出自愈一次、重复调用拦截、超步数强制收尾 → 再失败返回确定性兜底文案 |

**统一契约**：`AgentRuntime.run` 永不抛异常，调用方只需读 `AgentResult.status / errorCode / answer`。

### 8.2 trace 事件

`session_created`、`user_input`、`context_built`、`memory_recall`、`context_compressed`、
`context_compress_failed`、`llm_request`、`llm_response`、`tool_call`、`tool_result`、`tool_blocked`、
`loop_step`、`final_answer`、`max_steps_reached`、`error`。

- 落盘：`logs/<sessionId>.jsonl`（一行一事件，含耗时、token、错误码；`jq` 可直接查）。
- 内存：保留最近 2000 条，CLI `/trace` 可回放时间线。
- 失败隔离：sink 抛异常不影响主流程；写盘失败只打 warning。

---

## 9. 测试

### 9.1 运行

```bash
mvn test        # 163 个单元测试（离线，约 3 秒，不花 API 费用）
mvn verify      # 额外 5 个真实 API 集成测试（DeepSeekLiveIT，无 Key 自动跳过）
```

### 9.2 覆盖矩阵

| 测试类 | 用例数 | 覆盖点 |
| --- | --- | --- |
| `AgentLoopTest` | 15 | 循环四步、多工具并发、maxSteps 强制收尾、工具错误自愈、未知工具、重复调用拦截、空输出自愈、LLM 失败收敛、空输入、完整 trace |
| `OpenAiCompatibleClientTest` | 16 | wire format（tools/tool_calls/tool_call_id）、思维链不回灌、响应解析、429 重试、5xx 重试上限、401/400 不重试、坏响应、URL 规整、网络错误 |
| `LlmOutputParserTest` | 11 | 原生 tool_calls、`<tool_call>`、JSON 代码块、ReAct、整段 JSON、去重、防误判、EMPTY、括号配平扫描 |
| `ContextManagerTest` | 11 | 组装顺序、窗口按轮切分、不切散工具配对、压缩触发与位点、压缩后记忆仍在、固定开销计入、真实 token 信号、历史截断、召回 |
| `AssignmentScenarioTest` | 8 | 窗口 1 查天气记待办、窗口 2 检索记待办、纯对话追问、带工具追问、session 隔离、多用户越权、压缩后记忆、trace |
| `ToolInvokerTest` | 10 | 8 项工具防护 + trace 断言 |
| `BuiltinToolsTest` | 13 | 5 个工具的成功/失败/分页/隔离行为 |
| `CalculatorToolTest` | 29 | 表达式正确性（含全角）、非法表达式、格式化、Schema |
| `SessionManagerTest` | 9 | 隔离、幂等、越权、并发 8×50、工作记忆生命周期、摘要位点 |
| `MemoryRecallerTest` | 9 | 召回相关度、时间序、TopK、边界、渲染、摘要三级降级 |
| `TraceTest` | 8 | 事件序号、耗时、异常、JSONL 落盘（含 Instant 回归）、文件名安全化、sink 失败隔离 |
| `SchemaValidatorTest` | 6 | 必填/类型/枚举/范围/非对象/类型纠偏 |
| `ToolRegistryTest` | 5 | 注册、重名、非法工具、specs 导出、内置清单 |
| `UtilTest` | 7 | CJK 分词（含标点排除）、token 估算、截断、单行化、JSON、Env |
| `DeepSeekLiveIT` | 5 | **真实 API**：工具自主决策、跨轮记忆、知识库检索、会话隔离、用量与 trace |

### 9.3 设计要点

- **循环测试用 `ScriptedLlmClient`**：精确控制「模型每一步输出什么」，才能断言 Runtime 的行为
  （如「第二步的请求里必须包含工具结果」「收尾那次请求必须不带 tools」）。`ScriptedLlmClient` 也写 trace，与真实客户端行为对齐。
- **HTTP 层用 JDK 内置 `HttpServer` 打桩**：验证真实 wire format、重试与错误分类，不依赖外网。
- **真实 API 用例单独隔离**：`*IT` 由 failsafe 在 `verify` 阶段运行，无 Key 自动 skip，CI 不会红。

---

## 10. 真实 API 运行记录

模型：`deepseek-chat` ｜ 命令：`java -jar target/mini-agent.jar --demo --max-context-tokens 2000`
完整记录：[`docs/DEMO.md`](docs/DEMO.md)（离线 mock 版：[`docs/DEMO-mock.md`](docs/DEMO-mock.md)）

实测关键片段（真实模型自主决策，非关键词匹配）：

```
▶ 用户@w1：帮我查一下北京今天的天气，然后记个待办：给张总发周报
  · step 1 工具调用：[weather({"city": "北京", "date": "today"}), todo({"action": "add", "item": "给张总发周报"})]
      ✔ weather → [mock 数据] 北京 2026-09-28：阵雨，7~17℃，湿度 85%，2 级风，AQI 122。
      ✔ todo → 已加入待办 #1：给张总发周报（当前未完成 1 项）
  ◀ Agent：北京今天（2026-09-28）：阵雨，7~17℃…出门记得带伞，路面湿滑注意安全。
           待办已记好：#1 给张总发周报（未完成 1 项）。（天气为 mock 数据源，非真实气象接口。）
```
> 一轮内并发调用两个工具；模型自己把 `date` 填成 `today`；主动声明数据来源是 mock。

```
▶ 用户@w2：我要写本周周报，先查一下知识库里的周报模板，然后记个待办：周五前提交周报
  · step 1 工具调用：[search({"query": "周报模板 周报格式 写作规范"}), todo({"action": "add", "item": "周五前提交周报"})]
```
> **模型自己改写了检索 query**（原始输入里没有「周报格式 写作规范」），并基于检索片段总结出模板结构，最后反问用户要补充哪些信息。

```
▶ 用户@w1：我刚才让你记的待办是什么？          ← 纯对话追问，未调用任何工具
  ◀ Agent：你让我记的待办是：#1 给张总发周报（目前未完成，共 1 项）。

▶ 用户@w1：帮我把它标记成已完成                ← 带工具追问，模型理解「它」= #1
  · step 1 工具调用：[todo({"action": "done", "id": 1})]
  ◀ Agent：已把 #1「给张总发周报」标记为完成，当前没有未完成的待办。

▶ 用户@w2：我有哪些待办？                      ← 窗口 2 只能看到自己的
  ◀ Agent：你当前有 1 项待办：1. #1 周五前提交周报 [未完成]
```

- 单轮耗时约 1.0–2.7s（含 2 次 LLM 调用），token 用量从 API `usage` 精确采集并写入 trace。
- 离线 mock 版完整覆盖同一批场景，无 Key 也能验证 Runtime 行为。

---

## 11. 目录结构

```
.
├── pom.xml                      Maven 配置（Java 17，仅 jackson + junit 依赖）
├── README.md                    本文档
├── .env.example                 配置模板（复制为 .env.local）
├── docs/
│   ├── API.md                   ★ Java 接口文档（逐签名）
│   ├── DESIGN.md                系统设计（含时序图与决策记录）
│   ├── AI_PROMPTS.md            ★ AI Prompt 与问题解决记录
│   ├── TESTING.md               测试用例清单与运行说明
│   ├── DEMO.md / DEMO-mock.md   真实 API / 离线 运行记录
│   └── knowledge/*.md           内置知识库（search/read_docs 的数据源）
├── src/main/java/com/miniagent/ 源码（见第 4 节分层）
├── src/test/java/com/miniagent/ 测试（163 单元 + 5 集成）
└── logs/                        运行期 trace（JSONL，已 gitignore）
```

---

## 12. 提交到 GitHub

本仓库已经是本地 git 仓库（含分阶段提交历史）。推送到远端：

```bash
git remote add origin git@github.com:<你的用户名>/mini-agent.git
git branch -M main
git push -u origin main
```

> `.env.local`、`logs/`、`target/`、`.m2repo/` 均已被 `.gitignore` 排除；
> 提交前可用 `git status --ignored` 确认没有密钥与构建产物混入。

---

## 13. 已知限制

1. **`weather` 是 mock 数据源**（题面允许），未接真实气象 API；`search` 是本地关键词检索而非向量检索 —— 两者都保留了与真实实现一致的接口形状，替换不影响 Runtime。
2. **token 估算为启发式**（±30%）：压缩触发已优先采用真实 `prompt_tokens`，但首次请求前的判断仍依赖估算。
3. **Session 存储是进程内内存**：进程重启会丢失；接口已按可替换设计（换 Redis/DB 无需改 Runtime）。
4. **召回是关键词打分**，不做语义理解；同义改写（「魔都」vs「上海」）召回不到，生产应换向量检索。
5. **并发模型**：同一 session 串行执行，超长任务会阻塞该窗口的后续提问（不同窗口不受影响）。
6. 未实现：流式输出、多模态、工具并行执行（当前工具是串行调用，尚未并行化）。

---

## 14. 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/API.md`](docs/API.md) | **Java 接口文档**：全部公开类/方法签名、契约、错误码表、扩展指南 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 系统设计：分层、时序、数据结构、上下文与记忆策略、权衡记录 |
| [`docs/AI_PROMPTS.md`](docs/AI_PROMPTS.md) | **AI Prompt 与问题解决记录**：开发中使用的提示词、真实踩坑与修复过程 |
| [`docs/TESTING.md`](docs/TESTING.md) | 测试用例清单、运行方式、覆盖矩阵与断言意图 |
| [`docs/DEMO.md`](docs/DEMO.md) | 真实 DeepSeek API 的完整运行记录 |
| [`docs/DEMO-mock.md`](docs/DEMO-mock.md) | 离线 mock 模式运行记录 |
