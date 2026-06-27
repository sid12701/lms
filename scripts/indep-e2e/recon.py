"""Inventory current backend state so we know what fixtures exist."""
from client import req, admin_token
import json

t = admin_token()


def show(label, path):
    r = req("GET", path, token=t)
    print(f"\n=== {label} ({path}) -> {r.status_code} ===")
    try:
        b = r.json()
    except Exception:
        print(r.text[:300]); return None
    if isinstance(b, list):
        print(f"count={len(b)}")
        for x in b[:15]:
            print("  ", json.dumps({k: x.get(k) for k in list(x)[:8]}, default=str)[:240])
        return b
    else:
        print(json.dumps(b, default=str)[:600])
        return b


lsps = show("LSPs", "/api/v1/internal/admin/lsps")
show("LSP options", "/api/v1/internal/admin/lsp-options")
prods = show("Products", "/api/v1/internal/admin/products")
clients = show("API clients", "/api/v1/internal/admin/api-clients")
show("Users", "/api/v1/internal/admin/users")
show("Metadata", "/api/v1/internal/admin/metadata")
