import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
import { ResourceUsagePanel } from "./ResourceUsagePanel";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));
it("distinguishes cumulative Python allowance from money and shows shared evidence storage", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            limits: {
              max_online_workers: 5,
              max_running_trials: 2,
              max_stored_bytes: 1048576,
              max_experiments: 200,
              max_catalog_resources: 300,
              max_published_versions: 1000,
              max_trial_executions: 10,
              max_evaluation_jobs: 20,
              max_python_seconds: 60,
            },
            usage: {
              trial_executions: 3,
              evaluation_jobs: 4,
              python_reserved_seconds: 30,
              artifact_bytes: 1024,
              evidence_bytes: 2048,
            },
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
    ),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <ResourceUsagePanel />
    </QueryClientProvider>,
  );
  expect(await screen.findByText("30 / 60 秒")).toBeVisible();
  expect(screen.getByText("3 / 10 次")).toBeVisible();
  expect(screen.getByText("3 KiB / 1 MiB")).toBeVisible();
  expect(screen.getByText(/每个 Python 作业按 30 秒上界预留/)).toBeVisible();
});
