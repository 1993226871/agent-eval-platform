# 通用 Agent 评测平台设计

- 日期：2026-10-25（Asia/Shanghai）
- 状态：设计初稿，待用户审批；不代表已实现或已验证。
- 范围：第一版产品、技术架构和协议边界；实现计划在设计获批后编写。
- 项目目录：`C:\Users\lx\Documents\ChatGPT\agent通用评测`。
- 本轮开始时仓库为空，本项目独立于用户背景中的 Codex-academy，不修改其目录。

## 1. 产品意图

让用户在私人工作空间内，为自己的 Agent 建立可重复的质量评测流程。用户管理任务集和评测体系，在平台发起评测；Agent 仍在用户环境运行，Java/Python SDK Worker 主动领取任务、调用 Agent、采集轨迹并上报。平台使用规则、模型评审和 Python 自定义代码评测证据，生成能定位问题的报告。

平台负责统一评测流程和证据结构，具体业务的成功标准由任务和评测器定义。不能从任意任务描述自动可靠地推导完整验收条件，也不能从一个 Agent 入口自动观察所有内部行为。

### 1.1 用户明确确认的决定

1. 后端采用 Java 相关技术，确定 Java 21、Spring Boot 3。
2. 前端采用 React、TypeScript、Vite。
3. 数据使用 PostgreSQL、Redis 和 S3 兼容对象存储，初期 Docker Compose 部署。
4. 每个用户一个私人工作空间，仅本人使用，无成员邀请和协作权限。
5. 用户可新增、修改、删除自己的任务集、评测体系等资源。
6. 平台提供基础示例任务集、规则评测体系和大模型评分模板。
7. 支持规则、大模型评审、用户自定义 Python 评测代码；自定义 Java 评测代码不进入第一版。
8. 提供 Java、Python 两套 SDK，采集模型/工具调用、中间结果、异常和最终结果。
9. 平台发起评测；SDK Worker 主动领取任务，在用户环境执行，无需开放入站端口。
10. 平台统一提供评审模型，每个用户使用累计额度，不周期性重置。
11. 轨迹采集、存储、查询、展示、评分全部自建，不使用 Langfuse。
12. 开发遵循 TDD，模块边界和类型定义清晰。

### 1.2 本设计提出的默认方案

以下是设计者的建议，待用户随全文确认，并非用户此前明确指定：

- 后端采用模块化单体、API 与后台执行进程分开部署，不先拆微服务。
- Java SDK 第一版基线 Java 21；Python SDK 和评测镜像基线 Python 3.11。
- SDK 使用 HTTPS JSON 协议、长轮询、租约、心跳和批量事件上传；第一版不使用 WebSocket。
- 第一版一次 Trial 对应一次 Agent 入口调用；入口内部可多步执行，不提供平台自动模拟多轮用户。
- Python 评测函数一次性接收评测上下文、返回结构化结果；不支持自由安装依赖或直接访问网络。
- 规则使用受限声明式操作符，不执行用户提供的 Java 表达式或任意脚本。
- 初始模型额度为 0，管理员人工发放/追加；无支付和充值渠道。
- Agent 执行额度、Python 执行资源限制与模型评审额度分开管理。
- 第一版不提供独立的实验费用上限；实验费用为展示估算，硬性消费约束是工作空间累计余额和单次模型调用限制。
- 第一版仅做用户自己的质量回归，不提供跨用户公共排行榜。
- 不强制建立统一总分；主要展示任务通过状态、结果质量、过程质量、效率和证据覆盖。

## 2. 目标、非目标和失败条件

### 2.1 第一版目标

- 用户能接入任意可在 Java/Python 入口函数中调用的自定义 Agent。
- 基础示例可端到端跑通：注册 Worker、领取任务、上报嵌套轨迹、执行三类评测器、展示证据。
- 任务集、评测器、评测套件可维护，已启动实验不受后续编辑影响。
- 报告能定位到案例、尝试、评测项和具体事件，区分质量失败、执行故障及评分故障。
- 同一任务集可对同一工作空间的不同 Agent/配置版本进行比较。
- 并发运行、重复上传、进程重启和余额不足不导致重复执行承诺或重复扣款。
- Python 代码与平台服务隔离，租户数据和模型密钥不泄露。

### 2.2 第一版非目标

