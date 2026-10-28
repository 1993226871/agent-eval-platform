import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { ExperimentReport } from "./ExperimentReport";

vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));

it("keeps the Agent and fixed configuration visible even when report scores are unavailable", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request =
        input instanceof Request ? input : new Request(input, init);
      const path = new URL(request.url).pathname;
      let body: unknown = [],
        status = 200;
      if (path.endsWith("/experiment-summaries/experiment-1"))
        body = {
          id: "experiment-1",
          agent_id: "agent-1",
          agent_name: "订单对话助手",
          agent_version: "v2",
          dataset_name: "订单查询",
          dataset_version_number: 3,
          suite_name: "回答质量",
          suite_version_number: 2,
          case_count: 3,
          repeat_count: 2,
          task_count: 6,
          created_at: "2026-10-28T12:00:00Z",
          source_experiment_id: null,
        };
      if (path.endsWith("/reports/experiments/experiment-1")) {
        body = { code: "REQUEST_FAILED" };
        status = 503;
      }
      if (path.endsWith("/trials")) body = { items: [], next_cursor: null };
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
      <MemoryRouter initialEntries={["/workspace/reports/experiment-1"]}>
        <Routes>
          <Route path="/workspace/reports/:id" element={<ExperimentReport />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(
    await screen.findByRole("heading", { name: "订单对话助手 的评测报告" }),
  ).toBeVisible();
  const context = within(screen.getByRole("region", { name: "关联实验信息" }));
  expect(context.getByText(/任务集：订单查询 · v3/)).toBeVisible();
  expect(context.getByText(/评分规则集：回答质量 · v2/)).toBeVisible();
  expect(context.getByText("3 道任务 × 每道 2 次 = 6 次执行")).toBeVisible();
  expect(context.getByText("experiment-1")).not.toBeVisible();
});
