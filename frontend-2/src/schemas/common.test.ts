import { describe, expect, it } from "vitest";
import {
  Aadhaar,
  BankAccount,
  Email,
  IdempotencyKey,
  Ifsc,
  IpCidr,
  Iso8601,
  IsoDate,
  Mobile,
  MoneyINR,
  MoneyINRPositive,
  Pan,
  PinCode,
  Uuid,
} from "./common";

describe("common scalars", () => {
  it("Uuid accepts a v4 uuid and rejects garbage", () => {
    expect(Uuid.safeParse("550e8400-e29b-41d4-a716-446655440000").success).toBe(true);
    expect(Uuid.safeParse("not-a-uuid").success).toBe(false);
    expect(Uuid.safeParse("").success).toBe(false);
  });

  it("Iso8601 accepts an offset datetime and rejects bare dates", () => {
    expect(Iso8601.safeParse("2026-05-09T00:00:00.000Z").success).toBe(true);
    expect(Iso8601.safeParse("2026-05-09").success).toBe(false);
    expect(Iso8601.safeParse("yesterday").success).toBe(false);
  });

  it("IsoDate accepts YYYY-MM-DD and rejects datetimes", () => {
    expect(IsoDate.safeParse("2026-05-09").success).toBe(true);
    expect(IsoDate.safeParse("2026-5-9").success).toBe(false);
    expect(IsoDate.safeParse("2026-05-09T00:00:00Z").success).toBe(false);
  });

  it("MoneyINR accepts zero, positive, and rejects negatives + > 1e12", () => {
    expect(MoneyINR.safeParse(0).success).toBe(true);
    expect(MoneyINR.safeParse(1500).success).toBe(true);
    expect(MoneyINR.safeParse(-1).success).toBe(false);
    expect(MoneyINR.safeParse(1e13).success).toBe(false);
  });

  it("MoneyINRPositive rejects zero", () => {
    expect(MoneyINRPositive.safeParse(0).success).toBe(false);
    expect(MoneyINRPositive.safeParse(0.01).success).toBe(true);
  });

  it("Pan validates Indian PAN format", () => {
    expect(Pan.safeParse("ABCDE1234F").success).toBe(true);
    expect(Pan.safeParse("abcde1234f").success).toBe(false);
    expect(Pan.safeParse("ABCDE12345").success).toBe(false);
  });

  it("Aadhaar validates 12-digit number", () => {
    expect(Aadhaar.safeParse("123412341234").success).toBe(true);
    expect(Aadhaar.safeParse("12341234").success).toBe(false);
    expect(Aadhaar.safeParse("1234 1234 1234").success).toBe(false);
  });

  it("Mobile validates 10-digit Indian mobile prefix 6-9", () => {
    expect(Mobile.safeParse("9876543210").success).toBe(true);
    expect(Mobile.safeParse("6111111111").success).toBe(true);
    expect(Mobile.safeParse("5111111111").success).toBe(false);
    expect(Mobile.safeParse("987654321").success).toBe(false);
  });

  it("Email accepts valid + rejects invalid", () => {
    expect(Email.safeParse("a@b.co").success).toBe(true);
    expect(Email.safeParse("not-an-email").success).toBe(false);
  });

  it("IpCidr accepts IPv4 with optional CIDR and rejects malformed", () => {
    expect(IpCidr.safeParse("10.0.0.0/8").success).toBe(true);
    expect(IpCidr.safeParse("192.168.1.1").success).toBe(true);
    expect(IpCidr.safeParse("999.0.0.1").success).toBe(false);
    expect(IpCidr.safeParse("::1").success).toBe(false);
  });

  it("Ifsc validates 4 letters + 0 + 6 alphanumerics", () => {
    expect(Ifsc.safeParse("HDFC0001234").success).toBe(true);
    expect(Ifsc.safeParse("HDFC1234567").success).toBe(false);
  });

  it("BankAccount accepts 9-20 digit string", () => {
    expect(BankAccount.safeParse("123456789").success).toBe(true);
    expect(BankAccount.safeParse("12345").success).toBe(false);
    expect(BankAccount.safeParse("12345678901234567890").success).toBe(true);
    expect(BankAccount.safeParse("123456789012345678901").success).toBe(false);
  });

  it("PinCode rejects leading zero and accepts 6 digits", () => {
    expect(PinCode.safeParse("110001").success).toBe(true);
    expect(PinCode.safeParse("011001").success).toBe(false);
    expect(PinCode.safeParse("11000").success).toBe(false);
  });

  it("IdempotencyKey accepts 1-80 char string", () => {
    expect(IdempotencyKey.safeParse("a").success).toBe(true);
    expect(IdempotencyKey.safeParse("").success).toBe(false);
    expect(IdempotencyKey.safeParse("x".repeat(81)).success).toBe(false);
  });
});
