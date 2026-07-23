import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { z } from "zod";
import { installZodErrorMap } from "./zod-error-map";

function messageFor(schema: z.ZodTypeAny, value: unknown): string {
  const result = schema.safeParse(value);
  if (result.success) throw new Error("Expected validation to fail");
  return result.error.issues[0]?.message ?? "";
}

describe("installZodErrorMap", () => {
  beforeEach(installZodErrorMap);
  afterEach(() => z.setErrorMap(z.defaultErrorMap));

  it("uses concise required and size messages", () => {
    expect(messageFor(z.string().min(1), "")).toBe("Required");
    expect(messageFor(z.string().min(3), "a")).toBe("Must be at least 3 characters");
    expect(messageFor(z.string().max(3), "abcd")).toBe("Must be at most 3 characters");
    expect(messageFor(z.number().min(2), 1)).toBe("Must be at least 2");
    expect(messageFor(z.number().max(2), 3)).toBe("Must be at most 2");
  });

  it("identifies missing required values without replacing other type errors", () => {
    expect(messageFor(z.string(), undefined)).toBe("Required");
    expect(
      messageFor(
        z
          .string()
          .nullable()
          .refine((value) => value !== null),
        null,
      ),
    ).not.toBe("");
    expect(messageFor(z.number(), "one")).toContain("number");
  });

  it("uses field-specific messages for common string formats", () => {
    expect(messageFor(z.string().email(), "invalid")).toBe("Enter a valid email address");
    expect(messageFor(z.string().url(), "invalid")).toBe("Enter a valid URL");
    expect(messageFor(z.string().uuid(), "invalid")).toBe("Enter a valid ID");
  });

  it("handles enums and preserves explicit custom messages", () => {
    expect(messageFor(z.enum(["ACTIVE", "DISABLED"]), "UNKNOWN")).toBe("Select a valid option");
    expect(
      messageFor(
        z.string().refine(() => false, "Domain-specific message"),
        "value",
      ),
    ).toBe("Domain-specific message");
  });
});
