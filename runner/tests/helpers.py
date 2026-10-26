import hashlib
from uuid import uuid4

IMAGE = "sha256:" + "a" * 64


def request(code=None):
    code = code or "def evaluate(context):\n    return {'status': 'SCORED', 'score': 1, 'reason': 'Compared', 'evidence_refs': ['output']}\n"
    return {"job_id": str(uuid4()), "code": code, "code_hash": hashlib.sha256(code.encode()).hexdigest(), "image_digest": IMAGE,
            "context": {"trial_id": str(uuid4()), "snapshot_id": str(uuid4()), "task_instruction": "Query order amount", "task_input": {"order_id": "A"}, "expected": {"amount": 100}, "output": {"amount": 100}, "events": [], "artifacts": [], "completeness": "NONE"},
            "limits": {"timeout_seconds": 30, "memory_mb": 256, "processes": 32, "tmp_mb": 64}, "artifact_downloads": []}
