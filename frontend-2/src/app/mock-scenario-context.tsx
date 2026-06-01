import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { scenario, type ScenarioKind } from "@/mocks/scenarios";

export interface MockScenarioContextValue {
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
