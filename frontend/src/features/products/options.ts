import { requestJson } from "@/lib/api/http-client";

export interface ProductOption {
  id: string;
  code: string;
  name: string;
  status: string;
}

const PRODUCT_OPTIONS_PATH = "/api/v1/internal/admin/product-options";

export function listProductOptions(): Promise<ProductOption[]> {
  return requestJson<ProductOption[]>(PRODUCT_OPTIONS_PATH);
}
