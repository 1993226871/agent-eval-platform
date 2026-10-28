import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { CatalogEditor } from "./CatalogEditor";

const draft = {
  id: "resource-1",
  kind: "DATASET" as const,
  name: "订单任务集",
  revision: 2,
  archived: false,
  content: {
    cases: [
      {
        instruction: "查询订单",
        input: { order_id: "A" },
        expected: { amount: 100 },
      },
    ],
  },
};
function props() {
  return {
    resource: draft,
    versions: [
      { id: "v1", number: 1, draft_revision: 1, content_hash: "hash" },
    ],
    loadVersion: vi
      .fn<(id: string) => Promise<import("../../api/types").PublishedVersion>>()
      .mockResolvedValue({
        id: "v1",
        resource_id: draft.id,
        kind: "DATASET",
        name: draft.name,
        number: 1,
        content_hash: "hash",
        content: draft.content,
      }),
    onSave: vi
      .fn<
        (name: string, content: Record<string, unknown>) => Promise<unknown>
      >()
      .mockResolvedValue(undefined),
    onPublish: vi.fn(),
    onArchive: vi.fn(),
  };
}

it("edits tasks and typed inputs using fields without a JSON editor", async () => {
  const user = userEvent.setup();
  const handlers = props();
  render(<CatalogEditor {...handlers} />);
  expect(screen.queryByLabelText("草稿内容")).not.toBeInTheDocument();
  await user.clear(await screen.findByLabelText("任务要求 1"));
  await user.type(screen.getByLabelText("任务要求 1"), "查询订单金额");
  await user.clear(screen.getByLabelText("任务 1 输入.order_id 值"));
  await user.type(screen.getByLabelText("任务 1 输入.order_id 值"), "B");
  await user.click(screen.getByRole("button", { name: "新增任务" }));
  await user.type(screen.getByLabelText("任务要求 2"), "查询不存在订单");
  await user.click(screen.getByRole("button", { name: "保存草稿" }));
  await waitFor(() => expect(handlers.onSave).toHaveBeenCalled());
  const content = handlers.onSave.mock.calls[0]![1] as typeof draft.content;
  expect(content.cases[0]!.input.order_id).toBe("B");
  expect(content.cases[0]!.expected.amount).toBe(100);
  expect(content.cases).toHaveLength(2);
});

it("shows immutable published tasks in the same form", async () => {
  const user = userEvent.setup();
  render(<CatalogEditor {...props()} />);
  await user.selectOptions(screen.getByLabelText("查看版本"), "v1");
  expect(await screen.findByLabelText("任务要求 1")).toBeDisabled();
  expect(
    screen.queryByRole("button", { name: "保存草稿" }),
  ).not.toBeInTheDocument();
});

it("adds inline LLM rubric rules and publishes the set without choosing evaluator versions", async () => {
  const user = userEvent.setup();
  const handlers = props();
  render(
    <CatalogEditor
      {...handlers}
      resource={{ ...draft, kind: "SUITE", content: { entries: [] } }}
      models={[
        {
          id: "11111111-1111-4111-8111-111111111111",
          name: "我的评审模型",
          model: "test",
          billing_mode: "BYOK",
          input_token_bound: 1000,
          max_output_tokens: 1000,
          price: {
            id: "22222222-2222-4222-8222-222222222222",
            input_rate: "1",
            output_rate: "1",
            denominator: "1",
          },
        },
      ]}
    />,
  );
  await user.click(await screen.findByRole("button", { name: "新增评分规则" }));
  await user.selectOptions(screen.getByLabelText("评分方式 1"), "LLM");
  await user.selectOptions(
    screen.getByLabelText("评审模型 1"),
    "11111111-1111-4111-8111-111111111111",
  );
  await user.clear(screen.getByLabelText("评分目标 1"));
  await user.type(
    screen.getByLabelText("评分目标 1"),
    "回答是否忠实于订单查询结果",
  );
  expect(screen.getByLabelText("规则 1 · 4 分标准")).toBeInTheDocument();
  expect(screen.queryByLabelText("评测器版本")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "保存草稿" }));
  await waitFor(() => expect(handlers.onSave).toHaveBeenCalled());
  const content = handlers.onSave.mock.calls[0]![1] as {
    entries: {
      definition: {
        type: string;
        config: { rubric: { metric: string; levels: unknown[] } };
      };
    }[];
  };
  expect(content.entries[0]!.definition.type).toBe("LLM");
  expect(content.entries[0]!.definition.config.rubric.levels).toHaveLength(5);
  expect(content.entries[0]!.definition.config.rubric.metric).toBe(
    "回答是否忠实于订单查询结果",
  );
});

it("loads existing referenced rules into the unified form", async () => {
  const handlers = props();
  const definition = {
    type: "RULE",
    criterion_id: "amount",
    threshold: 1,
    required_evidence: ["output"],
    config: { operator: "eq", selector: "/output/amount", expected: 100 },
  };
  handlers.loadVersion.mockResolvedValue({
    ...(await handlers.loadVersion("v1")),
    kind: "EVALUATOR",
    content: definition,
  });
  render(
    <CatalogEditor
      {...handlers}
      resource={{
        ...draft,
        kind: "SUITE",
        content: {
          entries: [
            {
              evaluator_version: "v1",
              dimension: "outcome",
              required_to_pass: true,
              weight: 1,
            },
          ],
        },
      }}
    />,
  );
  expect(await screen.findByLabelText("规则名称 1")).toHaveValue("amount");
  expect(screen.getByLabelText("规则 1 期望值 值")).toHaveValue(100);
});

it("does not expose an editable partial rule set when referenced rules fail to load", async () => {
  const handlers = props();
  handlers.loadVersion.mockRejectedValue(new Error("规则加载失败"));
  render(
    <CatalogEditor
      {...handlers}
      resource={{
        ...draft,
        kind: "SUITE",
        content: {
          entries: [
            {
              evaluator_version: "v1",
              dimension: "outcome",
              required_to_pass: true,
              weight: 1,
            },
          ],
        },
      }}
    />,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("规则加载失败");
  expect(
    screen.queryByRole("button", { name: "保存草稿" }),
  ).not.toBeInTheDocument();
});
