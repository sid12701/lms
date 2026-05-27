/**
 * Local hook that reads the mock DB's LSPs map for the mapping dialog. The
 * full `/lsps` admin surface is Phase 9 Agent A; we just need a list of
 * id+name pairs here without coupling to that agent's hook layer.
 */
import { useEffect, useState } from "react";
import { getDb } from "@/mocks/db/state";
import { bootstrapMockApi } from "@/mocks/api";
import { listLspOptions } from "@/features/lsps/options";

export interface LspChoice {
  id: string;
  name: string;
  code: string;
  status: string;
}

export function useLspChoices(): LspChoice[] {
  const [choices, setChoices] = useState<LspChoice[]>(() => readLsps());

  useEffect(() => {
    bootstrapMockApi();
    setChoices(readLsps());
    void listLspOptions()
      .then((rows) => {
        setChoices(rows.map((row) => ({
          id: row.id,
          name: row.name,
          code: row.code,
          status: row.status,
        })));
      })
      .catch(() => {
        setChoices(readLsps());
      });
  }, []);

  return choices;
}

function readLsps(): LspChoice[] {
  const db = getDb();
  return Array.from(db.lsps.values()).filter((l) => l.status === "ACTIVE").map((l) => ({
    id: l.id,
    name: l.name,
    code: l.code,
    status: l.status,
  }));
}
