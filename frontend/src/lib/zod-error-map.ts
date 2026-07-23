import { z } from "zod";

type ErrorMap = Parameters<typeof z.setErrorMap>[0];
type ZodIssue = Parameters<ErrorMap>[0];

function invalidTypeMessage(issue: ZodIssue): string | null {
  if (issue.code !== z.ZodIssueCode.invalid_type) return null;
  return issue.received === "undefined" || issue.received === "null" ? "Required" : null;
}

function sizeMessage(issue: ZodIssue): string | null {
  if (issue.code !== z.ZodIssueCode.too_small && issue.code !== z.ZodIssueCode.too_big) {
    return null;
  }
  if (issue.type !== "string" && issue.type !== "number") return null;
  if (issue.code === z.ZodIssueCode.too_small) {
    if (issue.type === "string" && issue.minimum === 1) return "Required";
    return `Must be at least ${issue.minimum}${issue.type === "string" ? " characters" : ""}`;
  }
  return `Must be at most ${issue.maximum}${issue.type === "string" ? " characters" : ""}`;
}

function invalidStringMessage(issue: ZodIssue): string | null {
  if (issue.code !== z.ZodIssueCode.invalid_string) return null;
  switch (issue.validation) {
    case "email":
      return "Enter a valid email address";
    case "url":
      return "Enter a valid URL";
    case "uuid":
      return "Enter a valid ID";
    default:
      return null;
  }
}

function issueMessage(issue: ZodIssue): string | null {
  if (issue.code === z.ZodIssueCode.invalid_enum_value) return "Select a valid option";
  if (issue.code === z.ZodIssueCode.custom && issue.message) return issue.message;
  return invalidTypeMessage(issue) ?? sizeMessage(issue) ?? invalidStringMessage(issue);
}

/**
 * Friendly validation copy for every Zod-powered form. Installed once at startup.
 */
export function installZodErrorMap(): void {
  z.setErrorMap((issue, ctx) => {
    return { message: issueMessage(issue) ?? ctx.defaultError };
  });
}
