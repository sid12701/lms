import { Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTheme } from "@/app/providers";

/**
 * Icon-only theme toggle for the TopBar. Mirrors the visual treatment of the
 * other TopBar icon buttons (Bell, HelpCircle) — shadcn `ghost` + `icon-sm`.
 * Reads + writes theme via the `useTheme()` hook from `@/app/providers`, which
 * already persists the preference and toggles the `.dark` class on
 * `document.documentElement`.
 */
export function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const isDark = theme === "dark";
  const label = isDark ? "Switch to light theme" : "Switch to dark theme";

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      data-slot="theme-toggle"
      aria-label={label}
      title={label}
      onClick={toggle}
    >
      {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </Button>
  );
}
