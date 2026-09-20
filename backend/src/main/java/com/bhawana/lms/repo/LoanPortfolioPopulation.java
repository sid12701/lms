package com.bhawana.lms.repo;

/**
 * The loan populations portfolio queries count over, as reusable predicate fragments. Each metric
 * picks the population that matches its meaning rather than one shared denominator:
 *
 * <ul>
 *   <li><b>Funded servicing</b> — money has left the LSP's disbursal account and the schedule is
 *       still live. Only this population carries outstanding debt, overdue amounts, delinquency
 *       buckets and dashboard priority rows. The account pair ({@code DISBURSED} status with a
 *       non-null {@code disbursed_at}, written together by the single disbursement-outcome path)
 *       proves funding; the application status keeps settled and cancelled loans out.</li>
 *   <li><b>Historical disbursed</b> — every loan that was ever funded, including loans since
 *       closed or foreclosed. This is the denominator for lifetime disbursed volume.</li>
 * </ul>
 *
 * Closed loans ({@code CLOSED}/{@code FORECLOSED}) are historical disbursed but not funded
 * servicing. Unfunded accounts ({@code PENDING_DISBURSEMENT}, {@code DISBURSEMENT_*},
 * {@code INVALID}) belong to neither: their generated schedules are not debt, and the pipeline is
 * reported separately through application status counts.
 *
 * Each fragment ends with a line break so it can sit between two text blocks. SQL fragments
 * expect {@code app} for {@code loan_application} and {@code acc} for {@code loan_account}; JPQL
 * fragments expect {@code application} and {@code account}.
 */
public final class LoanPortfolioPopulation {

    public static final String FUNDED_SERVICING_SQL = """
            app.status in ('DISBURSED', 'UNDER_REPAYMENT')
              and acc.status = 'DISBURSED'
              and acc.disbursed_at is not null
            """;

    public static final String FUNDED_SERVICING_JPQL = """
            application.status in (com.bhawana.lms.domain.LoanApplicationStatus.DISBURSED,
                                   com.bhawana.lms.domain.LoanApplicationStatus.UNDER_REPAYMENT)
              and account.status = com.bhawana.lms.domain.LoanAccountStatus.DISBURSED
              and account.disbursedAt is not null
            """;

    public static final String HISTORICAL_DISBURSED_SQL = """
            acc.disbursed_at is not null
            """;

    private LoanPortfolioPopulation() {
    }
}
