package com.bhawana.lms.tenant;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a {@code REQUIRES_NEW} transaction without changing the caller's tenant/admin
 * data-access scope. Use for LSP idempotency and onboarding writes that must stay on the tenant
 * datasource with row-level security enforced.
 */
@Component
public class ScopePreservingTransactionExecutor {

    private final TransactionTemplate requiresNewTemplate;

    public ScopePreservingTransactionExecutor(PlatformTransactionManager transactionManager) {
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
        return requiresNewTemplate.execute(status -> task.get());
    }
}
