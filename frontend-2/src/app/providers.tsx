import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "sonner";
import { SessionProvider } from "@/features/auth/session-context";
import { scenario, type ScenarioKind } from "@/mocks/scenarios";

// ─── Query client ────────────────────────────────────────────────────────────

export function createAppQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: 1,
        refetchOnWindowFocus: false,
      },
    },
  });
}

const defaultQueryClient = createAppQueryClient();

// ─── Theme provider (light/dark) ─────────────────────────────────────────────

type Theme = "light" | "dark";

interface ThemeContextValue {
  theme: Theme;
  setTheme: (t: Theme) => void;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);
const THEME_STORAGE_KEY = "bhawana-lms-theme";

function readPersistedTheme(): Theme {
  try {
    if (typeof window === "undefined") return "light";
    const v = window.localStorage.getItem(THEME_STORAGE_KEY);
    return v === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

export function ThemeProvider({ children }: { children: ReactNode }): ReactElement {
  const [theme, setThemeState] = useState<Theme>(() => readPersistedTheme());

  useEffect(() => {
    if (typeof document === "undefined") return;
    const root = document.documentElement;
    if (theme === "dark") root.classList.add("dark");
    else root.classList.remove("dark");
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, theme);
    } catch {
      // Ignore.
    }
  }, [theme]);

  const value = useMemo<ThemeContextValue>(
    () => ({
      theme,
      setTheme: setThemeState,
      toggle: () => setThemeState((t) => (t === "light" ? "dark" : "light")),
    }),
    [theme],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within ThemeProvider");
  return ctx;
}

// ─── Density provider ────────────────────────────────────────────────────────

type Density = "comfortable" | "compact";

interface DensityContextValue {
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

// ─── Mock scenario provider ──────────────────────────────────────────────────

interface MockScenarioContextValue {
  kind: ScenarioKind;
  setKind: (k: ScenarioKind) => void;
}

const MockScenarioContext = createContext<MockScenarioContextValue | null>(null);

export function MockScenarioProvider({ children }: { children: ReactNode }): ReactElement {
  const [kind, setKindState] = useState<ScenarioKind>(() => scenario.current);

  const value = useMemo<MockScenarioContextValue>(
    () => ({
      kind,
      setKind: (next) => {
        scenario.set(next);
        setKindState(next);
      },
    }),
    [kind],
  );

  return <MockScenarioContext.Provider value={value}>{children}</MockScenarioContext.Provider>;
}

export function useMockScenario(): MockScenarioContextValue {
  const ctx = useContext(MockScenarioContext);
  if (!ctx) throw new Error("useMockScenario must be used within MockScenarioProvider");
  return ctx;
}

// ─── Composed Providers ──────────────────────────────────────────────────────

interface ProvidersProps {
  children: ReactNode;
  /** Test seam — pass an isolated QueryClient. */
  queryClient?: QueryClient;
}

export function Providers({ children, queryClient }: ProvidersProps): ReactElement {
  const client = queryClient ?? defaultQueryClient;
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <DensityProvider>
          <SessionProvider>
            <MockScenarioProvider>
              <TooltipProvider delayDuration={150}>
                {children}
                <Toaster position="top-right" richColors closeButton />
              </TooltipProvider>
            </MockScenarioProvider>
          </SessionProvider>
        </DensityProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
