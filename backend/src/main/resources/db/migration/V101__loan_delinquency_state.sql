CREATE TABLE IF NOT EXISTS loan_delinquency_state (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL UNIQUE REFERENCES loan_application(id) ON DELETE CASCADE,
    last_bucket VARCHAR(32) NOT NULL,
    last_max_days_past_due INTEGER NOT NULL,
    last_evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
