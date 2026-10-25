import json
import subprocess
import sys
from pathlib import Path
import pytest
from agent_eval_runner.validation import validate_result, RunnerValidationError
from helpers import request

ENTRYPOINT = Path(__file__).resolve().parents[1] / "sandbox/entrypoint.py"


def test_stdout_does_not_corrupt_result(tmp_path):
    inputs, output = tmp_path / "input", tmp_path / "output"
    inputs.mkdir()
    payload = request("print('module noise')\ndef evaluate(context):\n    print('function noise')\n    return {'status': 'SCORED', 'score': 1, 'reason': 'Compared', 'evidence_refs': ['output']}\n")
    (inputs / "evaluator.py").write_text(payload["code"], encoding="utf-8")
    (inputs / "context.json").write_text(json.dumps(payload["context"]), encoding="utf-8")
    result = subprocess.run([sys.executable, str(ENTRYPOINT), "--input", str(inputs), "--output", str(output), "--no-wait"], capture_output=True, text=True, timeout=5)
    assert result.returncode == 0
    assert "module noise" in result.stdout
    assert "function noise" in result.stdout
    parsed = validate_result((output / "result.json").read_bytes(), payload["context"])
    assert parsed["score"] == 1


@pytest.mark.parametrize("result", [
    b'{"status":"SCORED","score":NaN,"reason":"bad","evidence_refs":[]}',
    b'{"status":"SCORED","score":2,"reason":"bad","evidence_refs":[]}',
    b'{"status":"SCORED","score":1,"passed":true,"reason":"bad","evidence_refs":[]}',
    b'{"status":"SCORED","score":1,"reason":"bad","evidence_refs":["event:00000000-0000-0000-0000-000000000001"]}',
    b'noise {"status":"SCORED","score":1,"reason":"bad","evidence_refs":[]}',
    b'{"status":"SCORED","score":0,"score":1,"reason":"duplicate","evidence_refs":[]}',
])
def test_invalid_output_does_not_become_agent_zero(result):
    with pytest.raises(RunnerValidationError):
        validate_result(result, request()["context"])
