# 选择和扩展评测方法

平台将评测器、套件、任务集发布为不可变版本。实验固定这些版本；编辑草稿不会改变已有实验。执行状态和评分状态分开：Agent 执行成功不等于回答合格，证据不足或评测器错误也不等于 Agent 得零分。

## 规则、LLM 和 Python

LLM 模型只需填写模型名称、API 地址和 API Key；平台模型扣累计人民币额度，自带 Key 由供应商向用户计费、不扣平台额度。配置步骤、预设范围和计费口径见 [模型接入与额度](llm-models.md)。

RULE 用于可确定的检查，例如 `config: {"operator":"eq","selector":"/output/amount","expected":100}`，不产生模型费用。字段选择、工具调用和过程指标的完整操作符见 [规则评测说明](../development/reporting.md)。

LLM 用于语义指标。每个指标指定五档 rubric、`prompt_version: rubric-v1`、模型/价格版本、输入和输出上限。模型返回 `status`、整数 `grade: 0..4`、原因和已提供的证据引用；平台归一化为 0～1 并计算阈值。固定响应格式并不保证判断正确，需用人工标注校准。结构错误最多触发一次额外计费请求；已发送但未知的调用保留预留，不自动重发。配置和示例见 [LLM 指南](../development/llm-evaluation.md)。

PYTHON 用于现有模板无法表达的自定义指标。在“评测器”创建 Python 草稿并编写同步函数：

```python
def evaluate(context):
    matched = context["output"].get("amount") == context["expected"].get("amount")
    return {
        "status": "SCORED",
        "score": 1 if matched else 0,
        "reason": "Compare the order amount with the reference",
        "evidence_refs": ["output", "expected"],
    }
```

上下文包含任务说明、输入、隐藏参考、最终输出、固定事件和附件元数据。隐藏参考只提供给平台评分器，不在 Worker 任务中下发。附件文件只读位于 `/input/artifacts/<artifact_id>`；名称和 URL 不决定文件路径。

SCORED 的分数须有限且在 0～1 之间；INSUFFICIENT_EVIDENCE、ERROR、SKIPPED 的 `score` 为 null。不要返回 `passed` 或修改指标版本，平台用固定配置计算是否通过。必需指标不通过不能被可选高分抵消；报告保留未评分项和覆盖率。

草稿试运行也通过同一个强化隔离、资源预算和模型额度路径，结果独立保存，不替换正式报告。显式重评复用选定证据；“纳入迟到证据”会生成新的证据范围。重跑 Agent 则重新执行真实工具，应由用户确认副作用。

## 合格 Linux 执行节点

平台默认 `RUNNER_ENABLED=false`。执行节点必须具备 Linux Docker、已注册的 gVisor runsc 和固定 guest 镜像 ID；不允许回退到宿主 Python 或普通 runc。专用临时 Linux CI 已通过真实超时、内存、网络、文件、进程数和日志隔离验收；生产节点仍须自行重新验收。

根据 [gVisor 官方安装说明](https://gvisor.dev/docs/user_guide/install/) 配置节点。固定版本需要完整 tarball，包括 `gvisor-bin` sidecars；项目 `scripts/ci-install-runsc.sh` 仅用于临时 GitHub 托管 VM，会拒绝普通机器，不能当作生产安装脚本。

在专用节点构建并验收：

```bash
docker build -t agent-eval-sandbox:0.1.0 runner/sandbox
export RUNNER_IMAGE_DIGEST="$(docker image inspect --format '{{.Id}}' agent-eval-sandbox:0.1.0)"
export RUNNER_TEST_IMAGE="$RUNNER_IMAGE_DIGEST"
python -m pip install -r runner/requirements-test.lock
python -m pytest runner/tests -q
```

通过后准备仅管理员可读的执行节点 env 文件，包含 `RUNNER_INTERNAL_KEY`（至少 32 字节随机值）、`RUNNER_IMAGE_DIGEST`、绝对路径 `RUNNER_WORK_ROOT`、精确 HTTPS `RUNNER_ARTIFACT_ORIGINS`、私网绑定地址 `RUNNER_BIND_ADDRESS` 及 Docker socket 的数字组 ID `DOCKER_GID`。工作目录由 UID 10001 持有，路径必须在主机和控制容器内一致。

```bash
docker compose --env-file /private/runner.env -f deploy/compose.sandbox.yaml up --build -d --wait
```

控制服务拥有 Docker 管理权限，应运行在独立执行节点，不能与平台数据库宿主混放。API/Worker 不挂载 Docker socket。只允许平台 Worker 访问 8091；跨主机使用 TLS 隧道或可信加密网格。生产附件证书须受信任，若使用私有 CA，需为 Runner 配置受控信任根。

在平台 env 中配置 `RUNNER_ENABLED=true`、私网 `RUNNER_BASE_URL`、相同内部密钥和镜像 ID，再重新创建 API/Worker。资源预算不足会等待，提高预算不代表绕过沙箱能力检查。默认每作业 30 秒、256 MiB、32 进程、64 MiB 临时目录、无网络、只读输入及根文件系统；详见 [Runner 协议](../development/python-runner.md)。

32为同real UID用户任务（进程/线程，含guest入口和结果读取）的软/硬限制，不保证32个额外子进程；包含gVisor线程和stub的宿主cgroup另设256 PID上限。不要把整个cgroup改回32，否则可能耗尽运行时自身的线程预算。生产节点必须使用当前验收测试重新确认这两层限制。
