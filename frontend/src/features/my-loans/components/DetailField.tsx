import { fmt } from "../utils";

interface DetailFieldProps {
  label: string;
  value: string | null | undefined;
  mono?: boolean;
}

export function DetailField({ label, value, mono = false }: DetailFieldProps) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-foreground-muted text-xs tracking-wide uppercase">{label}</dt>
      <dd className={mono ? "font-mono text-sm tabular-nums" : "text-sm"}>{fmt(value)}</dd>
    </div>
  );
}
