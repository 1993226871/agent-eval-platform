import { useEffect, useState } from "react";
import type {
  CatalogResource,
  ModelChoice,
  PublishedVersion,
  VersionSummary,
} from "../../api/types";
import { Dialog, ErrorNotice, Loading } from "../../components/ui";
import { formatCredits, estimateReservation } from "../models/credits";
import { formatCny } from "../models/money";
import { objectValue, parseContent, pretty } from "./validation";

export type VersionOption = { id: string; label: string };
type Props = {
  resource: CatalogResource;
  versions: VersionSummary[];
  loadVersion: (id: string) => Promise<PublishedVersion>;
  onSave: (name: string, content: Record<string, unknown>) => Promise<unknown>;
  onPublish: () => Promise<unknown>;
  onArchive: () => Promise<unknown>;
  onImport?: (jsonl: string) => Promise<unknown>;
  evaluatorVersions?: VersionOption[];
  models?: ModelChoice[];
  children?: React.ReactNode;
};
export function CatalogEditor({
  resource,
  versions,
  loadVersion,
  onSave,
  onPublish,
  onArchive,
  onImport,
  evaluatorVersions = [],
  models = [],
  children,
}: Props) {
  const [version, setVersion] = useState("draft");
  const [published, setPublished] = useState<PublishedVersion>();
  const [name, setName] = useState(resource.name);
  const [source, setSource] = useState(pretty(resource.content));
  const [jsonl, setJsonl] = useState("");
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [archive, setArchive] = useState(false);
  useEffect(() => {
    setName(resource.name);
    setSource(pretty(resource.content));
    setVersion("draft");
    setError(undefined);
  }, [resource.id, resource.revision]);
  useEffect(() => {
    let active = true;
    setPublished(undefined);
    if (version !== "draft")
      loadVersion(version)
        .then((value) => {
          if (active) setPublished(value);
        })
        .catch((failure) => {
          if (active) setError(failure);
        });
    return () => {
      active = false;
    };
  }, [version, loadVersion]);
  const readonly = version !== "draft";
  const dirty = name !== resource.name || source !== pretty(resource.content);
  let current: Record<string, unknown> = {};
  try {
    current = objectValue(JSON.parse(source));
  } catch {
    /* Draft can be temporarily incomplete while typing. */
  }
  const config = objectValue(current.config);
  const selectedModel = models.find(
    (model) => model.id === config.model_profile_id,
  );
  let reservation: string | undefined;
  if (selectedModel) {
    try {
      reservation = (
        selectedModel.billing_mode === "PLATFORM" ? formatCny : formatCredits
      )(
        estimateReservation(
          selectedModel,
          Number(config.max_output_tokens ?? 500),
        ),
      );
    } catch {
      /* The raw JSON editor permits unfinished numeric values. */
    }
  }
  async function act(action: () => Promise<unknown>) {
    setError(undefined);
    setBusy(true);
    try {
      await action();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  function updateConfig(patch: Record<string, unknown>) {
    setSource(pretty({ ...current, config: { ...config, ...patch } }));
  }
  return (
    <section className="editor-pane" aria-label="资源编辑器">
      <div className="editor-toolbar">
        <h2>{resource.name}</h2>
        <label>
          查看版本
          <select
            value={version}
            onChange={(event) => setVersion(event.target.value)}
          >
            <option value="draft">草稿（修订 {resource.revision}）</option>
            {versions.map((item) => (
              <option key={item.id} value={item.id}>
                v{item.number}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorNotice error={error} />
      {readonly ? (
        published ? (
          <>
            <p className="hint">已发布版本固定保存，修改请回到草稿。</p>
            <label>
              已发布版本
              <textarea
                className="code-editor"
                value={pretty(published.content)}
                disabled
              />
            </label>
          </>
        ) : (
          <Loading />
        )
      ) : (
        <>
          <label>
            资源名称
            <input
              value={name}
              maxLength={200}
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          {resource.kind === "SUITE" && (
            <SuiteFields
              content={current}
              options={evaluatorVersions}
              onChange={(value) => setSource(pretty(value))}
            />
          )}
          {resource.kind === "EVALUATOR" && current.type === "LLM" && (
            <div className="model-binding">
              <label>
                评审模型
                <select
                  value={
                    typeof config.model_profile_id === "string"
                      ? config.model_profile_id
                      : ""
                  }
                  onChange={(event) => {
                    const model = models.find(
                      (item) => item.id === event.target.value,
                    );
                    if (model)
                      updateConfig({
                        model_profile_id: model.id,
                        price_version_id: model.price.id,
                        max_output_tokens: Math.min(
                          500,
                          model.max_output_tokens,
                        ),
                      });
                  }}
                >
                  <option value="">选择已配置的模型</option>
                  {models.map((model) => (
                    <option key={model.id} value={model.id}>
                      {model.name}
                    </option>
                  ))}
                </select>
              </label>
              {selectedModel && (
                <p className="hint">
                  {reservation
                    ? selectedModel?.billing_mode === "BYOK"
                      ? "使用本人的 API Key，不扣平台额度；费用由服务商收取。"
                      : `单次调用预留上界：${reservation}${selectedModel?.billing_mode === "PLATFORM" ? "" : " credits"}。实际消耗按用量结算，重试与重评另计。`
                    : "请填写模型允许范围内的整数输出 Token 上限后查看估算。"}
                </p>
              )}
            </div>
          )}
          {resource.kind === "EVALUATOR" && current.type === "PYTHON" && (
            <label>
              Python 评测代码
              <textarea
                className="code-editor"
                spellCheck={false}
                value={typeof config.code === "string" ? config.code : ""}
                onChange={(event) => updateConfig({ code: event.target.value })}
              />
              <span className="hint">
                定义 evaluate(context)，返回 status、score、reason 和
                evidence_refs。代码由平台强化沙箱执行；未启用合格 Runner
                时试运行返回 ERROR。
              </span>
            </label>
          )}
          <label>
            草稿内容
            <textarea
              className="code-editor"
              spellCheck={false}
              value={source}
              onChange={(event) => setSource(event.target.value)}
            />
          </label>
          <div className="actions">
            <button
              className="primary"
              disabled={busy}
              onClick={() =>
                act(() => {
                  if (!name.trim()) throw new Error("请填写资源名称。");
                  return onSave(
                    name.trim(),
                    parseContent(source, resource.kind),
                  );
                })
              }
            >
              保存草稿
            </button>
            <button
              disabled={busy || dirty}
              title={dirty ? "请先保存修改，再发布版本。" : undefined}
              onClick={() => act(onPublish)}
            >
              发布版本
            </button>
            <button
              className="danger subtle"
              disabled={busy}
              onClick={() => setArchive(true)}
            >
              归档资源
            </button>
          </div>
          {dirty && (
            <p className="hint">有未保存的修改。保存后可发布或试运行。</p>
          )}
          {resource.kind === "DATASET" && onImport && (
            <details className="import-section" open>
              <summary>导入 JSONL 案例</summary>
              <p>
                每行一个任务，包含 instruction 和
                input；导入会替换当前草稿案例。
              </p>
              <label>
                JSONL 导入内容
                <textarea
                  spellCheck={false}
                  value={jsonl}
                  onChange={(event) => setJsonl(event.target.value)}
                />
              </label>
              <button
                disabled={busy || !jsonl.trim()}
                onClick={() => act(() => onImport(jsonl))}
              >
                导入 JSONL
              </button>
            </details>
          )}
          {!dirty && children}
        </>
      )}
      <Dialog open={archive} title="归档资源" onClose={() => setArchive(false)}>
        <p>归档后不再用于新实验，已发布版本和历史结果会保留。</p>
        <div className="actions">
          <button onClick={() => setArchive(false)}>取消</button>
          <button
            className="danger"
            disabled={busy}
            onClick={() =>
              act(async () => {
                await onArchive();
                setArchive(false);
              })
            }
          >
            确认归档
          </button>
        </div>
      </Dialog>
    </section>
  );
}
function SuiteFields({
  content,
  options,
  onChange,
}: {
  content: Record<string, unknown>;
  options: VersionOption[];
  onChange: (value: Record<string, unknown>) => void;
}) {
  const entries = Array.isArray(content.entries)
    ? content.entries.map(objectValue)
    : [];
  function edit(index: number, patch: Record<string, unknown>) {
    onChange({
      ...content,
      entries: entries.map((entry, position) =>
        position === index ? { ...entry, ...patch } : entry,
      ),
    });
  }
  return (
    <fieldset className="suite-fields">
      <legend>评分套件条目</legend>
      {entries.map((entry, index) => (
        <div className="suite-row" key={index}>
          <label>
            评测器版本
            <select
              value={String(entry.evaluator_version ?? "")}
              onChange={(event) =>
                edit(index, { evaluator_version: event.target.value })
              }
            >
              <option value="">选择已发布版本</option>
              {options.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.label}
                </option>
              ))}
              {typeof entry.evaluator_version === "string" &&
                !options.some(
                  (option) => option.id === entry.evaluator_version,
                ) && (
                  <option value={entry.evaluator_version}>
                    {entry.evaluator_version}
                  </option>
                )}
            </select>
          </label>
          <label>
            维度
            <select
              value={String(entry.dimension ?? "outcome")}
              onChange={(event) =>
                edit(index, { dimension: event.target.value })
              }
            >
              <option value="outcome">结果质量</option>
              <option value="process">执行过程</option>
            </select>
          </label>
          <label>
            权重
            <input
              type="number"
              min={0}
              max={1000000}
              step="any"
              value={Number(entry.weight ?? 1)}
              onChange={(event) =>
                edit(index, { weight: Number(event.target.value) })
              }
            />
          </label>
          <label className="check-label">
            <input
              type="checkbox"
              checked={entry.required_to_pass === true}
              onChange={(event) =>
                edit(index, { required_to_pass: event.target.checked })
              }
            />
            必需通过
          </label>
          <button
            onClick={() =>
              onChange({
                ...content,
                entries: entries.filter((_, position) => position !== index),
              })
            }
          >
            移除
          </button>
        </div>
      ))}
      <button
        onClick={() =>
          onChange({
            ...content,
            entries: [
              ...entries,
              {
                evaluator_version: options[0]?.id ?? "",
                dimension: "outcome",
                required_to_pass: true,
                weight: 1,
              },
            ],
          })
        }
      >
        添加评测项
      </button>
    </fieldset>
  );
}
