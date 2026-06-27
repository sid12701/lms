import {
  InlineErrorAlert,
  type InlineErrorAlertProps,
} from "@/components/app/feedback/InlineErrorAlert";

export type FormDialogErrorAlertProps = InlineErrorAlertProps;

export function FormDialogErrorAlert(props: FormDialogErrorAlertProps) {
  return <InlineErrorAlert {...props} />;
}
