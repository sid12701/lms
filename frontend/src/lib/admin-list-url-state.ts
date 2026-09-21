import { z } from "zod";
import { parseFilters } from "./url-state";

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

const ADMIN_LIST_PARAMS_SCHEMA = z.object(ADMIN_LIST_FILTER_FIELDS);

/**
 * Reads the common admin-list params through the shared schema-driven parser
 * (`parseFilters` in `./url-state`). Its per-key `ignoredKeys` are discarded:
 * admin list surfaces do not render an ignored-filter notice, and the parsed
 * value set is identical either way.
 *
 * The write side stays local: the admin convention omits the default first
 * page (`page=0`) and empty values from the URL, which the generic
 * serializer does not express — merging it would add `?page=0` noise to
 * every filter change (the filter bars reset `page` explicitly).
 */
export function readAdminListParams(params: URLSearchParams): AdminListFilterValues {
  return parseFilters(ADMIN_LIST_PARAMS_SCHEMA, params).values;
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
