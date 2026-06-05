import { forwardRef, type HTMLAttributes } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const avatarVariants = cva(
  "inline-flex shrink-0 select-none items-center justify-center rounded-full font-medium uppercase tracking-tight",
  {
    variants: {
      size: {
        sm: "h-6 w-6 text-[10px]",
        md: "h-8 w-8 text-xs",
        lg: "h-10 w-10 text-sm",
      },
      tone: {
        neutral: "bg-surface-muted text-foreground",
        brand: "bg-primary text-primary-foreground",
        accent: "bg-accent text-accent-foreground",
      },
    },
    defaultVariants: {
      size: "md",
      tone: "brand",
    },
  },
);

export interface AvatarInitialsProps
  extends Omit<HTMLAttributes<HTMLSpanElement>, "children">, VariantProps<typeof avatarVariants> {
  /** Full display name. Initials are derived from the first letter of the
   *  first and last whitespace-separated words (max 2 chars). */
  name: string;
  className?: string;
}

/**
 * Derive up to two uppercase initials from a display name and render them
 * inside a tinted, fully rounded chip. Use stand-alone or as the
 * `<AvatarFallback>` content of a shadcn Avatar.
 *
 * The full name is rendered in `aria-label` so screen readers announce
 * the actual person, not just the letters.
 */
export const AvatarInitials = forwardRef<HTMLSpanElement, AvatarInitialsProps>(
  function AvatarInitials({ name, size, tone, className, ...rest }, ref) {
    const initials = deriveInitials(name);
    return (
      <span
        ref={ref}
        data-slot="avatar-initials"
        role="img"
        aria-label={name}
        className={cn(avatarVariants({ size, tone }), className)}
        {...rest}
      >
        <span aria-hidden="true">{initials}</span>
      </span>
    );
  },
);

function deriveInitials(name: string): string {
  const parts = name
    .trim()
    .split(/\s+/)
    .filter((p) => p.length > 0);
  if (parts.length === 0) return "?";
  if (parts.length === 1) {
    const only = parts[0]!;
    return only.slice(0, Math.min(2, only.length)).toUpperCase();
  }
  const first = parts[0]!.charAt(0);
  const last = parts[parts.length - 1]!.charAt(0);
  return `${first}${last}`.toUpperCase();
}
