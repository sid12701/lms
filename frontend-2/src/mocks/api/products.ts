/**
 * Products handler (Phase 9 — Agent B).
 *
 * Implements the loan product admin surface:
 *
 *   GET   /api/v1/admin/products            → list + filters
 *   GET   /api/v1/admin/products/:id        → detail (product + mapping)
 *   POST  /api/v1/admin/products            → create
 *   PATCH /api/v1/admin/products/:id        → update (any subset)
 *   PUT   /api/v1/admin/products/:id/mapping → replace lspIds
 *
 * Role gate (all endpoints): SYSTEM_ADMIN or PRODUCT_ADMIN. Every other role
 * is rejected with UNAUTHORIZED (401).
 *
 * Every mutation:
 *   1. Validates the body against the LoanProduct schema (which carries
 *      the principalMin ≤ principalMax + tenureMin ≤ tenureMax superRefine
 *      checks)
 *   2. Applies the change to `db.products` / `db.productLspMappings`
 *   3. Appends exactly ONE ProductAuditEvent to `db.auditProduct` with
 *      action ∈ {CREATED, UPDATED, MAPPING_CHANGED, STATUS_CHANGED}. STATUS_CHANGED
 *      wins over UPDATED when the PATCH carries a status change.
 *
 * BR coverage:
 *   - BR-5 — every mutation route registered with `{ mutating: true }`; the
 *     router replays cached responses under matching Idempotency-Key headers
 *     within a 30s TTL.
 *   - BR-7 — every product write surfaces in the Product audit stream and
 *     thus in the /audit explorer (Phase 8).
 *
 * Self-registers routes on import. Imported by `src/features/products/api.ts`
 * so loading the feature wires the routes for the running app.
 */
import { z } from "zod";
import { LoanProduct, ProductStatus } from "@/schemas/product";
import { Uuid } from "@/schemas/common";
import type {
  LoanProduct as LoanProductT,
  ProductAuditAction,
  ProductAuditEvent,
  ProductLspMapping,
  Role,
} from "@/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { BadRequestError, ConflictError, NotFoundError, UnauthorizedError } from "../errors";
import type { MockDb } from "../db/state";
import type {
  CreateProductInput,
  ProductDetailResponse,
  ProductMutationResponse,
  ProductRow,
  ProductsListFilters,
  ProductsListResponse,
  UpdateProductInput,
  UpdateProductMappingInput,
} from "@/features/products/types";

// ─── Role helpers ────────────────────────────────────────────────────────────

const PRODUCT_ADMIN_ROLES = new Set<Role>(["SYSTEM_ADMIN", "PRODUCT_ADMIN"]);

interface ActiveSession {
  userId: string;
  role: Role;
}

function requireProductSession(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  if (!PRODUCT_ADMIN_ROLES.has(user.role)) {
    throw new UnauthorizedError(correlationId, `role ${user.role} cannot access product admin`);
  }
  return { userId: user.id, role: user.role };
}

// ─── Projection ──────────────────────────────────────────────────────────────

function projectRow(db: MockDb, product: LoanProductT): ProductRow {
  const mapping = db.productLspMappings.get(product.id);
  const lspIds = mapping?.lspIds ?? [];
  const lspNames = lspIds
    .map((id) => db.lsps.get(id)?.name)
    .filter((n): n is string => typeof n === "string");
  return {
    ...product,
    lspIds,
    lspNames,
  };
}

function emitAudit(
  db: MockDb,
  session: ActiveSession,
  productId: string,
  action: ProductAuditAction,
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
  correlationId: string,
): ProductAuditEvent {
  const evt: ProductAuditEvent = {
    id: crypto.randomUUID(),
    productId,
    action,
    before,
    after,
    actorId: session.userId,
    actorRole: session.role,
    correlationId,
    createdAt: new Date().toISOString(),
  };
  db.auditProduct.push(evt);
  return evt;
}

function cloneForAudit<T>(value: T): Record<string, unknown> {
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
}

