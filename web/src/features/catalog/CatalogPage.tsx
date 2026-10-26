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

const labels = { DATASET: "任务集", EVALUATOR: "评测器", SUITE: "评分套件" };
const initial: Record<ResourceKind, Record<string, unknown>> = {
  DATASET: { cases: [{ instruction: "请完成任务", input: {}, expected: {} }] },
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
  const [modelId, setModelId] = useState("");
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
  const judges = useQuery({
    queryKey: [workspace_id, "judge-templates"],
    enabled: kind === "EVALUATOR",
    queryFn: () => request(api.GET("/api/v1/templates/judges")),
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
        description="编辑草稿、发布固定版本，为实验选择可重复的评测配置。"
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
              action.mutate(() =>
                request(api.POST("/api/v1/templates/starter/copy")),
              )
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
        {kind === "EVALUATOR" && (
          <>
            <label>
              示例使用的评审模型
              <select
                value={modelId}
                onChange={(event) => setModelId(event.target.value)}
              >
                <option value="">选择模型</option>
                {models.data?.map((model) => (
                  <option key={model.id} value={model.id}>
                    {model.name}
                  </option>
                ))}
              </select>
            </label>
            <div className="actions">
              {judges.data?.map((item) => (
                <button
                  key={item.id}
                  disabled={!modelId || action.isPending}
                  onClick={() =>
                    action.mutate(async () => {
                      const model = models.data?.find(
                        (value) => value.id === modelId,
                      );
                      if (!model) return;
                      const copy = await request(
                        api.POST("/api/v1/templates/judges/{id}/copy", {
                          params: { path: { id: item.id } },
                          body: {
                            model_profile_id: model.id,
                            price_version_id: model.price.id,
                          },
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
            <ErrorNotice error={models.error ?? judges.error} />
          </>
        )}
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
            onImport={(jsonl) =>
              saved(
                request(
                  api.POST("/api/v1/catalog/{id}/import", {
                    params: { path: { id: selected } },
                    body: { revision: detail.data!.revision, jsonl },
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
