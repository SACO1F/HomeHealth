# HomeHealth · Agent 岗面试准备指南（中英双语）
# HomeHealth · Agent-Role Interview Prep Guide (Bilingual CN/EN)

> 生成日期 Date: 2026-09-27
> 依据 Basis: master 分支当前代码逐文件实测（ReActAgent / LlmClient / QaRetriever / 四工具 / Room v9），非凭空八股。
> Grounded in the actual code on master — every number below was read from source, not invented.

---

## 0. 使用说明 | How to Use

- 每题包含：**题目 → 参考答案 → 解析（考察意图 / 答题要点 / 可能追问 / 常见错误）**。中文为完整版，英文为对照版（题目 + 答案 + 压缩解析）。
- Each question has: **Question → Model Answer → Analysis (why asked / key points / follow-ups / common mistakes)**. Chinese is the full version; English is the companion version.
- 三套题 Three sets:
  - **Set 1（28 题）**：Agent 专题，全部锚定本项目真实实现。
  - **Set 2（25 题）**：语言框架 / 数据结构算法 / 系统设计 / 并发性能 / 测试调试。
  - **Set 3（21 题）**：通用 Agent 知识（范式、上下文工程、RAG、协议、安全、评估）。
- 建议用法：先自己作答 60~90 秒，再对答案；错题回到对应源码文件重读一遍。
- Suggested use: answer each in 60–90s before reading; revisit the referenced source file for every miss.

## 0.1 假设 | Assumptions

1. **角色级别**：2~5 年 Android 工程师，目标岗位为「Agent / LLM 应用开发」（大模型应用工程师、AI 工程师-应用方向），面试以中文为主；英文对照用于外企或英文面试。
   Role level: mid-level (2–5 yrs) Android engineer interviewing for **Agent / LLM-application** positions. Chinese is the primary interview language; English is provided for international companies.
2. **技术栈**：Kotlin 2.3 / Jetpack Compose / Room v9 / Hilt / OkHttp + SSE / Gson / 协程 Flow。无服务端（BYOK：用户自带 API Key 直连供应商）。
3. **项目范围**：单机 Android 健康管理应用。Agent 部分为**手写单 Agent 循环 + 4 个只读工具**，无多智能体编排、无向量库、无服务端框架（这些"没有"本身就是考点）。
4. 未涵盖：端侧模型推理（llama.cpp/MediaPipe）、分布式多 Agent 系统（按需自学，见 Set 3 相应题的追问）。
   Not covered: on-device inference, distributed multi-agent systems (see Set 3 follow-ups for pointers).

## 0.2 项目速查表 | Project Cheat Sheet（背下来背下来背下来）

| 常量 / 事实 Constant | 值 Value | 出处 Source |
|---|---|---|
| Agent 最大轮数 MAX_TURNS | 5 | `AgentConfig.kt` |
| 单次提问工具调用上限 MAX_TOOL_CALLS | 8 | `AgentConfig.kt` |
| 同一工具连续失败禁用阈值 | 2 次 | `AgentConfig.kt` |
| 单条 observation 字符预算 | 1,500（超限标注省略字符数） | `AgentConfig.kt` |
| 本地工具 / 视觉工具超时 | 10s / 60s（`withTimeoutOrNull`） | `AgentConfig.kt` |
| Agent 最终回答轮采样 | temperature 0.3 / maxTokens 2048 | `AgentConfig.kt` |
| 问答重试次数（解析场景） | 3 次（解析仅 2 次，避免用户干等 6 分钟） | `LlmClient.kt` |
| 重试退避 | 500ms × 2ⁿ + 0~250ms 抖动 | `withRetry` |
| 上下文全量摘要预算 SUMMARY_CHAR_BUDGET | 6,000 字符 | `QARepositoryImpl.kt` |
| 每指标最近记录数 RECORDS_PER_TYPE | 10 条（SQL 侧 Top-N，避免 N+1） | `QARepositoryImpl.kt` |
| BM25 Top-K 召回 | 12 条（k1=1.5, b=0.75，score≤0 不返回） | `QaRetriever.kt` |
| 工具清单（全部只读） | `search_records` / `get_reference_range` / `get_alerts` / `read_report_image` | `domain/tool/` |
| 协议 | OpenAI 兼容 + Anthropic Messages，SSE 流式 | `AgentRequestBody.kt` |
| Room | v9，7 实体，exportSchema=true；`llm_call_logs` 索引 createdAt | `AppDatabase.kt` |
| 错误分类 errorType | http_429 / http_5xx / http_4xx / timeout / io / truncated / config / 异常类名 | `LlmClient.classifyError` |
| 事件流 | AgentEvent{Answer,Thinking,ToolCallStarted,ToolCallFinished,Completed}；UI 侧 QaStreamEvent{References→…→Finished} | `AgentProtocol.kt` / `QARepositoryImpl.kt` |
| 解析管线 | 拍照（最长边 1600px 降采样）→ 视觉模型 JSON → 归一化（106 别名 / 24 单位写法 / 24 换算）→ 人工确认卡片 → 入库 | `README.md` |
| 测试 | 约 100 条 JVM 单测（09-23 时点 101 全绿）；ReActLoopTest 用假网关脚本化循环策略 | `app/src/test` |
| 合规三件套 | 首启同意门（CONSENT_VERSION_CURRENT=1）+ 固定免责尾注 + API Key AES-256-GCM（Keystore） | `SettingsPrefs.kt` 等 |

**项目叙事一句话（面试开场可直接用）**：
"这是一个本地优先的家庭健康管理 Android 应用：拍照 → 视觉 LLM 提取 49 类指标 JSON → 归一化入库 → 三规则异常检测 → 流式健康问答。问答有两条路径——指向具体指标的问题走 BM25 检索快路径，泛化/多步问题走手写 ReAct Agent（4 个只读工具），失败逐级降级到本地规则引擎；每次 LLM 调用落一条不含内容的可观测日志。"
One-liner: "A local-first family health manager: photo → vision-LLM JSON extraction → normalization → three-rule anomaly detection → streaming Q&A. Q&A has two paths — BM25 fast path for metric-specific questions, a hand-written ReAct agent with 4 read-only tools for open-ended ones, degrading stepwise to a rule engine; every LLM call logs privacy-safe metrics."

---

# Set 1 · Agent 专题（项目落地，28 题）

## 1A 架构与 Agent 循环 | Architecture & Agent Loop

---

### S1-1. 为什么手写 ReAct 循环，而不是引入 LangChain / LangGraph / AutoGen？

**参考答案**：本项目是**单进程、单循环、4 个工具**的场景，框架提供的编排抽象（图调度、消息总线、多角色协作）一项都用不上；反而会把最关键的三个判断藏进框架内部——什么时候停、失败了怎么办、观察结果如何截断。手写 200 行循环换来的是：全部策略可被 JVM 单测覆盖（`ReActLoopTest` 用假网关脚本化任意轨迹）、依赖只剩 Hilt 注入的两个接口、排查"模型为什么调错工具"时读代码即可，不用读框架源码。成本（协议适配、重试、流式解析）并没有消失，而是集中在 `LlmClient`/`AgentRequestBody`/`LlmStreamParser` 三个纯函数文件里，一样可测。

**解析**：
- **考察意图**：考架构决策能力——能否说清"不用框架"的理由，而不是背"LangChain 很流行"。面试官想听的是**权衡**而非站队。
- **答题要点**：a) 场景复杂度评估：单循环 vs 多角色/多阶段图；b) 框架的抽象泄漏：停机条件、失败策略、截断策略属于业务核心，不该委托给框架默认值；c) 可测试性：接口化 `AgentLlmGateway` 后循环策略可脚本化测试；d) 引入框架的合理时机（见追问）。
- **可能追问**："如果要做多 Agent 协作你会引入什么？"（LangGraph 的图状态机 / 自研消息协议，先问清协作拓扑）；"LangChain 的 Memory 抽象了解吗？"；"什么规模下框架才开始划算？"（≥3 个子任务角色、需要 checkpoint/恢复、需要人工审批节点）。
- **常见错误**：把"不用框架"答成"框架不好"（框架在自己的适用场景是好的）；说不出框架究竟省了什么（省的是编排，不是协议与解析）。

**EN**: Q: Why a hand-written ReAct loop instead of LangChain/LangGraph/AutoGen?
A: Single process, single loop, 4 tools — framework orchestration buys nothing here, yet it hides the three judgments that matter most: when to stop, what to do on failure, how to truncate observations. A ~200-line loop keeps every strategy unit-testable (`ReActLoopTest` scripts any trajectory with a fake gateway).
Why asked: tests architectural judgment, not framework fashion.
Keys: complexity assessment; abstraction leakage; testability via `AgentLlmGateway`; when a framework *would* pay off.
Follow-ups: multi-agent scale-out; LangGraph checkpointing.
Pitfall: framing it as "frameworks are bad" instead of a context-dependent trade-off.

---

### S1-2. 从用户提问到收尾，完整讲一遍 `ReActAgent.run` 的生命周期与事件序列。

**参考答案**：入口 `run(context, recordsOverview): Flow<AgentEvent>`，`flowOn(Dispatchers.IO)`。① 组装消息：`AgentEntry.System(SYSTEM_PROMPT)` + `AgentEntry.User(用户问题+成员信息+记录概览)`——附图不在消息里，只留一句提示"可用 read_report_image 读取"。② 进入 `for (turnIndex in 1..MAX_TURNS)`：每轮先算本轮工具集（最后一轮为空；其余为 `registry.availableFor(context)` 去掉已禁用工具），调 `gateway.turn()` 流式收增量——`onDelta` 先缓冲进 `turnText`（因为本轮是答案还是过渡语要到流结束才知道），`onThinking` 实时 emit。③ 无工具调用 → 判定为最终回答：正文 emit `Answer`，随后 emit `Completed(text, thinking, truncated, evidence, toolNames)` 并 return。④ 有工具调用 → 本轮正文并入思考轨迹 emit `Thinking`，消息追加 `AssistantToolCalls`，逐个执行工具（次数护栏→注册表查名→禁用检查→`withTimeoutOrNull` 超时→try/catch 兜底），emit `ToolCallStarted/Finished`，成功的检索类结果进 `evidence`，失败累计计数，最后追加 `ToolResults` 进入下一轮。⑤ 中途异常 → break 后抛 `lastFailure`，交给上层降级。UI 侧（`QARepositoryImpl`）再把 AgentEvent 翻译成 QaStreamEvent，最终 `Finished` 时连同问答历史落库。

**解析**：
- **考察意图**：检验是否真的读懂了循环——能不能不看代码把控制流、事件流、状态（evidence/disabled/failures/计数）讲清楚。这是 Agent 岗最核心的"讲项目"题。
- **答题要点**：a) 正文"先缓冲后决策"的原因；b) 最后一轮撤工具的合流设计；c) 工具执行的四层防护（上限/查名/禁用/超时/兜底 catch）；d) evidence 只收集 `search_records` 与 `get_alerts`；e) 事件顺序对 UI 的意义（References 先于正文）。
- **可能追问**："为什么 evidence 收集放循环内而不是结束后重新查库？"（保证依据与模型看到的逐字一致）；"turnIndex 循环里哪一步最可能抛异常？"（gateway.turn 的网络层）；"如果模型一次返回 3 个并行 tool_calls 怎么处理？"（逐个执行、共享 MAX_TOOL_CALLS 预算，超了回填"已达上限请作答"）。
- **常见错误**：把 Thinking 事件说成"模型自带的思考流"（项目里工具轮正文也会进 Thinking）；漏掉"最后一轮无工具"；说 Completed 之后还会 emit Answer（顺序反了）。

**EN**: Q: Walk through `ReActAgent.run` end to end.
A: Build entries → up-to-5 turns; last turn has no tools; deltas buffered per turn (final-answer vs interim is only known when the stream ends); no tool calls → Answer + Completed; tool calls → text goes to Thinking, tools executed with 4-layer guards (budget / lookup / disable-list / timeout+catch), results appended as ToolResults; mid-loop failure throws upward for degradation.
Why asked: the "explain your project" litmus for agent roles.
Keys: buffered turn text; final-turn tool removal; guards; evidence collection; event ordering.
Follow-ups: why evidence isn't re-queried; parallel tool calls sharing the budget.
Pitfall: conflating Thinking with model-native reasoning streams.

---

### S1-3. "最后一轮不提供工具"——这个设计解决什么死角？为什么它比"轮数用尽后强制总结"更好？

**参考答案**：死角是"**轮数用尽 + 手里还挂着一批没回填的工具调用**"：如果最后一轮仍给工具、模型又选择了调用，循环必须结束，这些调用要么被静默丢弃（模型下一轮若还在就会语无伦次），要么伪造结果（污染）。提前在 `turnIndex == MAX_TURNS` 时传 `tools = emptyList()`，模型在协议层就**看不见**任何可调用工具，唯一出路是用已有 observation 作答——"轮数用尽"与"正常收尾"走同一条代码路径，不会出现"没有回答"的空档。配套实现细节：`AgentRequestBody` 在 tools 为空时**整体省略** tools 字段而非传空数组（部分供应商判空数组非法）。这个策略有专门单测：`最后一轮不再向模型提供工具`。

**解析**：
- **考察意图**：考循环终止条件的设计深度。多数候选人只会答"设个 MAX_TURNS"，能讲出终止时的工具残留问题才是真的写过。
- **答题要点**：a) 问题本质：FC 协议下模型输出 tool_calls 后必须回填 tool 结果，否则消息序列非法；b) "提前撤声明"是协议层的硬约束，比提示词"请在最后一轮直接回答"可靠（提示词是软约束）；c) 与"强制注入一条 user 消息要求总结"的对比：后者多花一轮 token 且可能再要工具；d) 空数组 vs 省略字段的供应商兼容细节。
- **可能追问**："还有别的终止设计吗？"（显式 `finish`/`submit_answer` 工具、结构化输出标记 STOP；对比优劣）；"最后一轮模型仍想调工具会怎样？"（协议上发不出，只会输出文本）；"中间某轮 LLM 请求失败怎么办？"（break → 抛给上层降级，不硬撑）。
- **常见错误**：只答"防止无限循环"（那是 MAX_TURNS 的职责，两者要区分）；不知道空数组在某些供应商的兼容性问题。

**EN**: Q: What does "no tools on the final turn" solve?
A: It eliminates the dead corner "turn budget exhausted with un-answered tool calls outstanding". By passing an empty tool list at `turnIndex == MAX_TURNS`, the model can't emit tool calls at all and must answer from existing observations — exhaustion and normal completion converge to one code path. Empty array is omitted entirely (some providers reject it).
Why asked: tests depth on loop-termination design.
Keys: FC protocol requires tool results back-fill; removing declarations is a hard constraint vs soft prompt; unit-tested.
Follow-ups: explicit `finish` tool alternative; mid-loop LLM failure.
Pitfall: confusing this guard with the MAX_TURNS loop cap itself.

---

### S1-4. Agent 轮的正文为什么"先缓冲、不逐字上屏"？代价是什么？

**参考答案**：一轮流式输出在**流结束前无法判定**它是最终回答还是"我先查一下血糖"这种过渡语。如果逐字上屏，模型输出工具调用轮时就必须把已经渲染的正文**撤回**——用户看到答案区文字突然变成工具轨迹，是明显的视觉抖动。因此 Agent 路径：`onDelta` 只 append 到 `turnText`，流结束后二选一——无工具调用 → 整段 emit `Answer`；有工具调用 → 整段进 `Thinking` 轨迹。**代价**：Agent 路径的正文不是逐字流式，最终回答轮要多等几百毫秒到几秒（整段生成完才上屏）；而快路径（无工具）保持逐字上屏。这是"正确性优先于首字延迟"的刻意取舍，且如实写进了设计文档——面试里主动承认代价并解释取舍，比假装没有代价可信得多。

**解析**：
- **考察意图**：考流式 UI 与 Agent 语义的冲突处理；同时考是否诚实评估工程取舍（用户 SOUL 里明确反感"只报喜"）。
- **答题要点**：a) 判定时机问题：最终性是轮级属性不是 token 级属性；b) 抖动 vs 延迟的权衡；c) 缓解方案：Thinking 通道实时流出（用户并非干等）、快路径保持逐字、可在最终轮检测到"已确认无工具"后再流式（混合方案，代价是复杂度）。
- **可能追问**："能不能提前判断？比如看到 <tool_call> 标记就转 Thinking？"（供应商流式 FC 的 tool_calls 分片与正文可能交错，启发式不稳）；"用户感知延迟有多大？"（最终回答轮 = 完整生成时间，与快路径首 token 差 1~5s 量级，可用 Thinking 填充等待）。
- **常见错误**：说"反正都能流式"（没理解判定问题）；说"撤回就行"（UI 撤回体验差且思考轨迹丢失）。

**EN**: Q: Why buffer Agent-turn text instead of streaming it to the answer area?
A: Whether a turn's text is the final answer is only known when the stream ends. Streaming first and retracting on tool turns causes visible UI jitter. So deltas are buffered; final text is emitted wholesale to Answer, or routed to Thinking. Cost: the final answer is not token-streamed in agent mode — a deliberate correctness-over-latency trade-off, documented honestly.
Why asked: streaming UX vs agent semantics.
Keys: finality is turn-level; jitter vs latency; Thinking channel keeps users informed.
Follow-ups: hybrid early-detection schemes and why they're fragile.
Pitfall: claiming "it all streams anyway".

---

### S1-5. strongTerms 路由闸门：什么问题走 Agent、什么问题走快路径？为什么说"用 Agent 替换整条链路是负优化"？

**参考答案**：`QARepositoryImpl.askStream` 里：`QaRetriever.strongTermsOf(question)` 抽取"强指标词"（49 类指标中文名 + 106 条别名 + 成组词如"血脂"，多字条目再加相邻双字窗口）。**strongTerms 为空**（泛化问题如"我整体怎么样"，没有可靠指标词）→ 才走 Agent；**非空**（"血糖最近怎么样"指向明确）→ 走快路径：BM25 Top-12 召回 + `[n]` 引用一次成文。为什么：对指向明确的单步问题，Agent 只会把 1 次请求变成 2~5 次——每轮重发全部历史与工具 schema，token 成本超线性增长，而答案质量**没有提升**（检索层是同一套 BM25，`search_records` 工具内部复用 `QaRetriever`，保证两路召回一致）。另外两个硬条件：没配置 LLM 或既无记录也无图时不走 Agent（本地引擎一句"请先录入"即可）。这就是"Agent 是放大器不是替换品"：它解决**需要多步交叉验证**的问题（读图+查参考范围+查告警），不解决单步检索问题。

**解析**：
- **考察意图**：考"何时该用 Agent"的产品级判断——这是区分"会调 API"和"会做系统"的分水岭，也是本项目最有面试价值的判断之一。
- **答题要点**：a) 路由信号：strongTerms 语义（为什么"身体"这种泛化词不能进闸门——单字"体"撞体重记录会把上下文窄化）；b) 成本模型：轮数×(历史+schema) 超线性；c) 质量守恒论证：两条路径共用检索实现；d) 闸门的三个非词条件（llmReady / 有数据 / 无 noVision）。
- **可能追问**："strongTerms 漏召回怎么办？"（匹配不到时回退全量摘要，行为与检索上线前一致——失败安全）；"为什么不用小模型做意图分类路由？"（多一次请求延迟+成本，词表闸门零成本可解释；指标词是封闭集合）；"用户问'血糖和血压哪个更值得担心'算单步还是多步？"（多步——要两个指标的记录+告警+参考范围，正因 strongTerms 非空会走快路径……承认这是闸门的已知取舍，候选改进是"复合指标词检测"）。
- **常见错误**：把路由说成"按问题长度/复杂度"（实际是词表闸门）；不知道两条路径共用 `QaRetriever`。

**EN**: Q: When does a question go to the agent vs the fast path?
A: `strongTermsOf` extracts strong metric terms (labels + 106 aliases + group words). Empty → generalized question → agent path; non-empty → BM25 fast path with [n] citations. Routing by agent for metric-specific questions turns 1 request into 2–5 with superlinear token cost and no quality gain (both paths share the same `QaRetriever`). Agent is an amplifier for multi-step cross-checking, not a replacement for single-step retrieval.
Why asked: the "when to use an agent" product judgment.
Keys: vocabulary gate semantics; cost model; quality-conservation argument; additional conditions.
Follow-ups: miss-recall fallback; classifier-based routing trade-offs; compound-metric questions as a known gap.
Pitfall: inventing a "question length" heuristic that isn't in the code.

---

### S1-6. `AgentEntry` 为什么设计成协议无关的 sealed interface？协议差异被推到了哪里？

**参考答案**：两家协议回填工具结果的结构完全不同——OpenAI 要求每个 tool_call 配一条独立 `role:"tool"` 消息（带 `tool_call_id`）；Anthropic 要求把 `tool_result` 块塞进 **user** 消息（带 `is_error`）。如果循环直接维护 `List<ChatMessage>`，每加一家协议就要改循环本体。因此 `AgentEntry` 只表达语义：`System / User / AssistantToolCalls / ToolResults` 四种；所有格式翻译集中在 `AgentRequestBody`（**纯函数**，不碰网络），可 JVM 单测逐字段断言。同样思路：工具声明差异（OpenAI 的 `tools:[{type:function,function:{...}}]` vs Anthropic 的 `tools:[{name,description,input_schema}]`）、system 位置（消息 vs 顶层参数）也都在翻译层。循环本体因此永不 import 任何协议 DTO。

**解析**：
- **考察意图**：考防腐层（anti-corruption layer）设计能力；同时验证候选人知道两家协议的真实差异（很多人只会 OpenAI 一家）。
- **答题要点**：a) 四种语义条目与两家协议的映射表（能白板画出）；b) 纯函数翻译的意义：这类错误表现为"第二轮 400"或"模型不调工具"，真机排查成本极高，纯函数让它变成 JVM 断言；c) sealed interface 的穷尽 when 保证新增条目类型时编译器报错。
- **可能追问**："再加 Gemini 协议要改哪些文件？"（新增翻译分支 + 协议常量 + 流式解析分支，循环与工具零改动）；"为什么 schema 共用一份 JSON 字符串？"（`parametersJsonSchema` 协议无关，翻译时 `schemaObject` 解析成对象——传字符串给 parameters 会直接 400）；"AssistantToolCalls 里的 text 是什么？"（模型调用工具时附带的正文，回填时保留以维持消息序列完整）。
- **常见错误**：把 tool_result 回填说成两种协议一样（恰好是最不一样的地方）；不知道 Anthropic 的 system 是顶层参数。

**EN**: Q: Why is `AgentEntry` protocol-neutral, and where do protocol differences live?
A: OpenAI back-fills results as standalone `role:"tool"` messages; Anthropic requires `tool_result` blocks inside a **user** message (with `is_error`). `AgentEntry` models only semantics (System/User/AssistantToolCalls/ToolResults); `AgentRequestBody` — a pure function — does all translation, so the loop never imports protocol DTOs and translation is field-assertable in JVM tests.
Why asked: anti-corruption-layer design + real knowledge of both protocols.
Keys: mapping table; pure-function rationale; exhaustive `when` via sealed.
Follow-ups: adding a Gemini protocol; why schemas are shared JSON; role of assistant text on tool turns.
Pitfall: assuming both protocols back-fill tool results the same way.

---

## 1B 规划与推理 | Planning & Reasoning

---

### S1-7. 本项目的 ReAct 里，Thought / Action / Observation 分别对应什么代码实体？与教科书版 ReAct 有何差异？

**参考答案**：**Thought** = 模型每轮的正文（"我先查一下血糖"）与思考增量（`AgentEvent.Thinking`，含深度思考模型的 reasoning_content）；它不是显式的"Let me think"文本协议，而是自然发生在对话流里。**Action** = `AgentToolCall(id, name, argsJson)`，由流式 Function Calling 协议层给出（不是让模型输出 "Action: search[...]" 的文本再正则解析）。**Observation** = `AgentToolResultEntry(text, ok)`，截断到 1500 字符后以 `ToolResults` 条目回填。与教科书差异有三：① Action 走**原生 FC**而非文本解析——免去正则漂移与格式错误一类；② Thought 的"中间轮正文不进答案"由**代码**区分（工具轮正文并入 Thinking），不靠模型自觉；③ 停止条件不是模型输出 "Final Answer:" 标记，而是"本轮无 toolCalls"这一协议事实。

**解析**：
- **考察意图**：验证候选人理解 ReAct 的**本质是交错生成**而非某个 prompt 模板；区分"论文里的 ReAct"和"工程里的 ReAct"。
- **答题要点**：a) 三要素到代码实体的一一映射；b) 原生 FC 相对文本协议的工程优势（可靠性、流式友好）；c) 终止判定的协议化；d) 失败也是 Observation（`ok=false` + 原因文本，模型可据此换方式）。
- **可能追问**："为什么不用纯文本 ReAct？"（文本解析要处理转义、幻觉格式、多行；FC 由解码约束保证合法 JSON）；"Thought 会被记进历史吗？"（会——`AssistantToolCalls` 条目携带正文回填，保持消息序列完整）；"模型连 Thought 都不写直接调工具行不行？"（行，text 可空）。
- **常见错误**：把 Observation 说成只有成功结果（失败文本同样是 observation，这是 ReAct 纠错能力的来源）；说本项目用提示词让模型输出 "Thought:" 前缀。

**EN**: Q: Map Thought/Action/Observation to concrete entities in this codebase.
A: Thought = per-turn text + Thinking deltas; Action = `AgentToolCall` via native function calling (no regex protocol); Observation = `AgentToolResultEntry` (truncated to 1500 chars, failures included). Differences from textbook ReAct: native FC over text parsing; code-enforced routing of interim text to Thinking; termination judged by "no toolCalls this turn", not a "Final Answer:" marker.
Why asked: separates "knows the paper" from "built the loop".
Keys: entity mapping; FC reliability; protocol-level termination; failures as observations.
Follow-ups: why not text-protocol ReAct; is Thought persisted (yes).
Pitfall: claiming observations are only successful results.

---

### S1-8. MAX_TURNS=5、MAX_TOOL_CALLS=8、连续失败 2 次禁用——这些数字怎么定的？调大调小各会发生什么？

**参考答案**：三条护栏各对准一种具体失控方式：**MAX_TURNS=5**——超过 5 轮还没收敛的问题答案已不可靠，且每轮要重发工具 schema 与全部历史 observation，token 成本随轮数**超线性**增长；**MAX_TOOL_CALLS=8**——防"单轮并行调用 N 个 × 多轮"叠加爆预算（5 轮 × 每轮 3 个并行 = 15 次调用若无此闸）；**连续失败 2 次禁用**——模型极易对着同一个错参数反复重试（查错类型 → 报错 → 原参数重试），提示词拦不住，必须代码刹车。超限时的处理都是"软拒绝"：把原因作为失败 observation 回填（"已达到工具调用次数上限，请用已有数据作答"），让模型转向作答而不是硬崩。所有数字集中在 `AgentConfig`，注释里写明每个数字针对的失控方式。

