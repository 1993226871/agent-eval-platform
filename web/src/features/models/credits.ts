import type { ModelChoice } from "../../api/types";
export function integerCredits(value: string): bigint {
  if (!/^-?\d+$/.test(value)) throw new Error("额度必须是整数。");
  return BigInt(value);
}
export function formatCredits(value: string | bigint) {
  return new Intl.NumberFormat("zh-CN").format(
    typeof value === "bigint" ? value : integerCredits(value),
  );
}
export function estimateReservation(
  model: ModelChoice,
  outputTokens: number,
): bigint {
  if (
    !Number.isSafeInteger(outputTokens) ||
    outputTokens < 1 ||
    outputTokens > model.max_output_tokens
  )
    throw new Error("输出 Token 上限超出模型范围。");
  const divisor = integerCredits(model.price.denominator);
  if (divisor <= 0n) throw new Error("价格比例无效。");
  const total =
    BigInt(model.input_token_bound) * integerCredits(model.price.input_rate) +
    BigInt(outputTokens) * integerCredits(model.price.output_rate);
  return (total + divisor - 1n) / divisor;
}