- 不托管用户 Agent 代码，不提供 Agent 开发框架或训练平台。
- 不要求用户创建 Agent HTTP 服务，不采集模型隐藏思维过程。
- 不支持团队协作、跨租户共享、公共排行榜、线上生产持续监测。
- 不提供完整浏览器/桌面沙箱，不统一接管用户的业务工具和数据库。
- 不自动推断所有业务指标，不承诺所有自定义框架零埋点。
- 不做付费计费、多区域高可用、复杂工作流编排或任意 Python 包在线安装。

### 2.3 不能接受的失败

- 把“缺少轨迹”误判为“未调用工具”，把 Agent 自报成功当成独立证明。
- 已运行实验因修改任务或评分标准发生无记录的变化。
- 重复结果或重试导致模型配额重复扣减，或未知模型消耗被直接释放。
- 以过程高分抵消关键业务目标失败或硬性约束违规。
- Worker 丢失后自动重做有副作用的任务，导致实际业务重复操作。
- 用户评测代码可访问模型密钥、其他用户文件、平台数据库或容器宿主控制接口。

## 3. 技术栈和部署边界

| 部分 | 技术选择 | 用途 |
| --- | --- | --- |
| Java 构建 | Maven 多模块 | 后端、协议模型、Java SDK 和契约测试 |
| API/后台服务 | Java 21、Spring Boot 3、Spring MVC、Bean Validation | 管理、调度、采集、评测编排 |
| 鉴权 | Spring Security、服务端 Session | 浏览器登录；Worker 使用独立凭证 |
| 数据访问 | Spring JDBC / JdbcClient、Flyway | 显式控制租约和额度事务 |
| 数据库 | PostgreSQL，结构化字段 + JSONB | 权威任务状态、证据索引、评分和账本 |
| 缓存 | Redis | 限流和短期缓存，不作为余额或任务状态权威来源 |
| 文件存储 | S3 API，Compose 开发环境使用兼容实现 | 大事件载荷、附件、产物 |
| 前端 | React、TypeScript、Vite、React Router、TanStack Query | 工作空间管理、进度和报告 |
| 样式 | Tailwind CSS，内联 SVG 图标 | 管理端界面；不引入 lucide-react |
| Java SDK | Java 21、JDK HTTP Client | Worker 和埋点接口，避免强依赖 Spring |
| Python SDK | Python 3.11、httpx、contextvars | Worker、装饰器和上下文传播 |
| Python 评分执行 | Python 3.11、独立 Runner、隔离沙箱 | 运行用户自定义代码 |
| API 约定 | OpenAPI 3.1、JSON Schema、JSON UTF-8 | Java/Python 共享协议和校验 |
| 后端测试 | JUnit 5、AssertJ、Testcontainers、WireMock | 事务、协议、模型代理和隔离测试 |
| SDK/前端测试 | pytest；Vitest、React Testing Library、Playwright | 双 SDK 契约和关键用户流程 |
| 部署 | Linux + Docker Compose，反向代理终止 HTTPS | 第一版单机部署 |

Spring Boot 3 使用 3.5 系列作为候选；所有组件的具体补丁版本、兼容性和维护状态在实施计划阶段核对并锁定，不使用浮动 latest。若候选版本届时不满足维护要求，记录技术决策并调整，不悄悄升级到不同主版本。

进程边界：

- `web`：静态前端和反向代理。
- `platform-api`：登录、管理 API、SDK 控制/采集入口。
- `platform-worker`：数据库任务扫描、租约回收、评分编排、模型调用、额度对账。与 API 共用模块化代码库。
- `sandbox-runner`：受限内部接口，创建和销毁 Python 沙箱。外部不可访问。
- PostgreSQL、Redis、对象存储：独立基础组件。
- 用户侧 Java/Python Worker：部署在用户自己的运行环境，不在平台 Compose 内。

不先引入 Kafka、RabbitMQ、Elasticsearch、ClickHouse。第一版用数据库作业表、事务领取和游标查询；若测量显示瓶颈，再替换存储或队列实现。

## 4. 领域模块与资源关系

| 模块 | 职责 |
| --- | --- |
| identity | 用户、单人工作空间、Session、Worker 凭证 |
| catalog | 系统模板、任务集和案例、版本、复制/归档 |
| agents | Agent 注册、Worker 在线状态、能力和版本 |
| experiments | 实验快照、Trial、调度、租约和取消 |
| telemetry | Span/事件/附件采集、去重、索引和完整性 |
| evaluation | 三类评测器、套件、评测作业、重评 |
| model-gateway | 平台评审模型配置、调用记录、用量和供应商适配 |
| quota | 累计额度、预留、结算、释放、人工调整 |
| reporting | 聚合、版本对比、失败证据和导出 |
| administration | 用户、额度、模型配置、审计和资源上限 |

