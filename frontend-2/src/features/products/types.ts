/**
 * View-layer types for the `/products` admin surface (Phase 9 — Agent B).
 *
 * `LoanProduct` is the canonical wire shape from `src/schemas/product.ts`.
 * The list row projection adds the `lspIds` from the
 * `ProductLspMapping` so the table can render an "available to" chip without
 * a second fetch.
 *
 * Every mutation must:
 *   - mint an idempotency key (BR-5)
 *   - append exactly one `ProductAuditEvent` to `db.auditProduct` (BR-7 stream)
 *
 * Mutations require role SYSTEM_ADMIN or PRODUCT_ADMIN (per IA §4).
 */
import { z } from "zod";
import {
  LoanProduct,
  ProductLspMapping,
  ProductStatus,
} from "@/schemas/product";

export type { LoanProduct, ProductLspMapping };
export { ProductStatus };

/** Server-side filter shape for the list page. */
export const ProductsListFilters = z.object({
  status: ProductStatus.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type ProductsListFilters = z.infer<typeof ProductsListFilters>;

/** List-row projection: product + mapping aggregate. */
export interface ProductRow extends LoanProduct {
  lspIds: string[];
  /** Pre-joined display names for the LSPs the product is enabled for. */
  lspNames: string[];
}

export interface ProductsListResponse {
  items: ProductRow[];
  total: number;
  page: number;
  pageSize: number;
}

/** Detail view returned by `GET /products/:id` — adds the full mapping array. */
export interface ProductDetailResponse {
  product: LoanProduct;
  mapping: ProductLspMapping;
}

/** Create form input. Status defaults to ACTIVE; mapping defaults to []. */
export interface CreateProductInput {
  code: string;
  name: string;
  principalMin: number;
  principalMax: number;
  interestRatePct: number;
  processingFeePct: number;
  tenureMinMonths: number;
  tenureMaxMonths: number;
  lspIds: string[];
  idempotencyKey: string;
}

/** Update form input. Any subset of mutable fields may be omitted. */
export interface UpdateProductInput {
  name?: string;
  status?: z.infer<typeof ProductStatus>;
  principalMin?: number;
  principalMax?: number;
  interestRatePct?: number;
  processingFeePct?: number;
  tenureMinMonths?: number;
  tenureMaxMonths?: number;
  idempotencyKey: string;
}

/** Mapping update — replaces the lspIds array wholesale. */
export interface UpdateProductMappingInput {
  lspIds: string[];
  idempotencyKey: string;
}

export interface ProductMutationResponse {
  product: ProductRow;
}
