"""Submit a verified bundle for USER_MANAGED validation, never publish or retry upload."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid

BASE = "https://central.sonatype.com/api/v1/publisher/"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


open_request = build_opener(NoRedirect).open


def submit(bundle: Path, expected_sha256: str, username: str, password: str) -> str:
    payload = bundle.read_bytes()
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise ValueError("Bundle SHA-256 differs from verified manifest")
    if not username or not password:
        raise ValueError("Central Portal Token credentials are required")
    authorization = "Bearer " + base64.b64encode((username + ":" + password).encode()).decode()

    def post(path, data=b"", content_type="application/json"):
        request = Request(BASE + path, data=data, headers={"Authorization": authorization, "Content-Type": content_type}, method="POST")
        try:
            with open_request(request, timeout=120) as response:
                return response.read().decode()
        except HTTPError as error:
            raise RuntimeError(f"Central HTTP {error.code}; inspect Portal before retrying") from None
        except (URLError, TimeoutError, OSError):
            raise RuntimeError("Central request failed; upload outcome may be unknown, inspect Portal before retrying") from None

    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode() + payload + f"\r\n--{boundary}--\r\n".encode())
    # Exactly one submission. An uncertain response must be reconciled in Portal.
    deployment = str(uuid.UUID(post("upload?publishingType=USER_MANAGED", body, "multipart/form-data; boundary=" + boundary).strip()))
    print("Central deployment:", deployment)
    (bundle.parent / "deployment-id.txt").write_text(deployment)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write("\nCentral deployment: `" + deployment + "`\n\nManual publication required in https://central.sonatype.com/publishing/deployments\n")
    for attempt in range(30):
        state = json.loads(post("status?id=" + deployment))["deploymentState"]
        print("Central state:", state)
        if state == "VALIDATED":
            return deployment
        if state not in ("PENDING", "VALIDATING"):
            raise RuntimeError("Unexpected Central state: " + state + "; inspect deployment in Portal")
        time.sleep(10)
    raise RuntimeError("Validation still pending; inspect existing deployment before retrying")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--checksums", type=Path, required=True)
    args = parser.parse_args()
    lines = args.checksums.read_text().splitlines()
    if len(lines) != 1 or lines[0].split()[-1] != args.bundle.as_posix():
        raise ValueError("Expected one manifest entry for the selected bundle")
    submit(args.bundle, lines[0].split()[0], os.environ.get("CENTRAL_USERNAME", ""), os.environ.get("CENTRAL_PASSWORD", ""))