资源主链：User → Workspace → Agent / Dataset / Evaluator / Suite / Experiment。

- Dataset 下包含 TaskCase；一次发布产生不可变 DatasetVersion。
- Evaluator 有版本；Suite 将若干 EvaluatorVersion 组合为评分体系。
- Experiment 固定 Agent 声明版本/配置标识、DatasetVersion、SuiteVersion、重复次数、执行限制和费用估算。
- Trial 对应一个案例的一次独立尝试，有独立 Trace 和执行租约。
- EvaluationRun 是对一个 Trial 证据快照的一轮评分；同一运行可重评但不覆盖旧评分。

Agent 在用户环境，平台记录的是用户声明的版本和配置摘要，无法独立保证远程代码未变；报告标注此限制。

## 5. 私人空间、模板与版本

每个普通用户自动获得唯一工作空间。所有业务查询和写入都从登录身份或 Worker 凭证推导 workspace_id，不信任客户端提交的归属字段。

系统模板只读，用户复制为私有资源再修改。默认示例包括：结构化回答、工具查询、模拟订单操作、带引用的简单总结；附带可运行的演示 Agent 和预期证据。示例不操作真实业务系统。

任务案例结构：instruction、input（JSON）、expected（可选 JSON）、reference_assets、tags、timeout、证据要求。用户 Agent 只获得执行所需输入和明确约束，不获得隐藏参考答案、评分提示词或评测器代码。expected 并非所有任务必填。

评测器结构：type、name、输入范围、指标定义、required_evidence、配置/代码/提示词、通过条件、版本。

套件条目绑定评测器版本、`dimension`（outcome/process）、`required_to_pass`、可选权重。

- 编辑产生草稿，发布产生不可变版本；启动实验只接受已发布版本。
- 启动事务固定全部版本与本次配置，展开 Trial。禁止异步读“最新版本”执行历史实验。
- 已被实验引用的资源删除时归档，不物理删除被引用版本。
- 删除整个实验视为显式删除其历史记录；先逻辑删除、拒绝新增任务，清理执行完后异步清理附件和证据。清理作业按租户校验，不能影响额度审计账本。
- 默认证据保留至用户删除实验，管理员可设置容量上限；第一版不做无提示的自动过期删除。

## 6. 平台到用户 Worker 的执行链路

### 6.1 注册与发现

1. 用户在空间创建 Agent，获得 agent_id，选择 worker 凭证的 Agent 范围。
2. 凭证只展示一次，服务端保存摘要，可撤销；权限仅限登记、领取、心跳和本范围运行的证据上传。
3. SDK 配置平台地址、凭证、agent_id 和本地处理函数。启动后登记 Worker：instance_id、sdk_language/version、protocol_version、Agent 版本、并发能力和支持的埋点类型。
4. 默认单 Worker 并发 1。用户显式配置并发安全后才能增加；平台默认工作空间最多 2 个 Agent Trial 同时执行，管理员可调。
5. 平台按 Agent 和版本指纹匹配可用 Worker；不满足协议或任务能力时，在启动前提示。

### 6.2 长轮询与租约

- Worker 调用 claim 接口，服务端最长等待 20 秒；无任务返回空结果。断线指数退避并加随机抖动。
- 数据库事务按到期时间选择 Trial，使用行锁/`SKIP LOCKED` 领取，生成 attempt_token（fencing token）和租约。
- 初始租约 60 秒，每 15 秒心跳续租。只有匹配当前 token 的 Worker 可更新权威执行状态。
- SDK 开始处理前发送 started 确认；本地维护运行注册表，重复收到同一 trial_id/token 不再次调用处理函数。
- 全部控制和上传接口使用幂等键。网络协议允许至少一次送达，不声称 exactly-once 执行。
- 任务执行期限来自快照，不能通过心跳无限延期。

### 6.3 断线、失败与副作用

- Worker 短暂断线允许在租约内继续；结果上传可重试。
- 租约过期或 Worker 丢失，Trial 标记 EXECUTION_UNKNOWN，并拒绝旧 token 提交为权威结果。
- 默认不重新分配或自动重做未知 Trial，避免重复退款等副作用。用户确认后创建新的 Trial，并保留重跑关系。
- 自动重做仅允许任务显式声明可安全重试，第一版默认关闭。
- 用户环境应使用测试数据；平台无法撤回用户代码已经完成的外部动作。

