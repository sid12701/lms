import { format, parse, parseISO } from "date-fns";
import { enIN } from "date-fns/locale";
import { screen, waitFor } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";

function dayButtonLabel(date: Date): string {
  return date.toLocaleDateString(enIN.code);
}

function readVisibleMonth(): Date | null {
  const caption = document.querySelector(".cn-calendar-caption");
  if (!caption?.textContent?.trim()) return null;
  const parsed = parse(caption.textContent.trim(), "MMMM yyyy", new Date());
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

async function ensureCalendarMonth(user: UserEvent, target: Date) {
  const targetCaption = format(target, "MMMM yyyy");

  for (let i = 0; i < 24; i += 1) {
    const visible = readVisibleMonth();
    if (visible && format(visible, "MMMM yyyy") === targetCaption) return;

    const buttonName =
      visible && visible.getTime() > target.getTime() ? /Previous Month/i : /Next Month/i;
    await user.click(screen.getByRole("button", { name: buttonName }));
  }

  throw new Error(`Could not navigate calendar to ${targetCaption}`);
}

/** Pick a calendar day in a DatePickerField (ISO `yyyy-MM-dd`). */
export async function pickDateInField(user: UserEvent, fieldLabel: string | RegExp, iso: string) {
  const target = parseISO(iso);
  const targetLabel = dayButtonLabel(target);
  await user.click(screen.getByRole("button", { name: fieldLabel }));
  await ensureCalendarMonth(user, target);
  const dayButton = await waitFor(() => {
    const buttons = Array.from(document.querySelectorAll("button[data-day]"));
    const match = buttons.find((btn) => btn.getAttribute("data-day") === targetLabel);
    if (!match) throw new Error(`day ${targetLabel} not found`);
    return match;
  });
  await user.click(dayButton);
}
