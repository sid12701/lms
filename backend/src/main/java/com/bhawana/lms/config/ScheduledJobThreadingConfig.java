package com.bhawana.lms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated scheduler threads per job family, so one slow or stuck job can only starve its own
 * family.
 *
 * <p>With no {@code TaskScheduler} bean configured, Spring Boot runs every {@code @Scheduled}
 * method on one shared thread: a report request that blocks on storage or SMTP would delay
 * disbursement submission, status polling and reconciliation on the same process. Each family
 * gets its own bounded pool instead:
 *
 * <ul>
 *   <li>{@link #FINANCIAL_TASK_SCHEDULER} — disbursement submission, status checks and
 *   reconciliation. Two threads so a slow submission tick cannot hold up status recovery;
 *   overlapping runs of the <em>same</em> method are still impossible because fixed-delay
 *   tasks never run concurrently with themselves.
 *   <li>{@link #REPORTING_TASK_SCHEDULER} — report request processing, the job most likely to
 *   block on an external system.
 *   <li>{@link #MAINTENANCE_TASK_SCHEDULER} — alert evaluation, KPI snapshots, retention and
 *   partition lifecycle. Single thread on purpose: these are whole-book scans and serialising
 *   them keeps two scans from competing for the database at once.
 * </ul>
 *
 * <p>Fixed-delay scheduling provides the backlog behaviour for free — the next run is only
 * scheduled after the current one finishes, so a saturated pool queues at most one pending run
 * per method. On shutdown, in-flight work finishes within the termination budget while pending
 * periodic runs are cancelled, so a stopping instance stops claiming new work.
 */
@Configuration
public class ScheduledJobThreadingConfig {

    public static final String FINANCIAL_TASK_SCHEDULER = "financialTaskScheduler";
    public static final String REPORTING_TASK_SCHEDULER = "reportingTaskScheduler";
    public static final String MAINTENANCE_TASK_SCHEDULER = "maintenanceTaskScheduler";

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobThreadingConfig.class);
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    @Bean(FINANCIAL_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler financialTaskScheduler() {
        return buildScheduler(2, "lms-financial-job-");
    }

    @Bean(REPORTING_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler reportingTaskScheduler() {
        return buildScheduler(1, "lms-reporting-job-");
    }

    @Bean(MAINTENANCE_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler maintenanceTaskScheduler() {
        return buildScheduler(1, "lms-maintenance-job-");
    }

    private static ThreadPoolTaskScheduler buildScheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(throwable ->
                log.error("Scheduled job failed on {}", threadNamePrefix, throwable));
        return scheduler;
    }
}
