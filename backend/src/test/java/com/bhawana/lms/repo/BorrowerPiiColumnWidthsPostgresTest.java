package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F-15: the borrower table caps Aadhaar at 12 characters (UIDAI spec) and
 * IFSC at 11 characters (RBI spec). These tests describe the DB contract
 * directly via JdbcTemplate — the contract being tested is "the borrower
 * schema rejects over-width PII", and the public interface of the DB is
 * SQL.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BorrowerPiiColumnWidthsPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aadharNumberIsRejectedWhenLongerThanTwelveCharacters() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile, aadhar_number) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id,
                "Width Probe",
                uniquePan(),
                "9999999999",
                "9999999999999"
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aadharNumberRoundTripsAtTwelveDigits() {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile, aadhar_number) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id,
                "Happy Aadhaar",
                uniquePan(),
                "9999999999",
                "999999999999"
        );

        String stored = jdbcTemplate.queryForObject(
                "SELECT aadhar_number FROM borrower WHERE id = ?",
                String.class,
                id
        );
        assertThat(stored).isEqualTo("999999999999");
    }

    @Test
    void ifscCodeIsRejectedWhenLongerThanElevenCharacters() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile, ifsc_code) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id,
                "Width Probe",
                uniquePan(),
                "9999999999",
                "HDFC00012345"
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void ifscCodeRoundTripsAtElevenCharacters() {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile, ifsc_code) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id,
                "Happy IFSC",
                uniquePan(),
                "9999999999",
                "HDFC0001234"
        );

        String stored = jdbcTemplate.queryForObject(
                "SELECT ifsc_code FROM borrower WHERE id = ?",
                String.class,
                id
        );
        assertThat(stored).isEqualTo("HDFC0001234");
    }

    private static String uniquePan() {
        String hex = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        StringBuilder letters = new StringBuilder();
        for (int i = 0; letters.length() < 5 && i < hex.length(); i++) {
            char c = hex.charAt(i);
            letters.append(Character.isLetter(c) ? c : (char) ('A' + (c - '0')));
        }
        while (letters.length() < 5) {
            letters.append('A');
        }
        String digits = String.valueOf(Math.abs(hex.hashCode())).substring(0, 4);
        return letters.substring(0, 5) + digits + "Z";
    }
}
