# 当前后端运行方式

当前已实现身份、版本化任务/评测资源、Agent调度、轨迹/附件、证据快照、规则评分和报告API，尚无Web界面及完整部署包。需要JDK21、PostgreSQL17；测试另需Docker，双SDK演示另需Python3.11。

## 构建和测试

Windows：`mvnw.cmd verify`。Linux：`./mvnw verify`。Failsafe会运行真实PostgreSQL集成测试，Docker不可用会失败，不静默跳过。

## 启动

构建后，配置 `DATABASE_URL`（JDBC URL）、`DATABASE_USER`、`DATABASE_PASSWORD`，运行：

```text
java -jar platform/api/target/api-0.1.0.jar
```

API启动并完成Flyway迁移后，以相同数据库环境变量启动独立平台后台：

```text
java -jar platform/worker/target/worker-0.1.0.jar
```

平台后台负责过期任务维护、证据封存和评分调度；用户Agent侧的SDK Worker是另一个进程，负责从平台领取并执行Agent任务。后台默认不自行迁移数据库。

正式环境通过HTTPS反代，默认Cookie Secure=true；纯本地HTTP调试才设置 `SECURE_COOKIES=false`。

首次创建管理员：通过环境变量提供 `BOOTSTRAP_ADMIN_EMAIL` 和 `BOOTSTRAP_ADMIN_PASSWORD`，启动参数添加 `--app.bootstrap.enabled=true`。密码至少12个字符，最多72 UTF-8字节。初始化不会改写已有管理员密码，也不会提升已有普通账户的权限。完成后取消初始化开关并移除临时密码环境变量。

先GET `/api/v1/auth/csrf`保留Session Cookie，再将返回的header_name/token用于POST。登录使用表单username（邮箱）和password；首次登录后必须调用改密接口才能访问工作空间。改密会撤销旧会话并为当前请求建立新会话。

当前[OpenAPI](../../contracts/openapi/platform-v1.yaml)覆盖身份接口，后续会补齐全量定义。其他已实现协议见[Worker控制](worker-protocol.md)、[附件与快照](evidence.md)、[规则](rules.md)、[评分及报告](reporting.md)。Worker端使用有Agent范围的Bearer凭证及尝试令牌，不使用浏览器Session。

未配置S3时文件能力返回503，最小订单演示无需文件存储；配置见附件文档。LLM/Python执行和模型额度尚未启用，不能将配置模板误当成已完成运行能力。自动创建并清理临时服务的双SDK闭环脚本见报告文档。
