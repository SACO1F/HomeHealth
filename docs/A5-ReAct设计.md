# A5 · ReAct Agent 设计（含 QAScreen 发图前置改动）

> 状态：**已实现（2026-09-16）** —— 构建 `BUILD SUCCESSFUL`，JVM 单测 **101 条全绿**
> 日期：2026-09-16 ｜ 代码基线：`main @ 42dba6d` + 第 8/9 轮未提交改动
> 对应考纲：阶段 2「手写 ReAct / Plan-and-Solve / Reflection」+ 阶段 3「Function Calling / SSE 选型」
> 相关文件：`HomeHealth-待办与改进清单-v2.md` 的 A5 / B1 条目、`docs/项目深度分析报告-v2-考纲映射.md`

### 实现与本文档的差异（如实记录）

1. **Agent 路径的最终回答整段提交，不逐字上屏**。本文档 5.2 只说"只有最终回答轮流式"，
   但实现时发现：**只有一轮结束后才知道它是不是最终回答**。为了不让中间轮的推理先流到答案区
   再撤回（对用户是明显抖动），改为该轮正文先缓冲、确认是最终轮后一次性提交。
   快路径仍然逐字上屏 —— 这是 Agent 路径为正确性付出的代价。
2. **工具依赖收窄为接口**：`ToolProvider`（工具集）与 `VisionReader`（读图）两个接口，
   否则 `ReadReportImageTool` 在 JVM 单测里根本构造不出来（要凑齐 OkHttpClient / SettingsPrefs / …）。
   这是为了兑现 7 节的循环级测试。
3. **4.3 提到的 `DetectAnomaliesUseCase` 解耦仍未做**（`evaluate` 与 `invoke` 拆分），
   仍排在 A5 之后 —— 当前走只读 `get_alerts`，不依赖该重构。
4. 本设计未覆盖的实测缺口：**Agent 路径暂无真机验证**（只跑了 JVM 单测与编译）。
5. 顺带修掉第 8 轮流式实现里的一个真 bug：`received` / `completionChars` 从未被更新，
   导致"首个增量后不再重试"的策略与 `llm_call_logs` 的补全字符数统计双双失效。

---

## 0. 一页结论

1. **不替换现有问答链路，而是加一条路由**。快路径（BM25 → 1 次 LLM）保留不动，只把「泛化 / 多步 / 需交叉验证」的问题交给 Agent。理由是实测推演：指向具体指标的问题用 Agent 是**负优化**（1 次请求变 2 次，质量不变）。
2. **工具由问题分布推导，不是把现有代码模块 1:1 包一层**。`read_report_image` 之所以成立，是因为它能解决一个具体问题（见 4.4），不是为了凑齐"四个工具"。
3. **`detect_anomalies` 工具必须只读**。现有 `DetectAnomaliesUseCase.invoke()` 会写库（生成告警 + 去重），直接当工具用会导致"用户问一句话，凭空多出告警和通知"。这是本次设计最重要的发现（见 4.3）。
4. **一条 SSE 连接贯穿整个循环**。工具调用与结果作为事件先下发，只有最终回答轮逐字上屏 —— 循环中途方向错了，不会有半句话已经流出去。
5. **QAScreen 发图是前置改动**，它带一个 Room **v7 → v8 迁移**和一个新的孤儿文件风险点，需要单独验收（见第 3 节）。

---

## 1. 目标与不做什么

**做**
- 4 个工具 + ReAct 循环（硬上限 5 轮），带完整护栏与降级
- 路由：命中强指标词走快路径，否则走 Agent
- QAScreen 支持随提问附图（相机 / 相册）
- 流式事件扩展：工具调用与结果进流，界面可折叠展示

