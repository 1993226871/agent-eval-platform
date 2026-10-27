import type { Schema } from "../../api/types";
import { JsonBlock } from "../../components/ui";
import { formatCny } from "../models/money";
import { formatCredits } from "../models/credits";

const states: Record<string, { title: string; description: string }> = {
  SUCCEEDED: { title: "测试成功", description: "模型接口已正常响应。" },
  RECONCILED: {
    title: "调用费用已核对",
    description: "费用已完成核对；这不代表丢失的响应内容已经恢复。",
  },
  WAITING_QUOTA: {
    title: "平台额度不足",
    description: "请求尚未发送。请补充本人平台额度，或使用自己的 API Key。",
  },
  WAITING_CAPACITY: {
    title: "等待可用并发",
    description: "请求尚未发送，请稍后查询本次测试。",
  },
  RESERVED: {
    title: "测试准备中",
    description: "已预留额度，尚未取得模型结果。",
  },
  SEND_INTENT: {
    title: "等待模型响应",
    description: "本次请求已经开始发送，请稍后查询结果。",
  },
  PENDING_RECONCILIATION: {
    title: "测试结果待核对",
    description:
      "请求可能已经产生服务商费用，结果或用量尚未确认。再次点击只查询本次调用，不会自动重发。",
  },
  FAILED_UNSENT: {
    title: "请求未发送",
    description: "发送前检查未通过，本次未产生模型调用费用。",
  },
  MODEL_DISABLED: {
    title: "模型已停用",
    description: "该模型当前不可用，请选择其他模型。",
  },
  CANCELLED: { title: "测试已取消", description: "本次调用已取消。" },
};

export function ModelTestResult({
  modelName,
  billingMode,
  result,
}: {
  modelName: string;
  billingMode: Schema<"ModelProfile">["billing_mode"];
  result: Schema<"ModelCallResolution">;
}) {
  const state = states[result.status] ?? {
    title: "测试状态待确认",
    description: "请展开技术详情查看本次调用状态。",
  };
  const usage = (value: string | null) =>
    value == null ? "待确认" : formatCredits(value);
  const cost =
    billingMode === "BYOK"
      ? "不扣平台额度"
      : result.credits == null
        ? "待确认"
        : formatCny(result.credits);
  return (
    <section
      className="panel"
      role="status"
      aria-label="模型测试结果"
      aria-live="polite"
    >
      <h2>{state.title}</h2>
      <p>
        {modelName}：
        {billingMode === "BYOK" && result.status === "RESERVED"
          ? "请求已准备，尚未取得模型结果。"
          : state.description}
      </p>
      <dl className="metrics model-test-metrics">
        <div>
          <dt>输入 Token</dt>
          <dd>{usage(result.input_tokens)}</dd>
        </div>
        <div>
          <dt>输出 Token</dt>
          <dd>{usage(result.output_tokens)}</dd>
        </div>
        <div>
          <dt>本次平台扣款</dt>
          <dd>{cost}</dd>
        </div>
      </dl>
      {billingMode === "BYOK" && (
        <p className="hint">使用本人的 API Key，由你的模型服务商计费。</p>
      )}
      <details>
        <summary>技术详情</summary>
        <JsonBlock value={result} />
      </details>
    </section>
  );
}
