# Agent Evaluation Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现平台调度、Java/Python SDK 接入、自建轨迹和三类评测器组成的私人 Agent 质量评测平台。

**Architecture:** Java 模块化单体分别启动 API 和后台 Worker，PostgreSQL 是业务状态、任务队列与额度的权威来源。用户 SDK Worker 主动领取任务并上报证据，评测服务根据不可变快照执行规则、平台模型评审或隔离 Python 代码。前端提供私有资源管理、进度、报告和管理员能力。

**Tech Stack:** Java 21、Spring Boot 3、Maven、PostgreSQL、Redis、S3、React/TypeScript/Vite、Java/Python SDK、Python 3.11、Linux Compose、gVisor Runner。

**Spec:** [2026-10-25-agent-evaluation-platform-design.md](../specs/2026-10-25-agent-evaluation-platform-design.md)。执行前同时阅读规格和计划。

## Global Constraints

- 用户明确确认的产品边界以规格 §1.1 为准；本计划固定实现路径，不静默改变技术主版本。
- Java SDK 第一版基线 Java 21；Python SDK 和评测镜像基线 Python 3.11。
- 每个用户一个私人工作空间，仅本人使用，无成员邀请和协作权限。
- SDK 使用 HTTPS JSON 协议、长轮询、租约、心跳和批量事件上传；第一版不使用 WebSocket。
- 第一版一次 Trial 对应一次 Agent 入口调用；入口内部可多步执行，不提供平台自动模拟多轮用户。
- 初始模型额度为 0，管理员人工发放/追加；无支付和充值渠道。
- 第一版不提供独立的实验费用上限；实验费用为展示估算，硬性消费约束是工作空间累计余额和单次模型调用限制。
- 轨迹采集、存储、查询、展示、评分全部自建，不使用 Langfuse。
- 长轮询最长等待 20 秒；初始租约 60 秒，每 15 秒心跳续租；证据收集宽限 30 秒。
- SDK 批次不超过 100 个事件、请求不超过 1 MiB、单个内联载荷不超过 64 KiB；本地已脱敏 spool 默认上限 100 MiB。
- Worker 默认并发 1、工作空间同时执行 Trial 默认上限 2；默认不自动重跑未知执行。
- Python 默认超时 30 秒、1 vCPU、256 MiB、进程数 32、临时目录 64 MiB，无网络、无平台密钥。
- 没有强化隔离运行时则禁用用户代码执行，不回退为主服务内 exec。
- 初始工程容量目标：50 个在线 Worker、20 个运行 Trial、每 Trial 最多 10,000 事件和 100 MiB 附件；必须实测后才宣称达到。
- 按行为先写失败测试再实现；数据库一致性使用真实 PostgreSQL 集成测试；模型使用可控 stub；不为文案写镜像测试。
- 图标使用内联 SVG；CSS 的 @import 位于其他 Tailwind 指令之前。

## Review Focus

以下是从规格推导的额外输入/失败场景，已绑定到任务中的测试，不作为新增产品功能：

1. 平台在发送 claim 响应前崩溃：租约不能让两个 Worker 获得同一执行；失效后不自动重做。见任务 5。
2. 同一 event_id 使用不同载荷重放：拒绝冲突且不能把已存内容覆盖。见任务 6。
3. 模型请求被供应商接受后平台崩溃：重启后保留预留并进入对账，不盲目重发。见任务 14。
4. 用户删除实验时 Worker 仍在上传：不得复活资源、孤儿附件需可清理，账本不被删除。见任务 19。
5. 多个同名工具 Span 包含重试及并发：参数聚合和先后检查不得依靠上传顺序或错误配对。见任务 10。

---

## 1. 当前状态与交付阶段

截至 2026-10-25，仓库只有 README、忽略规则和设计文档，无产品代码。本文件中的测试命令是将来的执行步骤，尚未运行，也不代表测试已经通过。

| 阶段 | 任务 | 独立验收结果 |
| --- | --- | --- |
| A：基础与执行协议 | 1～5 | 用户登录、私有版本资源、平台创建实验，模拟 Worker 安全领取 |
| B：证据与基础评测 | 6～11 | 双 SDK 运行示例 Agent，轨迹完整性可追踪，规则评分可生成 |
| C：扩展评测 | 12～16 | Python 隔离执行、模型网关、累计额度及三类评分统一编排 |
| D：产品交付 | 17～20 | 管理界面、证据报告、删除审计、部署和端到端验收 |

阶段依赖为 A → B → C → D，不跳过安全与额度阶段对外开放付费模型或自定义代码。详细任务按顺序执行；后期可调整执行顺序，但必须保持 Interfaces 依赖。

## 2. 文件布局与职责

计划创建的目录，不在本次规划阶段创建产品脚手架：

```text
pom.xml                         Maven reactor / BOM / compiler / tests
mvnw, mvnw.cmd, .mvn/           固定 Maven wrapper
contracts/
  openapi/platform-v1.yaml       管理及 Worker API
  schemas/                      JSON Schema 2020-12
  fixtures/v1/                  双语言合法/非法消息与场景
platform/
  protocol/                     纯 Java DTO / 枚举 / 协议验证，无 Spring 依赖
  core/                         按 identity/catalog/agents/experiments/
                                telemetry/evaluation/model/quota/reporting 分包
  infrastructure/               JDBC/Flyway/S3/Redis/模型和 Runner 客户端
  api/                          Spring MVC、Session 和 Worker 安全过滤器
  worker/                       持久作业调度、证据封存、评测及清理
sdks/
  java/                         Java Worker / telemetry；可选 spring 子模块
  python/                       agent_eval 包、同步及异步埋点
runner/
  agent_eval_runner/            内部鉴权、gVisor 调用、资源与协议校验
  sandbox/                      固定镜像和 evaluate(context) 入口
web/
  src/api/                      OpenAPI 生成客户端及查询封装
  src/features/                 auth/catalog/agents/experiments/reports/admin
examples/
  java-order-agent/              无外部业务副作用的演示 Agent
  python-order-agent/            与 Java 同契约的演示 Agent
deploy/                         Compose、反向代理、Runner 与备份配置
e2e/                            跨进程验收场景及浏览器测试
docs/                           设计、计划、协议和运行指南
.github/workflows/              JVM / Python / web / 隔离验收 CI
```

Java 包前缀为 `io.agenteval`。下文 `C/` 表示 `platform/core/src/main/java/io/agenteval/`，`I/` 表示 `platform/infrastructure/src/main/java/io/agenteval/`，`A/` 表示 `platform/api/src/main/java/io/agenteval/`；`CT/IT/AT` 分别将相应模块的 `src/main` 替换为 `src/test`。每个文件列表都通过这些别名指向唯一真实路径。

