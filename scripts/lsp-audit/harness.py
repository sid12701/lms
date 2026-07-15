"""LSP POV audit harness — provisions fixtures and runs the API test matrix.

Written for the 2026-07-08 LSP end-to-end audit. Talks to the live backend on
localhost:8080. Admin login is email-based. Produces a JSON results file.
"""
from __future__ import annotations

import base64
import json
import random
import string
import sys
import time
import uuid
from pathlib import Path
from typing import Any

import requests

BASE = "http://localhost:8080"
ADMIN_EMAIL = "siddhant@bhawanafinance.com"
ADMIN_PASS = "ChangeMe123!"
OUT_DIR = Path(__file__).resolve().parent
BLANK_PDF = Path(__file__).resolve().parents[2] / "blank.pdf"

S = requests.Session()
RESULTS: list[dict[str, Any]] = []


def rec(cid: str, scenario: str, expected: str, status: int, ok: bool, actual: str) -> None:
    RESULTS.append({
        "id": cid, "scenario": scenario, "expected": expected,
        "http": status, "pass": ok, "actual": actual,
    })
    tag = "PASS" if ok else "FAIL"
    print(f"[{tag}] {cid} HTTP {status} :: {scenario} -> {actual[:160]}")


def req(method: str, path: str, token: str | None = None, idem: str | None = None, **kw) -> requests.Response:
    h = kw.pop("headers", {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    if idem:
        h["Idempotency-Key"] = idem
    url = path if path.startswith("http") else BASE + path
    return S.request(method, url, headers=h, timeout=120, **kw)


def jclaims(token: str) -> dict:
    seg = token.split(".")[1]
    seg += "=" * (-len(seg) % 4)
    return json.loads(base64.urlsafe_b64decode(seg))


def body(r: requests.Response) -> Any:
    try:
        return r.json()
    except Exception:
        return r.text[:300]


def rpan() -> str:
    L = string.ascii_uppercase
    return "".join(random.choices(L, k=5)) + "".join(random.choices(string.digits, k=4)) + random.choice(L)


def raadhaar() -> str:
    return "".join(random.choices(string.digits, k=12))


def rmobile() -> str:
    return "9" + "".join(random.choices(string.digits, k=9))


def rifsc() -> str:
    return "".join(random.choices(string.ascii_uppercase, k=4)) + "0" + "".join(random.choices(string.ascii_uppercase + string.digits, k=6))


def racct() -> str:
    return "".join(random.choices(string.digits, k=12))


def rid(p: str = "AUD") -> str:
    return f"{p}-{uuid.uuid4().hex[:12].upper()}"


def admin_login() -> str:
    r = req("POST", "/api/v1/auth/login", json={"email": ADMIN_EMAIL, "password": ADMIN_PASS})
    if r.status_code != 200:
        raise SystemExit(f"admin login failed {r.status_code}: {r.text[:300]}")
    return r.json()["accessToken"]


def token_exchange(client_id: str, secret: str) -> requests.Response:
    return req("POST", "/api/v1/auth/token", json={"clientId": client_id, "clientSecret": secret})


def provision(admin: str) -> dict[str, Any]:
    """Create two LSPs, API clients, UI users, products, mappings. Returns fixture dict."""
    fx: dict[str, Any] = {}
    for tag in ("A", "B"):
        code = f"AUDIT-{tag}-{uuid.uuid4().hex[:6].upper()}"
        r = req("POST", "/api/v1/internal/admin/lsps", token=admin,
                json={"code": code, "name": f"Audit LSP {tag}", "status": "ACTIVE"})
        lsp = r.json()
        lsp_id = lsp["id"]
        # product
        pr = req("POST", "/api/v1/internal/admin/products", token=admin, json={
            "code": f"AUDP-{tag}-{uuid.uuid4().hex[:5].upper()}", "name": f"Audit Product {tag}",
            "minPrincipal": "10000.00", "maxPrincipal": "500000.00",
            "interestRate": "16.00", "processingFeeRate": "1.50",
            "minTenureMonths": 6, "maxTenureMonths": 36, "status": "ACTIVE",
        })
        product = pr.json()
        # map product->lsp
        req("POST", "/api/v1/internal/admin/product-lsp-mappings/entries", token=admin,
            json={"lspId": lsp_id, "productId": product["id"], "enabled": True})
        # api client
        cr = req("POST", "/api/v1/internal/admin/api-clients", token=admin,
                 json={"name": f"Audit Client {tag}", "description": "audit", "lspId": lsp_id, "status": "ACTIVE"})
        client = cr.json()
        fx[tag] = {
            "lspId": lsp_id, "lspCode": code, "productId": product["id"],
            "productCode": product["code"], "clientId": client["clientId"],
            "clientSecret": client["clientSecret"],
        }
    # UI users for LSP A (read + write)
    for role, uname in (("LSP_UI_READ", f"audit.read.{uuid.uuid4().hex[:6]}"),
                        ("LSP_UI_WRITE", f"audit.write.{uuid.uuid4().hex[:6]}")):
        ur = req("POST", "/api/v1/internal/admin/users", token=admin, json={
            "username": uname, "email": f"{uname}@audit.local", "password": "AuditPass#2026!",
            "status": "ACTIVE", "lspId": fx["A"]["lspId"], "roles": [role],
        })
        fx[f"user_{role}"] = {"username": uname, "email": f"{uname}@audit.local",
                              "password": "AuditPass#2026!", "resp": body(ur), "http": ur.status_code}
    return fx


def onboarding_payload(fx_tag: dict, **overrides) -> dict:
    name = random.choice(["Aarav", "Priya", "Rohan", "Neha"]) + " " + random.choice(["Sharma", "Iyer", "Singh"])
    p = {
        "lspId": fx_tag["lspId"], "productId": fx_tag["productId"], "lspLoanId": rid(),
        "fullName": name, "emailAddress": f"b{uuid.uuid4().hex[:6]}@ex.com",
        "mobileNumber": rmobile(), "dob": "1990-05-05", "panNumber": rpan(),
        "aadharNumber": raadhaar(), "loanAmount": 100000.00, "interestRate": 16.0,
        "loanTenure": 12, "monthlyIncome": 50000.00, "bankAccountNumber": racct(),
        "bankName": "HDFC Bank", "ifscCode": rifsc(), "accountHolderName": name,
        "addressLine1": "12 Audit Lane", "addressCity": "Mumbai",
        "addressState": "Maharashtra", "addressZipcode": "400001",
        "referencePersonName": "Ref Person", "referencePersonNumber": rmobile(),
    }
    p.update(overrides)
    return p


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "provision"
    admin = admin_login()
    if action == "provision":
        fx = provision(admin)
        json.dump(fx, open(OUT_DIR / "fixtures.json", "w"), indent=2)
        # verify token exchange for both
        for tag in ("A", "B"):
            r = token_exchange(fx[tag]["clientId"], fx[tag]["clientSecret"])
            claims = jclaims(r.json()["accessToken"]) if r.status_code == 200 else {}
            print(f"LSP {tag}: token HTTP {r.status_code} lspId-claim={claims.get('lspId')} roles={claims.get('roles')}")
        print("\nFixtures written to fixtures.json")
        print(json.dumps(fx, indent=2))
