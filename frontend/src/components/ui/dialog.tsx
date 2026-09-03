import * as React from "react";
import { Dialog as DialogPrimitive } from "radix-ui";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { IconX } from "@tabler/icons-react";

function Dialog({ ...props }: React.ComponentProps<typeof DialogPrimitive.Root>) {
  return <DialogPrimitive.Root data-slot="dialog" {...props} />;
}

function DialogTrigger({ ...props }: React.ComponentProps<typeof DialogPrimitive.Trigger>) {
  return <DialogPrimitive.Trigger data-slot="dialog-trigger" {...props} />;
}

function DialogPortal({ ...props }: React.ComponentProps<typeof DialogPrimitive.Portal>) {
  return <DialogPrimitive.Portal data-slot="dialog-portal" {...props} />;
}

function DialogClose({ ...props }: React.ComponentProps<typeof DialogPrimitive.Close>) {
  return <DialogPrimitive.Close data-slot="dialog-close" {...props} />;
}

function DialogOverlay({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      data-slot="dialog-overlay"
      className={cn(
        // 50%, the shadcn default. At 80% the scrim was near-opaque and the
        // record the operator is acting on became unreadable behind its own
        // confirmation dialog — on a panel whose job is to let you check the
        // context before you commit.
        "data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0 fixed inset-0 isolate z-50 bg-black/50 duration-100 supports-backdrop-filter:backdrop-blur-xs",
        className,
      )}
      {...props}
    />
  );
}

function DialogContent({
  className,
  children,
  showCloseButton = true,
  onOpenAutoFocus,
  onCloseAutoFocus,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & {
  showCloseButton?: boolean;
}) {
  /*
    Return focus to whatever opened the dialog.

    Radix restores focus by calling `triggerRef.current?.focus()`, and that ref is
    populated only by `<DialogPrimitive.Trigger>`. Every dialog in this app is
    controlled — opened from an ordinary button, a row action, or a lifecycle
    control — so the ref is always null, the call is a no-op, and focus fell to
    `<body>`. A keyboard operator pressing Escape on the disbursement dialog
    landed at the top of the document and had to walk the sidebar, top bar,
    breadcrumb and tabs to get back (WCAG 2.4.3).

    These two events are the open/close pair Radix fires around its own focus
    move, so the opener is still focused when the first one runs.
  */
  const openerRef = React.useRef<HTMLElement | null>(null);

  const handleOpenAutoFocus = React.useCallback(
    (event: Event) => {
      const active = document.activeElement;
      openerRef.current = active instanceof HTMLElement && active !== document.body ? active : null;
      onOpenAutoFocus?.(event);
    },
    [onOpenAutoFocus],
  );

  const handleCloseAutoFocus = React.useCallback(
    (event: Event) => {
      onCloseAutoFocus?.(event);
      if (event.defaultPrevented) return;
      const opener = openerRef.current;
      // The opener can be gone — a row action whose row was removed by the very
      // action just confirmed. Radix's default lands focus on <body>, which is
      // the right fallback there.
      if (!opener?.isConnected) return;
      event.preventDefault();
      opener.focus();
    },
    [onCloseAutoFocus],
  );

  return (
    <DialogPortal>
      <DialogOverlay />
      <DialogPrimitive.Content
        data-slot="dialog-content"
        onOpenAutoFocus={handleOpenAutoFocus}
        onCloseAutoFocus={handleCloseAutoFocus}
        className={cn(
          // Width note: the default cap is `sm:max-w-sm`. Because that is a
          // breakpoint variant, a caller passing a *base* `max-w-*` is silently
          // ignored above 640px — tailwind-merge cannot dedupe across variants.
          // Callers that need a wider dialog must pass `sm:max-w-*`.
          "bg-popover text-popover-foreground ring-foreground/10 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 rounded-container fixed top-1/2 left-1/2 z-50 grid max-h-[calc(100dvh-2rem)] w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-4 overflow-y-auto p-4 text-xs/relaxed ring-1 duration-100 outline-none sm:max-w-sm",
          className,
        )}
        {...props}
      >
        {children}
        {showCloseButton && (
          <DialogPrimitive.Close data-slot="dialog-close" asChild>
            <Button variant="ghost" className="absolute top-2 right-2" size="icon-sm">
              <IconX />
              <span className="sr-only">Close dialog</span>
            </Button>
          </DialogPrimitive.Close>
        )}
      </DialogPrimitive.Content>
    </DialogPortal>
  );
}

function DialogHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div data-slot="dialog-header" className={cn("flex flex-col gap-1", className)} {...props} />
  );
}

function DialogFooter({
  className,
  showCloseButton = false,
  children,
  ...props
}: React.ComponentProps<"div"> & {
  showCloseButton?: boolean;
}) {
  return (
    // Pinned to the bottom of `DialogContent`'s scroll box. `DialogContent` caps
    // its height and scrolls as a whole, so on a short viewport a tall dialog
    // used to push its own actions below the fold with nothing on screen
    // suggesting they existed — found live on `DisbursementInitiateDialog`, at
    // 697px, where both Cancel and the confirm button were fully out of view.
    // The negative inset bleeds the footer across the container's `p-4` so
    // scrolled content cannot show through beside or beneath it.
    <div
      data-slot="dialog-footer"
      className={cn(
        // `-bottom-4` rather than `bottom-0`: a sticky offset is measured from the
        // scrollport's padding edge, so `bottom-0` would leave the container's
        // `p-4` gutter uncovered and scrolled content would show through beneath
        // the actions. `pb-4` keeps the buttons themselves off the edge.
        "bg-popover sticky -bottom-4 z-10 -mx-4 flex flex-col-reverse gap-2 px-4 pt-2 pb-4 sm:flex-row sm:justify-end",
        className,
      )}
      {...props}
    >
      {children}
      {showCloseButton && (
        <DialogPrimitive.Close asChild>
          <Button variant="outline">Close</Button>
        </DialogPrimitive.Close>
      )}
    </div>
  );
}

function DialogTitle({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Title>) {
  return (
    <DialogPrimitive.Title
      data-slot="dialog-title"
      className={cn("font-heading text-sm font-medium", className)}
      {...props}
    />
  );
}

function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Description>) {
  return (
    <DialogPrimitive.Description
      data-slot="dialog-description"
      className={cn(
        "text-muted-foreground *:[a]:hover:text-foreground text-xs/relaxed *:[a]:underline *:[a]:underline-offset-3",
        className,
      )}
      {...props}
    />
  );
}

export {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  DialogTrigger,
};
