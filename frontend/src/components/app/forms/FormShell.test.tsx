import { describe, it, expect, vi } from "vitest";
import { useForm } from "react-hook-form";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { FormControl, FormField, FormItem, FormLabel } from "@/components/ui/form";
import { FormShell } from "./FormShell";

interface Values {
  name: string;
}

function Harness({ onSubmit }: { onSubmit: (v: Values) => void }) {
  const form = useForm<Values>({ defaultValues: { name: "" } });
  return (
    <FormShell form={form} onSubmit={onSubmit}>
      <FormField
        control={form.control}
        name="name"
        rules={{ required: "Name is required" }}
        render={({ field }) => (
          <FormItem>
            <FormLabel>Name</FormLabel>
            <FormControl>
              <input aria-label="name" {...field} />
            </FormControl>
          </FormItem>
        )}
      />
      <button type="submit">Submit</button>
    </FormShell>
  );
}

describe("FormShell", () => {
  it("submits when the form is valid", async () => {
    const onSubmit = vi.fn();
    const { getByLabelText, getByRole } = renderWithProviders(<Harness onSubmit={onSubmit} />);
    await userEvent.type(getByLabelText("name"), "Sid");
    await userEvent.click(getByRole("button", { name: "Submit" }));
    expect(onSubmit).toHaveBeenCalledOnce();
    expect(onSubmit.mock.calls[0]?.[0]).toMatchObject({ name: "Sid" });
  });

  it("renders an error summary with role=alert after invalid submit", async () => {
    const onSubmit = vi.fn();
    const { getByRole, findByText } = renderWithProviders(<Harness onSubmit={onSubmit} />);
    await userEvent.click(getByRole("button", { name: "Submit" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(await findByText("There are errors in this form")).toBeInTheDocument();
    expect(await findByText("Name is required")).toBeInTheDocument();
    expect(getByRole("alert")).toHaveAttribute("data-slot", "form-shell-summary");
  });

  it("moves focus to the first invalid field after invalid submit", async () => {
    const onSubmit = vi.fn();
    const { getByRole } = renderWithProviders(<Harness onSubmit={onSubmit} />);
    const nameInput = getByRole("textbox", { name: "name" });
    await userEvent.click(getByRole("button", { name: "Submit" }));
    await vi.waitFor(() => {
      expect(nameInput).toHaveFocus();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("focuses a field that is already marked invalid on a repeat submit", async () => {
    // The first submit commits `aria-invalid`; a second submit must still move
    // focus off the button. This is the case a `requestAnimationFrame` racing
    // React's commit used to fail silently in a real browser.
    const onSubmit = vi.fn();
    const { getByRole } = renderWithProviders(<Harness onSubmit={onSubmit} />);
    const nameInput = getByRole("textbox", { name: "name" });
    const submit = getByRole("button", { name: "Submit" });

    await userEvent.click(submit);
    await vi.waitFor(() => expect(nameInput).toHaveFocus());

    submit.focus();
    expect(submit).toHaveFocus();

    await userEvent.click(submit);
    await vi.waitFor(() => expect(nameInput).toHaveFocus());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("forwards className", () => {
    const Wrap = () => {
      const form = useForm<Values>();
      return (
        <FormShell form={form} onSubmit={() => {}} className="my-form">
          <span>x</span>
        </FormShell>
      );
    };
    const { container } = renderWithProviders(<Wrap />);
    expect(container.querySelector("form")).toHaveClass("my-form");
  });

  it("has no axe violations", async () => {
    const Wrap = () => {
      const form = useForm<Values>();
      return (
        <FormShell form={form} onSubmit={() => {}}>
          <label htmlFor="x">Name</label>
          <input id="x" {...form.register("name")} />
        </FormShell>
      );
    };
    const { container } = renderWithProviders(<Wrap />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
