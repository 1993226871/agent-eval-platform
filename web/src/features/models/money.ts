import { integerCredits } from "./credits";
export function parseCny(amount: string): string {
  if (!/^-?(0|[1-9][0-9]*)(\.[0-9]{1,6})?$/.test(amount))
    throw new Error("金额最多支持六位小数。");
  const negative = amount.startsWith("-");
  const [whole = "0", fraction = ""] = (
    negative ? amount.slice(1) : amount
  ).split(".");
  const value = BigInt(whole) * 1000000n + BigInt(fraction.padEnd(6, "0"));
  return (negative ? -value : value).toString();
}
export function formatCny(value: string | bigint): string {
  const raw = typeof value === "bigint" ? value : integerCredits(value);
  const absolute = raw < 0n ? -raw : raw;
  const whole = new Intl.NumberFormat("zh-CN").format(absolute / 1000000n);
  const fraction = (absolute % 1000000n)
    .toString()
    .padStart(6, "0")
    .replace(/0+$/, "")
    .padEnd(2, "0");
  return `${raw < 0n ? "-" : ""}¥${whole}.${fraction}`;
}
