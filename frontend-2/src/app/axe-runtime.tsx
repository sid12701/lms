import { useEffect } from "react";

/**
 * DEV-only axe-core/react runtime. Mounted once at the top level. Tree-shaken
 * out of production builds because the dynamic import is gated on
 * `import.meta.env.DEV`.
 */
export function AxeRuntime(): null {
  useEffect(() => {
    if (!import.meta.env.DEV) return;
    if (typeof window === "undefined") return;

    let cancelled = false;
    void (async () => {
      try {
        const [{ default: axe }, react, reactDom] = await Promise.all([
          import("@axe-core/react"),
          import("react"),
          import("react-dom"),
        ]);
        if (cancelled) return;
        // axe(react, reactDom, debounce-ms, config)
        axe(react, reactDom, 1000);
      } catch (err) {
        console.warn("[axe-runtime] failed to initialise", err);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  return null;
}
