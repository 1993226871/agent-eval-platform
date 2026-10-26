"""Generate private deployment configuration. Development certificates are for localhost only."""
import argparse
import base64
import os
from pathlib import Path
import secrets
import shutil
import subprocess


def configure(root, email, https_port=8443, s3_port=9443, development_tls=False, openssl=None):
    root = Path(root).resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    env_path = root / ".env"
    if env_path.exists():
        raise ValueError("Refusing to overwrite existing deployment configuration")
    tls = root / "tls"
    tls.mkdir(mode=0o700, exist_ok=True)
    if development_tls:
        executable = openssl or shutil.which("openssl")
        if not executable:
            raise ValueError("OpenSSL required for explicit local development TLS")
        if any(tls.iterdir()):
            raise ValueError("Refusing to overwrite existing TLS files")
        subprocess.run([executable, "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes", "-days", "30",
                        "-keyout", str(tls / "server.key"), "-out", str(tls / "server.crt"), "-subj", "/CN=localhost",
                        "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1", "-addext", "basicConstraints=critical,CA:TRUE"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.chmod(tls / "server.key", 0o600)
    values = {
        "COMPOSE_PROJECT_NAME": "agenteval", "BIND_ADDRESS": "127.0.0.1", "HTTPS_PORT": str(https_port), "S3_HTTPS_PORT": str(s3_port),
        "PUBLIC_S3_ENDPOINT": f"https://localhost:{s3_port}", "TLS_CERT_DIR": tls.as_posix(),
        "DATABASE_PASSWORD": secrets.token_urlsafe(32), "S3_ACCESS_KEY": secrets.token_hex(12), "S3_SECRET_KEY": secrets.token_urlsafe(32),
        "MODEL_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(), "MODEL_ENDPOINT_ORIGINS": "",
        "BOOTSTRAP_ADMIN_EMAIL": email, "BOOTSTRAP_ADMIN_PASSWORD": secrets.token_urlsafe(32),
        "WEB_UID": str(os.getuid() if hasattr(os, "getuid") else 101), "WEB_GID": str(os.getgid() if hasattr(os, "getgid") else 101),
        "RUNNER_ENABLED": "false", "RUNNER_INTERNAL_KEY": secrets.token_urlsafe(32), "RUNNER_IMAGE_DIGEST": "",
    }
    if any("\n" in value or "\r" in value for value in values.values()):
        raise ValueError("Deployment values must be single-line")
    with env_path.open("x", encoding="utf-8", newline="\n") as output:
        os.chmod(env_path, 0o600)
        output.write("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
    return values


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--admin-email", required=True)
    parser.add_argument("--development-tls", action="store_true")
    args = parser.parse_args()
    configure(args.directory, args.admin_email, development_tls=args.development_tls)
    print("Private configuration generated. Read the local .env for bootstrap credentials; do not commit it.")
