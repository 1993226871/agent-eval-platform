import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice, JsonBlock } from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { collectPages } from "../catalog/pagination";
import { objectValue } from "../catalog/validation";
import { recoveryTask, type CaseSource } from "./diagnostics";

export function CaseRecovery({
  task,
  source,
}: {
  task: Record<string, unknown>;
  source: CaseSource;
}) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [open, setOpen] = useState(false);
  const [target, setTarget] = useState("");
  const [name, setName] = useState("失败样例回归");
  const [reviewed, setReviewed] = useState(false);
  const targets = useQuery({
    queryKey: [workspace_id, "recovery-datasets"],
    enabled: open,
    queryFn: ({ signal }) =>
      collectPages(
        (offset, limit) =>
          request(
            api.GET("/api/v1/catalog-summaries", {
              params: { query: { kind: "DATASET", offset, limit } },
              signal,
            }),
          ),
        signal,
      ),
  });
  const save = useMutation({
    retry: false,
    mutationFn: async () => {
      const copied = recoveryTask(task, source);
      if (!target)
        return request(
          api.POST("/api/v1/catalog", {
            body: {
              kind: "DATASET",
              name: name.trim(),
              content: { cases: [copied] },
            },
          }),
        );
      // Read immediately before saving, then rely on the server's revision check.
      const draft = await request(
        api.GET("/api/v1/catalog/{id}", { params: { path: { id: target } } }),
      );
      const content = objectValue(draft.content);
      if (draft.archived || draft.kind !== "DATASET")
        throw new Error("请选择未归档的任务集。");
      const cases = Array.isArray(content.cases) ? content.cases : [];
      if (cases.length >= 1000)
        throw new Error("任务集已达到1000道任务，请选择其他任务集。");
      if (
        cases.some(
          (raw) =>
            objectValue(objectValue(raw).source).snapshot_id ===
            source.snapshot_id,
        )
      )
        throw new Error("该证据快照已加入此草稿，无需重复添加。");
      return request(
        api.PUT("/api/v1/catalog/{id}", {
          params: { path: { id: target } },
          body: {
            name: draft.name,
            revision: draft.revision,
            content: { ...content, cases: [...cases, copied] },
          },
        }),
      );
    },
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: [workspace_id] });
    },
  });
  if (save.data)
    return (
      <div role="status">
        <p>已保存到草稿，尚未发布或启动评测。</p>
        <Link
          to={`/workspace/datasets?resource=${encodeURIComponent(save.data.id)}`}
        >
          打开任务集草稿
        </Link>
      </div>
    );
  return (
    <section className="case-recovery">
      <button onClick={() => setOpen(!open)} aria-expanded={open}>
        加入任务集草稿
      </button>
      {open && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
        >
          <p>
            复制任务输入和原参考答案，保留实验与证据来源。Agent
            的实际回答不会被当作参考答案。
          </p>
          <label>
            目标任务集
            <select
              value={target}
              onChange={(event) => {
                setTarget(event.target.value);
                save.reset();
              }}
              disabled={save.isPending}
            >
              <option value="">新建任务集草稿</option>
              {targets.data
                ?.filter((item) => !item.archived)
                .map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
            </select>
          </label>
          {!target && (
            <label>
              新任务集名称
              <input
                required
                maxLength={200}
                value={name}
                onChange={(event) => setName(event.target.value)}
                disabled={save.isPending}
              />
            </label>
          )}
          <details>
            <summary>核对将复制的任务和参考答案</summary>
            <JsonBlock value={task} />
          </details>
          <label className="check-label">
            <input
              type="checkbox"
              checked={reviewed}
              onChange={(event) => setReviewed(event.target.checked)}
              disabled={save.isPending}
            />
            已核对任务和参考答案；缺少的参考答案将在草稿中补充
          </label>
          <ErrorNotice error={save.error ?? targets.error} />
          <button
            className="primary"
            disabled={!reviewed || (!target && !name.trim()) || save.isPending}
          >
            保存到草稿
          </button>
        </form>
      )}
    </section>
  );
}
