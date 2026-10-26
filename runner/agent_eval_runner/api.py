import hmac
import os
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from uuid import UUID
from .executor import RunnerExecutor, RunnerSettings
from .validation import RunnerValidationError


class InternalBoundary:
    def __init__(self, app, key):
        self.app, self.expected = app, ("Bearer " + key).encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        if not hmac.compare_digest(headers.get(b"authorization", b""), self.expected):
            return await JSONResponse({"code": "UNAUTHORIZED"}, status_code=401)(scope, receive, send)
        limit, count = 3 * 1024 * 1024, 0
        try:
            if int(headers.get(b"content-length", b"0")) > limit:
                return await JSONResponse({"code": "REQUEST_LIMIT"}, status_code=413)(scope, receive, send)
        except ValueError:
            return await JSONResponse({"code": "INVALID_LENGTH"}, status_code=400)(scope, receive, send)
        async def bounded_receive():
            nonlocal count
            message = await receive()
            count += len(message.get("body", b""))
            if count > limit:
                raise HTTPException(413, detail="REQUEST_LIMIT")
            return message
        await self.app(scope, bounded_receive, send)


def create_app(engine, internal_key, service_lock=None):
    if not isinstance(internal_key, str) or len(internal_key.encode()) < 32:
        raise ValueError("A strong internal Runner key is required")
    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            if service_lock is not None:
                try:
                    engine.docker.cleanup_owned()
                finally:
                    service_lock.close()
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.add_middleware(InternalBoundary, key=internal_key)

    @app.get("/internal/v1/capabilities")
    def capabilities():
        return engine.capabilities()

    @app.post("/internal/v1/evaluations")
    def evaluate(payload: dict):
        try:
            result = engine.execute(payload)
        except RunnerValidationError as failure:
            return JSONResponse({"code": failure.code}, status_code=409 if failure.code == "RUN_KEY_CONFLICT" else 422)
        status = {"UNAVAILABLE": 503, "BUSY": 429, "IN_PROGRESS": 202}.get(result["state"], 200)
        return JSONResponse(result, status_code=status)

    @app.delete("/internal/v1/evaluations/{job_id}/evidence", status_code=204)
    def scrub(job_id: UUID):
        try:
            engine.scrub(str(job_id))
        except (RuntimeError, OSError):
            return JSONResponse({"code": "CLEANUP_UNAVAILABLE"}, status_code=503)
    return app


def create_application():
    key = os.environ.get("RUNNER_INTERNAL_KEY", "")
    if len(key.encode()) < 32:
        raise ValueError("A strong internal Runner key is required")
    settings = RunnerSettings(Path(os.environ.get("RUNNER_WORK_ROOT", "/var/lib/agent-eval-runner")), os.environ.get("RUNNER_IMAGE_DIGEST", ""),
                              artifact_origins=tuple(value.strip() for value in os.environ.get("RUNNER_ARTIFACT_ORIGINS", "").split(",") if value.strip()))
    engine = RunnerExecutor(settings)
    from .service_lock import ServiceLock
    lock = ServiceLock(engine.root / ".service.lock")
    try:
        # File lock makes this cleanup safe: no other live process owns this registry.
        try:
            engine.recover_abandoned()
        except (RuntimeError, OSError):
            # Keep cached outcomes available during daemon outage; deletion cannot acknowledge
            # until the same guarded cleanup can terminate orphan containers and remove inputs.
            engine.registry.recover_unknown()
        return create_app(engine, key, lock)
    except BaseException:
        lock.close()
        raise