### 6.4 取消

平台取消实验后停止新任务；心跳响应携带取消指令。SDK 给处理函数传 cancellation token 和 deadline。执行模式支持协作取消，无法保证任意线程/本地库可被安全中断。

超时或取消后的晚到证据可在宽限窗口内留作审计，但不把 Trial 改回成功。报告显示停止请求与实际停止是否已确认。

### 6.5 API 边界（示意资源，不冻结最终字段名）

| API | 作用 |
| --- | --- |
| POST /api/v1/workers/register | 注册实例、版本、能力 |
| POST /api/v1/workers/{id}/claim | 长轮询领取任务 |
| POST /api/v1/trials/{id}/started | 确认执行开始 |
| POST /api/v1/trials/{id}/heartbeat | 续租、获取取消状态 |
| POST /api/v1/trials/{id}/events:batch | 批量上报轨迹 |
| POST /api/v1/trials/{id}/artifacts:init | 申请当前运行的受限附件上传地址 |
| POST /api/v1/trials/{id}/complete | 声明终态、最终结果及事件清单 |

trial_id 是业务关联标识，trace_id/span_id 表达观测关系，attempt_token 控制运行归属；三者不能混为一个字段。

## 7. Java/Python SDK 设计

两套 SDK 共用 JSON Schema、协议版本和契约用例，分别保持 Java/Python 自然的编程风格。

### 7.1 Worker 能力

- 初始化、处理函数注册、任务领取、心跳、取消、最终结果和附件上传。
- 用 TrialContext 提供 instruction、input、deadline、trial_id、cancellation、telemetry；不得包含用户隐藏 expected 或评测密钥。
- Worker 凭证从环境/安全配置读取，任务内容不能覆盖平台地址或凭证。
- 默认每个 Trial 独立上下文；不得复用上一案例的对话、记忆和缓存状态，除非任务明确声明并在报告记录。实际 Agent 内部状态重置由用户处理函数负责，SDK 提供清理钩子。

### 7.2 埋点能力

- Java 提供显式 span 包装 API；Spring AOP 注解作为可选集成模块，不强制依赖 Spring，不承诺任意 Java 方法都能被注解自动拦截。
- Python 提供装饰器、上下文管理器和显式事件 API。
- 支持 agent、model、tool、retrieval、custom Span，记录输入/输出、异常和可得 usage。
- 基础版本保证显式埋点正确；不把所有 Agent 框架的自动埋点列为首版验收要求。
- 异步/线程池提供显式上下文捕获与恢复接口；不声称跨进程自动传播。
- 支持字段脱敏、禁采字段、载荷截断和附件引用；未采集/被截断状态写入元数据。
- 记录可观察的模型请求和响应，不索取或伪造模型隐藏思维过程。

### 7.3 可靠性

SDK 在 Span 完成时排队，按大小或时间批量发送。默认批次不超过 100 个事件、请求不超过 1 MiB、单个内联载荷不超过 64 KiB，大载荷转附件。服务端同步执行大小校验。

有界内存缓冲和可配置本地有界 spool，默认仅存已脱敏事件，建议上限 100 MiB。事件被服务端持久化确认后再删除本地副本；离线超过容量记录 dropped_count。关闭、Trial 完成前尝试 flush，有超时上限。采集失败不改变 Agent 本身的业务输出，但该 Trial 的证据完整性可能不足。

平台只接受凭证所属 Agent 的有效运行事件；本地 spool 中过期 token 的事件仅可在迟到窗口进入审计，不恢复任务归属。

## 8. 轨迹协议与存储

### 8.1 标准事件信封

```json
{
  "schema_version": "1.0",
  "event_id": "uuid",
  "trial_id": "uuid",
  "trace_id": "32-hex",
  "span_id": "16-hex",
  "parent_span_id": null,
  "event_type": "span.started",
  "span_kind": "tool",
  "name": "query_order",
  "sequence": 12,
  "occurred_at": "2026-10-25T08:00:00Z",
  "payload": {"input": {"order_id": "A"}},
  "capture": {"redacted": false, "truncated": false}
}
```

事件类型至少包括 span.started、span.completed、span.failed、log、artifact.created。完成事件包含 duration_ms、输出/附件引用和可选用量。duration 使用本地单调时钟计算；服务端记录 received_at。跨机器时间不用于判定关键因果关系。