**解析**：
- **考察意图**：考护栏（guardrail）思维：数字不是拍脑袋，每个上限对应一种失败模式；考"超限后用户体验"的细节意识。
- **答题要点**：a) 每个数字的失控场景；b) 软拒绝 vs 硬崩：超限回填可读文本维持 ReAct 纠错闭环；c) 集中配置 + 单测覆盖（`ReActLoopTest` 有专门的禁用与上限用例）；d) 调参方向：MAX_TURNS 调大 → 成本超线性涨、答非所问概率升；调小 → 复杂问题（读图+检索+告警）做不完。
- **可能追问**："为什么禁用阈值是 2 不是 3？"（健康问答轮数预算只有 5，浪费 3 轮在同一工具上不可接受；阈值与 MAX_TURNS 成比例）；"禁用后模型还调它怎么办？"（回填"该工具已因连续失败被禁用，请换个方式"）；"工具超时为什么本地 10s、读图 60s？"（读图是真实视觉网络请求，量级不同）。
- **常见错误**：只背数字不讲失控模式；说超限就抛异常终止（实际是回填文本继续循环）。

**EN**: Q: How were MAX_TURNS=5 / MAX_TOOL_CALLS=8 / disable-after-2-failures chosen?
A: Each targets a concrete failure mode: superlinear token growth per turn; parallel-calls × turns compounding; models retrying identical bad args (prompts can't stop that — code must). Breaches are soft-refused: a readable failure observation is fed back so the model pivots to answering. All live in `AgentConfig` with rationale comments and dedicated tests.
Why asked: guardrail thinking — numbers mapped to failure modes.
Keys: failure-mode mapping; soft refusal; centralized config; tuning direction.
Follow-ups: why 2 not 3; behavior when calling a disabled tool; timeout tiers.
Pitfall: saying the loop throws on breach.

---

### S1-9. 系统提示词规定"异常结论必须来自 get_alerts，不要自行判断"——为什么？模型自己判断参考范围的风险在哪？

**参考答案**：因为"是否异常"的判定涉及**性别分层参考范围**（血红蛋白/肌酐/尿酸男女不同）、**区间型比较符**（"<0.1 只有边界已越界才判异常"）、**180 天趋势窗与个人基线**——这些规则已经以确定性代码实现在异常检测模块（`DetectAnomaliesUseCase`），入库告警。让模型自行判断等于用模型的参数记忆重写一遍临床规则：它可能用合并区间忽略性别差异、把 "<0.1" 的正常结果判成异常、忽略单位不一致（nmol/L vs ng/mL）。所以闭环是：模型数值可谈，**异常结论只能引用 get_alerts 的返回**，get_alerts 又只渲染结构化字段（指标/数值/参考范围三要素）而非成品文案。这是"LLM 负责 Li（语言），代码负责 Logic"的边界划分。

**解析**：
- **考察意图**：考 LLM 与确定性代码的职责划分——医疗场景下这是安全设计，通用场景下这是"模型幻觉兜底"的通用模式。
- **答题要点**：a) 列出至少两类模型易错的规则（性别分层、区间比较符、单位不一致）；b) 确定性规则已代码化且被 `DetectAnomaliesUseCaseTest` 覆盖；c) 提示词软约束 + 工具描述硬引导（get_alerts 的 description 写明"结论必须来自本工具"）+ 数据通路（evidence 引用）三层配合；d) 承认残余风险：模型仍可能违抗提示词，最终靠展示层"数据依据"让用户可核对。
- **可能追问**："怎么验证模型真的遵守了？"（检查答案中的异常结论是否能在 evidence 中找到对应告警——可做自动断言，属评估方向）；"为什么 get_alerts 渲染不用现成的 description 文案？"（那是入库时固化的中文，问答需要的是可引用三要素）；"用户问一个没有告警的指标会怎样？"（工具返回明确文案"没有告警≠正常，无记录无法判断"——防止模型把'无告警'说成'一切正常'）。
- **常见错误**：答"因为模型会幻觉"就完事（要具体到哪类规则会错）；不知道单位不一致时标注会被跳过的细节。

**EN**: Q: Why must abnormality conclusions come from `get_alerts` rather than the model?
A: Abnormality depends on gender-specific ranges, interval comparators (e.g. "<0.1"), trend windows and personal baselines — all already implemented as deterministic code (`DetectAnomaliesUseCase`, unit-tested). Letting the model re-derive them from parametric memory invites merged-range errors, unit mismatches (nmol/L vs ng/mL), etc. The system prompt (soft), tool description (hard guidance), and evidence pipeline (verbatim citation) enforce the split: LLM does language, code does logic.
Why asked: responsibility split between LLM and deterministic code — safety-critical here.
Keys: concrete error classes; three-layer enforcement; residual-risk honesty.
Follow-ups: verifying compliance via evidence assertions; why get_alerts re-renders structured fields; "no alerts ≠ normal".
Pitfall: hand-waving "models hallucinate" without naming the rules.

---

## 1C 工具与函数调用 | Tools & Function Calling

---

### S1-10. `HealthTool.description` 是写给谁看的？"何时调用"为什么比"做什么"更重要？

**参考答案**：写给**模型**看的调用依据，不是开发者文档。模型决定是否调用工具，依据就是 description 与当前问题的匹配度。写"何时该调用"直接决定召回行为：`search_records` 的描述写"回答任何涉及具体数值的问题前都应先调用，不要凭记忆或推测回答"——因为对健康数据，一段编造的数值比一句"我不知道"危险得多；`get_alerts` 写"结论必须来自本工具"；`read_report_image` 写"仅当本轮确实附带图片时可用"。反面教材是只写功能（"检索健康记录"）——模型会认为"这个问题我知道"而跳过工具、凭参数记忆作答，且用户无从察觉数值是编的。四个工具的 description 都遵循同一公式：**能力一句 + 触发条件一句 + 禁止事项一句**。

**解析**：
- **考察意图**：考 prompt engineering 的实操颗粒度——工具描述是 Agent 系统里 ROI 最高的"prompt"，很多人把它当注释写。
- **答题要点**：a) description 的读者是模型；b) 触发条件写进描述 = 把路由规则下放到每次决策；c) 描述含糊的失败模式（跳过工具/乱调工具）；d) 结合本项目：没有附图时 `availableFor` 直接不暴露读图工具——描述管"该不该调"，注册表管"能不能调"，双保险。
- **可能追问**："怎么迭代工具描述？"（观测 llm_call_logs 中的失败与轮数、构造评测问题集对比不同描述的工具命中率与轮数）；"描述越长越好吗？"（不——每个工具的描述都随每轮请求重发，冗长描述烧 token 且互相干扰）；"中文还是英文描述？"（与领域词一致即可，本项目用中文因为指标词表是中文）。
- **常见错误**：把 description 说成 UI 提示；答不出"描述不好会发生什么"。

**EN**: Q: Who is `HealthTool.description` written for, and why does "when to call" matter more than "what it does"?
A: It's the model's calling rationale. Writing trigger conditions into the description routes each decision: "call before answering anything numeric; never answer from memory" exists because a fabricated lab value is far worse than "I don't know". Vague descriptions cause skipped or wrong tool calls that users can't detect. Registry handles "can it be called" (e.g., no image → tool not exposed); description handles "should it be called".
Why asked: tool descriptions are the highest-ROI prompts in an agent system.
Keys: model as reader; trigger-condition formula; failure modes; iteration via logs/eval sets.
Follow-ups: description length/token cost; language choice.
Pitfall: treating description as developer docs.

---

### S1-11. `ToolContext` 为什么把成员身份绑定在代码侧，而不是作为工具参数让模型传入？

**参考答案**：`ToolContext(member, question, imageBase64)` 由代码组装后传入每个工具，工具签名里根本没有 memberId 参数。理由：**身份是不可让渡的事实**——如果让模型在 `search_records` 参数里传成员，模型选错成员（多成员家庭下张冠李戴）就会把 A 的血糖答给 B，而用户完全无从察觉，这是健康场景里最恶劣的静默错误。代码绑定后这个错误类别被**类型系统消灭**：模型可以选错指标、选错日期（这些错了能被 observation 纠正），但物理上不可能跨成员。同时 `question` 字段作为兜底查询词传入（模型不给 query 时用它），`imageBase64` 同理——附图是否存在是系统事实，不是模型可声明的。

**解析**：
- **考察意图**：考"哪些参数永远不该交给模型"的权限意识——这是 Agent 安全设计的第一课，也对应通用安全里的 capability 思想。
- **答题要点**：a) 静默错误 vs 可纠正错误的分类（选错成员不可纠正、无感；选错词可被 observation 纠正）；b) 代码绑定 = 把错误类别从"提示词约束"升级为"类型系统约束"；c) 推广：任何"调用者身份/租户/会话"类参数都应如此（类比 Web 开发中 userId 从 session 取而非表单取）。
- **可能追问**："如果将来支持'帮妈妈查'跨成员问题怎么办？"（路由层显式解析并确认后构造对应 ToolContext，而不是给模型一个自由 member 参数；或提供 `list_members` 只读工具 + 人工确认）；"question 兜底会不会查歪？"（会，但 BM25 低分召回好过空 observation，且 strongTerms 闸门限制了弱词）。
- **常见错误**：只答"安全"不讲错误可纠正性分类；不知道 `question` 也是 ToolContext 字段。

**EN**: Q: Why is member identity bound in code (`ToolContext`) instead of passed as a model-supplied tool argument?
A: Identity is non-delegable. A model picking the wrong family member silently answers A's labs to B — the worst class of error in a health app. Binding it in code eliminates the error class by construction, while wrong metric names or dates remain correctable via observations. Same for `imageBase64` (a system fact) and `question` (fallback query).
Why asked: capability/permission thinking — what must never be model-controlled.
Keys: silent vs correctable errors; type-system over prompt constraints; session-userId analogy.
Follow-ups: cross-member questions; fallback-query trade-offs.
Pitfall: saying just "for security" without the error-taxonomy argument.

---

### S1-12. 流式 Function Calling 的分片累积：两家协议各自怎么分片？`ToolCallAssembler` 要处理哪些坑？

**参考答案**：两家协议的工具调用参数都是**跨多个 SSE 分片**逐段返回的，必须累积到流结束才是合法 JSON。**OpenAI**：`delta.tool_calls[]` 按数组下标 `index` 分组，`id`/`function.name` 通常只在首片出现，`function.arguments` 逐片追加；**Anthropic**：`content_block_start` 事件给出 `tool_use` 块的 `id`/`name`（仅一次），随后 `content_block_delta` 的 `input_json_delta.partial_json` 逐片补参数。`ToolCallAssembler` 用三个 `LinkedHashMap<Int,…>` 按 index 记账：ids / names / args(StringBuilder)，`build()` 时按 index 排序产出。四个坑：① id 缺失时兜底 `"call_$index"`；② args 为空/拼不完整时退化成 `"{}"`——让工具侧容错解析把"缺参数"当成一次**可纠正的失败**回填，而不是让整轮提问崩掉；③ 纯工具调用轮没有正文，必须把分片也算"有产出"，否则被误判为空响应而报错；④ 多个并行调用共享 index 空间，排序后顺序与模型声明一致。

**解析**：
- **考察意图**：这是流式 FC 最容易写错的地方，也是本项目面试素材里最"值钱"的细节题——没实际处理过分片的人编不出这些坑。
- **答题要点**：a) 两家分片格式的准确对比（能白板画出 SSE 序列）；b) "必须累积到流结束"的原因（半截 JSON 无法解析）；c) 四个坑各对应代码里的哪一行防御；d) 与非流式 FC 的差别（非流式一次拿完整数组，无需累积器）。
- **可能追问**："为什么要按 index 分组而不能按到达顺序合并？"（并行调用交错到达，index 是唯一归属依据）；"partial_json 可能是无效 UTF-8 断片吗？"（SSE 按行读、JSON 转义在字符串内，行级拼接安全；但直接按字节流读就要处理）；"流结束后发现 args 不是合法 JSON 会怎样？"（build 不校验 JSON——工具侧 `ToolArgs.parse` 返回 null 走失败回填，故意不在累积器里抛）。
- **常见错误**：以为 arguments 是一次性给的；把 OpenAI 的 `index` 和 Anthropic 的 `index`（block 序号）混为一谈却说不清；不知道空 args 的 `{}` 兜底是有意为之。

**EN**: Q: How do both protocols fragment streaming tool calls, and what does `ToolCallAssembler` guard against?
A: OpenAI: `delta.tool_calls[]` grouped by `index`, id/name on the first fragment, arguments appended piecewise. Anthropic: `content_block_start` carries id/name once; `input_json_delta.partial_json` appends. The assembler keeps three `LinkedHashMap`s keyed by index and builds sorted calls at stream end. Guards: missing id → `"call_$index"`; empty/broken args → `"{}"` so tool-side parsing yields a correctable failure; fragment-only turns count as "produced" to avoid false empty-response errors.
Why asked: the single most error-prone part of streaming FC — unreproducible without hands-on experience.
Keys: side-by-side fragmentation; accumulate-to-end rationale; four guards.
Follow-ups: why index-keyed; JSON validity deliberately not checked here.
Pitfall: assuming arguments arrive in one piece.

---

### S1-13. `AgentRequestBody` 做双协议翻译：请列出两家协议在工具调用上的四点结构差异。

**参考答案**：① **工具声明**：OpenAI 是 `tools:[{type:"function", function:{name, description, parameters}}]`，且 `parameters` 必须是 **JSON 对象**（传字符串直接 400）；Anthropic 是 `tools:[{name, description, input_schema}]`。② **助手请求调用**：OpenAI 把 `tool_calls[]` 放在 assistant 消息上；Anthropic 用 content 数组里的 `tool_use` 块（`input` 是对象不是字符串）。③ **结果回填**：OpenAI 每个调用要一条独立 `role:"tool"` 消息 + `tool_call_id`（缺一条 400）；Anthropic 把 `tool_result` 块放进 **user** 消息，用 `tool_use_id` 关联且支持 `is_error` 标记。④ **system 提示**：OpenAI 是普通 system 消息；Anthropic 是顶层 `system` 参数，翻译时要从条目里过滤掉。附加细节：最后一轮 tools 为空时**整体省略字段**（空数组部分供应商判非法）；Anthropic 启用扩展思考时要求 temperature=1，被 400 拒后降级为不传 temperature 重发一次。

**解析**：
- **考察意图**：验证真实对接过多家协议。这四点差异每一处错都会表现为"第二轮 400"或"模型永远不调工具"，是流式 Agent 的协议基础题。
- **答题要点**：a) 四点差异准确复述（最好画表）；b) `schemaObject` 把共享 JSON 字符串解析成对象再嵌入（一份 schema 服务两家）；c) 翻译层是纯函数 → 单测逐字段断言（`ReActLoopTest`/专门的结构测试）；d) temperature 降级重发是协议版本兼容的现实例子。
- **可能追问**："tool_call_id 对不上会怎样？"（OpenAI 报 400——每个 tool_calls 元素必须有配对 tool 消息；本项目 `ToolResults` 条目逐条生成保证配对）；"为什么 Anthropic 要把结果放 user 消息？"（它的消息角色只有 user/assistant，工具结果本质是'外部世界对 assistant 的回应'，语义上属于 user 侧）；"Gemini 的工具格式了解吗？"（functionDeclarations + functionResponse parts，思路同 Anthropic 更近）。
- **常见错误**：把 parameters 写成字符串（真 400 过的细节）；把 tool_result 说成 assistant 消息；不知道 `is_error`。

**EN**: Q: List the four structural differences between the two protocols for tool calling.
A: (1) Tool declarations: OpenAI `type:"function"+parameters` (must be an object, not a string); Anthropic `name/description/input_schema`. (2) Assistant side: OpenAI `tool_calls[]` on the message; Anthropic `tool_use` content blocks. (3) Back-fill: OpenAI one standalone `role:"tool"` message per call (`tool_call_id`, missing → 400); Anthropic `tool_result` blocks inside a **user** message with `is_error`. (4) System prompt: message vs top-level `system` param. Plus: omit `tools` entirely when empty; temperature=1 requirement for extended thinking with a degradation retry.
Why asked: proof of real multi-provider integration.
Keys: the four-row table; shared schema parsed per protocol; pure-function testing.
Follow-ups: id-pairing failures; why tool_result is user-side; Gemini's format.
Pitfall: sending schema as a string; wrong role for tool results.

---

### S1-14. `ToolArgs` 为什么"解析失败一律返回 null/空集合而不抛异常"？这不是掩盖错误吗？

**参考答案**：不是掩盖，而是**错误分类学**：模型给出的 JSON 经常"差一点"——缺字段、类型不符（数字给了字符串）、数组里混 null。这些是**模型可以被纠正的行为**，不是需要中断流程的异常。所以 `ToolArgs.parse/str/int/strList` 全部容错返回，工具层把"缺参数"转成 `ToolResult.fail("缺少 metric_type。请传入…")` 作为 observation 回填——模型看到原因后下一轮改对参数，ReAct 闭环继续。如果解析层抛异常：轻则整轮提问失败（用户看到报错），重则模型对着同一个异常反复重试烧轮数。配套的哲学在工具接口注释里：**实现方必须自行容错**（内部转 fail），循环外层再兜一层 try/catch 保证任何异常不掀翻循环——双层防御。而真正不可恢复的系统性错误（网络断、DB 坏）走的是另一条路：抛给上层整体降级。

**解析**：
- **考察意图**：考错误处理分层：可纠正错误（observation 回填）vs 系统性错误（降级）vs 编程 bug（让它崩、修掉）。Agent 系统里把这三层混在一起是最常见的设计事故。
- **答题要点**：a) 三层错误分类与各自的处理路径；b) "fail 也是 observation"——回填原因文本让模型自纠；c) 双层防御（工具内 catch + 循环兜底 catch）；d) `CancellationException` 在两层都被**原样重抛**——取消不是错误（见 S1-25）。
- **可能追问**："fail 文本里该写什么？"（可操作的下一步：'缺少 metric_type，请传入指标类型或中文名'、'可以先调用 search_records 看看有哪些指标'——把纠错指令写给模型）；"参数错重试会不会死循环？"（不会——轮数与调用次数护栏 + 连续失败禁用）；"工具内 runCatching 会不会吞 CancellationException？"（会——所以代码里专门 `catch (e: CancellationException) throw e` 在 runCatching 之外/之内处理，这是协程经典坑）。
- **常见错误**：认为抛异常更"健壮"（恰恰相反——ReAct 的纠错能力依赖失败文本回流）；不知道 runCatching 会吞取消。

**EN**: Q: Why does `ToolArgs` return null/empty instead of throwing on bad model args? Isn't that hiding errors?
A: It's error taxonomy: malformed-but-plausible args are **correctable model behavior** — feed the reason back as a failed observation and the model retries with better args next turn. Throwing would either kill the turn or waste rounds. Three layers exist: correctable errors → observation back-fill; systemic failures → degrade upstream; programming bugs → crash loudly. `CancellationException` is re-thrown at every layer — cancellation is not an error.
Why asked: layered error handling is where agent designs most often go wrong.
Keys: taxonomy; actionable fail texts; double defense; runCatching swallowing cancellation.
Follow-ups: what makes a good fail message; retry loops vs budgets.
Pitfall: claiming exceptions are "more robust" here.

---

### S1-15. 四个只读工具的分工与两个反直觉设计：为什么 `get_alerts` 不能复用 `DetectAnomaliesUseCase`？为什么 `read_report_image` 在多轮下必须做成工具？

**参考答案**：**get_alerts**：`DetectAnomaliesUseCase.invoke()` 会**写库**（createAlert + 7 天去重 + 严重度穿透）。把它当工具用，意味着用户只是问了一句"我有什么问题吗"，系统就凭空生成一批告警进预警页、可能触发通知——"用查询的姿势触发了写操作"，语义就是错的。检测由每日后台任务与录入流程负责，问答只读已判定结果。这也是全项目"**工具只读**"铁律的守门案例。**read_report_image**：单轮场景下图片内联进 user message 更简单更省；但多轮循环里 base64 图片会**随每一轮重发**，成本按轮数放大。工具形式把图片**一次**转成文本，后续轮次上下文保持纯文本——这是它存在的唯一理由（代码注释原话：如果将来只做单轮问答，应当砍掉本工具改回内联）。另外两个工具：`search_records` 双路径（metric_types 直查 / query 走 BM25，与快路径同一实现避免两路结果不一致）；`get_reference_range` 是四工具里唯一零 IO 的（纯查表），服务"没有任何记录的参考范围问题"。

**解析**：
- **考察意图**：考工具集设计的两个深层问题——副作用治理与多轮成本模型。能讲出"为什么这个工具存在"比"有哪些工具"高一档。
- **答题要点**：a) 查询≠检测：语义层面的读写分离；b) 多轮 token 成本模型：图片 base64 每轮重发 vs 一次性转文本；c) 工具间一致性（search_records 与快路径共用 QaRetriever）；d) 工具的"存在必要性审计"（get_reference_range 的空记录场景）。
- **可能追问**："将来要加写工具（如'帮我记录血压'）怎么办？"（写工具单独白名单 + 参数确认回显 + 与只读工具分域声明；医疗写入建议人工确认一步）；"读图工具超时为什么单独 60s？"（真实视觉网络请求，与本地查表不是一个量级）；"图片文字化会不会丢信息？"（会——所以提示词要求原文照录结论性文字，focus 参数支持定向重读）。
- **常见错误**：认为工具越多越好（每轮 schema 重发，工具多反而降准确率）；没想过图片内联的多轮成本。

**EN**: Q: Why can't `get_alerts` just call `DetectAnomaliesUseCase`, and why must `read_report_image` be a tool at all?
A: The use case **writes** to the DB (alert creation + dedup + severity escalation) — answering a question must never mutate data or trigger notifications; detection belongs to background/ingest flows, Q&A reads results. That's the read-only rule in action. The image tool exists because in a multi-turn loop an inline base64 image is re-sent every turn; converting image→text once keeps later turns text-only (the code says: for single-turn only, delete this tool and inline the image).
Why asked: side-effect governance and multi-turn cost modeling.
Keys: read/write separation; per-turn resend cost; tool-existence auditing; tool consistency via shared QaRetriever.
Follow-ups: adding write tools safely; information loss in image→text.
Pitfall: "more tools = more capable".

---

## 1D 记忆与上下文管理 | Memory & Context Management

---

### S1-16. 本项目的"四层上下文预算"分别是什么？各自防哪种爆炸？

**参考答案**：四层从外到内：① **`recordsOverview`（范围概览）**——Agent 路径只给"共 N 条记录，覆盖血糖(3)、LDL(4)…"，**只给范围不给明细**，明细必须靠工具去取，否则等于把全量摘要塞回上下文、Agent 就白做了；② **`SUMMARY_CHAR_BUDGET=6000`（快路径全量/检索摘要）**——49 项指标 × 每项 10 条会一次性超出窗口，超预算即停并显式标注"已省略"；③ **`OBSERVATION_CHAR_BUDGET=1500`（单条 observation）**——一次 search_records 返回多项 × 多条就能撑爆，`truncateObservation` 截断并标注省略字符数；④ **`ANSWER_MAX_TOKENS=2048`（输出上限）**——流式下发 `Truncated` 软告警，调用方在答案末尾追加"因长度上限被截断"提示而不是抛异常。另有两处辅助设计：promptChars 统计**排除图片 base64**（否则统计被淹没）；Agent 每轮重发全部历史，所以 ②③ 的预算直接决定总成本。

**解析**：
- **考察意图**：考上下文工程的空间感——窗口是稀缺资源，每一层都要回答"谁在往里塞东西、塞爆了谁负责"。这是 Agent 岗的高频深题。
- **答题要点**：a) 四层各自的对象（输入概览/输入明细/中间结果/输出）与数值；b) "只给概览不给明细"与 Agent 存在意义的因果（明细靠工具取，Agent 才有存在价值）；c) 截断必须显式（见 S1-17）；d) 流式截断的软告警处理（非流式才是异常）。
- **可能追问**："为什么 6000 字符而不是 token 数？"（字符估算保守且实现简单，中文 1 字≈1~2 token，足够防溢出；精确化方向是 tokenizer 计数）；"observation 1500 会不会截掉关键字段？"（检索结果按相关度排序、头部最相关，且截断文本明示省略量，模型可追加更精确查询）；"多轮下来上下文还会无限涨吗？"（有界：轮数≤5、每轮 observation≤1500、无附图——上限可静态算出）。
- **常见错误**：只知道"有截断"不知道四层各管一段；把 ANSWER_MAX_TOKENS 与输入预算混为一谈。

**EN**: Q: What are the four context-budget layers and what does each prevent?
A: (1) `recordsOverview`: scope only ("N records covering glucose(3), LDL(4)…"), never details — details must come via tools or the agent is pointless. (2) `SUMMARY_CHAR_BUDGET=6000` for fast-path summaries with explicit omission notes. (3) `OBSERVATION_CHAR_BUDGET=1500` per tool result, truncated with a stated omitted-count. (4) `ANSWER_MAX_TOKENS=2048` — streaming emits a soft `Truncated` chunk, not an exception. Bonus: prompt char stats exclude image base64.
Why asked: context-window spatial reasoning.
Keys: layer-by-layer ownership; overview-vs-details causality; explicit truncation; bounded total context.
Follow-ups: chars vs tokens; does 1500 cut key fields; upper bound of total context.
Pitfall: knowing "there's truncation" but not the layering.

---

### S1-17. Observation 截断时为什么要显式标注"省略了多少字符"，而不是静默砍掉？

**参考答案**：`truncateObservation` 的实现：超限时 take(1500) 再追加"…（因上下文预算省略约 N 字符）"。两个理由：① **模型需要知道"还有更多"**——静默截断后，截断点恰好停在半条记录上，模型会把残缺数据当成完整事实（例如最后一条血糖只显示数值没显示单位）；带上省略标注，模型知道数据不完整，可以选择追加更精确的查询（缩小 metric_types 或减小 limit）而不是基于残缺数据下结论；② **可调试性**——排查"模型为什么说数据不全"时，observation 文本自带解释。同理快路径摘要的"…其余记录因上下文预算已省略"、解析结果的"省略标注"都是同一原则：**对信息做手脚可以，但要让上下文里的读者（模型）和屏幕前的用户都知道**。

**解析**：
- **考察意图**：细节意识题。截断策略人人会写，"截断后语义完整性"的考虑区分熟手与生手。
- **答题要点**：a) 残缺数据比没有数据更危险的论证；b) 标注是给模型的"元信息"——触发更精确的二次查询而不是错误结论；c) 一致性原则：模型侧标注与 UI 侧省略提示同一风格；d) 反例：静默截断 + 模型自信作答 = 用户无从察觉的数据缺失。
- **可能追问**："按行截断还是按字符截断更好？"（按行保记录完整性更优，当前按字符是实现简单；改进方向）；"为什么标注'约'N 字符？"（字符数即差值，精确值也行，措辞保守是习惯）；"答案区怎么处理截断？"（truncated 标志一路传到 UI，追加用户可读的提示行）。
- **常见错误**：只答"用户体验好"（核心读者是模型）；没想过残缺记录的具体危害例子。

