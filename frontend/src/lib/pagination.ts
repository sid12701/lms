export interface PaginationOptions {
  page?: number;
  pageSize?: number;
}

export interface PageSlice<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export function paginate<T>(
  items: readonly T[],
  { page = 0, pageSize = 20 }: PaginationOptions,
): PageSlice<T> {
  const start = page * pageSize;
  return {
    items: items.slice(start, start + pageSize),
    total: items.length,
    page,
    pageSize,
  };
}
