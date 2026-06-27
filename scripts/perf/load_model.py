"""Load model for 100,000 loans/day — API volume estimates."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class LoadTier:
    name: str
    loans_per_sec: float
    api_rps: float
    description: str


# Per complete loan lifecycle (origination → closure), realistic API touchpoints:
#   LSP: token(1) + create(1) + docs(8) + status polls(6) + schedule read(2) + payments(12) = 30
#   Admin/Ops: approval poll reads(6) + disburse(2) + payment posts alt(0 if LSP pays) + audit(2) = 10
#   Background: webhooks(~4 events), report rows(~0.01 per loan daily)
#   Dashboard/ops reads amortized: ~5 reads/loan over lifecycle
# Conservative blended: ~45 API calls per loan over full life; ~18 calls in first 24h (origination day)

CALLS_PER_LOAN_ORIGINATION_DAY = 18
CALLS_PER_LOAN_LIFECYCLE = 45
CALLS_PER_REPAYMENT_EVENT = 2  # payment POST + status GET
AVG_INSTALLMENTS = 12
DASHBOARD_READS_PER_OPS_USER_PER_HOUR = 120
REPORT_REQUESTS_PER_DAY = 50  # admin + LSP MIS


def loans_per_second(loans_per_day: int) -> float:
    return loans_per_day / 86_400


def api_rps(loans_per_sec: float, calls_per_loan: float) -> float:
    return loans_per_sec * calls_per_loan


def build_load_model(
    loans_per_day: int = 100_000,
    peak_factor: float = 3.5,
    burst_factor: float = 10.0,
    ops_users: int = 25,
) -> dict:
    avg_lps = loans_per_second(loans_per_day)
    peak_lps = avg_lps * peak_factor
    burst_lps = avg_lps * burst_factor

    # Steady-state portfolio repayments: assume 30-day rolling active book
    active_book = loans_per_day * 30
    repayments_per_day = active_book * (AVG_INSTALLMENTS / (AVG_INSTALLMENTS * 30))  # ~1 installment/loan/month
    repayment_rps = (repayments_per_day * CALLS_PER_REPAYMENT_EVENT) / 86_400

    origination_rps_avg = api_rps(avg_lps, CALLS_PER_LOAN_ORIGINATION_DAY)
    origination_rps_peak = api_rps(peak_lps, CALLS_PER_LOAN_ORIGINATION_DAY)
    origination_rps_burst = api_rps(burst_lps, CALLS_PER_LOAN_ORIGINATION_DAY)

    dashboard_rps = (ops_users * DASHBOARD_READS_PER_OPS_USER_PER_HOUR) / 3600
    report_rps = (REPORT_REQUESTS_PER_DAY * 8) / 86_400  # ~8 API calls per report request (create+poll+download)

    tiers = [
        LoadTier("average", avg_lps, origination_rps_avg + repayment_rps + dashboard_rps + report_rps,
                 "24h sustained mean including repayments and ops reads"),
        LoadTier("peak", peak_lps, origination_rps_peak + repayment_rps * peak_factor + dashboard_rps + report_rps,
                 f"{peak_factor}x origination during business hours (10h window)"),
        LoadTier("burst", burst_lps, origination_rps_burst + repayment_rps,
                 f"{burst_factor}x origination spike for 15 minutes"),
        LoadTier("sustained_peak", peak_lps, origination_rps_peak + repayment_rps * 2 + dashboard_rps * 2,
                 "2-hour sustained peak with elevated repayments"),
    ]

    return {
        "target_loans_per_day": loans_per_day,
        "assumptions": {
            "calls_per_loan_origination_day": CALLS_PER_LOAN_ORIGINATION_DAY,
            "calls_per_loan_full_lifecycle": CALLS_PER_LOAN_LIFECYCLE,
            "calls_per_repayment": CALLS_PER_REPAYMENT_EVENT,
            "avg_installments": AVG_INSTALLMENTS,
            "ops_concurrent_users": ops_users,
            "report_requests_per_day": REPORT_REQUESTS_PER_DAY,
            "peak_factor": peak_factor,
            "burst_factor": burst_factor,
        },
        "tiers": {t.name: {"loans_per_sec": round(t.loans_per_sec, 4), "api_rps": round(t.api_rps, 2), "description": t.description} for t in tiers},
        "daily_api_volume_estimate": int(loans_per_day * CALLS_PER_LOAN_ORIGINATION_DAY + repayments_per_day * CALLS_PER_REPAYMENT_EVENT + REPORT_REQUESTS_PER_DAY * 8),
        "hikari_pool_note": "Local profile uses maximum-pool-size=5; production target likely 20-50 per instance",
    }


if __name__ == "__main__":
    import json
    print(json.dumps(build_load_model(), indent=2))