**EN**: Q: Why must truncated observations announce how much was omitted?
A: A silently truncated stream ends mid-record, and the model treats the fragment as complete fact. The appended "…(≈N chars omitted)" tells the model data is incomplete, prompting a more precise follow-up query instead of a confident wrong conclusion; it also self-documents for debugging. Same principle on the UI side: the user sees an explicit omission note.
Why asked: truncation semantics separate seniors from juniors.
Keys: incomplete > missing is dangerous; meta-information triggers better queries; consistent style everywhere.
Follow-ups: line-based truncation; propagating `truncated` to UI.
Pitfall: justifying it purely as "better UX".

---

### S1-18. 为什么用 BM25 而不上向量检索？什么信号出现时才该引入向量？

**参考答案**：四条理由：① **数据特性**：个人健康记录量级在千条以内，体检指标是强关键词域——问"血糖"就该召回血糖记录，词与文档词面几乎重合，向量语义泛化用不上；② **隐私约束**：云端 Embedding API 会把健康数据送第三方，破坏"数据不上传"承诺；上向量就必须端侧小模型（如 bge-small-zh-v1.5），复杂度陡增；③ **可解释性**：BM25 得分可逐词解释，向量分数是黑盒——引用溯源场景需要可解释；④ **零依赖**：纯 Kotlin 实现内存索引，每次提问毫秒级建索引，无模型加载、无迁移。**引入向量的信号**（评估文档里写明的结论）：同义改写多到关键词覆盖不住（"维 D 不够"vs"25-羟基维生素D 缺乏"已部分靠别名表解决）、语料到万条级、或引入叙述性文本（出院小结等长文——Embedding 评估的结论是"叙述文本需要向量，且需先落地 ParseResult.rawText 落库"）。届时方案：端侧 bge-small-zh-v1.5 + RRF 混合，千级语料不需要向量库。

**解析**：
- **考察意图**：考检索选型的第一性原理。很多候选人条件反射"RAG=向量库"，能反着论证的是真想过的。
- **答题要点**：a) 数据特性决定技术（强关键词域、千条量级）；b) 隐私与依赖成本；c) 已有的低成本替代：106 条别名表 + 双字切分已解决大部分同义问题；d) 明确的"何时升级"信号与升级路径（端侧小模型 + RRF，而非云端 API）。
- **可能追问**："BM25 的公式写一下？"（见 S2-16）；"别名表和向量什么关系？"（别名表是人工先验的'确定性语义'，向量是学习的'统计语义'，前者免费可靠先用）；"RRF 是什么？"（Reciprocal Rank Fusion，多路召回按排名倒数融合，免调分数尺度）；"SQLite FTS 为什么不用？"（要动 schema 迁移，千条内存索引毫秒级，数据涨了再上）。
- **常见错误**：说"数据少所以 BM25"就停（量级只是原因之一，域特性才是主因）；不知道本项目已有别名表这层"穷人的语义检索"。

**EN**: Q: Why BM25 instead of vector retrieval, and what signals would justify vectors?
A: Thousands of records in a strong-keyword domain (queries literally contain the metric names), cloud embeddings would break the no-upload promise, BM25 scores are explainable for citation, and a pure-Kotlin in-memory index is millisecond-cheap. Upgrade signals: synonym-heavy phrasing beyond the 106-alias table, 10k+ records, or narrative documents (the embedding study concluded: vectors needed only for narrative text, via on-device bge-small-zh-v1.5 + RRF; a vector store is unnecessary at this scale).
Why asked: first-principles retrieval selection.
Keys: domain properties; privacy cost; existing alias-table "poor man's semantics"; explicit upgrade path.
Follow-ups: BM25 formula; RRF; why not SQLite FTS.
Pitfall: reflexively equating RAG with vector DBs.

---

### S1-19. Agent 路径的"数据依据"为什么逐字复用工具返回原文，而不是重新组织一遍？引用闭环是怎么形成的？

**参考答案**：`Completed` 事件把 `evidence`（`search_records`/`get_alerts` 的返回原文）带出循环，`buildAgentReferences` 直接拼进答案尾部。不重新组织的理由：**模型看到的就是这段文本**——答案里引用的数字与依据里列出的数字必然逐字一致；另起一条数据通路（重新查库格式化）迟早出现两边口径不一致（比如一条用性别分层参考范围、一条用合并区间），而用户是**拿依据核对答案的**，依据本身错了整个可信度设计就崩了。引用闭环：SYSTEM_PROMPT 第 5 条要求"引用具体数值时标注来源记录编号（如 [2]）"→ 工具返回渲染时就带 `[1] [2]` 编号 → 模型的 [n] 与依据的编号同源 → 快路径同样（检索摘要带 [n]，`requireCitation` 开启）→ 用户可拿任何数字回溯到具体记录。

**解析**：
- **考察意图**：考"可溯源性"的系统设计——健康场景的杀手锏特性，也是通用 RAG 的 groundedness 工程化。
- **答题要点**：a) 单一数据通路原则（single source of truth 的引用版）；b) 编号同源：上下文里的 [n] 与依据区的 [n] 同一来源渲染；c) 快路径与 Agent 路径行为对齐（两条路径都有依据，且都在答案尾部）；d) evidence 去重（distinct）与空依据的处理（不显示依据区）。
- **可能追问**："模型编了个不存在的 [7] 怎么办？"（展示层编号只有 1~N，越界编号用户一眼可辨；可加自动校验——评估方向）；"依据会不会太长撑爆答案？"（依据不进模型上下文第二次——它只是展示层拼接；快路径有 6000 预算兜底）；"为什么 evidence 只收两个工具？"（get_reference_range 是静态知识不算数据依据，read_report_image 是图片内容——与告警/记录两类结构化数据性质不同）。
- **常见错误**：以为依据是模型生成的（恰恰相反，是本地渲染）；不知道快路径也有同一套编号闭环。

**EN**: Q: Why is the "evidence" section verbatim tool output rather than re-formatted data?
A: The model answered from exactly that text — verbatim reuse guarantees the numbers a user cross-checks are identical to what the model saw. A second data path would eventually diverge (e.g., gender-specific vs merged reference ranges) and destroy the whole trust design. The citation loop: tool output renders with `[n]`, the system prompt requires citing those numbers, and the fast path does the same via `requireCitation` — every number traces back to a record.
Why asked: grounding/citation engineering, the killer feature in this domain.
Keys: single data path; number co-rendering; parity across both paths; dedup.
Follow-ups: hallucinated [7]; evidence length; why only two tools contribute evidence.
Pitfall: thinking the model generates the evidence section.

---

## 1E 多智能体与降级 | Multi-Agent & Degradation

---

### S1-20. 这个项目为什么不需要多智能体？什么信号出现时才值得拆分？

**参考答案**：多智能体的适用前提是**任务可并行分解、子任务需要不同专长/上下文、或需要相互校验**。本项目问答是单线程推理链：读图（可选）→ 检索 → 查告警 → 汇总作答，工具间有依赖、数据规模小（单成员千条记录）、无领域切换。引入多 Agent 只会：每跳一次 Agent 多一次全套请求开销、上下文传递需要序列化协议、失败模式从"一条链"变成"一张图"（调试成本爆炸）。**值得拆分的信号**：① 任务出现天然并行段（如同时分析 5 份不同报告）；② 子任务需要不同模型档位（视觉重模型 vs 文本轻模型——本项目用"三档视觉路由"在**单 Agent 内**解决了，见追问）；③ 需要角色对抗校验（生成者/批评者）；④ 单上下文窗口装不下任务状态。对应通识：多 Agent 的成本是通信协议 + 状态一致性 + 调试复杂度，收益必须是结构性的。

**解析**：
- **考察意图**：考"少即是多"的架构判断。面试官（尤其做过 Agent 平台的）最警惕无脑上多 Agent 的候选人。
- **答题要点**：a) 判定标准（可并行性/专长差异/校验需求）逐条对照本项目；b) 用单 Agent 内的路由解决"伪多 Agent 需求"（视觉三档路由：问答模型本身支持视觉 → 直接用；否则回退解析服务视觉模型；都没有 → 明确报错提示而非静默丢图）；c) 拆分信号清单；d) 若被追问设计：明确编排者-执行者拓扑、消息 schema、失败传播。
- **可能追问**："视觉三档路由展开讲讲？"（`resolveVisionConfig`：QA 供应商的模型在 visionModels 里 → 直接用（QA_PROVIDER）；否则用解析服务的视觉模型兜底（PARSE_SERVICE）；都没有 → 抛可操作错误。且 `qaVisionRoute()` 先探测一次，回答来源里如实标注"图片由 XX 读取"，防止用户以为换供应商是 bug）；"多 Agent 框架了解吗？"（AutoGen 对话式、CrewAI 角色式、LangGraph 图状态机）。
- **常见错误**：把"单 Agent + 多工具"说成多智能体；说不出多 Agent 的真实成本。

**EN**: Q: Why is this project single-agent, and what signals would justify splitting?
A: The reasoning chain is sequential (read image → retrieve → alerts → synthesize) with small data and no domain shifts. Multi-agent adds per-hop full-request overhead, a context-serialization protocol, and graph-shaped failure modes. The "pseudo multi-agent need" (different model tiers for vision vs text) is solved inside one agent via the 3-tier vision routing (QA provider if vision-capable → parse service fallback → actionable error, with honest source labeling). Split signals: parallelizable subtasks, per-subtask model tiers, adversarial verification, context overflow.
Why asked: guards against reflexive multi-agent design.
Keys: decision criteria; single-agent routing solutions; split-signal list.
Follow-ups: vision routing details; framework landscape.
Pitfall: calling "one agent + many tools" multi-agent.

---

### S1-21. 讲一遍降级链路：Agent 失败后发生什么？为什么"没有证据就回退本地规则引擎"比"把半截 observation 给用户"好？

**参考答案**：三层降级矩阵（`QARepositoryImpl.askStream` 的 when 分支）：① **Agent 路径**（泛化问题 + 已配置 LLM + 有数据）→ 异常时 `answerText` 为空 → **本地规则引擎** `LocalQaEngine.answer()`，并在 sources 里明确标注"本地规则引擎（多步分析未能完成，以下为离线兜底回答）"；② **快路径**（LLM 流式）→ 失败但**已有部分回答上屏**时，不丢弃已生成内容，末尾追加"回答在生成过程中中断"说明——用户已经读到了，清空重来或静默截断都更糟；完全没有产出才回退本地引擎（标注"AI 服务暂不可用"）；③ **根本没配置 / 无记录** → 直接本地引擎。为什么不让用户看半截 observation：observation 是中间态（一堆 JSON 串和截断文本），对用户是噪音；本地引擎虽然规则化，但给出的是**结构完整的回答**（最近记录 + 均值 + 趋势方向），且来源标注诚实——宁可弱而完整，不可强而残缺。另外降级策略只放一处（`LlmClient.turn` 不做降级只抛出），避免"两处降级互相打架"。

**解析**：
- **考察意图**：考失败设计的"用户体验完整性"——大部分候选人只会说"try-catch 回退"，讲不出部分成功、来源标注、降级位置这三层。
- **答题要点**：a) 三分支矩阵按"配置×数据×部分成功"划分；b) 部分成功的处理原则（已上屏内容不丢 + 显式中断说明）；c) 降级必须诚实标注来源（防止用户把规则引擎输出当成模型结论——医疗场景的信任问题）；d) "降级策略只应有一处"的架构理由。
- **可能追问**："有图但没视觉模型怎么办？"（第三种分支：明确告知"图片不会被发送"+ 两条修复路径，绝不静默按纯文本回答——健康场景里'以为模型看过图'的误会代价极高）；"本地引擎的实现？"（关键词检测指标类型 → 最近 3 条 + 均值 + 与前值比较的方向，纯规则零依赖）；"降级后历史怎么落库？"（同样落 QAHistory，sources 字段记录真实来源——历史里能看出那次是降级）。
- **常见错误**：说"失败就重试"（重试在 LLM 层已完成 3 次，到 repository 层是最终裁决）；不知道部分上屏内容的处理原则。

**EN**: Q: Walk through the degradation chain when the agent fails.
A: A three-branch matrix: agent path fails → local rule engine with an honest "multi-step analysis failed" source note; fast path fails mid-stream → keep the already-shown text, append an interruption notice (never discard what the user has read); unconfigured/no data → local engine directly. Never show raw observations — they're intermediate noise; a complete rule-based answer with honest labeling beats a strong-looking fragment. Degradation lives in exactly one place (`LlmClient.turn` only throws).
Why asked: user-experience completeness under failure.
Keys: matrix dimensions; partial-success principle; honest source labeling; single degradation site.
Follow-ups: image-but-no-vision-model branch; local engine internals; history recording.
Pitfall: answering "just retry" (retries already happened at the LLM layer).

---

## 1F 错误处理与重试 | Error Handling & Retries

---

### S1-22. `withRetry` 的设计：指数退避为什么用 `delay` 而不是 `Thread.sleep`？哪些错误值得重试？抖动防什么？

**参考答案**：`withRetry(maxAttempts=3, canRetry, block)`：每轮先 `currentCoroutineContext().ensureActive()`（协程已取消立刻退出——阻塞读被 `call.cancel()` 掐断后以 IOException 结束，在 `isRetryable` 眼里"值得重试"，少了这道判断会白等一轮退避）；失败后若 `attempt < max && isRetryable(e) && canRetry()` 则 `delay((500L shl (attempt-1)) + Random.nextLong(0,250))` 后重试。**delay 而非 sleep**：调用方在协程里，`Thread.sleep` 阻塞的是 `Dispatchers.IO` 的工作线程（池子就 64 个），挂起不占线程且能被取消；**抖动（0~250ms）**：防止多个并发请求同时失败、同时退避、同时重试形成同步风暴（thundering herd）；**isRetryable**：仅 `IOException`（含超时/连接类）、HTTP 429、5xx 值得重试——其余 4xx 是配置错误（Key 错、模型名错），重试没有意义；`CancellationException` 原样重抛。解析场景刻意只用 2 次：readTimeout 120s，3 次超时会让用户在解析页干等 6 分钟以上——重试策略必须看场景的用户等待模型。

**解析**：
- **考察意图**：考重试的完整心智模型：什么重试（错误分类）、怎么等（退避+抖动）、何时放弃（次数+场景）、取消怎么办（ensureActive）。四问缺一不可。
- **答题要点**：a) delay/sleep 的协程语义差异（线程占用、可取消性）；b) 抖动的同步风暴论证；c) 429/5xx/IO vs 4xx 的分类逻辑（"值得重试"= 概率性恢复，"不值得"= 确定性失败）；d) 场景化 maxAttempts（QA 3 次、解析 2 次的用户等待计算）。
- **可能追问**："429 要不要读 Retry-After 头？"（应该，当前实现没读是已知改进点——诚实承认）；"退避为什么 500ms 起步？"（LLM 供应商限流恢复通常秒级，500ms→1s→2s 三次总等待 ~3.5s 可接受）；"重试期间 attempts 怎么记账？"（每次尝试 +1，最终随成败一起落 llm_call_logs，重试不改行只累计）。
- **常见错误**：不知道 ensureActive 的存在（被取消的协程继续重试是经典 bug）；把 4xx 也加进重试列表。

**EN**: Q: Inside `withRetry` — why `delay` not `Thread.sleep`, which errors are retryable, what does jitter prevent?
A: `ensureActive()` guards each round (a cancelled coroutine resuming as IOException must not burn another backoff); `delay` suspends instead of blocking an `Dispatchers.IO` worker and stays cancellable; jitter (0–250ms) prevents synchronized retry storms; retryable = IOException / 429 / 5xx only — other 4xx are deterministic config errors; `CancellationException` rethrown. Parse scene uses 2 attempts because 3×120s timeouts would make users wait 6+ minutes.
Why asked: the complete retry mental model.
Keys: suspension semantics; thundering herd; probabilistic vs deterministic failures; per-scene budgets.
Follow-ups: Retry-After header (admitted gap); backoff base; attempts accounting.
Pitfall: retrying 4xx; missing ensureActive.

---

### S1-23. 流式为什么"首个增量到达后不再重试"？这个闸门在代码里怎么实现？

**参考答案**：`executeStreamCore` 里 `withRetry(canRetry = { !received })`，`received` 在收到任何 `Answer`/`Thinking` 增量时置 true，每次新尝试开始时归零（"能走到下一轮说明上一轮没有任何增量上屏"）。理由：重试意味着**重新发起完整请求**，如果第一轮已把 300 字上屏，重试会从头生成——用户屏幕上出现重复内容，且两轮输出不可能逐字节一致（采样温度 0.3），内容错乱无法自动修复。所以规则是：**零增量失败可以重试（对用户无感），有增量后失败宁可断掉**（走"保留已生成部分 + 中断说明"的降级，见 S1-21）。配套细节：`completionChars` 计数也随尝试归零，保证日志统计口径是"最后一次成功尝试"的补全长度。

**解析**：
- **考察意图**：考流式与重试的交互——这是非流式后端开发转 Agent 开发最容易踩的盲区（HTTP 幂等直觉在这里失效）。
- **答题要点**：a) 重发的重复输出问题（两次生成不一致）；b) canRetry 闸门的实现位置与归零语义；c) 与降级链路的衔接（有增量失败 → INTERRUPTED_NOTICE 而非重试）；d) 对比：非流式路径可以放心重试（`chatCompletion` 无此闸门）。
- **可能追问**："HTTP 状态码错误（400）时能重发吗？"（能——还没收到任何内容；Anthropic temperature 降级就利用了这一点，在 `runStreamOnce` 里重发一次）；"Thinking 增量算'已上屏'吗？"（算——用户已经看到了思考流，重发同样会重复）；"能做断点续传吗？"（供应商 API 无此能力，唯一途径是本地把已收增量拼进下一轮 prompt——复杂度不值当）。
- **常见错误**：把流式重试当成普通重试；不知道 `received` 归零的必要性（不归零则第一次失败后永远不能重试）。

**EN**: Q: Why no retries after the first streamed delta, and how is that gate implemented?
A: `withRetry(canRetry = { !received })`; `received` flips true on any Answer/Thinking delta and resets at the start of each new attempt. Retrying re-issues the full request — already-shown text would be duplicated, and two samples never match exactly. Zero-delta failures retry invisibly; post-delta failures degrade (keep partial text + notice). Non-streaming paths retry freely — the asymmetry is deliberate.
Why asked: streaming × retry interaction; HTTP idempotency intuition fails here.
Keys: duplication argument; gate placement and reset semantics; handoff to degradation; Anthropic temperature retry still OK (no content yet).
Follow-ups: does Thinking count (yes); resume-from-partial (not worth it).
Pitfall: forgetting the per-attempt reset.

---

### S1-24. 工具连续失败两次禁用：为什么必须由代码刹车？禁用后模型再调用会怎样？

**参考答案**：模型对失败工具的行为是出了名的"撞墙"：传错参数 → 收到报错 → **用几乎相同的参数重试** → 再报错。提示词写"不要重试同样参数"是软约束，实测拦不住。代码刹车：`failures[name]` 计数，成功清零，达到 `MAX_TOOL_CONSECUTIVE_FAILURES=2` 就把工具加进 `disabled` 集合——效果有三：① 下一轮 `registry.availableFor(context).filterNot { it in disabled }` **不再向模型声明该工具**（协议层消失）；② 模型若仍硬调（记住历史里的调用），回填"工具 X 因连续失败已被禁用，请换个方式"的失败 observation；③ 轮数预算不再被无效重试烧掉。连续失败的定义是"累计 2 次失败未成功穿插"——成功一次即清零，所以偶发失败不会误杀。有专门单测：`同一工具连续失败两次后被禁用并不再出现在工具声明里`。

**解析**：
- **考察意图**：考"LLM 行为不能靠提示词治理"的工程觉悟，以及禁用的三重效果（声明移除/硬调兜底/预算保护）是否理解到位。
- **答题要点**：a) 撞墙行为的现实观察；b) 三重效果的层次（协议层消失是主防御，回填文本是纵深，预算保护是目的）；c) 清零语义（连续 vs 累计失败）；d) 单测覆盖。
- **可能追问**："为什么不直接终止整个提问？"（其他工具可能正常，禁用是工具粒度的最小干预）；"模型被禁用后答得差怎么办？"（下一轮 observation 引导'换个方式'，或最终轮强制用已有证据作答——仍优于无限撞墙）；"禁用要不要持久化？"（不——按次提问生命周期，跨提问恢复）。
- **常见错误**：说禁用后模型"调不到所以没事"（历史消息里仍有 toolCalls 记录，模型可以重复请求——所以必须有回填兜底）；混淆"连续"与"累计"。

**EN**: Q: Why must consecutive tool failures be disabled by code, and what happens if the model still calls a disabled tool?
A: Models famously retry identical bad args despite prompts. Code brakes: 2 consecutive failures (success resets the counter) remove the tool from the next turn's declarations; a stubborn call gets a "tool disabled, try another way" failure observation; the turn budget stops bleeding. Unit-tested. Minimal intervention: per-tool, per-question lifetime.
Why asked: "prompts can't govern model behavior" engineering maturity.
Keys: wall-banging behavior; three-layer effect; reset semantics; stubborn-call back-fill.
Pitfall: thinking removing the declaration fully prevents calls.

---

### S1-25. 协程取消在本项目怎么处理？为什么 `CancellationException` 必须原样抛出？`invokeOnCompletion → call.cancel()` 解决什么问题？

**参考答案**：四个层面：① **重试层**：`withRetry` catch 后第一件事判 `e is CancellationException` 原样抛出——用户退出页面后绝不能再退避重试；且每轮 `ensureActive()` 提前止损。② **工具/降级层**：`ReActAgent` 与 `QARepositoryImpl` 的 catch 都是先 `catch (e: CancellationException) throw e` 再 catch `Exception`——取消不是失败，不能走降级，更不能把取消落库成"一次失败调用"。③ **网络层**：`executeCancellable` 用 `suspendCancellableCoroutine` 桥接 OkHttp enqueue，`invokeOnCancellation { call.cancel() }`——协程取消时主动掐断 socket。④ **流式读取层（最隐蔽）**：`readStream` 用 `source.readUtf8Line()` 阻塞读，**协程取消不会唤醒阻塞读**——不处理的话用户已退出问答页、Flow 收集已停止，但后台线程仍挂在这个读上直到 120s readTimeout。解法：`currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) activeCall?.cancel() }`，Job 以异常完成（=取消）时掐掉当前活着的 Call；Anthropic temperature 降级会发第二个请求，所以每次 `onCallCreated` 都要更新 activeCall，`finally` 里 `abortHandle?.dispose()` 注销回调。

**解析**：
- **考察意图**：这是本项目协程功力的天花板题。四层取消处理 + 阻塞读问题，全是实战才有的细节。
- **答题要点**：a) 取消≠失败的原则（降级/落库/重试三条路都必须绕开取消）；b) runCatching 与 catch(Exception) 都会吞取消——必须显式前置 catch；c) 阻塞读不响应协程取消的原理（取消靠挂起点传播，阻塞 IO 无挂起点）；d) invokeOnCompletion 的 cause 判断与 dispose 清理。
- **可能追问**："runCatching { tool.execute() } 取消时会怎样？"（CancellationException 被 runCatching 捕获当普通异常——工具层因此专门用 try/catch 并前置重抛取消；这是 Kotlin 协程著名坑）；"OkHttp 的 cancel() 对阻塞读生效吗？"（生效——call.cancel 会中断底层 socket 读，抛 IOException）；"为什么不用 withTimeout 包住整个提问？"（顶层超时会杀掉正常慢回答；超时只在工具调用层用 withTimeoutOrNull 分级设置）。
- **常见错误**：只知道"取消会抛 CancellationException"，不知道阻塞 IO 不响应取消；在 runCatching 里处理挂起调用。

**EN**: Q: How is coroutine cancellation handled, and what does `invokeOnCompletion → call.cancel()` solve?
A: Four layers: retry (rethrow + ensureActive), tool/degradation layers (catch CancellationException first, rethrow — cancellation is not failure, never degrade or log it as one), network bridge (`suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`), and the subtle one: `readUtf8Line()` is a blocking read that coroutine cancellation cannot wake up — without `Job.invokeOnCompletion(cause != null → activeCall.cancel())` the thread hangs until the 120s readTimeout after the user left. Each new Call updates the handle; disposed in `finally`.
Why asked: the senior-level coroutine question in this codebase.
Keys: cancellation ≠ failure at three sites; runCatching swallows cancellation; blocking IO ignores cancellation.
Follow-ups: runCatching pitfall; does OkHttp cancel interrupt reads; why not a top-level withTimeout.
Pitfall: assuming cancellation interrupts blocking reads.

---

## 1G 安全、隐私与可观测 | Safety, Privacy & Observability

---

### S1-26. "工具只读"铁律：这条边界划在哪？为什么它对这个项目特别重要？

**参考答案**：四个工具的 `execute` 全部只走 Repository 的查询方法，没有任何 insert/update/delete 路径；唯一的"外部能力"是 `read_report_image` 调视觉模型——它也是纯读（图片→文字）。重要性来自三点：① **数据完整性**：健康记录是用户核对过的真实数据（入库前有人工确认卡片），模型一句"帮我修正血糖值"若能落库，污染的是后续所有分析的地基；② **攻击面收敛**：模型输出不可信（提示注入、幻觉），只读意味着最坏情况 = 错误的回答，而不是错误的数据；写路径只能由人在 UI 上显式触发；③ **降级安全**：Agent 失败、模型抽风、甚至提示注入成功的最坏后果都被限制在"本次回答错误"之内。将来若加写工具（如语音记录血压），正确姿势：写工具单独白名单 + 关键参数回显确认 + 独立审计日志，与只读工具分域。

**解析**：
- **考察意图**：考 Agent 权限模型——"least privilege for agents"。这是所有面试官都会挖的安全题，本项目有真实的设计佐证（get_alerts 复用写用例被否决，见 S1-15）。
- **答题要点**：a) 只读的代码层证据；b) 三条重要性论证（数据完整性/攻击面/爆炸半径）；c) get_alerts 的反面教材（用查询姿势触发写操作）；d) 写工具的准入设计。
- **可能追问**："读操作有副作用吗？"（有——查询本身无副作用但读图消耗 token、日志落库；'只读'指业务数据不可变）；"模型能不能绕过？"（物理上不能——工具签名里没有写方法可调；比提示词约束强）；"日志算写吗？"（算，但那是系统观测不是用户数据，且失败不影响主流程——recordCall 用 runCatching 包住，观测组件坏了不能拖垮问答）。
- **常见错误**：把只读说成"最佳实践"却给不出爆炸半径分析；不知道 recordCall 的 runCatching 细节。

