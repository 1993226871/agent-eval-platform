# Python 评测 Runner

Runner 是单独部署的内部服务。平台只通过鉴权 HTTP 调用它，不在 API/Worker 进程中执行用户 Python。混合调度已接入 RULE、PYTHON 和 LLM；部署默认关闭 Python，合格节点配置完成后显式开启。

## 能力与验收状态

必须满足 Linux 主机、Linux Docker daemon、已注册的 `runsc` 和固定镜像摘要。缺任一条件返回 `CAPABILITY_UNAVAILABLE`，不回退宿主解释器或普通容器。

本地 Windows/Docker Desktop 环境没有 runsc。独立临时 Ubuntu24.04 VM 使用固定 gVisor20261019.0 完整发行包及 SHA512，已实际通过全部21项 Runner测试（含5项真实隔离），见[验收运行](https://github.com/1993226871/agent-eval-platform/actions/runs/40314323679)。这不自动赋予生产节点资格：更换主机、运行时或镜像后须重新验收。

控制层测试只证明鉴权、校验、幂等和命令策略，不能替代上述隔离测试。集成测试在缺少合格节点时明确失败，不能用跳过表示通过。`Linux sandbox acceptance` 在可信分支提交后启动独立VM；`Hardened Runner acceptance` 手动工作流保留给专用 `agent-eval-runsc` 运维节点；普通CI仍仅验证控制层。

## 内部协议

`Authorization: Bearer <RUNNER_INTERNAL_KEY>`，至少32字节；鉴权先于请求体读取。`GET /internal/v1/capabilities` 返回能力，`POST /internal/v1/evaluations` 接受：

```json
{
  "job_id": "<评分作业UUID>",
  "code": "def evaluate(context): ...",
  "code_hash": "<UTF-8代码SHA256>",
  "image_digest": "sha256:<已部署镜像ID>",
  "context": "<EvaluationContext对象，非字符串>",
  "limits": {"timeout_seconds":30,"memory_mb":256,"processes":32,"tmp_mb":64},
  "artifact_downloads": [{"artifact_id":"<当前快照附件UUID>","url":"<短期下载地址>"}]
}
```

HTTP体最多3MiB，代码64KiB、上下文2MiB；限制只能降低。附件ID集合必须与快照一致，下载只允许服务端配置的精确origin、禁止重定向，并核验大小和SHA256，总量100MiB。用户任务中的URL不会变成下载配置。

`job_id` 是持久幂等键，绑定代码、快照、镜像、资源限制及附件身份；重新签名的下载URL不改变内容身份。同键不同请求409；执行中202，繁忙429，能力缺失503。丢失HTTP响应时必须重用同一键。Runner崩溃后未完成作业记为 `EXECUTION_UNKNOWN`，不会自动再次执行。单个服务进程通过文件锁独占状态库。

## 自定义指标

配置 `type: PYTHON`，`config.code` 定义同步 `evaluate(context)`。附件只读文件位于 `/input/artifacts/<artifact_id>`。返回值：

```python
def evaluate(context):
    matched = context["output"]["amount"] == context["expected"]["amount"]
    return {"status": "SCORED", "score": 1 if matched else 0,
            "reason": "Compare order amount", "evidence_refs": ["output", "expected"]}
```

支持 SCORED/INSUFFICIENT_EVIDENCE/ERROR/SKIPPED。SCORED分数必须有限且在0到1之间，其余score为null。禁止返回passed、指标版本或外部证据引用；通过阈值由Java后端计算。失败保持未评分，不冒充Agent零分。

stdout/stderr各保留最多64KiB并标记截断，结果通过独立文件和独立读取通道传输，最多64KiB。guest non-root、只读根目录/输入、无网络、drop ALL、no-new-privileges，256MiB内存且无额外swap、32进程、64MiB tmpfs、1MiB输出tmpfs。超时终止且仅清理具有本服务标签和runsc身份的容器。

进程预算分为两层：用户real UID10001的任务使用`RLIMIT_NPROC`软/硬上限（默认32，协议允许4..32），包含进程及线程，也包含guest入口和同UID结果读取进程，不保证可再启动32个子进程。通过固定OCI `--ulimit=nproc=N:N`设置，guest不能提高硬上限；容器宿主cgroup另外限制为256个PID，为gVisor自身线程和地址空间stub保留有界运行开销。256不是用户可创建的进程数。内存、CPU和并发限制仍共同约束整个沙箱。部署节点需要为运行时开销做容量规划。

此前把用户32进程预算直接用作整个宿主cgroup上限，进程压力会使Sentry发生`newosproc`崩溃，随后出现`WaitPID EOF`和`NO_RESULT`。合格节点验收必须检查guest上限不可提升、创建进程在EAGAIN处被拒绝，并确认沙箱仍可正常返回结果；不能把任意运行失败当作进程隔离通过。背景见[gVisor资源模型](https://gvisor.dev/docs/architecture_guide/resources/)。

## 合格节点启动

服务直接部署于专用Linux节点，独占所需Docker控制权限；平台API不挂载socket。环境变量由部署系统注入，不进guest：`RUNNER_INTERNAL_KEY`、`RUNNER_IMAGE_DIGEST`、`RUNNER_WORK_ROOT`、可选`RUNNER_ARTIFACT_ORIGINS`（逗号分隔精确origin）。使用单进程：

```sh
python -m pip install -r runner/requirements-test.lock
docker build -t agent-eval-sandbox:local runner/sandbox
export RUNNER_IMAGE_DIGEST="$(docker image inspect --format '{{.Id}}' agent-eval-sandbox:local)"
export RUNNER_TEST_IMAGE="$RUNNER_IMAGE_DIGEST"
python -m pytest runner/tests/integration -q
cd runner
uvicorn agent_eval_runner.api:create_application --factory --host 0.0.0.0 --port 8091 --workers 1
```

仅内部受控网络开放8091，跨主机使用TLS或可信网格加密。受控目录及SQLite含用户证据/代码日志，不能共享给其他用户，需要私有磁盘和备份/保留策略。未配置节点时保持功能关闭。
