-- M07: durable worker lease for long multi-transaction jobs. A transaction-scoped advisory
-- lock spanning a whole-book sweep would recreate one long writing transaction; a lease row
-- gives singleton ownership with an expiry for crash reclaim and a fencing sequence so a
-- stale owner detects that it lost the job mid-run.
CREATE TABLE worker_lease (
    job_name     VARCHAR(64)  PRIMARY KEY,
    owner        VARCHAR(160) NOT NULL,
    fencing_seq  BIGINT       NOT NULL,
    claimed_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);