**EN**: Q: Where is the "read-only tools" boundary drawn and why does it matter here?
A: All four tools call query-only repository methods; the only external capability (image reading) is also pure read. Rationale: health records are human-confirmed ground truth; model output is untrusted (injection/hallucination), so read-only caps the blast radius at "one wrong answer", never "wrong data". `get_alerts` is the case study: reusing the anomaly-detection use case would have written alerts as a side effect of a question. Future write tools: separate allowlist, echoed confirmation, audit logs.
Why asked: least-privilege agent design with a real case study.
Keys: blast-radius analysis; the get_alerts counterexample; write-tool admission design.
Follow-ups: do reads have side effects (tokens/logs); can the model bypass (physically no).
Pitfall: "best practice" without the risk argument.

---

### S1-27. 可观测性日志 `llm_call_logs`：字段设计有哪些讲究？为什么不记录提示词与回复内容？

**参考答案**：粒度是**每次调用一行**，重试不改行、只在 `attempts` 上累计（否则一次失败请求变 3 行，统计口径乱了）。字段：provider / model / scene（parse/qa）/ latencyMs（含重试等待）/ promptChars（**不含图片 base64**，否则视觉请求把统计淹没）/ completionChars / hasImage / attempts / ok / errorType（短标签：http_429、http_5xx、timeout、io、truncated、config、异常类名——可聚合） / createdAt（有索引，按时间查询是主模式）。**不记内容**的原因：日志里是用户的体检数据——落库即扩大暴露面（任何能查库的调试代码/导出功能都变成数据泄露通道），且观测日志的生命周期（长期保留）与医疗数据的敏感度不匹配。结构化短标签设计让"失败集中在哪"可以聚合统计（比如 http_429 占比高 → 该供应商限流严，考虑换档）。两个边缘：配置类失败（Key 未填）也记一条 errorType="config"（发生在请求前，但统计"为什么用户用不了 AI"很关键）；协程取消**不落失败行**（用户主动行为不是故障）。

**解析**：
- **考察意图**：考观测设计的产品思维（回答"AI 功能出问题时到底发生了什么"）与隐私权衡——移动端 LLM 应用特有的议题。
- **答题要点**：a) 粒度选择（调用级而非请求级/轮级）与 attempts 累计的口径理由；b) promptChars 排除图片的统计卫生；c) errorType 短标签的可聚合性（对比：存完整异常栈没法 GROUP BY）；d) 隐私论证（内容 vs 元数据的分界）；e) 两个边缘 case（config 失败、取消不记）体现"日志是为统计服务的"。
- **可能追问**："怎么用这份数据？"（失败率/重试分布/延迟分位数 per 供应商-模型-场景；驱动供应商选型与超时调参）；"要不要记 token 数？"（API 不返回 usage 就只能估——字符数是可得的代理指标，诚实记录口径）；"日志会膨胀吗？"（调用级低频，量级小；可加清理策略）；"trace 怎么串一轮 Agent 的多次调用？"（当前靠 createdAt 时序 + scene 字段；改进方向是加 runId 串联——诚实说是 v2 方向）。
- **常见错误**：建议记录完整 prompt 便于调试（隐私上不可接受，面试官在等你说"不"）；不知道取消不算失败。

**EN**: Q: What's careful about the `llm_call_logs` design, and why never log prompt/completion content?
A: One row per call; retries accumulate in `attempts` rather than adding rows. Fields: provider/model/scene/latencyMs/promptChars (image base64 excluded)/completionChars/hasImage/attempts/ok/errorType (short aggregable tags)/createdAt (indexed). No content: it would be the user's medical data — logging widens the exposure surface, and observability retention doesn't match medical-data sensitivity. Edge cases: config failures logged as `errorType="config"`; user cancellation is not logged as failure.
Why asked: observability design + privacy trade-off unique to on-device LLM apps.
Keys: grain choice; stats hygiene; aggregable labels; the privacy line between metadata and content.
Follow-ups: how the data drives decisions; token vs chars; runId tracing as future work.
Pitfall: suggesting full-prompt logging "for debugging".

---

### S1-28. 首启同意门 + 固定免责声明：这两个合规设计为什么长在它们现在的位置？

**参考答案**：**同意门**：`ConsentScreen` 首启必须同意后才能进主功能，通知权限申请也必须在同意**之后**（先讲清数据怎么用再要权限，顺序本身就是合规要求）；`SettingsPrefs` 用 `consentVersion` 记录同意版本，`hasAcceptedConsent = consentVersion >= CONSENT_VERSION_CURRENT`——**文案实质变更时递增版本号**，老用户会重新看到同意页。为什么这么设计：同意不是"点过一次就完了"的事件，而是与**具体文案版本**绑定的契约，文案变了契约就得重签。**免责尾注**：SYSTEM_PROMPT 最后一条固定"回答末尾附：以上内容由 AI 基于已保存记录生成，仅供参考，不构成医疗建议"——模型自己生成尾巴不可靠（可能漏、可能改写），所以 qaSystemPrompt 与 ReAct SYSTEM_PROMPT 都把它作为硬编码规则，UI 层还可以兜底。位置哲学：**同意在数据入口之前（首启），免责在数据出口之后（每次回答）**——一个管进入，一个管输出。

**解析**：
- **考察意图**：考 AI 产品合规意识——健康类 Agent 面试几乎必问；同时考"规则长在代码哪一层"的架构判断。
- **答题要点**：a) 版本化同意的机制（consentVersion 比对）与递增时机；b) 权限申请时序；c) 免责的硬编码位置（提示词规则 + UI 兜底）与"不能靠模型自觉"的理由；d) 本地优先本身的合规价值（数据不经过开发者的服务器，BYOK 直连，Key AES-256-GCM 加密）。
- **可能追问**："同意状态怎么存？会不会被清数据绕过？"（SharedPreferences，清数据重同意是合理行为）；"免责声明放 UI 层固定渲染不是更可靠？"（是，双层最稳；提示词层保证历史记录/分享文本里也带）；"国内上架 vs 海外合规差异？"（国内：算法备案/深度合成标识；海外：GDPR/医疗类目审核——本项目选免费分享不上商店来规避上架资质，如实说）。
- **常见错误**：把免责声明当"文字游戏"（它是有监管含义的输出标识）；不知道版本化同意机制。

**EN**: Q: Why do the first-launch consent gate and the fixed medical disclaimer live where they do?
A: Consent: `ConsentScreen` gates all features; the notification permission request comes *after* consent; `SettingsPrefs` stores a `consentVersion` compared against `CONSENT_VERSION_CURRENT`, so substantive copy changes re-trigger consent — agreement is bound to a specific contract version, not a one-time event. Disclaimer: the final system-prompt rule appends a fixed AI-generated disclaimer; model-generated tails can't be trusted, and the UI can backstop. Philosophy: consent guards the data entrance; disclaimers guard every output.
Why asked: AI-product compliance for a health agent.
Keys: versioned consent; permission ordering; hard-coded disclaimer placement; local-first/BYOK privacy posture.
Follow-ups: storage bypass; UI-layer rendering; store vs direct-distribution compliance.
Pitfall: dismissing the disclaimer as boilerplate.

---

---

# Set 2 · 其他技术专题（25 题）

## 2A Kotlin / 协程 / Flow（6 题）

---

### S2-1. `flow { ... }.flowOn(Dispatchers.IO)` 的确切语义是什么？为什么不用 `withContext`？

**参考答案**：`flowOn` 只影响**上游**（flow 构建器 lambda 及其上游操作符）的执行上下文，下游收集者（ViewModel/UI）的上下文不变——本项目 `ReActAgent.run` 和 `askStream` 都是 `flowOn(Dispatchers.IO)`，UI 在 main 线程收集，网络/DB 在 IO 线程执行。`withContext` 是单次挂起调用的上下文切换，写进 flow 块里只包住某一段；`flowOn` 是流级别的声明式表达，且**不改变流的冷性质**。另一个关键点：`flowOn` 会让上游在 channel buffer 里运行（默认 64），上游发射与下游收集解耦——本项目 UI 逐事件消费（Answer/Thinking 增量），buffer 保证网络快于渲染时短暂缓冲而非丢事件。追问点：`buffer()`/`conflate()`/`collectLatest` 的差异（conflate 会丢中间增量——对"逐字上屏"正好可接受且省渲染，但会丢 ToolCallStarted 事件，所以不能全局 conflate）。

**解析**：
- **考察意图**：Flow 基本功——上下游上下文分离是 Flow 面试第一题，结合真实项目讲比背定义强。
- **答题要点**：a) 只影响上游 + 冷流不变；b) buffer 的存在与默认 64；c) 为什么不用 withContext（局部切换 vs 声明式、还有 channel 引入）；d) 结合事件流（不能 conflate 丢事件）。
- **可能追问**："flowOn 两次会怎样？"（靠近上游的生效，实际上游运行在最近的 flowOn 指定上下文）；"collect 在 IO 会怎样？"（下游也在 IO，UI 更新就会崩——需切回 main）。
- **常见错误**：说 flowOn 影响整条流；不知道 buffer 的引入。

**EN**: Q: Exact semantics of `flowOn(Dispatchers.IO)`, and why not `withContext`?
A: `flowOn` affects only the upstream context; the collector keeps its own. It's a declarative, stream-level switch that preserves cold-flow semantics and introduces a channel buffer (default 64) between upstream emission and collection. `withContext` is a local suspension switch. `conflate()` would drop intermediate deltas — unacceptable for ToolCallStarted events, so no global conflate.
Why asked: Flow fundamentals grounded in the project's event stream.
Keys: upstream-only; buffer; conflate trade-offs.
Follow-ups: two flowOns; collecting on IO.
Pitfall: "flowOn affects the whole chain".

---

### S2-2. 冷流 vs 热流：`askStream` 每次收集都重新执行意味着什么？QA 页为什么适合冷流？

**参考答案**：`flow { ... }` 是冷的：每个收集者触发一次完整执行。`askStream` 每收集一次就重新查库、重新检索、重新发起 LLM 请求——这正是想要的：一次提问 = 一次执行，执行结束事件流完结。副作用也随流走：`QAHistory` 落库发生在 flow 块内，收集被取消（用户退出页面）时——注意——**取消会连落库一起取消**，这是有意为之（半截回答不该进历史，见快路径对 CancellationException 的处理：直接 rethrow，不算失败）。热流（StateFlow/SharedFlow）适合**多观察者共享同一份状态**：本项目的成员列表、记录列表用 Room 的 `Flow<List<T>>`（也是冷流但由 Room 驱动重发）+ ViewModel `stateIn` 转 StateFlow 供 Compose 收集。判断口诀：**动作（一次问答）用冷流，状态（可观察数据）用热流**。

**解析**：
- **考察意图**：Flow 类型学的工程落地——什么时候冷什么时候热，用真实页面讲。
- **答题要点**：a) 冷流的"每次收集重新执行"对 LLM 请求的含义；b) 取消语义与落库的位置关系；c) Room Flow 的响应式机制（表变更重发）+ stateIn(WhileSubscribed(5000))；d) SharedFlow 的适用场景（一次性事件用 Channel/SharedFlow，本项目 QaStreamEvent 是冷流事件不用 SharedFlow）。
- **可能追问**："stateIn 的 5000ms 是什么？"（WhileSubscribed 的超时，屏幕旋转/短暂离开不重启上游）；"为什么问答结果不进 StateFlow？"（它是动作不是状态，历史列表才是状态）。
- **常见错误**：把事件流设计成 SharedFlow 导致重放/缓冲问题；不知道取消会带走落库。

**EN**: Q: Cold vs hot flows — what does re-execution per collection mean for `askStream`?
A: Each collection re-runs retrieval + the LLM call — desired: one question = one execution. Cancellation (user leaves) also cancels the history insert, deliberately (partial answers shouldn't persist; CancellationException is rethrown, not treated as failure). Hot flows fit shared state: Room's `Flow<List<T>>` + `stateIn(WhileSubscribed(5000))` feed Compose. Rule of thumb: actions → cold flow; observable state → hot flow.
Why asked: flow typology with real pages.
Keys: re-execution semantics; cancellation and persistence; stateIn timeout.
Follow-ups: the 5000ms; why Q&A results aren't StateFlow.
Pitfall: forcing event streams into SharedFlow.

---

### S2-3. `suspendCancellableCoroutine`：`LlmClient` 怎么把 OkHttp 的异步 enqueue 桥接成挂起函数？`invokeOnCancellation` 干什么？

**参考答案**：`executeCancellable(request)` 的模板：`suspendCancellableCoroutine { cont -> val call = okHttpClient.newCall(request); cont.invokeOnCancellation { call.cancel() }; call.enqueue(callback) }`——onResponse 里 `resp.use { cont.resume(code to body) }`，onFailure 里 `resumeWithException(e)`。三处讲究：① **enqueue 而非 execute**：execute 占用线程，enqueue 由 OkHttp 自己的调度器管理线程，协程只挂起不占线程；② **invokeOnCancellation 调 call.cancel()**：协程被取消时 OkHttp 不知道，必须显式掐断底层连接（socket 关闭会让阻塞的读/写抛 IOException，enqueue 的线程收到后走 onFailure）；③ **`resp.use {}`**：响应体必须关闭否则连接泄漏（OkHttp 连接池打满后整个客户端僵死）。这桥接把"异步回调 + 手动取消 + 资源释放"三件事压进一个挂起函数，调用方用 `val (code, text) = executeCancellable(request)` 像同步一样写。

**解析**：
- **考察意图**：协程互操作（bridging callback APIs）——Android 面试经典题，本项目有两个真实实例（executeCancellable 与流式读取）。
- **答题要点**：a) 模板三件套（resume/resumeWithException/invokeOnCancellation）；b) enqueue 的线程经济学；c) call.cancel 的必要性（否则取消后连接仍跑完）；d) use{} 的连接泄漏论证。
- **可能追问**："resume 被调两次会怎样？"（抛 IllegalStateException——原子 resume 只允许一次；本项目 code 分支结构保证单次）；"为什么不用 OkHttp 的 suspend 扩展？"（三方可得性/自定义可控；原理相同）；"流式怎么桥接？"（流式不用 suspendCancellableCoroutine——直接在 flowOn(IO) 的 flow 块里同步 execute + 逐行读，靠 Job invokeOnCompletion 掐断，见 S1-25）。
- **常见错误**：忘 invokeOnCancellation（取消后请求跑到底）；在 onResponse 里做重活（它跑在 OkHttp 线程）。

**EN**: Q: How does `LlmClient` bridge OkHttp's `enqueue` into a suspending function, and what does `invokeOnCancellation` do?
A: The classic template: create the Call inside `suspendCancellableCoroutine`, `invokeOnCancellation { call.cancel() }`, enqueue, resume with `code to body` inside `resp.use {}` or `resumeWithException`. Three subtleties: enqueue keeps threads free; cancellation must explicitly kill the socket (OkHttp doesn't know about coroutines); `use {}` prevents connection-pool leaks. Streaming instead runs a blocking read on IO with a Job-completion hook (see S1-25).
Why asked: coroutine interop, a classic Android question with two real instances here.
Keys: the template; thread economics; cancel-on-cancellation; resource closing.
Follow-ups: double resume; the streaming variant.
Pitfall: forgetting invokeOnCancellation.

---

### S2-4. `sealed interface` 在本项目建模了哪几组事件？相比 enum / 抽象类 / 泛型 ADT 的优势？

**参考答案**：五组：`AgentEvent`（Answer/Thinking/ToolCallStarted/ToolCallFinished/Completed）、`LlmStreamChunk`（Answer/Thinking/ToolCalls/Truncated）、`LlmStreamParser.Delta`（Content/Thinking/ToolCallFragment/Failure/Truncated/Done）、`AgentEntry`（System/User/AssistantToolCalls/ToolResults）、`QaStreamEvent`（References/Thinking/ToolCall/ToolResult/Answer/Finished）。sealed 的核心优势：**穷尽性**——所有 `when (event)` 不写 else 分支，新增事件类型编译器立刻报错（升级防回归）；**携带数据**——每个子类型是带字段的 data class（enum 只能是常量）；**层级控制**——sealed interface 限制实现位置在同一包/文件，外部不能扩展 = 事件集是封闭契约。相比抽象类：sealed 可多继承接口、data class 更轻；相比"泛型 Result<T>"式建模：事件是异构的，ADT 才是自然表达。

**解析**：
- **考察意图**：Kotlin 类型系统在事件驱动架构中的运用——Agent 系统本质是事件机，本题验证类型建模能力。
- **答题要点**：a) 五组各对应哪一层（UI 事件/流分块/协议增量/消息条目/页面事件）；b) 穷尽 when 的编译期保障；c) 与 enum 的本质区别（带载荷）；d) 分层事件为什么不是一套（协议 Delta → 流 Chunk → Agent 事件 → 页面事件，各层语义不同、逐层翻译）。
- **可能追问**："为什么 Delta.Failure 直接 throw 而不是当事件传？"（流内错误是终止性的，与增量事件不同质）；"Completed 里为什么带 evidence/toolNames？"（循环结束时一次性交付汇总状态，避免 UI 再查）。
- **常见错误**：把 when 加 else（破坏穷尽性保护）；五组事件混为一谈说不清层级。

**EN**: Q: Which event families does `sealed interface` model here, and why sealed over enum/abstract?
A: Five: AgentEvent, LlmStreamChunk, Delta, AgentEntry, QaStreamEvent — one per layer (agent events / stream chunks / protocol deltas / message entries / page events). Sealed buys: exhaustive `when` without else (compiler guards new variants), payload-carrying data classes, and a closed contract. Layers are translated stepwise because semantics differ (e.g., Delta.Failure is thrown, not emitted).
Why asked: type modeling for event-driven agent architectures.
Keys: the five families; exhaustiveness; payload vs enum; layer translation.
Follow-ups: why Failure throws; what Completed bundles.
Pitfall: adding `else` to exhaustive whens.

---

### S2-5. Kotlin DSL 写 Gradle 的坑：这个项目踩过哪些？keystore.properties 缺失时 release 不签名是怎么实现的？

**参考答案**：踩过的坑（记入项目备忘的）：① Kotlin DSL 需要 `import java.util.Properties`（不像 Groovy 隐式可用）；② properties 里的路径用**正斜杠**（Windows 反斜杠会被当转义）；③ Kotlin DSL 类型严格——Groovy 里 `minifyEnabled` 写错大小写运行时才炸，Kotlin DSL 编译期就报。签名设计：`app/build.gradle.kts` 里读取根目录 `keystore.properties`（storeFile/storePassword/...），存在则配置 `signingConfigs.release`，**不存在则 release 构建保持未签名**（用 debug 签名兜底或无签名 APK）——公开仓库 clone 下来无需任何密钥即可 `assembleRelease` 出可安装包；真实密钥文件 `keystore/homehealth-release.jks` 在仓库目录内但被 .gitignore。这是"开源可构建 + 私钥不泄露"的双目标实现。

**解析**：
- **考察意图**：构建系统熟练度——中级 Android 面试常问，能讲出签名配置与开源策略的联动是加分项。
- **答题要点**：a) 三个 DSL 坑的具体症状；b) 条件签名配置的代码结构（if (propsFile.exists())）；c) 为什么密钥不放仓库（ PUBLIC 仓库+分发安全）；d) versionCode/versionName 管理（1.2.0 / 2）。
- **可能追问**："R8 混淆怎么配？"（minifyEnabled + shrinkResources，proguard-rules.pro 里给 Gson 反射用的 DTO 加 keep 规则——不 keep 会字段全 null）；"为什么 targetSdk 36？"（政策跟进，行为变更适配如前台服务类型）。
- **常见错误**：说 Kotlin DSL 只是"语法不同"（类型安全与 IDE 补全是实质差异）；不知道 Gson + R8 的 keep 坑。

**EN**: Q: What Gradle Kotlin-DSL pitfalls did this project hit, and how is "unsigned release when keystore.properties is missing" implemented?
A: `import java.util.Properties` required; forward slashes in paths (backslash = escape); strict typing catches `minifyEnabled` typos at compile time. Signing: `build.gradle.kts` reads `keystore.properties` if present and wires `signingConfigs.release`; otherwise release stays unsigned so the public repo builds out of the box. The real JKS is gitignored. Also: R8 needs keep rules for Gson DTOs or fields come back null.
Why asked: build-system fluency plus the open-source signing strategy.
Keys: three pitfalls; conditional signing; R8×Gson keep rules.
Follow-ups: targetSdk 36 behavior changes; versioning.
Pitfall: treating DSL as mere syntax change.

---

### S2-6. `internal` 可见性与 companion object 在本项目怎么用的？`AgentRequestBody` 为什么是 `internal object`？

**参考答案**：`AgentRequestBody`、`LlmStreamParser`、`ToolCallAssembler` 都声明为 `internal`——它们是**协议实现细节**，只该被同模块的 `LlmClient` 使用，不该出现在对模块外的 API 面（`internal` 使其模块内可见、对外不可见，IDE 自动完成也不提示）。`object` 单例的适用：无状态纯函数集合（翻译、解析）——`ToolCallAssembler` 反而是 `class`（有状态：三个 Map 在累积），这是 object vs class 的教科书对比点。companion object 用于：常量集中（`AgentConfig` 是顶层 object；`LlmClient` 的 companion 放 TRUNCATED_HINT 等私有常量与 RETRY 常量）与伴生工厂（`QaRetriever.index(records)`、`ToolResult.ok/fail`）。可见性纪律的实际收益：测试可以放同模块访问 internal（Kotlin 默认允许同模块测试），但**别的模块无法依赖协议细节**——将来拆分:remote 为独立模块时边界已经划好。

**解析**：
- **考察意图**：Kotlin 语言细节的工程运用——考察是否理解可见性是架构工具而非语法糖。
- **答题要点**：a) internal 的模块边界语义与三个受益文件；b) object（无状态）vs class（有状态累积器）的对比；c) companion 的常量/工厂用法；d) 可见性服务于未来模块化。
- **可能追问**："internal 在 Java 里是什么？"（编译成 public + 名称修饰？不对——Kotlin internal 编译为字节码 public 但带 @JvmName 修饰的名称 mangling，Java 侧仍可强行调用，是弱约束）；"为什么 AgentConfig 不 internal？"（它是策略常量，属可公开的"配置面"）。
- **常见错误**：把 internal 说成 private 的别名；没想过 object 的状态问题。

**EN**: Q: How are `internal` and `companion object` used here? Why is `AgentRequestBody` an `internal object`?
A: Translation/parsing internals (`AgentRequestBody`, `LlmStreamParser`, `ToolCallAssembler`) are `internal` — module-internal API surface, hidden from outside (and ready for future module extraction). `object` fits stateless pure-function sets; `ToolCallAssembler` is a `class` because it accumulates state. `companion object` hosts constants and factory helpers (`QaRetriever.index`, `ToolResult.ok/fail`).
Why asked: visibility as an architecture tool.
Keys: internal semantics; stateless object vs stateful class; companion factories.
Follow-ups: internal under the hood (name mangling, weak vs Java); why AgentConfig is public.
Pitfall: treating internal as "private but looser".

---

## 2B Compose / Android 平台（3 题）

---

### S2-7. 问答页的事件流（References → Thinking → ToolCall → Answer → Finished）如何驱动 Compose UI？流式增量怎么不把重组打爆？

**参考答案**：ViewModel 持有 `StateFlow<QaUiState>`（问题、依据文本、思考轨迹、工具轨迹列表、答案文本、来源、truncated 标志），`askStream().collect` 里逐事件 updateState。增量处理：Answer/Thinking 的 delta append 到 StringBuilder 再更新 State——**每个 delta 一次重组在长回答下会明显卡顿**，优化路径：① 生命周期安全的 `stateIn` + `WhileSubscribed`；② 对文本增量做节流（如 100ms 或 N 字符合并一次 setState）或 `collectLatest` 丢中间帧；③ 答案文本用 `TextFieldValue`-free 的纯 String + `remember { }` 缓存 Markdown 渲染结果（项目里有 `MarkdownText` 组件及其单测）；④ 工具轨迹是 List append——不可变列表每次复制 O(n)，轨迹短无所谓，长了用持久化数据结构。另外 side-effect 类事件（滚动到底部）用 LaunchedEffect 消费事件而不是塞进 State。

**解析**：
- **考察意图**：Compose 状态管理的实战深度——流式 UI 是 Compose 性能的真实压力场景。
- **答题要点**：a) UiState 结构与事件→状态的映射；b) 重组节流的三种手段；c) Markdown 重渲染的缓存；d) State vs side-effect 的分离（LaunchedEffect）。
- **可能追问**："为什么不用 LazyColumn 直接渲染事件列表？"（答案区是连续文本不是列表；轨迹区才适合 LazyColumn）；"旋转屏幕后流丢了怎么办？"（Flow 在 ViewModel 收集，collect 在 viewModelScope，配置变更不取消；若 Activity 重建则重订阅 StateFlow——但进行中的流继续跑，结果会进 State）；"MarkdownText 的测试测什么？"（纯函数化的解析逻辑在 JVM 测，见 S2-24）。
- **常见错误**：每个 delta 直接 setState 不节流；把 UI 事件（滚动）当状态存。

**EN**: Q: How does the Q&A event stream drive Compose UI without recomposition blowups?
A: ViewModel holds `StateFlow<QaUiState>`; deltas append to text fields in state. Streaming text needs throttling (merge deltas every ~100ms or N chars, or `collectLatest`), cached Markdown rendering (the `MarkdownText` component has its own JVM tests), and side effects (auto-scroll) consumed via LaunchedEffect rather than stored in state. `stateIn(WhileSubscribed)` keeps the flow alive across config changes.
Why asked: streaming UI is real-world Compose performance.
Keys: UiState mapping; throttling; render caching; state vs side-effect.
Follow-ups: why not LazyColumn everywhere; rotation during a stream.
Pitfall: setState per delta.

---

### S2-8. Android Keystore 存 API Key：AES-256-GCM 的加解密流程？"Key 已保存但解不开"为什么单独设计一个状态？

**参考答案**：流程：首次保存 Key 时生成/复用 Keystore 里的 AES 密钥（setUserAuthenticationRequired 视设计而定，本项目选择无生物识别、纯设备绑定），GCM 加密后密文+IV 存 SharedPreferences，明文永不落盘；读取时解密进内存（只在发起请求的调用栈里存在）。**解不开的场景**：用户清除锁屏凭据/恢复出厂/系统 keystore 异常 → Keystore 密钥永久失效，密文成了死数据。此时如果错误提示是"API Key 未填写"，用户会**反复重填同一把 Key 却始终失败**（填的时候能存，读的时候解不开）——所以 `SettingsPrefs` 单独维护 `parseKeyUnreadable`/`qaKeyUnreadable` 布尔，`LlmClient.resolveConfig` 据此给出不同错误文案："已保存的 API Key 无法解密（系统密钥库已失效），请重新填写"。这是"区分'从未发生'与'发生过但坏了'"的错误设计——两个状态一个修复动作，提示错一个字用户就卡死。