事件来源由服务端写为 worker_reported、platform_observed 或 verifier_observed；不能由用户把自己的上报声明成平台独立证明。trace_id 在 Trial 创建时分配，Worker 继承；第一版一 Trial 一根 Trace。

- 原始事件追加写，按 workspace_id/event_id 唯一去重；同 ID 不同内容报冲突。
- 投影视图按 span_id 构建调用树，允许 completed 先于 started 到达，不能因乱序丢弃事件。
- sequence 为一次执行内由 SDK 分配的连续序号；排序便于显示，因果判断仍依据父子关系、工具语义和显式关联。
- 每个事件最多属于一个 Trial；服务端验证 token、归属和引用。
- PostgreSQL 保存事件元数据、内联小载荷、Span 投影和评分关联；大载荷/附件放对象存储，保存 hash、字节数、媒体类型。
- 对象上传成功且校验完成后才标记 artifact AVAILABLE；未完成的附件不能作为有效证据。
- Trace 查询按 Trial 游标分页，深层 Span 逐步加载，不一次加载全空间轨迹。

### 8.2 完成和完整性

Worker flush 后 complete 请求携带 final_sequence、event_count、dropped_count、输出和附件清单。服务端把执行终态与轨迹收齐分开处理：

- completeness 为 PENDING、COMPLETE、PARTIAL、NONE。
- 默认宽限 30 秒，核对序号缺口、根 Span、父子闭合和必需附件。
- 收齐或窗口结束后固定 EvidenceSnapshot，记录哈希和截断/缺失清单，触发评分。
- COMPLETE 仅表示满足协议完整性检查，不证明用户没有漏埋点或伪造记录。
- 后续迟到数据保留但不静默改变已评结果。默认重评继续使用原快照；用户明确选择“纳入迟到证据”时先创建新的 EvidenceSnapshot，再新建 EvaluationRun。新旧快照和结果均保留，报告标记证据范围变化。
- 必需轨迹不足时对应指标返回 INSUFFICIENT_EVIDENCE；仅依赖最终输出的评分仍可继续。

## 9. 评测体系

### 9.1 共同输入与输出

评测器获得只读上下文：隐藏任务参考、公开任务输入、最终输出、相关 Span/事件、已脱敏产物、可选独立环境快照。上下文按 required_evidence 最小化传递。

```json
{
  "criterion_id": "refund_amount",
  "status": "SCORED",
  "score": 1.0,
  "passed": true,
  "reason": "记录中的退款金额与参考值一致",
  "evidence_refs": ["event:uuid"],
  "evaluator_version": "uuid"
}
```

- status 为 SCORED、INSUFFICIENT_EVIDENCE、ERROR、SKIPPED、WAITING_QUOTA；未评分状态不得伪造 score=0。
- score 统一为 [0,1]，拒绝 NaN 和越界；passed 由固定阈值或硬断言推导，不接受与配置矛盾的输出。
- evidence_refs 必须解析到当前证据快照内对象；无依据的模型引用不能通过校验。
- 指标类别和执行方式独立：过程/结果指标均可使用规则、LLM 或 Python。

### 9.2 规则评测器

第一版提供：存在/缺失、精确匹配、数值范围/比较、JSON Schema、工具选择集合、工具参数检查、必要先后关系、次数/耗时阈值。

规则选择器使用受限 JSON Pointer 和 Span 条件，不支持 eval、任意 SQL、SpEL。对多个匹配对象必须显式指定 any/all/count 或聚合；结果为空默认 INSUFFICIENT_EVIDENCE。只有证据完整且配置明确要求“不存在”时才作不存在判断。并发调用的顺序按因果关系检查，不能只比上传时间。

### 9.3 大模型评测器

平台管理供应商、模型白名单、加密凭证、价格版本、上下文/输出限制；用户选择允许的模型和评分模板，不接触密钥。第一版实现 OpenAI-compatible HTTP 协议适配，具体供应商由部署管理员配置，并保留其他供应商接口。

模板包含单项指标、0～4 或明确分档 rubric、参考资料、缺证据处理和 JSON 输出 Schema。分数映射到 [0,1]，维度分开评分，不直接使用一个随意总分。

