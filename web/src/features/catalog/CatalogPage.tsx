import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import type { ResourceKind } from "../../api/types";
import {
  Dialog,
  Empty,
  ErrorNotice,
  Loading,
  PageHeading,
} from "../../components/ui";
import { useWorkspace } from "../auth/AuthProvider";
import { CatalogEditor } from "./CatalogEditor";
import { useCatalog, useModels, useVersionOptions } from "./queries";
import { PreviewPanel } from "./PreviewPanel";

const labels = {
  DATASET: "任务集",
  EVALUATOR: "评分规则",
  SUITE: "评分规则集",
};
const initial: Record<ResourceKind, Record<string, unknown>> = {
  DATASET: { cases: [] },
  EVALUATOR: {
    type: "RULE",
    criterion_id: "answer_check",
    threshold: 1,
    required_evidence: ["output"],
    config: { operator: "eq", selector: "/output", expected: "answer" },
  },
  SUITE: { entries: [] },
};
export function CatalogPage({ kind }: { kind: ResourceKind }) {
  const { workspace_id } = useWorkspace();
  const cache = useQueryClient();
  const [offset, setOffset] = useState(0);
  const list = useCatalog(kind, offset);
  const [selected, setSelected] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const models = useModels();
  const evaluatorVersions = useVersionOptions("EVALUATOR");
  const detail = useQuery({
    queryKey: [workspace_id, "resource", selected],
    enabled: !!selected,
    queryFn: () =>
      request(
        api.GET("/api/v1/catalog/{id}", { params: { path: { id: selected } } }),
      ),
  });
  const versions = useQuery({
    queryKey: [workspace_id, "versions", selected],
    enabled: !!selected,
    queryFn: () =>
      request(
        api.GET("/api/v1/catalog/{id}/versions", {
          params: { path: { id: selected }, query: { limit: 100 } },
        }),
      ),
  });
  const templates = useQuery({
    queryKey: [workspace_id, "templates"],
    queryFn: () => request(api.GET("/api/v1/templates")),
  });
  const refresh = () => cache.invalidateQueries({ queryKey: [workspace_id] });
  const action = useMutation({
    mutationFn: async (operation: () => Promise<unknown>) => operation(),
    onSuccess: refresh,
  });
  const create = useMutation({
    mutationFn: () =>
      request(
        api.POST("/api/v1/catalog", {
          body: { kind, name: name.trim(), content: initial[kind] },
        }),
      ),
    onSuccess: async (value) => {
      setSelected(value.id);
      setCreateOpen(false);
      setName("");
      await refresh();
    },
  });
  const loadVersion = useCallback(
    (id: string) =>
      request(
        api.GET("/api/v1/catalog/versions/{id}", { params: { path: { id } } }),
      ),
    [],
  );
  async function saved(operation: Promise<unknown>) {
    await operation;
    await refresh();
  }
  return (
    <>
      <PageHeading
        title={labels[kind]}
        description={
          kind === "DATASET"
            ? "逐条编写任务、输入和参考答案，发布后用于实验。"
            : "在一个规则集中配置检查条件、LLM Rubric 或 Python 评分，整体发布后用于实验。"
        }
      >
        <button className="primary" onClick={() => setCreateOpen(true)}>
          新建{labels[kind]}
        </button>
      </PageHeading>
      <ErrorNotice
        error={list.error ?? detail.error ?? versions.error ?? action.error}
      />
      <details className="template-strip">
        <summary>从平台示例开始</summary>
        <div className="actions">
          <button
            disabled={action.isPending}
            onClick={() =>
              action.mutate(async () => {
                const bundle = await request(
                  api.POST("/api/v1/templates/starter/copy"),
                );
                setSelected(
                  kind === "DATASET"
                    ? bundle.dataset_version.resource_id
                    : bundle.suite_version.resource_id,
                );
              })
            }
          >
            复制完整入门示例
          </button>
          {templates.data
            ?.filter((item) => item.kind === kind)
            .map((item) => (
              <button
                key={item.id}
                disabled={action.isPending}
                onClick={() =>
                  action.mutate(async () => {
                    const copy = await request(
                      api.POST("/api/v1/templates/{id}/copy", {
                        params: { path: { id: item.id } },
                      }),
                    );
                    setSelected(copy.id);
                  })
                }
              >
                复制 {item.name}
              </button>
            ))}
        </div>
      </details>
      <div className="catalog-layout">
        <section className="resource-list" aria-label={labels[kind] + "列表"}>
          {list.isPending ? (
            <Loading />
          ) : list.data?.length ? (
            list.data.map((item) => (
              <button
                key={item.id}
                className={
                  selected === item.id ? "resource selected" : "resource"
                }
                onClick={() => setSelected(item.id)}
              >
                <strong>{item.name}</strong>
                <span>
                  草稿 r{item.revision}
                  {item.archived ? " · 已归档" : ""}
                </span>
              </button>
            ))
          ) : (
            <Empty title="尚无资源">新建一个草稿，或复制平台示例。</Empty>
          )}
          <div className="pagination">
            <button
              disabled={!offset}
              onClick={() => setOffset(Math.max(0, offset - 25))}
            >
              上一页
            </button>
            <button
              disabled={list.data?.length !== 25}
              onClick={() => setOffset(offset + 25)}
            >
              下一页
            </button>
          </div>
        </section>
        {detail.isPending && selected ? (
          <Loading />
        ) : detail.data && !detail.data.archived ? (
          <CatalogEditor
            key={detail.data.id}
            resource={detail.data}
            versions={versions.data ?? []}
            loadVersion={loadVersion}
            models={models.data ?? []}
            evaluatorVersions={evaluatorVersions.data ?? []}
            onSave={(name, content) =>
              saved(
                request(
                  api.PUT("/api/v1/catalog/{id}", {
                    params: { path: { id: selected } },
                    body: { name, content, revision: detail.data!.revision },
                  }),
                ),
              )
            }
            onPublish={() =>
              saved(
                request(
                  api.POST("/api/v1/catalog/{id}/versions", {
                    params: { path: { id: selected } },
                  }),
                ),
              )
            }
            onArchive={() =>
              saved(
                request(
                  api.DELETE("/api/v1/catalog/{id}", {
                    params: { path: { id: selected } },
                  }),
                ),
              )
            }
          >
            {kind === "EVALUATOR" && <PreviewPanel resource={detail.data} />}
          </CatalogEditor>
        ) : (
          <Empty title={detail.data?.archived ? "资源已归档" : "选择一个资源"}>
            已发布版本和历史实验会保留。
          </Empty>
        )}
      </div>
      <Dialog
        open={createOpen}
        title={"新建" + labels[kind]}
        onClose={() => setCreateOpen(false)}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            create.mutate();
          }}
        >
          <label>
            名称
            <input
              required
              maxLength={200}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <ErrorNotice error={create.error} />
          <button
            className="primary"
            disabled={create.isPending || !name.trim()}
          >
            创建草稿
          </button>
        </form>
      </Dialog>
    </>
  );
}