**解析**：
- **考察意图**：安全存储实操 + 错误消息设计的用户同理心——两件事的交叉点最能看出工程成熟度。
- **答题要点**：a) GCM 流程（密钥不出 Keystore、密文+IV、认证标签）；b) Keystore 失效的真实触发条件；c) 双状态错误设计的用户行为推演；d) resolveConfig 里的落地（unreadable 分支文案）。
- **可能追问**："为什么不要求生物识别解锁？"（问答是高频操作，每次解锁体验不可接受；Key 泄露面=拿到已解锁设备，权衡后接受）；"密文存哪、备份会带走吗？"（SharedPreferences，Android 12+ 自动备份会带密文但没密钥解不开——密文可备份是 GCM 语义下的安全属性）；"GCM 的 IV 要求？"（唯一即可不保密，随机 12 字节）。
- **常见错误**：把 Keystore 说成"存 Key 的地方"（它存的是加密密钥，业务 Key 的密文在 SharedPreferences）；答不出解不开场景。

**EN**: Q: How are API keys stored with Android Keystore AES-256-GCM, and why is "key saved but unreadable" a distinct state?
A: AES key lives in Keystore (never exported); ciphertext+IV go to SharedPreferences; plaintext exists only in the request call stack. Keystore keys die permanently on lockscreen-credential resets etc., so stored ciphertext becomes unreadable. If the error said "key not set", users would re-enter the same key forever — hence `parseKeyUnreadable`/`qaKeyUnreadable` flags and a distinct message in `resolveConfig`. Classifying "never existed" vs "existed but broke" is what makes the fix path obvious.
Why asked: secure storage plus error-message empathy.
Keys: GCM flow; invalidation triggers; two-state design; user-behavior reasoning.
Follow-ups: biometrics trade-off; backup semantics; IV requirements.
Pitfall: "Keystore stores the API key".

---

### S2-9. 本项目的通知/提醒（用药提醒）为什么在首启同意之后才申请权限？"先同意后要权限"的合规与体验逻辑？

**参考答案**：铁律（记在项目备忘）：**通知权限申请必须在首启同意之后**。逻辑：① 合规——Android 13+ 通知是运行时权限，Google Play 政策与个人信息保护法都要求"告知用途在先、索取在后"：用户还不知道这个应用是什么、数据怎么处理时弹权限，属于"未告知先索取"；② 体验——同意页刚解释完"本地存储、AI 交互、提醒功能"，此刻请求通知权限的转化率最高且不突兀（语境完整的权限请求）；③ 架构——`hasAcceptedConsent` 是个门闩：入口导航先过这关，需要权限的功能页在门后。另外同意版本化（CONSENT_VERSION_CURRENT）保证文案实质变更时老用户重新同意——权限语境随之更新。这套设计面试可迁移到任意"权限敏感功能"（定位/通讯录）。

**解析**：
- **考察意图**：Android 运行时权限 + 合规意识；区分"能申请"（API 层面随时可以）与"该申请"（产品合规时序）。
- **答题要点**：a) 时序铁律与理由（告知在先）；b) consentVersion 的门闩作用；c) 语境化权限请求的转化逻辑；d) 通用化：任何运行时权限都适用此模式。
- **可能追问**："用户拒绝通知权限怎么办？"（功能降级不阻塞——提醒页显示手动开启引导，永不死循环弹窗）；"同意页存了什么文案版本？"（同意时把当前版本号写入 SP，比对即知是否需要重同意）。
- **常见错误**：把权限时序当"产品偏好"（它有政策依据）；不知道 Android 13 通知改运行时权限。

**EN**: Q: Why must the notification permission request come after first-launch consent?
A: Policy and privacy law require disclosure before request; Android 13+ made notifications a runtime permission. Asking with full context (right after the consent screen explains data handling and reminders) is both compliant and highest-converting. `hasAcceptedConsent` acts as the gate; versioned consent (`CONSENT_VERSION_CURRENT`) re-runs the flow when copy substantively changes. Pattern generalizes to any sensitive runtime permission.
Why asked: runtime permissions + compliance ordering.
Keys: disclosure-first; the consent gate; contextual requesting; graceful denial.
Follow-ups: denied permission UX; what's stored at consent time.
Pitfall: treating ordering as mere product taste.

---

## 2C Room / Hilt（4 题）

---

### S2-10. Room 从 v1 迁到 v9：迁移策略是什么？`exportSchema=true` 和 `SchemaMigrationStructureTest` 各起什么作用？

**参考答案**：策略是**显式 Migration 链**（`MIGRATION_1_2, ... 8_9`），拒绝 `fallbackToDestructiveMigration`——健康数据丢不起，每次升级必须保数据。`exportSchema=true` 把每个版本的 schema JSON 导出进版本库（`app/schemas/`），两个用途：① 迁移代码写错时 Room 的 schema 校验会在测试里炸；② **结构测试**（`SchemaMigrationStructureTest`，JVM 纯单测）：跑 `MigrationTestHelper` 需要设备，但它替代性地断言了每个迁移的关键结构（表/列/索引存在性与类型）——迁移逻辑的"形状"在 CI 就被锁住。加 `llm_call_logs` 表（v9 里的新增之一）的迁移就是 `CREATE TABLE IF NOT EXISTS llm_call_logs (...)` + `CREATE INDEX` 的组合。配合 `AutoMigration` 的判断：本项目的变更多是加表加列（AutoMigration 可覆盖）与复杂重构（仍需手写），保持手写链可控。

**解析**：
- **考察意图**：Room 迁移的完整工程链（迁移代码 + schema 导出 + 结构测试 + 策略取舍）——只答"写 Migration"不及格。
- **答题要点**：a) 显式链 vs 破坏性回退的取舍（数据价值）；b) exportSchema 的版本化 JSON 入库意义；c) 结构测试锁住迁移形状的原理；d) AutoMigration 的适用边界。
- **可能追问**："MigrationTestHelper 怎么用？"（androidTest 里 createDatabase(version N) → runMigrations → 验证 → close；本项目把它换成 JVM 结构断言是构建速度取舍）；"迁移写错了会怎样？"（运行时 IllegalStateException: Migration didn't properly handle——上线前被结构测试拦住）。
- **常见错误**：建议 fallbackToDestructiveMigration（健康数据不可接受）；不知道 schema JSON 导出。

**EN**: Q: What's the Room v1→v9 migration strategy, and what do `exportSchema=true` and `SchemaMigrationStructureTest` do?
A: Explicit migration chain, no destructive fallback — health data must survive upgrades. `exportSchema=true` commits versioned schema JSONs to the repo; the JVM structure test asserts each migration's key shape (tables/columns/indexes) so migration regressions fail in CI without needing device-based `MigrationTestHelper`. New tables (e.g. `llm_call_logs`) are CREATE TABLE + CREATE INDEX pairs.
Why asked: the full migration engineering chain.
Keys: explicit chain; schema JSON in VCS; shape-locking tests; AutoMigration boundaries.
Follow-ups: MigrationTestHelper usage; what a broken migration throws.
Pitfall: suggesting destructive fallback.

---

### S2-11. `getRecentPerTypeByMember` 解决什么问题？"每指标最近 N 条"在 SQL 里怎么写，为什么不用内存过滤？

**参考答案**：解决 **N+1 查询**：问答上下文要"每类指标最近 10 条"，朴素做法是先查全部指标类型列表、再逐类型发一条 `SELECT ... WHERE type=? ORDER BY date DESC LIMIT 10`——49 类指标 49 条查询。`getRecentPerTypeByMember(memberId, n)` 用一条 SQL（窗口函数 `ROW_NUMBER() OVER (PARTITION BY type ORDER BY recordDate DESC)` 或相关子查询）直接返回组内 Top-N。内存侧 `groupBy { it.type }.mapValues { take(RECORDS_PER_TYPE) }` 做二次裁剪（SQL 侧已限 Top-N，这里是防御性收敛与分组重建）。为什么 SQL 侧做：数据在 DB 磁盘上，N+1 = 49 次磁盘往返 + 49 次游标物化；一条查询一次扫描。千条量级差距是毫秒 vs 几十毫秒，但这是随手就该做对的事，且记录涨到万条时不用回头改。

**解析**：
- **考察意图**：经典 N+1 题的移动端版本；考察"查询下推到 SQL"的直觉。
- **答题要点**：a) N+1 的产生场景与代价；b) 窗口函数写法（或 MySQL 8 前的相关子查询变体）；c) 内存二次裁剪是防御不是主逻辑；d) SQLite 对窗口函数的支持（3.25+，Android API 30+ 默认带新版本 SQLite——低版本需要兼容写法，诚实说明实现选择）。
- **可能追问**："为什么不全查出来内存分组？"（本项目千条确实可行——这也是一个诚实答案；SQL 侧做是为了量级增长安全 + 一次往返）；"recordDate 有索引吗？"（查询模式决定，见下一题 llm_call_logs 的 createdAt 同理）。
- **常见错误**：只会 Java 8 stream 内存分组；不知道 SQLite 窗口函数的版本问题。

**EN**: Q: What does `getRecentPerTypeByMember` solve and how does "latest N per type" work in SQL?
A: It kills the N+1: fetching the latest 10 records per metric type naively issues one query per type (~49). One SQL with `ROW_NUMBER() OVER (PARTITION BY type ORDER BY recordDate DESC)` returns per-group Top-N in a single pass; the Kotlin `groupBy + take` afterwards is defensive regrouping, not the main filter. Push-down matters as data grows and keeps one round-trip.
Why asked: N+1 on mobile + SQL window functions.
Keys: N+1 cost; window-function formulation; honest trade-off at thousand-row scale.
Follow-ups: SQLite window-function version support; indexing.
Pitfall: memory-only grouping with no push-down awareness.

---

### S2-12. Hilt：新增一个接口实现（如新 Repository）忘在 `RepositoryModule` 里补 `@Binds` 会发生什么？为什么 Agent 网关要绑定到 LlmClient？

**参考答案**：**编译期就炸**——Hilt/Dagger 生成组件时发现 `QARepository` 没有绑定，报 `error: [Dagger/MissingBinding] kotlinx...QARepository cannot be provided without an @Provides-annotated method`，构建失败（这是编译期 DI 的核心价值，运行时才炸的是 ServiceLocator 模式）。项目备忘里特意记了这个坑：Hilt 坑——新接口实现必须补 di/RepositoryModule.kt 的 @Binds。**Agent 网关绑定**：`ReActAgent` 依赖的是 `AgentLlmGateway` 接口（不是 `LlmClient` 具体类），`RepositoryModule` 里 `@Binds AgentLlmGateway → LlmClient`（LlmClient 同时还绑 `VisionReader`）——一个实现类服务两个窄接口，接口隔离让 ReActAgent 只看见 `turn()` 一个方法。同理 `ToolProvider → HealthToolRegistry`。收益在测试：`ReActLoopTest` 用假网关跑完整循环策略，不需要凑齐 OkHttpClient/SettingsPrefs/日志仓库一整串真依赖。

**解析**：
- **考察意图**：DI 原理（编译期依赖图校验）+ 接口隔离与可测试性的联动——本题把 Hilt 从"注解背诵"拉到"设计工具"层面。
- **答题要点**：a) MissingBinding 的编译期报错与价值；b) 一个实现绑多个窄接口的结构（LlmClient = AgentLlmGateway + VisionReader + 自身业务方法）；c) @Binds vs @Provides（Binds 绑实现零样板，Providers 构造）；d) 测试注入路径。
- **可能追问**："@Singleton 加在哪？"（实现类上——ReActAgent/LlmClient 都是 @Singleton @Inject constructor；组件图里单例是绑定属性不是接口属性）；"为什么要 VisionReader 窄接口？"（读图工具只需要"图→文字"一个语义，不暴露整个客户端——依赖最小化，JVM 单测可构造）。
- **常见错误**：说运行时才报错；分不清 @Binds 与 @Provides。

**EN**: Q: What happens if you forget the `@Binds` entry for a new repository, and why is the agent gateway bound to LlmClient?
A: Hilt fails at **compile time** with `[Dagger/MissingBinding]` — the whole point of compile-time DI. `RepositoryModule` binds `AgentLlmGateway → LlmClient` and `VisionReader → LlmClient` (one implementation serving two narrow interfaces), plus `ToolProvider → HealthToolRegistry`. Interface isolation means `ReActAgent` sees only `turn()`, and `ReActLoopTest` injects a fake gateway without constructing the real dependency graph.
Why asked: DI as a design tool, not annotation trivia.
Keys: compile-time graph validation; one impl → multiple narrow interfaces; @Binds vs @Provides; test path.
Follow-ups: where @Singleton lives; why the VisionReader split.
Pitfall: "it fails at runtime".

---

### S2-13. `llm_call_logs` 为什么在 `createdAt` 上建索引？索引的写放大代价怎么权衡？

**参考答案**：主查询模式是"**按时间看最近的调用/失败**"（排查"昨晚问答为什么失败"→ `WHERE ... ORDER BY createdAt DESC LIMIT n` 或时间范围过滤），无索引 = 全表扫描 + 排序。`@Entity(indices = [Index("createdAt")])` 让该模式走 B-tree 范围扫描。写放大权衡：每次 LLM 调用插入一行都要同步维护索引（B-tree 插入 + 可能的页分裂），但本表**写频率极低**（每问答/解析一次）而**读模式高度偏向时间序**——索引收益碾压代价。对比反面：如果给 `provider`/`scene` 都建索引，写入成本翻倍而查询频率撑不起。索引设计方法论：**从真实查询模式出发，不预防性建索引**。参考范围另一例：`HealthRecord` 的查询模式（byMember+type+date 排序）决定其复合索引。

**解析**：
- **考察意图**：索引设计的基本功 + "查询模式驱动"的方法论——用一张真实表讲，比背"B-tree 加速查询"深一层。
- **答题要点**：a) 主查询模式与索引的对应；b) 写放大的量化直觉（写频率 vs 读频率）；c) 反例（哪些字段**不**建索引、为什么）；d) 方法论总结（真实模式驱动，不预防）。
- **可能追问**："什么时候索引反而慢？"（低选择性列——比如 hasImage 只有真/假，索引几乎无过滤力）；"覆盖索引了解吗？"（把查询列都放进索引避免回表；本表 SELECT * 无此需求）；"表会无限涨吗？"（调用级低频，长期可加 TTL 清理——诚实说是待办）。
- **常见错误**：说"索引越多越好"；说不出写放大。

**EN**: Q: Why index `createdAt` on `llm_call_logs`, and how do you weigh write amplification?
A: The dominant query is "recent calls/failures by time" — ORDER BY createdAt DESC / range filters — which without the index is a full scan + sort. Writes are rare (one row per LLM call) while reads are time-ordered, so the index easily pays for itself; indexing provider/scene too would double write cost for queries that don't justify it. Methodology: derive indexes from real query patterns, never preventively. Low-selectivity columns (hasImage) are pointless to index.
Why asked: index fundamentals with a real table.
Keys: query-pattern-driven design; write-vs-read asymmetry; counter-examples; selectivity.
Follow-ups: when indexes hurt; covering indexes; table growth.
Pitfall: "more indexes = better".

---

## 2D 数据结构与算法（4 题）

---

### S2-14. 手写 BM25：写出公式并解释 k1、b 与 IDF 的作用。`QaRetriever` 的实现里有哪些工程化变通？

**参考答案**：score(D,Q) = Σ_qtf × IDF(t) × [tf × (k1+1)] / [tf + k1 × (1 − b + b × |D|/avgdl)]，其中 IDF = ln(1 + (N − df + 0.5)/(df + 0.5))。**k1=1.5** 控制词频饱和（tf 越大贡献递增但增速衰减——一篇文档"血糖"出现 5 次不该是 1 次的 5 倍）；**b=0.75** 控制文档长度归一（长文档天然 tf 高，b 压制长文档优势）；**IDF** 压低到处都出现的词（"的""记录"）的权重。实现变通：① **索引即查询时构建**——不持久化倒排索引，每次提问对单成员全部记录建 tf/length（毫秒级），换来零状态管理；② df 用 HashMap 现算；③ score ≤ 0 的文档不返回（IDF 的 ln 形式下负分 = 完全不相关）；④ 查询词频 qtf 用 groupingBy eachCount。复杂度：建索引 O(N×L)（N 文档 × 平均长度），打分 O(N×|Q|)，千条语料毫秒级。

**解析**：
- **考察意图**：检索算法的真实功底——公式要能写，参数要能解释，工程变通要能说理由。Agent 岗的 RAG 部分高频。
- **答题要点**：a) 公式与两个参数的语义（饱和/归一）；b) ln 型 IDF 的性质（负分过滤）；c) 四个实现决策及代价收益；d) 复杂度分析。
- **可能追问**："k1=0 会怎样？"（变成二值 tf，出现即满分饱和）；"为什么不用 TF-IDF？"（BM25 是 TF-IDF 的饱和+归一改进版，长文档表现更好）；"相关性反馈/语义扩展呢？"（超范围，别名表已解决主要同义问题——引用 S1-18）。
- **常见错误**：公式写不出或参数瞎解释；不知道 ln(1+x) 形式下负分的含义。

**EN**: Q: Write the BM25 formula and explain k1, b, IDF; what engineering choices does `QaRetriever` make?
A: score = Σ qtf·IDF·[tf·(k1+1)] / [tf + k1·(1−b+b·|D|/avgdl)], IDF = ln(1+(N−df+0.5)/(df+0.5)). k1=1.5 saturates term frequency; b=0.75 normalizes document length; IDF down-weights ubiquitous terms. Choices: per-query in-memory index (ms-cheap, zero state), df recomputed per query, score ≤ 0 filtered (ln IDF can go negative), complexity O(N·L + N·|Q|).
Why asked: core RAG algorithm competence.
Keys: formula; parameter semantics; negative-score filtering; complexity.
Follow-ups: k1=0; TF-IDF vs BM25; relevance feedback.
Pitfall: hand-waving the parameters.

---

### S2-15. 中文没有分词器，`QaRetriever.tokenize` 怎么切？"单字+双字"策略的召回/精度权衡是什么？

**参考答案**：规则切分：CJK 连续段按**单字 + 相邻双字窗口**切（"空腹血糖"→ 空/腹/血/糖/空腹/腹血/血糖）；拉丁字母与数字按连续串切、**数字与字母之间强制分段**（"6.2mmol"→ 6/2/mmol）；其余字符作分隔。权衡：**单字保召回**——指标别名里有单字词（钾/钠/钙），纯双字切分永远匹配不上单字查询；**双字保精度**——中文指标名主体是双字词（血糖、尿酸），双字粒度的区分度远高于单字（"血"同时命中血糖血脂血常规）。IDF 自动兜底：高频单字（"血"）df 高 → IDF 低 → 不至于淹没双字词的得分，**不需要人工加权**。文档侧同样切分，且文档文本预置了指标别名（复用 SchemaNormalizer 的 106 条别名表）——查询"维D"能命中"维生素D"记录是别名层+双字窗口的合力。

**解析**：
- **考察意图**：中文信息检索的入门坑——没有 jieba 就必须自己设计切分；考察方案背后的召回/精度思维。
- **答题要点**：a) 三条切分规则（含数字字母边界这种细节）；b) 单字/双字各自的失败案例（反证必要性）；c) IDF 自动降权的机制（为什么不用停用词表）；d) 别名表与切分的配合。
- **可能追问**："为什么不引入 jieba/端侧分词模型？"（依赖+包体+准确率在本域未必更好——指标名是封闭词表，规则+别名可控可解释）；"三字词怎么办？"（双字窗口滑过三字词的所有相邻对，长名也能被碎片命中）；"索引会膨胀吗？"（文档数 × ~2L 项，千条量级毫无压力）。
- **常见错误**：只答"按字切"（丢失双字精度）；没想过 IDF 对高频单字的自动压制。

**EN**: Q: How does `QaRetriever.tokenize` handle Chinese without a segmenter, and what's the unigram+bigram trade-off?
A: Rule-based: CJK runs split into single chars + adjacent bigrams (recall via single-char aliases like 钾; precision via two-char metric names); latin/digit runs split at digit-letter boundaries ("6.2mmol" → 6/2/mmol). IDF automatically down-weights high-frequency single chars, so no stopword list is needed. Documents are pre-expanded with the 106-entry alias table so "维D" hits "维生素D" records.
Why asked: CJK retrieval without a segmenter.
Keys: three rules; recall/precision failure cases; IDF self-balancing; alias synergy.
Follow-ups: why not jieba; trigram coverage; index size.
Pitfall: char-only splitting.

---

### S2-16. `extractJsonObjects` 的括号配平扫描：状态机怎么画？为什么不能 `indexOf('{')` + `lastIndexOf('}')`？

**参考答案**：状态机变量：`depth`（花括号深度）、`inString`（是否在 JSON 字符串内）、`escaped`（转义态）。逐字符扫描：escaped 置位则消费掉；inString 下遇 `\` 进 escaped、遇 `"` 翻转 inString；**字符串外的** `{`/`}` 才增减 depth，depth 归 0 记录一个完整对象。为什么朴素方案不行：① `首{ 到末}`——模型输出两段 JSON 时会截出 `obj1} 中间 {obj2` 的非法串；② 正文夹带花括号（"体温{正常}"或 JSON 字符串值里的 `{`）会让深度错乱；③ 字符串里的引号（`{"value": "6\"2"}`）不处理 inString 会提前终止字符串态。这是典型的**手写词法扫描器**：正则做不了（嵌套配平不是正则语言），库解析器做不了（要先知道边界才能交给 Gson）。配套：解析时对每个候选 try Gson，取第一个含 `records` 的——多候选兜底模型输出的杂前后缀。

**解析**：
- **考察意图**：算法基本功（词法扫描/状态机）+ 为什么它的应用场景真实（LLM 输出的 JSON 容错解析是每个 Agent 工程师都躲不掉的活）。
- **答题要点**：a) 三状态变量与转移规则；b) 三个朴素方案的反例；c) "正则不可为（嵌套非正则语言）"的理论一句；d) 候选逐一 try 解析的工程兜底。
- **可能追问**："扫描 O(n) 能再优化吗？"（已经线性，常数小；没有更优渐近）；"模型输出 ```json 包裹怎么办？"（先 stripCodeBlock 去壳再扫）；"为什么不直接 Gson 从头解析？"（前后杂文字会让整次解析失败——这正是要扫描的原因）。
- **常见错误**：忘 escaped 态（`\"` 破坏 inString 判定）；只处理 `{}` 不管字符串内花括号。

**EN**: Q: Explain the bracket-balancing scanner in `extractJsonObjects` and why naive first-{/last-} slicing fails.
A: A three-state lexer: `depth`, `inString`, `escaped`; braces outside strings adjust depth, and depth 0 closes an object. Naive slicing fails on: two JSON blobs in one output; braces inside prose or string values; escaped quotes. Regex can't do balanced nesting (not a regular language); a full parser can't run without knowing the boundaries. Candidates are then tried with Gson one by one, first containing `records` wins.
Why asked: lexer/state-machine fundamentals applied to real LLM-output parsing.
Keys: three states; three counterexamples; regex impossibility; candidate fallback.
Follow-ups: complexity; code-fence stripping.
Pitfall: forgetting the escape state.

---

### S2-17. `ToolCallAssembler` 为什么用 `LinkedHashMap` 而不是 HashMap？`build()` 为什么要排序？

**参考答案**：三个 Map（ids/names/args）都以**分片 index** 为 key——HashMap 也功能正确，但：① `build()` 要按 index 升序产出调用列表（与模型声明的调用顺序一致——**顺序有语义**：模型先查 A 再查 B，回填顺序错乱会让"工具轨迹"展示与事实不符，也可能影响供应商对 tool_call_id 配对的宽容度）；② LinkedHashMap 保持插入序 + build 时显式 `sortedBy { it.key }` 双保险——即便 index 乱序到达也能正确排序；③ 稳定顺序对**测试**至关重要：断言 `assertEquals(expected, calls)` 要求每次顺序一致（HealthToolRegistry 的注释同款理由："顺序固定：测试与日志比对需要一个稳定顺序"）。args 用 `StringBuilder`（getOrPut）拼接而非字符串相加——分片可达几百个，O(n²) 字符串拷贝是真实风险。

**解析**：
- **考察意图**：数据结构选择的"为什么"层——HashMap vs LinkedHashMap 的差异人人都背过，能否落到顺序语义与可测试性上才是理解。
- **答题要点**：a) index 是分组键、插入序+显式排序双保险；b) 顺序的三层语义（声明序/展示序/配对宽容度）；c) 稳定性服务测试断言；d) StringBuilder 的复杂度论证。
- **可能追问**："TreeMap 不是更直接？"（功能等价，TreeMap 红黑树插入 O(log n)，LinkedHashMap O(1)+末尾排序——分片数小，都可；选后者是习惯与 API 直感）；"id 兜底 call_$index 会不会撞？"（同一流内 index 唯一，不撞；跨轮的 id 由新一轮模型重新生成）。
- **常见错误**：答不出顺序为什么有语义；用字符串拼接拼 args。

**EN**: Q: Why `LinkedHashMap` in `ToolCallAssembler`, and why does `build()` sort?
A: Fragments are keyed by `index`; call order carries semantics (declaration order, display order, provider tolerance for id pairing). LinkedHashMap preserves insertion order and `build()` sorts by key anyway — belt and braces — giving deterministic order that unit assertions require. Args accumulate in a per-index `StringBuilder` to avoid O(n²) string concatenation across hundreds of fragments.
Why asked: "why this structure" beyond textbook differences.
Keys: order semantics; insertion-order + explicit sort; test determinism; StringBuilder complexity.
Follow-ups: TreeMap alternative; id-collision.
Pitfall: string-concatenating the arguments.

---

## 2E 系统设计（2 题）

---

### S2-18. 白板题：为"本地优先 + 用户自带 Key（BYOK）"的移动 LLM 应用设计网络层。要求支持多供应商、双协议、流式、可观测。

**参考答案**：分层：① **配置层**——预设供应商清单（baseUrl/模型清单/协议标识）+ 用户自定义模型名 + Key 加密存储（Keystore AES-GCM），`resolveConfig` 统一校验（含视觉/文本模型区分校验与三类可操作错误文案）；② **协议适配层**——请求体构建与响应解析全部做成**纯函数**（AgentRequestBody / LlmStreamParser 模式），新增协议只加翻译分支；③ **执行层**——OkHttp enqueue + suspendCancellableCoroutine 桥接（取消掐 socket），withRetry 指数退避（错误分类决定可重试性），流式加"首增量后不重试"闸门；④ **超时分级**——工具类 10s、视觉类 60s、读超时 120s，分级设置而非全局一刀切；⑤ **观测层**——每次调用落一行结构化日志（无内容），观测组件自身故障用 runCatching 隔离，绝不拖垮主流程；⑥ **降级出口**——网络层只抛 typed 异常，降级策略集中在唯一一处。关键设计原则：纯函数优先（可测）、错误分类驱动重试、取消路径与失败路径分离、观测零侵入。

