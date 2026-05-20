import { useCallback, useState } from "react";
import { Command } from "cmdk";
import { Dialog, DialogContent, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { useShortcut } from "@/hooks/useShortcut";

/**
 * Global ⌘K command palette. Phase 2 ships an empty state — Phase 8 will
 * wire route + record search. The palette is portal'd via Dialog and
 * preserves focus trap automatically.
 */
export function CommandPalette() {
  const [open, setOpen] = useState(false);

  const toggle = useCallback(() => setOpen((v) => !v), []);
  useShortcut("k", toggle, { meta: true, ignoreInputs: false });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-xl gap-0 p-0" showCloseButton={false}>
        <DialogTitle className="sr-only">Command palette</DialogTitle>
        <DialogDescription className="sr-only">
          Search across the app. Press Escape to close.
        </DialogDescription>
        <Command label="Command palette" className="bg-popover rounded-md">
          <div className="border-b border-[var(--color-border)] px-4 py-3">
            <Command.Input
              placeholder="Search applications, borrowers, products…"
              className="placeholder:text-foreground-muted w-full bg-transparent text-sm outline-none"
            />
          </div>
          <Command.List className="max-h-72 overflow-auto p-2">
            <Command.Empty className="text-foreground-muted px-3 py-8 text-center text-sm">
              Search lands in Phase 8.
            </Command.Empty>
          </Command.List>
        </Command>
      </DialogContent>
    </Dialog>
  );
}
