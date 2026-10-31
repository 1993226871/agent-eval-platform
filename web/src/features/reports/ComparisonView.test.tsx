import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { ComparisonView } from "./ComparisonView";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));

it("does not pair tasks from different dataset versions even if their indices match", async () => {
  const paths: string[] = [];
  const report = (id: string) => ({
    experiment_id: id,
    dataset_version_id: `dataset-${id}`,
    suite_version_id: "suite",
    evaluation_suite_version_ids: ["suite"],
    agent_version: "v1",
    dimensions: {},
    pass_rate_planned: { numerator: 0, denominator: 1 },
    pass_rate_determinable: { numerator: 0, denominator: 1 },
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      const path = new URL(req.url).pathname;
      paths.push(path);
      return new Response(
        JSON.stringify(
          path.endsWith("/comparison")
            ? {
                left: report("left"),
                right: report("right"),
                directly_comparable: false,
                reasons: ["DATASET_VERSION_MISMATCH"],
                dimension_deltas: {},
                left_provenance: {},
                right_provenance: {},
              }
            : {
                agent_name: "订单助手",
                dataset_name: "订单查询",
                dataset_version_number: 1,
                suite_name: "综合评分",
                suite_version_number: 1,
              },
        ),
        { headers: { "Content-Type": "application/json" } },
      );
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
      <MemoryRouter
        initialEntries={["/workspace/comparisons?left=left&right=right"]}
      >
        <ComparisonView />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(
    await screen.findByText(
      "任务集版本不同，不能按任务序号配对。请使用同一任务集版本重新创建对比实验。",
    ),
  ).toBeVisible();
  expect(
    screen.queryByRole("region", { name: "逐任务对比" }),
  ).not.toBeInTheDocument();
  expect(paths.some((path) => path.endsWith("/trials"))).toBe(false);
});