**解析**：
- **考察意图**：把项目网络层的真实设计抽象成可复用的白板答案——面试官给 20 分钟，你要在白板上画出这六层并说清每层的"为什么"。
- **答题要点**：a) 六层职责一句一个；b) 至少三个"刻意决策"（纯函数翻译、取消≠失败、观测隔离）支撑深度；c) 明确演进方向（动态模型清单 /v1/models、Retry-After 头、runId 串联 trace）展示成长性。
- **可能追问**："如果加代理支持？"（OkHttp Proxy 配置 + 用户设置项）；"证书锁定（SSL pinning）要不要？"（BYOK 直连多家供应商，pin 不现实，依赖系统信任链）；"弱网优化？"（首字节超时独立于读超时、流式天然抗抖动、非流式可加断路器）。
- **常见错误**：上来就画"六边形架构"名词堆砌，说不出任何具体决策；漏掉取消与观测。

**EN**: Q: Design the network layer for a local-first, BYOK mobile LLM app: multi-provider, dual-protocol, streaming, observable.
A: Six layers: config (provider presets + Keystore-encrypted keys + resolveConfig validation with actionable errors); protocol adaptation as pure functions; execution (OkHttp enqueue bridged via suspendCancellableCoroutine, classified retry with exponential backoff, no-retry-after-first-delta gate for streams); tiered timeouts (10s tools / 60s vision / 120s read); observability (one content-free row per call, runCatching-isolated so logging never breaks the flow); degradation (network layer only throws typed errors; policy lives in one place).
Why asked: whiteboards the real design of this codebase.
Keys: six layers with rationales; three deliberate decisions; evolution roadmap.
Follow-ups: proxy support; pinning feasibility; weak-network behavior.
Pitfall: buzzword diagrams with no decisions.

---

### S2-19. 白板题：为问答功能设计"降级矩阵"。输入维度：问题类型（指标/泛化/带图）× 服务可用性（LLM 可用/失败）× 数据就绪度（有记录/无记录）。

**参考答案**：先列不变量：**任何分支都要么给出完整回答，要么给出可操作的下一步，绝不静默降级**（有图没视觉模型必须明说）。矩阵主干（即 `QARepositoryImpl` 的真实 when 结构）：① 泛化问题 + LLM 可用 + 有数据 → **Agent 路径**（多步工具），失败 → 本地引擎（标注"多步分析未完成"）；② 指标问题 + LLM 可用 → **快路径**（BM25+引用），无命中 → 全量摘要路径，部分中断 → 保留已生成+中断说明；③ 带图 → 先探测视觉能力（`qaVisionRoute`），无视觉模型 → **明确告知不发图**+两条修复路径；有 → 走对应路径并在来源标注视觉模型出处；④ 无记录 → 本地引擎引导录入（"请先上传报告"），不浪费 LLM 请求；⑤ 未配置 Key/本地模式 → 本地引擎，UI 常态显示"本地模式"。每格的**来源标注**是矩阵的一部分：AI 结论/规则引擎/中断部分三者的来源文案不同，用户永远知道自己在读什么。

**解析**：
- **考察意图**：系统设计题里最有区分度的类型——状态组合爆炸下如何用不变量（而非穷举）收敛设计。
- **答题要点**：a) 先立不变量再填格子；b) 矩阵与真实代码结构的对应（能指认 when 分支）；c) 来源标注作为一等公民；d) 成本意识（无记录不烧 LLM）。
- **可能追问**："分支数爆炸怎么办？"（矩阵按正交维度拆：路由决策 / 失败决策 / 展示决策三层各管各的）；"新加'多轮追问'维度？"（历史上下文进 ToolContext/消息列表，矩阵不变——维度正交的好处）；"怎么测试矩阵？"（每格一个 JVM 单测——QARepositoryImpl 的 when 分支可用假网关/假引擎逐格驱动）。
- **常见错误**：穷举所有格子丢不变量；忘记"部分成功"格（流式中断）。

**EN**: Q: Design the degradation matrix for Q&A: question type × service availability × data readiness.
A: Start with invariants — every cell yields a complete answer or an actionable next step; never silently drop an image. Then the matrix mirrors `QARepositoryImpl`: generalized+LLM → agent path (fail → local engine, honestly labeled); metric+LLM → BM25 fast path (no hits → full summary; mid-stream break → keep partial + notice); image → probe vision route first, no vision → explicit "image not sent" + two fixes; no records → local guidance without burning LLM calls; unconfigured → local mode badge. Source labeling is part of every cell.
Why asked: invariants vs combinatorial explosion.
Keys: invariants first; matrix-to-code mapping; source labels; cost awareness.
Follow-ups: scaling the matrix; testing each cell.
Pitfall: enumerating cells without invariants; forgetting partial success.

---

## 2F 并发与性能（2 题）

---

### S2-20. Agent 每轮重发全部历史（消息 + 工具 schema）——token 成本怎么随轮数增长？有哪些优化方向？

**参考答案**：第 t 轮的请求体 ≈ System + User + (t−1) × (AssistantToolCalls + ToolResults) + 工具 schema（4 个工具的 name/description/JSON schema，约数千字符）。总成本 Σt ≈ O(T²) 的消息部分 + O(T) 的 schema 部分——**超线性**，这正是 MAX_TURNS=5 的主要论据（注释原话：token 成本随轮数超线性增长）。本项目已做的控制：observation 截断 1500 字符（直接压低每轮增量）、轮数/调用次数护栏。优化方向（按 ROI）：① **Prompt caching**（供应商侧 KV cache，OpenAI/Anthropic/智谱均已支持——前缀不变即可命中，本项目消息只追加不修改，天然适合）；② 早期轮次用轻模型、最终轮用强模型（路由已具备基础设施）；③ 上下文压缩（把前几轮 observation 摘要成短段——有信息损失风险，健康数值场景谨慎）；④ schema 精简（工具描述是每轮固定成本，四工具描述的 ROI 审计）。

**解析**：
- **考察意图**：Agent 成本模型的量化意识——"知道多轮贵"人人会说，能写出增长阶数和优化优先级的是少数。
- **答题要点**：a) O(T²) 推导（每轮重发历史）；b) 本项目已有的三个控制手段；c) 四个优化方向的 ROI 排序与风险（压缩的数值保真问题）；d) prompt caching 的命中条件（前缀稳定）与本项目消息追加式的契合。
- **可能追问**："为什么消息只追加不修改适合 caching？"（KV cache 按前缀命中，改中间任何一条都会失效后缀——本项目工具轮只 append，前缀永远稳定）；" observation 截断 1500 对缓存的影响？"（无——截断发生在回填前，历史一旦写入就不再变）；"怎么观测成本？"（llm_call_logs 的 promptChars 按轮累计可画出轮数-成本曲线）。
- **常见错误**：只说"重发很贵"不给阶数；建议"历史全丢弃"（丢上下文的 Agent 不是 Agent）。

**EN**: Q: How does token cost grow when every turn resends full history plus tool schemas, and what are the optimizations?
A: Turn t resends (t−1) prior exchanges plus the schema → O(T²) messages + O(T) schema; superlinear growth is the stated rationale for MAX_TURNS=5. Already in place: 1500-char observation truncation, turn/call budgets. Optimizations by ROI: prompt caching (append-only message history = stable prefix, ideal hit rate), tiered models per turn, context summarization (risky for numeric fidelity), schema diet. Observability exists: promptChars per turn in llm_call_logs.
Why asked: quantified cost modeling.
Keys: the O(T²) derivation; existing controls; caching fit; compression risk.
Follow-ups: why append-only helps caching; measuring cost curves.
Pitfall: "just drop history".

---

### S2-21. 图片 base64 在多轮循环里为什么不内联？`promptChars` 统计为什么要排除图片？还有哪些"统计口径"细节？

**参考答案**：多轮循环中消息历史每轮重发——内联 base64（一张 1600px 报告图压缩后仍有数百 KB~MB 级）会让第 t 轮请求 carrying t 份图片拷贝，成本按轮数线性放大且逼近请求体上限。`read_report_image` 工具把图片**一次**转成文本（≤1500 tokens），后续轮次上下文纯文本。统计口径细节：① `promptChars` 只统计文本（ContentPart 过滤 text），否则一张图 50 万"字符"把所有统计淹没、字符数完全失去可比性；② hasImage 是独立布尔字段，视觉请求可单独分桶统计；③ Agent 路径的 `entryPromptChars` 把 ToolResults 的文本也算进 prompt——"工具结果撑大上下文"这件事必须可观测，否则轮数-成本曲线缺一块；④ 日志的 completionChars 失败时为 0、流式重试后记的是最后一次成功尝试的长度。口径设计的元原则：**统计字段要么可比、要么删除**——混入口径的指标比没有指标更误导。

**解析**：
- **考察意图**：多模态成本模型 + 统计口径的严谨性——后者是数据分析素养在工程里的投影。
- **答题要点**：a) 重发放大模型（t 份拷贝）与工具化的一次性转换；b) 文本/图片分离统计的理由；c) ToolResults 计入 prompt 的观测完整性；d) 元原则（口径可比性）。
- **可能追问**："图片还能更省吗？"（降采样 1600px 已做；供应商侧按 tile 计费，分辨率直连成本——可提示用户拍摄场景）；"文本化丢图会怎样？"（结论性文字原文照录的要求 + focus 定向重读兜底，见 S1-15）。
- **常见错误**：没意识到统计排除图片的必要性；把 base64 大小说成 token 数（需要换算且供应商计费方式不同）。

**EN**: Q: Why not inline the image base64 in a multi-turn loop, and why does `promptChars` exclude images?
A: Every turn resends the full history — an inline image would be copied t times, both costly and near body-size limits; the image tool converts once to ≤1500 tokens of text. Stats hygiene: promptChars counts text only (a 500k-"char" image would destroy comparability); hasImage is a separate flag for bucketing; agent turns include ToolResults text in the count so context bloat is observable; completionChars records the last successful attempt. Meta-principle: a metric must be comparable or it should not exist.
Why asked: multimodal cost model + metrics discipline.
Keys: resend amplification; text/image split; observable context bloat; comparability principle.
Follow-ups: further image savings; text-conversion information loss.
Pitfall: conflating base64 size with token counts.

---

## 2G 测试与调试（4 题）

---

### S2-22. `ReActLoopTest` 用假网关 + 假工具测试循环策略——为什么这些分支"真机测不了"？FakeGateway 怎么脚本化？

**参考答案**：循环的价值全在"什么时候停、失败了怎么办"：轮数用尽、工具连续失败、工具抛异常、超时、模型乱调不存在的工具……这些场景真机验证要凑齐"网络超时 + 供应商抽风 + 模型特定行为"，成本高且**不可复现**——今天跑通了明天模型换个说法又复现不了。FakeGateway 实现只是把预设的 `AgentTurnResult` 列表按调用顺序吐出（`List<AgentTurnResult>` + 记录每轮收到的 tools 声明），FakeTool 是 `FakeTool(name) { ToolResult }`（可设为抛异常/计数调用次数）。测试用例直接对应策略：`第一轮调工具第二轮给出回答时按顺序产出事件`、`最后一轮不再向模型提供工具`（断言 `gateway.declaredTools.last().isEmpty()`）、`工具轮产出的正文进入思考轨迹而不是答案`、`工具抛异常时转为失败观测且循环继续`、`同一工具连续失败两次后被禁用并不再出现在工具声明里`。架构前提：`AgentLlmGateway`/`ToolProvider` 两个接口把 LLM 与工具变成可替换点——**接口不是为了优雅，是为了这些测试能存在**。

**解析**：
- **考察意图**：测试策略设计的顶层思维——什么测试放什么层，依赖注入与可测试性的因果。
- **答题要点**：a) "不可复现"的论证（外部依赖 × 模型行为的组合空间）；b) 假件的实现与脚本化语义；c) 测试用例与策略一一对应（能报出两三个用例名）；d) 接口存在的动机。
- **可能追问**："假网关测不出协议错误怎么办？"（协议错误由 AgentRequestBody/LlmStreamParser 的纯函数单测覆盖——测试分层各司其职）；"要不要集成测试打真 API？"（smoke 级别可以，进 CI 不行——成本与不稳定）；"怎么测 flow 的取消？"（runTest + cancel 收集协程，断言资源释放——属于补充方向）。
- **常见错误**：说"真机也能测"给不出复现性论证；不知道接口与假件的因果链。

**EN**: Q: Why is `ReActLoopTest` (fake gateway + fake tools) the right level, and why can't real-device testing cover it?
A: The loop's value is "when to stop, what to do on failure" — exercising those needs network timeouts × provider flakiness × specific model behavior, expensive and irreproducible on-device. FakeGateway replays a scripted `List<AgentTurnResult>` and records declared tools per turn; FakeTool can throw or count calls. Test cases map 1:1 to strategies (final-turn tool removal, disable-after-2-failures, exception→observation, tool-turn text routing). The `AgentLlmGateway`/`ToolProvider` interfaces exist precisely so these tests can exist.
Why asked: test-strategy top-level thinking.
Keys: irreproducibility argument; scripting semantics; case-to-strategy mapping; interface rationale.
Follow-ups: where protocol errors are tested; smoke tests; cancellation tests.
Pitfall: "real device can cover it".

---

### S2-23. "纯函数测试优先"：AgentRequestBody / LlmStreamParser / QaRetriever 为什么都能在 JVM 单测？这个架构约束怎么反过来塑造代码？

**参考答案**：三者共同点：**输入输出全是值**——(entries, tools) → JSON 字符串；SSE 行 → Delta 列表；(records, query) → 排序结果。没有 Android 框架类、没有网络、没有时钟（QaRetriever 不看时间，时间在 documentText 里由调用方传入的 recordDate 提供）——所以能跑在毫秒级的 JVM 测试里。这个约束反过来塑造代码：① **副作用被推向边缘**——网络在 LlmClient、时钟在 DateUtils、随机在 withRetry，核心逻辑全部收进纯函数文件；② **错误表现从"运行时玄学"变"字段断言"**——协议翻译错一个字段，表现原本是"第二轮 400"（真机抓包才能查），现在是 `assertEquals` 一行红；③ **测试即文档**——`LlmStreamParserTest` 里每家供应商的真实分片样例就是协议行为的活文档。行业对应：函数式核心 + 命令式外壳（functional core, imperative shell）。

**解析**：
- **考察意图**：可测试性架构——不是"写测试"而是"让代码可测"的双向因果。
- **答题要点**：a) 三个文件"为什么能纯"的具体性（无框架类/无时钟/无 IO）；b) 副作用推向边缘的重构方向；c) 玄学错误→字段断言的转化例子；d) functional core, imperative shell 术语点题。
- **可能追问**："LlmClient 本身怎么测？"（它是 imperative shell——网络层靠集成测试/真机；但其内部纯函数 parseRecordsJson/extractJsonObjects 若抽出去也可单测——诚实说当前没拆，是改进项）；"QaRetrieverTest 测什么？"（tokenize 边界、BM25 排序合理性、strongTerms 抽取）。
- **常见错误**：把可测性归功于"用了接口"（接口只解决依赖替换，纯函数解决的是确定性——两个机制）。

**EN**: Q: Why are AgentRequestBody / LlmStreamParser / QaRetriever pure JVM-testable, and how did that constraint shape the code?
A: Their inputs/outputs are pure values — no Android classes, no network, no clock (time comes in via recordDate). The constraint pushed side effects to the edges (network in LlmClient, randomness in withRetry), turned "second request 400s" mysteries into single-line field assertions, and made tests double as protocol documentation. Pattern: functional core, imperative shell.
Why asked: testability as an architectural force, both directions.
Keys: why each is pure; side-effect pushing; mystery→assertion; the pattern name.
Follow-ups: how LlmClient itself is tested; QaRetrieverTest contents.
Pitfall: crediting only "interfaces" — determinism is the other half.

---

### S2-24. 排查题：上线后收到反馈"Agent 第二轮请求 400"。给出完整排查路径，以及这个项目里哪一层测试本可以拦住它。

**参考答案**：排查路径：① **看 llm_call_logs**——errorType=http_400 的行、scene、attempts，确认失败集中在 Agent 路径第二轮（快路径正常）→ 缩小到"带工具历史的多轮请求"结构问题；② **对照协议规格**——第二轮请求体比第一轮多了 AssistantToolCalls + ToolResults 两类条目，四个高危点：tool_call_id 与 tool 消息没配对（OpenAI 必 400）、parameters 传了字符串（必 400）、Anthropic 把 tool_result 放错角色、tools 字段空数组；③ **本地复现**——AgentRequestBody 是纯函数：构造同样条目调 build()，直接 diff 出请求体与规格差异，一分钟定位，不用抓包；④ **修复 + 回归**——补单测断言该结构（含 tool_call_id 配对），以后回归拦住。哪层测试本可拦住：**AgentRequestBody 的逐字段单测**（这正是它做成纯函数的动机——注释原话："这类结构如果写错……真机排查成本极高；纯函数可以在 JVM 单测里逐字段断言"）。

**解析**：
- **考察意图**：故障排查的流程素养（观测→定位→复现→回归）+ 把纯函数设计与"可调试性"连起来。
- **答题要点**：a) 从日志（errorType 聚合）开始的定位路径——可观测性的实战价值回收（S1-27 的伏笔）；b) 第二轮特有的四个高危结构点；c) 纯函数本地复现的效率论证；d) 回归测试闭环。
- **可能追问**："如果日志也看不出呢？"（加一轮级 promptChars 对比——第二轮 prompt 异常膨胀提示结构错误；或临时加详细日志分级）；"供应商行为不一致怎么排查？"（对每个供应商保留真实响应样本进测试 fixture——LlmStreamParserTest 就是这么做的）。
- **常见错误**：第一步就说"打 logcat 重现"（有观测数据不用是浪费）；说不出四个高危点。

**EN**: Q: Users report "agent's second-turn request 400s". Walk the diagnosis and name the test layer that should have caught it.
A: 1) `llm_call_logs`: errorType=http_400, scene, attempts — confirm it's agent-path turn 2 only. 2) Protocol checklist for newly added entry types: tool_call_id pairing, parameters-as-string (instant 400), wrong role for tool_result, empty tools array. 3) Reproduce locally: `AgentRequestBody` is a pure function — build the same entries, diff the JSON against spec in a minute, no packet capture. 4) Fix + add a field-level regression test. The layer: `AgentRequestBody` unit tests, whose whole reason for being pure is exactly this.
Why asked: diagnosis pipeline plus the debuggability payoff of pure functions.
Keys: logs-first; the four hot spots; local repro; regression loop.
Follow-ups: when logs aren't enough; per-provider fixtures.
Pitfall: starting with logcat reproduction.

---

### S2-25. 本项目约 100 条 JVM 单测 + 仅 1 个 androidTest——为什么测试重心这么偏？哪些东西 JVM 测不了、怎么补？

**参考答案**：重心偏 JVM 是刻意的：核心复杂度（循环策略、协议解析、检索打分、归一化规则、日期处理、迁移结构）全部被抽成无 Android 依赖的纯函数或接口隔离单元——它们占 bug 面的 90%+，而 JVM 测试秒级、可进 CI、可枚举边界（QaRetrieverTest 的 tokenize 边界用例、DetectAnomaliesUseCaseTest 的规则组合）。**JVM 测不了的**：① Room 实际行为（迁移真跑、Flow 响应）→ Room 自身的 instrumented 测试库或迁移 helper，本项目以 SchemaMigrationStructureTest 折中（JVM 断结构）+ 数据库版本化的 schema JSON 兜底；② Compose UI（渲染、交互）→ 目前靠手动验收，正确的补充是 Compose 测试（createComposeRule）挑关键路径 + 截图测试；③ 网络与真实供应商行为 → fixture 样本进 LlmStreamParserTest（真实 SSE 响应当测试输入），端到端 smoke 手动跑；④ Worker/闹钟（MedicationAlarmScheduler 有 JVM 测试——调度逻辑被抽出来了，系统闹钟行为才需要真机）。元原则：**把"不可测的壳"做薄，把"可测的核"做厚**。

**解析**：
- **考察意图**：测试金字塔在 Android 的实际落地——能说清"哪些测不了、用什么补"比"测试很重要"值钱得多。
- **答题要点**：a) 复杂度分布论证（核 vs 壳）；b) 四类 JVM 盲区及各自的补充手段（含本项目现状与改进方向）；c) fixture 化真实响应的做法；d) 元原则。
- **可能追问**："CI 上 JVM 测试怎么跑？"（gradle testDebugUnitTest，本项目还有沙箱环境跑测的实战经验—— daemon 僵尸/锁文件处理）；"测试覆盖率多少算够？"（不看数字看风险：循环策略与协议解析 100% 是目标，UI 可以低）。
- **常见错误**：背测试金字塔不给项目实例；认为 androidTest 少 = 测试差（要看核在哪）。

**EN**: Q: Why ~100 JVM tests but only 1 androidTest, and what can't JVM tests cover?
A: Deliberate: the core complexity (loop strategy, protocol parsing, BM25, normalization, dates, migration shape) was extracted into Android-free pure functions and interface-isolated units — the bulk of the bug surface, testable in seconds in CI. JVM blind spots: real Room behavior (mitigated by the JVM structure test + versioned schema JSONs), Compose UI (manual today; compose tests are the right addition), live provider behavior (real SSE samples as fixtures), system alarm behavior (scheduler logic already extracted). Meta-principle: keep the untestable shell thin, the testable core thick.
Why asked: the Android test pyramid in practice.
Keys: complexity distribution; four blind spots and mitigations; fixture approach.
Follow-ups: CI setup; coverage philosophy.
Pitfall: equating few androidTests with weak testing.

---

---

# Set 3 · 通用 Agent 知识（21 题）

## 3A Agent 基础与范式（4 题）

---

### S3-1. 什么是 Agent？最小构成是什么？它和 Chain / Workflow / Copilot 的边界在哪？

**参考答案**：Agent = **LLM 在循环中自主决定"下一步做什么"，并以工具与环境交互，直到达成目标**。最小构成四件套：① LLM（推理引擎）、② 工具集（能力边界）、③ 循环 + 终止条件（把单次调用变成过程）、④ 状态（至少是对话历史）。边界：**Chain** 是固定管道（A→B→C，无分支决策）；**Workflow** 是人定义好的控制流、LLM 只填节点（可分支可循环但拓扑是写死的）；**Agent** 是模型自己决定控制流（调不调工具、调哪个、何时停）；**Copilot** 是人在环的 Agent（每步等人确认）。灰色地带要敢说：本项目 fast path 是 Chain（检索→生成），Agent path 才是 Agent——同一个功能里两者共存，靠路由分治。学术对应：Russell & Norvig 的"理性Agent 感知-行动循环"与 LLM Agent 的映射（感知=用户问题+工具结果，行动=工具调用）。

**解析**：
- **考察意图**：概念地基题，人人会答、边界容易糊——考的是能否给出**可操作的判据**（谁决定控制流）而非比喻。
- **答题要点**：a) 四件套最小构成；b) 一条分界线贯穿四种形态（控制流的决定权）；c) 用本项目说明共存与路由；d) 敢于说"很多叫 Agent 的产品其实是 Workflow"。
- **可能追问**："Autonomy 程度光谱怎么排？"（Chain < Workflow < Copilot < Agent）；"Agent 一定是多轮对话吗？"（不一定——单目标循环即 Agent，对话只是交互形式）；"LLM-only 循环（无工具）算 Agent 吗？"（弱算——有循环与终止判定，但能力受限于模型内部知识，业界一般不算）。
- **常见错误**：用"像人一样思考"这类比喻糊弄；说不出 Chain 与 Agent 的实际分界。

**EN**: Q: What is an agent, its minimal components, and the boundary vs chain/workflow/copilot?
A: An agent is an LLM deciding in a loop what to do next, acting via tools until the goal is met. Minimal four: LLM, toolset, loop with termination condition, state (at least the conversation). One dividing line — who decides the control flow: chain (nobody, fixed pipe) < workflow (developer) < copilot (human approves) < agent (model). This project's fast path is a chain; its agent path is an agent — coexistence via routing.
Why asked: concept foundations with an operational criterion.
Keys: four components; the control-flow line; coexistence example.
Follow-ups: autonomy spectrum; tool-less loops.
Pitfall: anthropomorphic hand-waving.

---

### S3-2. ReAct、Plan-and-Execute、Reflexion、Tree-of-Thoughts 各是什么？分别适合什么任务？

**参考答案**：**ReAct**：思考-行动-观察交错，每步根据最新观察决定下一步——适合环境反馈丰富的任务（检索、API 操作），实现最简单，错误可即时纠偏；弱点是短视（无全局计划）。**Plan-and-Execute**：先让强模型产出完整计划，再逐步执行（执行器可换轻模型）——适合长链条任务、成本敏感场景（计划一次、执行便宜），弱点是环境变化时计划过时（需 replan 机制）。**Reflexion**：执行后自我反思生成语言化教训，存入记忆供下次尝试——适合有明确成功判据、可多轮重试的任务（代码修复类），代价是多次尝试的 token。**ToT**：分支探索多个推理路径并回溯打分——适合需要系统性搜索的小空间问题（解谜、数学），成本最高，工程落地最少。谱系：ToT（搜索）> Reflexion（学习）> P&E（计划）> ReAct（反应式）。本项目选 ReAct 的理由：工具反馈即时、任务链条短（≤5 轮）、错误可由 observation 纠偏——不需要全局计划。

**解析**：
- **考察意图**：Agent 范式的横向比较——高频题，考察是否知道每个范式的适用条件而非名词解释。
- **答题要点**：a) 每个范式一句话本质 + 适用条件 + 弱点；b) 复杂度/成本谱系；c) 结合项目论证 ReAct 的选择；d) 混合形态的存在（ReAct 内嵌 plan 步骤、P&E 的 replan 就是 ReAct 化）。
- **可能追问**："本项目如果要做'帮我制定三个月健康改善计划'会怎么改？"（P&E 更合适——计划生成一次、执行可分天拆步；诚实说当前 ReAct 不适合跨会话长任务）；"Reflexion 的记忆存哪？"（语言化经验进外部记忆，区别于普通对话历史）。
- **常见错误**：四个范式混为一谈或只认识 ReAct；说不出各自的失效场景。

**EN**: Q: Compare ReAct, Plan-and-Execute, Reflexion, Tree-of-Thoughts and their fit.
A: ReAct interleaves thought-action-observation — best with rich feedback, simple, myopic. Plan-and-Execute: one strong-model plan, cheap executor — long chains, needs replanning. Reflexion: verbal self-lessons stored across attempts — tasks with clear success criteria. ToT: branching search with backtracking — small search spaces, highest cost, rare in production. Spectrum: ToT > Reflexion > P&E > ReAct. This project chose ReAct: immediate tool feedback, short chains (≤5 turns), observation-driven correction.
Why asked: paradigm comparison with applicability conditions.
Keys: essence + fit + weakness per paradigm; cost spectrum; the project's justification.
Follow-ups: a planning-heavy variant task; where Reflexion memories live.
Pitfall: knowing only ReAct.

