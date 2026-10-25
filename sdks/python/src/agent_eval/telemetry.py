"""Context-local instrumentation. Capturing never changes a business return or exception."""
import contextlib
import contextvars
import functools
import inspect
import math
import re
import time
from datetime import datetime, timezone
from uuid import uuid4
from .spool import encode

_current = contextvars.ContextVar("agent_eval_session", default=None)
_parent = contextvars.ContextVar("agent_eval_parent", default=None)
_private_key = re.compile(r"authorization|password|secret|token|api[_-]?key|cookie", re.I)
KINDS = {"agent", "model", "tool", "retrieval", "custom"}


def _redact(value, secrets, state, depth=0):
    if depth > 16:
        state["truncated"] = True
        return "[depth limit]"
    if isinstance(value, dict):
        result = {}
        for index, (key, item) in enumerate(value.items()):
            if index >= 128:
                state["truncated"] = True
                break
            key = str(key)
            for secret in secrets:
                if secret and secret in key:
                    key = key.replace(secret, "[redacted]")
                    state["redacted"] = True
            if _private_key.search(key):
                state["redacted"] = True
                result[key] = "[redacted]"
            else:
                result[key] = _redact(item, secrets, state, depth + 1)
        return result
    if isinstance(value, (list, tuple)):
        if len(value) > 128:
            state["truncated"] = True
        return [_redact(item, secrets, state, depth + 1) for item in value[:128]]
    if isinstance(value, str):
        for secret in secrets:
            if secret and secret in value:
                value = value.replace(secret, "[redacted]")
                state["redacted"] = True
        # Bound memory and payload even when a single string is huge.
        if len(value) > 1024 * 1024:
            value = value[:1024 * 1024]
            state["truncated"] = True
        return value
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, float) and math.isfinite(value):
        return value
    state["truncated"] = True
    return {"unsupported_type": type(value).__name__}


class TelemetrySession:
    def __init__(self, trial_id, trace_id, spool, secrets=()):
        self.trial_id, self.trace_id, self.spool = trial_id, trace_id, spool
        self._secrets = tuple(s for s in secrets if s)

    @contextlib.contextmanager
    def activate(self):
        current_token = _current.set(self)
        parent_token = _parent.set(None)
        try:
            yield self
        finally:
            _parent.reset(parent_token)
            _current.reset(current_token)

    def emit(self, event_type, kind, name, span_id, parent_id, payload):
        try:
            state = {"redacted": False, "truncated": False}
            clean = _redact(payload, self._secrets, state)
            clean_name = _redact(name, self._secrets, state)
            encoded = encode(clean)
            if len(encoded) > 65536:
                reference = self.spool.store_payload(self.trial_id, encoded)
                clean = {"payload_ref": reference} if reference else {"omitted": "spool capacity exceeded"}
                # Inline evidence is incomplete even if a separately readable attachment is later available.
                state["truncated"] = True
            event = {"schema_version": "1.0", "event_id": str(uuid4()), "trial_id": self.trial_id,
                     "trace_id": self.trace_id, "span_id": span_id, "parent_span_id": parent_id,
                     "event_type": event_type, "span_kind": kind, "name": clean_name, "sequence": 1,
                     "occurred_at": datetime.now(timezone.utc).isoformat(), "payload": clean, "capture": state}
            self.spool.append(self.trial_id, event)
        except Exception:
            try:
                self.spool.record_drop(self.trial_id)
            except Exception:
                pass


class _Span:
    def __init__(self, kind, name, input_value=None):
        if kind not in KINDS or not isinstance(name, str) or not name.strip() or len(name) > 1024:
            raise ValueError("Invalid span")
        self.kind, self.name, self.input = kind, name, input_value
        self.output = None
        self.session = None

    def __enter__(self):
        self.session = _current.get()
        if self.session is None:
            return self
        self.span_id, self.parent_id = uuid4().hex[:16], _parent.get()
        self.started = time.monotonic()
        self.session.emit("span.started", self.kind, self.name, self.span_id, self.parent_id, {"input": self.input})
        self.parent_token = _parent.set(self.span_id)
        return self

    def __exit__(self, error_type, error, traceback):
        if self.session is None:
            return False
        _parent.reset(self.parent_token)
        payload = {"duration_ms": max(0, int((time.monotonic() - self.started) * 1000))}
        if error_type:
            # Exception messages can contain credentials or raw model prompts.
            payload["error"] = {"type": error_type.__name__}
        else:
            payload["output"] = self.output
        self.session.emit("span.failed" if error_type else "span.completed", self.kind, self.name, self.span_id, self.parent_id, payload)
        return False


def span(kind, name, input_value=None):
    return _Span(kind, name, input_value)


def observe(kind, name=None):
    def decorate(function):
        label = name or function.__name__
        if inspect.iscoroutinefunction(function):
            @functools.wraps(function)
            async def asynchronous(*args, **kwargs):
                with span(kind, label, {"args": args, "kwargs": kwargs}) as scope:
                    scope.output = await function(*args, **kwargs)
                    return scope.output
            return asynchronous
        @functools.wraps(function)
        def synchronous(*args, **kwargs):
            with span(kind, label, {"args": args, "kwargs": kwargs}) as scope:
                scope.output = function(*args, **kwargs)
                return scope.output
        return synchronous
    return decorate
