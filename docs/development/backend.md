# 当前后端运行方式

当前只实现协议和身份模块，不是完整评测平台。需要 JDK21 和 PostgreSQL17；运行测试另需 Docker。

## 构建和测试

Windows：`mvnw.cmd verify`。Linux：`./mvnw verify`。Failsafe会运行真实PostgreSQL集成测试，Docker不可用会失败，不静默跳过。

## 启动

构建后，配置 `DATABASE_URL`（JDBC URL）、`DATABASE_USER`、`DATABASE_PASSWORD`，运行：

```text
java -jar platform/api/target/api-0.1.0.jar
```

正式环境通过HTTPS反代，默认Cookie Secure=true；纯本地HTTP调试才设置 `SECURE_COOKIES=false`。

首次创建管理员：通过环境变量提供 `BOOTSTRAP_ADMIN_EMAIL` 和 `BOOTSTRAP_ADMIN_PASSWORD`，启动参数添加 `--app.bootstrap.enabled=true`。密码至少12个字符，最多72 UTF-8字节。初始化不会改写已有管理员密码，也不会提升已有普通账户的权限。完成后取消初始化开关并移除临时密码环境变量。

先GET `/api/v1/auth/csrf`保留Session Cookie，再将返回的header_name/token用于POST。登录使用表单username（邮箱）和password；首次登录后必须调用改密接口才能访问工作空间。改密会撤销旧会话并为当前请求建立新会话。

接口定义见[OpenAPI](../../contracts/openapi/platform-v1.yaml)。Worker接口在凭证功能完成前一律拒绝，不使用浏览器Session作为Worker认证。
