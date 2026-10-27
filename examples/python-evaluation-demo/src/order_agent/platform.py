"""Session/CSRF API for demo setup. Worker authentication belongs to the SDK."""
import ssl
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx


class PlatformError(RuntimeError):
    pass


def tls_context(ca: Path | None) -> ssl.SSLContext:
    return ssl.create_default_context(cafile=str(ca) if ca else None)


class Platform:
    def __init__(self, url: str, ca: Path | None, transport: httpx.BaseTransport | None = None):
        parts = urlsplit(url)
        if (parts.scheme not in {"https", "http"} or not parts.hostname
                or parts.username or parts.password or parts.query or parts.fragment
                or parts.path not in {"", "/"}):
            raise ValueError("Use a platform origin URL without credentials or path")
        if parts.scheme == "http" and parts.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("Remote platforms require HTTPS")
        self.url = url.rstrip("/")
        self.client = httpx.Client(base_url=self.url, verify=tls_context(ca), trust_env=False,
                                   follow_redirects=False, timeout=30, transport=transport)

    @staticmethod
    def checked(response: httpx.Response) -> Any:
        if not 200 <= response.status_code < 300:
            # A response can contain credentials or private data. Do not log its body.
            raise PlatformError(f"Platform request rejected: HTTP {response.status_code}")
        return None if response.status_code == 204 else response.json()

    def csrf(self) -> dict[str, str]:
        token = self.checked(self.client.get("/api/v1/auth/csrf"))
        return {token["header_name"]: token["token"]}

    def login(self, email: str, password: str) -> None:
        self.checked(self.client.post("/api/v1/auth/login", data={"username": email, "password": password},
                                      headers=self.csrf()))
        profile = self.request("GET", "/auth/me")
        if profile.get("must_change_password"):
            raise PlatformError("请先在平台网页完成首次密码修改，然后重新运行。")

    def request(self, method: str, path: str, body: Any = None) -> Any:
        headers = self.csrf() if method != "GET" else {}
        return self.checked(self.client.request(method, "/api/v1" + path, json=body, headers=headers))

    def publish(self, kind: str, name: str, content: Any) -> str:
        resource = self.request("POST", "/catalog", {"kind": kind, "name": name, "content": content})
        return self.request("POST", f"/catalog/{resource['id']}/versions")["id"]

    def close(self) -> None:
        try:
            self.request("POST", "/auth/logout")
        finally:
            self.client.close()
