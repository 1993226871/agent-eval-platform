import { useState } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import { ValueFields } from "./ValueFields";

function Example() {
  const [value, setValue] = useState<unknown>({
    question: "hello",
    count: 2,
    enabled: true,
    notes: [null],
  });
  return (
    <>
      <ValueFields label="输入" value={value} onChange={setValue} />
      <output aria-label="实际值">{JSON.stringify(value)}</output>
    </>
  );
}
it("preserves nested numeric, boolean and null types while editing values", async () => {
  const user = userEvent.setup();
  render(<Example />);
  await user.clear(screen.getByLabelText("输入.count 值"));
  await user.type(screen.getByLabelText("输入.count 值"), "3");
  await user.selectOptions(screen.getByLabelText("输入.enabled 值"), "false");
  const value = JSON.parse(screen.getByLabelText("实际值").textContent!);
  expect(value).toEqual({
    question: "hello",
    count: 3,
    enabled: false,
    notes: [null],
  });
});
it("renames fields without losing their values and rejects duplicate names", async () => {
  const user = userEvent.setup();
  render(<Example />);
  const name = screen.getByLabelText("输入.question 字段名");
  await user.clear(name);
  await user.type(name, "message");
  expect(JSON.parse(screen.getByLabelText("实际值").textContent!).message).toBe(
    "hello",
  );
  await user.clear(name);
  await user.type(name, "count");
  expect(name).toBeInvalid();
});
