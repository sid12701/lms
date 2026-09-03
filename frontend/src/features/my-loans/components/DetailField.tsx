import type { ReactNode } from "react";
import { fmt } from "../utils";

interface DetailFieldProps {
  label: string;
  value: ReactNode | null | undefined;
  mono?: boolean;
}

export function DetailField({ label, value, mono = false }: DetailFieldProps) {
  const content =
    value === null || value === undefined || typeof value === "string" ? fmt(value) : value;
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-foreground-muted text-xs tracking-wide uppercase">{label}</dt>
      <dd className={mono ? "font-mono text-sm tabular-nums" : "text-sm"}>{content}</dd>
    </div>
  );
}
