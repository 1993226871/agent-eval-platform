"""Verify the actual wheel's packaged protocol against the canonical shared schemas."""
import json
from pathlib import Path
import sys
import zipfile
from email.parser import Parser

root = Path(__file__).resolve().parents[1]
wheel = Path(sys.argv[1])
with zipfile.ZipFile(wheel) as package:
    metadata_files=[name for name in package.namelist() if name.endswith('.dist-info/METADATA')]
    assert len(metadata_files)==1, 'Wheel must contain one distribution metadata file'
    metadata=Parser().parsestr(package.read(metadata_files[0]).decode('utf-8'))
    assert metadata['License-Expression']=='Apache-2.0', 'Missing Apache-2.0 license metadata'
    assert metadata['Description-Content-Type']=='text/markdown', 'Missing Markdown project description'
    assert len(metadata.get_payload().strip())>200, 'Missing SDK usage documentation'
    prefix=metadata_files[0].rsplit('/',1)[0]
    license_path=prefix+'/licenses/LICENSE'
    normalize=lambda raw: raw.decode('utf-8').replace('\r\n','\n')
    assert normalize(package.read(license_path))==normalize((root/'LICENSE').read_bytes()), 'Packaged license must match project license'
    canonical = list((root / "contracts/schemas").glob("*.json"))
    for schema in canonical:
        assert json.loads(package.read("agent_eval/schemas/" + schema.name)) == json.loads(schema.read_text(encoding="utf-8")), schema.name
    assert len([name for name in package.namelist() if name.startswith("agent_eval/schemas/") and name.endswith(".json")]) == len(canonical)
print(f"Built SDK wheel packages Apache-2.0, usage documentation and all {len(canonical)} canonical protocol schemas.")
