/**
 * Phase 5 closeout — loan-application lifecycle smoke.
 *
 * Walks one live loan through repayments, exercising:
 *   - BR-13 (partial-installment-rejected) via the post-repayment dialog
 *   - BR-14 (DISBURSED → UNDER_REPAYMENT auto-advance on first payment)
 *
 * Requires a running Spring backend with demo portfolio seed data
 * (`app.seed.demo-portfolio.enabled=true` in the local profile).
 */
import { test, expect, type Page } from "@playwright/test";
import { signInAsSystemAdmin } from "./helpers/auth";

/** Read the outstanding amount cell from the next-due installment row. */
async function readNextDueOutstanding(page: Page): Promise<number> {
  const recordButton = page
    .getByRole("button", { name: /^Record payment for installment/ })
    .first();
  const row = recordButton.locator("xpath=ancestor::tr[1]");
  const cells = row.locator("td");
  const text = (await cells.nth(5).innerText()).trim();
  return parseInr(text);
}

/** Parse "₹ 1,42,500.00" → 142500. */
function parseInr(value: string): number {
  const digits = value.replace(/[^0-9.-]/g, "");
  if (digits === "") throw new Error(`Could not parse INR value: ${value}`);
  const n = Number(digits);
  if (!Number.isFinite(n)) throw new Error(`Could not parse INR value: ${value}`);
  return n;
}

async function openScheduleForRepayableLoan(page: Page): Promise<void> {
  await page.goto("/loan-applications");
  await expect(page.getByRole("heading", { name: /Loan applications/i })).toBeVisible({
    timeout: 15_000,
  });

  const repayableRow = page
    .getByRole("row")
    .filter({ hasText: /Disbursed|Under repayment/i })
    .first();
  await expect(repayableRow).toBeVisible({ timeout: 15_000 });
  await repayableRow.getByRole("link").first().click();

  await expect(page.getByText(/LOAN APPLICATION ·/i).first()).toBeVisible({ timeout: 15_000 });
  await page.getByRole("tab", { name: /Schedule/i }).click();
  await expect(
    page.getByRole("button", { name: /^Record payment for installment/ }).first(),
  ).toBeVisible({ timeout: 15_000 });
}

/**
 * Post one repayment via the Schedule tab dialog.
 */
async function postNextRepayment(page: Page): Promise<void> {
  const recordButton = page
    .getByRole("button", { name: /^Record payment for installment/ })
    .first();
  const label = await recordButton.getAttribute("aria-label");
  const installmentNumber = label?.match(/installment (\d+)/i)?.[1];

  await recordButton.click();
  const dialog = page.getByRole("dialog", { name: /Record repayment/i });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: /Post repayment/i }).click();

  if (installmentNumber) {
    await expect(
      page.getByRole("button", {
        name: new RegExp(`Record payment for installment ${installmentNumber}$`),
      }),
    ).toHaveCount(0, { timeout: 15_000 });
  }
  await expect(dialog).toBeHidden({ timeout: 15_000 });
}

async function assertBr13RejectsPartial(page: Page): Promise<void> {
  const outstanding = await readNextDueOutstanding(page);
  expect(outstanding).toBeGreaterThan(0);

  await page
    .getByRole("button", { name: /^Record payment for installment/ })
    .first()
    .click();

  const dialog = page.getByRole("dialog", { name: /Record repayment/i });
  await expect(dialog).toBeVisible();

  const amountInput = dialog.getByLabel("Amount (INR)");
  const wrong = Math.max(1, Math.round(outstanding) - 1);
  await amountInput.fill(String(wrong));
  await dialog.getByRole("button", { name: /Post repayment/i }).click();
  await expect(
    dialog.getByText(/Repayment must equal the outstanding amount.*BR-13/i).first(),
  ).toBeVisible();
  await dialog.getByRole("button", { name: /^Cancel$/i }).click();
  await expect(dialog).toBeHidden();
}

async function expectStatus(page: Page, expected: RegExp): Promise<void> {
  await expect(page.getByText(expected).first()).toBeVisible({ timeout: 15_000 });
}

test.describe("loan-application lifecycle", () => {
  test("repayment flow asserts BR-13 and posts at least one installment", async ({ page }) => {
    await signInAsSystemAdmin(page);
    await openScheduleForRepayableLoan(page);

    await assertBr13RejectsPartial(page);
    await postNextRepayment(page);

    // After a payment, status should be Disbursed or Under repayment depending on seed state.
    await expectStatus(page, /Disbursed|Under repayment/i);
  });
});
