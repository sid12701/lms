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

function readPersistedDensity(): Density {
  try {
    if (typeof window === "undefined") return "comfortable";
    const v = window.localStorage.getItem(DENSITY_STORAGE_KEY);
    return v === "compact" ? "compact" : "comfortable";
  } catch {
    return "comfortable";
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
