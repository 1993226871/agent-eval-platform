"""Verify the actual wheel's packaged protocol against the canonical shared schemas."""
import json
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
wheel = Path(sys.argv[1])
with zipfile.ZipFile(wheel) as package:
    canonical = list((root / "contracts/schemas").glob("*.json"))
    for schema in canonical:
        assert json.loads(package.read("agent_eval/schemas/" + schema.name)) == json.loads(schema.read_text(encoding="utf-8")), schema.name
    assert len([name for name in package.namelist() if name.startswith("agent_eval/schemas/") and name.endswith(".json")]) == len(canonical)
print(f"Built SDK wheel packages all {len(canonical)} canonical protocol schemas.")
