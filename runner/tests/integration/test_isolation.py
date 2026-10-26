"""Acceptance gate: absence of a hardened node is a failure, never a green skip."""
import os
import json
from pathlib import Path
import sys
import time
import pytest
from agent_eval_runner.executor import RunnerExecutor, RunnerSettings
from helpers import request

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def engine(tmp_path_factory):
    image = os.environ.get("RUNNER_TEST_IMAGE", "")
    if sys.platform != "linux" or not image:
        pytest.fail("NOT VERIFIED: Linux/runsc node and RUNNER_TEST_IMAGE digest are required")
    executor = RunnerExecutor(RunnerSettings(tmp_path_factory.mktemp("runner-isolation"), image))
    if not executor.capabilities()["python_enabled"]:
        pytest.fail("NOT VERIFIED: " + executor.capabilities()["reason"])
    # Synthetic fixtures only: retain bounded control metadata, never context or result bodies.
    from collections import deque
    executor.control_diagnostics = deque(maxlen=20)
    command, inspect = executor.docker._command, executor.docker._inspect
    def traced_command(arguments, **kwargs):
        result = command(arguments, **kwargs)
        if arguments[:2] == ["docker", "exec"]:
            executor.control_diagnostics.append({"reader_exit": result.returncode, "output_bytes": len(result.stdout), "control_error": result.stderr[:512].decode(errors="replace"), "fixture_control_output": result.stdout[:512].decode(errors="replace") if result.returncode else "result omitted"})
        return result
    def traced_inspect(identity):
        result = inspect(identity)
        state = result.get("State", {})
        executor.control_diagnostics.append({key: state.get(key) for key in ("Status", "ExitCode", "OOMKilled", "Error")})
        return result
    executor.docker._command, executor.docker._inspect = traced_command, traced_inspect
    return executor


def run(engine, code):
    payload = request(code)
    payload["image_digest"] = engine.settings.image_digest
    return engine.execute(payload)


def test_timeout(engine):
    start = time.monotonic()
    result = run(engine, "def evaluate(context):\n    while True: pass\n")
    assert result["code"] == "TIMEOUT"
    assert result["result"] is None
    assert 29 <= time.monotonic() - start < 45


def test_memory_limit(engine):
    result = run(engine, "def evaluate(context):\n    chunks = []\n    while True: chunks.append(bytearray(16 * 1024 * 1024))\n")
    assert result["code"] == "OUT_OF_MEMORY"
    assert result["result"] is None


def test_network_filesystem_process_and_secret_boundaries(engine):
    code = '''def evaluate(context):
    import os, socket, subprocess
    leaked = [key for key in os.environ if key.startswith(("RUNNER_", "AWS_", "DATABASE_", "PLATFORM_"))]
    assert leaked == []
    assert os.getuid() == 10001
    assert not os.path.exists("/var/run/docker.sock")
    assert not os.path.exists("/run/containerd/containerd.sock")
    try:
        socket.create_connection(("1.1.1.1", 443), timeout=1)
        raise AssertionError("network accessible")
    except OSError: pass
    try:
        open("/input/context.json", "wb")
        raise AssertionError("writable input")
    except OSError: pass
    try:
        open("/opt/runner/escape", "wb")
        raise AssertionError("writable root")
    except OSError: pass
    try:
        with open("/tmp/full", "wb") as file:
            for _ in range(70): file.write(b"x" * 1024 * 1024)
        raise AssertionError("tmp limit missing")
    except OSError: pass
    finally:
        if os.path.exists("/tmp/full"): os.unlink("/tmp/full")
    children = []
    try:
        for _ in range(40):
            children.append(subprocess.Popen(["/usr/local/bin/python", "-I", "-c", "import time;time.sleep(15)"]))
        raise AssertionError("process limit missing")
    except OSError: pass
    finally:
        for child in children: child.kill()
        for child in children: child.wait()
    return {"status":"SCORED", "score":1, "reason":"Isolation boundaries checked", "evidence_refs":["output"]}
'''
    result = run(engine, code)
    assert result["state"] == "COMPLETED", json.dumps({"result": result, "control": list(engine.control_diagnostics)}, ensure_ascii=False)
    assert result["result"]["score"] == 1


def test_bounded_logs_and_independent_result(engine):
    result = run(engine, '''def evaluate(context):
    import sys
    print("x" * 200000)
    print("y" * 200000, file=sys.stderr)
    return {"status":"SCORED", "score":1, "reason":"Independent result", "evidence_refs":["output"]}
''')
    assert result["state"] == "COMPLETED", json.dumps(result, ensure_ascii=False)
    assert len(result["stdout"].encode()) <= 65536
    assert len(result["stderr"].encode()) <= 65536
    assert result["stdout_truncated"] and result["stderr_truncated"]


def test_host_artifact_symlink_denied(engine, tmp_path):
    from uuid import uuid4
    from agent_eval_runner.validation import safe_artifact_file, RunnerValidationError
    root = tmp_path / "artifacts"
    root.mkdir()
    outside = tmp_path / "secret"
    outside.write_text("not an artifact")
    identity = str(uuid4())
    (root / identity).symlink_to(outside)
    with pytest.raises(RunnerValidationError):
        safe_artifact_file(root, identity)


def test_deleted_private_result_is_scrubbed_and_cannot_replay(engine):
    from uuid import uuid4
    marker = "private-evidence-" + uuid4().hex
    payload = request('def evaluate(context):\n    print(' + repr(marker) + ')\n    return {"status":"SCORED","score":1,"reason":"Checked","evidence_refs":["output"]}\n')
    payload["image_digest"] = engine.settings.image_digest
    response = engine.execute(payload)
    assert response["state"] == "COMPLETED" and marker in response["stdout"]
    engine.scrub(payload["job_id"])
    assert engine.execute(payload)["code"] == "EVIDENCE_DELETED"
    assert marker.encode() not in engine.registry.path.read_bytes()
