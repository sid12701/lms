import { describe, expect, it } from "vitest";
import {
  Borrower,
  BorrowerAddress,
  BorrowerBanking,
  BorrowerEmployment,
  BorrowerReference,
  EmploymentType,
  Gender,
  MaritalStatus,
} from "./borrower";

const UUID = "550e8400-e29b-41d4-a716-446655440000";

function validBorrower() {
  return {
    id: UUID,
    fullName: "Asha Rao",
    pan: "ABCDE1234F",
    aadhaar: "123412341234",
    mobile: "9876543210",
    email: "asha@example.com",
    dob: "1992-04-12",
    gender: "F" as const,
    maritalStatus: "MARRIED" as const,
    fathersName: "Ramesh Rao",
    spouseName: "Vikram Rao",
    address: {
      residential: "1 MG Road",
      city: "Mumbai",
      state: "MH",
      zip: "400001",
    },
    employment: {
      type: "SALARIED" as const,
      organization: "Bhawana",
      employeeId: "E-001",
      location: "Mumbai",
      monthlyIncome: 50_000,
      annualIncome: 600_000,
    },
    banking: {
      bank: "HDFC",
      accountHolder: "Asha Rao",
      accountNumber: "123456789012",
      ifsc: "HDFC0001234",
    },
    references: [{ name: "Priya", contact: "9123456780" }],
    kycComplete: true,
    visibleLspIds: [UUID],
  };
}

describe("borrower enums", () => {
  it("Gender accepts M/F/O", () => {
    for (const g of ["M", "F", "O"]) expect(Gender.safeParse(g).success).toBe(true);
    expect(Gender.safeParse("X").success).toBe(false);
  });

  it("MaritalStatus accepts canonical values", () => {
    for (const s of ["SINGLE", "MARRIED", "DIVORCED", "WIDOWED"]) {
      expect(MaritalStatus.safeParse(s).success).toBe(true);
    }
    expect(MaritalStatus.safeParse("PARTNERED").success).toBe(false);
  });

  it("EmploymentType lists all categories", () => {
    for (const t of ["SALARIED", "SELF_EMPLOYED", "BUSINESS", "RETIRED", "STUDENT", "UNEMPLOYED"]) {
      expect(EmploymentType.safeParse(t).success).toBe(true);
    }
    expect(EmploymentType.safeParse("FREELANCER").success).toBe(false);
  });
});

describe("nested schemas", () => {
  it("BorrowerAddress requires non-empty fields", () => {
    expect(
      BorrowerAddress.safeParse({ residential: "x", city: "Mumbai", state: "MH", zip: "400001" })
        .success,
    ).toBe(true);
    expect(
      BorrowerAddress.safeParse({ residential: "", city: "Mumbai", state: "MH", zip: "400001" })
        .success,
    ).toBe(false);
  });

  it("BorrowerEmployment requires non-negative incomes", () => {
    expect(
      BorrowerEmployment.safeParse({
        type: "STUDENT",
        organization: null,
        employeeId: null,
        location: null,
        monthlyIncome: 0,
        annualIncome: 0,
      }).success,
    ).toBe(true);
    expect(
      BorrowerEmployment.safeParse({
        type: "STUDENT",
        organization: null,
        employeeId: null,
        location: null,
        monthlyIncome: -1,
        annualIncome: 0,
      }).success,
    ).toBe(false);
  });

  it("BorrowerBanking requires IFSC + account", () => {
    expect(
      BorrowerBanking.safeParse({
        bank: "HDFC",
        accountHolder: "X",
        accountNumber: "123456789",
        ifsc: "BAD",
      }).success,
    ).toBe(false);
  });

  it("BorrowerReference accepts a basic reference", () => {
    expect(BorrowerReference.safeParse({ name: "X", contact: "9876543210" }).success).toBe(true);
  });
});

describe("Borrower full record", () => {
  it("BR-1: accepts a fully-valid borrower", () => {
    expect(Borrower.safeParse(validBorrower()).success).toBe(true);
  });

  it("rejects malformed PAN", () => {
    expect(Borrower.safeParse({ ...validBorrower(), pan: "abcde1234f" }).success).toBe(false);
  });

  it("rejects mobile with wrong prefix", () => {
    expect(Borrower.safeParse({ ...validBorrower(), mobile: "5111111111" }).success).toBe(false);
  });

  it("rejects Aadhaar with spaces", () => {
    expect(Borrower.safeParse({ ...validBorrower(), aadhaar: "1234 1234 1234" }).success).toBe(
      false,
    );
  });

  it("accepts null email + null spouseName", () => {
    const b = { ...validBorrower(), email: null, spouseName: null };
    expect(Borrower.safeParse(b).success).toBe(true);
  });
});
