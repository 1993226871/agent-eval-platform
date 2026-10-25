# Agent Eval · Python SDK

Connect your own Agent to private evaluations. Your Agent runs in your environment; the platform assigns tasks and collects execution evidence.

## Install

Python 3.11 or newer. From this repository:

```bash
python -m pip install ./sdks/python
```

## Connect an Agent

Create an Agent and a scoped Worker credential in the platform. Set `AGENT_EVAL_URL`, `AGENT_EVAL_API_KEY`, `AGENT_EVAL_AGENT_VERSION`, and optionally `AGENT_EVAL_SPOOL` and `AGENT_EVAL_INSTANCE_ID` in your environment. Use HTTPS outside localhost.

```python
from threading import Event
from agent_eval import AgentResult, EvaluationWorker, observe

@observe("tool", name="lookup")
def lookup(order_id):
    return {"amount": 100, "status": "paid"}

def handle(task):
    if task.cancelled:
        return AgentResult({"status": "cancelled"})
    return AgentResult(lookup(task.input["order_id"]))

worker = EvaluationWorker.from_env()
worker.register_agent("YOUR_AGENT_UUID", handle)
try:
    worker.start()
    Event().wait()
finally:
    worker.stop()
```

The platform starts experiments. The SDK long-polls for work, confirms `started`, invokes your registered handler, renews the lease independently, flushes evidence, and submits the result. It never executes task-provided Python or applies task fields as SDK configuration. A handler may also be async. Check `task.cancelled` or wait on `task.cancellation` during long operations; Python threads cannot safely force-stop a noncooperative handler.

## Instrument calls

Use `@observe("model")`, `@observe("tool")`, or `with span("retrieval", "search", input_value=...): ...`. Inside a Span, assign `scope.output` when using the context manager. Async tasks inherit context through contextvars. Calls outside an active evaluation are passed through. Instrumentation captures visible inputs, outputs, error types, and elapsed time; it does not capture hidden model reasoning. Generator streaming is not instrumented automatically; wrap the actual consumption in an explicit span.

The default redactor masks credential-like keys and the current platform/attempt credentials, including occurrences in values and labels. It does not prove all domain-sensitive data is removed: shape the values you pass into explicit spans to suit your application. Final `AgentResult.output` goes to the platform unchanged and must be JSON-compatible; telemetry redaction does not rewrite that business result. Exception messages are excluded from traces.

## Evidence and recovery

- Events are saved before upload and removed only after a persisted acknowledgement. Retry keeps the same event IDs. The default spool budget is 100MiB of queued event/payload bytes; SQLite metadata and its bounded write journal require additional disk headroom. Restrict the spool directory to the Worker account and retain it across process restarts.
- Oversized inline payloads are queued as redacted JSON attachments, then linked by `payload_ref`. To bound capture, individual strings are limited to 1MiB, nesting to16 and collections to128 entries. Truncation remains explicit, including when an attachment replaces inline evidence. Capacity loss increments dropped_count and does not rewrite Agent output.
- `task.artifact(path, name=..., media_type=...)` uploads an explicitly selected file and includes its ID in completion. File contents are the caller's responsibility; automatic telemetry attachments have already passed through redaction.
- Durable execution markers contain token hashes. Repeated delivery does not invoke the handler again. A crash after the marker is written may leave execution unknown; the SDK provides no exactly-once guarantee and does not automatically replay business operations.
- Undelivered events remain available with their original IDs after restart. Resubmission requires a valid assignment/token, which is intentionally not stored in plaintext. The platform does not automatically reassign expired executions. This can leave old evidence locally queued until explicit recovery or cleanup; it never grants renewed execution authority.
- Shutdown signals cooperative cancellation and waits up to6s for pollers. A long poll or noncooperative handler may outlive this wait; daemon threads stop on process exit. Active resources are retained until a later `stop()` can close them. Flush attempts have a finite request timeout and cannot guarantee delivery during outages.

## Try the example

```bash
python examples/python-order-agent/main.py --local
```

Without `--local`, also set `AGENT_EVAL_AGENT_ID` and start a platform experiment using the order-query template. The example only queries a local dictionary.

## Develop

```bash
python -m pip install -r sdks/python/requirements-test.lock
python -m pytest sdks/python/tests -q
```

Schemas in `src/agent_eval/schemas` are generated copies of `contracts/schemas`; tests fail if they drift. After a contract change run `python sdks/python/scripts/sync_contracts.py` and re-run both Java and Python contract tests.
