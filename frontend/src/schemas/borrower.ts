/**
 * Borrower master record per blueprint §8 + UI pages.md borrower-detail.
 * Borrower is the canonical identity carrier (BR-1 one-open-loan key).
 */
import { z } from "zod";
import {
  Aadhaar,
  BankAccount,
  Email,
  Ifsc,
  IsoDate,
  Mobile,
  MoneyINR,
  Pan,
  PinCode,
  Uuid,
} from "./common";

export const Gender = z.enum(["M", "F", "O"]);
type Gender = z.infer<typeof Gender>;

export const MaritalStatus = z.enum(["SINGLE", "MARRIED", "DIVORCED", "WIDOWED"]);
type MaritalStatus = z.infer<typeof MaritalStatus>;

export const EmploymentType = z.enum([
  "SALARIED",
  "SELF_EMPLOYED",
  "BUSINESS",
  "RETIRED",
  "STUDENT",
  "UNEMPLOYED",
]);
type EmploymentType = z.infer<typeof EmploymentType>;

export const BorrowerAddress = z.object({
  residential: z.string().min(1).max(240),
  city: z.string().min(1).max(80),
  state: z.string().min(1).max(80),
  zip: PinCode,
});
type BorrowerAddress = z.infer<typeof BorrowerAddress>;

export const BorrowerEmployment = z.object({
  type: EmploymentType,
  organization: z.string().max(160).nullable(),
  employeeId: z.string().max(40).nullable(),
  location: z.string().max(120).nullable(),
  monthlyIncome: MoneyINR,
  annualIncome: MoneyINR,
});
type BorrowerEmployment = z.infer<typeof BorrowerEmployment>;

export const BorrowerBanking = z.object({
  bank: z.string().min(1).max(120),
  accountHolder: z.string().min(1).max(120),
  accountNumber: BankAccount,
  ifsc: Ifsc,
});
type BorrowerBanking = z.infer<typeof BorrowerBanking>;

export const BorrowerReference = z.object({
  name: z.string().min(1).max(120),
  contact: z.string().min(1).max(120),
});
type BorrowerReference = z.infer<typeof BorrowerReference>;

export const Borrower = z.object({
  id: Uuid,
  fullName: z.string().min(1).max(160),
  pan: Pan,
  aadhaar: Aadhaar,
  mobile: Mobile,
  email: Email.nullable(),
  dob: IsoDate,
  gender: Gender,
  maritalStatus: MaritalStatus,
  fathersName: z.string().min(1).max(160),
  spouseName: z.string().max(160).nullable(),
  address: BorrowerAddress,
  employment: BorrowerEmployment,
  banking: BorrowerBanking,
  references: z.array(BorrowerReference),
  /** True when all identity proofs verified — gate for BR-4. */
  kycComplete: z.boolean(),
  /** LSPs allowed to see this borrower (blueprint §8 borrower_lsp_tag). */
  visibleLspIds: z.array(Uuid),
});
export type Borrower = z.infer<typeof Borrower>;
