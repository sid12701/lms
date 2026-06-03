package com.bhawana.lms.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bhawana.lms.service.AuditExplorerQuery;
import com.bhawana.lms.service.AuditExplorerQuery.AuditStream;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gap #91 / #159 — each UNION branch must execute on H2 without column-shape drift.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditExplorerStreamProjectionParityTest {

    @Autowired
    private AuditExplorerRepository repository;

    @ParameterizedTest
    @EnumSource(AuditStream.class)
    void everyStreamBranchProducesAllUnifiedAuditEventRowColumnsOnH2(AuditStream stream) {
        var result = repository.search(new AuditExplorerQuery(
                Set.of(stream),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                1,
                false
        ));
        assertNotNull(result.items());
        if (!result.items().isEmpty()) {
            var row = result.items().getFirst();
            assertNotNull(row.stream());
            assertNotNull(row.nativeId());
            assertNotNull(row.occurredAt());
            assertNotNull(row.actorUsername());
        }
    }
}
