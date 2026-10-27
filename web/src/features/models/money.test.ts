import { expect, it } from "vitest";
import { formatCny, parseCny } from "./money";
it("converts exact yuan strings to integer micro-yuan without floating point", () => {
  expect(parseCny("5")).toBe("5000000");
  expect(parseCny("0.000015")).toBe("15");
  expect(formatCny("5000000")).toBe("¥5.00");
  expect(formatCny("15")).toBe("¥0.000015");
  expect(formatCny("9007199254740993")).toBe("¥9,007,199,254.740993");
  expect(() => parseCny("0.0000001")).toThrow();
  expect(() => parseCny("NaN")).toThrow();
});
