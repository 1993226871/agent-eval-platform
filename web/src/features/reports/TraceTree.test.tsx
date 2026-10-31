import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { TraceTree } from "./TraceTree";

describe("trace evidence tree", () => {
  it("places out-of-order children under parents, labels incomplete capture, and escapes content", async () => {
    const common = {
      id: 1,
      kind: "tool",
      status: "COMPLETED",
      started_at: null,
      ended_at: null,
      duration_ms: 2,
      origin: "worker_reported",
      input: {},
      output: "<script>globalThis.traceExecuted=true</script>",
    };
    render(
      <TraceTree
        spans={[
          {
            ...common,
            span_id: "child",
            parent_span_id: "root",
            name: "query_order",
          },
          {
            ...common,
            id: 2,
            span_id: "root",
            parent_span_id: null,
            kind: "agent",
            name: "Agent",
          },
        ]}
        completeness="PARTIAL"
        issues={["truncated_or_redacted"]}
        highlightedSpan="child"
      />,
    );
    expect(screen.getByText("证据不完整")).toBeVisible();
    expect(screen.getByText("截断或脱敏")).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: /query_order/ }));
    expect(
      screen.getByText('"<script>globalThis.traceExecuted=true</script>"'),
    ).toBeVisible();
    expect(document.querySelector("script")).toBeNull();
    expect(
      document.querySelector('[data-span="child"] > .trace-node'),
    ).toHaveAttribute("data-highlighted", "true");
    expect(
      document
        .querySelector('[data-span="child"] > .trace-node')
        ?.closest('[data-span="root"]'),
    ).not.toBeNull();
  });
  it("keeps orphaned and cyclic spans visible without infinite recursion", () => {
    const common = {
      id: 1,
      name: "missing-parent",
      kind: "tool",
      status: "RUNNING",
      started_at: null,
      ended_at: null,
      duration_ms: null,
      origin: "worker_reported",
      input: null,
      output: null,
    };
    render(
      <TraceTree
        spans={[
          { ...common, span_id: "a", parent_span_id: "b" },
          { ...common, id: 2, span_id: "b", parent_span_id: "a" },
        ]}
        completeness="PARTIAL"
        issues={["incomplete_span_tree"]}
      />,
    );
    expect(screen.getAllByText("missing-parent")).toHaveLength(2);
    expect(screen.getByText("父子关系不完整或存在循环")).toBeVisible();
  });
});
