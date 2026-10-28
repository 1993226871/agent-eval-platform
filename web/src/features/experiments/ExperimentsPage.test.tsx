import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ExperimentsPage } from "./ExperimentsPage";

vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
vi.mock("../catalog/queries", () => ({
  useVersionOptions: (kind: string) => ({
    data: [{ id: kind, label: kind }],
    error: null,
  }),
}));
describe("experiment submission recovery", () => {
  it("reuses the same complete request after an uncertain network failure", async () => {
    const bodies: unknown[] = [];
    vi.spyOn(Date, "now").mockReturnValue(1792962000000);
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const request =
          input instanceof Request ? input : new Request(input, init);
        const path = new URL(request.url).pathname;
        let body: unknown = [];
        let status = 200;
        if (path.endsWith("/auth/csrf"))
          body = { header_name: "X-CSRF-TOKEN", token: "csrf" };
        if (path.endsWith("/experiments/estimate"))
          body = {
            planned_credits: "54043195528445958",
            planned_calls: 6,
            trial_count: 6,
            basis: "FIRST_ATTEMPT_RESERVATION_BOUND",
            assumptions: [],
          };
        if (path.endsWith("/agents"))
          body = [
            {
              id: "agent",
              workspace_id: "workspace",
              name: "Agent",
              version: "v1",
            },
          ];
        if (path.endsWith("/experiments") && request.method === "POST") {
          bodies.push(await request.json());
          body =
            bodies.length === 1
              ? { code: "REQUEST_FAILED" }
              : { id: "created" };
          status = bodies.length === 1 ? 503 : 201;
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
          <ExperimentsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "新建实验" }));
    await user.selectOptions(
      await screen.findByLabelText("Agent", { exact: true }),
      "agent",
    );
    await user.selectOptions(screen.getByLabelText("任务集版本"), "DATASET");
    await user.selectOptions(screen.getByLabelText("评分规则集版本"), "SUITE");
    expect(
      await screen.findByText(/¥54,043,195,528\.445958/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "启动实验" }));
    await screen.findByRole("alert");
    vi.mocked(Date.now).mockReturnValue(1792962030000);
    await user.click(screen.getByRole("button", { name: "启动实验" }));
    await screen.findByRole("heading", { name: /任务执行列表/ });
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toEqual(bodies[0]);
  });
});

it("identifies experiments by Agent and fixed configuration and explains task executions", async () => {
  const summary = {
    id: "experiment-readable",
    workspace_id: "workspace",
    agent_id: "agent",
    agent_name: "订单对话助手",
    agent_version: "v2",
    dataset_version_id: "DATASET",
    dataset_name: "订单查询任务集",
    dataset_version_number: 3,
    suite_version_id: "SUITE",
    suite_name: "订单回答质量",
    suite_version_number: 2,
    case_count: 3,
    task_count: 6,
    repeat_count: 2,
    status: "RUNNING",
    created_at: "2026-10-28T12:00:00Z",
    deadline: "2026-10-29T12:00:00Z",
    source_experiment_id: null,
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request =
        input instanceof Request ? input : new Request(input, init);
      const path = new URL(request.url).pathname;
      let body: unknown = [];
      if (path.endsWith("/experiment-summaries")) body = [summary];
      if (path.endsWith("/experiment-summaries/experiment-readable"))
        body = summary;
      if (path.endsWith("/agents"))
        body = [{ id: "agent", name: "订单对话助手", version: "v2" }];
      if (path.endsWith("/trials"))
        body = [
          {
            id: "trial-readable",
            experiment_id: summary.id,
            case_index: 0,
            attempt: 2,
            status: "SUCCEEDED",
            task: {
              instruction: "查询订单 A 的状态和金额",
              input: { order_id: "A" },
            },
          },
        ];
      return new Response(JSON.stringify(body), {
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
        <ExperimentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  expect(await screen.findByText("订单对话助手")).toBeVisible();
  expect(screen.getByText(/订单查询任务集/)).toBeVisible();
  expect(screen.getByText(/订单回答质量/)).toBeVisible();
  await user.click(screen.getByRole("button", { name: "查看 6 次任务执行" }));
  expect(await screen.findByText("查询订单 A 的状态和金额")).toBeVisible();
  expect(screen.getByText(/3 道任务.*每道 2 次.*6 次执行/)).toBeVisible();
  expect(screen.getByText("第 2 次")).toBeVisible();
  expect(screen.getByText("执行成功")).toBeVisible();
});
