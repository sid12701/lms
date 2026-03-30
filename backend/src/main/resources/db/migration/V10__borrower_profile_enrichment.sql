ALTER TABLE borrower
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN city VARCHAR(128),
    ADD COLUMN state VARCHAR(128),
    ADD COLUMN employment_type VARCHAR(64),
    ADD COLUMN monthly_income NUMERIC(19, 2);
