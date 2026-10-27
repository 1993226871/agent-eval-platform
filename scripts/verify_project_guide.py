"""Check the guide's file inventory and local documentation links without modifying files."""
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
raw = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
files = {name for name in raw.decode("utf-8").split("\0") if name and (ROOT / name).is_file()}
guide = (ROOT / "docs/project-guide.md").read_text(encoding="utf-8")
indexed = re.findall(r"^\| \[`([^`]+)`\]\(", guide, re.MULTILINE)
assert len(indexed) == len(set(indexed)), "Duplicate file inventory rows"
assert set(indexed) == files, f"Inventory mismatch; missing={sorted(files-set(indexed))}; extra={sorted(set(indexed)-files)}"

for name in sorted(files):
    if not name.endswith(".md"):
        continue
    path = ROOT / name
    # Ignore code blocks; only actual Markdown links should resolve as documents.
    text = re.sub(r"```.*?```", "", path.read_text(encoding="utf-8"), flags=re.DOTALL)
    for target in re.findall(r"\]\(([^)]+)\)", text):
        target = target.strip("<>").split("#", 1)[0]
        if not target or re.match(r"^[A-Za-z][\w+.-]*:", target):
            continue
        target = target.split("?", 1)[0]
        resolved = (path.parent / unquote(target)).resolve()
        assert resolved.exists(), f"Broken link in {name}: {target}"
        if resolved.is_file() and resolved.is_relative_to(ROOT):
            relative = resolved.relative_to(ROOT).as_posix()
            assert relative in files, f"Public document links to untracked/private file: {name} -> {relative}"

print(f"Project guide covers all {len(files)} distributable source files; Markdown local links verified.")
