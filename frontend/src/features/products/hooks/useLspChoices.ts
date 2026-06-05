import { useEffect, useState } from "react";
import { listLspOptions } from "@/features/lsps/options";

export interface LspChoice {
  id: string;
  name: string;
  code: string;
  status: string;
}

export function useLspChoices(): LspChoice[] {
  const [choices, setChoices] = useState<LspChoice[]>([]);

  useEffect(() => {
    void listLspOptions()
      .then((rows) => {
        setChoices(
          rows.map((row) => ({
            id: row.id,
            name: row.name,
            code: row.code,
            status: row.status,
          })),
        );
      })
      .catch(() => {
        setChoices([]);
      });
  }, []);

  return choices;
}
