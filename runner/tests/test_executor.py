from pathlib import Path
from agent_eval_runner.executor import RunnerExecutor, RunnerSettings, RawSandboxResult
from helpers import IMAGE, request


class FakeDocker:
    def __init__(self, runtimes=("runsc",)):
        self.runtimes = runtimes
        self.calls = []
    def info(self):
        return {"OSType": "linux", "Runtimes": {name: {} for name in self.runtimes}}
    def image_id(self, image):
        return IMAGE
    def run_sandbox(self, arguments, input_dir, timeout_seconds):
        self.calls.append(arguments)
        return RawSandboxResult(b'{"status":"SCORED","score":1,"reason":"Compared","evidence_refs":["output"]}', b"printed noise", b"", "COMPLETED", False, False)


def executor(tmp_path, docker=None):
    return RunnerExecutor(RunnerSettings(tmp_path, IMAGE), docker=docker or FakeDocker(), host_is_linux=lambda: True)


def test_no_runsc_disables_execution(tmp_path):
    docker = FakeDocker(("runc",))
    result = executor(tmp_path, docker).execute(request())
    assert result["code"] == "CAPABILITY_UNAVAILABLE"
    assert result["result"] is None
    assert docker.calls == []


def test_completed_run_remains_queryable_when_runtime_becomes_unavailable(tmp_path):
    docker = FakeDocker()
    engine = executor(tmp_path, docker)
    payload = request()
    first = engine.execute(payload)
    docker.runtimes = ()
    assert engine.execute(payload) == first
    assert len(docker.calls) == 1


def test_pinned_hardened_command_and_idempotent_run_key(tmp_path):
    docker = FakeDocker()
    engine = executor(tmp_path, docker)
    payload = request()
    first = engine.execute(payload)
    second = executor(tmp_path, docker).execute(payload)
    assert first == second
    assert first["result"]["score"] == 1
    assert len(docker.calls) == 1
    arguments = docker.calls[0]
    for flag in ("--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network=none", "--runtime=runsc", "--pids-limit=32", "--memory=256m", "--memory-swap=256m", "--user=10001:10001", "--log-driver=none"):
        assert flag in arguments
    assert "--privileged" not in arguments
    assert IMAGE in arguments
    assert any("/tmp:rw" in value and "size=64m" in value for value in arguments)
    assert any("/output:rw" in value and "size=1m" in value for value in arguments)


def test_conflicting_reuse_and_symlink_attachment_are_rejected(tmp_path):
    import pytest
    from agent_eval_runner.validation import RunnerValidationError, safe_artifact_file
    engine = executor(tmp_path)
    payload = request()
    engine.execute(payload)
    changed = request("def evaluate(context):\n    return {}\n")
    changed["job_id"] = payload["job_id"]
    with pytest.raises(RunnerValidationError, match="RUN_KEY_CONFLICT"):
        engine.execute(changed)
    artifacts = tmp_path / "artifacts"
    artifacts.mkdir()
    outside = tmp_path / "outside.txt"
    outside.write_text("outside")
    with pytest.raises(RunnerValidationError):
        safe_artifact_file(artifacts, "../outside.txt")
    try:
        (artifacts / "11111111-1111-4111-8111-111111111111").symlink_to(outside)
    except OSError:
        return  # Traversal is still checked above; actual symlink denial is mandatory on Linux integration.
    with pytest.raises(RunnerValidationError):
        safe_artifact_file(artifacts, "11111111-1111-4111-8111-111111111111")


def test_code_hash_and_resource_escalation_are_rejected_before_docker(tmp_path):
    import pytest
    from agent_eval_runner.validation import RunnerValidationError
    docker = FakeDocker()
    engine = executor(tmp_path, docker)
    payload = request()
    payload["code_hash"] = "0" * 64
    with pytest.raises(RunnerValidationError):
        engine.execute(payload)
    payload = request()
    payload["limits"]["memory_mb"] = 1024
    with pytest.raises(RunnerValidationError):
        engine.execute(payload)
    assert docker.calls == []
