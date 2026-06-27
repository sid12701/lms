package com.bhawana.lms.tenant;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a {@code REQUIRES_NEW} transaction on the admin datasource. The admin
 * scope is active before the transaction acquires its JDBC connection and is restored
 * only after commit/rollback completes.
 */
@Component
public class AdminScopedTransactionExecutor {

    private final TransactionTemplate requiresNewTemplate;

    public AdminScopedTransactionExecutor(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;
    }

    public void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    public <T> T call(Supplier<T> task) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return requiresNewTemplate.execute(status -> task.get());
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }
}
