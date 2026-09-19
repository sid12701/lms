package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * H26 regression tests: financial, reporting and maintenance jobs run on separate bounded
 * scheduler pools so a blocked job can only starve its own family. The workers' scheduled
 * bodies are disabled under the test profile — what is proven here is the scheduler-family
 * isolation itself, and the architecture test pins which job targets which pool.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ScheduledJobThreadingIntegrationTest {

    @Autowired
    @Qualifier(ScheduledJobThreadingConfig.FINANCIAL_TASK_SCHEDULER)
    private ThreadPoolTaskScheduler financialTaskScheduler;

    @Autowired
    @Qualifier(ScheduledJobThreadingConfig.REPORTING_TASK_SCHEDULER)
    private ThreadPoolTaskScheduler reportingTaskScheduler;

    @Autowired
    private LoanDisbursementWorker loanDisbursementWorker;

    @Autowired
    private ReportRequestProcessingWorker reportRequestProcessingWorker;

    @AfterEach
    void tearDown() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void blockedReportingSchedulerDoesNotStarveFinancialWork() throws Exception {
        CountDownLatch reportingBusy = new CountDownLatch(1);
        CountDownLatch releaseReporting = new CountDownLatch(1);
        reportingTaskScheduler.execute(() -> {
            reportingBusy.countDown();
            await(releaseReporting);
        });
        assertTrue(reportingBusy.await(10, TimeUnit.SECONDS), "reporting thread never blocked");

        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            Future<?> financialRun = financialTaskScheduler.submit(() -> {
                loanDisbursementWorker.run();
                ranOn.set(Thread.currentThread().getName());
                return null;
            });
            financialRun.get(10, TimeUnit.SECONDS);
            assertTrue(ranOn.get().startsWith("lms-financial-job-"),
                    "financial work ran on " + ranOn.get());

            // Meanwhile the reporting family can only queue — it is not lost.
            Future<String> queuedReporting = reportingTaskScheduler.submit(() -> {
                reportRequestProcessingWorker.processPendingRequests();
                return Thread.currentThread().getName();
            });
            assertFalse(queuedReporting.isDone(), "reporting task ran while its thread was blocked");

            releaseReporting.countDown();
            assertTrue(queuedReporting.get(10, TimeUnit.SECONDS).startsWith("lms-reporting-job-"));
        } finally {
            releaseReporting.countDown();
        }
    }

    @Test
    void saturatedFinancialPoolQueuesRatherThanOverlappingOrDropping() throws Exception {
        CountDownLatch bothBusy = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            bothBusy.countDown();
            await(release);
        };
        financialTaskScheduler.execute(blocker);
        financialTaskScheduler.execute(blocker);
        assertTrue(bothBusy.await(10, TimeUnit.SECONDS), "financial pool did not saturate");

        try {
            AtomicBoolean thirdRan = new AtomicBoolean();
            Future<?> third = financialTaskScheduler.submit(() -> {
                thirdRan.set(true);
                return null;
            });
            assertFalse(third.isDone(), "third task ran while both financial threads were blocked");

            release.countDown();
            third.get(10, TimeUnit.SECONDS);
            assertTrue(thirdRan.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownFinishesClaimedWorkAndClaimsNothingNew() throws Exception {
        // A scheduler built straight from the production config: same pool, same shutdown policy.
        ThreadPoolTaskScheduler scheduler =
                new ScheduledJobThreadingConfig().maintenanceTaskScheduler();
        scheduler.initialize();
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch inFlight = new CountDownLatch(1);
            scheduler.scheduleWithFixedDelay(() -> {
                runs.incrementAndGet();
                inFlight.countDown();
                await(release);
            }, Duration.ofMillis(20));
            assertTrue(inFlight.await(10, TimeUnit.SECONDS), "job never started");

            // Shutdown begins while the job is mid-run: the in-flight run finishes, periodic
            // rescheduling stops, and nothing new is claimed afterwards.
            Future<?> shutdown = shutdownCaller.submit(scheduler::shutdown);
            assertFalse(shutdown.isDone(), "shutdown abandoned claimed work mid-run");

            release.countDown();
            shutdown.get(35, TimeUnit.SECONDS);
            assertEquals(1, runs.get(), "job kept running after shutdown");
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.execute(() -> { }));
        } finally {
            release.countDown();
            shutdownCaller.shutdownNow();
            scheduler.destroy();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting on latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
