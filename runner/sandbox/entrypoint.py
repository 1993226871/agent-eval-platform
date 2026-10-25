"""Trusted guest entrypoint. The control service never imports or invokes this on host."""
import argparse
import json
import os
from pathlib import Path
import time
from uuid import uuid4


def write_result(directory, result):
    raw = json.dumps(result, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if len(raw) > 65536:
        raise ValueError("Result too large")
    temporary = directory / ("result-" + uuid4().hex)
    with os.fdopen(os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0), 0o600), "wb") as output:
        output.write(raw)
    os.replace(temporary, directory / "result.json")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=Path("/input"))
    parser.add_argument("--output", type=Path, default=Path("/output"))
    parser.add_argument("--no-wait", action="store_true", help="trusted repository unit fixtures only")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        code = (args.input / "evaluator.py").read_text(encoding="utf-8")
        context = json.loads((args.input / "context.json").read_text(encoding="utf-8"))
        namespace = {"__name__": "agent_eval_user", "__file__": "/input/evaluator.py"}
        exec(compile(code, "user_evaluator.py", "exec", dont_inherit=True), namespace)
        function = namespace.get("evaluate")
        if not callable(function):
            raise TypeError("evaluate must be callable")
        result = function(context)
        write_result(args.output, result)
    except BaseException as failure:
        # A metric crash is evaluator ERROR. Raw exception messages can expose context values.
        write_result(args.output, {"status": "ERROR", "score": None, "reason": "Evaluator raised " + type(failure).__name__, "evidence_refs": []})
    if not args.no_wait:
        deadline = time.monotonic() + 5
        while not (args.output / ".ack").exists() and time.monotonic() < deadline:
            time.sleep(0.02)


if __name__ == "__main__":
    main()