**不做**（明确列出，避免范围蔓延）
- **不引入 LangGraph / AutoGen**。单进程单循环，框架带来的是负复杂度，且会掩盖"手写循环"这一考纲要求本身。
- **不做 Multi-Agent**。本项目没有角色分工需求，拆分只会让失败面叠加。
- **不让 LLM 参与异常判定**。参考范围取值、性别分层、180 天趋势窗、区间型比较符全部留在确定性代码里（延续既有原则）。
- **不做 Reflection 自检的完整版**。A5 只做"重试一次"级别的轻量自检（见 5.5），完整 Reflection 留给后续。
- **不做端侧推理**。

---

## 2. 运行形态：路由器 + 两条路径

```
用户提问
   │
   ▼
路由器（复用并升级 QaRetriever.strongTermsOf）
   │
   ├── 命中强指标词 ──► 快路径：BM25 检索 → 1 次 LLM → 流式回答   （现有实现，不动）
   │
   └── 未命中 ───────► Agent 路径：ReAct 循环 ≤5 轮 → 最终回答轮流式
```

**路由器怎么判断**：现有 `QaRetriever.strongTermsOf(question)` 返回空集 = 没有可靠指标词。这正是"泛化问题"的定义，也是当初为修「弱字窄化」bug 引入的闸门。第一版直接沿用它：

| 问题 | strongTerms | 路径 |
|---|---|---|
| 最近血糖怎么样 | {血糖} | 快路径 |
| LDL 多少 | {ldl} | 快路径 |
| 我最近身体有什么问题吗 | {} | Agent |
| 帮我看看整体情况 | {} | Agent |
| 血压算高吗 | {血压} | 快路径 |

**后续可升级**（不在 A5 范围）：路由器改成一次极廉价的 LLM 分类（或关键词表 + 少量规则），支持"需要多步"的定向问题。第一版不做，因为多一次请求就抵掉了它省下的收益。

**为什么这条路由是设计亮点而不是妥协**：问"我为什么没有全局上 Agent"比问"我上了 Agent"更能体现判断力。快路径留着也有实际价值——它是 Agent 路径的**对照组**，两个路径可以 A/B 比较回答质量。

---

## 3. 前置改动 A：QAScreen 支持发图

### 3.1 为什么需要

不是所有检查报告都能变成结构化指标。血常规、生化能映射到 49 项字典，但影像报告、病理报告、医生手写结论、出院小结这类**叙述性内容**根本没有对应的结构化字段。这类内容只存在于图片里，因此"随提问附图"是刚性需求，`read_report_image` 工具也因此才成立。

### 3.2 关键问题：问答用文本模型，图片必须走视觉模型

现有配置是**两套独立**的：

| 用途 | 配置来源 | 模型约束 |
|---|---|---|
| 报告解析 | `parseProvider` / `parseApiKey` / `parseModel` | 必须是视觉模型，`resolveConfig(vision=true)` 会拦截文本模型 |
| 健康问答 | `qaProvider` / `qaApiKey` / `qaModel` | 文本模型（`resolveConfig(vision=false)`） |

发图提问直接撞上这个分离：**用户的问答模型很可能不支持图片**。各供应商的 chat / vision 清单交集不同——

- OpenAI / Gemini / Kimi：chat 与 vision 列表一致 → 直接可用
- 智谱：交集只有 `glm-5.3-flash`；若问答选的是 `glm-4.6`（纯文本）→ 不可用
- DeepSeek：交集只有 `deepseek-flash`
- Qwen：交集为 `qwen3.8-max` / `qwen3.8-flash` 等

### 3.3 路由决策（推荐方案 C）

```
resolveQaVisionConfig()：
  1) qaProvider 已配置，且解析出的问答模型 ∈ preset.visionModels
       → 用问答配置（零新增配置，大多数供应商走到这里）
  2) 否则若 parseConfigured()
       → 用「报告解析服务」的视觉模型，并在 sources 标注
         「图片由报告解析服务的视觉模型读取」
  3) 否则 → 抛可操作错误：
         「当前问答模型不支持图片。请把问答模型换成视觉模型，或先配置报告解析服务」
```

