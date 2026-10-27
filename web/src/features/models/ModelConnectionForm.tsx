import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, request } from "../../api/client";
import { ErrorNotice } from "../../components/ui";

export function ModelConnectionForm({
  mode,
  onSaved,
}: {
  mode: "PLATFORM" | "BYOK";
  onSaved: () => void | Promise<unknown>;
}) {
  const [model, setModel] = useState("");
  const [endpoint, setEndpoint] = useState("");
  const [key, setKey] = useState("");
  const save = useMutation({
    mutationFn: () =>
      request(
        api.POST(
          mode === "BYOK"
            ? "/api/v1/models/connections"
            : "/api/v1/admin/models/connections",
          {
            body: {
              model: model.trim(),
              endpoint: endpoint.trim(),
              api_key: key,
            },
          },
        ),
      ),
    onSuccess: async () => {
      setKey("");
      setModel("");
      setEndpoint("");
      await onSaved();
    },
  });
  return (
    <form
      className="model-form"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate();
      }}
    >
      <label>
        模型名称
        <input
          required
          autoComplete="off"
          placeholder="deepseek-flash"
          value={model}
          onChange={(e) => setModel(e.target.value)}
        />
      </label>
      <label>
        API 地址
        <input
          required
          type="url"
          placeholder="https://api.deepseek.com"
          value={endpoint}
          onChange={(e) => setEndpoint(e.target.value)}
        />
      </label>
      <label>
        API Key
        <input
          required
          type="password"
          autoComplete="new-password"
          value={key}
          onChange={(e) => setKey(e.target.value)}
        />
      </label>
      <p className="hint">
        模型参数和请求路径由平台自动配置。目前已适配
        deepseek-flash、deepseek-v4-pro。Key 加密保存，保存后不会返回。
      </p>
      <p className="hint">
        {mode === "BYOK"
          ? "仅本人工作空间可用；费用由你的模型服务商直接收取，不扣平台额度，余额为零仍可使用。"
          : "供用户选择，按平台公示的人民币价格版本扣减额度。不会因保存配置自动请求模型。"}
      </p>
      <ErrorNotice error={save.error} />
      {save.isSuccess && <p role="status">模型已保存。</p>}
      <button className="primary" disabled={save.isPending}>
        {mode === "BYOK" ? "保存我的模型" : "保存平台模型"}
      </button>
    </form>
  );
}
