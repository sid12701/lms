/**
 * Aggregated re-export of all domain types inferred from `src/schemas/*`.
 *
 * Consumers should import types from `@/types` rather than reaching into
 * individual schema files. The runtime Zod schemas remain in `@/schemas`.
 */
export type {
  Aadhaar,
  BankAccount,
  Email,
  IdempotencyKey,
  Ifsc,
  IpCidr,
  Iso8601,
  IsoDate,
  MoneyINR,
  MoneyINRPositive,
  Mobile,
  Pan,
  PinCode,
  Uuid,
} from "@/schemas/common";

export type { ActorContext, ActorType, Channel, Permission, Role } from "@/schemas/role";

export type { Lsp, LspStatus } from "@/schemas/lsp";

export type { LoanProduct, ProductLspMapping, ProductStatus } from "@/schemas/product";

export type { ApiClient, ApiClientStatus, User, UserStatus } from "@/schemas/user";

export type {
  Borrower,
  BorrowerAddress,
  BorrowerBanking,
  BorrowerEmployment,
  BorrowerReference,
  EmploymentType,
  Gender,
  MaritalStatus,
} from "@/schemas/borrower";

export type {
  LoanApplication,
  LoanDocument,
  LoanDocumentFileMeta,
  LoanDocumentStatus,
  LoanDocumentType,
  LoanStatus,
  SourceChannel,
} from "@/schemas/loan-application";

export type {
  ClosureReason,
  DelinquencyBucket,
  InstallmentStatus,
  LoanAccount,
  LoanAccountStatus,
  RepaymentInstallment,
  RepaymentSchedule,
  ScheduleGenerator,
} from "@/schemas/loan-account";

export type { LspProvidedInstallment, LspProvidedSchedule } from "@/schemas/lsp-provided-schedule";

export type { PaymentTransaction } from "@/schemas/payment";

export type {
  AlertSeverity,
  AlertStatus,
  AlertSubjectType,
  OperationalAlert,
} from "@/schemas/alert";

export type { MisPreviewRow, MisSummary, ReportRequest, ReportStatus } from "@/schemas/report";

export type {
  ApplicationAuditEvent,
  DocumentAccessEvent,
  IntakeAuditEvent,
  PiiRevealEvent,
  ProductAuditEvent,
} from "@/schemas/audit";
