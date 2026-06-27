import { z } from "zod";

/**
 * Friendly validation copy for every Zod-powered form. Installed once at startup.
 */
export function installZodErrorMap(): void {
  z.setErrorMap((issue, ctx) => {
    switch (issue.code) {
      case z.ZodIssueCode.invalid_type:
        if (issue.received === "undefined" || issue.received === "null") {
          return { message: "Required" };
        }
        break;
      case z.ZodIssueCode.too_small:
        if (issue.type === "string") {
          if (issue.minimum === 1) return { message: "Required" };
          return { message: `Must be at least ${issue.minimum} characters` };
        }
        if (issue.type === "number") {
          return { message: `Must be at least ${issue.minimum}` };
        }
        break;
      case z.ZodIssueCode.too_big:
        if (issue.type === "string") {
          return { message: `Must be at most ${issue.maximum} characters` };
        }
        if (issue.type === "number") {
          return { message: `Must be at most ${issue.maximum}` };
        }
        break;
      case z.ZodIssueCode.invalid_string:
        if (issue.validation === "email") return { message: "Enter a valid email address" };
        if (issue.validation === "url") return { message: "Enter a valid URL" };
        if (issue.validation === "uuid") return { message: "Enter a valid ID" };
        break;
      case z.ZodIssueCode.invalid_enum_value:
        return { message: "Select a valid option" };
      case z.ZodIssueCode.custom:
        if (issue.message) return { message: issue.message };
        break;
    }
    return { message: ctx.defaultError };
  });
}
