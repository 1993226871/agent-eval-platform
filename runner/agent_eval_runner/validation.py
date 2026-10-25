import ast
import hashlib
import json
import math
import re
from functools import lru_cache
from importlib.resources import files
from pathlib import Path
from uuid import UUID
from jsonschema import Draft202012Validator, FormatChecker

IMAGE_PATTERN = re.compile(r"^(?:sha256:[0-9a-f]{64}|[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64})$")
UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
CAPS = {"timeout_seconds": 30, "memory_mb": 256, "processes": 32, "tmp_mb": 64}


class RunnerValidationError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def encoded(value):
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, RecursionError):
        raise RunnerValidationError("INVALID_JSON_VALUE") from None


@lru_cache(maxsize=1)
def context_validator():
    schema = json.loads(files("agent_eval_runner").joinpath("schemas/evaluation-context.json").read_text(encoding="utf-8"))
    return Draft202012Validator(schema, format_checker=FormatChecker())


def validate_request(payload, configured_image):
    keys = {"job_id", "code", "code_hash", "image_digest", "context", "limits", "artifact_downloads"}
    if not isinstance(payload, dict) or set(payload) != keys:
        raise RunnerValidationError("INVALID_REQUEST")
    if not isinstance(payload["job_id"], str) or not UUID_PATTERN.fullmatch(payload["job_id"]):
        raise RunnerValidationError("INVALID_JOB_ID")
    if not isinstance(payload["image_digest"], str) or len(payload["image_digest"]) > 256 or not IMAGE_PATTERN.fullmatch(payload["image_digest"]) or payload["image_digest"] != configured_image:
        raise RunnerValidationError("IMAGE_NOT_ALLOWED")
    code = payload["code"]
    if not isinstance(code, str) or len(code.encode("utf-8")) > 65536 or hashlib.sha256(code.encode("utf-8")).hexdigest() != payload["code_hash"]:
        raise RunnerValidationError("INVALID_CODE_HASH_OR_SIZE")
    try:
        ast.parse(code, filename="user_evaluator.py")
    except (SyntaxError, ValueError, RecursionError):
        raise RunnerValidationError("SYNTAX_ERROR") from None
    context = payload["context"]
    if len(encoded(context)) > 2 * 1024 * 1024 or not context_validator().is_valid(context):
        raise RunnerValidationError("INVALID_CONTEXT")
    if len(context["events"]) > 10000 or len(context["artifacts"]) > 100:
        raise RunnerValidationError("CONTEXT_LIMIT")
    for event in context["events"]:
        if event["trial_id"] != context["trial_id"] or event["span_id"] == event["parent_span_id"]:
            raise RunnerValidationError("FOREIGN_OR_INVALID_EVENT")
    limits = payload["limits"]
    if not isinstance(limits, dict) or set(limits) != set(CAPS) or any(type(limits[key]) is not int or limits[key] < 1 or limits[key] > cap for key, cap in CAPS.items()):
        raise RunnerValidationError("RESOURCE_LIMIT_NOT_ALLOWED")
    if limits["processes"] < 4 or limits["memory_mb"] < 64:
        raise RunnerValidationError("RESOURCE_LIMIT_TOO_LOW")
    downloads = payload["artifact_downloads"]
    if not isinstance(downloads, list) or len(downloads) > 100:
        raise RunnerValidationError("ARTIFACT_LIMIT")
    references = {item["artifact_id"]: item for item in context["artifacts"]}
    seen, total = set(), 0
    for item in downloads:
        if not isinstance(item, dict) or set(item) != {"artifact_id", "url"} or not isinstance(item["artifact_id"], str) or item["artifact_id"] not in references or item["artifact_id"] in seen or not isinstance(item["url"], str) or len(item["url"]) > 8192:
            raise RunnerValidationError("INVALID_ARTIFACT_DOWNLOAD")
        seen.add(item["artifact_id"])
        total += references[item["artifact_id"]]["size_bytes"]
    if seen != set(references) or total > 100 * 1024 * 1024:
        raise RunnerValidationError("ARTIFACT_SCOPE_OR_SIZE_LIMIT")
    return payload


def request_hash(payload):
    # Signed URL expiry may change on retry; immutable content identity must not.
    identity = dict(payload)
    identity["artifact_downloads"] = sorted(item["artifact_id"] for item in payload["artifact_downloads"])
    return hashlib.sha256(encoded(identity)).hexdigest()


def safe_artifact_file(root, identity):
    if not isinstance(identity, str) or not UUID_PATTERN.fullmatch(identity):
        raise RunnerValidationError("INVALID_ARTIFACT_PATH")
    root = Path(root).resolve(strict=True)
    path = root / str(UUID(identity))
    try:
        if path.is_symlink() or not path.resolve(strict=True).is_relative_to(root) or not path.is_file():
            raise RunnerValidationError("INVALID_ARTIFACT_PATH")
    except (OSError, RuntimeError):
        raise RunnerValidationError("INVALID_ARTIFACT_PATH") from None
    return path


def validate_result(raw, context):
    if len(raw) > 65536:
        raise RunnerValidationError("RESULT_LIMIT")
    def reject_constant(_):
        raise RunnerValidationError("NONFINITE_SCORE")
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise RunnerValidationError("DUPLICATE_RESULT_FIELD")
            result[key] = value
        return result
    try:
        result = json.loads(raw.decode("utf-8"), parse_constant=reject_constant, object_pairs_hook=unique_object)
    except (ValueError, UnicodeError, RecursionError):
        raise RunnerValidationError("INVALID_RESULT_JSON") from None
    if not isinstance(result, dict) or set(result) != {"status", "score", "reason", "evidence_refs"}:
        raise RunnerValidationError("INVALID_RESULT_FIELDS")
    if not isinstance(result["status"], str) or result["status"] not in {"SCORED", "INSUFFICIENT_EVIDENCE", "ERROR", "SKIPPED"}:
        raise RunnerValidationError("INVALID_RESULT_STATUS")
    if result["status"] == "SCORED":
        score = result["score"]
        if type(score) not in {int, float} or not 0 <= score <= 1 or (isinstance(score, float) and not math.isfinite(score)):
            raise RunnerValidationError("INVALID_SCORE")
    elif result["score"] is not None:
        raise RunnerValidationError("NON_SCORED_VALUE")
    if not isinstance(result["reason"], str) or not result["reason"].strip() or len(result["reason"]) > 2048:
        raise RunnerValidationError("INVALID_RESULT_REASON")
    allowed = {"output", "expected", "task_input", "snapshot:" + context["snapshot_id"]}
    allowed.update("event:" + event["event_id"] for event in context["events"])
    allowed.update("artifact:" + artifact["artifact_id"] for artifact in context["artifacts"])
    refs = result["evidence_refs"]
    if not isinstance(refs, list) or len(refs) > 1000 or any(not isinstance(ref, str) or ref not in allowed for ref in refs):
        raise RunnerValidationError("UNKNOWN_EVIDENCE_REFERENCE")
    return result