不用新增第三套配置（方案 B），是因为它让设置页多一行 × 8 家供应商，而语义收益很小；不用"静默失败"是因为健康类应用里"看起来答了但没看图"比报错危险得多。

实现上**不能复用 `resolveConfig(vision=true)`** —— 它读的是解析服务的设置。需要新增 `resolveQaVisionConfig()`，校验规则也不同（允许"只在 visionModels 里"的模型，而 `resolveConfig(vision=true)` 对纯文本模型是拒绝）。

### 3.4 数据与持久化：需要 Room v7 → v8

`qa_history` 目前没有图片字段，历史里会变成"一条提问但看不到图"。三个选项：

| 方案 | 代价 | 评价 |
|---|---|---|
| (a) `qa_history` 加 `imagePath: String?` | Room v7→v8 迁移 | ✅ **推荐** |
| (b) 不持久化，仅当轮使用 | 零迁移，历史标注"本条附图（已不保留）" | 可接受但体验缺失 |
| (c) 复用 `medical_documents` 表 | 仍要加列，且把"聊天附图"混进"体检报告"语义 | ❌ 污染现有语义 |

选 (a)。迁移内容：`ALTER TABLE qa_history ADD COLUMN imagePath TEXT`。

**必须同步处理的三件事**（本项目踩过同类坑，清单第 1 轮就有「删成员清图片 + 日历 + 头像」）：
1. **图片落盘位置**：`filesDir/qa_images/qa_<ts>.jpg`，与 `filesDir/documents/` 分开，避免与报告图片的清理逻辑互相干扰。
2. **删成员时清理**：`FamilyRepositoryImpl` 的删除路径要一并删除该成员的问答附图。
3. **删除单条问答历史时清理**（若有该入口）：同理。

**且必须遵守既有的迁移纪律**（清单「操作备忘」第 1 条）：升版本后**构建两次**，核对 androidTest APK 内的 schema 列表应出现 `8.json`；**升版本当天不要装中途构建的半成品包**（会把 DB 版本烧到新号但列不全，之后任何新包都无法迁移）。`SchemaMigrationStructureTest` 要补 v7→v8 一跳。

### 3.5 隐私影响（必须写进 README）

现有承诺是"LLM 只在你主动触发解析/提问时收到当次请求所需的摘要"。发图让"当次请求"从**纯文本摘要**变成**图片本身**——这是承诺范围内，但表述需要修正：不能再说"只发送结构化摘要"。README 的 Highlights 6（Truly local-first）要加上"发图提问时，该图片会随该次请求发送给所配置的供应商；不配置 Key 时图片不出设备"。

### 3.6 界面

- 输入栏左侧加「附图」按钮（`rememberLauncherForActivityResult(PickVisualMedia)` + `TakePicture()`，与 `DocumentUploadScreen` 同一套，直接复用 `prepareCameraCapture` 的 FileProvider 写法）
- 已选图片在输入框上方显示缩略图 + 删除按钮（`File(path)` 预览，同 `DocumentUploadScreen`）
- 气泡内渲染缩略图（`qa_history.imagePath` 非空时），点击可全屏查看
- 新增字符串（中英各一）：`qa_attach_image` / `qa_image_hint` / `qa_image_removed` / `qa_vision_required`
- 发图提问期间卡片里要显示"正在读取图片…"（视觉调用比文本慢）

### 3.7 验收

1. 问答模型为视觉模型（如 OpenAI）→ 拍一张叙述性报告 → 提问 → 回答引用了图片内容，历史条目带缩略图
2. 问答模型为纯文本、解析服务已配 → 自动改用解析服务视觉模型，`sources` 明确标注出处
3. 两者都不可用 → 明确的引导文案，而不是"回答为空"
4. 删除成员 → `filesDir/qa_images/` 下该成员的图片被清理（无孤儿文件）
5. 不配任何 Key → 发图提问给出"需要配置视觉模型"的提示，且**图片不出设备**