- 待评文本/工具结果作为不可信数据，不执行其中要求改变评分标准的指令。
- Judge 无业务写工具，不以自己生成的“已查证”替代外部证据。
- 超长轨迹选取或截断必须记录；被截断的关键证据影响可评分状态，不能无提示摘要后当作完整证据。
- 保存模型版本标识、提示词版本、参数、实际输入摘要、原始响应和用量；低随机性不代表可完全复现。
- 结构不合法或无效引用可有限重试一次，每次模型请求单独计量；重复解析同一响应不重复计量。
- 首版提供人工逐项复核和评分器校准样本；人工结论作为附加 adjudication，不覆盖原始自动结果。不把模型自报置信度当作准确率。

### 9.4 Python 自定义评测器

用户在工作空间维护单文件 Python 代码，入口概念为 `evaluate(context) -> result`；代码有草稿、受限试运行和不可变发布版本。每个评测器第一版返回一个指标，多指标拆成多个评测器，便于版本与通过规则管理。

- 函数通过上下文 JSON 和只读附件目录获取数据，输出遵循统一结果 Schema。
- 默认允许标准库和平台预装的少量白名单依赖，发布时记录镜像 digest；不允许运行时 pip 安装。
- 默认超时 30 秒、1 vCPU、256 MiB、进程数 32、临时目录 64 MiB；管理员配置上下限。
- stderr/stdout 有大小上限；输出协议与普通日志分离，防止日志污染结构化结果。
- 不带数据库、对象存储或模型密钥，不挂载其他实验数据，不允许网络和 Docker socket。
- 用户自定义代码不得借执行环境免费调用平台评审模型；需要模型评分时使用独立 LLM 类型，便于审计和额度控制。
- 语法错误、超时、进程异常、无效输出分别记录为评分器 ERROR，不作为 Agent 得零分。

Runner 在专用 Linux 执行节点运行，API 不持有 Docker socket。运行第三方 Python 代码使用 gVisor 等强化容器边界、非 root、只读根文件系统、drop capabilities、资源和网络限制。没有强化隔离运行时则禁用用户代码执行，不回退为主服务内 exec。Compose 用于组织服务，不被当作代码安全沙箱；Windows 开发通过 Linux 虚拟机/WSL2 提供符合条件的 Runner。

### 9.5 结果与聚合

- Outcome 与 Process 分开显示，效率为独立指标；硬性违规不可被加权抵消。
- 任务通过要求所有 required_to_pass 条目均 SCORED 且 passed=true，并满足套件声明的关键结果条件。
- 任意必需项明确失败 → FAIL；没有明确失败但必需项缺证据/出错/待额度 → UNDETERMINED；全部必需项通过 → PASS。
- 可选项缺失不改变硬通过结论，但减少覆盖率。各维度按已配置权重计算；缺失项不默认为零，显示已评分权重/总权重及“部分评分”。
- Experiment 显示计划/已执行/可判定数量；通过率同时提供 PASS/全部计划尝试和 PASS/可判定尝试，明确分母，避免忽略故障抬高结果。
- 相同案例重复执行作为独立 Trial，统计波动；小样本不声称精确排名。
- 新旧版本比较必须显示任务、套件、模型和证据范围是否一致；不一致时标为不可直接比较。

## 10. 状态机与重评

执行状态与评分状态分别建模，避免“Agent 已结束但评分等待额度”被认为 Agent 仍在运行。

- Trial execution：QUEUED → LEASED → RUNNING → SUCCEEDED / FAILED / TIMED_OUT / CANCELLED / EXECUTION_UNKNOWN。
- LEASED 超过启动期限同样进入 UNKNOWN；取消前未分配任务可直接 CANCELLED。
- Trace：PENDING → COMPLETE / PARTIAL / NONE。
- EvaluationRun：PENDING → RUNNING → COMPLETED / PARTIAL / WAITING_QUOTA / FAILED / CANCELLED。
- 单项评测作业按 evaluator/version + EvidenceSnapshot + EvaluationRun 唯一，重发不会创建重复权威评分。
- Experiment progress 为执行进度和评分进度的聚合，不用一个 SUCCESS 表达所有情况。

每次重评在启动前固定一个 EvidenceSnapshot，使用指定评分版本创建新 EvaluationRun，会再次消耗实际模型调用额度。默认复用原快照；纳入迟到证据需明确创建新快照，不能向旧快照追加。重跑会调用 Agent、创建新 Trial，可能产生业务副作用。重评与重跑在 API 和用户操作上明确区分。

## 11. 平台模型累计额度

额度仅计平台 Judge 模型，不承担用户 Agent 自身的模型费用。采用整数 credit 记账，不用浮点；每个价格版本定义按输入/输出 Token 折算的比例和舍入规则，原始用量一并保留。

