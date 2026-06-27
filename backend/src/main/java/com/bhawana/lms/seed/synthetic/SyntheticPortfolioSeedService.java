package com.bhawana.lms.seed.synthetic;

import com.bhawana.lms.common.money.LoanFeeCalculator;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallmentStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.seed.synthetic.SyntheticPortfolioEmiCalculator.InstallmentRow;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyntheticPortfolioSeedService {

    private static final Logger LOG = LoggerFactory.getLogger(SyntheticPortfolioSeedService.class);
    private static final String ACTOR = "synthetic.seed";
    private static final int AUDIT_COPY_FLUSH_LINES = 50_000;

    private final JdbcTemplate jdbcTemplate;
    private final SyntheticPortfolioSeedProperties properties;
    private final ApiClientManagementService apiClientManagementService;
    private final Environment environment;

    private final AtomicLong borrowerSequence = new AtomicLong(1_000_000L);

    public SyntheticPortfolioSeedService(
            @Qualifier("adminDataSource") javax.sql.DataSource adminDataSource,
            SyntheticPortfolioSeedProperties properties,
            ApiClientManagementService apiClientManagementService,
            Environment environment
    ) {
        this.jdbcTemplate = new JdbcTemplate(adminDataSource);
        this.properties = properties;
        this.apiClientManagementService = apiClientManagementService;
        this.environment = environment;
    }

    public SeedResult seed() {
        guardNotProduction();
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Synthetic portfolio seeding requires app.seed.synthetic-portfolio.enabled=true");
        }

        SyntheticPortfolioSpec spec = SyntheticPortfolioSpec.from(properties);
        validateSpec(spec);

        long startedAt = System.currentTimeMillis();
        LOG.info(
                "Starting synthetic portfolio seed: applications={}, lsps={}, payments={}, auditRows={}",
                spec.totalApplications(),
                spec.lspCount(),
                spec.paymentTransactions(),
                spec.auditRows()
        );

        resetBusinessData();
        List<LspSeedContext> lspContexts = seedDimensions(spec);
        PortfolioCounters counters = seedPortfolio(spec, lspContexts);
        verifyCounts(spec, counters);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        LOG.info("Synthetic portfolio seed completed in {} ms", elapsedMs);
        return new SeedResult(elapsedMs, counters);
    }

    private void guardNotProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                throw new IllegalStateException("Synthetic portfolio seeding is not allowed in production profiles.");
            }
        }
    }

    private static void validateSpec(SyntheticPortfolioSpec spec) {
        int bucketTotal = spec.statusBuckets().stream().mapToInt(SyntheticPortfolioSpec.StatusBucket::count).sum();
        if (bucketTotal != spec.totalApplications()) {
            throw new IllegalStateException(
                    "Status bucket total " + bucketTotal + " does not match applications " + spec.totalApplications());
        }
        if (spec.totalApplications() <= 0) {
            throw new IllegalStateException("Application count must be positive.");
        }
    }

    private void resetBusinessData() {
        LOG.info("Resetting business data for synthetic seed");
        String bootstrap = properties.getBootstrapUsername().toLowerCase();
        jdbcTemplate.update(
                "DELETE FROM app_user_role WHERE user_id IN (SELECT id FROM app_user WHERE lower(username) <> ?)",
                bootstrap
        );
        jdbcTemplate.update("DELETE FROM app_user WHERE lower(username) <> ?", bootstrap);
        jdbcTemplate.execute(
                "TRUNCATE TABLE report_request, borrower, loan_product, lsp RESTART IDENTITY CASCADE"
        );
    }

    private List<LspSeedContext> seedDimensions(SyntheticPortfolioSpec spec) {
        List<LspSeedContext> contexts = new ArrayList<>(spec.lspCount());
        Instant now = Instant.now();
        for (int index = 0; index < spec.lspCount(); index++) {
            UUID lspId = UUID.randomUUID();
            UUID productId = UUID.randomUUID();
            String code = "SYN-LSP-" + String.format("%02d", index + 1);
            jdbcTemplate.update(
                    """
                    INSERT INTO lsp (id, code, name, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    lspId,
                    code,
                    "Synthetic LSP " + (index + 1),
                    LspStatus.ACTIVE.name(),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
            jdbcTemplate.update(
                    """
                    INSERT INTO loan_product (
                        id, code, name, min_principal, max_principal, interest_rate, processing_fee_rate,
                        min_tenure_months, max_tenure_months, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    productId,
                    code + "-PROD",
                    "Synthetic Product " + (index + 1),
                    new BigDecimal("10000.00"),
                    new BigDecimal("600000.00"),
                    SyntheticPortfolioSpec.DEFAULT_INTEREST_RATE,
                    SyntheticPortfolioSpec.DEFAULT_PROCESSING_FEE_RATE,
                    6,
                    36,
                    LoanProductStatus.ACTIVE.name(),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
            jdbcTemplate.update(
                    """
                    INSERT INTO loan_product_lsp_mapping (id, loan_product_id, lsp_id, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, TRUE, ?, ?)
                    """,
                    UUID.randomUUID(),
                    productId,
                    lspId,
                    Timestamp.from(now),
                    Timestamp.from(now)
            );

            ApiClientManagementService.CreatedApiClient apiClient = TenantScopedExecution.callAsAdmin(() ->
                    apiClientManagementService.createClient(
                            "synthetic-client-" + code,
                            "Synthetic load-test client",
                            lspId,
                            ApiClientStatus.ACTIVE,
                            ACTOR,
                            "127.0.0.1"
                    )
            );

            contexts.add(new LspSeedContext(
                    index,
                    lspId,
                    productId,
                    code,
                    apiClient.client().getClientId(),
                    apiClient.rawSecret()
            ));
            LOG.info("Seeded LSP {} ({}) apiClient={}", code, lspId, apiClient.client().getClientId());
        }
        return contexts;
    }

    private PortfolioCounters seedPortfolio(SyntheticPortfolioSpec spec, List<LspSeedContext> lspContexts) {
        PortfolioCounters counters = new PortfolioCounters();
        int[] appsPerLsp = spec.applicationsPerLsp();
        List<SyntheticPortfolioAuditWriter.IntakeAuditRow> intakeAuditRows = new ArrayList<>();
        List<SyntheticPortfolioAuditWriter.TransitionAuditRow> transitionAuditRows = new ArrayList<>();
        List<SyntheticPortfolioAuditWriter.ApplicationAuditRow> applicationAuditRows = new ArrayList<>();

        int auditIntakeTarget = Math.max(0, spec.totalApplications());
        int auditTransitionTarget = Math.max(0, (int) (spec.auditRows() * 0.35));
        int auditApplicationEventTarget = Math.max(0, spec.auditRows() - auditIntakeTarget - auditTransitionTarget);
        int applicationAuditRowsPerApp = Math.max(
                1,
                (int) Math.ceil(auditApplicationEventTarget / (double) Math.max(1, spec.totalApplications()))
        );

        long globalAppIndex = 0L;
        for (LspSeedContext lsp : lspContexts) {
            int lspApplications = appsPerLsp[lsp.index()];
            int[] bucketCounts = spec.splitProportionally(
                    lspApplications,
                    spec.statusBuckets().stream().mapToInt(SyntheticPortfolioSpec.StatusBucket::count).toArray()
            );
            int lspLocalIndex = 0;
            for (int bucketIndex = 0; bucketIndex < spec.statusBuckets().size(); bucketIndex++) {
                SyntheticPortfolioSpec.StatusBucket bucket = spec.statusBuckets().get(bucketIndex);
                int bucketCount = bucketCounts[bucketIndex];
                for (int offset = 0; offset < bucketCount; offset += spec.batchSize()) {
                    int batchCount = Math.min(spec.batchSize(), bucketCount - offset);
                    seedBatch(
                            spec,
                            lsp,
                            bucket,
                            batchCount,
                            lspLocalIndex + offset,
                            globalAppIndex,
                            counters,
                            intakeAuditRows,
                            transitionAuditRows,
                            auditIntakeTarget,
                            auditTransitionTarget,
                            auditApplicationEventTarget,
                            applicationAuditRowsPerApp,
                            applicationAuditRows
                    );
                    flushAuditBuffers(intakeAuditRows, transitionAuditRows, applicationAuditRows, false);
                }
                lspLocalIndex += bucketCount;
            }
            globalAppIndex += lspApplications;
        }

        flushAuditBuffers(intakeAuditRows, transitionAuditRows, applicationAuditRows, true);
        return counters;
    }

    private void seedBatch(
            SyntheticPortfolioSpec spec,
            LspSeedContext lsp,
            SyntheticPortfolioSpec.StatusBucket bucket,
            int batchCount,
            int lspLocalStart,
            long globalAppStart,
            PortfolioCounters counters,
            List<SyntheticPortfolioAuditWriter.IntakeAuditRow> intakeAuditRows,
            List<SyntheticPortfolioAuditWriter.TransitionAuditRow> transitionAuditRows,
            int auditIntakeTarget,
            int auditTransitionTarget,
            int auditApplicationEventTarget,
            int applicationAuditRowsPerApp,
            List<SyntheticPortfolioAuditWriter.ApplicationAuditRow> applicationAuditRows
    ) {
        Instant now = Instant.now();
        List<UUID> borrowerIds = new ArrayList<>(batchCount);
        List<UUID> applicationIds = new ArrayList<>(batchCount);
        List<Instant> createdAts = new ArrayList<>(batchCount);

        for (int index = 0; index < batchCount; index++) {
            borrowerIds.add(UUID.randomUUID());
            applicationIds.add(UUID.randomUUID());
            createdAts.add(randomCreatedAt(now, globalAppStart + lspLocalStart + index));
        }

        insertBorrowers(batchCount, borrowerIds, createdAts);
        insertBorrowerLspAccess(batchCount, borrowerIds, lsp.lspId(), createdAts);
        insertApplications(
                spec,
                batchCount,
                applicationIds,
                borrowerIds,
                lsp,
                bucket.applicationStatus(),
                lspLocalStart,
                createdAts
        );
        counters.applications += batchCount;
        counters.borrowers += batchCount;

        appendIntakeAudit(intakeAuditRows, applicationIds, createdAts, auditIntakeTarget, counters);
        appendTransitionAudit(transitionAuditRows, applicationIds, bucket.applicationStatus(), createdAts, auditTransitionTarget, counters);
        appendApplicationAudit(
                applicationAuditRows,
                applicationIds,
                bucket.applicationStatus(),
                createdAts,
                auditApplicationEventTarget,
                applicationAuditRowsPerApp,
                counters
        );

        if (!bucket.hasLoanAccount()) {
            return;
        }

        List<UUID> accountIds = new ArrayList<>(batchCount);
        for (int index = 0; index < batchCount; index++) {
            accountIds.add(UUID.randomUUID());
        }
        insertLoanAccounts(
                spec,
                batchCount,
                accountIds,
                applicationIds,
                borrowerIds,
                lsp,
                bucket,
                createdAts
        );
        counters.accounts += batchCount;

        List<UUID> installmentIds = insertInstallments(spec, batchCount, accountIds, createdAts, bucket);
        counters.installments += (long) batchCount * spec.tenureMonths();
        insertPaymentsForBatch(spec, batchCount, accountIds, installmentIds, bucket, createdAts, counters);
    }

    private void insertBorrowers(int count, List<UUID> borrowerIds, List<Instant> createdAts) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO borrower (id, full_name, pan, mobile, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        long seq = borrowerSequence.incrementAndGet();
                        UUID id = borrowerIds.get(index);
                        Instant createdAt = createdAts.get(index);
                        ps.setObject(1, id);
                        ps.setString(2, "Synthetic Borrower " + seq);
                        ps.setString(3, syntheticPan(seq));
                        ps.setString(4, syntheticMobile(seq));
                        ps.setString(5, "syn" + seq + "@example.invalid");
                        ps.setTimestamp(6, Timestamp.from(createdAt));
                        ps.setTimestamp(7, Timestamp.from(createdAt));
                    }

                    @Override
                    public int getBatchSize() {
                        return count;
                    }
                }
        );
    }

    private void insertBorrowerLspAccess(int count, List<UUID> borrowerIds, UUID lspId, List<Instant> createdAts) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO borrower_lsp_access (borrower_id, lsp_id, created_at) VALUES (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ps.setObject(1, borrowerIds.get(index));
                        ps.setObject(2, lspId);
                        ps.setTimestamp(3, Timestamp.from(createdAts.get(index)));
                    }

                    @Override
                    public int getBatchSize() {
                        return count;
                    }
                }
        );
    }

    private void insertApplications(
            SyntheticPortfolioSpec spec,
            int count,
            List<UUID> applicationIds,
            List<UUID> borrowerIds,
            LspSeedContext lsp,
            LoanApplicationStatus status,
            int lspLocalStart,
            List<Instant> createdAts
    ) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_application (
                    id, borrower_id, lsp_id, loan_product_id, external_loan_id, source_channel,
                    requested_amount, tenure_months, status, created_at, updated_at, entity_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ps.setObject(1, applicationIds.get(index));
                        ps.setObject(2, borrowerIds.get(index));
                        ps.setObject(3, lsp.lspId());
                        ps.setObject(4, lsp.productId());
                        ps.setString(5, lsp.code() + "-APP-" + (lspLocalStart + index + 1));
                        ps.setString(6, "API");
                        ps.setBigDecimal(7, SyntheticPortfolioSpec.DEFAULT_PRINCIPAL);
                        ps.setInt(8, spec.tenureMonths());
                        ps.setString(9, status.name());
                        Instant createdAt = createdAts.get(index);
                        ps.setTimestamp(10, Timestamp.from(createdAt));
                        ps.setTimestamp(11, Timestamp.from(createdAt));
                    }

                    @Override
                    public int getBatchSize() {
                        return count;
                    }
                }
        );
    }

    private void insertLoanAccounts(
            SyntheticPortfolioSpec spec,
            int count,
            List<UUID> accountIds,
            List<UUID> applicationIds,
            List<UUID> borrowerIds,
            LspSeedContext lsp,
            SyntheticPortfolioSpec.StatusBucket bucket,
            List<Instant> createdAts
    ) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_account (
                    id, loan_application_id, borrower_id, lsp_id, loan_product_id, account_number,
                    principal_amount, tenure_months, status, approved_at, disbursed_at, processing_fee_amount,
                    created_at, updated_at, entity_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Instant approvedAt = createdAts.get(index).plus(2, ChronoUnit.DAYS);
                        Instant disbursedAt = bucket.accountStatus() == LoanAccountStatus.PENDING_DISBURSEMENT
                                ? null
                                : approvedAt.plus(1, ChronoUnit.DAYS);
                        ps.setObject(1, accountIds.get(index));
                        ps.setObject(2, applicationIds.get(index));
                        ps.setObject(3, borrowerIds.get(index));
                        ps.setObject(4, lsp.lspId());
                        ps.setObject(5, lsp.productId());
                        ps.setString(6, lsp.code() + "-ACCT-" + accountIds.get(index));
                        ps.setBigDecimal(7, SyntheticPortfolioSpec.DEFAULT_PRINCIPAL);
                        ps.setInt(8, spec.tenureMonths());
                        ps.setString(9, bucket.accountStatus().name());
                        ps.setTimestamp(10, Timestamp.from(approvedAt));
                        if (disbursedAt == null) {
                            ps.setTimestamp(11, null);
                        } else {
                            ps.setTimestamp(11, Timestamp.from(disbursedAt));
                        }
                        ps.setBigDecimal(
                                12,
                                disbursedAt == null
                                        ? null
                                        : LoanFeeCalculator.computeProcessingFee(
                                                SyntheticPortfolioSpec.DEFAULT_PRINCIPAL,
                                                SyntheticPortfolioSpec.DEFAULT_PROCESSING_FEE_RATE
                                        )
                        );
                        ps.setTimestamp(13, Timestamp.from(approvedAt));
                        ps.setTimestamp(14, Timestamp.from(approvedAt));
                    }

                    @Override
                    public int getBatchSize() {
                        return count;
                    }
                }
        );
    }

    private List<UUID> insertInstallments(
            SyntheticPortfolioSpec spec,
            int accountCount,
            List<UUID> accountIds,
            List<Instant> createdAts,
            SyntheticPortfolioSpec.StatusBucket bucket
    ) {
        List<UUID> firstInstallmentIds = new ArrayList<>(accountCount);
        List<InstallmentInsert> rows = new ArrayList<>(accountCount * spec.tenureMonths());
        for (int accountIndex = 0; accountIndex < accountCount; accountIndex++) {
            Instant approvedAt = createdAts.get(accountIndex).plus(2, ChronoUnit.DAYS);
            List<InstallmentRow> schedule = SyntheticPortfolioEmiCalculator.buildSchedule(
                    SyntheticPortfolioSpec.DEFAULT_PRINCIPAL,
                    SyntheticPortfolioSpec.DEFAULT_INTEREST_RATE,
                    spec.tenureMonths(),
                    approvedAt
            );
            int paidThrough = paidInstallmentCount(bucket.applicationStatus(), spec.tenureMonths());
            for (InstallmentRow installment : schedule) {
                UUID installmentId = UUID.randomUUID();
                if (installment.installmentNumber() == 1) {
                    firstInstallmentIds.add(installmentId);
                }
                boolean paid = installment.installmentNumber() <= paidThrough;
                BigDecimal paidPrincipal = paid ? installment.principalDue() : BigDecimal.ZERO.setScale(2);
                BigDecimal paidInterest = paid ? installment.interestDue() : BigDecimal.ZERO.setScale(2);
                BigDecimal paidAmount = paid ? installment.installmentAmount() : BigDecimal.ZERO.setScale(2);
                BigDecimal outstanding = paid ? BigDecimal.ZERO.setScale(2) : installment.installmentAmount();
                String status = paid
                        ? LoanRepaymentScheduleInstallmentStatus.PAID.name()
                        : LoanRepaymentScheduleInstallmentStatus.PENDING.name();
                rows.add(new InstallmentInsert(
                        installmentId,
                        accountIds.get(accountIndex),
                        installment,
                        status,
                        paidPrincipal,
                        paidInterest,
                        paidAmount,
                        outstanding,
                        approvedAt
                ));
            }
        }

        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_repayment_schedule_installment (
                    id, loan_account_id, installment_number, due_date, opening_principal, principal_due,
                    interest_due, installment_amount, closing_principal, status, paid_principal, paid_interest,
                    paid_amount, outstanding_amount, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        InstallmentInsert row = rows.get(index);
                        ps.setObject(1, row.installmentId());
                        ps.setObject(2, row.accountId());
                        ps.setInt(3, row.installment().installmentNumber());
                        ps.setObject(4, row.installment().dueDate());
                        ps.setBigDecimal(5, row.installment().openingPrincipal());
                        ps.setBigDecimal(6, row.installment().principalDue());
                        ps.setBigDecimal(7, row.installment().interestDue());
                        ps.setBigDecimal(8, row.installment().installmentAmount());
                        ps.setBigDecimal(9, row.installment().closingPrincipal());
                        ps.setString(10, row.status());
                        ps.setBigDecimal(11, row.paidPrincipal());
                        ps.setBigDecimal(12, row.paidInterest());
                        ps.setBigDecimal(13, row.paidAmount());
                        ps.setBigDecimal(14, row.outstanding());
                        ps.setTimestamp(15, Timestamp.from(row.approvedAt()));
                        ps.setTimestamp(16, Timestamp.from(row.approvedAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                }
        );
        return firstInstallmentIds;
    }

    private void insertPaymentsForBatch(
            SyntheticPortfolioSpec spec,
            int accountCount,
            List<UUID> accountIds,
            List<UUID> firstInstallmentIds,
            SyntheticPortfolioSpec.StatusBucket bucket,
            List<Instant> createdAts,
            PortfolioCounters counters
    ) {
        if (bucket.applicationStatus() != LoanApplicationStatus.UNDER_REPAYMENT || spec.paymentTransactions() == 0) {
            return;
        }
        if (counters.payments >= spec.paymentTransactions()) {
            return;
        }
        int paymentsPerAccount = Math.max(1, spec.paymentTransactions() / Math.max(1, spec.activeUnderRepayment()));
        List<PaymentInsert> payments = new ArrayList<>(accountCount * paymentsPerAccount);
        for (int accountIndex = 0; accountIndex < accountCount; accountIndex++) {
            if (counters.payments >= spec.paymentTransactions()) {
                break;
            }
            Instant paymentInstant = createdAts.get(accountIndex).plus(30, ChronoUnit.DAYS);
            for (int paymentIndex = 0; paymentIndex < paymentsPerAccount; paymentIndex++) {
                if (counters.payments >= spec.paymentTransactions()) {
                    break;
                }
                UUID paymentId = UUID.randomUUID();
                BigDecimal amount = new BigDecimal("5000.00");
                payments.add(new PaymentInsert(
                        paymentId,
                        accountIds.get(accountIndex),
                        firstInstallmentIds.get(accountIndex),
                        amount,
                        paymentInstant.plus(paymentIndex, ChronoUnit.DAYS)
                ));
                counters.payments++;
            }
        }
        if (payments.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO loan_payment_transaction (
                    id, loan_account_id, repayment_installment_id, actor_username, amount, payment_date,
                    reference, idempotency_key, channel, status, allocated_amount, unallocated_amount, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        PaymentInsert row = payments.get(index);
                        ps.setObject(1, row.paymentId());
                        ps.setObject(2, row.accountId());
                        ps.setObject(3, row.installmentId());
                        ps.setString(4, ACTOR);
                        ps.setBigDecimal(5, row.amount());
                        ps.setObject(6, LocalDate.ofInstant(row.paidAt(), java.time.ZoneOffset.UTC));
                        ps.setString(7, "SYN-PAY-" + row.paymentId());
                        ps.setString(8, row.paymentId().toString());
                        ps.setString(9, LoanPaymentChannel.UPI.name());
                        ps.setString(10, LoanPaymentStatus.RECEIVED.name());
                        ps.setBigDecimal(11, row.amount());
                        ps.setTimestamp(12, Timestamp.from(row.paidAt()));
                        ps.setTimestamp(13, Timestamp.from(row.paidAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return payments.size();
                    }
                }
        );
    }

    private static int paidInstallmentCount(LoanApplicationStatus status, int tenureMonths) {
        return switch (status) {
            case CLOSED, FORECLOSED -> tenureMonths;
            case UNDER_REPAYMENT -> Math.max(1, tenureMonths / 2);
            default -> 0;
        };
    }

    private void appendIntakeAudit(
            List<SyntheticPortfolioAuditWriter.IntakeAuditRow> rows,
            List<UUID> applicationIds,
            List<Instant> createdAts,
            int target,
            PortfolioCounters counters
    ) {
        for (int index = 0; index < applicationIds.size(); index++) {
            if (counters.intakeAudit >= target) {
                return;
            }
            rows.add(new SyntheticPortfolioAuditWriter.IntakeAuditRow(
                    UUID.randomUUID(),
                    applicationIds.get(index),
                    ACTOR,
                    "syn-seed",
                    "{\"source\":\"synthetic\"}",
                    createdAts.get(index)
            ));
            counters.intakeAudit++;
        }
    }

    private void appendTransitionAudit(
            List<SyntheticPortfolioAuditWriter.TransitionAuditRow> rows,
            List<UUID> applicationIds,
            LoanApplicationStatus status,
            List<Instant> createdAts,
            int target,
            PortfolioCounters counters
    ) {
        for (int index = 0; index < applicationIds.size(); index++) {
            for (int duplicate = 0; duplicate < 4 && counters.transitionAudit < target; duplicate++) {
                rows.add(new SyntheticPortfolioAuditWriter.TransitionAuditRow(
                        UUID.randomUUID(),
                        applicationIds.get(index),
                        "INITIALIZED",
                        status.name(),
                        ACTOR,
                        "Synthetic status transition",
                        createdAts.get(index).plus(duplicate + 1L, ChronoUnit.HOURS)
                ));
                counters.transitionAudit++;
            }
            if (counters.transitionAudit >= target) {
                return;
            }
        }
    }

    private void appendApplicationAudit(
            List<SyntheticPortfolioAuditWriter.ApplicationAuditRow> rows,
            List<UUID> applicationIds,
            LoanApplicationStatus status,
            List<Instant> createdAts,
            int target,
            int rowsPerApplication,
            PortfolioCounters counters
    ) {
        for (int index = 0; index < applicationIds.size(); index++) {
            for (int duplicate = 0; duplicate < rowsPerApplication && counters.applicationAudit < target; duplicate++) {
                rows.add(new SyntheticPortfolioAuditWriter.ApplicationAuditRow(
                        UUID.randomUUID(),
                        applicationIds.get(index),
                        "STATUS_TRANSITION",
                        ACTOR,
                        "INITIALIZED",
                        status.name(),
                        "Synthetic application audit",
                        createdAts.get(index).plus(duplicate + 2L, ChronoUnit.HOURS)
                ));
                counters.applicationAudit++;
            }
            if (counters.applicationAudit >= target) {
                return;
            }
        }
    }

    private void flushAuditBuffers(
            List<SyntheticPortfolioAuditWriter.IntakeAuditRow> intakeRows,
            List<SyntheticPortfolioAuditWriter.TransitionAuditRow> transitionRows,
            List<SyntheticPortfolioAuditWriter.ApplicationAuditRow> applicationAuditRows,
            boolean force
    ) {
        if ((force || intakeRows.size() >= AUDIT_COPY_FLUSH_LINES) && !intakeRows.isEmpty()) {
            SyntheticPortfolioAuditWriter.flushIntakeAudit(jdbcTemplate, intakeRows);
            intakeRows.clear();
        }
        if ((force || transitionRows.size() >= AUDIT_COPY_FLUSH_LINES) && !transitionRows.isEmpty()) {
            SyntheticPortfolioAuditWriter.flushTransitionAudit(jdbcTemplate, transitionRows);
            transitionRows.clear();
        }
        if ((force || applicationAuditRows.size() >= AUDIT_COPY_FLUSH_LINES) && !applicationAuditRows.isEmpty()) {
            SyntheticPortfolioAuditWriter.flushApplicationAudit(jdbcTemplate, applicationAuditRows);
            applicationAuditRows.clear();
        }
    }

    private void verifyCounts(SyntheticPortfolioSpec spec, PortfolioCounters counters) {
        Map<String, Long> actual = new LinkedHashMap<>();
        actual.put("borrower", count("borrower"));
        actual.put("loan_application", count("loan_application"));
        actual.put("loan_account", count("loan_account"));
        actual.put("loan_payment_transaction", count("loan_payment_transaction"));
        actual.put("loan_application_intake_audit", count("loan_application_intake_audit"));
        actual.put("loan_application_status_transition", count("loan_application_status_transition"));
        actual.put("loan_application_audit_event", count("loan_application_audit_event"));
        long auditTotal = actual.get("loan_application_intake_audit")
                + actual.get("loan_application_status_transition")
                + actual.get("loan_application_audit_event");
        actual.put("audit_total", auditTotal);

        LOG.info("Synthetic seed verification: {}", actual);

        long tolerance = Math.max(5, Math.round(spec.totalApplications() * 0.02));
        assertWithin("loan_application", spec.totalApplications(), actual.get("loan_application"), tolerance);
        assertWithin("borrower", spec.totalApplications(), actual.get("borrower"), tolerance);
        assertWithin("loan_account", spec.accountsWithSchedules(), actual.get("loan_account"), tolerance);
        if (spec.paymentTransactions() > 0) {
            assertWithin(
                    "loan_payment_transaction",
                    spec.paymentTransactions(),
                    actual.get("loan_payment_transaction"),
                    Math.max(tolerance, spec.paymentTransactions() / 10)
            );
        }
        if (spec.auditRows() > 0) {
            assertWithin("audit_total", spec.auditRows(), auditTotal, Math.max(tolerance, spec.auditRows() / 10));
        }
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private static void assertWithin(String label, long expected, long actual, long tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new IllegalStateException(
                    label + " count " + actual + " outside tolerance of expected " + expected + " (+/- " + tolerance + ")");
        }
    }

    private static Instant randomCreatedAt(Instant now, long sequence) {
        long daysAgo = 270 - (sequence % 270);
        return now.minus(daysAgo, ChronoUnit.DAYS);
    }

    private static String syntheticPan(long sequence) {
        return String.format("ABC%07d", sequence);
    }

    private static String syntheticMobile(long sequence) {
        return "9" + String.format("%09d", sequence % 1_000_000_000L);
    }

    record LspSeedContext(int index, UUID lspId, UUID productId, String code, String apiClientId, String apiClientSecret) {
    }

    record SeedResult(long elapsedMs, PortfolioCounters counters) {
    }

    static final class PortfolioCounters {
        long applications;
        long borrowers;
        long accounts;
        long installments;
        long payments;
        long intakeAudit;
        long transitionAudit;
        long applicationAudit;
    }

    private record InstallmentInsert(
            UUID installmentId,
            UUID accountId,
            InstallmentRow installment,
            String status,
            BigDecimal paidPrincipal,
            BigDecimal paidInterest,
            BigDecimal paidAmount,
            BigDecimal outstanding,
            Instant approvedAt
    ) {
    }

    private record PaymentInsert(
            UUID paymentId,
            UUID accountId,
            UUID installmentId,
            BigDecimal amount,
            Instant paidAt
    ) {
    }
}
