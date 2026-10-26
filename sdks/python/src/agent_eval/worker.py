import asyncio
import copy
import inspect
import os
import random
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from .context import AgentResult, TaskContext
from .contracts import validate_message
from .spool import FileSpool, encode
from .telemetry import TelemetrySession, span
from .transport import HttpTransport, ProtocolError, TransportUnavailable


def _instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


class EvaluationWorker:
    def __init__(self, transport, spool, flush_timeout=5):
        self.transport, self.spool = transport, spool
        self.flush_timeout = flush_timeout
        self._handlers = {}
        self._stop = threading.Event()
        self._pollers = []
        self._lock = threading.Lock()
        self._cancellations = set()
        self._heartbeat_threads = set()
        self._owns_resources = False

    @classmethod
    def from_env(cls, environment=None):
        env = os.environ if environment is None else environment
        url, key = env.get("AGENT_EVAL_URL"), env.get("AGENT_EVAL_API_KEY")
        if not url or not key:
            raise ValueError("AGENT_EVAL_URL and AGENT_EVAL_API_KEY are required")
        spool = FileSpool(Path(env.get("AGENT_EVAL_SPOOL", ".agent-eval/spool.sqlite")))
        try:
            transport = HttpTransport(url, key, env.get("AGENT_EVAL_AGENT_VERSION", "v1"), env.get("AGENT_EVAL_INSTANCE_ID"))
        except Exception:
            spool.close()
            raise
        worker = cls(transport, spool)
        worker._owns_resources = True
        return worker

    def register_agent(self, agent_id, handler):
        if self._pollers or not isinstance(agent_id, str) or not callable(handler):
            raise ValueError("Register a callable before starting")
        if agent_id in self._handlers:
            raise ValueError("Agent already registered")
        self._handlers[agent_id] = handler
        return self

    @property
    def active_heartbeat_count(self):
        with self._lock:
            return sum(thread.is_alive() for thread in self._heartbeat_threads)

    def execute(self, agent_id, assignment):
        validate_message("LeaseGrant", assignment)
        if agent_id not in self._handlers or self._stop.is_set():
            return
        assignment = copy.deepcopy(assignment)
        trial = assignment["trial_id"]
        # Durable marker precedes started/handler. A crash creates uncertainty, never an automatic replay.
        if not self.spool.mark_execution(trial, assignment["attempt_token"]):
            self._flush_payloads(assignment)
            self._flush(assignment)
            return
        deadline = _instant(assignment["task"]["deadline"])
        if deadline <= datetime.now(timezone.utc):
            return
        try:
            self.transport.started(assignment)
        except (ProtocolError, TransportUnavailable):
            return
        cancellation, done, ownership_lost = threading.Event(), threading.Event(), threading.Event()
        with self._lock:
            self._cancellations.add(cancellation)
        lease_expiry = [_instant(assignment["lease_expires_at"])]
        session = TelemetrySession(trial, assignment["trace_id"], self.spool, secrets=(assignment["attempt_token"], getattr(self.transport, "_key", "")))
        artifacts = []

        def upload(path, name, media_type):
            artifact = self.transport.upload_artifact(assignment, path, name, media_type)
            artifacts.append(artifact)
            return artifact

        context = TaskContext(trial, assignment["task"]["instruction"], copy.deepcopy(assignment["task"]["input"]), deadline, cancellation, upload)

        def heartbeat():
            interval = min(self.transport.heartbeat_seconds, 15)
            while True:
                until_expiry = (min(deadline, lease_expiry[0]) - datetime.now(timezone.utc)).total_seconds()
                if done.wait(max(0, min(interval, until_expiry))):
                    return
                now = datetime.now(timezone.utc)
                if self._stop.is_set() or now >= deadline or now >= lease_expiry[0]:
                    cancellation.set()
                    if now >= lease_expiry[0]:
                        ownership_lost.set()
                    return
                try:
                    response = self.transport.heartbeat(assignment)
                    lease_expiry[0] = min(_instant(response["lease_expires_at"]), deadline)
                    if response["cancel_requested"]:
                        cancellation.set()
                    self._flush(assignment, budget=1)
                except ProtocolError as exc:
                    if exc.status in {401, 403, 404, 409}:
                        ownership_lost.set()
                        cancellation.set()
                        return
                except (TransportUnavailable, ValueError):
                    # Continue only until the last known lease expiry; never infer a successful renewal.
                    pass

        thread = threading.Thread(target=heartbeat, name="agent-eval-heartbeat", daemon=True)
        with self._lock:
            self._heartbeat_threads.add(thread)
        thread.start()
        output, state = None, "FAILED"
        try:
            with session.activate():
                with span("agent", "agent.run", context.input) as root:
                    result = self._handlers[agent_id](context)
                    if inspect.isawaitable(result):
                        result = asyncio.run(result)
                    if not isinstance(result, AgentResult):
                        raise TypeError("Agent handler must return AgentResult")
                    output = result.output
                    encode(output)
                    artifacts.extend(result.artifact_ids)
                    root.output = output
            state = "SUCCEEDED"
        except Exception:
            # Agent exceptions are recorded by the root span using type only, never raw messages.
            state = "FAILED"
            output = None
        finally:
            # Keep heartbeating through the final flush and completion submission.
            try:
                self._flush_payloads(assignment)
                self._flush(assignment)
                if cancellation.is_set():
                    state = "CANCELLED"
                if datetime.now(timezone.utc) >= deadline:
                    state = "TIMED_OUT"
                stats = self.spool.stats(trial)
                completion = {"status": state, "output": output, "final_sequence": stats.final_sequence,
                              "event_count": stats.event_count, "dropped_count": stats.dropped_count, "artifact_ids": list(dict.fromkeys(artifacts + self.spool.artifact_ids(trial)))}
                if not ownership_lost.is_set():
                    self._complete_with_retry(assignment, completion)
            finally:
                done.set()
                thread.join(timeout=26)
                with self._lock:
                    self._cancellations.discard(cancellation)
                    self._heartbeat_threads.discard(thread)

    def _flush_payloads(self, assignment):
        end = time.monotonic() + self.flush_timeout
        trial = assignment["trial_id"]
        while time.monotonic() < end:
            pending = self.spool.pending_payloads(trial)
            if not pending:
                return
            identity, content = pending[0]
            try:
                artifact_id = self.transport.upload_bytes(assignment, content, "payload.json", "application/json", idempotency_key=identity)
                self.spool.acknowledge_payload(trial, identity, artifact_id)
                session = TelemetrySession(trial, assignment["trace_id"], self.spool)
                from uuid import uuid4
                session.emit("artifact.created", "custom", "payload.attachment", uuid4().hex[:16], None, {"artifact_id": artifact_id, "payload_ref": identity})
            except (ProtocolError, TransportUnavailable, ValueError):
                return

    def _complete_with_retry(self, assignment, completion):
        end = time.monotonic() + self.flush_timeout
        delay = 0.1
        while True:
            try:
                self.transport.complete(assignment, completion)
                return
            except ProtocolError as exc:
                if exc.status < 500:
                    return
            except TransportUnavailable:
                pass
            if time.monotonic() + delay >= end:
                return
            self._stop.wait(delay)
            delay = min(delay * 2, 1)

    def _flush(self, assignment, budget=None):
        end = time.monotonic() + (self.flush_timeout if budget is None else budget)
        while time.monotonic() < end:
            events = self.spool.pending(assignment["trial_id"])
            if not events:
                return
            try:
                ack = self.transport.upload_events(assignment, events)
                ids = ack.get("acknowledged_event_ids", [])
                sent = {event["event_id"] for event in events}
                if not isinstance(ids, list) or not ids or any(event_id not in sent for event_id in ids):
                    return
                self.spool.acknowledge(assignment["trial_id"], ids)
            except (ProtocolError, TransportUnavailable):
                return

    def start(self):
        if self._pollers or not self._handlers or self._stop.is_set():
            raise ValueError("Worker cannot start")
        for agent_id in self._handlers:
            thread = threading.Thread(target=self._poll, args=(agent_id,), name="agent-eval-poller", daemon=True)
            self._pollers.append(thread)
            thread.start()
        return self

    def _poll(self, agent_id):
        delay, worker_id = 0.25, None
        while not self._stop.is_set():
            try:
                if worker_id is None:
                    worker_id = self.transport.register(agent_id)
                assignment = self.transport.claim(worker_id, agent_id)
                if assignment and not self._stop.is_set():
                    self.execute(agent_id, assignment)
                delay = 0.25
            except ProtocolError as exc:
                if exc.status in {401, 403, 404}:
                    return
                self._stop.wait(delay + random.uniform(0, delay / 4))
                delay = min(delay * 2, 10)
            except (TransportUnavailable, ValueError):
                self._stop.wait(delay + random.uniform(0, delay / 4))
                delay = min(delay * 2, 10)

    def stop(self, timeout=6):
        self._stop.set()
        with self._lock:
            for cancellation in self._cancellations:
                cancellation.set()
        end = time.monotonic() + timeout
        for thread in self._pollers:
            thread.join(timeout=max(0, end - time.monotonic()))
        # A noncooperative business handler cannot be killed safely by a Python thread.
        # Do not close its storage/client out from under it.
        if self._owns_resources and not self._cancellations and not any(thread.is_alive() for thread in self._pollers):
            self.transport.close()
            self.spool.close()
            self._owns_resources = False
