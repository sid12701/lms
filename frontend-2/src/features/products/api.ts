/**
 * Products feature API wrapper.
 *
 * Re-exports the typed mock handlers so the hooks layer never reaches into
 * `@/mocks/api/*` directly. Importing this module also triggers the
 * side-effect route registration in `@/mocks/api/products` (which is not
 * registered through `@/mocks/api/index.ts` — Phase-8 self-registration
 * pattern).
 */
import {
  createProduct as createProductImpl,
  getProduct as getProductImpl,
  listProducts as listProductsImpl,
  updateProduct as updateProductImpl,
  updateProductMapping as updateProductMappingImpl,
} from "@/mocks/api/products";
import type {
  CreateProductInput,
  ProductDetailResponse,
  ProductMutationResponse,
  ProductsListFilters,
  ProductsListResponse,
  UpdateProductInput,
  UpdateProductMappingInput,
} from "./types";

export async function listProducts(
  filters: ProductsListFilters = {},
): Promise<ProductsListResponse> {
  return listProductsImpl(filters);
}

export async function getProduct(id: string): Promise<ProductDetailResponse> {
  return getProductImpl(id);
}

export async function createProduct(
  input: CreateProductInput,
): Promise<ProductMutationResponse> {
  return createProductImpl(input);
}

export async function updateProduct(
  id: string,
  input: UpdateProductInput,
): Promise<ProductMutationResponse> {
  return updateProductImpl(id, input);
}

export async function updateProductMapping(
  id: string,
  input: UpdateProductMappingInput,
): Promise<ProductMutationResponse> {
  return updateProductMappingImpl(id, input);
}
