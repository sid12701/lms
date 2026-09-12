import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { TestProviders } from "@/test/test-providers";

const createUserMock = vi.hoisted(() => vi.fn());
const resetUserPasswordMock = vi.hoisted(() => vi.fn());

vi.mock("../api", () => ({
  listUsers: vi.fn(),
  createUser: createUserMock,
  updateUser: vi.fn(),
  resetUserPassword: resetUserPasswordMock,
  revokeUserSessions: vi.fn(),
}));

import { REPLAYED_CREATE_NOTICE, useUsersDialogController } from "./useUsersDialogController";
import type { UserRow } from "../types";

const ROW: UserRow = {
  id: "user-1",
  username: "operator",
  email: "operator@example.com",
  role: "OPS_USER",
  status: "ACTIVE",
  lspId: null,
  lspName: null,
  mustChangePassword: false,
  createdAt: "2026-06-08T10:00:00.000Z",
  lockedAt: null,
  lockReason: null,
};

function renderController() {
  return renderHook(() => useUsersDialogController(), { wrapper: TestProviders });
}

beforeEach(() => {
  createUserMock.mockReset();
  resetUserPasswordMock.mockReset();
});

describe("useUsersDialogController credential recovery", () => {
  it("surfaces a recovery notice — not a reveal — when create replays with a null credential", async () => {
    createUserMock.mockResolvedValue({
      user: { ...ROW, username: "new-user" },
      temporaryPassword: null,
    });
    const { result } = renderController();

    await act(async () => {
      await result.current.handleCreateConfirm({
        username: "new-user",
        email: "new@example.com",
        role: "OPS_USER",
        lspId: null,
        idempotencyKey: "replayed-key",
      });
    });

    expect(result.current.revealedTempPassword).toBeNull();
    expect(result.current.credentialRecoveryNotice).toBe(REPLAYED_CREATE_NOTICE);
  });

  it("reveals the server-minted password on first create and clears any notice", async () => {
    createUserMock.mockResolvedValue({
      user: { ...ROW, username: "new-user" },
      temporaryPassword: "server-secret",
    });
    const { result } = renderController();

    await act(async () => {
      await result.current.handleCreateConfirm({
        username: "new-user",
        email: "new@example.com",
        role: "OPS_USER",
        lspId: null,
        idempotencyKey: "fresh-key",
      });
    });

    expect(result.current.revealedTempPassword).toEqual({
      username: "new-user",
      password: "server-secret",
    });
    expect(result.current.credentialRecoveryNotice).toBeNull();
  });

  it("surfaces a reset recovery notice naming the user when reset replays without rotation", async () => {
    resetUserPasswordMock.mockResolvedValue({ temporaryPassword: null });
    const { result } = renderController();

    act(() => {
      result.current.openResetPassword(ROW);
    });
    await act(async () => {
      await result.current.handleResetConfirm({ idempotencyKey: "replayed-reset-key" });
    });

    expect(result.current.revealedTempPassword).toBeNull();
    expect(result.current.credentialRecoveryNotice).toContain("operator");
    expect(result.current.credentialRecoveryNotice).toContain("not changed again");

    await act(async () => {
      result.current.handleResetAcknowledge();
    });
    await waitFor(() => {
      expect(result.current.credentialRecoveryNotice).toBeNull();
    });
  });

  it("clears a previously revealed credential when the same create key retries as a replay", async () => {
    createUserMock
      .mockResolvedValueOnce({
        user: { ...ROW, username: "new-user" },
        temporaryPassword: "server-secret",
      })
      .mockResolvedValueOnce({
        user: { ...ROW, username: "new-user" },
        temporaryPassword: null,
      });
    const { result } = renderController();
    const confirm = {
      username: "new-user",
      email: "new@example.com",
      role: "OPS_USER" as const,
      lspId: null,
      idempotencyKey: "same-key-retried",
    };

    await act(async () => {
      await result.current.handleCreateConfirm(confirm);
    });
    expect(result.current.revealedTempPassword).toEqual({
      username: "new-user",
      password: "server-secret",
    });

    // Retry with the same key: replay carries no credential and must not
    // leave the previous value exposed behind the recovery notice.
    await act(async () => {
      await result.current.handleCreateConfirm(confirm);
    });
    expect(result.current.revealedTempPassword).toBeNull();
    expect(result.current.credentialRecoveryNotice).toBe(REPLAYED_CREATE_NOTICE);
  });

  it("clears the recovery notice when switching to another dialog", async () => {
    createUserMock.mockResolvedValue({
      user: { ...ROW, username: "new-user" },
      temporaryPassword: null,
    });
    const { result } = renderController();

    await act(async () => {
      await result.current.handleCreateConfirm({
        username: "new-user",
        email: "new@example.com",
        role: "OPS_USER" as const,
        lspId: null,
        idempotencyKey: "replayed-key",
      });
    });
    expect(result.current.credentialRecoveryNotice).not.toBeNull();

    act(() => {
      result.current.openResetPassword(ROW);
    });
    expect(result.current.credentialRecoveryNotice).toBeNull();

    act(() => {
      result.current.openCreate();
    });
    expect(result.current.dialog).toEqual({ kind: "create" });
  });
});
