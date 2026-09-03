import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";

type Density = "comfortable" | "compact";

export interface DensityContextValue {
  density: Density;
  setDensity: (d: Density) => void;
}

const DensityContext = createContext<DensityContextValue | null>(null);
const DENSITY_STORAGE_KEY = "bhawana-lms-density";

/**
 * `compact` is the default.
 *
 * The product's north star is instrument density — "information per square inch
 * is deliberately high ... every pixel spent on chrome is a pixel not spent on
 * the rows an operator reads all day" (DESIGN.md). Shipping `comfortable`
 * contradicted that on first run: the audit measured 6 of 25 rows visible on a
 * 1280x800 viewport, with the first row at y=409 of 699 usable.
 *
 * This is still a user setting, and a stored preference always wins — the
 * change is only to which side a first-time operator starts on.
 */
const DEFAULT_DENSITY: Density = "compact";

function readPersistedDensity(): Density {
  try {
    if (typeof window === "undefined") return DEFAULT_DENSITY;
    const v = window.localStorage.getItem(DENSITY_STORAGE_KEY);
    if (v === "compact") return "compact";
    if (v === "comfortable") return "comfortable";
    return DEFAULT_DENSITY;
  } catch {
    return DEFAULT_DENSITY;
  }
}

export function DensityProvider({ children }: { children: ReactNode }): ReactElement {
  const [density, setDensityState] = useState<Density>(() => readPersistedDensity());

  useEffect(() => {
    if (typeof document === "undefined") return;
    document.documentElement.setAttribute("data-density", density);
    try {
      window.localStorage.setItem(DENSITY_STORAGE_KEY, density);
    } catch {
      // Ignore.
    }
  }, [density]);

  const value = useMemo<DensityContextValue>(
    () => ({ density, setDensity: setDensityState }),
    [density],
  );

  return <DensityContext.Provider value={value}>{children}</DensityContext.Provider>;
}

export function useDensity(): DensityContextValue {
  const ctx = useContext(DensityContext);
  if (!ctx) throw new Error("useDensity must be used within DensityProvider");
  return ctx;
}
