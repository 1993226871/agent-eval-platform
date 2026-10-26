# 运维与恢复

先阅读 [快速开始](quickstart.md)。Compose 镜像通过实际获取的 digest 固定；升级需重新构建、运行迁移/协议/隔离测试及备份恢复演练。默认部署为单机小规模配置，不提供跨节点高可用保证。

## 启动、健康与网络

单一 `migrate` 作业完成 Flyway 后退出；API 和 Worker 的自动迁移关闭，只有迁移成功才启动。`storage-init` 幂等创建私人 bucket。API/Worker 探针检查数据库连接，Web 检查本机 TLS 入口，S3 检查 readiness。探针表示基础就绪，不保证所有评分作业正常推进；还需检查等待状态、错误和日志。

```bash
docker compose --env-file /private/platform.env -f deploy/compose.yaml ps
docker compose --env-file /private/platform.env -f deploy/compose.yaml logs --tail 100 api worker
```

只映射 HTTPS Web/S3 端口，默认绑定回环。S3 签名需要正确的外部 origin，代理保留 Host 与原路径，不能随意添加 `/storage` 前缀。反代不记录包含短期签名的访问日志。正式部署使用受信任证书，并核对 Secure、HttpOnly、SameSite Cookie 与同源 CSRF。

## 停写备份与空目标恢复

备份目录包含私人任务、模型密文、会话、费用记录和附件，应放在加密磁盘或受控备份系统，限定权限和保留时间。`MODEL_ENCRYPTION_KEY`、S3 凭证和 TLS 密钥由独立秘密备份保护；数据库密文不能替代主密钥备份。

```bash
python scripts/manage_backup.py backup --env-file /private/platform.env --directory /encrypted/backup-2026-10-26
```

命令停止 Web、API 和 Worker，关闭平台写入及公共上传入口，然后生成 PostgreSQL custom dump、流式 S3 对象文件和 SHA256 清单。命令完成后服务仍停止；检查结果后明确执行 `docker compose ... up -d --wait`。存储不可达或校验失败不报告成功。

恢复到另一个项目和新 volumes：先启动 postgres、s3、redis，等待健康，再用 `docker compose ... run --rm --no-deps storage-init init` 建立空 bucket。不要先启动 migrate/API，因为恢复要求 public schema 没有表。

```bash
python scripts/manage_backup.py restore --env-file /private/restore.env --directory /encrypted/backup-2026-10-26
```

使用备份对应的模型主密钥，配置新环境的公开 S3 origin。恢复拒绝非空数据库或 bucket，不执行 DROP 或覆盖。S3 文件完整校验后才上传；之后恢复数据库。若中途失败，保留备份，重新准备空目标，而不是在半恢复的目标上反复覆盖。检查数据及附件后启动应用，并做登录、私人报告、附件下载和费用对账检查。

已完成一次真实 PostgreSQL 与 RustFS 备份/恢复演练，目标为新建的独立实例，校验行数、私人实验和附件字节，并验证再次恢复会拒绝非空数据库。此演练不等于已验证所有云 S3 或大规模恢复时间。

## 密钥轮换

Worker 密钥通过私人 Agent 页签发新凭证、替换 Agent 环境、验证上线，再撤销旧凭证。旧密钥撤销后不能领取、续约或完成任务；应安排在无执行中业务时轮换。

供应商密钥轮换采用新模型配置和价格版本：更新部署允许的 origin，创建新配置，发布引用新模型的评测器/套件，验证后停用旧配置。历史已发送调用及预留仍需按原调用身份结算，不因停用直接退款。

模型加密主密钥必须离线轮换，不能只改 `.env`。先停写并备份，将旧 `MODEL_ENCRYPTION_KEY` 和新的 `NEW_MODEL_ENCRYPTION_KEY`（base64 编码的 32 字节随机密钥）从秘密管理系统注入操作进程，勿放命令行字面量或日志：

```bash
docker compose --env-file /private/platform.env -f deploy/compose.yaml run --rm --no-deps \
  --env MODEL_ENCRYPTION_KEY --env NEW_MODEL_ENCRYPTION_KEY --entrypoint java migrate \
  -Dloader.main=io.agenteval.operations.DeploymentOperations -cp /app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher rotate-model-key
```

工具在单事务中锁定、解密并重加密全部模型凭证，任一解密失败整批回滚；保留模型/价格身份，按模型追加不含秘密的系统审计。成功后将平台 env 中主密钥更新为新值，清除操作进程中的临时新密钥，再启动服务。旧备份仍依赖旧密钥，按备份生命周期安全保留。若轮换成功后尚未更新平台配置，不要启动使用旧密钥的应用。

Runner 内部密钥需要同时更新平台和专用节点，安排停机窗口；SQLite 执行注册表保持原目录，不清空以“解决”未知执行，否则可能重复执行用户代码。

## 费用与队列诊断

空间初始 credits 为 0，管理员调整需要原因和幂等键。模型调用先按上界预留，收到可信 usage 后消费并释放差额。调用已发送但响应不确定时，保留预留和并发槽，管理员确认真实用量后按原 call_id 对账；重复同键不重复消费。禁止按超时猜测免费或自动重发。

资源预算与模型 credits 分开。WAITING_RESOURCES/STORAGE_LIMIT 需提高预算或清理历史实验；WAITING_QUOTA 需发放 credits；Runner 不可用需修复合格节点。删除释放证据和附件预算，但累计执行、评分、Python 保守秒数不返还。详情见 [生命周期与资源](../development/lifecycle-resources.md)。

Worker 离线时依次检查 HTTPS 信任、网络、接入凭证是否撤销、Agent ID/版本、在线 Worker 上限及 Agent 进程。试运行/正式评分的错误需查看指标原因；平台不会把评测器超时伪装成 Agent 零分。业务执行不确定时，在确认真实副作用后再决定是否显式重跑。

## 已测容量及限制

验收脚本 `python -m pytest e2e -q -s` 使用独立临时项目，运行 50 在线 Worker、20 个并发任务和 10,000 个小事件，记录批量写入与报告 P95、容器内存快照和意外错误数。完整套件需要合格 Linux/runsc 节点、固定 guest 镜像、已构建的 Java/Web 产物和 Playwright Chromium。

Windows 基础平台的一轮实测：10000 事件约 6.92 秒入库，100 个批量请求 P95 1547 毫秒，10 次报告读取 P95 16 毫秒，意外错误 0。当时 API 内存快照约 364 MiB、Worker 192 MiB。它们是单轮短时测试数据，不是持续吞吐、峰值内存或生产 SLA；真实任务正文、附件、评分器和供应商延迟会影响容量。Linux 验收另保存实际测量 artifact。

规则、协议、事务、故障恢复和本地 Judge stub 均有验证；真实付费模型准确率、供应商全部兼容差异、生产节点隔离资格及高可用灾备需要各部署自行验收。
