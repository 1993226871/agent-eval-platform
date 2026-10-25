import json
from pathlib import Path
import pytest
from agent_eval.contracts import validate_message

ROOT = Path(__file__).resolve().parents[3] / "contracts"
CASES = json.loads((ROOT / "fixtures/v1/manifest.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("item", CASES, ids=lambda item: item["file"])
def test_shared_contract_fixtures(item):
    payload = json.loads((ROOT / "fixtures/v1" / item["file"]).read_text(encoding="utf-8"))
    if item["valid"]:
        validate_message(item["type"], payload)
    else:
        with pytest.raises(ValueError):
            validate_message(item["type"], payload)