---

### S3-3. 什么时候**不该**用 Agent？给出至少四个反指征。

**参考答案**：① **单步可解**——问题指向明确、一次检索一次生成就够：Agent 只是把 1 次请求变成 2~5 次，质量不涨成本超线性涨（本项目 fast path 存在的全部理由）；② **确定性要求高**——输出必须是可复现、可审计的规则结果（异常判定、单位换算）：让模型循环决策等于引入不可控变量，确定性代码才是对的工具（本项目异常检测在 `DetectAnomaliesUseCase` 不在 Agent）；③ **低延迟交互**——每轮 LLM 请求秒级，Agent 路径天然比快路径慢数倍，输入法联想这类场景直接排除；④ **可测试性优先**——循环策略测试要靠脚本化假件，提示词与循环行为对回归测试不友好，强审计场景（金融/医疗写入）慎用；⑤ **成本敏感的大流量**——每轮重发历史 + schema，流量 × 轮数的乘积可以直接烧穿预算。反过来说，Agent 的正指征：多步交叉验证、工具反馈丰富、允许一定延迟、失败可降级。

**解析**：
- **考察意图**："负优化"判断力——资深面试官最想听的答案，避免候选人把 Agent 当锤子。
- **答题要点**：a) 至少四个反指征各配一句论证；b) 每条尽量带本项目的对应设计（fast path / 检测用例 / 快路径保留）；c) 正反指征成对出现（知道何时用、更要知道何时别用）。
- **可能追问**："怎么在产品里识别'不该用 Agent'的场景？"（埋点对比两条路径的轮数/时长/满意度——llm_call_logs 的 attempts/latency 就是原料）；"已经上了 Agent 怎么退？"（路由层保留——本项目 fast path 从未下线，就是退路）。
- **常见错误**：只会说"简单任务不用 Agent"（太弱——要给出成本/确定性/延迟的具体机制）。

**EN**: Q: When should you NOT use an agent? Give at least four contraindications.
A: Single-step solvable (agent turns 1 request into 2–5 at superlinear cost — the reason a fast path exists); determinism required (rule-based anomaly detection stays in code); latency-sensitive interactions (each turn is seconds); auditability/testability-first domains; high-volume cost-sensitive traffic (history resend × volume). Positive indicators mirrored: multi-step cross-checking, rich tool feedback, tolerance for latency, safe degradation.
Why asked: the "negative optimization" judgment seniors look for.
Keys: four+ contraindications with mechanisms; project examples; paired indicators.
Follow-ups: how to detect mis-routed traffic; exit strategy.
Pitfall: "don't use agents for simple tasks" without mechanisms.

---

### S3-4. Agent 循环的终止设计有哪几种？各有什么坑？本项目的"最后一轮撤工具"属于哪类？

**参考答案**：四类：① **显式终止工具**（模型调 `finish`/`submit_answer` 提交）——语义最明确，坑是模型可能忘了调直到轮数耗尽，且浪费一次工具调用预算；② **协议判定**（本轮无 toolCalls 即终止）——依赖"模型不调工具=想作答"的约定，简单但失控时会空转；③ **轮数/成本上限**（MAX_TURNS）——兜底而非机制，坑是"到点没答案"（本项目用最后一轮撤工具让"到点"自动变成 ②，两个机制合流——这是它的设计亮点：兜底机制与正常机制走同一条路径）；④ **结构化输出标记**（要求最终回答以特定标记开头，检测到即停）——解析脆弱、易与正文冲突。坑位对比：① 最可控但多一次调用；② 最简单但需要兜底；③ 必须有；④ 尽量别用。本题是 S1-3 的通用化：考的是把项目设计映射回设计空间的能力。

**解析**：
- **考察意图**：终止机制的设计空间全景——S1-3 考项目细节，本题考通识归类，两者一起准备。
- **答题要点**：a) 四类机制 + 各自一个坑；b) ②+③ 合流的设计论证（本项目的做法可迁移）；c) 实践推荐组合（协议判定为主 + 上限兜底 + 可选显式工具）。
- **可能追问**："finish 工具在流式下怎么处理？"（工具调用分片照常累积，识别到 finish 后停止循环并把 args 里的答案字段取出）；"多 Agent 系统的终止呢？"（编排者判定或投票，复杂度更高）。
- **常见错误**：只知道 MAX_TURNS 一种；不知道②③可以合流。

**EN**: Q: What are the termination designs for agent loops, their pitfalls, and where does this project's final-turn tool removal fit?
A: (1) Explicit finish tool — clearest semantics, wastes a call, models forget it; (2) protocol-based (no toolCalls this turn) — simplest, needs a backstop; (3) turn/cost caps — pure backstop, risks "no answer at cap"; (4) structured markers — brittle. The project merges (2) and (3): removing tools on the final turn makes the cap degrade into the protocol termination — one code path for both. Recommended combo: (2) primary + (3) backstop.
Why asked: the generalized version of S1-3.
Keys: four mechanisms + pitfalls; the merging argument; the practical combo.
Follow-ups: finish-tool under streaming; multi-agent termination.
Pitfall: knowing only MAX_TURNS.

---

## 3B 上下文工程与记忆（4 题）

---

### S3-5. 上下文窗口预算的三种基本手段：截断、摘要、检索——各自的代价与组合方式？

**参考答案**：**截断**：零成本零失真风险（对保留部分），但丢尾部信息且截断点可能砸碎语义单元（一条记录、一个 JSON）——缓解：按语义单元截断 + 显式省略标注（本项目 observation/摘要的"已省略 N 字符"）；**摘要**：压缩率高且保大意，但**数值保真无保证**（摘要模型会把 6.2 写成"略高"）——健康/金融数值场景慎用，适合对话历史这类容忍损失的文本；**检索**：只放相关内容，最省且相关度最高，但依赖检索质量（漏召回直接变成上下文缺失）且需要基础设施（索引）。组合范式：**检索为骨架 + 截断为细节护栏 + 摘要只用在容忍损失的层**——本项目四层预算就是这个组合：Agent 路径用检索（工具）、observation 截断、输出上限；快路径用检索（BM25）+ 预算截断，全程没有数值摘要。元决策：**先问"这层上下文容不容得下信息损失"，再选手段**。

**解析**：
- **考察意图**：上下文工程的手段论——三种手段的失真特性是核心（尤其"摘要毁数值"这个健康场景痛点）。
- **答题要点**：a) 三手段各自代价一句；b) 失真容忍度作为选型第一问；c) 本项目四层预算的映射；d) 语义单元截断与显式标注两个细节。
- **可能追问**："对话历史为什么可以摘要？"（对话结论性内容多、数值已被记录沉淀，损失可容忍）；"什么信号说明预算设小了？"（observation 频繁触发截断 + 模型追问'数据不全'——可从日志 observation 长度分布看出来）。
- **常见错误**：把摘要当万能；不知道截断要保语义单元。

**EN**: Q: Compare the three context-budget tools — truncation, summarization, retrieval — and how to combine them.
A: Truncation: zero cost, keeps fidelity, but cuts mid-semantic-unit and loses the tail — mitigate with unit-aware cuts + explicit omission notes. Summarization: high compression, **untrustworthy for numbers** (6.2 becomes "slightly high") — only for loss-tolerant layers like chat history. Retrieval: most relevant per token, but recall misses become context holes. Combine: retrieval as the skeleton, truncation as the detail guardrail, summarization only in loss-tolerant layers. This project's four-layer budget does exactly that — no numeric summarization anywhere. First question: can this layer tolerate information loss?
Why asked: context-engineering methodology.
Keys: per-tool costs; the loss-tolerance first question; the project mapping.
Follow-ups: summarizing chat history; signals a budget is too small.
Pitfall: summarization as a universal tool.

---

### S3-6. Agent 的长期记忆有哪些类型？移动端单机场景怎么做长期记忆？

**参考答案**：类型学（按内容）：**情景记忆**（发生过什么——会话历史、用户偏好事件）、**语义记忆**（事实——用户的指标基线、慢病清单）、**程序记忆**（怎么做——可复用的提示词片段、工作流配置）。按机制：上下文内记忆（对话历史）、外部存储记忆（数据库/文件，检索注入）、参数化记忆（微调，工程上少用）。移动端单机做法：① **结构化优先**——能落表的绝不放自由文本（本项目的"记忆"其实是 Room 里的健康记录本体：成员档案、指标历史、告警——它们就是语义记忆，问答时经检索注入，这比"向量记忆库"可靠得多）；② 注入策略与预算绑定（每次提问检索 Top-K 而非全量——S3-5）；③ 情景记忆落 QAHistory 表（可回看、可清理）；④ 隐私约束下的取舍：不上云 = 跨设备同步缺失，用导出/导入补偿。反直觉观点要敢讲：**很多"记忆系统"需求其实是数据建模问题**——先把数据建对，记忆就自然存在。

**解析**：
- **考察意图**：记忆系统的分类学与落地判断；考是否能把"记忆"祛魅为"数据 + 注入策略"。
- **答题要点**：a) 三类内容记忆 + 三种机制；b) 移动端结构化优先的论证（Room 即记忆）；c) 注入策略与预算联动；d) 隐私取舍。
- **可能追问**："用户偏好（如'只关心血糖'）存哪？"（结构化偏好表 + 注入系统提示；不要让模型从历史里猜）；"记忆冲突怎么处理？"（新数据覆盖 + 时间戳仲裁——其实就是记录更新语义）。
- **常见错误**：上来就谈向量记忆库（跳过了数据建模这层）；分不清三类记忆。

**EN**: Q: What types of long-term memory do agents have, and how do you build it in an offline-first mobile app?
A: Episodic (what happened — history), semantic (facts — baselines, conditions), procedural (how-to — reusable prompts). Mechanisms: in-context, external store + retrieval injection, parametric (rare). On-device: structured-first — the Room tables ARE the semantic memory (records, alerts), injected via retrieval per question; episodic memory lives in QAHistory; privacy trades away sync, compensated by export/import. The sharp take: most "memory system" requirements are data-modeling problems in disguise.
Why asked: demystifying memory into data + injection.
Keys: the taxonomy; structured-first argument; budget-coupled injection.
Follow-ups: storing preferences; conflicting memories.
Pitfall: jumping to vector memory stores.

---

### S3-7. 多轮对话历史管理：全量保留、滚动窗口、摘要压缩、相关性裁剪——怎么选？

**参考答案**：**全量保留**：零信息损失，成本随轮数线性涨、指令遵循随长度劣化（lost-in-the-middle）；适合 ≤10 轮短会话（本项目的 Agent 循环 ≤5 轮就是全量——短循环不值得任何花活）。**滚动窗口**：保最近 N 轮，便宜且简单，但早期约束（"我是孕妇"）会被滚掉——需要把**持久约束提炼到系统提示**（system 不滚动）再滚动 user/assistant。**摘要压缩**：把老轮次压成摘要保关键信息，适合几十轮的长会话客服场景——数值场景有失真风险（S3-5），摘要对象应是"意图与结论"而非数字。**相关性裁剪**：按当前问题检索历史相关片段注入——最贵也最精细，历史很大时（数百轮）唯一可行。选型公式：会话长度 × 信息半衰期——短会话全量、长会话 system 提炼 + 窗口、超长会话检索。另注意：工具轮的 toolCalls/toolResults 必须成对保留（协议要求，S1-13），压缩时不能只压一半。

**解析**：
- **考察意图**：对话管理的基本功；考协议约束（工具消息成对）这种实践细节。
- **答题要点**：a) 四手段的适用区间；b) "约束提炼到 system"的关键技巧；c) 本项目短循环全量的取舍；d) 工具消息成对保留的协议硬约束。
- **可能追问**："历史里有敏感信息要清理怎么办？"（会话级删除即可——本项目 QAHistory 支持按成员清空；不可变的只有日志元数据）；"压缩摘要谁来做？"（轻模型离线做，避免挤占主对话延迟）。
- **常见错误**：不知道 lost-in-the-middle；压缩时破坏工具消息配对。

**EN**: Q: Full history vs rolling window vs summarization vs relevance trimming for multi-turn history?
A: Full: ≤10-turn sessions (this project's ≤5-turn loop keeps everything — short loops need no tricks). Rolling: cheap, but early constraints get evicted — extract durable constraints into the system prompt (which doesn't roll). Summarization: long support sessions; summarize intent/conclusions, never numbers. Relevance trimming: hundreds of turns, retrieval-injected. Selection = session length × information half-life. Protocol constraint: toolCalls/toolResults must stay paired — compress in whole units.
Why asked: history-management fundamentals plus protocol pairing.
Keys: applicability ranges; constraint extraction; the project's choice; pairing constraint.
Follow-ups: sensitive-data cleanup; who does the summarizing.
Pitfall: losing tool-message pairing during compression.

---

### S3-8. Prompt caching / KV cache 是怎么省钱的？为什么"只追加不修改"的消息历史命中率最高？

**参考答案**：Transformer 自回归生成的成本大头是每 token 对全部前文做注意力——KV cache 把前文的 Key/Value 缓存下来，前缀相同则**直接复用计算**。供应商侧的 prompt caching：对重复前缀收低价（典型 1/10）甚至免费，命中条件是**请求前缀逐字节一致**。为什么"只追加"最优：消息历史的每次请求 = 上次请求 + 新增轮次——前缀天然稳定，全量命中；任何中间修改（编辑某条消息、重排顺序、改 system 措辞）都会从修改点开始失效。本项目的契合点：Agent 循环的消息是纯追加（新轮次 append），observation 截断发生在**首次写入前**（历史一旦写入不再变），system prompt 与工具 schema 恒定且前置——三个条件都满足，缓存命中率理论上接近 100%。工程注意：缓存通常按分钟级 TTL，会话间隔过长会失效；多供应商的缓存语义不同（自动 vs 需显式标记），跨供应商抽象层要小心。

**解析**：
- **考察意图**：成本优化的原理层——考候选人是否理解 KV cache 与 prompt caching 的关系（很多人只知道"便宜"不知道"为什么能便宜"）。
- **答题要点**：a) 自回归注意力的成本结构 → 缓存省的是什么；b) 前缀命中条件；c) 只追加历史的原理与本项目的三个契合点；d) TTL 与供应商差异两个工程注意。
- **可能追问**："温度非 0 影响缓存吗？"（不影响——缓存的是前文计算不是采样）；"为什么 schema 要前置？"（前缀稳定性——schema 在 system 后、消息前，位置固定才能整段命中）。
- **常见错误**：把 prompt caching 说成"客户端缓存响应"；不知道逐字节一致的要求。

**EN**: Q: How does prompt caching (KV cache) save money, and why does append-only history maximize hit rates?
A: Autoregressive attention recomputes over the whole prefix; caching reuses the prefix's K/V computation, so providers bill repeated prefixes at a discount — with a byte-exact prefix requirement. Append-only history = each request is the previous prefix plus new turns → near-100% hits; any mid-history edit invalidates from that point. This project fits: append-only agent turns, truncation applied before first write, constant system + schema placed up front. Watch TTLs and per-provider caching semantics.
Why asked: the physics behind cost optimization.
Keys: attention cost structure; byte-exact prefixes; the project's three fits; TTL/provider variance.
Follow-ups: temperature vs caching; schema placement.
Pitfall: calling it "client-side response caching".

---

## 3C 检索增强生成 RAG（4 题）

---

### S3-9. BM25、向量检索、混合检索（RRF）三者怎么选？RRF 的公式与优点？

**参考答案**：**BM25**：词面匹配 + 饱和 + 长度归一——强关键词域、可解释、零模型依赖；**向量**：语义泛化（同义改写、跨语言）、需要 embedding 模型与索引（HNSW/IVF）、黑盒打分；**混合（RRF 融合）**：两路各召回 Top-N，按排名融合——RRF(d) = Σ 1/(k + rank_i(d))，k 常取 60。优点：免调两路分数尺度（BM25 分数量级与余弦相似度不可比，直接加权是灾难）、对单路失效鲁棒（一路空转另一路兜底）、实现十行代码。选型决策树：查询词与文档词面重合度高（专有名词、指标名、代码标识符）→ BM25 优先；同义改写多（自然语言问句 vs 叙述文档）→ 向量优先；两者都有 → 混合。本项目的位置：指标名强关键词域选 BM25（S1-18 详论），若升级叙述文本检索则 bge-small-zh-v1.5 + RRF（评估文档的既定结论）。

**解析**：
- **考察意图**：RAG 检索层的选型公式——高频题；RRF 的公式级掌握是区分度所在。
- **答题要点**：a) 三者特性对比（词面/语义/融合）；b) RRF 公式 + 为什么免调尺度；c) 选型决策树；d) 本项目位置与升级路径。
- **可能追问**："k=60 的作用？"（平滑排名差异——第 1 名与第 2 名的差距不至于压倒一切，让多路贡献均衡）；"向量索引为什么不用暴力扫？"（千级可以暴力扫——诚实说规模小时 HNSW 是过度设计）；"重排序（reranker）了解吗？"（交叉编码器精排，成本高、只对 Top 候选做）。
- **常见错误**：说不出 RRF 免调尺度的原因（两路分数不可比）；把 HNSW 当千级语料的必需品。

**EN**: Q: BM25 vs vector vs hybrid with RRF — how to choose, and what's the RRF formula?
A: BM25: lexical, saturated, explainable, model-free — strong-keyword domains. Vector: semantic generalization, needs embedding + ANN index, opaque scores. RRF fuses two ranked lists: score(d) = Σ 1/(k + rank_i(d)), k≈60 — rank-based so incomparable score scales don't matter, and it degrades gracefully when one path fails. Decision tree: lexical overlap high → BM25; synonym-heavy → vector; both → hybrid. This project: BM25 now; the planned upgrade path is on-device bge-small-zh-v1.5 + RRF for narrative text.
Why asked: retrieval selection with the formula as the differentiator.
Keys: three-way comparison; RRF formula and scale-independence; decision tree.
Follow-ups: k's smoothing role; brute-force at small scale; rerankers.
Pitfall: weighted fusion of incomparable scores.

---

### S3-10. Embedding 模型怎么选？为什么"端侧小模型"在这个项目里是既定结论？

**参考答案**：选型维度：① **语种匹配**（中文语料必须中文优化的模型——bge 家族/m3e；英文模型对中文召回崩）；② **维度与质量**（512~1024 维主流，维度越高存储/计算越贵，MTEB/C-MTEB 榜单看综合分但不迷信——**用自己的域内查询-文档对实测 recall@k 才算数**）；③ **部署位置**——本项目约束是隐私：云端 Embedding API 意味着把健康文本送第三方，破坏"数据不上传"承诺 → 端侧；④ **端侧可行性**——bge-small-zh-v1.5（~24M 参数）量化后几十 MB，ONNX/MediaPipe 可跑，千条语料建索引秒级；大模型端侧跑不动。⑤ **更新与运维**——embedding 模型换版本 = 全量重嵌入（向量不兼容），选型时就要接受这个锁定。结论（评估文档实测推导）：结构化记录检索不需要 embedding（BM25+别名已够）；叙述文本（出院小结）需要且必须端侧——**需求分析先于模型选型**，这个顺序本身就是答案的一半。

**解析**：
- **考察意图**：embedding 选型的完整维度（语种/维度/部署/锁定）；"端侧"结论的隐私论证。
- **答题要点**：a) 五个维度；b) C-MTEB 榜单的参考性与域内实测的必要性；c) 云端 API 的隐私矛盾；d) bge-small-zh-v1.5 的量化可行性数字；e) 模型锁定成本。
- **可能追问**："召回不好怎么排查？"（分桶看 badcase：检索层漏 vs 排序层错 vs 数据本身没有——三层各用不同工具）；"bge-small 会不会不够好？"（千条语料小模型绰绰有余；瓶颈通常在文档切分不在模型）。
- **常见错误**：只看榜单选型；没想到 embedding 版本锁定。

**EN**: Q: How do you choose an embedding model, and why is an on-device small model the settled conclusion here?
A: Dimensions: language match (Chinese-optimized: bge family), dimension vs quality (C-MTEB as a shortlist, but domain-pair recall@k evals decide), deployment (cloud embedding APIs violate the no-upload promise → on-device), feasibility (bge-small-zh-v1.5 ~24M params, tens of MB quantized, seconds for 1k docs), and version lock-in (changing the model = full re-embedding). Sequencing matters: needs analysis (structured records don't need embeddings; narrative text does) precedes model choice.
Why asked: complete embedding selection methodology.
Keys: five dimensions; domain evals over leaderboards; privacy constraint; lock-in cost.
Follow-ups: debugging poor recall; is small enough.
Pitfall: leaderboard-only selection.

---

### S3-11. RAG 系统怎么评估？没有标注数据怎么起步？

**参考答案**：分两层。**检索层**：recall@k / MRR / nDCG——需要"哪个文档该被召回"的标注；冷启动做法：让领域专家（或用强模型+人工抽检）为 50~100 个真实问题标注应命中的记录 ID，千条语料半天量级，性价比极高。**生成层**：① **忠实度/groundedness**——答案中的断言能否被检索到的上下文支持（RAGAS 一类框架的思路；本项目用 evidence 逐字复用 + [n] 引用把忠实度做成**结构性保证**——比事后评估更强的设计）；② **答案相关性**——回答了没有；③ **数值一致性**——答案里的数字与上下文数字逐一比对（规则断言，本项目天然可做：解析答案中的数值与 evidence 中的记录对账）。**无标注起步路径**：先做规则断言（数值对账、引用编号校验、免责声明存在性——全部零标注）+ LLM-as-judge 打相关性分（S3-20 讲偏差）+ 人工抽检 30 条建 baseline；上线后把真实问题流沉淀为评测集。评估要**进 CI 回归**（改提示词/换模型跑一遍），否则评估集会腐烂。

**解析**：
- **考察意图**：评估体系设计——区分"知道 RAGAS 名词"与"能从零搭评估"的候选人。
- **答题要点**：a) 两层评估各自指标；b) 冷启动标注的性价比方案；c) 结构性保证 > 事后评估的设计哲学（evidence 复用是亮点）；d) 零标注起步三步；e) CI 回归防腐烂。
- **可能追问**："LLM-as-judge 的分准吗？"（有系统性偏差，见 S3-20；先规则后 judge）；"评测集多大够？"（50~100 条能抓主要回归，300+ 才谈统计显著）。
- **常见错误**：只会说"用 RAGAS"；不知道结构性设计可以替代部分评估。

**EN**: Q: How do you evaluate a RAG system with no labeled data?
A: Two layers. Retrieval: recall@k/MRR — bootstrap with 50–100 expert-labeled (or strong-model-labeled + spot-checked) queries; half a day at this corpus size. Generation: faithfulness (does each claim follow from retrieved context), relevance, and numeric consistency — rule-based assertions that every number in the answer reconciles with the evidence. This project makes faithness *structural* by reusing tool output verbatim + [n] citations, which beats post-hoc evaluation. Zero-label start: rule assertions → LLM-as-judge for relevance → 30-sample human baseline; grow the eval set from real traffic; wire into CI so it doesn't rot.
Why asked: evaluation from scratch vs knowing RAGAS by name.
Keys: two layers; bootstrap labeling; structural guarantees; CI regression.
Follow-ups: judge reliability; eval set size.
Pitfall: framework-name-dropping only.

---

### S3-12. 结构化数据的 RAG："查数值"场景为什么关键词/SQL 往往优于向量？什么时候混合？

**参考答案**：数值查询的语义单元是**精确字段匹配**（"血糖 2026-03 的值"），不是模糊语义——向量把记录嵌入后，"6.2 mmol/L" 与 "5.8 mmol/L" 的向量几乎一样近（语义上都是血糖值），**区分度来自数值与日期这些结构字段，而向量恰恰抹平它们**。所以正确路径：Text-to-SQL / API 调用（Agent + 工具就是本项目的做法——`search_records` 按类型+时间过滤，结构精确）或关键词过滤（BM25）先缩小范围。向量的位置在**叙述性内容**：出院小结的"建议低盐低脂饮食"与问题"饮食要注意什么"之间没有词面重合，语义检索才有用。混合模式：结构过滤（SQL/关键词）做漏斗 + 语义检索做细排——先按成员/指标/时间窗过滤到几十条，再对叙述部分做向量排序。反面教训：对结构化表格全量做向量检索是常见反模式——费钱、慢、还答不准。

**解析**：
- **考察意图**：RAG 边界判断——很多候选人把 RAG 等同于向量检索，本题考"数据形态决定检索形态"。
- **答题要点**：a) 向量抹平数值区分度的机制论证；b) 结构过滤/Text-to-SQL/Agent 工具三条精确路径；c) 向量的正确战场（叙述文本）；d) 漏斗式混合架构。
- **可能追问**："Text-to-SQL 的风险？"（生成错误 SQL 查错数据/注入——参数化 + 白名单表列 + 只读账号，本项目用预定义工具回避了自由 SQL）；"混合的漏斗次序能反吗？"（先语义后结构过滤也行，漏斗方向取决于选择性——结构字段选择性强就先结构）。
- **常见错误**：给结构化数值场景推荐向量库；不知道 Text-to-SQL 的安全约束。

**EN**: Q: For structured "look up the value" queries, why do keywords/SQL beat vectors, and when to mix?
A: Vectors embed "6.2 mmol/L" and "5.8 mmol/L" nearly identically — discrimination lives in the structured fields (value, date, type), which embeddings flatten. Precision comes from Text-to-SQL / parameterized tools (this project's `search_records`) or keyword filtering. Vectors belong to narrative text ("diet advice" vs "低盐低脂"), where lexical overlap vanishes. Hybrid: structural filter as the funnel, semantic ranking on the survivors. Anti-pattern: vectorizing the whole structured table.
Why asked: data shape determines retrieval shape.
Keys: the flattening mechanism; three precise paths; vector's real battlefield; funnel hybrid.
Follow-ups: Text-to-SQL risks; funnel ordering.
Pitfall: vectorizing structured tables wholesale.

---

## 3D 函数调用与协议（3 题）

---

### S3-13. Function Calling 的实现原理是什么？模型是怎么"学会"调用工具的？约束解码起什么作用？

**参考答案**：原理：工具 schema（JSON Schema）注入 prompt（或在训练时见过大量工具使用轨迹），模型生成的 token 序列里包含工具调用结构；解码侧（或供应商推理栈）用**约束解码/语法引导采样**保证输出符合 schema——非法 JSON 的 token 概率被屏蔽，所以原生 FC 的 JSON 合法率远高于"求模型输出 JSON 再解析"。模型"学会"的两条路径：① **训练时**——后训练阶段用工具调用轨迹 SFT/RLHF，模型习得"何时调、怎么填参"；② **推理时**——schema 作为上下文条件，模型泛化到没见过的工具（icf：in-context function calling）。工程含义：① schema 的字段描述就是"推理时的微调信号"，写得差模型就填得差（呼应 S1-10）；② strict/schema 约束模式（OpenAI structured outputs、部分供应商的 schema enforcement）可进一步保证类型合法，但过度约束会牺牲灵活性；③ 分片流式输出时约束作用于每个 token，这正是参数逐片返回的原因（S1-12 的原理根基）。

**解析**：
- **考察意图**：FC 的原理层——多数人只会用 API，能讲到约束解码与训练路径的是少数。
- **答题要点**：a) prompt 注入 + 生成结构 + 约束解码的三段机制；b) 训练 vs 上下文两条学习路径；c) schema 描述即信号（连接 S1-10）；d) 流式分片的原理根源。
- **可能追问**："模型填错参数是 schema 问题还是能力问题？"（先查 schema 描述与类型定义——大部分是描述问题；再看是否超出模型能力）；"约束解码会降智力吗？"（屏蔽合法 token 外的选择不改变好 token 的相对排序，但过窄 schema 会逼模型硬填）。
- **常见错误**：认为 FC 是"模型输出了特殊 JSON 标记"的黑盒魔法；不知道分片与约束解码的因果关系。

