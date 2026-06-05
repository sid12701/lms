import type { ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import { DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { cn } from "@/lib/utils";

export interface FormDialogHeaderProps {
  icon: LucideIcon;
  iconClassName?: string;
  title: ReactNode;
  description?: ReactNode;
}

export function FormDialogHeader({
  icon: Icon,
  iconClassName,
  title,
  description,
}: FormDialogHeaderProps) {
  return (
    <DialogHeader>
      <div className="flex items-center gap-2">
        <Icon className={cn("h-5 w-5", iconClassName)} aria-hidden="true" />
        <DialogTitle>{title}</DialogTitle>
      </div>
      {description != null ? <DialogDescription>{description}</DialogDescription> : null}
    </DialogHeader>
  );
}
