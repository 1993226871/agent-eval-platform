import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { AdminPage } from "./AdminPage";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
it("shows each account's CNY and legacy balances and refreshes them after an adjustment", async () => {
  let granted = 5000000n;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      if (req.url.endsWith("/auth/csrf"))
        return Response.json({ header_name: "X-CSRF-TOKEN", token: "csrf" });
      const wallet = () => ({
        workspace_id: "ws-money",
        unit: "CNY_MICRO",
        granted: granted.toString(),
        consumed: "124",
        reserved: "0",
        available: (granted - 124n).toString(),
      });
      if (req.method === "POST" && req.url.endsWith("/money-adjustments")) {
        granted += 1000000n;
        return Response.json(wallet());
      }
      if (new URL(req.url).pathname.endsWith("/admin/users"))
        return Response.json([
          {
            user_id: "money",
            workspace_id: "ws-money",
            email: "money@example.test",
            admin: false,
            must_change_password: false,
            quota: wallet(),
          },
          {
            user_id: "legacy",
            workspace_id: "ws-legacy",
            email: "legacy@example.test",
            admin: false,
            must_change_password: false,
            quota: {
              workspace_id: "ws-legacy",
              unit: "CREDITS",
              granted: "77",
              consumed: "0",
              reserved: "0",
              available: "77",
            },
          },
        ]);
      return Response.json([]);
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
  const row = await screen.findByRole("row", { name: /money@example.test/ });
  expect(within(row).getByText("¥4.999876")).toBeVisible();
  const oldRow = screen.getByRole("row", { name: /legacy@example.test/ });
  expect(within(oldRow).getAllByText("77 credits")).toHaveLength(2);
  const user = userEvent.setup();
  await user.click(within(row).getByRole("button", { name: "调整额度" }));
  expect(screen.getByLabelText("用户工作空间")).toHaveValue("ws-money");
  await user.type(screen.getByLabelText("调整金额（元）"), "1");
  await user.type(screen.getByLabelText("调整原因"), "追加测试预算");
  await user.click(screen.getByRole("button", { name: "提交额度调整" }));
  await waitFor(() =>
    expect(
      within(screen.getByRole("row", { name: /money@example.test/ })).getByText(
        "¥5.999876",
      ),
    ).toBeVisible(),
  );
});
it("explains a legacy wallet mismatch instead of blaming model form fields", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      if (req.url.endsWith("/auth/csrf"))
        return Response.json({ header_name: "X-CSRF-TOKEN", token: "csrf" });
      if (req.url.endsWith("/test"))
        return Response.json(
          { code: "BILLING_UNIT_MISMATCH" },
          { status: 409 },
        );
      if (req.url.endsWith("/admin/models"))
        return Response.json([
          {
            id: "model",
            name: "Judge",
            model: "deepseek-flash",
            enabled: true,
            billing_mode: "PLATFORM",
            price: { input_rate: "2", output_rate: "8", denominator: "1" },
          },
        ]);
      return Response.json([]);
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
  await user.click(
    await screen.findByRole("button", { name: "测试（消耗本人额度）" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "当前账户的额度单位与模型计价单位不一致",
  );
  expect(screen.getByRole("alert")).toHaveTextContent("自带 Key");
  expect(screen.getByRole("alert")).not.toHaveTextContent("请检查填写的内容");
});
it.each(["LEGACY", "BYOK"])(
  "preserves a connectivity call identity after lost or pending responses (%s)",
  async (mode) => {
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
              billing_mode: mode,
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
                    bodies.length === 2
                      ? "PENDING_RECONCILIATION"
                      : "SUCCEEDED",
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
      name: mode === "BYOK" ? "测试（服务商计费）" : "测试（消耗本人额度）",
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
  },
);
