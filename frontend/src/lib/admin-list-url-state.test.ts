import { describe, expect, it } from "vitest";
import {
  readAdminListParams,
  readAllowedParam,
  readUuidParam,
  writeAdminListParams,
} from "./admin-list-url-state";

describe("admin list URL state", () => {
  it("reads and normalizes supported common filters", () => {
    const params = new URLSearchParams("q=%20demo%20&page=0&pageSize=100");

    expect(readAdminListParams(params)).toEqual({ q: "demo", page: 0, pageSize: 100 });
  });

  it.each([
    ["page=-1", {}],
    ["page=1.5", {}],
    ["pageSize=4", {}],
    ["pageSize=101", {}],
    ["q=%20%20", {}],
  ])("ignores invalid common filters in %s", (query, expected) => {
    expect(readAdminListParams(new URLSearchParams(query))).toEqual(expected);
  });

  it("writes canonical parameters and omits the default first page", () => {
    expect(writeAdminListParams({ q: "borrower", page: 0, pageSize: 50 }).toString()).toBe(
      "q=borrower&pageSize=50",
    );
    expect(writeAdminListParams({ page: 2 }).toString()).toBe("page=2");
  });

  it("accepts only values from the caller's allowlist", () => {
    const params = new URLSearchParams("status=ACTIVE");
    expect(readAllowedParam(params, "status", ["ACTIVE", "DISABLED"] as const)).toBe("ACTIVE");
    expect(readAllowedParam(params, "status", ["DISABLED"] as const)).toBeUndefined();
    expect(readAllowedParam(params, "missing", ["ACTIVE"] as const)).toBeUndefined();
  });

  it("accepts valid UUID parameters and rejects malformed identifiers", () => {
    const valid = "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa";
    expect(readUuidParam(new URLSearchParams(`lspId=${valid}`), "lspId")).toBe(valid);
    expect(readUuidParam(new URLSearchParams("lspId=not-a-uuid"), "lspId")).toBeUndefined();
    expect(readUuidParam(new URLSearchParams(), "lspId")).toBeUndefined();
  });
});
