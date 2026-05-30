/**
 * Products handler tests (Phase 9 — Agent B).
 *
 * Mirrors the alerts/loan-applications setup: reseed via `resetMockApi()`
 * so the 5 deterministic seeded products + 4 LSPs are available by id.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { resetMockApi, auth } from "./index";
import {
  createProduct,
  getProduct,
  listProducts,
  updateProduct,
  updateProductMapping,
} from "./products";
import { newIdempotencyKey } from "@/lib/idempotency";
import { getDb } from "../db/state";
import { LSP_BHAW_DEMO, LSP_NORTH, LSP_SOUTH, PROD_EDUCATION, PROD_PERSONAL } from "../db/seed";

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  resetMockApi();
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("products.list", () => {
  it("requires an active session (401)", async () => {
    await expect(listProducts()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects LSP_UI_READ (401)", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(listProducts()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("SYSTEM_ADMIN sees all 5 seeded products", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listProducts({ pageSize: 100 });
    expect(res.total).toBe(5);
    expect(res.items.length).toBe(5);
    // Mapping is projected onto each row.
    const personal = res.items.find((p) => p.id === PROD_PERSONAL);
    expect(personal?.lspIds).toEqual([LSP_BHAW_DEMO, LSP_SOUTH, LSP_NORTH]);
    expect(personal?.lspNames.length).toBe(3);
  });

  it("PRODUCT_ADMIN also sees all 5 products (role gate)", async () => {
    await auth.login({ username: "product.admin", password: "any" });
    const res = await listProducts({ pageSize: 100 });
    expect(res.total).toBe(5);
  });

  it("status filter narrows to ACTIVE (4 of 5)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listProducts({ status: "ACTIVE", pageSize: 100 });
    expect(res.total).toBe(4);
    expect(res.items.every((p) => p.status === "ACTIVE")).toBe(true);
  });

  it("status=INACTIVE returns only the education loan", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listProducts({ status: "INACTIVE", pageSize: 100 });
    expect(res.total).toBe(1);
    expect(res.items[0]?.id).toBe(PROD_EDUCATION);
  });

  it("q matches on code (case-insensitive)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listProducts({ q: "pl-std", pageSize: 100 });
    expect(res.total).toBe(1);
    expect(res.items[0]?.id).toBe(PROD_PERSONAL);
  });

  it("q matches on name (case-insensitive)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listProducts({ q: "gold", pageSize: 100 });
    expect(res.total).toBe(1);
    expect(res.items[0]?.name).toMatch(/gold/i);
  });

  it("paginates correctly", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // pageSize min is 5; with 5 seeded products the first page holds all 5.
    const page0 = await listProducts({ page: 0, pageSize: 5 });
    expect(page0.items.length).toBe(5);
    expect(page0.total).toBe(5);
    const page1 = await listProducts({ page: 1, pageSize: 5 });
    expect(page1.items.length).toBe(0);
    expect(page1.total).toBe(5);
  });
});

describe("products.detail", () => {
  it("returns product + mapping for SYSTEM_ADMIN", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await getProduct(PROD_PERSONAL);
    expect(res.product.id).toBe(PROD_PERSONAL);
    expect(res.mapping.productId).toBe(PROD_PERSONAL);
    expect(res.mapping.lspIds.length).toBe(3);
  });

  it("404s when product does not exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(getProduct("bbbbbbbb-9999-4bbb-8bbb-bbbbbbbbbbbb")).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });
});

describe("products.create", () => {
  it("rejects LSP_UI_WRITE (401)", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    await expect(
      createProduct({
        code: "NEW-1",
        name: "Brand new product",
        principalMin: 1000,
        principalMax: 10_000,
        interestRatePct: 10,
        processingFeePct: 1,
        tenureMinMonths: 6,
        tenureMaxMonths: 24,
        lspIds: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("creates a product + emits a CREATED audit event", async () => {
    await auth.login({ username: "product.admin", password: "any" });
    const beforeCount = getDb().auditProduct.length;
    const res = await createProduct({
      code: "NEW-1",
      name: "Brand new product",
      principalMin: 1000,
      principalMax: 10_000,
      interestRatePct: 10,
      processingFeePct: 1,
      tenureMinMonths: 6,
      tenureMaxMonths: 24,
      lspIds: [LSP_BHAW_DEMO],
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.product.code).toBe("NEW-1");
    expect(res.product.status).toBe("ACTIVE");
    expect(res.product.lspIds).toEqual([LSP_BHAW_DEMO]);

    const audits = getDb().auditProduct;
    expect(audits.length).toBe(beforeCount + 1);
    const last = audits[audits.length - 1]!;
    expect(last.action).toBe("CREATED");
    expect(last.before).toBeNull();
    expect(last.after).not.toBeNull();
    expect(last.productId).toBe(res.product.id);
  });

  it("rejects duplicate code (409 CONFLICT)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createProduct({
        // Existing code in seed.
        code: "pl-std",
        name: "Duplicate",
        principalMin: 1000,
        principalMax: 10_000,
        interestRatePct: 10,
        processingFeePct: 1,
        tenureMinMonths: 6,
        tenureMaxMonths: 24,
        lspIds: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "CONFLICT", httpStatus: 409 });
  });

  it("rejects bad principal range via superRefine", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createProduct({
        code: "BAD-PRIN",
        name: "Bad principal range",
        principalMin: 50_000,
        principalMax: 10_000, // < min
        interestRatePct: 10,
        processingFeePct: 1,
        tenureMinMonths: 6,
        tenureMaxMonths: 24,
        lspIds: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("rejects bad tenure range via superRefine", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createProduct({
        code: "BAD-TEN",
        name: "Bad tenure range",
        principalMin: 1000,
        principalMax: 10_000,
        interestRatePct: 10,
        processingFeePct: 1,
        tenureMinMonths: 36,
        tenureMaxMonths: 12, // < min
        lspIds: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-5 idempotency replays the cached response", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await createProduct({
      code: "IDEMP-1",
      name: "Idempotent product",
      principalMin: 1000,
      principalMax: 10_000,
      interestRatePct: 10,
      processingFeePct: 1,
      tenureMinMonths: 6,
      tenureMaxMonths: 24,
      lspIds: [],
      idempotencyKey: key,
    });
    const second = await createProduct({
      // Same key + different body → must replay the first response.
      code: "IDEMP-2",
      name: "DIFFERENT NAME",
      principalMin: 9999,
      principalMax: 88_888,
      interestRatePct: 99,
      processingFeePct: 5,
      tenureMinMonths: 1,
      tenureMaxMonths: 10,
      lspIds: [LSP_SOUTH],
      idempotencyKey: key,
    });
    expect(second.product.id).toBe(first.product.id);
    expect(second.product.code).toBe("IDEMP-1");
  });
});

describe("products.update", () => {
  it("rejects OPS_USER (not in product admin set)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(
      updateProduct(PROD_PERSONAL, {
        name: "Renamed",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("updates name and emits an UPDATED audit event", async () => {
    await auth.login({ username: "product.admin", password: "any" });
    const beforeCount = getDb().auditProduct.length;
    const res = await updateProduct(PROD_PERSONAL, {
      name: "Renamed personal",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.product.name).toBe("Renamed personal");
    const audits = getDb().auditProduct;
    expect(audits.length).toBe(beforeCount + 1);
    const last = audits[audits.length - 1]!;
    expect(last.action).toBe("UPDATED");
    expect(last.before).not.toBeNull();
    expect((last.before as { name: string }).name).toBe("Personal Loan — Standard");
    expect((last.after as { name: string }).name).toBe("Renamed personal");
  });

  it("status change emits STATUS_CHANGED (not UPDATED)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const beforeCount = getDb().auditProduct.length;
    const res = await updateProduct(PROD_PERSONAL, {
      status: "INACTIVE",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.product.status).toBe("INACTIVE");
    const audits = getDb().auditProduct;
    expect(audits.length).toBe(beforeCount + 1);
    const last = audits[audits.length - 1]!;
    expect(last.action).toBe("STATUS_CHANGED");
  });

  it("rejects bad principalMax < principalMin", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateProduct(PROD_PERSONAL, {
        principalMin: 9_000_000,
        principalMax: 100,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("404s when product does not exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateProduct("bbbbbbbb-9999-4bbb-8bbb-bbbbbbbbbbbb", {
        name: "ghost",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });
});

describe("products.mapping", () => {
  it("replaces lspIds + emits MAPPING_CHANGED", async () => {
    await auth.login({ username: "product.admin", password: "any" });
    const beforeCount = getDb().auditProduct.length;
    const res = await updateProductMapping(PROD_PERSONAL, {
      lspIds: [LSP_BHAW_DEMO],
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.product.lspIds).toEqual([LSP_BHAW_DEMO]);
    const audits = getDb().auditProduct;
    expect(audits.length).toBe(beforeCount + 1);
    const last = audits[audits.length - 1]!;
    expect(last.action).toBe("MAPPING_CHANGED");
    expect((last.before as { lspIds: string[] }).lspIds.length).toBe(3);
    expect((last.after as { lspIds: string[] }).lspIds).toEqual([LSP_BHAW_DEMO]);
  });

  it("rejects mapping referencing unknown LSP id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateProductMapping(PROD_PERSONAL, {
        lspIds: ["99999999-9999-4999-8999-999999999999"],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("rejects LSP_UI_WRITE on mapping (401)", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    await expect(
      updateProductMapping(PROD_PERSONAL, {
        lspIds: [LSP_BHAW_DEMO],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });
});

describe("products.audit-sample", () => {
  it("captures one event of every action kind end-to-end", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const initialCount = getDb().auditProduct.length;

    // CREATED
    const created = await createProduct({
      code: "SAMP-1",
      name: "Sampler",
      principalMin: 1000,
      principalMax: 50_000,
      interestRatePct: 12,
      processingFeePct: 1,
      tenureMinMonths: 3,
      tenureMaxMonths: 24,
      lspIds: [LSP_BHAW_DEMO],
      idempotencyKey: newIdempotencyKey(),
    });

    // UPDATED
    await updateProduct(created.product.id, {
      name: "Sampler v2",
      idempotencyKey: newIdempotencyKey(),
    });

    // STATUS_CHANGED
    await updateProduct(created.product.id, {
      status: "INACTIVE",
      idempotencyKey: newIdempotencyKey(),
    });

    // MAPPING_CHANGED
    await updateProductMapping(created.product.id, {
      lspIds: [LSP_BHAW_DEMO, LSP_SOUTH],
      idempotencyKey: newIdempotencyKey(),
    });

    const audits = getDb().auditProduct.slice(initialCount);
    expect(audits.map((a) => a.action)).toEqual([
      "CREATED",
      "UPDATED",
      "STATUS_CHANGED",
      "MAPPING_CHANGED",
    ]);
    if (process.env["DUMP_AUDIT"] === "1") {
      console.log(JSON.stringify(audits, null, 2));
    }
  });
});
