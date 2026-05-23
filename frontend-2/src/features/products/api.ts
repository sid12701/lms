/**
 * Loan-products admin surface, wired to the live backend.
 *
 * Backend contract: `LoanProductAdminController` under
 * `/api/v1/internal/admin/products` (SYSTEM_ADMIN + PRODUCT_ADMIN).
 *
 * The backend exposes 9 product fields (incl. status/code/name/principal
 * range, interest, processing fee, tenure bounds) plus a mappings endpoint
 * returning the LSPs the product is enabled for. The frontend's
 * `LoanProduct` projection includes a `createdAt` timestamp that the
 * backend does not surface today — we default it to `1970-01-01T00:00:00Z`.
 */
import { requestJson } from "@/lib/api/http-client";
import type { LoanProduct, ProductLspMapping, ProductStatus } from "@/schemas/product";
import type {
  CreateProductInput,
  ProductDetailResponse,
  ProductMutationResponse,
  ProductRow,
  ProductsListFilters,
  ProductsListResponse,
  UpdateProductInput,
  UpdateProductMappingInput,
} from "./types";

const BASE = "/api/v1/internal/admin/products";
const EPOCH_ISO = "1970-01-01T00:00:00.000Z";

interface BackendProductResponse {
  id: string;
  code: string;
  name: string;
  minPrincipal: number;
  maxPrincipal: number;
  interestRate: number;
  processingFeeRate: number;
  minTenureMonths: number;
  maxTenureMonths: number;
  status: string;
}

interface BackendMappingResponse {
  productId: string;
  productCode: string;
  productName: string;
  mappedLsps: Array<{ id: string; code: string; name: string; status: string }>;
}

function toLoanProduct(payload: BackendProductResponse): LoanProduct {
  const status: ProductStatus = payload.status === "INACTIVE" ? "INACTIVE" : "ACTIVE";
  return {
    id: payload.id,
    code: payload.code,
    name: payload.name,
    status,
    principalMin: Number(payload.minPrincipal),
    principalMax: Number(payload.maxPrincipal),
    interestRatePct: Number(payload.interestRate),
    processingFeePct: Number(payload.processingFeeRate),
    tenureMinMonths: Number(payload.minTenureMonths),
    tenureMaxMonths: Number(payload.maxTenureMonths),
    createdAt: EPOCH_ISO,
  };
}

async function fetchMapping(productId: string): Promise<BackendMappingResponse> {
  return requestJson<BackendMappingResponse>(`${BASE}/${productId}/mappings`);
}

async function toProductRow(payload: BackendProductResponse): Promise<ProductRow> {
  const product = toLoanProduct(payload);
  let lspIds: string[] = [];
  let lspNames: string[] = [];
  try {
    const mapping = await fetchMapping(payload.id);
    lspIds = mapping.mappedLsps.map((entry) => entry.id);
    lspNames = mapping.mappedLsps.map((entry) => entry.name);
  } catch {
    // Mapping failure is non-fatal — the row still renders without chips.
  }
  return { ...product, lspIds, lspNames };
}

export async function listProducts(
  filters: ProductsListFilters = {},
): Promise<ProductsListResponse> {
  const all = await requestJson<BackendProductResponse[]>(BASE);
  const filtered = all.filter((row) => {
    if (filters.status && row.status !== filters.status) return false;
    if (filters.q) {
      const needle = filters.q.toLowerCase();
      if (!row.code.toLowerCase().includes(needle) && !row.name.toLowerCase().includes(needle)) {
        return false;
      }
    }
    return true;
  });
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 20;
  const slice = filtered.slice(page * pageSize, page * pageSize + pageSize);
  const items = await Promise.all(slice.map(toProductRow));
  return { items, total: filtered.length, page, pageSize };
}

export async function getProduct(id: string): Promise<ProductDetailResponse> {
  const [payload, mapping] = await Promise.all([
    requestJson<BackendProductResponse>(`${BASE}/${id}`),
    fetchMapping(id).catch(() => null),
  ]);
  const product = toLoanProduct(payload);
  const lspIds = mapping?.mappedLsps.map((entry) => entry.id) ?? [];
  const productMapping: ProductLspMapping = { productId: payload.id, lspIds };
  return { product, mapping: productMapping };
}

function buildProductRequestBody(input: {
  code: string;
  name: string;
  principalMin: number;
  principalMax: number;
  interestRatePct: number;
  processingFeePct: number;
  tenureMinMonths: number;
  tenureMaxMonths: number;
  status?: ProductStatus;
}): Record<string, unknown> {
  return {
    code: input.code,
    name: input.name,
    minPrincipal: input.principalMin,
    maxPrincipal: input.principalMax,
    interestRate: input.interestRatePct,
    processingFeeRate: input.processingFeePct,
    minTenureMonths: input.tenureMinMonths,
    maxTenureMonths: input.tenureMaxMonths,
    status: input.status ?? "ACTIVE",
  };
}

export async function createProduct(
  input: CreateProductInput,
): Promise<ProductMutationResponse> {
  const payload = await requestJson<BackendProductResponse>(
    BASE,
    { method: "POST", body: JSON.stringify(buildProductRequestBody(input)) },
    { idempotencyKey: input.idempotencyKey },
  );
  if (input.lspIds.length > 0) {
    await requestJson(
      `${BASE}/${payload.id}/mappings`,
      { method: "PUT", body: JSON.stringify({ lspIds: input.lspIds }) },
      { idempotencyKey: `${input.idempotencyKey}-mapping` },
    );
  }
  const row = await toProductRow(payload);
  return { product: row };
}

export async function updateProduct(
  id: string,
  input: UpdateProductInput,
): Promise<ProductMutationResponse> {
  const current = await requestJson<BackendProductResponse>(`${BASE}/${id}`);
  const body = {
    code: current.code,
    name: input.name ?? current.name,
    minPrincipal: input.principalMin ?? Number(current.minPrincipal),
    maxPrincipal: input.principalMax ?? Number(current.maxPrincipal),
    interestRate: input.interestRatePct ?? Number(current.interestRate),
    processingFeeRate: input.processingFeePct ?? Number(current.processingFeeRate),
    minTenureMonths: input.tenureMinMonths ?? current.minTenureMonths,
    maxTenureMonths: input.tenureMaxMonths ?? current.maxTenureMonths,
    status: input.status ?? current.status,
  };
  const payload = await requestJson<BackendProductResponse>(
    `${BASE}/${id}`,
    { method: "PUT", body: JSON.stringify(body) },
    { idempotencyKey: input.idempotencyKey },
  );
  const row = await toProductRow(payload);
  return { product: row };
}

export async function updateProductMapping(
  id: string,
  input: UpdateProductMappingInput,
): Promise<ProductMutationResponse> {
  await requestJson(
    `${BASE}/${id}/mappings`,
    { method: "PUT", body: JSON.stringify({ lspIds: input.lspIds }) },
    { idempotencyKey: input.idempotencyKey },
  );
  const payload = await requestJson<BackendProductResponse>(`${BASE}/${id}`);
  const row = await toProductRow(payload);
  return { product: row };
}
