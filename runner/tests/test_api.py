import asyncio
import httpx
from agent_eval_runner.api import create_app
from test_executor import executor
from helpers import request

KEY = "runner-internal-test-key-32-characters-minimum"


def test_internal_auth_and_request_size_limit(tmp_path):
    async def check():
        payload = request()
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(executor(tmp_path), KEY)), base_url="http://runner") as client:
            assert (await client.post("/internal/v1/evaluations", json=request())).status_code == 401
            headers = {"Authorization": "Bearer " + KEY}
            response = await client.post("/internal/v1/evaluations", json=payload, headers=headers)
            assert response.status_code == 200
            assert response.json()["result"]["score"] == 1
            assert (await client.post("/internal/v1/evaluations", content=b"x" * (3 * 1024 * 1024 + 1), headers=headers)).status_code == 413
            assert (await client.get("/internal/v1/capabilities", headers=headers)).json()["python_enabled"] is True
            job = payload["job_id"]
            assert (await client.delete(f"/internal/v1/evaluations/{job}/evidence")).status_code == 401
            assert (await client.delete(f"/internal/v1/evaluations/{job}/evidence", headers=headers)).status_code == 204
            deleted = await client.post("/internal/v1/evaluations", json=payload, headers=headers)
            assert deleted.status_code == 200 and deleted.json()["code"] == "EVIDENCE_DELETED"
            async def chunks():
                for _ in range(4):
                    yield b"x" * (1024 * 1024)
            assert (await client.post("/internal/v1/evaluations", content=chunks(), headers=headers)).status_code == 413
    asyncio.run(check())
