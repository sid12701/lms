/**
 * Human labels for the backend's DPD (days-past-due) bucket vocabulary.
 *
 * The API returns bucket codes like `DPD_1_30`; the frontend schema spells the
 * same buckets `B1_30`. Both are storage identifiers, not copy — surfacing
 * either raw leaks schema into the UI and forces the reader to decode an enum.
 *
 * This module owns the vocabulary for every surface: the badge, the alerts
 * feed, the loan account delinquency panel and the home DPD chart. It exists
 * because those surfaces previously carried three separate maps, and one of
 * them had drifted — the chart labelled the buckets `0-30 / 30-60 / 60-90`,
 * which both contradicts the backend boundaries
 * (`LoanDelinquencySupport.resolveDelinquencyBucket`: 1–30, 31–60, 61–90, 90+)
 * and puts every boundary day in two buckets at once.
 */

/** Canonical key — the spelling the frontend schema uses. */
export type DelinquencyBucketKey = "B0" | "B1_30" | "B31_60" | "B61_90" | "B90_PLUS";

interface BucketCopy {
  /** Full label, for badges and prose. */
  label: string;
  /** Compact label, for chart axes where the full form does not fit. */
  short: string;
}

const BUCKET_COPY: Record<DelinquencyBucketKey, BucketCopy> = {
  B0: { label: "Current (0 DPD)", short: "Current" },
  B1_30: { label: "1–30 DPD", short: "1–30" },
  B31_60: { label: "31–60 DPD", short: "31–60" },
  B61_90: { label: "61–90 DPD", short: "61–90" },
  B90_PLUS: { label: "90+ DPD", short: "90+" },
};

/** Backend spelling → canonical key. */
const BACKEND_ALIASES: Record<string, DelinquencyBucketKey> = {
  CURRENT: "B0",
  DPD_1_30: "B1_30",
  DPD_31_60: "B31_60",
  DPD_61_90: "B61_90",
  DPD_90_PLUS: "B90_PLUS",
};

function resolveCopy(bucket: string): BucketCopy | undefined {
  const key = bucket.trim();
  const canonical = key in BUCKET_COPY ? (key as DelinquencyBucketKey) : BACKEND_ALIASES[key];
  return canonical ? BUCKET_COPY[canonical] : undefined;
}

/**
 * Full label for a DPD bucket, in either spelling. Unknown codes degrade to a
 * de-underscored form rather than disappearing, so a new backend bucket stays
 * readable until it is mapped here.
 */
export function delinquencyBucketLabel(bucket: string): string {
  return resolveCopy(bucket)?.label ?? bucket.trim().replace(/_/gu, " ");
}

/** Compact label for chart axes and other space-constrained surfaces. */
export function delinquencyBucketShortLabel(bucket: string): string {
  return resolveCopy(bucket)?.short ?? bucket.trim().replace(/_/gu, " ");
}
