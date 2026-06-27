"""Independent E2E verification client — written from scratch (not Cursor's harness).

Hits the real backend on localhost:8080 with strict assertions. No secret
auto-rotation, no matrix mutation, no silent skips. Designed so every check is
explicit and a failure is loud.
"""
from __future__ import annotations

import json
import random
import string
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import requests

BASE = "http://localhost:8080"
ADMIN_USER = "ops.admin"
ADMIN_PASS = "ChangeMe123!"

S = requests.Session()


# ---- result accounting -------------------------------------------------------
PASS, FAIL, INFO = [], [], []


def check(cid: str, ok: bool, detail: str) -> bool:
    tag = "PASS" if ok else "FAIL"
    (PASS if ok else FAIL).append((cid, detail))
    print(f"[{tag}] {cid}: {detail}")
    return ok


def info(cid: str, detail: str) -> None:
    INFO.append((cid, detail))
    print(f"[INFO] {cid}: {detail}")


def summary() -> None:
    print("\n" + "=" * 70)
    print(f"PASS={len(PASS)}  FAIL={len(FAIL)}  INFO={len(INFO)}")
    if FAIL:
        print("\nFAILURES:")
        for cid, d in FAIL:
            print(f"  - {cid}: {d}")
    print("=" * 70)


# ---- http helpers ------------------------------------------------------------
def req(method: str, path: str, token: str | None = None, idem: str | None = None,
        expect: int | tuple[int, ...] | None = None, **kw) -> requests.Response:
    h = kw.pop("headers", {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    if idem:
        h["Idempotency-Key"] = idem
    url = path if path.startswith("http") else BASE + path
    r = S.request(method, url, headers=h, timeout=60, **kw)
    if expect is not None:
        exp = (expect,) if isinstance(expect, int) else expect
        if r.status_code not in exp:
            body = r.text[:500]
            print(f"   !! {method} {path} -> {r.status_code} (wanted {exp}) body={body}")
    return r


def login(user: str, pw: str) -> tuple[int, dict]:
    r = req("POST", "/api/v1/auth/login", json={"username": user, "password": pw})
    return r.status_code, (r.json() if r.content else {})


def admin_token() -> str:
    """Cache token to a file to avoid burning the 10/min login rate limit across scripts."""
    import os
    cache = ".admin_token_cache.json"
    try:
        c = json.load(open(cache))
        if c.get("exp", 0) - 120 > time.time():
            return c["token"]
    except Exception:
        pass
    code, body = login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        # fall back to a possibly-still-valid cached token before giving up
        try:
            return json.load(open(cache))["token"]
        except Exception:
            raise SystemExit(f"admin login failed {code}: {body}")
    tok = body["accessToken"]
    exp = jwt_claims(tok).get("exp", time.time() + 3000)
    json.dump({"token": tok, "exp": exp}, open(cache, "w"))
    return tok


# ---- random generators -------------------------------------------------------
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


def rid(prefix: str = "INDEP") -> str:
    return f"{prefix}-{uuid.uuid4().hex[:12].upper()}"


def jwt_claims(token: str) -> dict:
    import base64
    seg = token.split(".")[1]
    seg += "=" * (-len(seg) % 4)
    return json.loads(base64.urlsafe_b64decode(seg))


if __name__ == "__main__":
    t = admin_token()
    c = jwt_claims(t)
    print("admin token claims:", json.dumps({k: c[k] for k in c if k in ("sub", "roles", "tenant", "tv", "exp")}, default=str))
