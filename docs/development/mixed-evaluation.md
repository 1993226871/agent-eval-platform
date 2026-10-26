# 混合评分、重评和草稿试运行

后台执行注册表包含RULE、PYTHON、LLM，先领取低成本规则、再Python、再LLM；即使必需规则失败，仍继续其他项以便诊断。质量判断保持必需失败优先，不用其他高分抵消。

## 等待与恢复

作业有QUEUED/RUNNING/DONE/CANCELLED/WAITING_QUOTA/DEFERRED及下一次执行时间。额度不足保存待额度结果，不写入不可变最终评分；追加额度后只恢复未完成项。并发等待、发送中和待对账继续使用同一job/call身份，不生成新计费请求。一般等待5秒、待对账30秒后重新检查。

处理租约为60秒，过期旧执行者不能提交权威结果；恢复者查询已有模型调用或Runner幂等结果。模型调用与Python结果不会因为评分进程重启而无条件重放。Python超时/OOM/协议错误均为未评分ERROR，缺少强化执行能力为SKIPPED。

取消检查有两层：作业开始前检查，模型预留及SEND_INTENT提交前在数据库锁内再次检查。发送意图提交是调用授权边界；已提交发送意图的费用仍正常结算/对账，取消不能推断供应商未受理。网络调用不在数据库事务或调度锁内执行。

## Runner接入

API/Worker均读取`RUNNER_ENABLED`（默认false）、`RUNNER_BASE_URL`、`RUNNER_INTERNAL_KEY`、`RUNNER_IMAGE_DIGEST`。仅在已验收的Linux/runsc专用节点部署Runner后启用。当前Windows主机没有强化运行时，仍保持关闭；替身超时测试不代表隔离验收。

附件签名下载同时校验workspace、trial、固定snapshot成员身份及名称/类型/大小/摘要。已到达的新附件不能用于旧快照。Runner下载仍检查origin、大小、摘要和只读文件边界。对象存储配置需同时提供给API与Worker，API不持有容器管理权限。

## 明确重评

`POST /api/v1/trial-evaluations/{trialId}/reevaluations`

```json
{"suite_version_id":"<已发布套件UUID>","include_late":false,"idempotency_key":"<固定请求键>"}
```

返回202和run_id。仅支持本人已终止、未取消的任务，不重新调用Agent。默认固定原证据快照；include_late=true才显式冻结当前迟到证据。每轮固定所选套件/指标版本并追加独立作业与结果，不覆盖旧分数或账目。重评产生的新模型请求正常计费。

请求幂等、冻结证据与入队在同一事务内完成，纳入迟到证据不额外生成隐藏的默认评分轮次。默认报告按最新正式评分轮次选择套件权重与结果，提供实际suite_version_id及实验evaluation_suite_version_ids，不能将不同评分配置冒充同一比较条件。

## 草稿试运行

`POST /api/v1/evaluators/{draftId}/previews`提交`revision`、已有终止任务的`trial_id`和固定`idempotency_key`，返回202/run_id。

试运行冻结草稿副本及单指标套件为私有归档版本，源草稿保持未发布/未修改。副本走同一持久评分队列、模型额度和强化Python沙箱。purpose=PREVIEW的评分单独查询，不取代正式实验报告；首次证据冻结来自预览时，仍确保原套件的自动评分入队。重复请求不重复冻结版本或计费。

`GET /api/v1/evaluation-runs/{runId}`返回本人评分轮次的目的、状态、快照哈希、套件版本及单项结果（包含result_id用于人工复核）。历史评分、预览和正式重评均可分别查看；管理员没有私人空间旁路。

当前验证使用本地HTTP模型供应商、真实PostgreSQL和可控Runner替身，覆盖混合等待恢复、取消竞态、20并发重评去重、快照/套件变更、预览计费与正式报告隔离；真实Python隔离仍需专用节点验收。