// ─── List handler ────────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  status: ProductStatus.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});

function parseListQuery(
  req: MockRequest,
  correlationId: string,
): z.infer<typeof ListFiltersSchema> {
  const raw: Record<string, unknown> = { ...(req.query ?? {}) };
  const parsed = ListFiltersSchema.safeParse(raw);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid list filters", parsed.error.flatten());
  }
  return parsed.data;
}

function listHandler(req: MockRequest, db: MockDb, correlationId: string): ProductsListResponse {
  requireProductSession(db, correlationId);
  const filters = parseListQuery(req, correlationId);

  let rows: LoanProductT[] = Array.from(db.products.values());

  if (filters.status) {
    rows = rows.filter((p) => p.status === filters.status);
  }
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    rows = rows.filter(
      (p) => p.code.toLowerCase().includes(needle) || p.name.toLowerCase().includes(needle),
    );
  }

  // Stable ordering: oldest-first by createdAt → code (deterministic).
  rows.sort((a, b) => {
    if (a.createdAt !== b.createdAt) {
      return a.createdAt < b.createdAt ? -1 : 1;
    }
    return a.code < b.code ? -1 : a.code > b.code ? 1 : 0;
  });

  const total = rows.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const slice = rows.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((p) => projectRow(db, p)),
    total,
    page,
    pageSize,
  };
}

// ─── Detail handler ──────────────────────────────────────────────────────────

function detailHandler(req: MockRequest, db: MockDb, correlationId: string): ProductDetailResponse {
  requireProductSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const product = db.products.get(id);
  if (!product) {
    throw new NotFoundError(correlationId, `product ${id} not found`);
  }
  const mapping =
    db.productLspMappings.get(id) ?? ({ productId: id, lspIds: [] } as ProductLspMapping);
  return { product, mapping };
}

// ─── Create handler ──────────────────────────────────────────────────────────

const CreateBodySchema = z.object({
  code: z.string().trim().min(2).max(24),
  name: z.string().trim().min(1).max(120),
  principalMin: z.number().positive().max(1e12),
  principalMax: z.number().positive().max(1e12),
  interestRatePct: z.number().nonnegative().max(100),
  processingFeePct: z.number().nonnegative().max(100),
  tenureMinMonths: z.number().int().min(1).max(360),
  tenureMaxMonths: z.number().int().min(1).max(360),
  lspIds: z.array(Uuid).default([]),
  idempotencyKey: z.string().min(1).max(80),
});

function createHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): ProductMutationResponse {
  const session = requireProductSession(db, correlationId);

  const parsed = CreateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid product body", parsed.error.flatten());
  }
  const body = parsed.data;

  // Code uniqueness (case-insensitive).
  const codeNorm = body.code.toUpperCase();
  for (const existing of db.products.values()) {
    if (existing.code.toUpperCase() === codeNorm) {
      throw new ConflictError(correlationId, `product code "${body.code}" already exists`);
    }
  }

  const newProduct: LoanProductT = {
    id: crypto.randomUUID(),
    code: codeNorm,
    name: body.name,
    status: "ACTIVE",
    principalMin: body.principalMin,
    principalMax: body.principalMax,
    interestRatePct: body.interestRatePct,
    processingFeePct: body.processingFeePct,
    tenureMinMonths: body.tenureMinMonths,
    tenureMaxMonths: body.tenureMaxMonths,
    createdAt: new Date().toISOString(),
  };

  // Trip the LoanProduct superRefine (principalMin ≤ Max + tenureMin ≤ Max).
  const productParse = LoanProduct.safeParse(newProduct);
  if (!productParse.success) {
    throw new BadRequestError(correlationId, "invalid product", productParse.error.flatten());
  }

  db.products.set(newProduct.id, newProduct);
  const mapping: ProductLspMapping = {
    productId: newProduct.id,
    lspIds: body.lspIds,
  };
  db.productLspMappings.set(newProduct.id, mapping);

  emitAudit(
    db,
    session,
    newProduct.id,
    "CREATED",
    null,
    cloneForAudit({ product: newProduct, mapping }),
    correlationId,
  );

  return { product: projectRow(db, newProduct) };
}

