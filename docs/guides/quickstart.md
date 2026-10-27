# 从源码启动私人评测平台

平台当前版本为源码构建的 `0.1.0`；Python SDK 已发布 PyPI，Java SDK 已发布 Maven Central，可直接引入依赖。平台使用 Java 21、PostgreSQL、React 和自建证据采集；Java/Python SDK 运行在 Agent 所在环境，长轮询领取平台任务。

## 构建与启动

准备 JDK 21、Node.js 22.23.4、Python 3.11、Docker Engine 和 Docker Compose v2。Windows Docker Desktop 可验证基础平台；自定义 Python 执行需要另行通过验收的 Linux/runsc 节点。源码构建需要访问依赖仓库。

```bash
git clone https://github.com/1993226871/veyraeval.git
cd veyraeval
bash scripts/build-release.sh
python scripts/configure_deployment.py .tools/local --admin-email admin@example.test --development-tls
docker compose --env-file .tools/local/.env -f deploy/compose.yaml up --build -d --wait
```

开发证书只覆盖 localhost，保存为 `.tools/local/tls/server.crt` 和 `server.key`；生成的私有 `.env` 包含首次登录密码。访问 `https://localhost:8443`。浏览器和 SDK 需要信任该开发 CA；正式部署应替换为受信任的证书，并修改 `TLS_CERT_DIR`、公网 S3 origin、端口和绑定地址。默认仅绑定回环地址，配置过程不会开放公网。

Linux 上证书目录和私钥必须可被配置的 `WEB_UID/WEB_GID` 读取。生成器默认使用当前用户 UID/GID，私钥为 0600。生产证书通常采用受控组及 0640 权限。勿把实际配置、私钥或备份提交到 Git；项目已忽略常见秘密文件，但仍需在提交前检查。

平台首次登录必须修改密码。管理员创建的新用户默认获得 ¥5.00 累计平台评审额度，没有周期重置。自带 API Key 不扣平台额度。基础规则评测无需模型额度，仍受执行次数和存储预算限制。模型接入和价格口径见 [LLM 模型接入与额度](llm-models.md)。

## 完成第一次评测

也可以直接按 [完整 Python 使用示例](python-evaluation-walkthrough.md) 自动创建私人任务集、规则与两个示范 Agent，并查看真实评分及轨迹。下面是通过工作台逐步操作的方式。

1. 普通用户在“任务集”展开“从平台示例开始”，复制完整入门示例，得到本人固定版本的任务集、规则评测器和套件。
2. 在“Agent 接入”注册 Agent，保存 Agent ID，创建并保存一次性接入密钥。
3. 在自己的运行环境按 [Python SDK](python-sdk.md) 或 [Java SDK](java-sdk.md) 启动 Worker。演示 Agent 仅查询本地模拟订单。
4. 在“实验”选择 Agent、任务集版本和套件版本后启动。平台派发任务，SDK 上报根调用和工具调用。
5. 任务结束后平台收集证据；默认宽限 30 秒。报告分开展示执行状态、评分状态、结果、过程、覆盖率和效率。

首次演示选择规则套件即可。LLM 评测需要管理员配置允许的供应商 origin、加密密钥、模型和不可变价格，再由用户将固定模型/价格绑定到评测器。Python 评测需要 [合格执行节点](custom-evaluators.md)；默认关闭，不能用普通容器替代 runsc。

## 部署边界

Compose 包含 Web、API、Worker、单一数据库迁移作业、PostgreSQL、Redis 和 RustFS S3。只有 Web 的 HTTPS 8443 和 S3 HTTPS 9443 映射到主机；数据库、Redis、S3 管理接口与 API 不直接暴露。附件签名使用单独的 S3 HTTPS origin，反向代理保留 Host 和路径。

默认的 RustFS 单节点单盘配置服务于小规模部署，没有冗余；它不是高可用存储。需要高可用时应配置合适的外部 S3 服务，并重新验证签名、存储和恢复。当前 Redis 是预留的临时基础设施，容量、额度和恢复状态由 PostgreSQL 决定。

参阅 [运维指南](operations.md)、[评分器指南](custom-evaluators.md)及[项目结构说明](../project-guide.md)。