### 11.1 账本

- 钱包字段：granted、consumed、reserved；available = granted - consumed - reserved。
- 追加账目：GRANT、RESERVE、SETTLE、RELEASE、ADJUST，每笔有唯一业务键、操作者和原因。
- 管理员发放/追加，初始为 0；无月度自动重置，无自助支付。
- 所有余额更新和对应账目写入处于同一 PostgreSQL 事务；行锁或等效原子条件保证 available 非负。
- 管理员调减不能低于 consumed + reserved；退款/纠错使用补偿账目，不能删除原交易。

### 11.2 调用流程

1. 实验启动前按候选模型、案例数量和 Token 配置展示费用估算，明确不是最终费用或实验硬上限。第一版不另设实验级额度池；重试、重评和重跑产生的 Judge 请求都使用同一工作空间余额，各有独立调用账目。
2. 模型调用前，在获得可用并发槽后按实际请求输入和 max_output_tokens 预留上界；选择本地可准确计量的模型/适配器，未知 tokenizer 用保守上界或拒绝无上界调用。
3. 余额不足不调用供应商，评测项进入 WAITING_QUOTA；独立规则和 Python 评分继续。
4. 成功后按供应商可信实际 usage 和冻结价格结算并释放差额；相同 call_id 只结算一次。
5. 明确未发送的请求可释放预留；已发送后超时、断线、取消的调用进入 PENDING_RECONCILIATION，保留预留，等待供应商对账或管理员确认，不能直接退款再自动重试。
6. 实际费用超过保守预留属于计量异常，先记录待对账费用并停止该适配器新调用，不能静默漏记或让可用额度变负。
7. 管理员追加后，仍有效的 WAITING_QUOTA 作业可由调度器幂等恢复；取消的实验不恢复。

若模型成功返回但评分 JSON 无效，模型成本仍计入消耗，报告区分“调用费用”和“有效评分”。供应商费用已发生后取消实验也不回滚已消耗额度。模型并发、单次 Token、工作空间总执行次数/存储和 Python 运行秒数另有资源上限，不能用零 Judge 消耗无限占用平台资源。

## 12. 存储模型与关键约束

核心表（名称为领域建议，实现者可调整命名）：

- users、workspaces、sessions、worker_credentials。
- agents、worker_instances。
- datasets、task_cases、dataset_versions、case_versions。
- evaluators、evaluator_versions、suites、suite_versions、suite_items。
- experiments、experiment_snapshots、trials、trial_leases。
- trace_events、spans、artifacts、evidence_snapshots。
- evaluation_runs、evaluation_jobs、criterion_results、human_reviews。
- model_profiles、model_price_versions、model_calls。
- quota_wallets、quota_ledger、quota_reservations、audit_events。

约束：

- 所有租户资源带 workspace_id；子资源用复合外键或等价数据库约束避免跨空间关联。
- immutable version、快照和结果不可原地编辑；新版本/重评追加。
- events 唯一键 workspace_id/event_id；作业和账本业务幂等键均有唯一约束。
- 状态迁移使用版本号/CAS 或行锁；只允许预定义迁移。
- 调度索引覆盖 job_state + next_run_at，轨迹索引覆盖 workspace_id + trial_id + sequence。
- 作业表即持久队列；业务状态与作业创建在同一事务。进程崩溃后可扫描恢复，Redis 消息丢失不影响权威状态。
- 附件不按客户端传入的任意路径读取，预签名上传和下载限制对象、租户、有效期及大小；服务端验证完成后才加入证据。

## 13. 用户与管理员能力

第一版普通用户支持：

- 登录、查看唯一私人工作空间；管理员创建账户并分配初始凭证，用户首次登录修改密码，暂不做公共注册、邮件验证和邮件找回。
- Agent 和 Worker 登记、在线状态、凭证创建与撤销。
- 基础模板复制、JSON/JSONL 任务集导入、案例编辑和版本发布。
- 三类评测器维护、样例试运行、套件组合和版本发布。
- 发起/取消实验、查看运行进度、重复尝试和显式重跑。
- 轨迹查询、结果/过程分项报告、证据定位、版本比较、JSON 导出和人工复核。
- 查看累计额度、预留和消耗明细、待额度评测项。

管理员支持：账户管理、模型配置与连通性测试、额度发放/补偿、资源限制、系统模板发布、模型对账和审计。

