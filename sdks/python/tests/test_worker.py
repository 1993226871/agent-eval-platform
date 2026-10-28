from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Event
from unittest.mock import Mock
from uuid import uuid4
from agent_eval import EvaluationWorker, AgentResult
from agent_eval.spool import FileSpool
from agent_eval.transport import ProtocolError


def grant():
    trial = str(uuid4())
    return {"trial_id": trial, "trace_id": uuid4().hex, "attempt_token": "secret-attempt", "lease_expires_at": (datetime.now(timezone.utc) + timedelta(seconds=60)).isoformat(),
            "task": {"trial_id": trial, "input": {"base_url": "http://attacker.invalid"}, "instruction": "answer", "deadline": (datetime.now(timezone.utc) + timedelta(seconds=300)).isoformat()}}


class FakeTransport:
    heartbeat_seconds = 0.01
    def __init__(self):
        self.started_count = 0
        self.heartbeats = 0
        self.completions = []
        self.events = []
        self.cancel = False
        self.reject_started = False
    def started(self, assignment):
        self.started_count += 1
        if self.reject_started:
            raise ProtocolError(409)
    def heartbeat(self, assignment):
        self.heartbeats += 1
        return {"cancel_requested": self.cancel, "lease_expires_at": assignment["lease_expires_at"]}
    def upload_events(self, assignment, events):
        self.events.extend(events)
        return {"acknowledged_event_ids": [e["event_id"] for e in events]}
    def complete(self, assignment, result):
        self.completions.append(result)


def test_duplicate_assignment_calls_handler_once(tmp_path):
    transport = FakeTransport()
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        handler = Mock(return_value=AgentResult({"answer": 42}))
        worker.register_agent("agent-one", handler)
        assignment = grant()
        with ThreadPoolExecutor(2) as pool:
            list(pool.map(lambda _: worker.execute("agent-one", assignment), range(2)))
        assert handler.call_count == 1
        assert transport.started_count == 1
        assert transport.completions[0]["output"] == {"answer": 42}
        assert transport.completions[0]["event_count"] == 2
        assert transport.completions[0]["status"] == "SUCCEEDED"


def test_heartbeat_is_independent_and_cancellation_is_visible(tmp_path):
    transport = FakeTransport()
    transport.cancel = True
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        contexts = []
        def handler(context):
            contexts.append(context)
            assert context.cancellation.wait(2)
            return AgentResult("done")
        worker.register_agent("agent-one", handler)
        worker.execute("agent-one", grant())
        heartbeat_count = transport.heartbeats
        assert heartbeat_count > 0
        assert transport.completions[0]["status"] == "CANCELLED"
        worker.execute("agent-one", grant())
        assert contexts[0] is not contexts[1]
        assert transport.heartbeats > heartbeat_count
        worker.stop()
        assert worker.active_heartbeat_count == 0


def test_rejected_started_and_restart_never_replay_handler(tmp_path):
    assignment = grant()
    transport = FakeTransport()
    transport.reject_started = True
    handler = Mock(return_value=AgentResult(42))
    for _ in range(2):
        with FileSpool(tmp_path / "queue.sqlite") as spool:
            worker = EvaluationWorker(transport, spool)
            worker.register_agent("agent-one", handler)
            worker.execute("agent-one", assignment)
    assert handler.call_count == 0