// ─── Update handler ──────────────────────────────────────────────────────────

const UpdateBodySchema = z
  .object({
    name: z.string().trim().min(1).max(120).optional(),
    status: ProductStatus.optional(),
    principalMin: z.number().positive().max(1e12).optional(),
    principalMax: z.number().positive().max(1e12).optional(),
    interestRatePct: z.number().nonnegative().max(100).optional(),
    processingFeePct: z.number().nonnegative().max(100).optional(),
    tenureMinMonths: z.number().int().min(1).max(360).optional(),
    tenureMaxMonths: z.number().int().min(1).max(360).optional(),
    idempotencyKey: z.string().min(1).max(80),
  })
  .strict();

function updateHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): ProductMutationResponse {
  const session = requireProductSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const current = db.products.get(id);
  if (!current) {
    throw new NotFoundError(correlationId, `product ${id} not found`);
  }

  const parsed = UpdateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid product update", parsed.error.flatten());
  }
  const body = parsed.data;

  const next: LoanProductT = {
    ...current,
    ...(body.name !== undefined ? { name: body.name } : {}),
    ...(body.status !== undefined ? { status: body.status } : {}),
    ...(body.principalMin !== undefined ? { principalMin: body.principalMin } : {}),
    ...(body.principalMax !== undefined ? { principalMax: body.principalMax } : {}),
    ...(body.interestRatePct !== undefined ? { interestRatePct: body.interestRatePct } : {}),
    ...(body.processingFeePct !== undefined ? { processingFeePct: body.processingFeePct } : {}),
    ...(body.tenureMinMonths !== undefined ? { tenureMinMonths: body.tenureMinMonths } : {}),
    ...(body.tenureMaxMonths !== undefined ? { tenureMaxMonths: body.tenureMaxMonths } : {}),
  };

  // Trip the LoanProduct superRefine.
  const productParse = LoanProduct.safeParse(next);
  if (!productParse.success) {
    throw new BadRequestError(correlationId, "invalid product", productParse.error.flatten());
  }

  const statusChanged = body.status !== undefined && body.status !== current.status;
  const action: ProductAuditAction = statusChanged ? "STATUS_CHANGED" : "UPDATED";

  db.products.set(id, next);

  emitAudit(db, session, id, action, cloneForAudit(current), cloneForAudit(next), correlationId);

  return { product: projectRow(db, next) };
}

// ─── Mapping handler ─────────────────────────────────────────────────────────

const MappingBodySchema = z.object({
  lspIds: z.array(Uuid),
  idempotencyKey: z.string().min(1).max(80),
});

function mappingHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): ProductMutationResponse {
  const session = requireProductSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const product = db.products.get(id);
  if (!product) {
    throw new NotFoundError(correlationId, `product ${id} not found`);
  }

  const parsed = MappingBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid mapping body", parsed.error.flatten());
  }

  // Validate every referenced LSP exists.
  for (const lspId of parsed.data.lspIds) {
    if (!db.lsps.has(lspId)) {
      throw new BadRequestError(correlationId, `unknown LSP id: ${lspId}`);
    }
  }

  const before =
    db.productLspMappings.get(id) ?? ({ productId: id, lspIds: [] } as ProductLspMapping);
  // De-duplicate (preserve order).
  const seen = new Set<string>();
  const uniqueIds = parsed.data.lspIds.filter((lid) => {
    if (seen.has(lid)) return false;
    seen.add(lid);
    return true;
  });
  const next: ProductLspMapping = { productId: id, lspIds: uniqueIds };
  db.productLspMappings.set(id, next);

  emitAudit(
    db,
    session,
    id,
    "MAPPING_CHANGED",
    cloneForAudit(before),
    cloneForAudit(next),
    correlationId,
  );

  return { product: projectRow(db, product) };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerProductRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/admin/products", listHandler);
  registerRoute("GET", "/api/v1/admin/products/:id", detailHandler);
  registerRoute("POST", "/api/v1/admin/products", createHandler, {
    mutating: true,
  });
  registerRoute("PATCH", "/api/v1/admin/products/:id", updateHandler, {
    mutating: true,
  });
  registerRoute("PUT", "/api/v1/admin/products/:id/mapping", mappingHandler, { mutating: true });
}
registerProductRoutes();