---

## 4. 工具集设计

### 4.1 工具契约

```kotlin
// domain/tool/HealthTool.kt
interface HealthTool {
    val name: String
    val description: String           // 给模型看的用途说明，决定它会不会被正确调用
    val parametersJsonSchema: String  // JSON Schema，两种协议共用
    /** 入参为模型给出的 JSON；实现方必须自行容错，异常请在内部转成错误文本 */
    suspend fun invoke(argsJson: String): ToolResult
}

/** observation 文本 + 是否成功（失败也返回文本，让模型有机会换个调用方式） */
data class ToolResult(val ok: Boolean, val text: String)
```

工具注册表 `HealthToolRegistry`（Hilt 注入 `Set<HealthTool>`），负责：按名查找、schema 导出、总调用次数统计、连续失败禁用。

### 4.2 逐个工具

#### ① `search_records`

```json
{
  "name": "search_records",
  "description": "检索该成员已保存的健康记录，返回带 [n] 编号的记录明细（含单位与参考范围）。回答任何涉及具体数值的问题前都应先调用。",
  "parameters": {
    "type": "object",
    "properties": {
      "query":        { "type": "string",  "description": "自然语言关键词，如“血糖”“LDL”“最近的血脂”" },
      "metric_types": { "type": "array", "items": { "type": "string" },
                        "description": "限定指标类型（英文键，如 blood_glucose、ldl）；不确定时不要传" },
      "limit":        { "type": "integer", "description": "最多返回条数，默认 10，上限 20" }
    },
    "required": ["query"]
  }
}
```
**实现映射**：`query` → 复用 `QaRetriever.index(...).search(question)`（BM25 + 强词闸门）；`metric_types` → `HealthRecordDao.getRecentRecords(memberId, type, limit)`。
**取舍**：`metric_types` 是给模型"精确下钻"用的，但模型很容易把中文当类型传（`type="血脂"`）。工具内部必须**先过 `SchemaNormalizer.normalizeType()`**，失败则忽略该字段而不是报错——报错会让模型反复尝试同一个错参数。

#### ② `get_reference_range`

```json
{
  "name": "get_reference_range",
  "description": "查询某指标的正常参考范围、单位与“偏高还是偏低更糟”。在回答“算不算高”“正常范围是多少”之前应先调用。",
  "parameters": { "type": "object", "properties": {
    "metric_type": { "type": "string", "description": "指标类型（英文键）或中文名" }
  }, "required": ["metric_type"] }
}
```
**实现映射**：`HealthTypes.def(type)?.rangeFor(member.gender)` + `unit` + `higherIsWorse`。纯本地查表，零 IO、零成本。
**它为什么值得单独存在**：检索结果虽然本来就带参考范围，但这个工具让「正常范围是多少」这类**没有任何记录也能问**的问题有答案——这是快路径覆盖不到的一类提问。

#### ③ `get_alerts` —— 只读，见 4.3

```json
{
  "name": "get_alerts",
  "description": "读取系统已判定的异常告警（越界 / 趋势 / 个体基线三条规则）。所有异常结论必须来自本工具，不要自行判断某项指标是否异常。",
  "parameters": { "type": "object", "properties": {
    "since_days": { "type": "integer", "description": "回溯天数，默认 90" }
  } }
}
```
**实现映射**：`alertRepository.getByMemberSince(memberId, since)` → 用结构化字段（`metricType` / `direction` / `valueText` / `refText`）渲染成文本。

#### ④ `read_report_image`

```json
{
  "name": "read_report_image",
  "description": "读取本轮提问附带的报告图片（影像、病理等无法结构化入库的叙述性报告）。仅当本轮有附图时可用。",
  "parameters": { "type": "object", "properties": {
    "focus": { "type": "string", "description": "想聚焦的内容，如“结论”“建议”“异常项”" }
  } }
}
```
**实现映射**：拿本轮附带的图片 → 走 `resolveQaVisionConfig()` → 用视觉模型做一次"抽取 + 描述"，返回纯文本。

