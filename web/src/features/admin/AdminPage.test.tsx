import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { AdminPage } from "./AdminPage";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
it("preserves a connectivity call identity after lost or pending responses", async () => {
  const bodies: { call_id: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      let body: unknown = [],
        status = 200;
      if (req.url.endsWith("/auth/csrf"))
        body = { header_name: "X-CSRF-TOKEN", token: "csrf" };
      if (req.url.endsWith("/admin/models"))
        body = [
          {
            id: "model",
            name: "Judge",
            model: "judge",
            enabled: true,
            input_token_bound: 10,
            max_output_tokens: 10,
            price: { input_rate: "1", output_rate: "1", denominator: "1" },
          },
        ];
      if (req.url.endsWith("/test")) {
        bodies.push(await req.json());
        status = bodies.length === 1 ? 503 : 200;
        body =
          bodies.length === 1
            ? { code: "REQUEST_FAILED" }
            : {
                call_id: bodies.at(-1)?.call_id,
                status:
                  bodies.length === 2 ? "PENDING_RECONCILIATION" : "SUCCEEDED",
              };
      }
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
        <AdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "模型配置" }));
  const button = await screen.findByRole("button", {
    name: "测试（消耗本人额度）",
  });
  await user.click(button);
  await screen.findByRole("alert");
  await user.click(button);
  await waitFor(() => expect(button).toBeEnabled());
  await user.click(button);
  await waitFor(() => expect(bodies).toHaveLength(3));
  expect(bodies[1]).toEqual(bodies[0]);
  expect(bodies[2]).toEqual(bodies[0]);
  await waitFor(() => expect(button).toBeEnabled());
  await user.click(button);
  await waitFor(() => expect(bodies).toHaveLength(4));
  expect(bodies[3]?.call_id).not.toEqual(bodies[0]?.call_id);
});
