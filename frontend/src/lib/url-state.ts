/**
 * Type-safe URL filters backed by URLSearchParams.
 *
 * Schema entries become individual query params (`?status=APPROVED&lspId=xxx`).
 * Arrays become repeated params (`?status=APPROVED&status=DISBURSED`).
 * `undefined`, `null`, and empty-string values are omitted from the URL.
 *
 * On parse, values that fail schema validation are silently omitted rather
 * than throwing — deep-link contexts must degrade gracefully when a stale
 * query string carries a value that no longer maps to a valid filter.
 *
 * Usage:
 *   const filtersSchema = z.object({
 *     status: LoanStatus.optional(),
 *     search: z.string().optional(),
 *   });
 *   const [filters, setFilters] = useUrlFilters(filtersSchema);
 */
import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { z } from "zod";

type AnyZodObject = z.ZodObject<z.ZodRawShape>;

/** Pure: read a URLSearchParams snapshot through a Zod object schema. */
function parseFilters<T extends AnyZodObject>(
  schema: T,
  params: URLSearchParams,
): Partial<z.infer<T>> {
  const shape = schema.shape;
  const out: Record<string, unknown> = {};

  for (const key of Object.keys(shape)) {
    const fieldSchema = shape[key] as z.ZodTypeAny;
    const innerArray = unwrapToArray(fieldSchema);

    if (innerArray) {
      const raw = params.getAll(key);
      if (raw.length === 0) continue;
      const coerced: unknown[] = [];
      for (const v of raw) {
        const parsed = coerceScalar(innerArray.element, v);
        if (parsed.ok) coerced.push(parsed.value);
      }
      if (coerced.length > 0) {
        const validated = fieldSchema.safeParse(coerced);
        if (validated.success) {
          out[key] = validated.data;
        }
      }
      continue;
    }

    const raw = params.get(key);
    if (raw === null || raw === "") continue;

    const parsed = coerceScalar(fieldSchema, raw);
    if (!parsed.ok) continue;
    const validated = fieldSchema.safeParse(parsed.value);
    if (validated.success) {
      out[key] = validated.data;
    }
  }

  return out as Partial<z.infer<T>>;
}

/** Pure: write filter values back to URLSearchParams. */
function serializeFilters<T extends AnyZodObject>(
  schema: T,
  values: Partial<z.infer<T>>,
): URLSearchParams {
  const shape = schema.shape;
  const params = new URLSearchParams();

  for (const key of Object.keys(shape)) {
    const value = (values as Record<string, unknown>)[key];
    if (value === undefined || value === null || value === "") continue;

    if (Array.isArray(value)) {
      for (const item of value) {
        if (item === undefined || item === null || item === "") continue;
        params.append(key, stringifyScalar(item));
      }
      continue;
    }

    params.set(key, stringifyScalar(value));
  }

  return params;
}

/**
 * React hook wrapping the pure helpers above against
 * `react-router`'s `useSearchParams`.
 *
 * Calling `setFilters(next)` merges `next` into the existing query string
 * and routes through React Router so back/forward work as expected.
 */
export function useUrlFilters<T extends AnyZodObject>(
  schema: T,
): readonly [Partial<z.infer<T>>, (next: Partial<z.infer<T>>) => void] {
  const [searchParams, setSearchParams] = useSearchParams();

  const filters = useMemo(() => parseFilters(schema, searchParams), [schema, searchParams]);

  const update = useCallback(
    (next: Partial<z.infer<T>>) => {
      const merged = { ...filters, ...next };
      setSearchParams(serializeFilters(schema, merged));
    },
    [schema, filters, setSearchParams],
  );

  return [filters, update] as const;
}

// ─── internal helpers ──────────────────────────────────────────────────────

interface ScalarOk<T> {
  ok: true;
  value: T;
}
interface ScalarErr {
  ok: false;
}

/**
 * Best-effort coerce a raw URL string to the target Zod scalar.
 *
 * URL params arrive as strings; numbers/booleans/dates need explicit
 * conversion. Enums and plain strings pass through. Anything else that
 * fails to coerce is reported as `{ ok: false }` so the caller can drop it.
 */
function coerceScalar(schema: z.ZodTypeAny, raw: string): ScalarOk<unknown> | ScalarErr {
  const inner = unwrap(schema);

  if (inner instanceof z.ZodNumber) {
    if (raw.trim() === "") return { ok: false };
    const n = Number(raw);
    if (!Number.isFinite(n)) return { ok: false };
    return { ok: true, value: n };
  }
  if (inner instanceof z.ZodBoolean) {
    if (raw === "true") return { ok: true, value: true };
    if (raw === "false") return { ok: true, value: false };
    return { ok: false };
  }
  // Strings, enums, native enums, and the rest stay as strings.
  return { ok: true, value: raw };
}

function stringifyScalar(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

/** Strip Optional/Nullable wrappers to reach the inner field schema. */
function unwrap(schema: z.ZodTypeAny): z.ZodTypeAny {
  let current: z.ZodTypeAny = schema;
  while (current instanceof z.ZodOptional || current instanceof z.ZodNullable) {
    current = current.unwrap();
  }
  if (current instanceof z.ZodDefault) {
    current = current.removeDefault();
  }
  return current;
}

function unwrapToArray(schema: z.ZodTypeAny): z.ZodArray<z.ZodTypeAny> | null {
  const inner = unwrap(schema);
  return inner instanceof z.ZodArray ? inner : null;
}
