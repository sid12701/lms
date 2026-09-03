import { ConfirmDestructiveDialog } from "@/components/app/forms/ConfirmDestructiveDialog";
import {
  UserCreateDialog,
  type UserCreateConfirmArgs,
  type UserCreateDialogLspOption,
} from "./UserCreateDialog";
import {
  UserEditDialog,
  type UserEditConfirmArgs,
  type UserEditDialogLspOption,
} from "./UserEditDialog";
import { ResetPasswordDialog, type ResetPasswordConfirmArgs } from "./ResetPasswordDialog";
import { RevokeSessionsDialog, type RevokeSessionsConfirmArgs } from "./RevokeSessionsDialog";
import type { RevealedTemporaryPassword, UserDialogState } from "../types";

export interface UsersDialogsProps {
  dialog: UserDialogState;
  revealedTempPassword: RevealedTemporaryPassword | null;
  lspOptions: readonly UserCreateDialogLspOption[];
  onCreateOpenChange: (open: boolean) => void;
  onCreateConfirm: (args: UserCreateConfirmArgs) => Promise<void> | void;
  onCreateAcknowledge: () => void;
  createLoading: boolean;
  createErrorMessage: string | null;
  onEditOpenChange: (open: boolean) => void;
  onEditConfirm: (args: UserEditConfirmArgs) => Promise<void> | void;
  updateLoading: boolean;
  updateErrorMessage: string | null;
  onResetOpenChange: (open: boolean) => void;
  onResetConfirm: (args: ResetPasswordConfirmArgs) => Promise<void> | void;
  onResetAcknowledge: () => void;
  resetLoading: boolean;
  resetErrorMessage: string | null;
  onRevokeOpenChange: (open: boolean) => void;
  onRevokeConfirm: (args: RevokeSessionsConfirmArgs) => Promise<void> | void;
  revokeLoading: boolean;
  revokeErrorMessage: string | null;
  onDisableOpenChange: (open: boolean) => void;
  onDisableConfirm: () => Promise<void> | void;
}

export function UsersDialogs({
  dialog,
  revealedTempPassword,
  lspOptions,
  onCreateOpenChange,
  onCreateConfirm,
  onCreateAcknowledge,
  createLoading,
  createErrorMessage,
  onEditOpenChange,
  onEditConfirm,
  updateLoading,
  updateErrorMessage,
  onResetOpenChange,
  onResetConfirm,
  onResetAcknowledge,
  resetLoading,
  resetErrorMessage,
  onRevokeOpenChange,
  onRevokeConfirm,
  revokeLoading,
  revokeErrorMessage,
  onDisableOpenChange,
  onDisableConfirm,
}: UsersDialogsProps) {
  const editTarget = dialog.kind === "edit" ? dialog.user : null;
  const resetTarget = dialog.kind === "reset-password" ? dialog.user : null;
  const revokeTarget = dialog.kind === "revoke-sessions" ? dialog.user : null;
  const disableTarget = dialog.kind === "disable" ? dialog.user : null;
  const editLspOptions: readonly UserEditDialogLspOption[] = lspOptions;

  return (
    <>
      <UserCreateDialog
        open={dialog.kind === "create"}
        onOpenChange={onCreateOpenChange}
        lspOptions={lspOptions}
        onConfirm={onCreateConfirm}
        temporaryPassword={revealedTempPassword?.password ?? null}
        createdUsername={revealedTempPassword?.username ?? null}
        onAcknowledgePassword={onCreateAcknowledge}
        loading={createLoading}
        errorMessage={createErrorMessage}
      />

      <UserEditDialog
        open={editTarget !== null}
        onOpenChange={onEditOpenChange}
        user={editTarget}
        lspOptions={editLspOptions}
        onConfirm={onEditConfirm}
        loading={updateLoading}
        errorMessage={updateErrorMessage}
      />

      <ResetPasswordDialog
        open={resetTarget !== null}
        onOpenChange={onResetOpenChange}
        username={resetTarget?.username ?? ""}
        onConfirm={onResetConfirm}
        temporaryPassword={resetTarget ? (revealedTempPassword?.password ?? null) : null}
        onAcknowledgePassword={onResetAcknowledge}
        loading={resetLoading}
        errorMessage={resetErrorMessage}
      />

      <RevokeSessionsDialog
        open={revokeTarget !== null}
        onOpenChange={onRevokeOpenChange}
        username={revokeTarget?.username ?? ""}
        onConfirm={onRevokeConfirm}
        loading={revokeLoading}
        errorMessage={revokeErrorMessage}
      />

      <ConfirmDestructiveDialog
        open={disableTarget !== null}
        onOpenChange={onDisableOpenChange}
        title="Disable user"
        description={
          updateErrorMessage ??
          `${disableTarget?.username ?? "This user"} will be signed out and blocked from signing in again. Their records and audit history are kept, and you can re-enable them at any time.`
        }
        confirmLabel="Disable user"
        onConfirm={onDisableConfirm}
        loading={updateLoading}
      />
    </>
  );
}
