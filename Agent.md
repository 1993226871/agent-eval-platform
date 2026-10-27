# VeyraEval Platform 项目协作规则

本文件是本仓库的项目级 Agent 说明；`AGENTS.md` 是自动发现入口。用户本轮明确指令优先于本文件。

## Git 操作约束

1. **每次 `git push` 都先询问用户，得到本次操作的明确同意后才执行。** 询问前先完成可在本地完成的修改、验证和差异整理，说明目标远端、分支、提交范围和是否涉及删除。普通的“继续”“完成改动”不等于同意 push。
2. 一次同意只适用于说明过的本次推送；再次 push、推送其他分支/标签、远端分支删除、强制推送和重写历史都必须分别获得明确授权。不得用 API、其他 Git 客户端、CI 或后台任务绕过。
3. `git commit -m` 的提交说明必须使用中文，例如 `完善项目说明并补充 SDK 发布准备`。`git push` 没有 `-m` 参数，中文备注指提交说明；PR 标题和描述也优先中文。
4. 功能分支使用 `feature/MMDD-简短英文主题`，例如 `feature/1027-project-docs`；不使用与助手产品名关联的分支前缀。未经明确要求不直接在 main 开发或合并。
5. 不覆盖用户未提交修改；不默认使用 `--force`。`.gitignore` 不会删除已跟踪文件或历史，历史清理需要单独审核并授权。
6. `docs/superpowers/` 和 `.superpowers/` 是本地过程资料，不能重新添加到 Git。公开文档应描述当前系统和使用方式，不复述私人设计过程。
   `docs/development/implementation-decisions.md`、`final-review.md`、`progress.md`、`web-design.md` 也只保留本地，不得强制添加。SDK 源码包或文档产物不得夹带这些资料。

## 项目结构

本项目是 Java 21 / Spring Boot 3 后端、React / TypeScript / Vite 前端的 Agent 评测平台。不是 Next.js 项目。

| 路径 | 职责 |
| --- | --- |
| `platform/protocol/` | 共享 Java DTO、协议序列化和协议契约测试；Java SDK 直接依赖。 |
| `platform/core/` | 身份、目录、实验、租约、证据、规则/LLM/Python 评测、费用、报告等领域服务和仓储接口。 |
| `platform/infrastructure/` | PostgreSQL/JDBC 仓储、S3 附件、模型网关、Runner HTTP 客户端、21 个 Flyway 迁移及集成测试。 |
| `platform/api/` | Spring Boot API、浏览器 Session/CSRF、安全过滤器、Worker 鉴权、Controller 和平台模板。 |
| `platform/worker/` | 平台后台进程：评分、超时维护、模型请求恢复与删除清理。不是用户 Agent Worker。 |
| `sdks/java/`、`sdks/python/` | 用户环境中的 Agent Worker、长轮询、心跳、打点、持久化上传和结果提交。 |
| `runner/` | 专用 Linux/runsc 节点上的自定义 Python 评测执行服务及隔离镜像；不在宿主执行用户代码。 |
| `web/` | React 私人工作台、目录编辑、实验、证据、报告、对比、额度及管理页面。 |
| `contracts/` | 规范 JSON Schema、共享有效/无效样例和生成的 OpenAPI；SDK/Runner 内 schema 是同步副本。 |
| `examples/` | 两语言订单查询 Agent 演示。 |
| `e2e/` | 真正的 HTTPS Compose、双 SDK、浏览器、隔离、恢复、计费不确定状态和容量验收。 |
| `deploy/` | Compose、Dockerfile、Nginx 和运维镜像。 |
| `scripts/` | 构建、契约生成、验证、部署配置、备份恢复及专用 CI 节点初始化。 |
| `docs/` | 面向维护者和使用者的当前系统说明；入口见 `docs/project-guide.md`。 |

## 必须保持的语义

- 每人一个私有工作空间，任何私人资源访问都校验所有者；管理员没有读取他人证据的旁路。
- PostgreSQL 是租约、调度、评分和额度的权威来源；Redis 当前在部署中预留，不是可靠任务队列。
- 任务、套件和评测器按发布版本冻结；证据快照不可变。未知业务执行和未知模型请求不能自动重放。
- 执行失败、证据不足和评测器错误不能伪装成 Agent 零分。规则、LLM Judge 和自定义 Python 结果都有证据来源。
- 费用是累计整数 credits；浏览器用整数字符串/BigInt。未知模型费用保持待对账，不能自动退款后重发。
- 用户 Agent 在其环境运行；自定义 Python 评测代码只在通过验收的 runsc 节点运行。不可回退到宿主解释器或普通容器。
- Runner guest real UID 的任务/线程默认软硬限制为32（允许4..32）；宿主运行时另有256 PID预算，不能混为一谈。
- 删除完成前必须收到对象存储和对应 Runner 的清理确认；墓碑围栏阻止迟到数据重新进入。

## 开发与验证

新功能与修复采用 TDD：先复现失败，再做最小修改，再运行相关及必要的整体验证。纯文档编辑检查链接、目录清单和 Markdown；不为文字修改编写实现镜像测试。

```bash
./mvnw verify
python -m pytest sdks/python/tests -q
python -m pytest runner/tests -m 'not integration' -q
cd web
npm ci
npm test -- --run
npm run build
npm run format:check
```

真实隔离必须在专用 Linux/runsc 节点运行 `python -m pytest runner/tests -q`，并运行当前 CI 的100次综合隔离重复检查；Windows 控制层测试不能替代它。`e2e/` 需要实际 Docker/HTTPS 环境及合格 Runner。根据改动范围执行验证，不对未改动部分反复重复测试。

最终说明需写清修改、验证结果、尚未完成或依赖用户配置的事项。SDK 打包成功不表示已经发布到 PyPI/Maven Central；公开发布和账号授权需单独确认。不要把密钥、Token、私有配置或本地过程文件加入 Git。
