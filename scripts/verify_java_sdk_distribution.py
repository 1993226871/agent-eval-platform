"""Check real Java SDK distribution artifacts; never upload or publish anything."""
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
parent = ET.parse(ROOT / "pom.xml").getroot()
version = parent.findtext("m:version", namespaces=NS)
for field in ("name", "description", "url", "licenses/license/name", "developers/developer/id", "scm/connection"):
    assert parent.findtext("/".join("m:" + part for part in field.split("/")), namespaces=NS), field

license_text = (ROOT / "LICENSE").read_text(encoding="utf-8").replace("\r\n", "\n")
for module, artifact, required in (
    ("platform/protocol", "protocol", "io/agenteval/protocol/ProtocolCodec"),
    ("sdks/java", "agent-eval-sdk", "io/agenteval/sdk/EvaluationWorker"),
):
    target = ROOT / module / "target"
    for suffix, member in (("", required + ".class"), ("-sources", required + ".java"), ("-javadoc", "index.html")):
        path = target / f"{artifact}-{version}{suffix}.jar"
        assert path.is_file(), f"Missing distribution artifact: {path}"
        with ZipFile(path) as package:
            assert member in package.namelist(), f"Missing {member} in {path.name}"
            assert not any(name.startswith("io/agenteval/evaluation/") for name in package.namelist()), "Server code in SDK distribution"
            if not suffix:
                actual = package.read("META-INF/LICENSE").decode("utf-8").replace("\r\n", "\n")
                assert actual == license_text, f"License mismatch in {path.name}"

print(f"Java {version}: SDK and protocol binary/source/Javadoc jars and Apache license verified; parent POM metadata present. Nothing published.")
