import * as React from "react";
import * as ReactDOM from "react-dom";

/**
 * DEV-only axe-core/react runtime. Mounted once at the top level. Tree-shaken
 * out of production builds because the dynamic import is gated on
 * `import.meta.env.DEV`.
 */
export function AxeRuntime(): null {
  React.useEffect(() => {
    if (!import.meta.env.DEV) return;
    if (typeof window === "undefined") return;

    let cancelled = false;
    void (async () => {
      try {
        const { default: axe } = await import("@axe-core/react");
        if (cancelled) return;
        // axe(react, reactDom, debounce-ms, config)
        axe(React, ReactDOM, 1000);
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
