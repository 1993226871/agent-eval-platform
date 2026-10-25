import asyncio
from uuid import uuid4
from agent_eval import observe, span
from agent_eval.telemetry import TelemetrySession
from agent_eval.spool import FileSpool


def test_contextvars_keep_parallel_spans_separate(tmp_path):
    async def scenario():
        with FileSpool(tmp_path / "queue.sqlite") as spool:
            @observe("tool")
            async def lookup(value):
                await asyncio.sleep(0)
                with span("custom", "nested"):
                    await asyncio.sleep(0)
                return value

            async def task():
                session = TelemetrySession(str(uuid4()), uuid4().hex, spool)
                with session.activate():
                    with span("agent", "root"):
                        assert await lookup(42) == 42
                return spool.pending(session.trial_id)

            first, second = await asyncio.gather(task(), task())
            assert {e["trial_id"] for e in first}.isdisjoint({e["trial_id"] for e in second})
            for events in (first, second):
                starts = {e["name"]: e for e in events if e["event_type"] == "span.started"}
                assert starts["lookup"]["parent_span_id"] == starts["root"]["span_id"]
                assert starts["nested"]["parent_span_id"] == starts["lookup"]["span_id"]
    asyncio.run(scenario())


def test_redaction_and_capture_failure_do_not_change_output(tmp_path):
    with FileSpool(tmp_path / "queue.sqlite", max_bytes=2048) as spool:
        session = TelemetrySession(str(uuid4()), uuid4().hex, spool, secrets=("private-value",))
        @observe("model")
        def model():
            return {"api_key": "private-value", "text": "x" * 100000}
        with session.activate():
            result = model()
        assert result["api_key"] == "private-value"
        assert "private-value" not in repr(spool.pending(session.trial_id))
        assert b"private-value" not in (tmp_path / "queue.sqlite").read_bytes()
        assert any(e["capture"]["truncated"] for e in spool.pending(session.trial_id)) or spool.stats(session.trial_id).dropped_count > 0


def test_outside_worker_is_noop_and_exceptions_are_preserved():
    @observe("tool")
    def fail():
        raise ValueError("business failure")
    try:
        fail()
    except ValueError as exc:
        assert str(exc) == "business failure"
    else:
        raise AssertionError("exception suppressed")


def test_configured_secrets_are_removed_from_payload_keys_and_span_names(tmp_path):
    with FileSpool(tmp_path / "queue.sqlite") as spool:
        session = TelemetrySession(str(uuid4()), uuid4().hex, spool, secrets=("private-value",))
        with session.activate():
            with span("tool", "lookup-private-value", {"private-value": "other"}):
                pass
        assert "private-value" not in repr(spool.pending(session.trial_id))


def test_packaged_schemas_match_repository_contracts():
    from pathlib import Path
    from importlib.resources import files
    root = Path(__file__).resolve().parents[3] / "contracts" / "schemas"
    for schema in root.glob("*.json"):
        assert files("agent_eval").joinpath("schemas", schema.name).read_text(encoding="utf-8") == schema.read_text(encoding="utf-8")
