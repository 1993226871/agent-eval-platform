"""The packaged schemas are generated from the repository's language-neutral contracts."""
import json
from functools import lru_cache
from importlib.resources import files
from jsonschema import Draft202012Validator, FormatChecker

MODELS = {
    "TraceEvent": "trace-event", "TrialContextPayload": "trial-context", "ClaimRequest": "claim-request",
    "LeaseGrant": "lease-grant", "CompletionRequest": "completion-request", "ArtifactRef": "artifact-ref",
    "EvaluationContext": "evaluation-context", "CriterionResult": "criterion-result",
    "RegisterWorkerRequest": "register-worker-request", "RegisterArtifactRequest": "register-artifact-request",
}


@lru_cache(maxsize=16)
def _validator(model):
    schema = json.loads(files("agent_eval").joinpath("schemas", MODELS[model] + ".json").read_text(encoding="utf-8"))
    return Draft202012Validator(schema, format_checker=FormatChecker())


def validate_message(model, payload):
    # Do not echo rejected values: a malformed message may contain secrets.
    if not _validator(model).is_valid(payload):
        raise ValueError("Invalid " + model)
    if model == "TraceEvent" and payload["parent_span_id"] == payload["span_id"]:
        raise ValueError("Self-parent span")
    if model == "LeaseGrant" and payload["task"]["trial_id"] != payload["trial_id"]:
        raise ValueError("Foreign task")
    if model == "CompletionRequest":
        final, count, dropped = (payload[k] for k in ("final_sequence", "event_count", "dropped_count"))
        if count > final or dropped > final - count:
            raise ValueError("Invalid completion counts")
    if model == "EvaluationContext":
        for event in payload["events"]:
            validate_message("TraceEvent", event)
            if event["trial_id"] != payload["trial_id"]:
                raise ValueError("Foreign trial evidence")
    return payload