版本迁移放在 `platform/infrastructure/src/main/resources/db/migration/`。测试采用 `*Test` 单元测试和 `*IT` 集成测试；配置 Surefire 与 Failsafe，`verify` 不得跳过 IT。所有命令在仓库根执行；Windows 使用 `mvnw.cmd` 替代 `./mvnw`。Java SDK 加入根 reactor。任务1只向reactor登记已创建的protocol模块；任务2创建core/infrastructure/api的pom及最小启动类并登记；任务4登记worker，任务9登记SDK及示例，禁止让早期构建引用尚不存在的模块。

## 3. 跨任务接口约定

接口均由下面标注的任务建立，后续任务只消费，不重复实现同名模型：

- 任务 1：protocol 中的 `TraceEvent`、`ClaimRequest`、`LeaseGrant`、`TrialContextPayload`、`CompletionRequest`、`EvaluationContext`、`CriterionResult`、`ArtifactRef`、全部状态枚举；Java 时间为 Instant、ID 为 UUID、JSON 为 Jackson JsonNode、分数 BigDecimal、credit 为 long。
- 任务 2：`WorkspaceActor(UUID userId, UUID workspaceId, boolean admin)`、`WorkspaceScope.require(WorkspaceActor actor, UUID resourceWorkspaceId)`。
- 任务 3：`DatasetVersionId`、`EvaluatorVersionId`、`SuiteVersionId` 采用 UUID；持久化版本返回 `PublishedVersion(UUID id, String contentHash)`。
- 任务 4：`ExperimentService.start(WorkspaceActor, StartExperimentCommand): UUID`；`TrialExecutionStatus` 与评分状态独立。
- 任务 5：`WorkerPrincipal(UUID workspaceId, Set<UUID> agentIds, UUID credentialId)`；`LeaseService.claim(WorkerPrincipal, ClaimRequest): Optional<LeaseGrant>`；`validateAttempt(principal, trialId, token)`。
- 任务 6：`TelemetryService.ingest(WorkerPrincipal, UUID trialId, String token, List<TraceEvent>): BatchAck`，`BatchAck` 包含已确认 event_id，不按“收到 HTTP 请求”确认持久化。
- 任务 7：`EvidenceService.freeze(UUID trialId, boolean includeLate): EvidenceSnapshot`，`EvidenceService.load(UUID snapshotId): EvaluationContext`。版本不可变。
- 任务 3 先建立 `EvaluatorDefinition`（evaluator_version、criterion_id、type、required_evidence、threshold、配置）；任务 10 建立 `Evaluator.evaluate(EvaluationContext context, EvaluatorDefinition definition): CriterionResult`。
- 任务 12：`SandboxClient.execute(UUID jobId, PythonEvaluationRequest): PythonEvaluationResponse`，内部 run key 幂等。
- 任务 13：`QuotaService.reserve(UUID workspaceId, UUID callId, long upperCredits): Reservation`；`settle(callId, long actualCredits)`、`releaseUnsent(callId)`、`markReconciliation(callId)`。
- 任务 14：`ModelGateway.judge(JudgeRequest): JudgeResponse`；所有 provider 请求必须经过它，JudgeRequest 带 workspaceId、callId、价格版本和输入/输出上限。
- 任务 16：`EvaluationOrchestrator.schedule(UUID trialId, UUID snapshotId, UUID suiteVersionId): UUID`，返回 EvaluationRun ID。

HTTP 错误统一为 `application/problem+json`，字段 code/status/detail/request_id。非法消息 400、无效凭证 401、越权 403 或不泄露资源存在性的 404、版本/幂等冲突 409、载荷过大 413、资源限流 429；语义性缺证据进入评分状态，不伪装 HTTP 错误。

## 4. 实施任务

### Task 1：建立可测试的协议与构建基线

**Files:** Create `pom.xml`、`platform/protocol/pom.xml`、`platform/protocol/src/main/java/io/agenteval/protocol/{TraceEvent,ClaimRequest,LeaseGrant,TrialContextPayload,CompletionRequest,EvaluationContext,CriterionResult,ArtifactRef}.java`、每个状态枚举独立文件、`contracts/schemas/{trace-event,claim-request,lease-grant,completion-request,evaluation-context,criterion-result}.json`、`contracts/fixtures/v1/`、`platform/protocol/src/test/java/io/agenteval/protocol/ProtocolContractTest.java`、`docs/development/dependencies.md`、`.github/workflows/verify.yml`；Modify `README.md`。

**Interfaces:** Consumes 设计规格 §6～9；Produces §3 第一项全部 DTO 和固定 JSON Schema，契约版本 `1.0`。隐藏 expected 不存在于 TrialContextPayload。

- [ ] **Step 1 — 写失败测试：** `ProtocolContractTest.rejectsInvalidScoreAndHiddenTaskReference` 断言 score=-0.1/1.1/NaN、未知必需版本、非法 ID 拒绝，发给 Worker 的 payload 无 expected/evaluator_code/model_key；合法 UTF-8、null parent 和嵌套 JSON 往返不变。关键断言：`assertThat(serializedWorkerPayload.has("expected")).isFalse()`、`assertThat(invalidScoreViolations).isNotEmpty()`。创建最小测试构建支持这一个交付，无独立“空脚手架”任务。
- [ ] **Step 2 — 运行 RED：** `./mvnw -pl platform/protocol -am test`，预期新增 DTO/validator 尚不存在而失败；构建工具下载失败不是行为 RED，应先排除环境问题。
- [ ] **Step 3 — 最小实现：** 建立 Maven reactor、wrapper、上述 DTO/validator 和契约 fixtures；平台 Spring Boot 3.5.16 为已查到的 3.5 文档版本，必须在依赖解析时验证可从允许仓库获取及维护适用性，不自动切换 Boot 4。PostgreSQL 固定 17.11。Java 21、Python 3.11、Node 22 为运行系列；其补丁、Maven 3.9 系列、Redis、S3 兼容实现、前端和测试依赖在 dependencies.md 记录确切解析版本/来源，镜像记录 digest，再提交 lockfile/BOM。未解决的维护或下载问题列为执行前置，不伪造“已验证”。CI 采用 Linux JDK21，执行协议测试。
- [ ] **Step 4 — 运行 GREEN：** 同一命令全部通过；版本文件不含 SNAPSHOT/latest，所有 fixtures 的接受/拒绝结果有确定断言。
- [ ] **Step 5 — 提交：** 只暂存本任务列出的构建、契约、测试、版本说明和 CI 文件，`git commit -m "feat: define versioned worker and evaluation contracts"`。

