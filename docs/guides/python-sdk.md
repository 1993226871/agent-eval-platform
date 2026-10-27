# Python Agent 接入

需要 Python 3.11 或更高版本。SDK `0.1.0` 已发布到 [PyPI](https://pypi.org/project/veyraeval-sdk/0.1.0/)，可直接安装，无需下载仓库。下面的订单示例文件仍位于仓库的 `examples/python-order-agent/`；自己的 Agent 项目只需引入 SDK 并按接入示例注册处理函数。

```bash
python -m pip install veyraeval-sdk==0.1.0
export AGENT_EVAL_URL=https://your-platform.example
export AGENT_EVAL_AGENT_ID=<平台注册的Agent UUID>
export AGENT_EVAL_API_KEY=<本次创建的接入密钥>
export AGENT_EVAL_AGENT_VERSION=v1
export AGENT_EVAL_SPOOL=/private/path/agent-spool.sqlite
python examples/python-order-agent/main.py
```

远程平台必须使用 HTTPS。开发 CA 可通过 `SSL_CERT_FILE` 配置；不要关闭 TLS 校验。`AGENT_EVAL_API_KEY` 是 Worker 接入密钥，不是模型供应商密钥，不能放到前端或任务输入里。

```python
from threading import Event
from agent_eval import AgentResult, EvaluationWorker, observe

@observe("tool", name="query_order")
def query_order(order_id):
    return {"amount": 100, "status": "paid"}

def agent(context):
    if context.cancelled:
        return AgentResult({"status": "cancelled"})
    return AgentResult(query_order(context.input["order_id"]))

worker = EvaluationWorker.from_env().register_agent("YOUR_AGENT_UUID", agent)
try:
    worker.start()
    Event().wait()
finally:
    worker.stop()
```

SDK 自动建立任务根 Span。用 `observe` 标记工具、模型、检索等步骤，或使用显式上下文采集；异步调用和完整选项见 [SDK 详细说明](../../sdks/python/README.md)。采集内容先脱敏再进入有界 SQLite spool。平台只看到上报的信息，未标记的内部逻辑不会自动出现；这些轨迹属于 Worker 上报证据。

SDK 独立续约，处理函数应检查取消信号和任务时限。执行标记先于业务处理持久化，进程崩溃后的不确定业务不会自动重跑；需要重试时在平台显式创建新实验。上报和完成请求可按原身份恢复，不重新执行业务。

每个进程使用自己的持久 spool，限制访问权限并保留到已确认消息发送完成。完整业务结果和附件还受平台大小、执行次数及存储限制。离线时检查网络、证书、密钥是否撤销、Agent ID/版本和空间资源上限。