**为什么这个工具在 ReAct 下是合理的**（而在单轮问答里不合理）：
- 单轮场景：图片直接内联进 user message 更简单、更省（1 次请求，模型自己看）
- **多轮 ReAct 场景：base64 图片会随每一轮重发，成本按轮数放大**。工具形式把图片**一次转成文本**，后续轮次上下文保持纯文本。这是它在循环里的真正价值。
- 若将来只做单轮问答，应改成内联图片并砍掉这个工具 —— 这个判断本身值得在面试里讲。

### 4.3 关键发现：`get_alerts` 必须只读

`DetectAnomaliesUseCase.invoke(memberId)` **会写库**：它调用 `alertRepository.createAlert(...)`，并且带「7 天窗口 + 严重度穿透」的去重逻辑。

如果把它直接当工具：
- 用户问一句"我有什么问题吗" → 凭空生成一批告警，进入预警页、可能触发系统通知
- 反复提问会不断触碰去重窗口逻辑（窗口内不重复，但窗口外会重新生成）
- 这属于**用查询的姿势触发了写操作**，语义上是错的，也是 Agent 工具设计里非常典型的一类错误

**因此 A5 的工具是 `get_alerts`（读已落库告警），不是"重新计算"。**

**可选的重构（排在 A5 之后，不在本次范围）**：把规则求值从 `DetectAnomaliesUseCase` 里抽出来——

```kotlin
// 现状：规则求值 + 去重 + 落库 混在一个类里
suspend operator fun invoke(memberId: String): Int

// 目标：拆成两段，求值变纯函数（无副作用、更易测）
suspend fun evaluate(memberId: String): List<Verdict>          // 只算不写
suspend operator fun invoke(memberId: String): Int             // evaluate → 去重 → 落库
```
这个重构本身是加分项（把"规则求值"和"持久化 + 去重"解耦），但它不是 A5 的前置条件：`DailyCheckWorker` 每天已在跑检测，告警表通常是最新的，读已生成的结果足够。

---

## 5. ReAct 循环

### 5.1 循环协议

```
messages = [system(工具说明 + 行为约束), user(问题 + [附图])]

repeat (最多 MAX_TURNS 次):
    流式请求 LLM（带 tools）
    ├─ 收到 tool_calls / tool_use → 执行工具 → 把结果作为 tool 角色消息追加 → 继续下一轮
    └─ 收到正文 / 无工具调用      → 本轮即最终回答，正文逐字上屏 → 结束
```

**双协议差异**（`LlmStreamParser` 需要新增的部分，也是考纲阶段 3 的考点）：

| | OpenAI 兼容 | Anthropic Messages |
|---|---|---|
| 请求声明工具 | `tools: [{type:"function", function:{name, description, parameters}}]` | `tools: [{name, description, input_schema}]` |
| 流式增量 | `delta.tool_calls[]`（分片，需按 `index` 拼 `arguments` 字符串） | `content_block_start(type=tool_use)` + `input_json_delta(partial_json)` |
| 回填结果 | `role:"tool"` + `tool_call_id` | user 消息内的 `tool_result` 块 + `tool_use_id` |
| 结束标记 | `finish_reason:"tool_calls"` | `stop_reason:"tool_use"` |

**最容易被忽略的一点**：OpenAI 的 `tool_calls` 是**分片流式**返回的，`arguments` 是一个逐片拼接的字符串，必须按 `index` 累积到 `finish_reason` 才构成合法 JSON。Anthropic 则是 `input_json_delta` 累积 `partial_json`。两者都不能"看到一段就当完整参数解析"。

### 5.2 事件与流式

`QaStreamEvent` 扩展两个事件（现有 `References` / `Answer` / `Thinking` / `Finished` 不变）：

