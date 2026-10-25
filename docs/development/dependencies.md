# 开发依赖与验证记录

本文件只记录实际已解析的依赖；完整平台尚未实现。

| 依赖 | 固定版本 | 验证方式 |
| --- | --- | --- |
| JDK | Temurin 21.0.12.1+1（本机项目内工具） | Adoptium API、下载 SHA256 校验、实际 Maven 测试 |
| Java release | 21 | Maven compiler release |
| Maven wrapper | 3.3.2，下载 Maven 3.9.9 | 官方 wrapper plugin 生成，HTTPS Maven Central |
| Spring Boot | 3.5.16（后续模块使用） | Maven Central POM HTTP200，尚未完成启动集成测试 |
| Jackson | 2.18.3 | BOM和协议测试实际解析 |
| JUnit | 5.11.4 | 参数化协议测试实际运行 |
| AssertJ | 3.27.3 | 实际测试运行 |
| networknt JSON Schema | 1.5.6 | 校验 JSON Schema 2020-12 和共享fixture |
| SLF4J test nop | 2.0.17 | 只用于纯协议模块测试日志绑定 |
| compiler plugin | 3.14.0 | JDK21编译 |
| Surefire / Failsafe | 3.5.3 | 单元/集成测试阶段约定 |
| PostgreSQL | 17.11 | 官方镜像已拉取 |

PostgreSQL 镜像 digest：`postgres@sha256:b8faeff7712663c2773a190181e1246e37e364b887611c04658c28b44e2408fe`。

本地默认 Java17 保持不变，运行前将 JAVA_HOME 指向 JDK21。项目提供 `.mvn/settings.xml` 使用 HTTPS Central；不提交本机下载缓存、JDK或真实凭证。

Python3.11.5和Node24.15.0在当前机器可用；其SDK/前端测试尚未执行。CI目标JDK21，Node22计划在web任务锁定。Redis、S3、Python镜像和前端lockfile由对应任务在实际运行后补充，不能把计划版本写成已验证。

Spring Boot 保留已选3.x；具体维护来源和适用性在正式发布前核实，不因文档存在对应版本就声称社区支持。
