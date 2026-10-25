from agent_eval_runner.registry import RunRegistry


def test_crash_recovery_preserves_unknown_execution_without_replaying(tmp_path):
    registry = RunRegistry(tmp_path / "runs.sqlite")
    assert registry.reserve("job", "digest")
    assert registry.lookup("job", "digest")["state"] == "IN_PROGRESS"
    assert registry.recover_unknown() == ["job"]
    result = RunRegistry(tmp_path / "runs.sqlite").lookup("job", "digest")
    assert result["code"] == "EXECUTION_UNKNOWN"
    assert result["result"] is None
    assert not registry.reserve("job", "digest")
