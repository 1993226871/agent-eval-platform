import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { CaseRecovery } from "./CaseRecovery";
vi.mock("../auth/AuthProvider", () => ({
  useWorkspace: () => ({ workspace_id: "workspace" }),
}));

const task = {
  instruction: "查询订单",
  input: { order: "A" },
  expected: { amount: 100 },
};
const source = {
  experiment_id: "exp",
  trial_id: "trial",
  snapshot_id: "snap",
  content_hash: "hash",
};
function setup(conflict = false) {
  const writes: {
    method: string;
    path: string;
    body: Record<string, unknown>;
  }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(input, init);
      const path = new URL(req.url).pathname;
      let body: unknown = [],
        status = 200;
      if (path.endsWith("csrf"))
        body = { header_name: "X-CSRF-TOKEN", token: "fixture" };
      if (path.endsWith("catalog-summaries"))
        body = [{ id: "existing", name: "回归题库", archived: false }];
      if (path.endsWith("catalog/existing"))
        body = {
          id: "existing",
          kind: "DATASET",
          name: "回归题库",
          revision: 7,
          content: {
            cases: [{ instruction: "已有任务", input: "hi" }],
            tag: "preserved",
          },
        };
      if (["POST", "PUT"].includes(req.method)) {
        writes.push({ method: req.method, path, body: await req.json() });
        body = { id: "saved" };
        if (conflict) {
          status = 409;
          body = { code: "CONFLICT" };
        }
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
          defaultOptions: {
            queries: { retry: false, gcTime: 0 },
            mutations: { retry: false },
          },
        })
      }
    >
      <MemoryRouter>
        <CaseRecovery task={task} source={source} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return writes;
}
it("creates an unpublished draft with source and requires reference review", async () => {
  const writes = setup();
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "加入任务集草稿" }));
  expect(screen.getByRole("button", { name: "保存到草稿" })).toBeDisabled();
  await user.click(
    screen.getByLabelText("已核对任务和参考答案；缺少的参考答案将在草稿中补充"),
  );
  await user.click(screen.getByRole("button", { name: "保存到草稿" }));
  await screen.findByText("已保存到草稿，尚未发布或启动评测。");
  expect(writes).toHaveLength(1);
  expect(writes[0]).toMatchObject({
    method: "POST",
    path: "/api/v1/catalog",
    body: {
      kind: "DATASET",
      content: {
        cases: [{ ...task, source: { type: "evaluation_trial", ...source } }],
      },
    },
  });
  expect(screen.getByRole("link", { name: "打开任务集草稿" })).toHaveAttribute(
    "href",
    "/workspace/datasets?resource=saved",
  );
});
it("appends using the latest revision and preserves existing tasks and metadata", async () => {
  const writes = setup();
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "加入任务集草稿" }));
  await screen.findByRole("option", { name: "回归题库" });
  await user.selectOptions(screen.getByLabelText("目标任务集"), "existing");
  await user.click(
    screen.getByLabelText("已核对任务和参考答案；缺少的参考答案将在草稿中补充"),
  );
  await user.click(screen.getByRole("button", { name: "保存到草稿" }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({
    method: "PUT",
    path: "/api/v1/catalog/existing",
    body: {
      revision: 7,
      name: "回归题库",
      content: {
        tag: "preserved",
        cases: [
          { instruction: "已有任务", input: "hi" },
          { ...task, source: { type: "evaluation_trial", ...source } },
        ],
      },
    },
  });
});
it("shows conflicts without retrying or overwriting another edit", async () => {
  const writes = setup(true);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "加入任务集草稿" }));
  await screen.findByRole("option", { name: "回归题库" });
  await user.selectOptions(screen.getByLabelText("目标任务集"), "existing");
  await user.click(
    screen.getByLabelText("已核对任务和参考答案；缺少的参考答案将在草稿中补充"),
  );
  await user.click(screen.getByRole("button", { name: "保存到草稿" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("内容已变化");
  expect(writes).toHaveLength(1);
  expect(
    screen.queryByText("已保存到草稿，尚未发布或启动评测。"),
  ).not.toBeInTheDocument();
});
