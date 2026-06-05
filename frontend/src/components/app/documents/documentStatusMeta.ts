import type { LucideIcon } from "lucide-react";
import { Clock, FileUp } from "lucide-react";
import type { DocumentStatus } from "@/schemas/document";
export type DocumentStatusPillTone = "warning" | "info";

interface DocumentStatusMeta {
  label: string;
  tone: DocumentStatusPillTone;
  icon: LucideIcon;
}

const STATUS_META: Record<DocumentStatus, DocumentStatusMeta> = {
  PENDING: { label: "Pending", tone: "warning", icon: Clock },
  UPLOADED: { label: "Uploaded", tone: "info", icon: FileUp },
};

export function resolveDocumentStatusMeta(status: DocumentStatus): DocumentStatusMeta {
  switch (status) {
    case "PENDING":
    case "UPLOADED":
      return STATUS_META[status];
    default: {
      const _exhaustive: never = status;
      return { label: String(_exhaustive), tone: "warning", icon: Clock };
    }
  }
}
