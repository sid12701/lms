import { z } from "zod";

export const ADMIN_LIST_FILTER_FIELDS = {
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
} as const;

export interface AdminListFilterValues {
  q?: string;
  page?: number;
  pageSize?: number;
}

const UUID = z.string().uuid();

function readInteger(
  params: URLSearchParams,
  key: string,
  minimum: number,
  maximum = Number.MAX_SAFE_INTEGER,
): number | undefined {
  const raw = params.get(key);
  if (raw === null) return undefined;
  const value = Number(raw);
  return Number.isInteger(value) && value >= minimum && value <= maximum ? value : undefined;
}

export function readAdminListParams(params: URLSearchParams): AdminListFilterValues {
  const q = params.get("q")?.trim();
  const page = readInteger(params, "page", 0);
  const pageSize = readInteger(params, "pageSize", 5, 100);
  return {
    ...(q ? { q } : {}),
    ...(page !== undefined ? { page } : {}),
    ...(pageSize !== undefined ? { pageSize } : {}),
  };
}

export function writeAdminListParams(filters: AdminListFilterValues): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.q) params.set("q", filters.q);
  if (typeof filters.page === "number" && filters.page > 0) {
    params.set("page", String(filters.page));
  }
  if (typeof filters.pageSize === "number") {
    params.set("pageSize", String(filters.pageSize));
  }
  return params;
}

export function readAllowedParam<T extends string>(
  params: URLSearchParams,
  key: string,
  allowedValues: readonly T[],
): T | undefined {
  const value = params.get(key);
  return value && (allowedValues as readonly string[]).includes(value) ? (value as T) : undefined;
}

export function readUuidParam(params: URLSearchParams, key: string): string | undefined {
  const value = params.get(key);
  return value && UUID.safeParse(value).success ? value : undefined;
}
