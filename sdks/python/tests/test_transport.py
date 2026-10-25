import json
from datetime import datetime, timedelta, timezone
from threading import Event
from uuid import uuid4
import httpx
import pytest
from agent_eval import AgentResult, EvaluationWorker
from agent_eval.spool import FileSpool
from agent_eval.transport import HttpTransport, ProtocolError, TransportUnavailable
from test_worker import grant


def test_lost_event_ack_replays_identical_ids_and_stops_heartbeat(tmp_path):
    assignment = grant()
    requests, event_batches = [], []
    dropped = [False]
    def server(request):
        requests.append(request)
        assert request.headers["authorization"] == "Bearer ae_private"
        if request.url.path.endswith("/started"):
            return httpx.Response(204)
        if request.url.path.endswith("/events:batch"):
            batch = json.loads(request.content)["events"]
            event_batches.append(batch)
            if not dropped[0]:
                dropped[0] = True
                raise httpx.ReadTimeout("lost ack after commit", request=request)
            return httpx.Response(200, json={"acknowledged_event_ids": [e["event_id"] for e in batch]})
        if request.url.path.endswith("/complete"):
            return httpx.Response(204)
        raise AssertionError("Unexpected request")
    transport = HttpTransport("https://platform.test", "ae_private", client=httpx.Client(transport=httpx.MockTransport(server)))
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        worker.register_agent("agent-one", lambda ctx: AgentResult(42))
        worker.execute("agent-one", assignment)
        assert len(spool.pending(assignment["trial_id"])) == 2
        worker.execute("agent-one", assignment)
        assert event_batches[0] == event_batches[1]
        assert spool.pending(assignment["trial_id"]) == []
        assert len([r for r in requests if r.url.path.endswith("/started")]) == 1
        assert worker.active_heartbeat_count == 0
    transport.close()


def test_error_does_not_echo_platform_secrets():
    transport = HttpTransport("https://platform.test", "ae_private", client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(401, text="ae_private secret-attempt"))))
    with pytest.raises(ProtocolError) as rejected:
        transport.started(grant())
    assert "ae_private" not in str(rejected.value)
    assert "secret-attempt" not in str(rejected.value)
    transport.close()


def test_each_registered_agent_has_a_distinct_instance_identity():
    instances = []
    def server(request):
        instances.append(json.loads(request.content)["instance_id"])
        return httpx.Response(200, json={"worker_id": str(uuid4()), "heartbeat_seconds": 15, "lease_seconds": 60})
    transport = HttpTransport("https://platform.test", "ae_private", instance_id="same-process", client=httpx.Client(transport=httpx.MockTransport(server)))
    transport.register(str(uuid4()))
    transport.register(str(uuid4()))
    assert len(set(instances)) == 2
    transport.close()


def test_deadline_cancels_before_fifteen_second_heartbeat(tmp_path):
    from test_worker import FakeTransport
    transport = FakeTransport()
    transport.heartbeat_seconds = 15
    assignment = grant()
    assignment["task"]["deadline"] = (datetime.now(timezone.utc) + timedelta(milliseconds=100)).isoformat()
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        observed = []
        def handler(context):
            observed.append(context.cancellation.wait(1))
            return AgentResult(42)
        worker.register_agent("agent-one", handler)
        worker.execute("agent-one", assignment)
        assert observed == [True]
        assert transport.completions[0]["status"] == "TIMED_OUT"


def test_running_worker_stops_on_revoked_attempt_without_reinvoking_handler(tmp_path):
    assignment = grant()
    claims, calls, observed = [], [], []
    finished = Event()
    def server(request):
        if request.url.path.endswith("/register"):
            return httpx.Response(200, json={"worker_id": str(uuid4()), "heartbeat_seconds": 15, "lease_seconds": 60})
        if request.url.path.endswith("/claim"):
            claims.append(True)
            return httpx.Response(200, json=assignment) if len(claims) == 1 else httpx.Response(401)
        if request.url.path.endswith("/started"):
            return httpx.Response(204)
        if request.url.path.endswith("/heartbeat"):
            return httpx.Response(409)
        if request.url.path.endswith("/events:batch"):
            return httpx.Response(409)
        if request.url.path.endswith("/complete"):
            raise AssertionError("Lost ownership must not complete")
        raise AssertionError("Unexpected request")
    transport = HttpTransport("https://platform.test", "ae_private", client=httpx.Client(transport=httpx.MockTransport(server)))
    transport.heartbeat_seconds = 0.01
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        worker = EvaluationWorker(transport, spool)
        def handler(context):
            calls.append(context.trial_id)
            observed.append(context.cancellation.wait(1))
            finished.set()
            return AgentResult(42)
        worker.register_agent(str(uuid4()), handler).start()
        assert finished.wait(2)
        worker.stop()
        assert observed == [True]
        assert calls == [assignment["trial_id"]]
        assert worker.active_heartbeat_count == 0
        assert all(not poller.is_alive() for poller in worker._pollers)
    transport.close()
