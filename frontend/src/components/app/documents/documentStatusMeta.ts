import type { LucideIcon } from "lucide-react";
import { Clock, FileUp, MinusCircle } from "lucide-react";
import type { DocumentStatus } from "@/schemas/document";
export type DocumentStatusPillTone = "warning" | "info" | "neutral";

interface DocumentStatusMeta {
  label: string;
  tone: DocumentStatusPillTone;
  icon: LucideIcon;
}

const STATUS_META: Record<DocumentStatus, DocumentStatusMeta> = {
  PENDING: { label: "Pending", tone: "warning", icon: Clock },
  UPLOADED: { label: "Uploaded", tone: "info", icon: FileUp },
  NOT_REQUIRED: { label: "Not required", tone: "neutral", icon: MinusCircle },
};

export function resolveDocumentStatusMeta(status: DocumentStatus): DocumentStatusMeta {
  switch (status) {
    case "PENDING":
    case "UPLOADED":
    case "NOT_REQUIRED":
      return STATUS_META[status];
    default: {
      const _exhaustive: never = status;
      return { label: String(_exhaustive), tone: "warning", icon: Clock };
    }
  }
}
