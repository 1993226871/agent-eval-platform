"""Only used by disposable acceptance fixtures. No real model credentials or external requests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import threading

calls = {}
lock = threading.Lock()


class Judge(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    def do_GET(self):
        if self.path != "/counts": self.send_error(404); return
        with lock: body = json.dumps(calls).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if self.path != "/v1/chat/completions" or not 0 < length <= 65536: self.send_error(400); return
        request = json.loads(self.rfile.read(length))
        key = self.headers.get("Idempotency-Key", "missing")
        with lock: calls[key] = calls.get(key, 0) + 1
        if request["model"] == "disconnect-after-accept":
            self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
        content = json.dumps({"status": "SCORED", "grade": 4, "reason": "Fixture output satisfies the objective", "evidence_refs": ["output"]})
        body = json.dumps({"id": "local-judge", "model": request["model"], "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
                           "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)


if __name__ == "__main__": ThreadingHTTPServer(("0.0.0.0", 8089), Judge).serve_forever()
