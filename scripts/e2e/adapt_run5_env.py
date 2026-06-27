#!/usr/bin/env python3
"""Adapt Newman run5 env export into edge-fixtures.json (quick path when fixtures.py is slow)."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
RUN5 = ROOT / ".e2e-runs" / "env-after-run5.json"
OUT = ROOT / ".e2e-runs" / "edge-fixtures.json"


def main() -> int:
    if not RUN5.exists():
        print(f"Missing {RUN5} — run Newman first.", file=sys.stderr)
        return 1
    env = {v["key"]: v.get("value") for v in json.loads(RUN5.read_text(encoding="utf-8"))["values"]}
    inst = json.loads(env.get("installments") or "[]")
    data = {
        "createdAt": "adapted-from-run5",
        "fixtures": {
            "F01_happy_lsp": {
                "lspId": env.get("lspId"),
                "productId": env.get("productId"),
                "clientId": env.get("lspApiClientId"),
                "clientSecret": env.get("lspApiClientSecret"),
                "applicationId_initialized": env.get("applicationId"),
                "borrowerId": env.get("borrowerId"),
            },
            "F10_disbursed_loan": {
                "applicationId": env.get("applicationId"),
                "loanAccountId": env.get("loanAccountId"),
                "installments": inst,
            },
            "F12_closed_loan": {"applicationId": env.get("applicationId"), "loanAccountId": env.get("loanAccountId")},
        },
        "note": "Partial fixtures from Newman run5 — run fixtures.py for full F02-F13 set",
    }
    OUT.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"Wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