def test_async_cancelled_handler_never_reports_success(tmp_path):
    import asyncio
    import pytest
    transport = FakeTransport()
    async def cancelled(context):
        raise asyncio.CancelledError()
    with FileSpool(tmp_path / "cancelled.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        worker.register_agent("agent-one", cancelled)
        with pytest.raises(asyncio.CancelledError):
            worker.execute("agent-one", grant())
        assert transport.completions[0]["status"] == "FAILED"
        assert transport.completions[0]["output"] is None
        assert any(event["event_type"] == "span.failed" for event in transport.events)


def test_from_env_uses_explicit_settings_only(tmp_path):
    env = {"AGENT_EVAL_URL": "https://platform.test", "AGENT_EVAL_API_KEY": "ae_private", "AGENT_EVAL_AGENT_VERSION": "v1", "AGENT_EVAL_SPOOL": str(tmp_path / "queue.sqlite"), "DATABASE_PASSWORD": "never-send"}
    worker = EvaluationWorker.from_env(env)
    assert worker.transport.base_url == "https://platform.test"
    assert "never-send" not in repr(worker)
    worker.stop()


def test_large_redacted_payloads_are_uploaded_as_artifacts(tmp_path):
    from agent_eval import observe
    import json
    transport = FakeTransport()
    uploaded = []
    def upload_bytes(assignment, content, name, media_type, idempotency_key=None):
        uploaded.append(content)
        return str(uuid4())
    transport.upload_bytes = upload_bytes
    @observe("model")
    def model():
        return {"text": "x" * 100000, "api_key": "never-cache"}
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        worker.register_agent("agent-one", lambda ctx: AgentResult(model()))
        worker.execute("agent-one", grant())
        assert uploaded
        assert any("x" * 100000 in data.decode() for data in uploaded)
        assert all(b"never-cache" not in data for data in uploaded)
        assert transport.completions[0]["artifact_ids"]
        assert transport.completions[0]["output"]["api_key"] == "never-cache"
        assert spool.pending_payloads(transport.events[0]["trial_id"]) == []


def test_invalid_business_result_is_reported_without_breaking_worker(tmp_path):
    transport = FakeTransport()
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        worker.register_agent("agent-one", lambda ctx: AgentResult(object()))
        worker.execute("agent-one", grant())
        assert transport.completions[0]["status"] == "FAILED"
        assert transport.completions[0]["output"] is None


def test_config_file_registers_agent_and_resolves_paths_without_environment(tmp_path, monkeypatch):
    import json
    from unittest.mock import patch
    config = tmp_path / "agent-eval.local.json"
    config.write_text(json.dumps({
        "platform_url": "https://platform.example", "api_key": "private-fixture-key",
        "agent_id": "agent-one", "agent_version": "release-2",
        "spool_path": "state/events.sqlite", "ca_cert": "certs/platform.crt",
    }), encoding="utf-8")
    monkeypatch.setenv("AGENT_EVAL_API_KEY", "unrelated-environment-key")
    handler = Mock(return_value=AgentResult("done"))
    with patch("agent_eval.worker.HttpTransport") as transport:
        worker = EvaluationWorker.from_config(config, handler=handler)
        transport.assert_called_once_with(
            "https://platform.example", "private-fixture-key", "release-2", None,
            ca_cert=tmp_path / "certs/platform.crt")
        assert worker._handlers == {"agent-one": handler}
        assert (tmp_path / "state/events.sqlite").is_file()
        assert worker._owns_resources
        worker.stop()
        transport.return_value.close.assert_called_once()


def test_config_errors_do_not_leak_credentials_or_create_spool(tmp_path):
    import json
    import pytest
    config = tmp_path / "agent-eval.local.json"
    for content in [
        {"api_key": "never-print-this"},
        {"platform_url": "https://platform.example", "api_key": "never-print-this", "agent_id": 123},
        ["never-print-this"],
    ]:
        config.write_text(json.dumps(content), encoding="utf-8")
        with pytest.raises(ValueError) as failure:
            EvaluationWorker.from_config(config, handler=Mock())
        assert "never-print-this" not in str(failure.value)
        assert not (tmp_path / ".agent-eval").exists()


def test_config_defaults_and_transport_failure_close_spool(tmp_path):
    import json
    from unittest.mock import patch
    import pytest
    config = tmp_path / "agent-eval.local.json"
    config.write_text(json.dumps({"platform_url": "https://platform.example", "api_key": "key", "agent_id": "agent-one"}), encoding="utf-8")
    with patch("agent_eval.worker.HttpTransport") as transport:
        worker = EvaluationWorker.from_config(config, handler=Mock())
        transport.assert_called_once_with("https://platform.example", "key", "v1", None, ca_cert=None)
        worker.stop()
    with patch("agent_eval.worker.FileSpool") as spool, patch("agent_eval.worker.HttpTransport", side_effect=ValueError("Invalid platform URL")):
        with pytest.raises(ValueError):
            EvaluationWorker.from_config(config, handler=Mock())
        spool.return_value.close.assert_called_once()
