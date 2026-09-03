import { type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { formatDate, formatINR } from "@/lib/format";
import type { BorrowerDetail } from "../../types";

export interface ProfileTabProps {
  detail: BorrowerDetail;
}

const GENDER_LABEL: Record<string, string> = {
  M: "Male",
  F: "Female",
  O: "Other",
};

const MARITAL_LABEL: Record<string, string> = {
  SINGLE: "Single",
  MARRIED: "Married",
  DIVORCED: "Divorced",
  WIDOWED: "Widowed",
};

const EMPLOYMENT_LABEL: Record<string, string> = {
  SALARIED: "Salaried",
  SELF_EMPLOYED: "Self-employed",
  BUSINESS: "Business",
  RETIRED: "Retired",
  STUDENT: "Student",
  UNEMPLOYED: "Unemployed",
};

function Section({
  title,
  children,
  className,
}: {
  title: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      data-slot="profile-section"
      className={cn("border-border bg-surface rounded-container border p-4", className)}
    >
      <h2 className="text-foreground mb-3 text-sm font-semibold tracking-tight">{title}</h2>
      {children}
    </section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-foreground-muted text-xs tracking-wide uppercase">{label}</dt>
      <dd className="text-foreground text-sm">{children}</dd>
    </div>
  );
}

/**
 * Profile tab — read-only display of the borrower master record. PII
 * fields render directly from the backend payload, which always masks
 * identity numbers (aadhaar, account, etc.). No reveal-on-demand flow
 * exists; see `docs/gap-fixes.md` § Gap #1.
 */
export function ProfileTab({ detail }: ProfileTabProps) {
  const { borrower, visibleLsps } = detail;

  return (
    <div data-slot="profile-tab" className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Section title="Identity">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Row label="PAN">
            <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
              {borrower.pan}
            </code>
          </Row>
          <Row label="Aadhaar">
            <code
              data-slot="aadhaar"
              className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs"
            >
              {borrower.aadhaar}
            </code>
          </Row>
          <Row label="Date of birth">{formatDate(borrower.dob)}</Row>
          <Row label="Gender">{GENDER_LABEL[borrower.gender] ?? borrower.gender}</Row>
          <Row label="Marital status">
            {MARITAL_LABEL[borrower.maritalStatus] ?? borrower.maritalStatus}
          </Row>
          <Row label="Father's name">{borrower.fathersName}</Row>
          <Row label="Spouse name">
            {borrower.spouseName ?? <span className="text-foreground-muted">—</span>}
          </Row>
        </dl>
      </Section>

      <Section title="Contact">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Row label="Mobile">{borrower.mobile}</Row>
          <Row label="Email">
            {borrower.email ?? <span className="text-foreground-muted">—</span>}
          </Row>
          <Row label="Residential address">
            <span className="block">{borrower.address.residential}</span>
            <span className="text-foreground-muted block text-xs">
              {borrower.address.city}, {borrower.address.state} {borrower.address.zip}
            </span>
          </Row>
        </dl>
      </Section>

      <Section title="Employment">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Row label="Type">
            {EMPLOYMENT_LABEL[borrower.employment.type] ?? borrower.employment.type}
          </Row>
          <Row label="Organization">
            {borrower.employment.organization ?? <span className="text-foreground-muted">—</span>}
          </Row>
          <Row label="Employee ID">
            {borrower.employment.employeeId ?? <span className="text-foreground-muted">—</span>}
          </Row>
          <Row label="Location">
            {borrower.employment.location ?? <span className="text-foreground-muted">—</span>}
          </Row>
          <Row label="Monthly income">
            <span className="tabular-nums">{formatINR(borrower.employment.monthlyIncome)}</span>
          </Row>
          <Row label="Annual income">
            <span className="tabular-nums">{formatINR(borrower.employment.annualIncome)}</span>
          </Row>
        </dl>
      </Section>

      <Section title="Banking">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Row label="Bank">{borrower.banking.bank}</Row>
          <Row label="Account holder">{borrower.banking.accountHolder}</Row>
          <Row label="Account number">
            <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
              {borrower.banking.accountNumber}
            </code>
          </Row>
          <Row label="IFSC">
            <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
              {borrower.banking.ifsc}
            </code>
          </Row>
        </dl>
      </Section>

      <Section title="References" className="lg:col-span-2">
        {borrower.references.length === 0 ? (
          <p className="text-foreground-muted text-sm">No references on file</p>
        ) : (
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
            {borrower.references.map((ref) => (
              <Row key={`${ref.name}-${ref.contact}`} label={ref.name}>
                <span className="text-foreground-muted">{ref.contact}</span>
              </Row>
            ))}
          </dl>
        )}
      </Section>

      <Section title="LSP relationships" className="lg:col-span-2">
        {visibleLsps.length === 0 ? (
          <p className="text-foreground-muted text-sm">No LSPs currently see this borrower</p>
        ) : (
          <ul data-slot="lsp-relationship-list" className="flex flex-col gap-2">
            {visibleLsps.map((lsp) => (
              <li
                key={lsp.id}
                className="border-border rounded-container flex flex-wrap items-center justify-between gap-2 border px-3 py-2"
              >
                <Badge variant="outline" className="border-border">
                  {lsp.name}
                </Badge>
                <span className="text-foreground-muted text-xs">
                  {lsp.firstSourcedAt
                    ? `Linked ${formatDate(lsp.firstSourcedAt)}`
                    : "Linked (legacy visibility)"}
                  {lsp.sourceChannel ? ` · ${lsp.sourceChannel}` : ""}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
