import { objectValue } from "./validation";
import { NumberField, ValueFields } from "./ValueFields";
import { Link } from "react-router-dom";

export function DatasetFields({
  content,
  onChange,
}: {
  content: Record<string, unknown>;
  onChange: (content: Record<string, unknown>) => void;
}) {
  const cases = Array.isArray(content.cases)
    ? content.cases.map(objectValue)
    : [];
  function edit(index: number, patch: Record<string, unknown>) {
    onChange({
      ...content,
      cases: cases.map((item, position) =>
        position === index ? { ...item, ...patch } : item,
      ),
    });
  }
  return (
    <section aria-label="任务列表" className="catalog-fields">
      <div className="section-heading">
        <h3>任务列表</h3>
        <span>{cases.length} 道任务</span>
      </div>
      <p className="hint">
        每道任务独立运行。输入字段与 Agent 代码约定一致；参考答案供评分使用。
      </p>
      {cases.map((task, index) => (
        <fieldset className="definition-fields" key={index}>
          <legend>任务 {index + 1}</legend>
          {objectValue(task.source).type === "evaluation_trial" && (
            <details className="identity-details">
              <summary>查看样例摘录来源</summary>
              <p>此来源随草稿保存，修改后不代表仍与原任务一致。</p>
              <p>
                实验：{String(objectValue(task.source).experiment_id)} · 执行：
                {String(objectValue(task.source).trial_id)}
              </p>
              <p>证据快照：{String(objectValue(task.source).snapshot_id)}</p>
              <p>内容指纹：{String(objectValue(task.source).content_hash)}</p>
              <Link
                to={`/workspace/reports/${encodeURIComponent(String(objectValue(task.source).experiment_id))}`}
              >
                查看来源报告
              </Link>
            </details>
          )}
          <label>
            任务要求 {index + 1}
            <textarea
              aria-label={`任务要求 ${index + 1}`}
              required
              rows={3}
              value={String(task.instruction ?? "")}
              onChange={(event) =>
                edit(index, { instruction: event.target.value })
              }
            />
          </label>
          <ValueFields
            label={`任务 ${index + 1} 输入`}
            value={task.input}
            onChange={(value) => edit(index, { input: value })}
          />
          <label className="check-label">
            <input
              type="checkbox"
              checked={Object.hasOwn(task, "expected")}
              onChange={(event) => {
                if (event.target.checked) edit(index, { expected: "" });
                else {
                  const next = { ...task };
                  delete next.expected;
                  onChange({
                    ...content,
                    cases: cases.map((item, position) =>
                      position === index ? next : item,
                    ),
                  });
                }
              }}
            />
            提供参考答案
          </label>
          {Object.hasOwn(task, "expected") && (
            <ValueFields
              label={`任务 ${index + 1} 参考答案`}
              value={task.expected}
              onChange={(value) => edit(index, { expected: value })}
            />
          )}
          <NumberField
            label={`任务 ${index + 1} 超时秒数`}
            value={Number(task.timeout_seconds ?? 300)}
            min={1}
            max={3600}
            step={1}
            onChange={(value) => edit(index, { timeout_seconds: value })}
          />
          <button
            type="button"
            className="danger subtle"
            onClick={() =>
              onChange({
                ...content,
                cases: cases.filter((_, position) => position !== index),
              })
            }
          >
            删除任务 {index + 1}
          </button>
        </fieldset>
      ))}
      <button
        type="button"
        disabled={cases.length >= 1000}
        onClick={() =>
          onChange({
            ...content,
            cases: [...cases, { instruction: "", input: { question: "" } }],
          })
        }
      >
        新增任务
      </button>
    </section>
  );
}
