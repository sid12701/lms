import { describe, expect, it } from "vitest";
import { useForm } from "react-hook-form";
import { renderWithProviders } from "@/test/utils";
import { Form, FormControl, FormField, FormItem, FormLabel } from "@/components/ui/form";

interface Values {
  name: string;
}

function Harness({ required }: { required?: boolean }) {
  const form = useForm<Values>({ defaultValues: { name: "" } });
  return (
    <Form {...form}>
      <FormField
        control={form.control}
        name="name"
        render={({ field }) => (
          <FormItem required={required}>
            <FormLabel>Full name</FormLabel>
            <FormControl>
              <input {...field} />
            </FormControl>
          </FormItem>
        )}
      />
    </Form>
  );
}

describe("FormItem required", () => {
  it("marks the control programmatically without polluting its accessible name", () => {
    const { getByRole, getByText } = renderWithProviders(<Harness required />);

    // One declaration on <FormItem> drives both signals: assistive tech gets
    // `aria-required`, sighted users get the `*`. The name stays clean so the
    // constraint is announced once, not twice.
    const input = getByRole("textbox", { name: "Full name" });
    expect(input).toHaveAttribute("aria-required", "true");
    expect(getByText("*")).toHaveAttribute("aria-hidden", "true");
  });

  it("leaves optional fields unmarked", () => {
    const { getByRole } = renderWithProviders(<Harness />);

    const input = getByRole("textbox", { name: "Full name" });
    expect(input).not.toHaveAttribute("aria-required");
  });
});
