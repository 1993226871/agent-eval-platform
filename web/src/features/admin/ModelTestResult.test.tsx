import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import { ModelTestResult } from "./ModelTestResult";

const success = {
  call_id: "test-call",
  status: "SUCCEEDED",
  input_tokens: "42",
  output_tokens: "5",
  credits: "124",
};
it("shows a readable success and micro-yuan charge, keeping raw JSON collapsed", async () => {
  render(
    <ModelTestResult
      modelName="DeepSeek"
      billingMode="PLATFORM"
      result={success}
    />,
  );
  expect(screen.getByRole("heading", { name: "测试成功" })).toBeVisible();
  expect(screen.getByText("42")).toBeVisible();
  expect(screen.getByText("5")).toBeVisible();
  expect(screen.getByText("¥0.000124")).toBeVisible();
  expect(screen.getByText(/"call_id"/)).not.toBeVisible();
  await userEvent.click(screen.getByText("技术详情"));
  expect(screen.getByText(/"call_id"/)).toBeVisible();
});
it("explains private-key charges without representing supplier usage as platform spending", () => {
  render(
    <ModelTestResult
      modelName="My Key"
      billingMode="BYOK"
      result={{ ...success, credits: "0" }}
    />,
  );
  expect(screen.getByText("不扣平台额度")).toBeVisible();
  expect(screen.getByText(/由你的模型服务商计费/)).toBeVisible();
});
it("does not claim that a private-key dispatch reserves platform money", () => {
  render(
    <ModelTestResult
      modelName="My Key"
      billingMode="BYOK"
      result={{ ...success, status: "RESERVED", credits: null }}
    />,
  );
  expect(screen.getByText("不扣平台额度")).toBeVisible();
  expect(screen.queryByText(/已预留额度/)).not.toBeInTheDocument();
});
it.each([
  ["WAITING_QUOTA", "平台额度不足"],
  ["PENDING_RECONCILIATION", "测试结果待核对"],
])("does not show %s as success or zero cost", (status, title) => {
  render(
    <ModelTestResult
      modelName="DeepSeek"
      billingMode="PLATFORM"
      result={{
        ...success,
        status,
        input_tokens: null,
        output_tokens: null,
        credits: null,
      }}
    />,
  );
  expect(screen.getByRole("heading", { name: title })).toBeVisible();
  expect(
    screen.queryByRole("heading", { name: "测试成功" }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText("¥0.00")).not.toBeInTheDocument();
});
