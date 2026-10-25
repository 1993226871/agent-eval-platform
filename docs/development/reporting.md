# 持久评分与报告（阶段B）

本阶段打通规则评分闭环。API处理资源、任务控制和报告查询；独立后台进程负责封存后的评分调度。用户的Agent仍在自己的环境里运行。

## 作业与恢复

快照事务创建的 `grading_jobs` 是初始EvaluationRun持久头，id即run_id。后台将冻结套件中的发布版本展开为 `evaluation_jobs`，以run_id/evaluator_version唯一。相同版本被多个套件条目引用时只计算一次，再按各条目的维度和权重聚合。

每个单项作业有60秒处理租约和新令牌。进程退出后，租约到期的纯规则计算可重新领取；旧令牌不能覆盖结果。权威结果按job_id唯一保存，同内容完成回调幂等；评分、作业完成及Run状态更新在同一事务内。取消已生效时不创建新评分或结果，已完成结果保留。此处的计算恢复不重跑Agent，也不授权未来模型调用在发送状态未知时自动重试。

此阶段只启用RULE。LLM/PYTHON配置可以管理，但执行返回SKIPPED，score/passed为null，解释为能力尚未启用；必需项因此不能判定通过。Runner、Judge及额度在后续阶段接入。

初始Run对外状态为PENDING/RUNNING/COMPLETED/PARTIAL/CANCELLED。COMPLETED只表示指标均已评分，不表示质量通过；存在未评分项时为PARTIAL。新快照保留旧Run和结果，当前实验报告选取最新快照对应Run，并提供run_id/snapshot_id以识别范围。完整重评选择与历史Run查询在后续阶段扩展。

## 质量和分母

必需项中任意明确失败优先得到FAIL；没有失败但缺必需评分为UNDETERMINED，尚在处理则显示PENDING；所有必需项通过才为PASS。可选高分不抵消硬性失败。执行状态独立展示，不把执行结束、评分结束、质量通过合成一个SUCCESS。

Outcome与Process分别按已评分权重求均值，缺失项不填零。同时返回scored_weight/total_weight、scored_criteria/planned_criteria和partial。即使缺失项权重为0，也保留“部分评分”标记；未配置维度或没有可用分数时score为null。

报告包含planned_attempts、started_attempts、finished_attempts、determinable_attempts及各质量状态计数。started表示平台收到开始确认，不把尚未执行就超时的队列任务算作已开始。通过率同时返回PASS/全部计划和PASS/可判定，两者都包含numerator/denominator/value，分母0时value为null。

效率单独显示已观测根Span耗时的样本数及p50/p95，来源标注worker_reported。耗时来自当前冻结快照内的事件，不读取随后变化的投影，也不以网络到达时间估算。报告查询采用只读重复读事务，避免查询期间新快照或结果提交导致分母、状态和来源混用。

## HTTP API

浏览器Session鉴权并检查当前工作空间：

| GET | 返回 |
| --- | --- |
| /api/v1/reports/experiments/{id} | 汇总、版本、执行/质量状态、通过率分母、分维度覆盖率和效率 |
| /api/v1/reports/experiments/{id}/trials?limit=25&cursor=... | Trial结果分页、单项解释/引用、Run及Snapshot ID |

分页上限100，游标绑定工作空间与实验。响应不附带任务input/expected；用户可按权限继续访问自己的证据快照和调用树。Worker Bearer不替代浏览器Session。

## 复现真实双SDK闭环

需要JDK21、Python3.11、运行中的Docker和已解析的Maven依赖。先从根目录构建：

```bash
./mvnw verify
python -m pip install -r sdks/python/requirements-test.lock
python scripts/verify_phase_b.py
```

若构建使用自定义Maven仓库，给脚本传 `--maven-repo <仓库路径>`；本机隔离仓库示例为 `.tools/m2`。脚本使用固定版本PostgreSQL一次性容器、API/后台可执行jar和两套SDK示例，全部服务绑定本机。

脚本在内存生成管理员、数据库和Worker凭证，通过真实HTTP完成CSRF登录、首次改密、Agent/模板/凭证管理及实验启动。两套Agent均执行本地订单查询，无真实模型或支付服务调用。等待终态30秒证据窗口及调度后，验证每个任务的根Trace、工具Span、规则分数和报告。SDK子进程不接收脚本生成的数据库或管理员密码。

结果保存在 `.tools/phase-b-demo-result.json`，不包含密钥。退出时仅停止本次创建的子进程和Docker容器；日志和本地已脱敏缓存留在忽略目录中以便诊断。GitHub Java验证作业在Maven通过后也执行该脚本。

2026-10-26本地实际验证：Python、Java各1次任务，均有1个根Span和1个query_order工具Span，score=1，verdict=PASS，Run=COMPLETED；planned=started=determinable=1。仅证明模拟订单场景闭环，不能代替后续完整部署和容量验收。