```kotlin
data class ToolCall(val name: String, val argsSummary: String) : QaStreamEvent
data class ToolResult(val name: String, val summary: String, val ok: Boolean) : QaStreamEvent
```

**一条 SSE 连接贯穿整个循环**：
- 每轮的 token 增量照常以 `Answer` / `Thinking` 事件下发，但**只有最终回答轮的 `Answer` 才允许进入界面正文**
- 中间轮次的模型输出（Thought）走 `Thinking` 通道，界面折叠展示
- 界面表现与现有 `ThinkingBlock` 同构：一个可折叠的「工具调用记录」，展开后是 `工具名 → 返回摘要` 列表
- 解析层复用现有 `LlmStreamParser`（按事件类型分派），扩展点集中在"工具调用分片的累积"

**为什么不能"中间轮次也流到正文"**：第 2 轮的 observation 可能推翻第 1 轮的判断（比如查错了指标）。已经流到屏幕上的半句话撤不回来，而"回答在生成中自我反转"对健康类应用是可信度灾难。

### 5.3 护栏参数（集中在一处，与 `DetectionConfig` 同风格）

建议新建 `data/remote/AgentConfig.kt`：

| 参数 | 值 | 理由 |
|---|---|---|
| `MAX_TURNS` | 5 | 超过 5 轮的问题，答案质量已经不可靠；且每轮成本叠加 |
| `MAX_TOTAL_LLM_CALLS` | 6 | 5 轮工具循环 + 1 轮最终回答，硬上限 |
| `MAX_TOOL_CALLS` | 8 | 防止单轮并行调用 4 个工具 × 5 轮 = 20 次 |
| `MAX_TOOL_CONSECUTIVE_FAILURES` | 2 | 同一工具连续失败 2 次即禁用，防止死循环撞墙 |
| `OBSERVATION_CHAR_BUDGET` | 1_500 | 单个工具结果截断（与 `SUMMARY_CHAR_BUDGET` 同思路，但预算是独立的一份） |
| `TOOL_TIMEOUT_MS` | 10_000 | 本地工具应远快于此 |
| `VISION_TOOL_TIMEOUT_MS` | 60_000 | 视觉调用是真实网络请求 |

**observation 截断的策略**：`search_records` 的结果按"相关度降序"截断（保住最相关的），而不是从头截断；截断时追加「…其余 N 条因预算省略」。

### 5.4 降级矩阵（缺少任何一条都会出现"整个回答没了"）

| 情况 | 处理 |
|---|---|
| 工具抛异常 | 转成 `{"error":"..."}` 文本作为 observation，循环继续 |
| 工具参数畸形 | 同上，并在 observation 里附上正确的类型清单（帮助模型自我修正） |
| 同一工具连续失败 2 次 | 禁用该工具，并在 system 消息里补一句"该工具当前不可用" |
| 达到 `MAX_TURNS` | **用已收集的 observations 直接生成最终回答**，并追加「未完成完整分析，以上基于已获取的记录」 |
| 某轮 LLM 请求失败（网络/429） | 复用现有 `withRetry`；**已有 observation 时不再重试整个循环**，直接用已有证据出答案 |
| 最终回答轮失败 | 复用现有流式降级：保留已生成部分 + `INTERRUPTED_NOTICE` |
| 用户离开页面 | 协程取消 → `call.cancel()` 掐断 socket（与第 8 轮一致），不落库半截结果 |

### 5.5 Reflection 的轻量版（A5 范围）

不做完整 Reflection（多一次独立复核请求），只做一件事：**最终回答轮之前，若 observations 为空或全部失败，不允许模型直接下结论**，改为返回"暂时无法获取足够数据"并引导用户换个问法。防的是最常见的失败模式——模型在没有任何数据的情况下编出一段听起来合理的健康建议。

---

## 6. 代码改动清单

