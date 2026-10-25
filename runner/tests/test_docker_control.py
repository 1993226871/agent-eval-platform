import subprocess
from agent_eval_runner.docker import DockerDriver, DockerControlTimeout


def test_result_channel_timeout_kills_owned_container_and_reports_timeout(monkeypatch, tmp_path):
    driver = DockerDriver("owner")
    identity = "b" * 64
    commands = []
    inspection = {"Config":{"Labels":{"io.agenteval.runner":"owner"}}, "HostConfig":{"Runtime":"runsc"}, "Image":"image", "State":{"ExitCode":0}}
    monkeypatch.setattr(driver, "_inspect", lambda _: inspection)
    monkeypatch.setattr(driver, "image_id", lambda _: "image")
    def command(args, **kwargs):
        commands.append(args)
        if args[:2] == ["docker", "create"]:
            return subprocess.CompletedProcess(args, 0, identity.encode(), b"")
        if args[:2] == ["docker", "exec"]:
            raise DockerControlTimeout("timed out")
        return subprocess.CompletedProcess(args, 0, b"", b"")
    monkeypatch.setattr(driver, "_command", command)
    import io
    class Attached:
        stdout, stderr = io.BytesIO(), io.BytesIO()
        def poll(self): return None
        def kill(self): pass
        def wait(self, timeout=None): return 0
    monkeypatch.setattr("agent_eval_runner.docker.subprocess.Popen", lambda *a, **k: Attached())
    ticks = iter([0, 0, 0, 31])
    monkeypatch.setattr("agent_eval_runner.docker.time.monotonic", lambda: next(ticks, 31))
    result = driver.run_sandbox(["docker", "create", "image"], tmp_path, 30)
    assert result.code == "TIMEOUT"
    assert ["docker", "kill", identity] in commands
    assert ["docker", "rm", "--force", identity] in commands
