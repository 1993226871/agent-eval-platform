"""Exercise Windows startup orchestration with fake Docker, without touching containers."""
import base64
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which("powershell.exe") or shutil.which("pwsh")


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


@unittest.skipUnless(POWERSHELL, "PowerShell is required")
class LocalPlatformTests(unittest.TestCase):
    def invoke(self, action="start", ready=True, fail=False, missing=False):
        with tempfile.TemporaryDirectory(prefix="veyraeval startup ") as directory:
            env = Path(directory) / ".env"
            original = "COMPOSE_PROJECT_NAME=existing-platform\nHTTPS_PORT=9444\nPRIVATE_TEST_KEY=do-not-print-this\n"
            if not missing:
                env.write_text(original, encoding="utf-8")
            code = f"""
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
. {literal(ROOT / 'scripts/local-platform.ps1')}
$global:Calls = [Collections.Generic.List[object]]::new()
$global:Ready = ${str(ready).lower()}
$global:DesktopStarts = 0
function docker {{
    $global:Calls.Add(@($args))
    if ($args[0] -eq 'info') {{
        if ($global:Ready) {{ $global:LASTEXITCODE=0; 'linux' }}
        else {{ $global:LASTEXITCODE=1 }}
    }} else {{ $global:LASTEXITCODE={7 if fail else 0} }}
}}
function Start-DockerDesktop {{ $global:DesktopStarts++; $global:Ready=$true }}
$failure=$null
try {{ Invoke-LocalPlatform -Action {literal(action)} -EnvFile {literal(env)} -NoBrowser }}
catch {{ $failure=$_.Exception.Message }}
'__RESULT__'+(@{{calls=$global:Calls.ToArray();desktop_starts=$global:DesktopStarts;failure=$failure}} | ConvertTo-Json -Depth 8 -Compress)
"""
            encoded = base64.b64encode(code.encode("utf-16-le")).decode("ascii")
            result = subprocess.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded],
                                    text=True, encoding="utf-8", capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn("do-not-print-this", result.stdout + result.stderr)
            if not missing:
                self.assertEqual(env.read_text(encoding="utf-8"), original)
            data = json.loads(result.stdout.split("__RESULT__", 1)[1])
            return data, result.stdout

    def test_start_reuses_configuration_and_images(self):
        data, output = self.invoke()
        self.assertIsNone(data["failure"])
        command = next(call for call in data["calls"] if "up" in call)
        self.assertIn("--env-file", command)
        self.assertIn("--no-build", command)
        self.assertEqual(command[command.index("--pull") + 1], "never")
        self.assertNotIn("--force-recreate", command)
        self.assertIn("https://localhost:9444", output)
        self.assertEqual(data["desktop_starts"], 0)

    def test_start_launches_desktop_only_when_needed(self):
        data, _ = self.invoke(ready=False)
        self.assertIsNone(data["failure"])
        self.assertEqual(data["desktop_starts"], 1)

    def test_stop_preserves_containers_and_volumes(self):
        data, _ = self.invoke(action="stop")
        self.assertIsNone(data["failure"])
        self.assertTrue(any(call[-1] == "stop" for call in data["calls"]))
        self.assertFalse(any("down" in call or "--volumes" in call for call in data["calls"]))

    def test_status_does_not_start_services(self):
        data, _ = self.invoke(action="status")
        self.assertIsNone(data["failure"])
        self.assertTrue(any(call[-1] == "ps" for call in data["calls"]))
        self.assertFalse(any("up" in call for call in data["calls"]))

    def test_missing_configuration_never_initializes_a_new_deployment(self):
        data, _ = self.invoke(missing=True)
        self.assertIn("configuration", data["failure"])
        self.assertEqual(data["calls"], [])

    def test_compose_failure_is_reported(self):
        data, _ = self.invoke(fail=True)
        self.assertIn("7", data["failure"])


if __name__ == "__main__":
    unittest.main()