**新增**
| 文件 | 作用 |
|---|---|
| `domain/tool/HealthTool.kt` | 工具契约 + `ToolResult` |
| `domain/tool/HealthToolRegistry.kt` | 注册表、schema 导出、失败计数 |
| `domain/tool/SearchRecordsTool.kt` | 工具 ① |
| `domain/tool/GetReferenceRangeTool.kt` | 工具 ② |
| `domain/tool/GetAlertsTool.kt` | 工具 ③（只读） |
| `domain/tool/ReadReportImageTool.kt` | 工具 ④ |
| `data/remote/ReActAgent.kt` | 循环本体（可注入 LLM 决策接口以便单测） |
| `data/remote/AgentConfig.kt` | 护栏参数 |
| `docs/A5-ReAct设计.md` | 本文件 |

**修改**
| 文件 | 改动 |
|---|---|
| `data/remote/dto/ChatDto.kt` | 请求加 `tools` / `tool_choice`；响应加 `tool_calls`；流式加工具分片 DTO；Anthropic 加 `tool_use` / `tool_result` |
| `data/remote/LlmStreamParser.kt` | 新增工具调用分片累积（按 `index` / `partial_json`） |
| `data/remote/LlmClient.kt` | 新增 `resolveQaVisionConfig()`；流式方法支持带图与带 tools |
| `data/repository/QARepositoryImpl.kt` | 路由器分流；Agent 路径接入 |
| `app/src/main/java/.../domain/repository/QARepository.kt` | `QaStreamEvent` 加 `ToolCall` / `ToolResult` |
| `data/local/entity/QAHistory.kt` + `AppDatabase.kt` + `AppMigrationSql.kt` + `di/DatabaseModule.kt` | **v7→v8 迁移**：`qa_history.imagePath` |
| `data/local/dao/QAHistoryDao.kt` | 图片路径写入（`@Upsert`，勿用 REPLACE） |
| `data/repository/FamilyRepositoryImpl.kt` | 删成员时清理 `qa_images/` |
| `ui/screens/qa/QAScreen.kt` / `QAViewModel.kt` | 选图、缩略图、工具调用折叠块、视觉提示 |
| `res/values/strings.xml` + `values-en/strings.xml` | 新增 4 条字符串（中英对齐） |
| `README.md` | 隐私表述订正；Roadmap 勾掉 ReAct；Highlights 补 Agent 与新工具 |
| `app/src/test/.../SchemaMigrationStructureTest.kt` | 补 v7→v8 一跳 |
| `app/src/androidTest/.../AppDatabaseMigrationTest.kt` | 补 v7→v8 |

---

## 7. 测试计划

现有 81 条 JVM 测试**一条都不覆盖** ReAct 循环，需新增：

**`HealthToolRegistryTest`（结构断言，与 `SchemaNormalizerTest` 的字典断言同构）**
- 每个工具的 `name` 唯一、`description` 非空
- `parametersJsonSchema` 是合法 JSON，且 `required` 里的字段都出现在 `properties`
- 每个工具的 description 里包含"何时该调用"的说明（防止模型不调用）

**`ReActLoopTest`（fake LLM + fake tools，不碰网络与设备）**
- 正常两轮收敛：第 1 轮返回 tool_call → 第 2 轮返回正文 → 事件序列正确
- 达到 `MAX_TURNS`：**降级生成回答而非抛错**，且追加了"未完成完整分析"说明
- 工具抛异常 → 转为 observation，循环继续
- 同一工具连续失败 2 次 → 被禁用，后续轮次的 tools 声明里不再包含它
- 畸形工具参数 → 返回 error observation，不崩
- observations 全空 → 不允许下结论（5.5 的防编造）
- 中间轮次的正文**不进入 `Answer` 事件**（只进 `Thinking`）

**`AgentRouterTest`**
- 命中强指标词 → 快路径
- 未命中 → Agent 路径
- 空问题 / 无记录 → 各自的分支