// ─── Dispatch parsers ────────────────────────────────────────────────────────

const ProductRowParser: z.ZodType<ProductRow> = z
  .object({
    id: z.string(),
    code: z.string(),
    name: z.string(),
    status: ProductStatus,
    principalMin: z.number(),
    principalMax: z.number(),
    interestRatePct: z.number(),
    processingFeePct: z.number(),
    tenureMinMonths: z.number(),
    tenureMaxMonths: z.number(),
    createdAt: z.string(),
    lspIds: z.array(z.string()),
    lspNames: z.array(z.string()),
  })
  .passthrough() as unknown as z.ZodType<ProductRow>;

const ListResponseSchema: z.ZodType<ProductsListResponse> = z.object({
  items: z.array(ProductRowParser),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
}) as unknown as z.ZodType<ProductsListResponse>;

const LoanProductParser: z.ZodType<LoanProductT> = z
  .object({
    id: z.string(),
    code: z.string(),
    name: z.string(),
    status: ProductStatus,
    principalMin: z.number(),
    principalMax: z.number(),
    interestRatePct: z.number(),
    processingFeePct: z.number(),
    tenureMinMonths: z.number(),
    tenureMaxMonths: z.number(),
    createdAt: z.string(),
  })
  .passthrough() as unknown as z.ZodType<LoanProductT>;

const MappingParser: z.ZodType<ProductLspMapping> = z.object({
  productId: z.string(),
  lspIds: z.array(z.string()),
}) as unknown as z.ZodType<ProductLspMapping>;

const DetailResponseSchema: z.ZodType<ProductDetailResponse> = z.object({
  product: LoanProductParser,
  mapping: MappingParser,
}) as unknown as z.ZodType<ProductDetailResponse>;

const MutationResponseSchema: z.ZodType<ProductMutationResponse> = z.object({
  product: ProductRowParser,
}) as unknown as z.ZodType<ProductMutationResponse>;

// ─── Public wrappers ─────────────────────────────────────────────────────────

function queryFromFilters(filters: ProductsListFilters): Record<string, string> {
  const out: Record<string, string> = {};
  if (filters.status) out["status"] = filters.status;
  if (filters.q) out["q"] = filters.q;
  if (typeof filters.page === "number") out["page"] = String(filters.page);
  if (typeof filters.pageSize === "number") {
    out["pageSize"] = String(filters.pageSize);
  }
  return out;
}

export async function listProducts(
  filters: ProductsListFilters = {},
): Promise<ProductsListResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/admin/products",
      query: queryFromFilters(filters),
    },
    ListResponseSchema,
  );
}

export async function getProduct(id: string): Promise<ProductDetailResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/admin/products/${id}`,
    },
    DetailResponseSchema,
  );
}

export async function createProduct(input: CreateProductInput): Promise<ProductMutationResponse> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/admin/products",
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    MutationResponseSchema,
  );
}

export async function updateProduct(
  id: string,
  input: UpdateProductInput,
): Promise<ProductMutationResponse> {
  return dispatch(
    {
      method: "PATCH",
      path: `/api/v1/admin/products/${id}`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    MutationResponseSchema,
  );
}

export async function updateProductMapping(
  id: string,
  input: UpdateProductMappingInput,
): Promise<ProductMutationResponse> {
  return dispatch(
    {
      method: "PUT",
      path: `/api/v1/admin/products/${id}/mapping`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    MutationResponseSchema,
  );
}
