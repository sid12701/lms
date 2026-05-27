import { useCallback } from "react";
import { Bell, HelpCircle, Menu, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { CommandPalette } from "./CommandPalette";
import { RoleScopeBadge } from "./RoleScopeBadge";
import { ThemeToggle } from "./ThemeToggle";
import { UserMenu } from "./UserMenu";
import { DevTopBarTools } from "./DevTopBarTools";
import { cn } from "@/lib/utils";

export interface TopBarProps {
  /** Mobile-only menu trigger. When provided, renders the hamburger on < lg. */
  onOpenMobileNav?: () => void;
  className?: string;
}

const isMac =
  typeof navigator !== "undefined" &&
  /mac/i.test(typeof navigator.platform === "string" ? navigator.platform : "");

/**
 * Sticky 56px top bar. Renders a mobile hamburger, a brand mark (mobile-only —
 * sidebar owns it on lg+), a ⌘K command-palette trigger, the role/scope chip,
 * notifications + help icons, the user menu, and the DEV-only developer
 * tools menu. The actual command palette dialog is mounted here so its global
 * shortcut is always live; the trigger button dispatches the same shortcut so
 * mouse and keyboard users converge on one path.
 */
export function TopBar({ onOpenMobileNav, className }: TopBarProps) {
  const openPalette = useCallback(() => {
    // CommandPalette owns its own open state and toggles on Cmd/Ctrl+K.
    // We dispatch the same event so a click and the keystroke share one code path.
    if (typeof window === "undefined") return;
    window.dispatchEvent(
      new KeyboardEvent("keydown", { key: "k", ctrlKey: !isMac, metaKey: isMac, bubbles: true }),
    );
  }, []);

  return (
    <header
      data-slot="top-bar"
      className={cn(
        "bg-surface sticky top-0 z-40 flex h-14 shrink-0 items-center gap-3 border-b border-[var(--color-border)] px-4",
        className,
      )}
    >
      {onOpenMobileNav ? (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Open navigation"
          className="lg:hidden"
          onClick={onOpenMobileNav}
        >
          <Menu className="h-4 w-4" />
        </Button>
      ) : null}

      {/* Brand mark — visible only on mobile (sidebar owns it on lg+). */}
      <div className="flex items-center gap-2 lg:hidden">
        <span aria-hidden="true" className="bg-primary inline-block h-6 w-6 rounded" />
        <span className="text-foreground text-sm font-semibold">Bhawana</span>
      </div>

      {/* ⌘K trigger — flex-1 to push everything else right. */}
      <div className="flex flex-1 items-center justify-start">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={openPalette}
          aria-label="Open command palette"
          className="text-foreground-muted hover:text-foreground hidden w-full max-w-md justify-between gap-2 px-3 font-normal sm:inline-flex"
        >
          <span className="flex items-center gap-2">
            <Search aria-hidden="true" className="h-3.5 w-3.5" />
            <span>Search applications, borrowers…</span>
          </span>
          <kbd className="bg-surface-muted text-foreground-muted rounded border border-[var(--color-border)] px-1.5 py-0.5 font-mono text-[10px]">
            {isMac ? "⌘K" : "Ctrl K"}
          </kbd>
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Open command palette"
          onClick={openPalette}
          className="sm:hidden"
        >
          <Search className="h-4 w-4" />
        </Button>
      </div>

      <RoleScopeBadge className="hidden md:inline-flex" />

      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label="Notifications"
        title="Notifications"
      >
        <Bell className="h-4 w-4" />
      </Button>

      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label="Help"
        title="Help"
        className="hidden sm:inline-flex"
      >
        <HelpCircle className="h-4 w-4" />
      </Button>

      <ThemeToggle />

      <UserMenu />

      {import.meta.env.DEV ? <DevTopBarTools /> : null}

      <CommandPalette />
    </header>
  );
}
