"""Regenerate package data from the authoritative shared contracts."""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[3]
destination = root / "sdks/python/src/agent_eval/schemas"
destination.mkdir(parents=True, exist_ok=True)
for source in (root / "contracts/schemas").glob("*.json"):
    shutil.copyfile(source, destination / source.name)
