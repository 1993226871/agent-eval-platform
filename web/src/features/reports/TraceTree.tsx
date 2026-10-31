import { useId, useState } from "react";
import type { Schema } from "../../api/types";
import { Badge, JsonBlock } from "../../components/ui";

type Span = Schema<"Span">;
type Props = {
  spans: Span[];
  completeness: string;
  issues: string[];
  highlightedSpan?: string;
};
const issueLabels: Record<string, string> = {
  truncated_or_redacted: "截断或脱敏",
  sequence_gap: "事件序号缺失",
  event_count_mismatch: "事件数量不一致",
  dropped_events: "事件曾被丢弃",
  incomplete_span_tree: "父子关系不完整或存在循环",
  missing_artifact: "附件缺失",
  missing_completion: "缺少结束结果",
};

export function TraceTree({
  spans,
  completeness,
  issues,
  highlightedSpan,
}: Props) {
  const treeId = useId();
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const unique = [
    ...new Map(spans.map((span) => [span.span_id, span])).values(),
  ];
  const ids = new Set(unique.map((span) => span.span_id));
  const children = new Map<string, Span[]>();
  for (const span of unique)
    if (span.parent_span_id && ids.has(span.parent_span_id))
      children.set(span.parent_span_id, [
        ...(children.get(span.parent_span_id) ?? []),
        span,
      ]);
  const visited = new Set<string>();
  function toggle(id: string) {
    setExpanded((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
  function node(span: Span, depth: number): React.ReactNode {
    if (visited.has(span.span_id)) return null;
    visited.add(span.span_id);
    const open = expanded.has(span.span_id);
    return (
      <li key={span.span_id} data-span={span.span_id}>
        <div
          id={treeId + "-span-" + span.span_id}
          data-highlighted={
            highlightedSpan === span.span_id ? "true" : undefined
          }
          className="trace-node"
        >
          <button
            className="trace-toggle"
            aria-expanded={open}
            onClick={() => toggle(span.span_id)}
          >
            <span aria-hidden>{open ? "−" : "+"}</span>
            <strong>{span.name}</strong>
            <Badge>{span.kind}</Badge>
            <span>{span.status}</span>
            <span>
              {span.duration_ms === null
                ? "耗时未知"
                : `${span.duration_ms} ms`}
            </span>
          </button>
          <small>
            来源：{span.origin} · {span.span_id}
          </small>
          {open && (
            <div className="trace-detail">
              <h3>输入</h3>
              <JsonBlock value={span.input} />
              <h3>输出 / 中间结果</h3>
              <JsonBlock value={span.output} />
            </div>
          )}
        </div>
        {depth < 100 ? (
          <ul>
            {children.get(span.span_id)?.map((child) => node(child, depth + 1))}
          </ul>
        ) : (
          <p className="hint">层级过深，其余节点在下方单独列出。</p>
        )}
      </li>
    );
  }
  const roots = unique.filter(
    (span) => !span.parent_span_id || !ids.has(span.parent_span_id),
  );
  const rootNodes = roots.map((span) => node(span, 0));
  const detached = unique.filter((span) => !visited.has(span.span_id));
  const detachedNodes = detached.map((span) => node(span, 0));
  return (
    <section className="trace-tree" aria-label="执行轨迹树">
      <div className="actions">
        <h2>执行轨迹</h2>
        <Badge tone={completeness === "COMPLETE" ? "success" : "warning"}>
          {completeness === "COMPLETE" ? "证据完整" : "证据不完整"}
        </Badge>
      </div>
      <p className="hint">
        树结构来自 Worker
        上报。冻结快照用于评分，当前轨迹可能包含迟到事件；缺失父节点也可能尚未载入。
      </p>
      {issues.length > 0 && (
        <ul className="evidence-issues">
          {issues.map((issue) => (
            <li key={issue}>{issueLabels[issue] ?? issue}</li>
          ))}
        </ul>
      )}
      <ul className="trace-roots">
        {rootNodes}
        {detachedNodes}
      </ul>
      {unique.length === 0 && <p>没有已上报的 Span。</p>}
    </section>
  );
}
