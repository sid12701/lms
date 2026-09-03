import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { DEFAULT_DOCUMENT_TITLE } from "@/lib/loan-page-identity";

export interface PageMeta {
  /** Overrides the last opaque-id breadcrumb segment when set. */
  breadcrumbLabel?: string;
  /** Full `document.title` string for the active route. */
  documentTitle?: string;
}

interface PageMetaContextValue {
  meta: PageMeta;
  setMeta: (meta: PageMeta) => void;
}

const PageMetaContext = createContext<PageMetaContextValue | null>(null);

export function PageMetaProvider({ children }: { children: ReactNode }) {
  const [meta, setMeta] = useState<PageMeta>({});
  const value = useMemo(() => ({ meta, setMeta }), [meta]);
  return <PageMetaContext.Provider value={value}>{children}</PageMetaContext.Provider>;
}

export function usePageMetaState(): PageMeta {
  const ctx = useContext(PageMetaContext);
  return ctx?.meta ?? {};
}

/**
 * Registers route-scoped breadcrumb + document title overrides. Clears on
 * unmount so list routes fall back to static labels.
 */
export function usePageMeta(meta: PageMeta): void {
  const ctx = useContext(PageMetaContext);
  // Depend on the stable setter only — including `ctx` (which changes whenever
  // `meta` updates) re-fires this effect and loops indefinitely.
  const setMeta = ctx?.setMeta;
  const breadcrumbLabel = meta.breadcrumbLabel;
  const documentTitle = meta.documentTitle;

  useEffect(() => {
    if (!setMeta) return;
    setMeta({ breadcrumbLabel, documentTitle });
    return () => setMeta({});
  }, [setMeta, breadcrumbLabel, documentTitle]);

  useEffect(() => {
    const previous = document.title;
    document.title = documentTitle?.trim() ? documentTitle : DEFAULT_DOCUMENT_TITLE;
    return () => {
      document.title = previous;
    };
  }, [documentTitle]);
}
