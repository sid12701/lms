import { describe, it, expect, vi } from "vitest";
import { useForm } from "react-hook-form";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { FormShell } from "./FormShell";

interface Values {
  name: string;
}

function Harness({ onSubmit }: { onSubmit: (v: Values) => void }) {
  const form = useForm<Values>({ defaultValues: { name: "" } });
  return (
    <FormShell form={form} onSubmit={onSubmit}>
      <input aria-label="name" {...form.register("name", { required: "Name is required" })} />
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

  it("renders an error summary listing field errors after invalid submit", async () => {
    const onSubmit = vi.fn();
    const { getByRole, findByText } = renderWithProviders(<Harness onSubmit={onSubmit} />);
    await userEvent.click(getByRole("button", { name: "Submit" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(await findByText("There are errors in this form")).toBeInTheDocument();
    expect(await findByText("Name is required")).toBeInTheDocument();
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
