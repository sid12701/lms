-- F-15: tighten over-sized borrower PII columns to their canonical widths.
-- Aadhaar is exactly 12 digits (UIDAI) and IFSC is exactly 11 characters (RBI);
-- the wider VARCHAR(16) / VARCHAR(32) declared in V33 left silent slack that
-- the LSP API @Pattern validation already disallows. This aligns the DB
-- schema with that contract so direct SQL writers, restored backups, or
-- future Flyway data fixes cannot store over-width values.
--
-- F-01 (PII encryption) may later replace these columns with encrypted
-- payloads; that migration will re-shape the columns as needed.

DO $$
DECLARE
    over_aadhar BIGINT;
    over_ifsc BIGINT;
BEGIN
    SELECT COUNT(*) INTO over_aadhar FROM borrower
        WHERE aadhar_number IS NOT NULL AND length(aadhar_number) > 12;
    SELECT COUNT(*) INTO over_ifsc FROM borrower
        WHERE ifsc_code IS NOT NULL AND length(ifsc_code) > 11;
    IF over_aadhar > 0 OR over_ifsc > 0 THEN
        RAISE EXCEPTION
            'F-15 width tightening blocked: % aadhar rows > 12 chars, % ifsc rows > 11 chars. Clean data before re-running.',
            over_aadhar, over_ifsc;
    END IF;
END $$;

ALTER TABLE borrower ALTER COLUMN aadhar_number TYPE VARCHAR(12);
ALTER TABLE borrower ALTER COLUMN ifsc_code TYPE VARCHAR(11);
