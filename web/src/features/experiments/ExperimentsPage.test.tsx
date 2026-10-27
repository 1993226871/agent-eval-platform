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
    await user.selectOptions(screen.getByLabelText("套件版本"), "SUITE");
    expect(
      await screen.findByText(/¥54,043,195,528\.445958/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "启动实验" }));
    await screen.findByRole("alert");
    vi.mocked(Date.now).mockReturnValue(1792962030000);
    await user.click(screen.getByRole("button", { name: "启动实验" }));
    await screen.findByRole("heading", { name: /任务列表/ });
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toEqual(bodies[0]);
  });
});
