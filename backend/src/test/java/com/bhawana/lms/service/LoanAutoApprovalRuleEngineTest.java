package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanAutoApprovalRuleEngineTest {

    @Test
    void missingPinnedVersionFailsLoudlyInsteadOfFallingBackToTheCatalog() {
        LoanAutoApprovalRuleEngine engine = new LoanAutoApprovalRuleEngine(
                mock(LoanApplicationDocumentChecklistRepository.class),
                mock(LoanProductLspMappingRepository.class),
                mock(BorrowerActiveLoanChecker.class)
        );
        LoanProduct product = mock(LoanProduct.class);
        when(product.getStatus()).thenReturn(LoanProductStatus.ACTIVE);
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(UUID.randomUUID());
        when(application.getLoanProduct()).thenReturn(product);
        when(application.getLoanProductVersion()).thenReturn(null);

        assertThatThrownBy(() -> engine.evaluate(application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pinned product version");
    }
}
