<div align="center">

# 🏠 HomeHealth · 家庭健康管家

**Snap a lab report → AI extracts health metrics → one place for the whole family's health**
**拍照上传体检报告 · AI 自动提取健康指标 · 全家健康档案一站管理**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM%20%2B%20Clean-FF6F00)](https://developer.android.com/topic/architecture)
[![Version](https://img.shields.io/badge/version-1.2.0-blue)](./app/build.gradle.kts)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

[About](#-about--项目简介) · [Pipeline](#-core-pipeline--核心流程) · [Highlights](#-highlights--差异化亮点) · [Features](#-feature-overview--功能全景) · [Tech](#-tech-stack--技术架构) · [Quick Start](#-quick-start--快速开始)

</div>

---

## 📖 About | 项目简介

**English**

HomeHealth is a local-first, privacy-focused family health manager for Android. Snap a photo of a lab report or health checkup sheet, and a **Vision LLM** extracts 49 types of structured health metrics in JSON mode — complete blood count, glucose, lipids, liver & kidney function, vitamins and more. A **Schema Normalization** layer then maps 106 metric aliases to a standard dictionary, unifies 24 unit spellings, and applies 24 clinically reliable unit conversions before anything reaches the database. Every family member gets an independent health profile with three-rule anomaly detection (reference ranges / trend windows / personal baseline), streaming record-grounded Q&A with cited source records — open-ended questions are handled by a hand-written **ReAct agent** over four read-only health-data tools — and medication reminders synced with the system calendar. Every LLM call is logged locally (latency / retries / failure type — never the prompt content). The UI is fully bilingual (English / 中文).

> 🔐 **Local-first, privacy first**: all health data lives in an on-device Room database, and the app talks to **no server of mine**. Data leaves the phone only when you explicitly trigger report parsing or Q&A — and then it goes **directly to the LLM provider you configured yourself**, authenticated with your own API key. API keys are AES-256-GCM encrypted with Android Keystore; the app is fully usable without configuring any LLM (a built-in offline Q&A engine covers the basics).

**中文**

HomeHealth 面向多成员家庭，是一款本地优先、隐私至上的 Android 健康管理应用。只需拍照或上传体检报告、化验单，**Vision 大模型**即可通过 JSON 模式自动提取 49 类结构化健康指标（血常规、血糖血脂、肝肾功能、维生素等）。入库前，**Schema 归一化**层会把 106 条指标别名映射到标准字典、统一 24 种单位写法、执行 24 类医学上可靠的跨单位换算。每位家庭成员拥有独立健康档案，异常检测由三条规则组成（参考范围 / 趋势时间窗 / 个体基线），另有流式、可溯源到个人记录的健康问答——泛化问题交给一个手写的 **ReAct Agent**（4 个只读健康数据工具）——以及与系统日历联动的用药提醒。每次 LLM 调用都在本地留有观测日志（耗时 / 重试 / 失败类型——不含提示词内容）。界面已全面支持中英双语。

> 🔐 **本地优先，隐私至上**：所有健康数据以 Room 数据库存储在设备本地，应用**不经过任何属于开发者的服务器**。数据只在你主动触发报告解析或问答时离开手机，且**直连你自己配置的大模型服务商**、用你自己的 API Key 认证。API Key 经 Android Keystore AES-256-GCM 加密后保存；不配置任何 LLM 也完全可用（内置离线问答引擎兜底）。

## 🔄 Core Pipeline | 核心流程

**English — from report to insight:**

```
┌─────────┐   ┌──────────────┐   ┌────────────────┐   ┌─────────────┐   ┌────────────┐
│ 📷 Photo │ → │ 🔍 Vision LLM│ → │ 📐 Normalize   │ → │ ✅ Confirm  │ → │ 📊 Insights │
│ upload   │   │ JSON extract │   │ names + units  │   │ editable    │   │ trends     │
└─────────┘   └──────────────┘   └────────────────┘   └─────────────┘   └────────────┘
                     │                    │                                   │
                     │ image downsampling │ 106 alias mappings                │ trend charts
                     │ (OOM-safe)         │ 24 unit spellings unified         │ three-rule alerts
                     │ dual protocol      │ 24 reliable unit conversions      │ record-grounded Q&A
                     │ (OpenAI/Anthropic) │ (no guessing on unknown units)    │ med reminders
                     └────────────────────┴───────────────────────────────────┴────────────┘
```

1. **Photo upload** — camera or gallery; images are downsampled (max edge 1600px) to prevent OOM on large photos
2. **Vision LLM parsing** — a vision model (e.g., glm-4.6v, qwen-vl) extracts metric name / value / unit / date in JSON mode; text-only models are rejected by runtime validation
3. **Schema normalization** — aliases mapped to the 49-metric standard dictionary; units unified; cross-unit conversions applied only with reliable coefficients — **unknown units are never guessed**
4. **Human confirmation** — results shown as editable cards (with conversion annotations) before saving; implausible values get an explicit double-confirm
5. **Continuous insights** — trend charts, three-rule anomaly detection, streaming record-grounded Q&A over personal records, daily medication reminders

**中文 —— 从报告到洞察：**

1. **拍照上传**：拍照或相册选取报告图片，自动降采样（最长边 1600px）防止大图 OOM
2. **Vision LLM 解析**：视觉大模型以 JSON 模式提取指标名/数值/单位/日期，文本模型在此被严格校验拦截
3. **Schema 归一化**：指标别名映射标准字典（49 项指标体系），单位写法统一，跨单位按可靠系数换算——**没有系数的单位绝不猜测**
4. **人工确认**：解析结果以可编辑卡片展示（含换算标注），核对修正后入库；明显可疑的数值会触发二次确认
5. **持续洞察**：趋势折线图、三规则异常检测、流式且可溯源到个人记录的健康问答、每日用药提醒

## 💎 Highlights | 差异化亮点

### 1. 📐 Schema Normalization — metric standardization | 指标标准化归一化

**EN**: Most AI report parsers output whatever name and unit the model feels like: `vitamin D / 25-OH-D / 25(OH)D` all over the place, `nmol/L` vs `ng/mL` incomparable. HomeHealth normalizes before storage: **106 alias mappings, 24 unit spellings unified, 24 clinically reliable conversion factors** — so trends and alerts are always built on one consistent measurement system. Interval results like `<0.1` / `>100` are carried with their comparator instead of being dropped. Unknown units are kept as-is and clearly annotated.

**中文**：多数 AI 报告解析工具直接输出「模型认为的」指标名和原始单位，同一指标在不同报告中 `维生素D / 25-羟维生素D / 25(OH)D` 各写各的，`nmol/L` 与 `ng/mL` 数值不可比。HomeHealth 在入库前强制归一化：**106 条指标别名映射、24 种单位写法统一、24 类跨单位医学换算系数**。`<0.1` / `>100` 这类区间型结果连同比较符一起入库，不再被丢弃。未知单位保持原样并明确标注，绝不猜测。

### 2. 👁️ Strict vision/text model separation | 视觉/文本模型严格分离

**EN**: Report parsing must use a **vision model** (image input capable); health Q&A uses a **text model**. The two are configured and validated independently. Picking a text model for parsing is intercepted at runtime with a clear message — instead of a pile of hallucinated data.

**中文**：报告解析必须用**视觉模型**（支持图片输入），健康问答用**文本模型**——两者独立配置、独立校验。误选文本模型解析图片会被运行时拦截并给出明确指引，而不是返回一堆幻觉数据。

### 3. 🚨 Three-rule anomaly detection | 三规则异常检测

**EN**: Detection runs three complementary rules — **(1) Reference range** (sex-specific where it matters; interval results judged conservatively: `<0.1` only alerts "low" when the boundary itself is already below the limit), **(2) Trend** (three consecutive same-direction readings whose cumulative change exceeds a per-metric threshold, valid only within a 180-day window — readings two years apart are not a "trend"), **(3) Personal baseline** (current value vs the median of your own history, ≥5 samples — group reference ranges cannot answer "is this abnormal *for you*"). Deduplication = 7-day window + severity escalation: a relapsing condition can re-alert, a worsening one escalates.

**中文**：异常检测由三条互补规则组成——**(1) 越界**（按性别取参考范围；区间型结果保守判断：`<0.1` 只有在边界值本身已低于下限时才报"偏低"），**(2) 趋势**（连续三次同向且累计变化超指标阈值，且三次读数须落在 180 天窗口内——跨两年的读数不构成"趋势"），**(3) 个体基线**（当前值对比自身历史中位数，至少 5 个样本——群体参考范围回答不了"对你来说是否异常"）。去重策略为「7 天窗口 + 严重度穿透」：症状复发能再次报警，病情恶化能升级报警。

### 4. 📊 LLM call observability | LLM 调用可观测性

**EN**: Every LLM call is logged to a local table: provider / model / scene (parsing vs Q&A) / latency / prompt & completion sizes / attempts / failure type (config vs network vs parse). The settings screen aggregates the last 30 days per provider — so "is it this provider or my network" is answerable from data. The log **never contains prompts or responses** — that would mean a second copy of your health data on the device.

**中文**：每次 LLM 调用都会写入本地观测表：供应商 / 模型 / 场景（解析 vs 问答）/ 耗时 / 提示与补全字符数 / 重试次数 / 失败类型（配置 vs 网络 vs 解析）。设置页按供应商聚合近 30 天统计——「是这家供应商的问题还是我的网络」从此有数据可答。日志**绝不包含提示词与回复内容**——那等于在设备上再存一份健康数据。

### 5. 🔌 7 LLM providers, dual protocol | 7 家 LLM 供应商，双协议适配

**EN**: Zhipu GLM / OpenAI / Google Gemini / DeepSeek / Kimi / Tongyi Qwen / Anthropic Claude. Beyond the OpenAI-compatible protocol, Anthropic's **Messages API** is natively supported (separate system param, image base64 source format). Reasoning content (`reasoning_content` / `thinking`) from thinking models is rendered as a collapsible "reasoning process" block.

**中文**：智谱 GLM / OpenAI / Gemini / DeepSeek / Kimi / 通义千问 / Anthropic Claude。除 OpenAI 兼容协议外，原生适配 **Anthropic Messages API**（system 独立传参、图片 base64 source 格式）。深度思考模型的 `reasoning_content` / `thinking` 会解析为可折叠的「思考过程」展示。

### 6. 🔐 Truly local-first | 真正的本地优先

**EN**: Health data and Q&A history live only in the on-device Room database. API keys are encrypted with **Android Keystore (AES-256-GCM)** — key material never leaves the TEE, and legacy plaintext keys are migrated in place. Android backup is disabled (`allowBackup="false"`), so none of it can be pulled out via `adb backup` or cloud backup. When you explicitly trigger parsing or asking, the request goes **straight to the provider you configured, authenticated with your own API key** — nothing is relayed through a server of mine, and there is no background upload whatsoever. Without a key the app stays fully offline: a local rule engine answers basic questions.

**中文**：健康数据与问答历史仅存于设备 Room 数据库。API Key 经 **Android Keystore（AES-256-GCM）**加密存储——密钥材料不出 TEE，历史明文 Key 会就地平滑迁移。已关闭 Android 备份（`allowBackup="false"`），无法通过 `adb backup` 或云备份取出。你主动触发解析/提问时，请求会**直连你自己配置的服务商、用你自己的 API Key 认证**——不经过开发者服务器，也没有任何后台数据上报。不配 Key 时应用完全离线，由本地规则引擎兜底问答。

### 7. 🌍 Bilingual alerts + UI | 双语告警与界面

**EN**: Alerts are stored as **structured facts** (metric / direction / value / reference) rather than finished strings, and rendered in the current language at display time — switching the UI to English turns every alert into English, including historical ones. Full English / 中文 / follow-system switching via Android per-app locales. Light/dark/system theme modes; each of the five modules carries its own accent color that follows you through navigation.

**中文**：告警以**结构化事实**（指标 / 方向 / 数值 / 参考范围）落库，而非成品文案，展示时按当前语言现场渲染——切到英文后所有告警（含历史告警）都显示英文。基于 Android per-app locale 的中英文/跟随系统语言切换；浅色/深色/跟随系统主题模式；五大模块各持独立主题色，切换页面时整页色彩随动。

### 8. 📅 Calendar two-way sync | 用药提醒与系统日历联动

**EN**: Reminders can be written into the system calendar with one tap (daily recurring events + 5-minute-ahead notifications). Deleting a reminder also cleans up its calendar events — dual-channel cleanup via stored event IDs plus a signature-based fallback for legacy events. No orphan calendar entries.

**中文**：提醒可一键写入系统日历（每日重复事件 + 提前 5 分钟通知），**删除提醒时日历日程同步清理**（事件 ID 精确删除 + 签名兜底双通道），不产生孤儿日程。

### 9. 📡 Streaming answers, data basis first | 流式回答 · 数据依据先行

**EN**: Health Q&A streams over SSE — the answer appears token by token instead of after the whole generation. Before the first token even arrives, the app has already shown the **data basis**: which saved records the answer draws on, with reference ranges and high/low flags — so the user sees what the answer stands on *before* reading it. On the keyword-retrieval path every record carries an `[n]` id and the model is instructed to cite it inline, making every figure in the answer traceable to a specific record. General questions that don't point at a metric get a data-scope summary instead (how many records, which metrics) — the basis block is never empty. Report parsing deliberately stays non-streaming: it needs the complete JSON object before anything can be saved.

**中文**：健康问答走 SSE 流式——回答逐字上屏，而不是等整段生成完。**在首个 token 到达之前，界面已经先给出数据依据**：本次回答基于哪些已保存记录（带参考范围与偏高/偏低标注），用户在读到结论前就知道它站在什么之上。走检索路径时每条记录带 `[n]` 编号，并要求模型在数值后内联标注编号，答案里的每个数字都能追溯到具体记录；不指向具体指标的泛化问题则给出数据范围（多少条记录、覆盖哪些指标），依据块永不为空。报告解析刻意保持非流式——它需要完整的 JSON 对象才能入库。

### 10. 📜 First-run consent, not a buried notice | 首启主动同意，而非埋一段文字

**EN**: Consent for how health data is handled is obtained **before a single record can be entered** — a full-screen, scrollable notice covering four things: where the data lives (this phone; there is no developer server), when it leaves the device (only once you enable a provider and enter your own API key), what uninstalling does (deletes everything; system backup is disabled), and the medical disclaimer. Declining exits the app, and notification permission is only requested *after* consent. The notice is **versioned**: change the text materially, bump the version, and every existing user sees it again. Health records are sensitive personal information — a paragraph buried in Settings is not consent.

**中文**：对「数据怎么被处理」的同意，是在**能录入任何一条记录之前**取得的——一屏可滚动的说明，覆盖四件事：数据存在哪（本机，没有开发者服务器）、什么时候离开设备（只有你启用供应商并填自己的 Key 之后）、卸载会发生什么（全部删除，系统备份已关闭）、以及医疗免责声明。不同意即退出应用，且通知权限在同意**之后**才申请。说明带**版本号**：文案有实质变更就递增版本，所有老用户会重新看到并再次同意。健康记录属于敏感个人信息——在设置页里埋一段文字不构成同意。

## ✨ Feature Overview | 功能全景

| Feature | 功能 |
| --- | --- |
| 👨‍👩‍👧‍👦 Multi-member family profiles · avatars | 多成员家庭档案 · 成员头像 |
| 📸 Vision LLM report parsing · editable results | 视觉大模型报告解析 · 结果可编辑 |
| 📐 Schema normalization (aliases / units / conversions / comparators) | 指标归一化（别名 / 单位 / 换算 / 比较符） |
| ✏️ Full record lifecycle · dual-value blood pressure | 记录全生命周期 · 血压双值输入 |
| 📈 Trend charts · latest / avg / high / low stats | 趋势折线图 · 最新/平均/最高/最低统计 |
| 🚨 Three-rule anomaly alerts · severity levels · notifications | 三规则异常预警（越界/趋势/个体基线）· 分级 · 系统通知 |
| 📊 LLM call logs · 30-day per-provider stats | LLM 调用日志 · 近 30 天按供应商统计 |
| 💬 Streaming Q&A · data basis · **ReAct agent** · image attach · offline engine | 流式问答 · 数据依据 · **ReAct Agent** · 随提问附图 · 离线引擎 |
| 💊 Medication reminders · calendar two-way sync | 用药提醒 · 日历双向联动 |
| 🌍 English / 中文 UI (alerts included) · dark mode · per-module theming | 中英双语界面（含告警）· 深色模式 · 模块化主题 |
| 🖼️ Splash screen · adaptive app icon | 启动页 · 自适应应用图标 |

## 🏗️ Tech Stack | 技术架构

**EN**: Kotlin with coroutines & Flow · Jetpack Compose + Material 3 (single Activity + Navigation) · MVVM + Clean Architecture (`ui` / `domain` / `data`) · Hilt DI · Room v7 (progressive migrations) · OkHttp + Gson for LLM calls · WorkManager daily health checks · CalendarProvider integration. Built with AGP 9.3 / Kotlin 2.3 / compileSdk 37 / minSdk 26.

**中文**：Kotlin 协程 + Flow · Jetpack Compose + Material 3（单 Activity + Navigation）· MVVM + Clean Architecture（`ui` / `domain` / `data` 三层）· Hilt 依赖注入 · Room v7（渐进式迁移）· OkHttp + Gson（LLM 直连）· WorkManager 每日健康检查 · CalendarProvider 日历集成。基于 AGP 9.3 / Kotlin 2.3 / compileSdk 37 / minSdk 26 构建。

```
┌───────────────────────────────────────────┐
│           Presentation Layer              │
│   Compose UI · ViewModel · Navigation     │
├───────────────────────────────────────────┤
│              Domain Layer                 │
│   Use Cases · Repository 接口 · 领域模型   │
├───────────────────────────────────────────┤
│               Data Layer                  │
│         Repository 实现 · Mapper          │
│  ┌────────────────────┬───────────────┐   │
│  │ Room 本地库 (7 表)  │ LlmClient     │   │
│  │ 7 DAOs             │ (双协议)       │   │
│  └────────────────────┴───────────────┘   │
└───────────────────────────────────────────┘
```

**Key engineering decisions | 关键工程决策**

- **Never guess an unknown unit** — cross-unit conversion applies only when a clinically reliable factor exists; otherwise the original value and unit are kept as-is and annotated — 未知单位绝不猜测：只有存在可靠换算系数时才换算，否则保留原值与单位并明确标注
- **Interval results are judged conservatively** — a reading reported as `<0.1` only raises a "below range" alert when the boundary itself already crosses the limit; "not sure whether it is out of range" is never reported as a definite conclusion — 区间型结果（`<0.1` / `>100`）按比较符保守判断，只有边界值本身已越界才下结论
- **Deliberately *not* muting "stably elevated" values** — long-term BP at 135 is itself a signal worth seeing; the personal-baseline rule catches *changes from your own history*, it does not silence chronic readings — 有意不做「长期稳定偏高就静音」：个体基线规则捕捉的是"相对自身历史的变化"，不会掩盖慢性问题
- **Trends need a time window** — three same-direction readings spanning two years are not a "sustained trend"; detection only fires within 180 days and the alert states the real span — 趋势判定有时间窗：跨两年的三次同向读数不构成"持续上升"，超出 180 天不判趋势
- **Alert de-duplication = time window + severity escalation** — deduped within 7 days, but a worsening reading still gets through. Global de-duplication would make a relapsing condition permanently silent, which is worse than occasional noise — 预警去重为「7 天窗口 + 严重度穿透」：窗口内不重复，但病情恶化必须能升级报警
- **Two independent privacy layers** — `allowBackup="false"` closes the backup-extraction path (adb / cloud); Android Keystore encryption (key material never leaves the TEE) raises the bar for offline `/data` dumps. Different threats — neither replaces the other — 两层互不替代的隐私防线：关闭备份导出通道 + Keystore 加密 API Key
- **Observability without copying sensitive data** — `llm_call_logs` records latency / sizes / retries / failure types only; prompts and responses (which contain health metrics) are never logged — 可观测性不复制敏感数据：调用日志只记耗时/字符数/重试/失败类型，含健康指标的提示词与回复不入库
- **`@Upsert` instead of `INSERT OR REPLACE`** — SQLite's REPLACE deletes then re-inserts, which fired FK cascade deletion and once wiped a member's health records — 用 `@Upsert` 替代 REPLACE，避免外键级联误删（真实的踩坑复盘）
- **Parsing catches `Throwable`, not just `Exception`** — `OutOfMemoryError` is an `Error`; catching only `Exception` crashes the app. It is now a retryable failure state with stuck-state recovery — 解析路径捕获 `Throwable`，OOM 转为可重试失败态，并有僵尸状态恢复
- **LLM calls go through raw OkHttp, streaming only where it pays** — Q&A is **SSE-streamed** (first-token latency instead of wait-for-completion), report parsing stays **non-streaming single-shot** (a half-received JSON object is worthless when the parser needs the complete one); both share fine-grained timeouts and exponential-backoff retry limited to 429/5xx/IO — LLM 请求走 OkHttp 原生实现，**只在有收益的地方流式**：问答走 **SSE 流式**（首字延迟从"整段生成完"降到"首个 token"），报告解析保持**非流式单次请求-响应**（解析要的是完整 JSON，半截对象没有意义）；两条链路共用精细超时与仅限 429/5xx/IO 的指数退避重试
- **Streaming retries only before the first token** — once part of the answer is on screen, a retry would duplicate it; a mid-stream failure keeps what the user already read and appends an explicit notice instead of silently restarting — 流式只在首个增量到达前重试：回答已部分上屏时重发会造成重复，中途失败保留已生成内容并显式说明，不静默重来
- **Cancelling a stream cancels the socket, not just the coroutine** — a blocking read never returns on coroutine cancellation, so the in-flight call is cancelled on the way out, releasing the connection instead of holding it until the 120s read timeout — 取消流式要掐断 socket 而不只是取消协程：阻塞读不会因协程取消而返回，退出时主动 `call.cancel()`，避免连接挂到 120s readTimeout 才释放
- **Agent tools are read-only** — the anomaly tool *reads* alerts that the deterministic engine already decided; it never re-runs detection, because that use case writes rows and can raise notifications. Wrapping it directly would mean "ask one question → alerts appear out of nowhere" — 工具必须只读：异常工具读**已判定**的告警，不重跑检测。`DetectAnomaliesUseCase` 会写库并可能触发通知，直接包成工具等于「问一句话就凭空多出告警」
- **The last agent turn gets no tools** — rather than ending with unfilled tool calls when the turn budget runs out, tool declarations are dropped on the final turn, so "budget exhausted" and "normal finish" share one code path and there is never a turn with no answer — 最后一轮撤掉工具声明：让「轮数用尽」与「正常收尾」走同一条路径，不会出现「没有回答」的空档
- **Agent answers are committed in one piece, not token-streamed** — only after a turn ends do we know it was the final one; streaming intermediate reasoning and then retracting it is visible flicker. The fast path still streams token by token — Agent 路径的最终回答整段提交而非逐字上屏：只有一轮结束后才知道它是不是最终回答，中间轮先流再撤回对用户是明显抖动；快路径仍逐字上屏
- **Server-side turn budget on the client** — max 5 turns, an 8-call tool cap, per-observation truncation, and a tool disabled after 2 consecutive failures, because a model will happily retry the same malformed argument forever — 客户端侧硬护栏：最多 5 轮、工具调用上限 8 次、单条观测截断、同一工具连续失败 2 次即禁用 —— 模型会对着同一个错参数反复重试

### 🗄️ Database evolution | 数据库演进

The Room schema is exported to `app/schemas/` (committed), and **every version bump ships with a hand-written migration**:

| Version | Change |
| --- | --- |
| v1 → v2 | `family_members.heightCm` / `weightKg` |
| v2 → v3 | `qa_history.thinking`（深度思考模型的思考过程） |
| v3 → v4 | `medication_reminders.calendarEventIds` |
| v4 → v5 | indices on `alerts(memberId)`、`health_records(memberId, type, recordDate)` |
| v5 → v6 | `health_records.comparator`（承载 `<0.1` / `>100` 这类区间型结果） |
| v6 → v7 | `llm_call_logs` 表（LLM 调用可观测性）+ `alerts` 7 列结构化告警字段 |

- `fallbackToDestructiveMigration()` is deliberately **not** used: a version jump fails loudly instead of silently wiping the user's health records — 刻意不使用破坏性迁移兜底：宁可启动失败也不静默清库
- An instrumented `MigrationTestHelper` test validates the **v4→v7 chain and the v6→v7 single hop** against the exported schemas and asserts that data survives (`gradlew connectedDebugAndroidTest`, requires a device/emulator) — 插桩迁移测试对导出 schema 校验 v4→v7 全链与 v6→v7 单跳，并断言数据不丢
- 18 JVM unit tests cover `SchemaNormalizer` — conversions, unit canonicalization, comparator handling — plus **structural assertions on the dictionaries themselves**: every alias must point to a defined metric, metric types must be unique, and sex-specific ranges must be well-formed with low < high — 18 条 JVM 单测覆盖归一化层（换算 / 单位 / 比较符），并对字典本身做结构性断言：别名必须指向已定义指标、指标类型不得重复、性别区间必须上下限合法
- Index names must match Room's generated `index_<table>_<column>` **character for character**, or opening the database throws `IllegalStateException` — 索引名必须与 Room 的生成规则逐字一致
- ⚠️ **After bumping the DB version, build twice**: the androidTest asset merge does not depend on the schema-generation task, so a newly added `<N>.json` can miss the test APK on the first build (compilation still succeeds — the test only fails at runtime) — 升版本后要构建两次，否则迁移测试会在运行时找不到 schema

## 🤖 LLM Provider Support | LLM 供应商支持

| Provider | 供应商 | Vision parsing | Text Q&A | Protocol |
| --- | --- | :-: | :-: | --- |
| Zhipu GLM | 智谱 GLM | ✅ | ✅ | OpenAI-compatible |
| OpenAI | OpenAI | ✅ | ✅ | OpenAI-compatible |
| Google Gemini | Google Gemini | ✅ | ✅ | OpenAI-compatible |
| DeepSeek | DeepSeek | ✅ (experimental) | ✅ | OpenAI-compatible |
| Kimi (Moonshot) | Kimi 月之暗面 | ✅ | ✅ | OpenAI-compatible |
| Tongyi Qwen (Alibaba) | 通义千问（百炼） | ✅ | ✅ | OpenAI-compatible |
| Anthropic Claude | Anthropic Claude | ✅ | ✅ | Messages API |
| Local mode (no key) | 本地模式（无需 Key） | ➖ | ✅ rule engine | offline |

## 🚀 Quick Start | 快速开始

**EN**

- Android Studio Ladybug+ / JDK 17 (source compatibility) / Android SDK 37 (min. Android 8.0 / API 26)

```bash
git clone https://github.com/SACO1F/HomeHealth.git
cd HomeHealth

# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

Or open the project in Android Studio and hit Run. APK output: `app/build/outputs/apk/debug/`.

> 🔧 **Build environment**: source compatibility is JDK 17, but the Gradle **daemon** JVM is pinned by `gradle/gradle-daemon-jvm.properties` (JDK 25 in the reference environment). That file is gitignored, so each machine keeps its own toolchain. Building from the command line therefore needs `JAVA_HOME` set to a matching JDK.

> 💡 **Works out of the box**: family profiles, manual records, trend charts, alerts, medication reminders and offline Q&A all work without any LLM key. Fill in any provider's API key under *Settings → Report Parsing / Health Q&A Service* to unlock photo-based report parsing and AI Q&A.

**中文**

- Android Studio Ladybug 及以上 / JDK 17（源码兼容级别）/ Android SDK 37（最低支持 Android 8.0 / API 26）

```bash
git clone https://github.com/SACO1F/HomeHealth.git
cd HomeHealth

# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

或直接用 Android Studio 打开项目，点击 Run。APK 输出在 `app/build/outputs/apk/debug/`。

> 🔧 **构建环境说明**：源码兼容级别是 JDK 17，但 Gradle **daemon** 的 JVM 由 `gradle/gradle-daemon-jvm.properties` 指定（参考环境下为 JDK 25）。该文件已加入 `.gitignore`，每台机器保留自己的工具链；因此从命令行构建时需要把 `JAVA_HOME` 指向匹配的 JDK。

> 💡 **开箱即用**：不配置任何 LLM Key 也能使用家庭档案、手动记录、趋势图、预警、用药提醒和离线健康问答；在「设置 → 报告解析服务 / 健康问答服务」中填入任意供应商的 API Key 后，即可解锁拍照解析报告和 AI 问答。

> 🔑 **Release signing | 正式签名与分发**
>
> Debug builds are signed with the shared debug keystore and carry `android:debuggable="true"` — fine for local testing, **not for distribution**. To produce a distributable build, create your own keystore and point a gitignored `keystore.properties` (repo root) at it:
>
> ```bash
> keytool -genkeypair -keystore keystore/homehealth-release.jks -alias homehealth \
>     -keyalg RSA -keysize 4096 -validity 10000
> ```
>
> ```properties
> # keystore.properties — repo root, gitignored
> storeFile=keystore/homehealth-release.jks
> storePassword=…
> keyAlias=homehealth
> keyPassword=…
> ```
>
> then `gradlew.bat assembleRelease`. Both `keystore.properties` and the `keystore/` folder are gitignored — verify with `git check-ignore -v keystore.properties keystore/homehealth-release.jks` before your first commit. When the file is absent the release variant is simply left unsigned, so a fresh clone still builds. **Keep an offline backup of the keystore** — losing it means you can never update an installed copy again (users would have to uninstall, losing all local data).
>
> 调试包由公共调试证书签名并带 `android:debuggable="true"`，适合本机测试，**不可用于分发**。要出可分发版本，请自建密钥库，并用仓库根目录的 `keystore.properties`（**已 gitignore**）指向它，然后 `gradlew.bat assembleRelease`。`keystore.properties` 与 `keystore/` 目录都在 `.gitignore` 内——首次提交前用 `git check-ignore -v keystore.properties keystore/homehealth-release.jks` 确认一下。该文件不存在时 release 变体不签名，因此他人 clone 后仍可正常构建。**请离线备份密钥库**——丢了就永远无法再更新已安装的版本（用户只能卸载重装，本地数据全丢）。

## 📁 Project Structure | 项目结构

```
app/src/main/java/com/example/homehealth/
├── data/            # Data layer: Room, DAOs, LLM client, repository impls
│   ├── remote/      #   LlmClient (dual protocol), LlmProviders, offline QA engine
│   ├── local/       #   Room entities & DAOs (7 tables incl. llm_call_logs)
│   └── repository/  #   Repository implementations
├── domain/          # Domain layer: models, repository interfaces, use cases
├── di/              # Hilt modules (incl. progressive DB migrations)
├── ui/              # Compose UI: navigation, module themes, 8 screens
├── worker/          # WorkManager background jobs & notifications
└── util/            # SchemaNormalizer, HealthTypes, DetectionConfig, AlertText, SecretStore...
```

Design brief (pre-implementation, Chinese) — 实现前设计蓝图，与最终实现存在差异: [docs/开发文档.md](./docs/开发文档.md)

## 🗺️ Roadmap

Q&A today is streaming keyword retrieval (BM25) over personal records — no chunking, embedding or vector retrieval yet. The evolution path, one step at a time:

当前问答是「流式 + 关键词检索（BM25）」式 RAG——尚无切分、embedding 与向量检索。演进路线一次一步：

- [x] **Keyword-retrieval RAG** for health Q&A: BM25 top-K over personal records + citation of source records — 健康问答检索层：BM25 Top-K 召回 + 引用溯源（含数据依据块）
- [x] **SSE streaming** answers — SSE 流式回答（问答链路；报告解析需完整 JSON，刻意保持非流式）
- [x] **ReAct agent** over a health-data toolset, behind a router that keeps the fast path — ReAct Agent：4 个健康数据工具（检索记录 / 参考范围 / 已判定告警 / 读报告图片）+ 路由器（保留快路径）
- [ ] **Vector retrieval / hybrid search**: embeddings + a local vector store alongside BM25, with reranking — 向量检索 / 混合检索：embedding + 本地向量库，与 BM25 组成混合检索并加重排
- [ ] **MCP server** exposing health data tools — 以 MCP Server 暴露健康数据工具
- [ ] On-device LLM inference, fully offline — 端侧 LLM 推理，完全离线运行
- [ ] Health Connect wearable data — 集成 Health Connect 可穿戴设备数据
- [ ] More document types (imaging reports, etc.) — 支持更多文档类型（影像报告等）

## ⚠️ Disclaimer | 免责声明

**EN**: Reference ranges, anomaly alerts and Q&A content provided by this project are for wellness management reference only and do **not constitute medical advice**. The reference-range dictionary covers common adult values with sex stratification only (no age stratification); always defer to the ranges printed on your own lab report. Consult a qualified physician for any health concerns.

**中文**：本项目提供的指标参考范围、异常预警和问答内容**仅供健康管理参考，不构成医疗建议**。参考范围字典覆盖常见成人值、仅做性别分层（无年龄分层），请以你自己的化验单标注为准。如有健康问题，请及时咨询专业医生。

## 📄 License

MIT License — see [LICENSE](./LICENSE). 本项目基于 [MIT License](./LICENSE) 开源。
