import { describe, expect, it } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { z } from "zod";
import { useUrlFilters } from "./url-state";

const Schema = z.object({
  q: z.string().trim().min(1).optional(),
  status: z.array(z.enum(["OPEN", "CLOSED"])).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});

function renderAt(search: string) {
  return renderHook(() => useUrlFilters(Schema), {
    wrapper: ({ children }) => <MemoryRouter initialEntries={[search]}>{children}</MemoryRouter>,
  });
}

describe("useUrlFilters", () => {
  it("parses valid params and reports nothing ignored", () => {
    const { result } = renderAt("/?q=acme&status=OPEN&pageSize=25");
    const [filters, , ignored] = result.current;

    expect(filters).toEqual({ q: "acme", status: ["OPEN"], pageSize: 25 });
    expect(ignored).toEqual([]);
  });

  it("reads repeated params as an array", () => {
    const { result } = renderAt("/?status=OPEN&status=CLOSED");

    expect(result.current[0].status).toEqual(["OPEN", "CLOSED"]);
    expect(result.current[2]).toEqual([]);
  });

  /**
   * A dropped filter turns a slice of the book into the whole book with no
   * visible difference. The values must still degrade gracefully, but the
   * surface has to be able to say what it ignored.
   */
  it("names every param it could not apply instead of dropping it silently", () => {
    const { result } = renderAt("/?status=NOT_A_STATUS&pageSize=9999&q=acme");
    const [filters, , ignored] = result.current;

    expect(filters).toEqual({ q: "acme" });
    expect(ignored).toEqual(["status", "pageSize"]);
  });

  it("treats a comma-joined array as invalid rather than half-applying it", () => {
    const { result } = renderAt("/?status=OPEN,CLOSED");

    expect(result.current[0].status).toBeUndefined();
    expect(result.current[2]).toEqual(["status"]);
  });

  it("ignores absent params without flagging them", () => {
    const { result } = renderAt("/?q=acme");

    expect(result.current[2]).toEqual([]);
  });
});
