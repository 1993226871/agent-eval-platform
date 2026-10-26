import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { PreviewPanel } from "./PreviewPanel";

vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
it("replays an uncertain paid preview and rotates its identity only after success", async () => {
  const bodies: Record<string, unknown>[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      let body: unknown = { header_name: "X-CSRF-TOKEN", token: "csrf" };
      let status = 200;
      if (req.method === "POST") {
        bodies.push(await req.json());
        status = bodies.length === 1 ? 503 : 200;
        body =
          bodies.length === 1 ? { code: "REQUEST_FAILED" } : { run_id: "run" };
      } else if (req.url.endsWith("/evaluation-runs/run"))
        body = {
          status: "COMPLETED",
          purpose: "PREVIEW",
          wait_reasons: [],
          criteria: [],
        };
      return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter>
        <PreviewPanel
          resource={{
            id: "draft",
            kind: "EVALUATOR",
            name: "Judge",
            revision: 1,
            archived: false,
            content: {},
          }}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(screen.getByText("用已有任务证据试运行"));
  await user.type(
    screen.getByLabelText("试运行 Trial ID"),
    "9b0de8ef-5623-4654-887b-895926dfcb2c",
  );
  await user.click(screen.getByRole("button", { name: "提交试运行" }));
  await screen.findByRole("alert");
  await user.click(screen.getByRole("button", { name: "提交试运行" }));
  await screen.findByText(/状态：COMPLETED/);
  expect(bodies[1]).toEqual(bodies[0]);
  await user.click(screen.getByRole("button", { name: "提交试运行" }));
  await waitFor(() => expect(bodies).toHaveLength(3));
  expect(bodies[2]?.idempotency_key).not.toEqual(bodies[1]?.idempotency_key);
});
