import { describe, expect, it } from "vitest";
import { estimateReservation, formatCredits } from "./credits";

describe("integer credit estimates", () => {
  it("does not reserve platform money for a private API key", () => {
    expect(
      estimateReservation(
        {
          id: "m",
          name: "private",
          model: "deepseek-flash",
          billing_mode: "BYOK",
          input_token_bound: 1048576,
          max_output_tokens: 8192,
          price: {
            id: "p",
            input_rate: "2",
            output_rate: "8",
            denominator: "1",
          },
        },
        500,
      ),
    ).toBe(0n);
  });
  it("retains exact amounts beyond Number.MAX_SAFE_INTEGER and rounds up once", () => {
    const model = {
      id: "m",
      name: "judge",
      model: "test",
      input_token_bound: 3,
      max_output_tokens: 10,
      price: {
        id: "p",
        input_rate: "9007199254740993",
        output_rate: "5",
        denominator: "2",
      },
    };
    expect(estimateReservation(model, 2)).toBe(13510798882111495n);
    expect(formatCredits("9007199254740993")).toBe("9,007,199,254,740,993");
  });
  it("rejects invalid output token values", () => {
    const model = {
      id: "m",
      name: "judge",
      model: "test",
      input_token_bound: 3,
      max_output_tokens: 10,
      price: { id: "p", input_rate: "1", output_rate: "5", denominator: "2" },
    };
    expect(() => estimateReservation(model, NaN)).toThrow();
    expect(() => estimateReservation(model, 11)).toThrow();
  });
});
