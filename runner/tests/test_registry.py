from agent_eval_runner.registry import RunRegistry


def test_scrubs_private_cache_but_keeps_replay_tombstone_across_restart(tmp_path):
    path = tmp_path / "runs.sqlite"
    registry = RunRegistry(path)
    assert registry.reserve("job", "digest")
    registry.complete("job", {"job_id": "job", "state": "COMPLETED", "result": {"reason": "PRIVATE_REFERENCE"}, "stdout": "PRIVATE_TASK_INPUT", "stderr": "PRIVATE_TOOL_OUTPUT"})
    registry.scrub("job")
    result = RunRegistry(path).lookup("job", "digest")
    assert result["code"] == "EVIDENCE_DELETED" and result["result"] is None
    assert "stdout" not in result and "stderr" not in result
    assert not registry.reserve("job", "digest")
    assert b"PRIVATE_" not in path.read_bytes()


def test_delete_fences_late_completion_and_not_yet_received_job(tmp_path):
    registry = RunRegistry(tmp_path / "runs.sqlite")
    assert registry.reserve("running", "digest")
    registry.scrub("running")
    registry.complete("running", {"stdout": "PRIVATE_LATE_RESPONSE"})
    assert registry.lookup("running", "digest")["code"] == "EVIDENCE_DELETED"
    registry.scrub("unreceived")
    assert not registry.reserve("unreceived", "future-digest")
    assert registry.lookup("unreceived", "future-digest")["code"] == "EVIDENCE_DELETED"


def test_crash_recovery_preserves_unknown_execution_without_replaying(tmp_path):
    registry = RunRegistry(tmp_path / "runs.sqlite")
    assert registry.reserve("job", "digest")
    assert registry.lookup("job", "digest")["state"] == "IN_PROGRESS"
    assert registry.recover_unknown() == ["job"]
    result = RunRegistry(tmp_path / "runs.sqlite").lookup("job", "digest")
    assert result["code"] == "EXECUTION_UNKNOWN"
    assert result["result"] is None
    assert not registry.reserve("job", "digest")
