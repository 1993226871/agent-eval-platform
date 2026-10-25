from pathlib import Path
import json


def test_packaged_context_contracts_match_shared_schemas():
    root = Path(__file__).resolve().parents[2]
    packaged = root / "runner/agent_eval_runner/schemas"
    shared = root / "contracts/schemas"
    assert {item.name for item in packaged.glob("*.json")} == {item.name for item in shared.glob("*.json")}
    for item in shared.glob("*.json"):
        assert json.loads(item.read_text(encoding="utf-8")) == json.loads((packaged / item.name).read_text(encoding="utf-8"))
