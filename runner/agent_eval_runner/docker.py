"""Trusted Docker control adapter. Never calls a shell or runs evaluator code on host."""
import json
import os
import re
import subprocess
import threading
import time
from .executor import RawSandboxResult


class DockerControlTimeout(RuntimeError):
    pass


class BoundedLog:
    def __init__(self):
        self.data = bytearray()
        self.truncated = False

    def drain(self, stream):
        try:
            while chunk := stream.read(8192):
                remaining = max(0, 65536 - len(self.data))
                self.data.extend(chunk[:remaining])
                self.truncated |= len(chunk) > remaining
        finally:
            stream.close()


class DockerDriver:
    def __init__(self, owner):
        self.owner = owner
        self.hidden = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        self.active = set()
        self.lock = threading.Lock()

    def _command(self, arguments, timeout=5, limit=1024 * 1024, required=True):
        try:
            result = subprocess.run(arguments, capture_output=True, timeout=timeout, **self.hidden)
        except subprocess.TimeoutExpired:
            raise DockerControlTimeout("Docker control request timed out") from None
        except (OSError, subprocess.SubprocessError):
            raise RuntimeError("Docker control request failed") from None
        if len(result.stdout) > limit or len(result.stderr) > limit or (required and result.returncode != 0):
            raise RuntimeError("Docker control request rejected")
        return result

    def info(self):
        return json.loads(self._command(["docker", "info", "--format", "{{json .}}"]).stdout)

    def image_id(self, reference):
        result = self._command(["docker", "image", "inspect", "--format", "{{.Id}}", reference], required=False)
        identity = result.stdout.decode().strip()
        return identity if result.returncode == 0 and re.fullmatch(r"sha256:[0-9a-f]{64}", identity) else None

    def _inspect(self, identity):
        return json.loads(self._command(["docker", "inspect", "--format", "{{json .}}", identity]).stdout)

    def _owned(self, inspection):
        labels = inspection.get("Config", {}).get("Labels") or {}
        return labels.get("io.agenteval.runner") == self.owner and inspection.get("HostConfig", {}).get("Runtime") == "runsc"

    def cleanup_owned(self):
        result = self._command(["docker", "ps", "--all", "--quiet", "--no-trunc", "--filter", "label=io.agenteval.runner=" + self.owner])
        for identity in result.stdout.decode().splitlines():
            if not re.fullmatch(r"[0-9a-f]{64}", identity) or not self._owned(self._inspect(identity)):
                raise RuntimeError("Runner container ownership mismatch")
            self._command(["docker", "rm", "--force", identity])

    def cleanup_job(self, job):
        from uuid import UUID
        job = str(UUID(job))
        result = self._command(["docker", "ps", "--all", "--quiet", "--no-trunc", "--filter", "label=io.agenteval.runner=" + self.owner, "--filter", "label=io.agenteval.job=" + job])
        for identity in result.stdout.decode().splitlines():
            if not re.fullmatch(r"[0-9a-f]{64}", identity):
                raise RuntimeError("Invalid Runner container identity")
            inspection = self._inspect(identity)
            if not self._owned(inspection) or inspection.get("Config", {}).get("Labels", {}).get("io.agenteval.job") != job:
                raise RuntimeError("Runner job ownership mismatch")
            self._command(["docker", "rm", "--force", identity])

    def run_sandbox(self, arguments, input_dir, timeout_seconds):
        identity = None
        attached = None
        readers = []
        stdout, stderr = BoundedLog(), BoundedLog()
        raw, code = None, "NO_RESULT"
        try:
            identity = self._command(arguments).stdout.decode().strip()
            if not re.fullmatch(r"[0-9a-f]{64}", identity):
                raise RuntimeError("Invalid container identity")
            inspection = self._inspect(identity)
            if not self._owned(inspection) or inspection.get("Image") != self.image_id(arguments[-1]):
                raise RuntimeError("Unapproved container runtime or image")
            with self.lock:
                self.active.add(identity)
            attached = subprocess.Popen(["docker", "start", "--attach", identity], stdout=subprocess.PIPE, stderr=subprocess.PIPE, **self.hidden)
            for stream, log in ((attached.stdout, stdout), (attached.stderr, stderr)):
                reader = threading.Thread(target=log.drain, args=(stream,), daemon=True)
                readers.append(reader)
                reader.start()
            deadline = time.monotonic() + timeout_seconds
            while time.monotonic() < deadline:
                # Fixed fresh interpreter, separate channel, no inherited evaluator Python namespace.
                try:
                    result = self._command(["docker", "exec", "--user", "10001:10001", identity, "/usr/local/bin/python", "-I", "/opt/runner/read_result.py"], timeout=min(3, max(0.05, deadline - time.monotonic())), limit=65536, required=False)
                except DockerControlTimeout:
                    if time.monotonic() >= deadline:
                        code = "TIMEOUT"
                        break
                    continue
                if result.returncode == 0:
                    raw, code = result.stdout, "COMPLETED"
                    try:
                        attached.wait(timeout=min(2, max(0.05, deadline - time.monotonic())))
                    except subprocess.TimeoutExpired:
                        code = "PROCESS_EXIT_TIMEOUT"
                    break
                if attached.poll() is not None:
                    break
                time.sleep(min(0.1, max(0, deadline - time.monotonic())))
            else:
                code = "TIMEOUT"
            if code == "TIMEOUT":
                self._command(["docker", "kill", identity], required=False)
            state = self._inspect(identity).get("State", {})
            if state.get("OOMKilled"):
                code = "OUT_OF_MEMORY"
            elif code == "COMPLETED" and state.get("ExitCode") != 0:
                code = "PROCESS_ERROR"
            if code != "COMPLETED":
                raw = None
        finally:
            if identity is not None and re.fullmatch(r"[0-9a-f]{64}", identity):
                # Identity came from this create call; still verify ownership before removal.
                inspection = self._inspect(identity)
                if not self._owned(inspection):
                    raise RuntimeError("Refusing to remove an unowned container")
                self._command(["docker", "rm", "--force", identity])
                with self.lock:
                    self.active.discard(identity)
            if attached is not None:
                if attached.poll() is None:
                    attached.kill()
                attached.wait(timeout=3)
            for reader in readers:
                reader.join(timeout=2)
        return RawSandboxResult(raw, bytes(stdout.data), bytes(stderr.data), code, stdout.truncated, stderr.truncated)
