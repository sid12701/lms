import { useCallback, useState } from "react";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { Role as RoleT } from "@/schemas/role";
import type { UserStatus as UserStatusT } from "@/schemas/user";
import type { UserDialogState, UserRow, RevealedTemporaryPassword } from "../types";
import { useCreateUser } from "./useCreateUser";
import { useResetUserPassword } from "./useResetUserPassword";
import { useRevokeUserSessions } from "./useRevokeUserSessions";
import { useUpdateUser } from "./useUpdateUser";

export function useUsersDialogController() {
  const [dialog, setDialog] = useState<UserDialogState>({ kind: "none" });
  const [revealedTempPassword, setRevealedTempPassword] =
    useState<RevealedTemporaryPassword | null>(null);
  const create = useCreateUser();
  const update = useUpdateUser();
  const reset = useResetUserPassword();
  const revokeSessions = useRevokeUserSessions();

  const clearRevealed = () => setRevealedTempPassword(null);
  const openCreate = () => setDialog({ kind: "create" });

  /*
    The row-action handlers below are memoised because `UsersTable` lists them in
    the dependency array of its `columns` memo, and TanStack renders each column's
    `cell` function *as a React component type*. A new function identity therefore
    remounts every cell in the table — which threw away the button the operator
    had just activated, so closing the dialog had no element left to return focus
    to. `setDialog` is a `useState` setter and `update.mutate` is stable, so these
    genuinely never need to change.
  */
  const openEdit = useCallback((user: UserRow) => setDialog({ kind: "edit", user }), []);
  const openResetPassword = useCallback(
    (user: UserRow) => setDialog({ kind: "reset-password", user }),
    [],
  );
  const openRevokeSessions = useCallback(
    (user: UserRow) => setDialog({ kind: "revoke-sessions", user }),
    [],
  );

  const editTarget = dialog.kind === "edit" ? dialog.user : null;
  const resetTarget = dialog.kind === "reset-password" ? dialog.user : null;
  const revokeTarget = dialog.kind === "revoke-sessions" ? dialog.user : null;
  const disableTarget = dialog.kind === "disable" ? dialog.user : null;

  const handleCreateOpenChange = (open: boolean) => {
    if (open) {
      openCreate();
      return;
    }
    if (create.isPending) return;
    setDialog({ kind: "none" });
    create.reset();
    clearRevealed();
  };

  const handleCreateConfirm = async ({
    username,
    email,
    role,
    lspId,
    idempotencyKey,
  }: {
    username: string;
    email: string;
    role: RoleT;
    lspId: string | null;
    idempotencyKey: string;
  }) => {
    try {
      const result = await create.mutateAsync({
        username,
        email,
        role,
        lspId,
        idempotencyKey,
      });
      setRevealedTempPassword({
        username: result.user.username,
        password: result.temporaryPassword,
      });
    } catch {
      // Surfaced via create.error.
    }
  };

  const handleCreateAcknowledge = () => {
    setDialog({ kind: "none" });
    create.reset();
    clearRevealed();
  };

  const handleEditOpenChange = (open: boolean) => {
    if (open || update.isPending) return;
    setDialog({ kind: "none" });
    update.reset();
  };

  const handleEditConfirm = async ({
    email,
    role,
    lspId,
    status,
    idempotencyKey,
  }: {
    email: string;
    role: RoleT;
    lspId: string | null;
    status: UserStatusT;
    idempotencyKey: string;
  }) => {
    if (!editTarget) return;
    try {
      await update.mutateAsync({
        id: editTarget.id,
        email,
        role,
        lspId,
        status,
        idempotencyKey,
      });
      setDialog({ kind: "none" });
      update.reset();
    } catch {
      // Surfaced via update.error.
    }
  };

  const handleResetOpenChange = (open: boolean) => {
    if (open || reset.isPending) return;
    setDialog({ kind: "none" });
    reset.reset();
    clearRevealed();
  };

  const handleResetConfirm = async ({ idempotencyKey }: { idempotencyKey: string }) => {
    if (!resetTarget) return;
    try {
      const result = await reset.mutateAsync({
        id: resetTarget.id,
        idempotencyKey,
      });
      setRevealedTempPassword({
        username: resetTarget.username,
        password: result.temporaryPassword,
      });
    } catch {
      // Surfaced via reset.error.
    }
  };

  const handleResetAcknowledge = () => {
    setDialog({ kind: "none" });
    reset.reset();
    clearRevealed();
  };

  const handleRevokeOpenChange = (open: boolean) => {
    if (open || revokeSessions.isPending) return;
    setDialog({ kind: "none" });
    revokeSessions.reset();
  };

  const handleRevokeConfirm = async ({
    reason,
    idempotencyKey,
  }: {
    reason?: string;
    idempotencyKey: string;
  }) => {
    if (!revokeTarget) return;
    try {
      await revokeSessions.mutateAsync({
        id: revokeTarget.id,
        username: revokeTarget.username,
        reason,
        idempotencyKey,
      });
      setDialog({ kind: "none" });
      revokeSessions.reset();
    } catch {
      // Surfaced via revokeSessions.error.
    }
  };

  const mutateUser = update.mutate;
  const setUserStatus = useCallback(
    (row: UserRow, nextStatus: UserStatusT) => {
      mutateUser({
        id: row.id,
        status: nextStatus,
        idempotencyKey: newIdempotencyKey(),
      });
    },
    [mutateUser],
  );

  const handleToggleStatus = useCallback(
    (row: UserRow) => {
      if (row.status === "DISABLED") {
        setUserStatus(row, "ACTIVE");
        return;
      }
      setDialog({ kind: "disable", user: row });
    },
    [setUserStatus],
  );

  const handleDisableOpenChange = (open: boolean) => {
    if (open || update.isPending) return;
    update.reset();
    setDialog({ kind: "none" });
  };

  const handleDisableConfirm = async () => {
    if (!disableTarget) return;
    try {
      await update.mutateAsync({
        id: disableTarget.id,
        status: "DISABLED",
        idempotencyKey: newIdempotencyKey(),
      });
      update.reset();
      setDialog({ kind: "none" });
    } catch {
      // Surfaced via update.error inside the dialog description slot.
    }
  };

  return {
    dialog,
    revealedTempPassword,
    clearRevealed,
    openCreate,
    openEdit,
    openResetPassword,
    openRevokeSessions,
    handleCreateOpenChange,
    handleCreateConfirm,
    handleCreateAcknowledge,
    handleEditOpenChange,
    handleEditConfirm,
    handleResetOpenChange,
    handleResetConfirm,
    handleResetAcknowledge,
    handleRevokeOpenChange,
    handleRevokeConfirm,
    handleToggleStatus,
    handleDisableOpenChange,
    handleDisableConfirm,
    create,
    update,
    reset,
    revokeSessions,
  };
}
