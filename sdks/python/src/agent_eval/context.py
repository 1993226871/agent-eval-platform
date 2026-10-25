from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Event
from typing import Any


@dataclass(frozen=True)
class AgentResult:
    output: Any
    artifact_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class TaskContext:
    trial_id: str
    instruction: str
    input: Any
    deadline: datetime
    cancellation: Event = field(repr=False)
    _artifact_upload: Any = field(default=None, repr=False)

    @property
    def cancelled(self):
        return self.cancellation.is_set() or datetime.now(timezone.utc) >= self.deadline

    def artifact(self, path, name=None, media_type="application/octet-stream"):
        if self._artifact_upload is None:
            raise RuntimeError("Artifact upload is unavailable")
        return self._artifact_upload(path, name, media_type)
