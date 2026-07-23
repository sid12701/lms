import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";

const listMock = vi.fn();
const enforcementMock = vi.fn();
const addMock = vi.fn();
const removeMock = vi.fn();
const updateEnforcementMock = vi.fn();

vi.mock("../api", () => ({
  listLspIpAllowlist: (...args: unknown[]) => listMock(...args),
  getLspAllowlistEnforcement: (...args: unknown[]) => enforcementMock(...args),
  addLspIpAllowlistEntry: (...args: unknown[]) => addMock(...args),
  removeLspIpAllowlistEntry: (...args: unknown[]) => removeMock(...args),
  updateLspAllowlistEnforcement: (...args: unknown[]) => updateEnforcementMock(...args),
}));

import { useLspIpAllowlistAdmin } from "./useLspIpAllowlistAdmin";

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return Wrapper;
}

beforeEach(() => {
  listMock
    .mockReset()
    .mockImplementation(async (_id: string, surface: string) =>
      surface === "ui" ? [{ id: "ui-1", cidr: "10.0.0.0/8" }] : [],
    );
  enforcementMock.mockReset().mockResolvedValue({ enforceUi: true, enforceApi: false });
  addMock.mockReset().mockResolvedValue({ id: "new-entry" });
  removeMock.mockReset().mockResolvedValue(undefined);
  updateEnforcementMock.mockReset().mockResolvedValue({ enforceUi: false, enforceApi: false });
});

describe("useLspIpAllowlistAdmin", () => {
  it("keeps all three reads disabled until the dialog has an LSP", () => {
    const { result } = renderHook(() => useLspIpAllowlistAdmin(null, true), {
      wrapper: makeWrapper(),
    });

    expect(result.current.ui.fetchStatus).toBe("idle");
    expect(result.current.api.fetchStatus).toBe("idle");
    expect(result.current.enforcement.fetchStatus).toBe("idle");
    expect(listMock).not.toHaveBeenCalled();
    expect(enforcementMock).not.toHaveBeenCalled();
  });

  it("loads both surfaces and enforcement for the selected LSP", async () => {
    const { result } = renderHook(() => useLspIpAllowlistAdmin("lsp-1", true), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.ui.isSuccess).toBe(true));
    await waitFor(() => expect(result.current.api.isSuccess).toBe(true));
    await waitFor(() => expect(result.current.enforcement.isSuccess).toBe(true));
    expect(listMock).toHaveBeenCalledWith("lsp-1", "ui");
    expect(listMock).toHaveBeenCalledWith("lsp-1", "api");
    expect(enforcementMock).toHaveBeenCalledWith("lsp-1");
  });

  it("performs explicit save, remove, and enforcement mutations", async () => {
    const { result } = renderHook(() => useLspIpAllowlistAdmin("lsp-1", true), {
      wrapper: makeWrapper(),
    });
    await waitFor(() => expect(result.current.enforcement.isSuccess).toBe(true));

    await act(async () => {
      await result.current.saveEntries.mutateAsync({
        ui: ["10.0.0.0/8"],
        api: ["203.0.113.7"],
      });
      await result.current.removeEntry.mutateAsync({ surface: "ui", entryId: "entry-1" });
      await result.current.updateEnforcement.mutateAsync({ enforceApi: true });
    });

    expect(addMock).toHaveBeenCalledWith("lsp-1", "ui", { cidr: "10.0.0.0/8" });
    expect(addMock).toHaveBeenCalledWith("lsp-1", "api", { cidr: "203.0.113.7" });
    expect(removeMock).toHaveBeenCalledWith("lsp-1", "ui", "entry-1");
    expect(updateEnforcementMock).toHaveBeenCalledWith("lsp-1", { enforceApi: true });
  });

  it("retains mutation errors for the dialog to render", async () => {
    addMock.mockRejectedValue(new Error("save failed"));
    const { result } = renderHook(() => useLspIpAllowlistAdmin("lsp-1", true), {
      wrapper: makeWrapper(),
    });

    await expect(
      act(() => result.current.saveEntries.mutateAsync({ ui: ["10.0.0.0/8"], api: [] })),
    ).rejects.toThrow("save failed");
    await waitFor(() => expect(result.current.saveEntries.isError).toBe(true));
    expect(result.current.saveEntries.error?.message).toBe("save failed");
  });
});
