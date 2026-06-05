export interface FormDialogErrorAlertProps {
  message: string | null;
}

export function FormDialogErrorAlert({ message }: FormDialogErrorAlertProps) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="border-danger/30 bg-danger/5 text-danger rounded-md border p-3 text-sm"
    >
      {message}
    </div>
  );
}
