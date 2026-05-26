-- Gap #15: optional acknowledgement note (max 500 chars).
ALTER TABLE ops_alert ADD COLUMN IF NOT EXISTS acknowledgement_note VARCHAR(500);
