import hashlib
import os
import re
import shutil
from pathlib import Path
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from urllib.parse import urlsplit
from uuid import UUID
import httpx
from .registry import RunRegistry
from .validation import IMAGE_PATTERN, RunnerValidationError, encoded, request_hash, safe_artifact_file, validate_request, validate_result


@dataclass(frozen=True)
class RunnerSettings:
    work_root: Path
    image_digest: str
    runtime: str = "runsc"
    artifact_origins: tuple[str, ...] = ()
    max_concurrency: int = 2

    def __post_init__(self):
        if self.runtime != "runsc" or not 1 <= self.max_concurrency <= 4:
            raise ValueError("Invalid hardened Runner policy")
        if self.image_digest and not IMAGE_PATTERN.fullmatch(self.image_digest):
            raise ValueError("A pinned sandbox image is required")
        if "," in str(self.work_root) or "\n" in str(self.work_root):
            raise ValueError("Invalid Runner work directory")


@dataclass(frozen=True)
class RawSandboxResult:
    result: bytes | None
    stdout: bytes
    stderr: bytes
    code: str
    stdout_truncated: bool
    stderr_truncated: bool


class RunnerExecutor:
    def __init__(self, settings, docker=None, host_is_linux=None):
        self.settings = settings
        self.root = Path(settings.work_root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.jobs = self.root / "jobs"
        self.jobs.mkdir(exist_ok=True, mode=0o700)
        if self.jobs.is_symlink() or not self.jobs.resolve().is_relative_to(self.root):
            raise ValueError("Invalid Runner jobs directory")
        self.registry = RunRegistry(self.root / "runs.sqlite")
        os.chmod(self.root / "runs.sqlite", 0o600)
        if docker is None:
            from .docker import DockerDriver
            docker = DockerDriver(hashlib.sha256(str(self.root).encode()).hexdigest()[:16])
        self.docker = docker
        self.host_is_linux = host_is_linux or (lambda: sys.platform.startswith("linux"))
        self.slots = threading.BoundedSemaphore(settings.max_concurrency)
        self.activity = threading.Condition()
        self.active_jobs = set()

    def capabilities(self):
        enabled, reason = False, "A Linux execution node is required"
        if self.host_is_linux() and self.settings.image_digest:
            try:
                info = self.docker.info()
                if info.get("OSType") != "linux":
                    reason = "The Docker daemon must run Linux containers"
                elif self.settings.runtime not in info.get("Runtimes", {}):
                    reason = "The hardened runtime is not registered"
                elif not self.docker.image_id(self.settings.image_digest):
                    reason = "The pinned sandbox image is unavailable"
                else:
                    enabled, reason = True, "Configured hardened runtime and pinned image are available"
            except (RuntimeError, OSError):
                reason = "The hardened execution service is unavailable"
        elif self.host_is_linux():
            reason = "A pinned sandbox image is not configured"
        return {"python_enabled": enabled, "code": "AVAILABLE" if enabled else "CAPABILITY_UNAVAILABLE", "reason": reason, "runtime": self.settings.runtime, "image_digest": self.settings.image_digest or None}

    def execute(self, payload):
        # A cached outcome survives temporary daemon/runtime failure. Never re-execute it.
        if self.settings.image_digest:
            validate_request(payload, self.settings.image_digest)
            identity, digest = str(UUID(payload["job_id"])), request_hash(payload)
            existing = self.registry.lookup(identity, digest)
            if existing is not None:
                return existing
        capability = self.capabilities()
        if not capability["python_enabled"]:
            return {"job_id": payload.get("job_id") if isinstance(payload, dict) else None, "state": "UNAVAILABLE", "code": "CAPABILITY_UNAVAILABLE", "result": None}
        if not self.slots.acquire(blocking=False):
            return {"job_id": identity, "state": "BUSY", "code": "CAPACITY_REACHED", "result": None}
        reserved = False
        try:
            with self.activity:
                if not self.registry.reserve(identity, digest):
                    return self.registry.lookup(identity, digest)
                self.active_jobs.add(identity)
                reserved = True
            response = self._execute_reserved(identity, payload)
            self.registry.complete(identity, response)
            return self.registry.lookup(identity, digest)
        finally:
            if reserved:
                with self.activity:
                    self.active_jobs.discard(identity)
                    self.activity.notify_all()
            self.slots.release()

    def _remove_inputs(self, identity=None):
        prefix = UUID(identity).hex + "-" if identity is not None else None
        for path in self.jobs.iterdir():
            if not re.fullmatch(r"[0-9a-f]{32}-[A-Za-z0-9_-]+", path.name) or (prefix is not None and not path.name.startswith(prefix)):
                continue
            if path.parent.resolve() != self.jobs.resolve():
                raise RuntimeError("Invalid Runner cleanup identity")
            if path.is_symlink() or path.is_file():
                path.unlink()
            elif path.is_dir():
                shutil.rmtree(path)

    def scrub(self, identity):
        identity = str(UUID(identity))
        with self.activity:
            self.registry.scrub(identity)
            if not self.activity.wait_for(lambda: identity not in self.active_jobs, timeout=60):
                raise RuntimeError("Runner deletion waits for active input cleanup")
            # No new execution of this identity can reserve after the durable tombstone.
            self.docker.cleanup_job(identity)
            self._remove_inputs(identity)

    def recover_abandoned(self):
        # Called only while the service-wide lock is held, before accepting requests.
        with self.activity:
            if self.active_jobs:
                raise RuntimeError("Cannot recover a live Runner")
            self.docker.cleanup_owned()
            self._remove_inputs()
            self.registry.recover_unknown()

    def _execute_reserved(self, identity, payload):
        try:
            with tempfile.TemporaryDirectory(prefix=UUID(identity).hex + "-", dir=self.jobs) as directory:
                job_dir = Path(directory).resolve()
                if not job_dir.is_relative_to(self.jobs.resolve()):
                    raise RunnerValidationError("INVALID_JOB_DIRECTORY")
                inputs = job_dir / "input"
                inputs.mkdir(mode=0o755)
                (inputs / "artifacts").mkdir(mode=0o755)
                (inputs / "evaluator.py").write_bytes(payload["code"].encode("utf-8"))
                (inputs / "context.json").write_bytes(encoded(payload["context"]))
                self._download_artifacts(payload, inputs / "artifacts")
                for path in inputs.rglob("*"):
                    if path.is_file():
                        os.chmod(path, 0o444)
                arguments = self.command(identity, inputs, payload["limits"])
                raw = self.docker.run_sandbox(arguments, inputs, payload["limits"]["timeout_seconds"])
                response = {"job_id": identity, "state": "ERROR", "code": raw.code, "result": None,
                            "stdout": raw.stdout[:65536].decode("utf-8", errors="replace"), "stderr": raw.stderr[:65536].decode("utf-8", errors="replace"),
                            "stdout_truncated": raw.stdout_truncated or len(raw.stdout) > 65536, "stderr_truncated": raw.stderr_truncated or len(raw.stderr) > 65536,
                            "runtime": self.settings.runtime, "image_digest": self.settings.image_digest}
                if raw.code == "COMPLETED" and raw.result is not None:
                    response["result"] = validate_result(raw.result, payload["context"])
                    response["state"], response["code"] = "COMPLETED", "COMPLETED"
                return response
        except RunnerValidationError as rejected:
            return {"job_id": identity, "state": "ERROR", "code": rejected.code, "result": None}
        except (RuntimeError, OSError):
            return {"job_id": identity, "state": "ERROR", "code": "SANDBOX_EXECUTION_FAILED", "result": None}

    def command(self, identity, inputs, limits):
        owner = hashlib.sha256(str(self.root).encode()).hexdigest()[:16]
        return ["docker", "create", "--name", "aev-" + owner + "-" + UUID(identity).hex,
                "--label", "io.agenteval.runner=" + owner, "--label", "io.agenteval.job=" + identity,
                "--runtime=runsc", "--read-only", "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user=10001:10001", "--pids-limit=" + str(limits["processes"]), "--memory=" + str(limits["memory_mb"]) + "m", "--memory-swap=" + str(limits["memory_mb"]) + "m", "--cpus=1", "--shm-size=16m", "--log-driver=none", "--pull=never",
                "--mount", "type=bind,src=" + str(inputs) + ",dst=/input,readonly",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=" + str(limits["tmp_mb"]) + "m,mode=1777",
                "--tmpfs", "/output:rw,noexec,nosuid,nodev,size=1m,mode=0700,uid=10001,gid=10001",
                self.settings.image_digest]

    def _download_artifacts(self, payload, root):
        references = {item["artifact_id"]: item for item in payload["context"]["artifacts"]}
        deadline = time.monotonic() + 20
        for item in payload["artifact_downloads"]:
            uri = urlsplit(item["url"])
            origin = uri.scheme + "://" + uri.netloc
            if uri.scheme not in {"https", "http"} or uri.username or uri.password or uri.fragment or origin not in self.settings.artifact_origins:
                raise RunnerValidationError("ARTIFACT_ORIGIN_NOT_ALLOWED")
            metadata = references[item["artifact_id"]]
            destination = root / str(UUID(item["artifact_id"]))
            flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0)
            total, digest = 0, hashlib.sha256()
            try:
                with httpx.Client(timeout=httpx.Timeout(5, connect=3), follow_redirects=False, trust_env=False) as client:
                    with client.stream("GET", item["url"], headers={"Accept-Encoding": "identity"}) as response, os.fdopen(os.open(destination, flags, 0o600), "wb") as output:
                        if response.status_code != 200:
                            raise RunnerValidationError("ARTIFACT_DOWNLOAD_FAILED")
                        for chunk in response.iter_raw(chunk_size=65536):
                            total += len(chunk)
                            if total > metadata["size_bytes"] or time.monotonic() > deadline:
                                raise RunnerValidationError("ARTIFACT_SIZE_OR_TIMEOUT")
                            digest.update(chunk)
                            output.write(chunk)
            except httpx.HTTPError:
                raise RunnerValidationError("ARTIFACT_DOWNLOAD_FAILED") from None
            if total != metadata["size_bytes"] or digest.hexdigest() != metadata["sha256"]:
                raise RunnerValidationError("ARTIFACT_CHECKSUM_MISMATCH")
            safe_artifact_file(root, item["artifact_id"])