版本核对依据：[Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Spring 支持政策](https://spring.io/support-policy/)、[PostgreSQL 版本政策](https://www.postgresql.org/support/versioning/)。已确认 Java21 属于该 Boot 文档兼容区间；版本出现在文档不等于当前社区维护承诺，发布前仍需核实维护来源。

### Task 2：单人工作空间与双鉴权边界

**Files:** Create `platform/{core,infrastructure,api}/pom.xml`、`platform/api/src/main/java/io/agenteval/api/PlatformApiApplication.java`、`C/identity/{WorkspaceActor,WorkspaceScope,IdentityService}.java`、`I/identity/JdbcIdentityRepository.java`、migration `V001__identity.sql`、`A/security/{BrowserSecurityConfig,WorkerSecurityConfig}.java`、`A/identity/IdentityController.java`、`AT/identity/PrivateWorkspaceIT.java`；Modify root `pom.xml`。

**Interfaces:** Produces WorkspaceActor、WorkspaceScope 和管理员 `createUser(email, temporaryPassword)`；首次登录强制改密码；Session 与未来 Worker Bearer 过滤链分开。

- [ ] **Step 1 — 写失败测试：** `PrivateWorkspaceIT.isolatesUsersAndRequiresPasswordChange` 创建 A/B 两用户，断言每人恰有一空间、A 不能读取 B；临时密码用户只可改密码，改后访问成功；变更缺 CSRF 为403、未登录为401、密码不以明文入库。关键断言：`assertThat(workspaceIds).hasSize(2).doesNotHaveDuplicates()`、`assertThat(mutationWithoutCsrf.statusCode()).isEqualTo(403)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/api -am verify -Dit.test=PrivateWorkspaceIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`，预期身份行为未实现而失败。
- [ ] **Step 3 — 实现：** `IdentityService.createUser` 在同一事务建立 user/workspace，额度钱包由任务13引入；使用自适应哈希及Spring Session JDBC管理服务端Session，相关表随V001迁移。管理员初始化走部署命令，禁公共注册。Spring启动扫描根包`io.agenteval`；迁移复合唯一键，所有 API 从身份推导 scope。
- [ ] **Step 4 — GREEN：** 同一命令通过；Session 退出失效、用户不存在与密码错误不泄露账户信息。
- [ ] **Step 5 — 提交：** `git commit -m "feat: add private workspaces and session authentication"`，仅 stage 本任务文件。

### Task 3：版本化任务、评测器、套件与模板

**Files:** Create `C/catalog/{DatasetService,EvaluatorCatalogService,SuiteService,PublishedVersion}.java`、`C/evaluation/EvaluatorDefinition.java`、`I/catalog/JdbcCatalogRepository.java`、migration `V002__versioned_catalog.sql`、`A/catalog/CatalogController.java`、`AT/catalog/CatalogVersionIT.java`、`platform/api/src/main/resources/templates/`。

**Interfaces:** Produces `publishDataset(actor,id)`、`publishEvaluator(actor,id)`、`publishSuite(actor,id): PublishedVersion`；套件条目固定版本、dimension、threshold、required_to_pass 和 weight。EvaluatorDefinition 为发布内容读取模型。

- [ ] **Step 1 — 写失败测试：** `CatalogVersionIT.preservesPublishedVersionsAndRejectsCrossScopeReferences`：复制系统模板、编辑发布 v1/v2，v1 hash/input 不变；引用他人版本被拒；草稿套件不可使用未发布评测器；JSONL 非法行报告行号且整次导入不部分生效。关键断言：`assertThat(reloadedV1.contentHash()).isEqualTo(originalHash)`、`assertThat(caseCountAfterInvalidImport).isEqualTo(caseCountBeforeImport)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/api -am verify -Dit.test=CatalogVersionIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 任务 JSON/JSONL 输入大小限制、草稿 CRUD、发布不可变快照、引用后归档、只读系统模板复制；先保存三类评测器配置但未上线执行能力时显式标注能力状态。套件至少一个 required 项，权重非负且参与维度的权重和>0。
- [ ] **Step 4 — GREEN：** 同命令通过，补校验不存在引用、重复发布幂等、隐藏 expected 不出现在 Worker 专用映射。
- [ ] **Step 5 — 提交：** `git commit -m "feat: add versioned datasets and evaluation suites"`。

### Task 4：实验快照、Trial 与持久作业

**Files:** Create `C/experiments/{ExperimentService,StartExperimentCommand,TrialStateMachine}.java`、`I/experiments/JdbcExperimentRepository.java`、migration `V003__experiments_and_jobs.sql`、`A/experiments/ExperimentController.java`、`IT/experiments/ExperimentSnapshotIT.java`。

**Interfaces:** Produces §3 `ExperimentService.start`，command 包含 Agent ID/version、发布版本 ID、repeatCount、deadline；状态机只接受定义过的迁移。

- [ ] **Step 1 — 写失败测试：** `ExperimentSnapshotIT.expandsCasesAtomicallyAndSurvivesCatalogEdits`：2 案例×3 次=6 Trial；同请求幂等键返回同一实验，异内容同键409；启动后目录编辑不改变快照；事务故障时无半个实验或孤儿作业。关键断言：`assertThat(trials).hasSize(6)`、`assertThat(replayedExperimentId).isEqualTo(firstExperimentId)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=ExperimentSnapshotIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 快照与 Trial/作业同事务落库，后台扫描按 next_run_at 使用行锁；新增基础后台启动模块 `platform/worker/pom.xml`、`platform/worker/src/main/java/io/agenteval/worker/PlatformWorkerApplication.java`，运行与 API 共用领域服务，不先用 Redis 事件做权威队列。
- [ ] **Step 4 — GREEN：** 同命令通过；重建服务对象后仍读取全部待执行作业；实验重复次数/任务超限明确拒绝。
- [ ] **Step 5 — 提交：** `git commit -m "feat: persist experiment snapshots and trial jobs"`。

### Task 5：Agent 注册、凭证和领取租约

**Files:** Create `C/agents/AgentService.java`、`C/experiments/LeaseService.java`、`A/workers/WorkerController.java`、`I/experiments/JdbcLeaseRepository.java`、migration `V004__workers_and_leases.sql`、`AT/workers/WorkerLeaseIT.java`。

**Interfaces:** Produces WorkerPrincipal、LeaseService 及 register/claim/started/heartbeat/complete 控制接口；完成先保存结果，证据完成交任务 7。

- [ ] **Step 1 — 写失败测试：** `WorkerLeaseIT.fencesConcurrentClaimsAndDoesNotReplayUnknownExecution`：两个并发领取最多一人拿到 Trial；假 Clock 推进61秒后旧 token不能完成、不自动再分配；模拟提交租约后响应丢失仍不重复执行；凭证撤销后401，越 Agent范围拒绝，满工作空间2并发不发第3项。关键断言：`assertThat(grants).hasSize(1)`、`assertThat(claimAfterExpiry).isEmpty()`、`assertThat(oldTokenCompletion.statusCode()).isEqualTo(409)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/api -am verify -Dit.test=WorkerLeaseIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** Worker 凭证只存摘要、一次展示；持久租约和 CAS token，20秒长轮询、60秒租约、15秒续租契约，按 workspace/全局容量串行化计数。注入 Clock，过期到 EXECUTION_UNKNOWN；started/complete 幂等，取消通过心跳下发，超时不被续租延长。
- [ ] **Step 4 — GREEN：** 同命令通过；断开长轮询可释放服务器等待资源，旧客户端不兼容协议明确拒绝。
- [ ] **Step 5 — 提交：** `git commit -m "feat: lease agent tasks with fenced worker credentials"`。

### Task 6：自建事件采集和调用树

**Files:** Create `C/telemetry/{TelemetryService,SpanProjector,BatchAck}.java`、`I/telemetry/JdbcTelemetryRepository.java`、migration `V005__trace_events_and_spans.sql`、`A/telemetry/TelemetryController.java`、`AT/telemetry/TraceIngestionIT.java`。

**Interfaces:** Produces ingest 与 `queryTrialTrace(actor, trialId, cursor, limit)`；只有数据库提交成功才 ack event_id，接收来源由服务端写入。

- [ ] **Step 1 — 写失败测试：** `TraceIngestionIT.acceptsOutOfOrderAndRejectsConflictingReplay`：end先于start仍构树；相同ID同内容重传只存1条，相同ID异内容409且原数据不变；外租户/错token拒绝；伪造 verifier_observed 无法改变来源；101事件、1MiB+请求、64KiB+内联拒绝。关键断言：`assertThat(savedEventCount).isEqualTo(1)`、`assertThat(conflictingReplay.statusCode()).isEqualTo(409)`、`assertThat(savedPayload).isEqualTo(originalPayload)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/api -am verify -Dit.test=TraceIngestionIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 原始追加事件、唯一键和规范化载荷 hash、可重建 Span 投影；分页游标绑定 workspace/trial；TTL外事件不更新权威状态。每Trial10000事件限额在事务内执行，来源和接收时间可信。
- [ ] **Step 4 — GREEN：** 同命令通过；分页不因并发写入重复/跳过固定页边界，时区使用 UTC，duration 不由网络到达间隔计算。
- [ ] **Step 5 — 提交：** `git commit -m "feat: ingest idempotent traces and project call trees"`。

### Task 7：附件、完成清单和证据封存

**Files:** Create `C/telemetry/{ArtifactService,EvidenceService,EvidenceSnapshot}.java`、`I/storage/S3ArtifactStore.java`、`A/telemetry/ArtifactController.java`、migration `V006__artifacts_and_evidence.sql`、`IT/telemetry/EvidenceSnapshotIT.java`。

**Interfaces:** Produces freeze/load、受限上传初始化及校验完成接口；snapshot 记录事件ID集合/哈希、附件hash、complete标识、缺失/截断清单。

- [ ] **Step 1 — 写失败测试：** `EvidenceSnapshotIT.freezesAfterGraceAndPreservesOldSnapshot`：缺sequence2在30秒后PARTIAL；补到事件不修改旧snapshot；显式includeLate产生新hash；未完成附件不可当证据；越租户下载拒绝；同Trial附件超100MiB明确失败。关键断言：`assertThat(oldSnapshotAfterLate.contentHash()).isEqualTo(originalHash)`、`assertThat(newSnapshot.contentHash()).isNotEqualTo(originalHash)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=EvidenceSnapshotIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** S3对象key由服务端生成，限制尺寸、有效期和用途，完成后核对checksum/大小；complete清单和事件收齐分开。快照封存和评分待办同事务；NONE/PARTIAL/COMPLETE不等于客观真实性。
- [ ] **Step 4 — GREEN：** 同命令通过；同freeze请求幂等，取消/删除时不新增评分；storage失败没有AVAILABLE虚假记录。
- [ ] **Step 5 — 提交：** `git commit -m "feat: store artifacts and freeze evaluation evidence"`。

### Task 8：Python SDK Worker 与埋点

**Files:** Create `sdks/python/pyproject.toml`、`sdks/python/src/agent_eval/{worker,context,telemetry,transport,spool}.py`、`sdks/python/tests/{test_worker,test_telemetry,test_spool,test_contracts}.py`、`examples/python-order-agent/main.py`。

**Interfaces:** Produces `EvaluationWorker.from_env()`、`register_agent(agent_id, handler)`、`start()/stop()`、`observe(kind,name=None)`、`span(kind,name)`；handler `(TaskContext)->AgentResult`，TaskContext包含input/instruction/cancellation/deadline。仅显式映射环境变量，不允许任务替换配置。

- [ ] **Step 1 — 写失败测试：** `test_worker.test_duplicate_assignment_calls_handler_once` 断言同Trial/token仅调用一次、每次任务context独立、取消可观察；`test_telemetry.test_contextvars_keep_parallel_spans_separate` 断言异步并行调用父子不串；`test_spool.test_ack_then_delete_and_record_overflow` 断言未ack保留、重启重放同ID、超过100MiB计丢失且不改Agent输出；契约读取根目录fixtures。关键断言：`assert handler.call_count == 1`、`assert replayed_event_ids == original_event_ids`、`assert dropped_count > 0`。
- [ ] **Step 2 — RED：** `python -m pytest sdks/python/tests -q`，预期导入/能力缺失失败；由本任务安装受控测试依赖，不能把缺工具当行为失败。
- [ ] **Step 3 — 实现：** httpx长轮询、心跳线程/协程独立于handler、指数退避、contextvars、脱敏后spool，批次100/1MiB、内联64KiB；complete带清单，退出有限flush。异常不泄露密钥。演示Agent模拟本地订单，不连接真实模型。
- [ ] **Step 4 — GREEN：** 同命令通过；fake-server契约测试证明退出心跳、token失效不再次调用handler，异常退出重启不声称 exactly-once。
- [ ] **Step 5 — 提交：** `git commit -m "feat: add Python worker and trace instrumentation SDK"`。

### Task 9：Java SDK 与跨语言契约

**Files:** Create `sdks/java/pom.xml`、`sdks/java/src/main/java/io/agenteval/sdk/{EvaluationWorker,TaskContext,AgentResult,Telemetry,SpanScope,FileSpool}.java`、`sdks/java/src/test/java/io/agenteval/sdk/{WorkerContractTest,AsyncContextTest,SpoolRecoveryTest}.java`、`examples/java-order-agent/`、可选 `sdks/java-spring/`。

**Interfaces:** Produces `EvaluationWorker.fromEnvironment()`、`registerAgent(String, Function<TaskContext,AgentResult>)`、`startAndAwait()/close()`；`Telemetry.tool(String,Supplier<T>):T`、`captureContext()/runWithContext(...)`。不要求SDK依赖平台Spring模块。

- [ ] **Step 1 — 写失败测试：** `WorkerContractTest.matchesSharedFixturesAndDeduplicatesAssignments`、`AsyncContextTest.propagatesOnlyExplicitCapturedContext`、`SpoolRecoveryTest.replaysSameIdsWithoutLeakingSecrets`，断言与Python同一JSON fixture、错误/用量状态、缓存上限；Spring可选注解测试明确代理边界、自调用不冒充已记录。关键断言：`assertThat(handlerInvocations.get()).isEqualTo(1)`、`assertThat(replayedIds).containsExactlyElementsOf(originalIds)`、`assertThat(spoolContents).doesNotContain(secretValue)`。
- [ ] **Step 2 — RED：** `./mvnw -pl sdks/java -am test -Dtest=WorkerContractTest,AsyncContextTest,SpoolRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** JDK HttpClient、独立心跳/上传执行器、try-with-resources Span和有界spool；建立Java演示Agent。仅注解模块依赖Spring AOP；共享契约由schema校验，不要求与Python源码相同结构。
- [ ] **Step 4 — GREEN：** 同命令通过，再运行Python契约测试；两个SDK的事件来源均由平台校验，不由SDK声明可信。
- [ ] **Step 5 — 提交：** `git commit -m "feat: add Java worker SDK with shared protocol contracts"`。

### Task 10：规则评测引擎

**Files:** Create `C/evaluation/{Evaluator,RuleEvaluator,RuleSelector,EvidenceReferenceValidator}.java`、`CT/evaluation/RuleEvaluatorTest.java`、`contracts/fixtures/v1/evaluation/`；Consumes任务3的 `C/evaluation/EvaluatorDefinition.java`。

**Interfaces:** Produces Evaluator通用接口，RuleEvaluator实现；结果按CriterionResultSchema校验、服务端阈值计算passed。

- [ ] **Step 1 — 写失败测试：** `RuleEvaluatorTest.distinguishesMissingEvidenceFromFailure` 断言PARTIAL中的缺工具→INSUFFICIENT_EVIDENCE、完整证据+明确不存在断言才可判定；`aggregatesRetriedToolsExplicitly` 断言any/all/count不混同重试；`checksCausalityInsteadOfArrivalOrder` 断言并发无因果不能假判先后；越界数值、未知引用和非法eval操作符拒绝。关键断言：`assertThat(missingResult.status()).isEqualTo(INSUFFICIENT_EVIDENCE)`、`assertThat(missingResult.score()).isNull()`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/core -am test -Dtest=RuleEvaluatorTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** JSON Pointer、精确/数值/存在/Schema/工具集合/参数/因果/次数耗时操作符；配置必须指明多匹配聚合；NaN不可转BigDecimal，限制数值长度、选择器复杂度和Schema递归，禁止任意表达式执行。
- [ ] **Step 4 — GREEN：** 同命令通过；基础订单案例正确退款PASS、金额错FAIL、缺证据UNDETERMINED，规则分数不读取用户隐藏思维过程。
- [ ] **Step 5 — 提交：** `git commit -m "feat: evaluate deterministic rules against frozen evidence"`。

### Task 11：最小闭环评分作业与报告 API

**Files:** Create `C/evaluation/{EvaluationOrchestrator,EvaluationAggregator}.java`、`C/reporting/{ReportService,ExperimentReport}.java`、`I/evaluation/JdbcEvaluationRepository.java`、`platform/worker/src/main/java/io/agenteval/worker/EvaluationJobRunner.java`、migration `V007__evaluation_results.sql`、`A/reporting/ReportController.java`、`IT/evaluation/RuleEvaluationFlowIT.java`。

**Interfaces:** 首次提供Orchestrator schedule和`ReportService.get(actor,experimentId):ExperimentReport`；此阶段只执行RULE，其他评测器显式标记尚未启用。

- [ ] **Step 1 — 写失败测试：** `RuleEvaluationFlowIT.restartsWithoutDuplicateScoresAndExplainsDenominators`：持久作业重启只产生1个结果，硬项失败不能被可选高分抵消；1PASS/1FAIL/1缺证据报告计划分母3、可判定分母2；无硬项配置启动前拒绝。关键断言：`assertThat(resultRows).hasSize(1)`、`assertThat(report.plannedAttempts()).isEqualTo(3)`、`assertThat(report.determinableAttempts()).isEqualTo(2)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=RuleEvaluationFlowIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** snapshot + evaluatorVersion + run唯一作业、可恢复处理租约、聚合FAIL优先/UNDETERMINED/PASS；结果/过程维度与覆盖率分开，证据分页接口不返回整空间数据。
- [ ] **Step 4 — GREEN：** 同命令通过；另以Java/Python示例通过平台API各执行一个任务，根Trace和规则评分可查询，记录阶段B演示命令。
- [ ] **Step 5 — 提交：** `git commit -m "feat: run durable rule evaluations and expose evidence reports"`。

### Task 12：Python 隔离 Runner

**Files:** Create `runner/pyproject.toml`、`runner/agent_eval_runner/{api,executor,validation}.py`、`runner/sandbox/{Dockerfile,entrypoint.py}`、`runner/tests/{test_executor,test_result_protocol}.py`、`runner/tests/integration/test_isolation.py`、`I/evaluation/RunnerSandboxClient.java`。

**Interfaces:** Produces SandboxClient和HTTP POST `/internal/v1/evaluations`；请求含jobId/codeHash/imageDigest/context/limits，Runner凭内部凭证受理，run key去重，容器只有本次只读输入和受限输出目录。

- [ ] **Step 1 — 写失败测试：** `test_result_protocol.test_stdout_does_not_corrupt_result` 断言print只进有界日志；`test_executor.test_no_runsc_disables_execution` 断言runtime缺失返回CAPABILITY_UNAVAILABLE、不回退普通exec；`test_isolation` 检查30秒超时、256MiB OOM、32进程、64MiB临时目录、无网络、无平台密钥/宿主socket、symlink不能越界读取附件。关键断言：`assert unavailable.code == "CAPABILITY_UNAVAILABLE"`、`assert sandbox_result.score == 1.0`、`assert leaked_secrets == []`。
- [ ] **Step 2 — RED：** `python -m pytest runner/tests -q`；Linux强化节点上 `python -m pytest runner/tests/integration -q`，runtime未配置时必须报告未验证，不以跳过冒充通过。
- [ ] **Step 3 — 实现：** 受鉴权Runner API、固定镜像执行`evaluate(context)`、单指标JSON结果独立文件、non-root/read-only/no-network/drop-caps/资源控制；stdout/stderr各最多64KiB，输出result最多64KiB。Runner独占受控容器管理能力，API服务无socket权限。
- [ ] **Step 4 — GREEN：** 两命令在合适环境全部通过；无runtime环境仅验证功能关闭，不能宣称隔离验收完成。服务端校验结果Schema和引用，用户代码失败保留ERROR诊断。
- [ ] **Step 5 — 提交：** `git commit -m "feat: execute custom Python evaluators in hardened sandboxes"`。

### Task 13：累计额度账本与管理员操作

**Files:** Create `C/quota/{QuotaService,Reservation,QuotaWallet}.java`、`I/quota/JdbcQuotaRepository.java`、migration `V008__quota_ledger.sql`、`A/admin/QuotaAdminController.java`、`IT/quota/QuotaConcurrencyIT.java`。

**Interfaces:** Produces §3 QuotaService、`grant(admin,workspaceId,credits,reason,idempotencyKey)`；迁移为已有空间创建零钱包，新建空间时通过同事务初始化零钱包，每空间唯一，不引入假余额占位。

- [ ] **Step 1 — 写失败测试：** `QuotaConcurrencyIT.neverOverspendsAndSettlesExactlyOnce`：初始0；grant1000，同时20个reserve100仅10个成功；另用独立钱包grant100/reserve100后settle60释放40，重复settle无新扣费；同call不同金额冲突；管理员调减低于consumed+reserved拒绝；用户不能grant自己。关键断言：`assertThat(successfulReservations).hasSize(10)`、`assertThat(settledWallet.available()).isEqualTo(40L)`、`assertThat(settleEntries).hasSize(1)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=QuotaConcurrencyIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** wallet行锁、整数credit、不可变账目、业务唯一键；RESERVE/SETTLE/RELEASE/ADJUST及补偿，禁止把已发送未知请求当作未发送释放。幂等key跨HTTP重试保持不变。
- [ ] **Step 4 — GREEN：** 同命令通过；注入账本写入失败时余额也回滚，long溢出拒绝，工作空间查询隔离。
- [ ] **Step 5 — 提交：** `git commit -m "feat: account for cumulative judge credits transactionally"`。

### Task 14：平台模型网关与异常对账

**Files:** Create `C/model/{ModelGateway,JudgeRequest,JudgeResponse,ModelCallStateMachine}.java`、`I/model/OpenAiCompatibleJudgeClient.java`、`I/model/EncryptedModelProfileRepository.java`、migration `V009__model_calls_and_prices.sql`、`A/admin/ModelAdminController.java`、`IT/model/ModelGatewayIT.java`。

**Interfaces:** Produces ModelGateway；价格版本为整数比例，credit=向上取整(inputTokens*inputRate + outputTokens*outputRate)，比例分母写入版本，计算防溢出。模型支持声明tokenizer/安全上界，不接受无法约束的调用。

- [ ] **Step 1 — 写失败测试：** `ModelGatewayIT.reconcilesAcceptedCallsAfterCrash` 使用WireMock接受请求后断链：断言预留仍保留、重启不重发、调用PENDING_RECONCILIATION；另测余额不足供应商收到0请求、坏JSON仍按usage扣费、实际费用超预留停用适配器并记异常、管理员对账幂等。关键断言：`assertThat(providerRequestCount).isEqualTo(1)`、`assertThat(recoveredCall.status()).isEqualTo(PENDING_RECONCILIATION)`、`assertThat(wallet.reserved()).isEqualTo(originalReservation)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=ModelGatewayIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 管理员配置供应商endpoint白名单、加密密钥、模型/价格/最大输出；获得并发槽后预留。请求标记SEND_INTENT再发送；SEND_INTENT后崩溃保守对账，不能以本地状态推断未到供应商。成功可信usage结算；取消不回滚已发生费用，无usage走对账。
- [ ] **Step 4 — GREEN：** 同命令通过；所有模型路径经网关，日志/响应不输出密钥，endpoint仅管理员控制且出口限制，request不允许用户覆盖baseURL。
- [ ] **Step 5 — 提交：** `git commit -m "feat: gate judge calls with reservations and reconciliation"`。

### Task 15：LLM 评测器与人工校准

**Files:** Create `C/evaluation/{LlmEvaluator,JudgePromptBuilder,HumanReviewService}.java`、`I/evaluation/JdbcHumanReviewRepository.java`、migration `V010__human_reviews.sql`、`CT/evaluation/LlmEvaluatorTest.java`、`platform/api/src/main/resources/templates/judges/`。

**Interfaces:** LlmEvaluator实现Evaluator，调用任务14网关；`HumanReviewService.add(actor,resultId,score,reason)`追加人工结论不覆盖自动结果。

- [ ] **Step 1 — 写失败测试：** `LlmEvaluatorTest.rejectsUnsupportedClaimsAndPreservesUsage` 断言0～4映射[0,1]、引用越快照不接受、证据不足不强打0、提示注入文本仍位于数据段、超长关键证据不静默删除；坏JSON最多额外1次请求且callId不同；人工改判原始结果仍存在。关键断言：`assertThat(scoreFromThreeOfFour).isEqualByComparingTo("0.75")`、`assertThat(judgeCalls).hasSize(2)`、`assertThat(originalAutomaticScore).isEqualByComparingTo(savedAutomaticScore)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/core -am test -Dtest=LlmEvaluatorTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 单指标rubric + JSON Schema + 外部证据引用校验，冻结输入/模型/提示版本；WAITING_QUOTA向上透传不错误重试。提供内容覆盖、证据支持、工具结果利用三类模板及人工校准fixtures。
- [ ] **Step 4 — GREEN：** 同命令通过；评分器网络异常区分模型调用状态与评分状态，多个调用的实际费用均能追溯。
- [ ] **Step 5 — 提交：** `git commit -m "feat: add rubric-based LLM judges and human calibration"`。

### Task 16：三类评分统一编排、恢复与重评

**Files:** Modify `C/evaluation/EvaluationOrchestrator.java`、`platform/worker/src/main/java/io/agenteval/worker/EvaluationJobRunner.java`；Create `C/evaluation/{PythonEvaluator,ReevaluationService}.java`、`IT/evaluation/MixedEvaluationIT.java`。

**Interfaces:** Produces完整Orchestrator；`ReevaluationService.create(actor,trialId,suiteVersionId,includeLate,idempotencyKey):UUID`，固定snapshot后才创建EvaluationRun。

- [ ] **Step 1 — 写失败测试：** `MixedEvaluationIT.resumesOnlyQuotaBlockedJobsAndPreservesEvidenceVersions`：规则/Python在LLM余额不足时已完成，追加额度只恢复待额度项，取消后不恢复；Python超时为ERROR不为0；重评旧快照结果独立，显式includeLate才有新hash；硬失败优先于缺证据。关键断言：`assertThat(ruleExecutionCount).isEqualTo(1)`、`assertThat(pythonTimeoutResult.score()).isNull()`、`assertThat(cancelledExperimentNewJudgeCalls).isZero()`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=MixedEvaluationIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 类型注册表、required_evidence筛选、作业幂等和持久恢复；先执行低成本确定性检查，默认继续其余项用于诊断；dry-run/用户试运行同样走隔离和额度路径，不能成为免费模型入口。
- [ ] **Step 4 — GREEN：** 同命令通过；并发重评idempotency不重计，取消在发送模型前检查且已发送请求进入正常费用结算/对账。
- [ ] **Step 5 — 提交：** `git commit -m "feat: orchestrate mixed evaluators and explicit reevaluation"`。

### Task 17：管理前端与资源版本操作

**Files:** Create `web/package.json`、`web/package-lock.json`、`web/vite.config.ts`、`web/src/main.tsx`、`web/src/api/client.ts`、`web/src/features/auth/`、`web/src/features/catalog/`、`web/src/features/agents/`、`web/src/features/experiments/`、`web/src/features/admin/`、`web/src/features/catalog/CatalogEditor.test.tsx`、`web/src/features/auth/LoginFlow.test.tsx`。

**Interfaces:** 消费OpenAPI生成客户端；路由 `/login`、`/workspace/datasets`、`/workspace/evaluators`、`/workspace/suites`、`/workspace/agents`、`/workspace/experiments`、`/admin`；Session同源，不在localStorage存密码/Worker密钥。

- [ ] **Step 1 — 写失败测试：** `CatalogEditor.test.tsx` 断言草稿/发布状态、引用版本只读、删除显示归档含义、JSONL错误行号；`LoginFlow.test.tsx` 断言强制改密码流程、401回登录、普通用户无admin入口；Agent凭证只显示一次且不在后续详情出现。关键断言：`expect(screen.getByLabelText("已发布版本")).toBeDisabled()`、`expect(screen.queryByRole("link", { name: "管理后台" })).not.toBeInTheDocument()`。
- [ ] **Step 2 — RED：** `npm --prefix web test -- --run`，预期对应界面行为不存在。
- [ ] **Step 3 — 实现：** 将web构建设置纳入本任务，React Router + TanStack Query、前端schema校验、套件required/weight配置、Python编辑/试运行、平台模型选择/价格估算、额度明细和管理员建号/发放/对账；前端禁用不是后端授权的替代品。
- [ ] **Step 4 — GREEN：** 同命令通过及 `npm --prefix web run build` 无TS错误；请求携CSRF，同源cookie策略通过集成浏览器校验。
- [ ] **Step 5 — 提交：** `git commit -m "feat: manage private agent evaluation resources in web UI"`。

### Task 18：报告、轨迹与对比前端

**Files:** Modify任务11的 `C/reporting/ReportService.java`；Create `C/reporting/{ComparisonService,ReportExporter}.java`、`I/reporting/JdbcReportQueries.java`、`web/src/features/reports/{ExperimentReport,TraceTree,ComparisonView}.tsx`、`web/src/features/reports/TraceTree.test.tsx`、`IT/reporting/ReportComparisonIT.java`。

**Interfaces:** 完成任务11 ReportService、`compare(actor,leftId,rightId):ComparisonReport`、JSON导出；分页可取消，正文与附件名称只渲染转义文本。

- [ ] **Step 1 — 写失败测试：** `ReportComparisonIT.exposesCoverageAndRejectsFalseComparability` 对任务/评测/模型/证据版本不一致标明不可直接比较；自动与人工评分并存；通过率两个分母都正确。`TraceTree.test.tsx` 展示乱序根/子span、证据跳转、截断/缺失标签，含`<script>`文本不执行。关键断言：`assertThat(comparison.directlyComparable()).isFalse()`、`expect(screen.getByText("证据不完整")).toBeVisible()`。
- [ ] **Step 2 — RED：** Java运行 `./mvnw -pl platform/infrastructure -am verify -Dit.test=ReportComparisonIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`；web运行 `npm --prefix web test -- --run`。
- [ ] **Step 3 — 实现：** 结果/过程/效率/覆盖率分开展示，执行和评分各自进度，逐层查询轨迹、按评测项定位证据、显式重跑与重评入口及副作用提示，原快照/纳入迟到证据可选；没有可比数据不画误导总分。
- [ ] **Step 4 — GREEN：** 两测试和web build通过，JSON导出含版本/来源/分母，不输出隐藏密钥；导出当前用户授权证据。
- [ ] **Step 5 — 提交：** `git commit -m "feat: explain evaluation results with traces and comparisons"`。

### Task 19：删除、审计与资源上限

**Files:** Create `C/experiments/ExperimentDeletionService.java`、`C/administration/{ResourceLimitService,AuditService}.java`、`platform/worker/src/main/java/io/agenteval/worker/CleanupJobRunner.java`、migration `V011__audit_and_cleanup.sql`、`IT/experiments/DeletionRaceIT.java`、`IT/administration/ResourceLimitIT.java`。

**Interfaces:** `deleteExperiment(actor,id,idempotencyKey)`只先标记DELETING并取消；`cleanup(id)`可重试；审计append只记录非秘密字段。

- [ ] **Step 1 — 写失败测试：** `DeletionRaceIT.lateUploadCannotResurrectDeletedExperiment` 删除同时complete/upload：拒绝新增权威记录，已申请未完成对象进入清理，账本保留；其他租户对象永不删除。`ResourceLimitIT.enforcesConcurrentLimitsAtomically` 断言50Worker/20Trial以及本空间上限、HTTP429和可恢复队列，非LLM任务也有限流。关键断言：`assertThat(ledgerAfterDeletion).containsExactlyElementsOf(ledgerBeforeDeletion)`、`assertThat(otherTenantObjectsAfter).containsExactlyElementsOf(otherTenantObjectsBefore)`。
- [ ] **Step 2 — RED：** `./mvnw -pl platform/infrastructure -am verify -Dit.test=DeletionRaceIT,ResourceLimitIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`。
- [ ] **Step 3 — 实现：** 删除代次/状态栅栏，平台和SDK凭证权限复核、对象清理只按服务端可信清单；取消后处理完已有费用，账本以最小审计关系留存。Redis只做短期限流；容量权威计数在数据库。审计包含建号、凭证、发布、启动、删除、额度和模型变更。
- [ ] **Step 4 — GREEN：** 同命令通过；storage短暂失败重试不跨租户且最终可清理，不以吞错误宣布已完成删除。
- [ ] **Step 5 — 提交：** `git commit -m "feat: enforce lifecycle cleanup and platform resource limits"`。

### Task 20：部署、端到端验收和使用指南

**Files:** Create `deploy/{compose.yaml,compose.sandbox.yaml,nginx.conf}`、`deploy/.env.example`、`e2e/{test_java_worker.py,test_python_worker.py,test_restart_and_quota.py}`、`web/e2e/workspace-evaluation.spec.ts`、`docs/guides/{quickstart,java-sdk,python-sdk,custom-evaluators,operations}.md`、`.github/workflows/{sdk-contracts,sandbox-e2e}.yml`；Modify `README.md`、`.github/workflows/verify.yml`。

**Interfaces:** docker-compose基础服务含web/API/Worker/Postgres/Redis/S3，Runner通过受限内部地址在合格Linux节点运行；无外网真实模型依赖的验收用固定Judge stub。

- [ ] **Step 1 — 写失败测试：** 两语言E2E各完成平台发起→Worker领取→嵌套轨迹→规则/LLM/Python结果；重启测试验证未知执行不重跑、模型已发送未知状态不重复扣费；Playwright管理员建号发额度、普通用户复制示例→发起→证据报告→对比→删除。关键断言：`assert evaluator_types == {"RULE", "LLM", "PYTHON"}`、`assert unknown_trial_handler_calls == 1`、`await expect(page.getByText("证据来源")).toBeVisible()`。
- [ ] **Step 2 — RED：** `python -m pytest e2e -q`、`npm --prefix web exec -- playwright test`，预期缺部署/场景行为失败；硬化sandbox套件在专用runner执行而不是普通CI假成功。
- [ ] **Step 3 — 实现：** HTTPS反代、Session安全配置、健康检查、非root、受限端口、固定镜像、环境密钥注入；API与Worker数据库迁移由单一启动作业执行，Runner不暴露公网。补备份/恢复、密钥轮换、额度对账、离线Worker诊断和SDK安装文档；仅发布实际已构建验证的包坐标。
- [ ] **Step 4 — GREEN：** `./mvnw verify`、Python SDK/Runner全部测试、`npm --prefix web ci`及test/build、E2E和强化隔离套件通过。压测50在线Worker/20Trial和10000事件，报告延迟/内存/错误率，不达目标列出限制；进行一次数据库+附件备份恢复演练。更新README只勾选有证据完成项。
- [ ] **Step 5 — 提交：** `git commit -m "feat: ship verified platform deployment and SDK examples"`；再按用户选择的执行方法完成整分支审阅，修复问题后才发布可运行版本。

## 5. 每项任务的执行规则

- 创建任务文件后仅 stage 本任务文件，检查 `git diff --cached --check`；不使用 `git add .` 混入无关改动。
- 所有 RED/GREEN 和集成验证命令记录到实施进度；没有运行就记“未验证”，不能用文档检查代替行为测试。
- 测试用Clock、HTTP stub和真实PostgreSQL控制边界，不用sleep等待30/60秒。测试中复用共享fixture，不能通过双份实现互相“证明”。
- 引入依赖时只为当前任务交付服务；lockfile、镜像digest与所用测试版本一并提交。已有大版本选择不随意升级。
- 不在日志、截图或提交中记录真实模型密钥、Worker凭证、用户任务秘密；演示使用测试数据。
- 暂不执行平台调用真实业务写工具的E2E。任何上线、发布包及开放公网操作另依用户授权范围执行。

## 6. 规格覆盖自查

| 规格范围 | 覆盖任务 |
| --- | --- |
| 私人工作空间、管理员、凭证、隔离 | 2、5、17、19 |
| 任务集/模板/评测体系CRUD与不可变版本 | 3、4、17 |
| 平台调度、长轮询、租约、取消、失联 | 4、5、8、9、19 |
| 双SDK、异步上下文、脱敏、spool | 1、8、9 |
| 轨迹、自建存储、附件、完整性、迟到 | 6、7、18 |
| 规则、缺证据、因果和聚合 | 10、11 |
| Python自定义代码、试运行与安全 | 12、16、17、20 |
| LLM模板、密钥、输入边界、人工校准 | 14、15、17 |
| 累计额度、预留、并发结算和对账 | 13、14、16、17 |
| 重评、重跑、版本比较、报告与导出 | 7、16、18 |
| 删除/保留、审计、配额和容量 | 19、20 |
| 部署、TDD、重启、端到端和恢复 | 各任务测试、20 |

自查结论：规格没有留待未来实现的首版功能缺口；运行时可用性、依赖维护状态、模型供应商和gVisor节点需在实际执行时核验，不能由本计划替代。任务中的具体DTO、命名和默认上限是实施约定，不表示产品已具备这些能力。

## 7. 执行前环境与版本记录

本轮确认当前机器能找到 java、mvn、python、node、npm、docker、git、gh 命令，但命令存在不证明版本或服务可用。一次组合版本查询无输出后已中断，未安装/升级任何工具，也未验证Docker daemon、JDK21、Python3.11或gVisor。

实施任务1前分别有界检查工具版本，优先使用项目wrapper/隔离虚拟环境；不覆盖用户现有全局Java或Anaconda。Docker/Testcontainers和gVisor受系统条件限制时使用明确的Linux执行环境；如需用户提供运行环境，说明哪个验收受阻，其余任务继续。

## 8. 计划交接

本次只交付计划，不创建产品脚手架。请先确认本计划，并选择执行方式：

- **当前会话直接执行（Native）：** 主Agent逐项TDD实现，完成后独立整分支审阅。前期接口依赖紧密，优先推荐此方式。
- **子Agent逐项执行（Subagent-driven）：** 各任务使用新的实施者和审阅者，再进行整分支审阅；审阅更密集，但上下文与协调成本更高。

用户选择后按对应技能执行；具体执行分支使用 `codex/` 前缀并依工作树技能检查隔离，不在规划阶段创建产品实现分支或并行任务。