认证默认服务端 Session，Cookie HttpOnly/Secure/SameSite，状态变更启用 CSRF 防护；密码使用自适应哈希。Worker 使用独立可撤销凭证，不复用用户密码或管理员 Session。管理员模型密钥使用部署密钥加密，前端永不返回明文，日志不输出密钥。

## 14. 信任、安全和容量边界

- SDK 用户上报的数据用于诊断和自评，不能自动提升为独立环境证明。
- Python 评测器只能检查被提供的证据，无法因为能执行代码就验证远程真实业务状态。
- 首版独立验证依赖平台内置演示环境；真实业务如需强验证，后续提供经过授权的 verifier 适配器，不在首版开放任意 URL/网络访问。
- 平台不向 Worker 下发可执行的用户评分代码；Worker 只执行本地用户已注册的 Agent 处理函数。
- 普通任务数据不得改变 SDK 配置或触发 shell 命令；Agent 自身处理输入的安全性仍由用户负责。
- 渲染模型输出、日志和附件名称时转义，不直接执行 HTML；不自动抓取用户任意 URL。
- 对用户代码、Trace、附件、任务、模型输入分别做尺寸和速率限制；超过限制显示明确状态。
- 操作审计包括凭证、版本发布、实验启动/删除、管理员额度调整与模型配置，不记录秘密原文。

初始工程容量目标（建议，待实际压测验证）：单节点 50 个在线 Worker、全平台 20 个运行 Trial、每 Trial 不超过 10,000 事件和 100 MiB 附件。容量超限时明确拒绝或排队，不静默丢弃。限值通过管理员配置，可按工作空间收紧。

## 15. 测试与验收

按行为先写失败测试再实现；不为单纯文案变更编写镜像测试。集成测试使用真实 PostgreSQL 事务，模型调用使用可控 stub，不能以 mock 内存余额验证并发正确性。

### 15.1 核心验收场景

1. Java 和 Python 演示 Agent 分别领取平台任务，执行根调用和嵌套工具/模型调用，输出同协议的轨迹。
2. 用户复制平台任务和套件、发布版本、执行并查看三类评测结果。
3. 两用户无法读取、引用、删除或上报到彼此空间，附件和游标接口同样隔离。
4. 改动或归档任务/评测器后，历史实验仍读取原版本和证据。
5. 事件重复、乱序、缺失、截断、迟到均有明确行为和完整性显示。
6. Worker 断线、租约过期、重复领取、旧 token 结果不会触发默认重复副作用执行。
7. 平台重启后作业恢复；取消后不再发新任务，不能把晚到成功改回权威成功。
8. 并发模型调用不透支；重复结算不重复扣款；超时待对账不直接释放预留。
9. 余额不足时规则/Python 可继续，LLM 等待；追加后仅恢复未取消的任务。
10. Python 代码访问网络、其他文件、Fork 耗尽、超时和过量输出均被沙箱限制；无强化隔离时功能关闭。
11. 验收规则能判真/判假，LLM 返回坏 JSON/无效证据引用不被接受；评分失败不当 Agent 零分。
12. 报告分开展示执行状态、证据完整性、评分状态、通过状态，明确效率和成功率分母。

### 15.2 评分器有效性

为每个内置评测器提供通过、失败、缺证据和边界样本。建立少量人工标注的校准集，记录模型评审误判/漏判和人工分歧，不用未经验证的 LLM 分数宣称客观准确率。评测器升级必须在相同证据上回归，保留旧版本结果。

## 16. 文档边界与后续工作

这是一份设计规格，不是实现清单，也不是产品已实现的声明。当前不编写产品代码、脚手架或安装依赖。

用户批准全文后，下一步编写分阶段 TDD 实施计划：先固定协议和租户模型，再打通双 SDK 与持久调度，随后加入轨迹、三类评测器、额度、报告和隔离部署。具体依赖补丁、DTO 命名、索引调优、UI 样式和文件拆分由实施者决定，不改变已确认产品边界。

待全文确认的主要设计者默认项：SDK 语言基线、单入口调用范围、管理员建号和额度发放、Python 无网络/白名单依赖、强化沙箱要求、模型异常对账、容量上限及数据保留策略。

### 16.1 独立可实施性审阅

初稿已由独立软件工程审阅者按 builder-check 要求检查。审阅提出两处需要明确的约定，均由设计者作出默认决定：第一版只有工作空间硬额度，实验展示费用估算；重评默认使用原证据快照，纳入迟到证据必须显式创建新快照。上述决定已写入相关章节，并随全文等待用户确认。
