# 附件与证据快照

Worker通过 `POST /api/v1/trials/{trial}/artifacts` 初始化上传。请求为共享 `register-artifact-request.json`；Bearer及 `X-Attempt-Token` 与轨迹接口一致。名称仅作为标签，禁止路径；对象key由服务端随机生成。同Trial幂等键必须对应完全相同的元数据。包括未完成上传在内，每Trial预留总量最多100MiB。

响应提供 `artifact_id`、`upload_url`、`expires_at`、`content_type` 和 `content_length`。SDK应使用这两个HTTP头以PUT上传原始字节，链接有效120秒，然后调用 `POST /api/v1/trials/{trial}/artifacts/{artifact_id}/complete`。平台流式检查实际大小与SHA-256，将同一份已校验字节上传到服务端专用sealed位置。临时上传链接再次使用不会覆盖sealed对象。存储调用在数据库锁之外执行，失败保持PENDING；VERIFYING通过令牌及120秒恢复窗口防止失联校验永久占用状态。

完成清单中的附件必须属于当前Trial，但允许仍在上传；快照只接纳AVAILABLE附件。浏览器通过 `GET /api/v1/artifacts/{id}/download` 获得120秒下载链接，服务端检查当前工作空间及可用状态，并要求附件下载和application/octet-stream内容类型。链接属于短期持有者凭证，前端不可写入日志或公开页面。

本地/API未配置存储时能力明确返回503，不回退到本地文件夹。启用配置：`app.storage.enabled=true`，并提供 `app.storage.endpoint`、`app.storage.region`、`app.storage.bucket`、`app.storage.access-key`、`app.storage.secret-key`。bucket须预建且保持私有。endpoint须同时能被平台及SDK访问；不通过修改已签名URL的域名解决网络差异。真实密钥从部署环境注入。

后台每5秒扫描已结束30秒且尚未封存的Trial。浏览器也可POST `/api/v1/evidence/trials/{id}/freeze`，证据尚缺且未过30秒时返回409 EVIDENCE_PENDING。快照记录任务、实验配置、完成清单、原始事件及其ID/hash/服务端来源、已验证附件hash、缺失/截断原因和完整性等级。没有事件为NONE，缺事件或附件、截断、未闭合调用树为PARTIAL，清单和轨迹收齐为COMPLETE。它描述可用证据范围，不证明Agent自报信息客观真实。

默认freeze始终返回首份快照；显式 `includeLate=true` 重新收集当前可用证据，有变化才产生新hash和快照，旧快照不变。同一快照的评分待办只创建一次，并与快照在同一事务写入。取消实验/Trial不产生新评分待办。后台依靠持久终态扫描恢复，无需SDK再次触发；结果读取接口为 `GET /api/v1/evidence/snapshots/{id}`。

集成验证使用PostgreSQL17.11及S3Mock4.11.0。S3Mock验证对象读写与封存流程，不替代目标S3服务对签名、权限、配额和生命周期策略的部署验收。临时对象清理由后续生命周期任务统一提供；首版预留容量不会在上传失败后自动释放。