**EN**: Q: How does function calling work under the hood, and where does constrained decoding fit?
A: Tool schemas enter the prompt; the model emits tool-call structures learned either in training (SFT/RL on tool traces) or in-context (generalizing to unseen tools); the decoding stack enforces schema validity via constrained decoding — illegal JSON tokens get suppressed, which is why native FC beats "please output JSON" parsing. Schema field descriptions are inference-time signals (connecting to S1-10), and streaming fragmentation of arguments is a direct consequence of token-level constrained generation.
Why asked: principle-layer FC knowledge.
Keys: the three-stage mechanism; two learning paths; schema-as-signal; streaming root cause.
Follow-ups: bad args — schema or capability?; does constraining hurt quality.
Pitfall: treating FC as magic JSON markers.

---

### S3-14. 并行工具调用：模型一次要三个工具怎么办？调用失败怎么回填？设计上要注意什么？

**参考答案**：处理：一次返回多个 toolCalls（OpenAI 的 tool_calls 数组 / Anthropic 的多个 tool_use 块），执行侧逐个跑——**本项目的选择是顺序执行**（本地工具毫秒级，并行收益为零，还共享 MAX_TOOL_CALLS 预算防叠加爆量）；远程工具（如多个独立 API）可并行，但要注意：① 结果回填必须**每个 toolCallId 一条对应结果**（OpenAI 缺一条 400——S1-13）；② 失败回填失败文本（Anthropic 的 is_error / OpenAI 的失败描述），语义是"这个调用失败了 + 原因"，**不是**跳过——模型需要看到失败才能调整；③ 部分成功部分失败时全部如实回填，不要只回填成功的（模型会基于错误的完整视图推理）；④ 顺序依赖的工具调用（B 的参数依赖 A 的结果）不该出现在同一批——提示词引导"每次只做一件明确的事"（本项目 SYSTEM_PROMPT 第 2 条）或串行化执行。预算共享：并行调用合计计入 MAX_TOOL_CALLS，超预算的调用回填"已达上限"而不是静默丢弃。

**解析**：
- **考察意图**：FC 协议的工程细节——回填完整性（每 id 必有结果）是实战最容易翻车的点。
- **答题要点**：a) 顺序 vs 并行执行的决策依据（工具延迟）；b) 回填三原则（每 id 必答、失败也答、全量如实）；c) 依赖调用的处理（提示词引导/串行化）；d) 预算共享。
- **可能追问**："并行执行怎么取消？"（结构化并发——coroutineScope + 子协程，一个取消全组取消，防止孤儿工具继续跑）；"模型一轮要 5 个工具但预算只剩 2 怎么办？"（前 2 个执行，其余回填上限提示——本项目的真实行为）。
- **常见错误**：静默丢弃超预算调用（模型看到结果缺失会幻觉）；不知道 OpenAI 配对缺失会 400。

**EN**: Q: How do you handle parallel tool calls and back-fill failures?
A: Execute sequentially when tools are local (this project — negligible latency, shared budget caps compounding), in parallel when remote. Back-fill rules: one result per toolCallId (OpenAI 400s on a missing pair), failures back-filled with reasons (Anthropic's `is_error`), never partial honesty — the model must see the failure to adapt, and never drop over-budget calls silently. Dependency-coupled calls should be discouraged via prompt ("one clear thing per call") or serialized. Cancellation of parallel batches: structured concurrency, cancel the group.
Why asked: back-fill completeness is where real systems break.
Keys: execution-order decision; three back-fill rules; budget sharing; structured concurrency.
Follow-ups: cancellation semantics; budget exhaustion behavior.
Pitfall: silently dropping un-answered calls.

---

### S3-15. MCP 是什么？解决什么问题？与直接 function calling 的关系？A2A 呢？

**参考答案**：**MCP（Model Context Protocol）**：Anthropic 2024 年底开放的协议，标准化"应用 ↔ 工具/数据源"的连接——把工具发现（list tools）、调用（call tool）、资源读取（resources）、提示模板做成统一的 JSON-RPC 接口。解决的是 **M×N 集成爆炸**：M 个 AI 应用 × N 个工具源，每人各写一套胶水 → 有了 MCP，工具方实现一次 Server，应用方实现一次 Client，即插即用（类比 USB-C）。与直接 FC 的关系：**FC 是模型与应用之间的能力表达，MCP 是应用与工具源之间的连接协议**——MCP Server 把工具暴露给宿主应用，宿主再把它们翻译成自家模型的 FC schema；两层互补不替代。本项目"若接 MCP"：四个工具包成 MCP Server 后，任何 MCP 宿主（Claude Desktop 等）都能用同一套健康数据工具，但 ReAct 循环本身不变——工具的**声明方式**标准化了，**调度策略**仍是应用自己的。**A2A（Agent2Agent）**：Google 开放的 Agent 间协作协议（发现对方能力、委派任务、状态回传），解决"多 Agent 跨框架协作"，与 MCP（Agent↔工具）互补成完整拼图。

**解析**：
- **考察意图**：协议生态常识——2025+ 的 Agent 岗位面试开始必问 MCP；考分层理解（FC/MCP/A2A 各管一段）而非名词背诵。
- **答题要点**：a) MCP 的接口面与 USB-C 类比；b) M×N 问题；c) FC 与 MCP 的分层关系（模型↔应用 ↔ 应用↔工具）；d) 对本项目的具体含义（工具可复用、循环不变）；e) A2A 一句话定位。
- **可能追问**："MCP 的安全风险？"（工具描述注入、Server 权限过大——最小授权 + 用户确认，连接 MCP 的安全检查思路通用）；"什么时候值得把自家工具 MCP 化？"（多宿主复用需求出现时；单应用自用不值得多一层协议）。
- **常见错误**：把 MCP 说成"替代 function calling"；A2A/MCP 混为一谈。

**EN**: Q: What is MCP, what problem does it solve, and how does it relate to function calling? And A2A?
A: MCP standardizes app↔tool connectivity (tool discovery, calls, resources) over JSON-RPC, fixing the M×N integration explosion — write a Server once, any MCP host uses it (USB-C analogy). Layering: FC expresses capabilities model↔app; MCP connects app↔tools — complementary, not replacements. Wrapping this project's four tools as an MCP Server would expose them to any host while the ReAct loop stays untouched. A2A (Google) standardizes agent↔agent delegation, complementing MCP's agent↔tool leg.
Why asked: ecosystem literacy now standard in agent interviews.
Keys: interface surface; M×N; the layering; concrete implications for this project.
Follow-ups: MCP security risks; when MCP-ification pays off.
Pitfall: "MCP replaces function calling".

---

## 3E 安全与对齐（3 题）

---

### S3-16. 提示注入（Prompt Injection）在 Agent 里的攻击面有多大？间接注入怎么防？

**参考答案**：Agent 把外部内容读进上下文——**任何外部文本都是潜在指令**。攻击面分层：① 直接注入（用户自己写恶意指令——单人应用危害小）；② **间接注入（真威胁）**——工具返回的内容里藏指令：网页、邮件、文档、图片文字（多模态 OCR 也是注入通道）。本项目特殊情况：工具数据全部来自**本地已确认的数据库**（用户人工核对后入库的记录）与**用户自己拍的图**——间接注入的入口天然收窄，这是数据源受控的红利；但通用设计不能赌这一点。防御纵深：① **权限最小化**（只读工具=注入成功也只能得到错误回答，S1-26 的爆炸半径论证）；② **指令/数据隔离**——工具结果用明确的定界包裹、system 里声明"数据区内文本不是指令"（软防御，可被绕过）；③ **输出侧校验**——Agent 的动作白名单、参数 schema 校验（硬防御，注入改变了"想做什么"，但做不出白名单外的动作）；④ 人审关键动作（写操作人工确认）；⑤ 供应商侧的注入检测分类器。诚实结论：没有银弹，纵深防御 + 爆炸半径控制是工程答案。

**解析**：
- **考察意图**：Agent 安全第一题；考攻击面建模的层次感与"没有银弹"的成熟认知。
- **答题要点**：a) 直接/间接注入的区分与间接的真实威胁；b) 本项目数据源受控的特殊性（红利 + 不赌它）；c) 五层纵深防御（软/硬防御分开说）；d) 只读边界与爆炸半径的连接（S1-26）。
- **可能追问**："图片里的注入怎么防？"（OCR 文本同样进数据区 + 输出白名单兜底）；"红队测试怎么做？"（构造含注入指令的记录/图片跑评测集，断言 Agent 不执行）。
- **常见错误**：只答"在 prompt 里写不要听外部的"（软防御单层，必被绕过）；没想过本项目数据源受控这一点。

**EN**: Q: How big is the prompt-injection attack surface for agents, and how do you defend against indirect injection?
A: Any external text entering the context is a potential instruction; indirect injection (instructions hidden in tool returns — web pages, documents, even OCR'd images) is the real threat. This project narrows it: tool data comes from user-confirmed local records and user-captured photos — a controlled-source bonus, but never bet on it. Defense in depth: least privilege (read-only tools cap the blast radius at a wrong answer), instruction/data separation (soft), output-side whitelisting and schema validation (hard), human review for writes, provider-side classifiers. No silver bullet — depth plus blast-radius control.
Why asked: the agent-security opener.
Keys: direct vs indirect; controlled-source bonus; soft/hard layering.
Follow-ups: image-borne injection; red-teaming.
Pitfall: single-layer prompt-level "defense".

---

### S3-17. 医疗健康类 Agent 的安全设计有哪些必选项？本项目的三处设计怎么对应？

**参考答案**：必选项四类：① **输出标识**——AI 生成内容必须可辨识（固定免责尾注："仅供参考，不构成医疗建议"——本项目写进 SYSTEM_PROMPT 硬编码，不靠模型自觉）；② **结论来源约束**——高风险判断（异常/诊断）锚定到确定性规则或权威来源，模型只做语言组织（"异常结论必须来自 get_alerts"，S1-9）；③ **人工确认**——数据入库前 editable 卡片 + 离谱值双确认（解析管线的确认环节），模型永不直接写数据；④ **升级路径**——涉及诊断/用药时强制提示咨询医生（提示词规则 + 兜底文案）。本项目对应还有隐含项：数据最小化（日志无内容、BYOK 直连）、降级诚实标注（本地引擎回答必须标明非 AI——防用户把规则输出当医学结论）、引用可溯源（[n] + 数据依据区，用户可核对每个数字）。通用原则：**医疗场景的目标不是"AI 更准"，而是"错误可被发现"**——溯源、确认、标识三件套都是让错误暴露给用户的机制。

**解析**：
- **考察意图**：垂直场景（尤其医疗）的安全清单——面医疗 AI 岗位几乎必考；考设计机制与原则的对应。
- **答题要点**：a) 四类必选项；b) 每类对应到项目具体位置（能指认文件级细节）；c) "错误可发现 > AI 更准"的目标重定义；d) 降级标注也是安全设计（容易漏）。
- **可能追问**："免责声明能免责吗？"（法律上有限，但它是监管要求的输出标识与用户知情机制——诚实说不是护身符）；"如果要做 AI 建议直接改用药提醒？"（必须处方级人工确认 + 审计轨迹，当前架构下答案是不做）。
- **常见错误**：只答"加免责声明"（四类里最弱的一类）；不知道人工确认环节在解析管线里的位置。

**EN**: Q: What are the mandatory safety designs for a medical agent, and how does this project implement them?
A: Four classes: output labeling (fixed disclaimer hard-coded in the system prompt), conclusion anchoring (abnormality only from `get_alerts` — the model organizes language, rules decide), human confirmation (editable cards + double-confirm for implausible values before anything hits the DB; the model never writes), escalation prompts (consult-a-doctor rules). Implicit extras: data minimization, honest fallback labeling (rule-engine output is never presented as AI), [n] citations for every number. The goal reframing: not "make the AI more accurate" but "make errors discoverable" — traceability, confirmation, and labeling all expose errors to the user.
Why asked: vertical-domain safety checklist.
Keys: four classes mapped to code; the goal reframing; fallback labeling as safety.
Follow-ups: does a disclaimer legally protect; direct medication changes (no).
Pitfall: answering "add a disclaimer" only.

---

### S3-18. "数据最小化"在 Agent 系统里怎么落地？本项目的 BYOK 架构在隐私上交换了什么？

**参考答案**：数据最小化的四个落点：① **采集面**——只处理功能必需的数据（本项目只解析用户主动上传的报告，不扫描相册/后台采集）；② **传输面**——端到端最小化：问答只发送当前成员的检索结果（BM25 Top-12）而非全库，Agent 只发范围概览；③ **留存面**——日志无内容（llm_call_logs 只有元数据）、QAHistory 可清空、附图有孤儿清理（1 小时静置期）；④ **暴露面**——Key AES-256-GCM 加密、本地数据库、无开发者服务器。**BYOK 的交换**：获得——数据只流经用户自己选的供应商、无中间服务器（开发者侧零接触）、无账号体系；付出——UX 成本（用户要自己申请 Key）、无服务端能力（没有服务端过滤/限流/统一审计，滥用防护缺位）、计费与额度由用户自担。这个交换在健康场景是划算的：**开发者接触不到数据**在隐私承诺上是最强形态（"我们不会看"不如"我们不能看"）。面试话术要点：承认 BYOK 限制了产品化（普通用户门槛），但这是隐私定位下的主动选择。

**解析**：
- **考察意图**：隐私工程 + 商业权衡——考能否把架构选择讲成有代价的交换而非"我们更好"。
- **答题要点**：a) 四个落点各配项目实例；b) BYOK 的得失表；c) "不能看 > 不会看"的架构级隐私论证；d) 诚实承认产品化代价。
- **可能追问**："要上服务端会怎么改？"（传输面加 TLS+最小字段、留存面加保留策略、暴露面加审计——每条都要重新评估， Privacy 不是开关是持续预算）；"本地数据库加密吗？"（可加 SQLCipher；当前依赖系统沙箱 + 应用级加密 Key，诚实说 SQLCipher 是加固项）。
- **常见错误**：把最小化说成"不收集数据"口号（要讲四个面的机制）；不知道 BYOK 的产品化代价。

**EN**: Q: How does data minimization land in an agent system, and what does BYOK trade away?
A: Four surfaces: collection (only user-initiated parsing), transmission (BM25 Top-12 or a scope overview, never the whole DB), retention (content-free logs, clearable history, orphan-image cleanup with a grace period), exposure (Keystore-encrypted keys, local DB, no developer server). BYOK gains "we cannot look" (stronger than "we won't look"), zero server touch; it costs UX (users bring their own keys), server-side protections, and product reach — a deliberate trade for a privacy-first health app.
Why asked: privacy engineering with honest trade-offs.
Keys: four surfaces with instances; the BYOK ledger; "cannot look" argument.
Follow-ups: adding a server later; local DB encryption (SQLCipher as hardening).
Pitfall: minimization as a slogan, not mechanisms.

---

## 3F 评估与可观测（3 题）

---

### S3-19. Agent 评估：只看最终答案为什么不够？轨迹评估评什么？离线评测集怎么建？

**参考答案**：最终答案对 ≠ 过程对：可能是运气（工具查错了但答案碰巧对）、可能是幻觉对上（编的数值恰好接近真值）；最终答案错 ≠ 过程错（数据本身缺失时正确行为就是承认不知道）。**轨迹评估维度**：① 工具选择正确性（该查告警时查了吗——可从 system prompt 规则派生断言）；② 调用效率（轮数/调用次数——5 轮解决别人 2 轮的问题就是浪费）；③ 参数质量（metric_types 传对没有）；④ 失败恢复（失败后是否换了策略而不是撞墙——对应禁用机制）；⑤ 终止合理性（该停时停了吗）。实现：轨迹日志（本项目的 AgentEvent 流 + llm_call_logs 就是原料，缺 runId 串联是改进项）+ 评测集断言。**离线评测集**：30~100 个覆盖典型场景的问题（指标/泛化/带图/空数据/多步），每题标注期望行为（不是标准答案文本而是行为断言：应调用哪些工具、答案应含哪些数值、免责应在）；回归跑法：换提示词/模型/工具描述后全量重跑对比行为差异。

**解析**：
- **考察意图**：评估成熟度——从"看结果对不对"升级到"过程可审计"；评测集的工程化（行为断言 vs 文本匹配）。
- **答题要点**：a) 答案对≠过程对的两个反例；b) 五个轨迹维度（可结合项目机制）；c) 行为断言式的评测集设计（标注期望行为而非答案文本）；d) 回归驱动的用法。
- **可能追问**："轨迹日志怎么收集？"（AgentEvent 已是结构化流——序列化落盘即可；加 runId 串联多次调用是第一步）；"行为断言怎么写？"（每个用例一个断言函数：assertToolsCalled(setOf("get_alerts")) + assertAnswerContains("6.2") 级别）。
- **常见错误**：评测集标注成标准答案文本（答案有无数种对法，文本匹配必误伤）；没想过幻觉碰巧对的情况。

**EN**: Q: Why is final-answer-only evaluation insufficient, what does trajectory evaluation measure, and how do you build an offline eval set?
A: Correct answer ≠ correct process (lucky tool calls, hallucinated numbers that happen to be close; wrong answer ≠ wrong process when data is genuinely missing). Trajectory dimensions: tool selection, call efficiency (turns/calls), argument quality, failure recovery (pivot vs wall-banging), termination sanity. Raw material already exists (AgentEvent stream + llm_call_logs; runId correlation is the gap). Eval set: 30–100 scenario-covering questions annotated with *behavior assertions* (which tools should be called, which numbers must appear, disclaimer present) — not golden text. Re-run on every prompt/model/description change.
Why asked: evaluation maturity beyond answer matching.
Keys: two counterexamples; five dimensions; behavior-assertion design; regression usage.
Follow-ups: trajectory logging; assertion style.
Pitfall: golden-text eval sets.

---

### S3-20. LLM-as-judge 可靠吗？有哪些系统性偏差？什么时候必须用规则断言替代？

**参考答案**：可用但有系统性偏差，必须校准后才能信任：① **位置偏差**——对比评估时偏向前者/某个固定位置（随机化顺序可消）；② **长度偏差**——偏爱长而全的回答（健康问答里啰嗦不等于好，可加长度归一或分项打分）；③ **自我偏好**——同家族模型偏好自己的输出风格；④ **权威口吻偏差**——自信的错误比犹豫的正确得分高（恰恰是医疗场景最危险的偏差）；⑤ **锚定**——被上下文里的声称影响（答案说"根据记录"它就信）。**必须用规则断言的场景**：数值一致性（6.2 就该逐字出现——judge 会放过"约 6"）、结构存在性（免责声明、引用编号格式）、行为断言（调用了哪些工具）、任何要进 CI 阻断合并的检查（judge 的方差会打爆 CI）。工程组合：**规则断言做门槛（确定性）→ judge 做排序与探索（发现 badcase）→ 人工复核固化新规则**——judge 的产出最终沉淀为规则，评估体系才收敛。

**解析**：
- **考察意图**：评估的元认知——知道 judge 的失真模式并设计补偿，是从"用过"到"会建"的分界。
- **答题要点**：a) 五个偏差（位置/长度/自我/口吻/锚定）；b) 四类必须规则化的场景（数值、结构、行为、CI 门槛）；c) 三层组合与"judge 沉淀为规则"的收敛机制；d) 随机化、分项打分等校准手段。
- **可能追问**："judge 用什么模型？"（比被评模型强一档；评测 judge 本身——用人工标注样本算 judge 与人的一致率）；"多 judge 投票？"（降方差但相关性仍在（同家族），异构 judge 才有独立信息）。
- **常见错误**：把 judge 分数当客观真理；不知道 CI 场景必须规则化。

**EN**: Q: Is LLM-as-judge reliable? What are its systematic biases, and when must rule assertions replace it?
A: Usable with calibration. Biases: position (randomize), length (normalizers/split criteria), self-preference, confident-tone bias (dangerous in health — confident wrong beats hesitant right), anchoring on claimed sources. Rule assertions are mandatory for: numeric exactness ("6.2", judges accept "about 6"), structural presence (citations, disclaimers), behavior assertions (tool calls), and anything gating CI (judge variance breaks pipelines). Stack: rules as the gate → judge for ranking and badcase discovery → human review promoting findings into new rules. The eval system converges as judge findings crystallize into rules.
Why asked: meta-awareness of evaluation distortion.
Keys: five biases; four must-rule cases; the convergence mechanism.
Follow-ups: choosing the judge; ensembles.
Pitfall: treating judge scores as ground truth.

---

### S3-21. 生产环境的 Agent 可观测要采哪些信号？与普通 LLM 应用的观测差在哪？

**参考答案**：Agent 比单次调用多出"过程"维度，观测分四层：① **调用层**——每次 LLM 请求：供应商/模型/延迟/输入输出体量/重试/错误类型（本项目 llm_call_logs 的字段就是清单——promptChars/completionChars/attempts/errorType）；② **轮次层（Agent 特有）**——每轮的的工具调用序列：调了什么、参数、成功否、耗时、observation 长度（截断频次是预算健康度信号）；③ **轨迹层**——一次提问的完整 trace：轮数、工具序列、终止原因（正常/上限/失败/禁用）、最终证据数量——轮数分布漂移（P50 从 2 涨到 3）往往是模型行为或数据变化的先行指标；④ **业务层**——降级触发率（Agent→本地引擎的比例升高=供应商质量恶化）、引用校验失败率、用户负反馈关联 trace。与普通 LLM 应用（一问一答）的差别：**多了一层状态机**——同一次用户动作产生多次调用，必须 runId 串联才看得见全程；失败定位从"哪次调用失败"变成"哪个环节失败"（工具 vs 模型 vs 路由）。落地顺序建议：先 ①（本项目已做）→ runId 串 ②③ → ④ 的业务指标。

**解析**：
- **考察意图**：可观测的分层设计 + Agent 特有的"过程观测"；考能否从本项目现状推演生产化路线。
- **答题要点**：a) 四层信号清单（每层能报 2~3 个具体字段）；b) runId 串联与状态机观测的核心差异；c) 轮数分布/降级率两个先行指标的用法；d) 落地顺序。
- **可能追问**："采样率怎么定？"（调用层全采（轻量），轨迹层全采（结构化不大），内容层不采（隐私）——延续 S1-27 的口径）；"告警阈值怎么设？"（错误率/延迟分位数/降级率的移动窗口对比，不设绝对值设相对漂移）。
- **常见错误**：只有调用层观测就宣称"可观测齐全"；说不出 Agent 与单次调用的观测差异。

**EN**: Q: What signals does production agent observability need, and how does it differ from plain LLM apps?
A: Four layers: call (provider/model/latency/chars/attempts/errorType — exactly llm_call_logs), turn (per-turn tool sequence, args, outcomes, observation length — truncation frequency signals budget health), trajectory (turns, tool sequence, termination reason, evidence count — P50 turn drift is an early indicator), business (fallback-trigger rate, citation-check failures, feedback correlated to traces). The agent difference: a state machine — one user action fans out into multiple calls, so runId correlation is mandatory and failure localization becomes "which stage" (tool vs model vs routing). Rollout order: call layer (done) → runId+turn+trajectory → business metrics.
Why asked: layered observability plus the agent-specific twist.
Keys: four layers with concrete fields; runId/state-machine difference; drift indicators.
Follow-ups: sampling rates; alert thresholds.
Pitfall: claiming full observability with call logs only.

---

---

# 附 · 考前 10 分钟速记 | Last-Minute Recall Sheet

**数字组 Numbers**（背：5 / 8 / 2 / 1500 / 6000 / 10+60s / 0.3 / 2048 / 3 vs 2 次 / 500ms×2ⁿ+250ms / Top-12 / k1=1.5 b=0.75 / 49 指标 · 106 别名 / Room v9 · 7 实体 / CONSENT v1 / 1600px）

**铁律组 Iron rules**（说得出出处）：
1. 工具只读（get_alerts 不复用写用例）→ 爆炸半径 = 错误回答
2. 最后一轮撤工具 → 轮数用尽与正常收尾合流
3. 身份代码绑定（ToolContext）→ 类型系统消灭跨成员错误
4. 流式首个增量后不重试 → 防回答重复
5. 取消不是失败（三层 rethrow + call.cancel）
6. 降级策略只有一处；降级必须诚实标注来源
7. evidence 逐字复用 → 依据与模型所见逐字一致
8. 日志记元数据不记内容 → "我们不能看"
9. observation 截断必须显式标注省略
10. 免责尾注硬编码，不靠模型自觉

**叙事主线 Storyline**（开场 60 秒）：
本地优先健康管家 → 拍照 → 视觉 LLM JSON（49 指标）→ 归一化（106 别名/24 单位）→ 人工确认 → 三规则检测 → 问答：strongTerms 路由（快路径 BM25+引用 / 泛化走手写 ReAct + 4 只读工具）→ 三层降级 → llm_call_logs 可观测（无内容）。设计哲学：**确定性代码管规则与安全，LLM 管语言与组织；Agent 是放大器不是替换品；正确性优先于首字延迟，且如实记录每个取舍。**

**反问面试官的问题库**（面试结尾用）：
- "团队的 Agent 目前在 terminating 条件和失败降级上是怎么设计的？"
- "评估体系里规则断言和 LLM-as-judge 的比例大概什么样？"
- "工具权限模型是白名单还是 capability 制？"

---

*本文档基于 master 分支代码实测撰写（2026-09-27）。所有类名、常量、行为均可在对应源文件中验证：`ReActAgent.kt` / `AgentConfig.kt` / `AgentProtocol.kt` / `AgentRequestBody.kt` / `LlmStreamParser.kt` / `LlmClient.kt` / `QaRetriever.kt` / `QARepositoryImpl.kt` / `domain/tool/*.kt` / `ReActLoopTest.kt`。*

