import { useEffect, useState } from "react";
import { objectValue } from "./validation";

type Props = {
  label: string;
  value: unknown;
  onChange: (value: unknown) => void;
};
const defaults: Record<string, unknown> = {
  string: "",
  number: 0,
  boolean: false,
  object: {},
  array: [],
  null: null,
};

export function NumberField({
  label,
  value,
  onChange,
  min,
  max,
  step = "any",
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  step?: string | number;
}) {
  const [text, setText] = useState(String(value));
  useEffect(() => setText(String(value)), [value]);
  return (
    <label>
      {label}
      <input
        aria-label={label}
        type="number"
        required
        min={min}
        max={max}
        step={step}
        value={text}
        onChange={(event) => {
          setText(event.target.value);
          if (
            event.target.value !== "" &&
            Number.isFinite(event.target.valueAsNumber)
          )
            onChange(event.target.valueAsNumber);
        }}
      />
    </label>
  );
}

function ObjectField({
  name,
  value,
  label,
  onRename,
  onChange,
  onRemove,
}: Props & {
  name: string;
  onRename: (name: string) => void;
  onRemove: () => void;
}) {
  const [key, setKey] = useState(name);
  useEffect(() => setKey(name), [name]);
  return (
    <div className="value-row">
      <label>
        字段名
        <input
          aria-label={`${label} 字段名`}
          required
          value={key}
          onChange={(event) => {
            setKey(event.target.value);
            try {
              onRename(event.target.value);
              event.target.setCustomValidity("");
            } catch {
              event.target.setCustomValidity("字段名不能为空或重复");
            }
          }}
        />
      </label>
      <ValueFields label={label} value={value} onChange={onChange} />
      <button
        type="button"
        className="subtle danger"
        aria-label={`删除 ${label}`}
        onClick={onRemove}
      >
        删除字段
      </button>
    </div>
  );
}

export function ValueFields({ label, value, onChange }: Props) {
  const type =
    value === null ? "null" : Array.isArray(value) ? "array" : typeof value;
  const object = objectValue(value);
  const items = Array.isArray(value) ? value : [];
  return (
    <div className="value-fields">
      <label>
        {label} 类型
        <select
          aria-label={`${label} 类型`}
          value={type}
          onChange={(event) =>
            onChange(structuredClone(defaults[event.target.value]))
          }
        >
          <option value="string">文本</option>
          <option value="number">数字</option>
          <option value="boolean">是 / 否</option>
          <option value="object">字段组</option>
          <option value="array">列表</option>
          <option value="null">空值</option>
        </select>
      </label>
      {type === "string" && (
        <label>
          {label} 值
          <textarea
            aria-label={`${label} 值`}
            rows={2}
            value={String(value)}
            onChange={(event) => onChange(event.target.value)}
          />
        </label>
      )}
      {type === "number" && (
        <NumberField
          label={`${label} 值`}
          value={Number(value)}
          onChange={onChange}
        />
      )}
      {type === "boolean" && (
        <label>
          {label} 值
          <select
            aria-label={`${label} 值`}
            value={String(value)}
            onChange={(event) => onChange(event.target.value === "true")}
          >
            <option value="true">是</option>
            <option value="false">否</option>
          </select>
        </label>
      )}
      {type === "object" && (
        <div className="nested-fields">
          {Object.entries(object).map(([name, child], index) => (
            <ObjectField
              key={index}
              name={name}
              label={`${label}.${name}`}
              value={child}
              onChange={(next) => onChange({ ...object, [name]: next })}
              onRemove={() =>
                onChange(
                  Object.fromEntries(
                    Object.entries(object).filter(([key]) => key !== name),
                  ),
                )
              }
              onRename={(next) => {
                if (
                  !next.trim() ||
                  (next !== name && Object.hasOwn(object, next))
                )
                  throw new Error("字段名重复");
                onChange(
                  Object.fromEntries(
                    Object.entries(object).map(([key, child]) => [
                      key === name ? next : key,
                      child,
                    ]),
                  ),
                );
              }}
            />
          ))}
          <button
            type="button"
            onClick={() => {
              let index = 1;
              while (Object.hasOwn(object, `field_${index}`)) index++;
              onChange({ ...object, [`field_${index}`]: "" });
            }}
          >
            添加字段到 {label}
          </button>
        </div>
      )}
      {type === "array" && (
        <div className="nested-fields">
          {items.map((child, index) => (
            <div key={index}>
              <ValueFields
                label={`${label}[${index}]`}
                value={child}
                onChange={(next) =>
                  onChange(
                    items.map((item, position) =>
                      position === index ? next : item,
                    ),
                  )
                }
              />
              <button
                type="button"
                onClick={() =>
                  onChange(items.filter((_, position) => position !== index))
                }
              >
                删除列表项 {index + 1}
              </button>
            </div>
          ))}
          <button type="button" onClick={() => onChange([...items, ""])}>
            添加列表项到 {label}
          </button>
        </div>
      )}
    </div>
  );
}
