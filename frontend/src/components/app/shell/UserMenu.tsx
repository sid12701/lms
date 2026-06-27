import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { DropdownMenu } from "radix-ui";
import { ChevronDown, LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useSession } from "@/features/auth/session-context";
import { AvatarInitials } from "@/components/app/misc/AvatarInitials";
import { formatRoleLabel } from "@/lib/role-labels";
import { cn } from "@/lib/utils";

const menuContentClass =
  "z-50 min-w-[12rem] rounded-md border border-(--color-border) bg-popover p-1 shadow-md outline-none data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=closed]:animate-out data-[state=closed]:fade-out-0";

const itemClass =
  "flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-highlighted:bg-accent/10 data-highlighted:text-foreground";

/**
 * TopBar user menu. DropdownMenu (radix) showing role, scope, and sign-out.
 */
export function UserMenu() {
  const { session, signOut } = useSession();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  if (!session) return null;

  return (
    <DropdownMenu.Root open={open} onOpenChange={setOpen}>
      <DropdownMenu.Trigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="gap-2"
          aria-label={`Account menu for ${session.user.username}`}
        >
          <AvatarInitials name={session.user.username} size="sm" />
          <span className="hidden text-sm font-medium md:inline">{session.user.username}</span>
          <ChevronDown aria-hidden="true" className="h-3 w-3" />
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content sideOffset={6} align="end" className={menuContentClass}>
          <DropdownMenu.Label className="text-foreground-muted px-2 py-1.5 text-xs">
            Signed in as
          </DropdownMenu.Label>
          <DropdownMenu.Group>
            <div className="px-2 pb-2">
              <p className="text-foreground text-sm font-medium">{session.user.username}</p>
              <p className="text-foreground-muted text-xs">{formatRoleLabel(session.user.role)}</p>
            </div>
          </DropdownMenu.Group>
          <DropdownMenu.Separator className="my-1 h-px bg-(--color-border)" />
          <DropdownMenu.Item
            className={cn(itemClass, "text-danger data-highlighted:text-danger")}
            onSelect={async () => {
              await signOut();
              navigate("/login", { replace: true });
            }}
          >
            <LogOut aria-hidden="true" className="h-4 w-4" />
            <span>Sign out</span>
          </DropdownMenu.Item>
          <DropdownMenu.Separator className="my-1 h-px bg-(--color-border)" />
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
