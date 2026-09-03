import { useMemo } from "react";
import { useLspOptions } from "@/features/lsps/hooks/useLspOptions";

export interface LspChoice {
  id: string;
  name: string;
  code: string;
  status: string;
}

/**
 * LSP picker choices for the product dialogs.
 *
 * Thin projection over the shared `useLspOptions` query, so this surface no
 * longer runs its own fetch of a list three others already hold in cache. The
 * degrade-to-empty behaviour is preserved: these dialogs fall back to "no LSPs
 * selectable" rather than blocking, and the mapping stays here so callers keep
 * their existing shape.
 */
export function useLspChoices(): LspChoice[] {
  const { data } = useLspOptions();

  return useMemo(
    () =>
      (data ?? []).map((row) => ({
        id: row.id,
        name: row.name,
        code: row.code,
        status: row.status,
      })),
    [data],
  );
}
