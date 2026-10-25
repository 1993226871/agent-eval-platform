# 开发依赖与验证记录

本文件只记录实际已解析的依赖；完整平台尚未实现。

| 依赖 | 固定版本 | 验证方式 |
| --- | --- | --- |
| JDK | Temurin 21.0.12.1+1（本机项目内工具） | Adoptium API、下载 SHA256 校验、实际 Maven 测试 |
| Java release | 21 | Maven compiler release |
| Maven wrapper | 3.3.2，下载 Maven 3.9.9 | 官方 wrapper plugin 生成，HTTPS Maven Central |
| Spring Boot | 3.5.16 | Maven解析、启动及真实PostgreSQL身份集成测试 |
| Jackson | 2.21.4 | 随Boot BOM对齐；协议测试再次通过 |
| JUnit | 5.11.4 | 参数化协议测试实际运行 |
| AssertJ | 3.27.3 | 实际测试运行 |
| networknt JSON Schema | 1.5.6 | 校验 JSON Schema 2020-12 和共享fixture |
| SLF4J test nop | 2.0.17 | 只用于纯协议模块测试日志绑定 |
| compiler plugin | 3.14.0 | JDK21编译 |
| Surefire / Failsafe | 3.5.3 | 单元/集成测试阶段约定 |
| PostgreSQL | 17.11 | 官方镜像已拉取 |
| Testcontainers | 1.21.4 | Docker Desktop27.5.1 + PostgreSQL实际运行 |
| Spring Security | 6.5.11 | 实际HTTP登录、CSRF及会话测试 |
| Spring Session | 3.5.7 | JDBC持久化Session集成测试 |
| AWS Java SDK S3 / URLConnection | 2.31.20 | HTTPS Maven Central解析、实际S3兼容对象读写 |
| S3Mock（仅测试） | 4.11.0 | Docker容器中的上传、校验失败和封存验证 |
| Python | 3.11.5（本机）/3.11（CI系列） | 隔离venv测试、wheel安装、本地订单示例 |
| httpx / jsonschema | 0.28.1 / 4.23.0 | Python Worker HTTP协议与共享fixture验证 |
| pytest / setuptools | 8.3.5 / 80.9.0 | 47项SDK测试、隔离构建wheel |
| SQLite JDBC | 3.53.4.0 | Maven Central解析、Java缓存恢复/并发测试 |

PostgreSQL 镜像 digest：`postgres@sha256:b8faeff7712663c2773a190181e1246e37e364b887611c04658c28b44e2408fe`。

S3Mock 镜像 digest：`adobe/s3mock@sha256:cd49108c0094bc3f420b24bff354073032a9ec1a6f4cc89f8e8f483a308cf99e`。实现依据[AWS预签名URL文档](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)及[S3Mock固定版本说明](https://github.com/adobe/S3Mock/tree/4.11.0)；S3Mock是测试依赖，部署存储实现仍在部署任务中确定。

本地默认 Java17 保持不变，运行前将 JAVA_HOME 指向 JDK21。项目提供 `.mvn/settings.xml` 使用 HTTPS Central；不提交本机下载缓存、JDK或真实凭证。

Python SDK实际解析的传递依赖锁定于 `sdks/python/requirements-test.lock`，不复用或修改全局Anaconda环境。Node24.15.0在当前机器可用，前端测试尚未执行；CI目标Node22在web任务锁定。Redis、部署S3兼容实现、Python Runner镜像和前端lockfile由对应任务在实际运行后补充，不能把计划版本写成已验证。

Java SDK复用协议模块Jackson2.21.4，未引入Spring运行依赖。SQLite JDBC版本依据[官方发布记录](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0)并验证Maven Central可解析；Windows本地测试已执行，Linux由同一Maven CI验证。

Spring Boot 保留已选3.x；具体维护来源和适用性在正式发布前核实，不因文档存在对应版本就声称社区支持。
