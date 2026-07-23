import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import type { LoanProduct } from "../types";
import { ProductEditDialog } from "./ProductEditDialog";

const product: LoanProduct = {
  id: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
  code: "PERSONAL",
  name: "Personal loan",
  status: "INACTIVE",
  principalMin: 10_000,
  principalMax: 500_000,
  interestRatePct: 14.5,
  processingFeePct: 1.25,
  tenureMinMonths: 3,
  tenureMaxMonths: 36,
  createdAt: "2026-01-01T00:00:00.000Z",
};

describe("ProductEditDialog", () => {
  it("hydrates every editable field from the selected product", () => {
    renderWithProviders(
      <ProductEditDialog open onOpenChange={vi.fn()} product={product} onConfirm={vi.fn()} />,
    );

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Personal loan");
    expect(screen.getByRole("combobox", { name: "Status" })).toHaveTextContent("Inactive");
    expect(screen.getByRole("spinbutton", { name: "Min principal (INR)" })).toHaveValue(10_000);
    expect(screen.getByRole("spinbutton", { name: "Max principal (INR)" })).toHaveValue(500_000);
    expect(screen.getByRole("spinbutton", { name: "Interest rate (%)" })).toHaveValue(14.5);
    expect(screen.getByRole("spinbutton", { name: "Processing fee (%)" })).toHaveValue(1.25);
    expect(screen.getByRole("spinbutton", { name: "Min tenure (months)" })).toHaveValue(3);
    expect(screen.getByRole("spinbutton", { name: "Max tenure (months)" })).toHaveValue(36);
  });

  it("uses safe form defaults when no product is selected", () => {
    renderWithProviders(
      <ProductEditDialog open onOpenChange={vi.fn()} product={null} onConfirm={vi.fn()} />,
    );

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Status" })).toHaveTextContent("Active");
    expect(screen.getByRole("spinbutton", { name: "Min principal (INR)" })).toHaveValue(0);
    expect(screen.getByRole("spinbutton", { name: "Max principal (INR)" })).toHaveValue(0);
    expect(screen.getByRole("spinbutton", { name: "Min tenure (months)" })).toHaveValue(1);
    expect(screen.getByRole("spinbutton", { name: "Max tenure (months)" })).toHaveValue(12);
  });
});
