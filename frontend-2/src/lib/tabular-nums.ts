/**
 * Marker attribute applied to numeric cells/spans.
 *
 * The base CSS layer keys off `[data-tabular]` to enable
 * `font-variant-numeric: tabular-nums`, so columns of figures align cleanly.
 * Spread `TABULAR_ATTR` onto any element that should opt in.
 */
export const TABULAR_ATTR = { "data-tabular": "true" } as const;
export type TabularAttr = typeof TABULAR_ATTR;
