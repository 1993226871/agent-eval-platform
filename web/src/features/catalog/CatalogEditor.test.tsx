import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CatalogEditor } from "./CatalogEditor";

const draft = {
  id: "resource-1",
  kind: "DATASET" as const,
  name: "订单案例",
  revision: 2,
  archived: false,
  content: { cases: [{ instruction: "查询金额", input: { order_id: "A" } }] },
};
const published = {
  id: "version-1",
  resource_id: draft.id,
  kind: draft.kind,
  name: draft.name,
  number: 1,
  content_hash: "hash",
  content: { cases: [{ instruction: "原始任务", input: {} }] },
};
const summary = {
  id: published.id,
  number: 1,
  draft_revision: 1,
  content_hash: "hash",
};
function props() {
  return {
    resource: draft,
    versions: [summary],
    loadVersion: vi.fn().mockResolvedValue(published),
    onSave: vi.fn().mockResolvedValue(undefined),
    onPublish: vi.fn().mockResolvedValue(undefined),
    onArchive: vi.fn().mockResolvedValue(undefined),
    onImport: vi.fn().mockResolvedValue(undefined),
  };
}

describe("CatalogEditor", () => {
  it("keeps editing an incomplete LLM token limit without crashing the page", () => {
    const resource = {
      ...draft,
      kind: "EVALUATOR" as const,
      content: {
        type: "LLM",
        criterion_id: "judge",
        threshold: 1,
        required_evidence: [],
        config: { model_profile_id: "model-1", max_output_tokens: 10 },
      },
    };
    render(
      <CatalogEditor
        {...props()}
        resource={resource}
        models={[
          {
            id: "model-1",
            name: "Judge",
            model: "test",
            billing_mode: "PLATFORM" as const,
            input_token_bound: 100,
            max_output_tokens: 100,
            price: {
              id: "price-1",
              input_rate: "1",
              output_rate: "1",
              denominator: "1",
            },
          },
        ]}
      />,
    );
    const content = {
      ...resource.content,
      config: { ...resource.content.config, max_output_tokens: 0 },
    };
    expect(() =>
      fireEvent.change(screen.getByLabelText("草稿内容"), {
        target: { value: JSON.stringify(content) },
      }),
    ).not.toThrow();
    expect(screen.getByLabelText("草稿内容")).toHaveValue(
      JSON.stringify(content),
    );
  });
  it("keeps published versions immutable and permits returning to the draft", async () => {
    const user = userEvent.setup();
    const handlers = props();
    render(<CatalogEditor {...handlers} />);
    await user.selectOptions(
      screen.getByRole("combobox", { name: "查看版本" }),
      published.id,
    );
    expect(await screen.findByLabelText("已发布版本")).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "保存草稿" }),
    ).not.toBeInTheDocument();
    await user.selectOptions(
      screen.getByRole("combobox", { name: "查看版本" }),
      "draft",
    );
    expect(screen.getByLabelText("草稿内容")).not.toBeDisabled();
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() => expect(handlers.onSave).toHaveBeenCalledTimes(1));
  });

  it("explains archival and preserves JSONL error line numbers", async () => {
    const user = userEvent.setup();
    const handlers = props();
    handlers.onImport.mockRejectedValue(
      new Error("JSONL line 2: invalid JSON"),
    );
    render(<CatalogEditor {...handlers} />);
    await user.click(screen.getByRole("button", { name: "归档资源" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "已发布版本和历史结果会保留",
    );
    await user.click(screen.getByRole("button", { name: "确认归档" }));
    await waitFor(() => expect(handlers.onArchive).toHaveBeenCalledTimes(1));
    await user.click(screen.getByLabelText("JSONL 导入内容"));
    await user.paste('{"instruction":"test","input":{}}\ninvalid');
    await user.click(screen.getByRole("button", { name: "导入 JSONL" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("line 2");
  });
});