**扩充 `LlmStreamParserTest`**
- OpenAI `tool_calls` 分片按 `index` 累积成完整 arguments
- Anthropic `content_block_start(tool_use)` + `input_json_delta` 累积
- 分片中途畸形 JSON → 不崩，作为失败 observation

---

## 8. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 成本随轮数超线性 | 每轮重发 tools schema + 全部 observations；第 4 轮输入 ≈ 前三轮之和 | 严格控制轮数上限；`read_report_image` 把图片转文本避免 base64 重发；工具 schema 别做太细 |
| 可靠性叠加 | 单次 99% → 4 轮 ≈ 96% | 5.4 降级矩阵：任何一环失败都能给出"已获取部分的答案" |
| 工具描述写得不好导致不调用 | 模型直接凭记忆回答，绕过确定性判断 | description 必须写清"何时调用"；`get_alerts` 明确写"结论必须来自本工具" |
| 模型绕过工具编造数值 | 健康类应用最严重的失败 | system 约束 + 5.5 的空观测保护 + 现有「只依据记录分析」规则 |
| Room 版本升级踩坑 | 中途构建的包把 DB 烧到 v8 但列不全，之后无法迁移 | 遵守既有纪律：构建两次、核对 androidTest schema 列表、当天不装半成品包 |
| 范围蔓延 | 从"加个循环"变成"重写问答链路" | 快路径不动；Reflection / Multi-Agent / LangGraph 明确排除 |

---

## 9. 面试话术锚点

这个改动一次覆盖两位考纲内容，值得把下面几点练熟：

1. **ReAct 循环我是手写的**——`Thought → Action → Observation` 的循环、终止条件、失败注入都在 `ReActAgent` 里，没有框架。
2. **为什么保留快路径**：指向具体指标的问题用 Agent 是负优化（1 次请求变 2 次，质量不变）。我做的是路由，不是替换。
3. **最值钱的那个坑**：`detect_anomalies` 直接当工具用会写库——用户问一句话就凭空多出告警。工具必须只读，写操作是另一个入口。（这条比"我实现了 ReAct"更能证明工程判断）
4. **流式和 ReAct 的冲突**：循环期间没有最终答案可流，中间轮次的方向可能被推翻。解法是一条 SSE 贯穿、只让最终轮进正文。
5. **双协议的第二次扩展**：OpenAI 的 `tool_calls` 是分片字符串累积，Anthropic 是 `input_json_delta`；回填格式也不同（`role:tool` vs `tool_result` 块）。
6. **护栏是设计的一部分**：轮数上限、observation 预算、连续失败禁用、观测全空时不允许下结论。
7. **图片为什么做成工具而不是内联**：多轮循环里 base64 会随每轮重发，工具形式一次性转文本；如果只做单轮，内联更简单——判断随场景变。

---

## 10. 实施顺序

前置改动 A（发图）会引入 Room 迁移，风险等级高于 B，因此**分两次做**：

| 步 | 内容 | 验收 |
|---|---|---|
| 1 | **前置改动 A**：`qa_history.imagePath`（v7→v8）+ QAScreen 发图 + `resolveQaVisionConfig()` | 3.7 的五条；`testDebugUnitTest` 全绿；迁移测试通过 |
| 2 | 工具 ①②③（皆只读）+ `HealthToolRegistry` + 结构断言测试 | `HealthToolRegistryTest` 全绿 |
| 3 | `ReActAgent` 循环 + `AgentConfig` 护栏 + `ReActLoopTest`（fake LLM） | 7 节全部循环用例 |
| 4 | 路由器接入 `QARepositoryImpl` + `AgentRouterTest` | 快路径行为与现在逐字一致（回归） |
| 5 | 工具 ④ `read_report_image` + 双协议工具调用解析 + 扩充 `LlmStreamParserTest` | 发图 + Agent 路径端到端 |
| 6 | 文档同步（README / 清单 / 本文件状态改为"已实现"） | — |

第 1 步单独做完就可以先提交——它自身是完整的用户价值，且不依赖 Agent。
