package com.bhawana.lms.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * B12: prove the full migration chain applies cleanly and passes Flyway validate on Postgres.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FlywaySchemaValidationPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private Flyway flyway;

    @Test
    void flywayValidatePassesAfterMigrate() {
        assertThatCode(flyway::validate).doesNotThrowAnyException();
    }

    @Test
    void allMigrationsAreApplied() {
        MigrationInfo[] pending = flyway.info().pending();
        assertThat(pending)
                .as("pending migrations: %s", describe(pending))
                .isEmpty();
    }

    private static String describe(MigrationInfo[] pending) {
        if (pending.length == 0) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (MigrationInfo info : pending) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(info.getVersion()).append(" ").append(info.getDescription());
        }
        return builder.toString();
    }
}
