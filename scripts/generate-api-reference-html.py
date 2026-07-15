#!/usr/bin/env python3
"""Regenerate docs/api-reference-by-role.html from openapi/openapi.json."""

from __future__ import annotations

import html
import json
import re
from datetime import date
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parent.parent
OPENAPI = REPO / "openapi" / "openapi.json"
OUT = REPO / "docs" / "api-reference-by-role.html"

HTTP_METHODS = ("get", "post", "put", "patch", "delete")

# Method-level role overrides (path prefix + method + suffix match on operationId or path tail).
SYSTEM_ADMIN_ONLY_SUFFIXES = (
    "/foreclosure-quotes",
    "/status-transitions",
    "/manual-status",
    "/disbursement-requests",
    "/disbursement-requests/mock-outcome",
    "/disbursement-requests/status-check",
    "/payments",
    "/foreclosure-quotes/{quoteId}/execute",
)
SYSTEM_ADMIN_ONLY_EXACT = {
    ("get", "/api/v1/internal/alerts/rules"),
    ("patch", "/api/v1/internal/admin/borrowers/{borrowerId}/bank-details"),
}

LSP_API_CLIENT_ONLY = {
    ("post", "/api/v1/lsp/loan-applications"),
    ("put", "/api/v1/lsp/loan-applications/{applicationId}/repayment-schedule"),
    ("post", "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check"),
    ("post", "/api/v1/lsp/loans/{loanId}/payments"),
    ("get", "/api/v1/lsp/borrowers/{borrowerId}/bank-details"),
    ("patch", "/api/v1/lsp/borrowers/{borrowerId}/bank-details"),
}

LSP_WRITE_ONLY = {
    ("post", "/api/v1/lsp/loan-applications/{applicationId}/documents"),
    ("post", "/api/v1/lsp/loan-applications/{applicationId}/documents/upload"),
    ("post", "/api/v1/lsp/loan-applications/{applicationId}/invalidate"),
    ("post", "/api/v1/lsp/loans/{loanId}/foreclosure-quote"),
    ("post", "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute"),
}

IDEMPOTENCY_REQUIRED = {
    ("post", "/api/v1/lsp/loans/{loanId}/payments"),
    ("post", "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute"),
}

STRICT_JSON_WRITE_PATHS = {
    "/api/v1/lsp/loan-applications",
    "/api/v1/lsp/loan-applications/{applicationId}/documents",
    "/api/v1/lsp/loan-applications/{applicationId}/invalidate",
    "/api/v1/lsp/loan-applications/{applicationId}/repayment-schedule",
    "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
    "/api/v1/lsp/loans/{loanId}/payments",
    "/api/v1/lsp/loans/{loanId}/foreclosure-quote",
    "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
    "/api/v1/lsp/borrowers/{borrowerId}/bank-details",
}


def esc(text: str) -> str:
    return html.escape(text, quote=True)


def esc_json(obj: Any) -> str:
    return esc(json.dumps(obj, indent=2))


def titleize(operation_id: str) -> str:
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", operation_id)
    return spaced.replace("_", " ").strip().title()


def resolve_role(method: str, path: str) -> str:
    key = (method.lower(), path)
    if path == "/actuator/health":
        return "Public"
    if path.startswith("/api/v1/auth/"):
        if path.endswith("/login") or path.endswith("/token"):
            return "Public"
        if path.endswith("/refresh") or path.endswith("/logout"):
            return "Public endpoint using refresh cookie when present"
        if path.endswith("/password"):
            return "Authenticated user with password-change-required session"
        return "Public"
    if key in SYSTEM_ADMIN_ONLY_EXACT:
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/ops/loan-applications") and any(
        path.endswith(suffix) for suffix in SYSTEM_ADMIN_ONLY_SUFFIXES
    ):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/api-clients"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/users"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/audit-events"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/webhook-outbox"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/metadata"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/home"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/ops/auth-audit"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/reports"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/lsps"):
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/lsps/") and "allowlist" in path:
        return "SYSTEM_ADMIN"
    if path.startswith("/api/v1/internal/admin/products"):
        return "SYSTEM_ADMIN or PRODUCT_ADMIN"
    if path.startswith("/api/v1/internal/admin/product-lsp-mappings"):
        return "SYSTEM_ADMIN or PRODUCT_ADMIN"
    if path.startswith("/api/v1/internal/admin/lsp-options"):
        return "SYSTEM_ADMIN, OPS_USER, or PRODUCT_ADMIN"
    if path.startswith("/api/v1/internal/admin/borrowers"):
        return "SYSTEM_ADMIN or OPS_USER"
    if path.startswith("/api/v1/internal/ops/"):
        return "SYSTEM_ADMIN or OPS_USER"
    if path.startswith("/api/v1/internal/alerts"):
        if path.endswith("/rules"):
            return "SYSTEM_ADMIN"
        return "SYSTEM_ADMIN or OPS_USER"
    if path.startswith("/api/v1/internal/system/"):
        return "SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN, LSP_UI_READ, or LSP_UI_WRITE"
    if path.startswith("/api/v1/lsp/"):
        if key in LSP_API_CLIENT_ONLY:
            return "LSP_API_CLIENT scoped to own LSP"
        if key in LSP_WRITE_ONLY:
            return "LSP_API_CLIENT or LSP_UI_WRITE scoped to own LSP"
        return "LSP_API_CLIENT, LSP_UI_READ, or LSP_UI_WRITE scoped to own LSP"
    return "Authenticated"


def idempotency_note(method: str, path: str, parameters: list[dict]) -> str | None:
    has_header = any(p.get("in") == "header" and p.get("name") == "Idempotency-Key" for p in parameters)
    if not has_header:
        return None
    key = (method.lower(), path)
    if key in IDEMPOTENCY_REQUIRED:
        return "Required UUID v4 header for safe retries."
    if path.startswith("/api/v1/lsp/") and method.lower() in ("post", "put", "patch"):
        return "Optional UUID v4 header; when supplied, replays the stored response for the same request fingerprint."
    if path.startswith("/api/v1/internal/") and method.lower() in ("post", "put", "patch"):
        return "Optional UUID v4 header; when supplied, replays the stored admin response for the same request fingerprint."
    return "Optional UUID v4 header."


def response_masks_pii(path: str) -> bool:
    if path.endswith("/bank-details"):
        return False
    if path == "/api/v1/internal/admin/borrowers":
        return False
    return (
        path.startswith("/api/v1/lsp/loan-applications")
        or path.startswith("/api/v1/lsp/loans/")
        or path == "/api/v1/internal/admin/borrowers/{borrowerId}"
        or path.startswith("/api/v1/internal/alerts")
    )


def should_null_signing_secret(path: str, method: str) -> bool:
    return method.lower() == "get" and (
        path.endswith("/webhook-config")
        or path == "/api/v1/internal/admin/lsps"
        or path == "/api/v1/internal/admin/lsps/{lspId}"
    )


def sample_value(name: str, schema: dict, schemas: dict, *, mask_pii: bool, null_signing_secret: bool) -> Any:
    if "$ref" in schema:
        ref = schema["$ref"].split("/")[-1]
        if ref in schemas:
            return sample_schema(schemas[ref], schemas, mask_pii=mask_pii, null_signing_secret=null_signing_secret)
        return {}
    if "allOf" in schema:
        merged: dict[str, Any] = {}
        for part in schema["allOf"]:
            val = sample_value(name, part, schemas, mask_pii=mask_pii, null_signing_secret=null_signing_secret)
            if isinstance(val, dict):
                merged.update(val)
        return merged
    if schema.get("type") == "array":
        item = sample_value(name, schema.get("items", {}), schemas, mask_pii=mask_pii, null_signing_secret=null_signing_secret)
        return [item]
    if schema.get("type") == "object" or "properties" in schema:
        return sample_schema(schema, schemas, mask_pii=mask_pii, null_signing_secret=null_signing_secret)
    fmt = schema.get("format")
    typ = schema.get("type")
    enum = schema.get("enum")
    if enum:
        return enum[0]
    lower = name.lower()
    if null_signing_secret and lower == "signingsecret":
        return None
    if mask_pii and "bankaccountnumber" in lower.replace("_", ""):
        return "XXXXXXXX9012"
    if "bankaccountnumbermasked" in lower.replace("_", ""):
        return "XXXXXXXX9012"
    if mask_pii and ("aadhar" in lower or "aadhaar" in lower) and "mask" not in lower:
        return "XXXXXXXX9012"
    if "aadhar" in lower or "aadhaar" in lower:
        return "XXXXXXXX9012" if "mask" in lower else "123456789012"
    if fmt == "uuid" or lower.endswith("id"):
        return "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    if fmt == "date-time":
        return "2026-07-05T11:00:00Z"
    if fmt == "date":
        return "2026-07-05"
    if fmt == "int32" or typ == "integer":
        return 12 if "tenure" in lower or "month" in lower else 1
    if typ == "number":
        if "rate" in lower:
            return 12.5
        if "amount" in lower or "principal" in lower or "income" in lower:
            return 150000
        return 100000
    if typ == "boolean":
        return True
    if "email" in lower:
        return "user@example.com"
    if "mobile" in lower or "phone" in lower:
        return "9876543210"
    if "pan" in lower:
        return "ABCDE1234F"
    if "ifsc" in lower:
        return "HDFC0001234"
    if "password" in lower or "secret" in lower:
        return "ChangeMe123!"
    if "code" in lower and "zip" not in lower:
        return "CODE001"
    if "name" in lower:
        return "Sample Name"
    if "status" in lower:
        return "ACTIVE"
    if typ == "string":
        return "string"
    return None


def sample_schema(schema: dict, schemas: dict, *, mask_pii: bool, null_signing_secret: bool) -> Any:
    props = schema.get("properties", {})
    if not props:
        return {}
    out: dict[str, Any] = {}
    for key, sub in props.items():
        out[key] = sample_value(key, sub, schemas, mask_pii=mask_pii, null_signing_secret=null_signing_secret)
    return out


def resolve_schema(spec: dict | None, schemas: dict) -> dict | None:
    if not spec:
        return None
    if "$ref" in spec:
        ref = spec["$ref"].split("/")[-1]
        return schemas.get(ref)
    return spec


def required_fields(schema: dict | None, schemas: dict) -> list[str]:
    schema = resolve_schema(schema, schemas)
    if not schema:
        return []
    return list(schema.get("required", []))


def render_parameters(params: list[dict], note: str | None) -> str:
    if not params and not note:
        return '<p class="muted">No path/query/header parameters documented.</p>'
    rows = []
    for p in params:
        schema = p.get("schema", {})
        schema_type = schema.get("type", "")
        if schema.get("format"):
            schema_type = f"{schema_type}:{schema.get('format')}" if schema_type else schema.get("format")
        if not schema_type and "$ref" in schema:
            schema_type = schema["$ref"].split("/")[-1]
        notes = []
        if p.get("name") == "Idempotency-Key" and note:
            notes.append(note)
        if p.get("name") == "paginationDetails":
            notes.append("ON includes X-Total-Count; OFF omits it.")
        if p.get("name") in ("offset", "limit"):
            notes.append("Used with paginated list endpoints.")
        rows.append(
            "<tr>"
            f"<td>{esc(p.get('name', ''))}</td>"
            f"<td>{esc(p.get('in', ''))}</td>"
            f"<td>{'yes' if p.get('required') else 'no'}</td>"
            f"<td>{esc(schema_type)}</td>"
            f"<td>{esc('; '.join(notes))}</td>"
            "</tr>"
        )
    table = (
        "<table><thead><tr><th>Name</th><th>In</th><th>Required</th><th>Schema</th><th>Notes</th></tr></thead>"
        f"<tbody>{''.join(rows)}</tbody></table>"
    )
    return table


def render_request_body(op: dict, schemas: dict, path: str, method: str) -> str:
    body = op.get("requestBody")
    if not body:
        return '<p class="muted">No JSON body. Use path/query parameters or multipart form parts where listed.</p>'
    content = body.get("content", {})
    blocks = []
    for media, media_spec in content.items():
        schema = media_spec.get("schema")
        resolved = resolve_schema(schema, schemas)
        req = required_fields(schema, schemas)
        example = sample_schema(resolved, schemas, mask_pii=False, null_signing_secret=False) if resolved else {}
        strict = (
            method.lower() in ("post", "put", "patch")
            and path.startswith("/api/v1/lsp/")
            and any(path == p or path.startswith(p.replace("{", "").split("}")[0]) for p in STRICT_JSON_WRITE_PATHS)
        )
        # simpler strict check
        strict = method.lower() in ("post", "put", "patch") and path.startswith("/api/v1/lsp/") and (
            path in STRICT_JSON_WRITE_PATHS
            or path.startswith("/api/v1/lsp/loan-applications/")
            or path.startswith("/api/v1/lsp/loans/")
            or path.startswith("/api/v1/lsp/borrowers/")
        )
        note = ""
        if req:
            note = f'<p class="muted">Required fields: {esc(", ".join(req))}</p>'
        strict_note = (
            '<p class="muted">Strict JSON: unknown properties are rejected with 400 VALIDATION_FAILED.</p>'
            if strict and media == "application/json"
            else ""
        )
        blocks.append(
            '<div class="body-block">'
            f'<div class="mini"><span>{esc(media)}</span>'
            f'<span>{"required" if body.get("required", True) else "optional"}</span>'
            f'<span>{esc((resolved or {}).get("title") or (schema or {}).get("$ref", "").split("/")[-1] or "body")}</span></div>'
            f"{note}{strict_note}"
            f"<pre><code>{esc_json(example)}</code></pre></div>"
        )
    return "".join(blocks) if blocks else '<p class="muted">No JSON body. Use path/query parameters or multipart form parts where listed.</p>'


def render_responses(op: dict, schemas: dict, path: str, method: str) -> str:
    responses = op.get("responses", {})
    rows = []
    sample = None
    for status, spec in sorted(responses.items(), key=lambda x: x[0]):
        content = spec.get("content", {})
        content_types = ", ".join(content.keys()) if content else "-"
        rows.append(f"<tr><td>{esc(status)}</td><td>{esc(spec.get('description', ''))}</td><td>{esc(content_types)}</td></tr>")
        if sample is None and status.startswith("2") and content:
            for media_spec in content.values():
                schema = media_spec.get("schema")
                resolved = resolve_schema(schema, schemas)
                if resolved:
                    mask_pii = response_masks_pii(path)
                    null_secret = should_null_signing_secret(path, method)
                    if schema and schema.get("type") == "array":
                        item_schema = resolve_schema(schema.get("items"), schemas)
                        sample = [sample_schema(item_schema, schemas, mask_pii=mask_pii, null_signing_secret=null_secret)] if item_schema else []
                    else:
                        sample = sample_schema(resolved, schemas, mask_pii=mask_pii, null_signing_secret=null_secret)
                break
    table = (
        "<table><thead><tr><th>Status</th><th>Description</th><th>Content</th></tr></thead>"
        f"<tbody>{''.join(rows)}</tbody></table>"
    )
    if sample is not None:
        table += f"<pre><code>{esc_json(sample)}</code></pre>"
    return table


def endpoint_description(path: str, method: str, op: dict) -> str:
    desc = op.get("description") or op.get("summary")
    if desc:
        return esc(desc)
    extras = []
    if response_masks_pii(path):
        extras.append("Bank account numbers are masked as XXXXXXXX&lt;last4&gt; in responses from this surface.")
    if "webhook-config" in path or (path.startswith("/api/v1/internal/admin/lsps") and method.lower() == "get"):
        extras.append("signingSecret is write-only on reads (null); secretSet indicates whether a secret is stored.")
    if not extras:
        return "No controller description; schema and route are source-backed."
    return " ".join(extras)


def render_endpoint(method: str, path: str, op: dict, schemas: dict) -> str:
    operation_id = op.get("operationId", "")
    title = titleize(operation_id) if operation_id else f"{method.upper()} {path}"
    role = resolve_role(method, path)
    params = op.get("parameters", [])
    note = idempotency_note(method, path, params)
    search = f"{method} {path} {operation_id} {role}".lower()
    return (
        f'<article class="endpoint" data-search="{esc(search)}">\n'
        f'    <div class="endpoint-head"><span class="method {method}">{method.upper()}</span><code>{esc(path)}</code></div>\n'
        f"    <h3>{esc(title)}</h3>\n"
        f"    <p>{endpoint_description(path, method, op)}</p>\n"
        f'    <div class="chips"><span>{esc(role)}</span><span>{esc(operation_id)}</span><span>openapi/openapi.json</span></div>\n'
        f"    <details><summary>Parameters</summary>{render_parameters(params, note)}</details>\n"
        f"    <details open><summary>Expected request body</summary>{render_request_body(op, schemas, path, method)}</details>\n"
        f"    <details><summary>Success response</summary>{render_responses(op, schemas, path, method)}</details>\n"
        f"  </article>"
    )


def health_endpoint() -> str:
    return (
        '<article class="endpoint" data-search="get /actuator/health health public">\n'
        '    <div class="endpoint-head"><span class="method get">GET</span><code>/actuator/health</code></div>\n'
        "    <h3>Health</h3>\n"
        "    <p>Unauthenticated health probe used by local/E2E startup checks.</p>\n"
        '    <div class="chips"><span>Public</span><span>health</span><span>SecurityConfig.java / actuator</span></div>\n'
        '    <details><summary>Parameters</summary><p class="muted">No path/query/header parameters documented.</p></details>\n'
        '    <details open><summary>Expected request body</summary><p class="muted">No JSON body. Use path/query parameters or multipart form parts where listed.</p></details>\n'
        '    <details><summary>Success response</summary><table><thead><tr><th>Status</th><th>Description</th><th>Content</th></tr></thead><tbody><tr><td>200</td><td>Health status</td><td>-</td></tr></tbody></table></details>\n'
        "  </article>"
    )


def static_sections(today: str) -> str:
    error_example = {
        "timestamp": "2026-07-05T11:00:00Z",
        "status": 422,
        "code": "REPAYMENT_SCHEDULE_INVALID",
        "error": "REPAYMENT_SCHEDULE_INVALID",
        "message": "Provided repayment schedule does not reconcile with the approved loan terms.",
        "path": "/api/v1/lsp/loan-applications/{applicationId}/repayment-schedule",
        "correlationId": "corr-123",
        "errorCode": "REPAYMENT_SCHEDULE_INVALID",
        "errorReason": "REPAYMENT_SCHEDULE_INVALID",
        "errorSource": "Provided repayment schedule does not reconcile with the approved loan terms.",
        "violations": [
            {
                "field": "installments[0].openingPrincipal",
                "message": "Opening principal on the first installment must equal the approved principal amount.",
            }
        ],
        "errors": [
            {
                "errorCode": "REPAYMENT_SCHEDULE_INVALID",
                "errorReason": "REPAYMENT_SCHEDULE_INVALID",
                "errorSource": "installments[0].openingPrincipal: Opening principal on the first installment must equal the approved principal amount.",
                "field": "installments[0].openingPrincipal",
                "message": "Opening principal on the first installment must equal the approved principal amount.",
            }
        ],
    }
    create_example = {
        "lspId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "productId": "2c73954b-1ad3-4b90-8a95-9630c0e38f51",
        "lspLoanId": "LSP-LOAN-0001",
        "fullName": "Asha Sharma",
        "emailAddress": "asha@example.com",
        "mobileNumber": "9876543210",
        "dob": "1992-04-15",
        "aadharNumber": "123456789012",
        "panNumber": "ABCDE1234F",
        "loanAmount": 150000,
        "interestRate": 12.5,
        "loanTenure": 12,
        "addressLine1": "42 MG Road",
        "addressCity": "Pune",
        "addressState": "MH",
        "addressZipcode": "411001",
        "monthlyIncome": 55000,
        "bankAccountNumber": "123456789012",
        "bankName": "HDFC Bank",
        "ifscCode": "HDFC0001234",
        "accountHolderName": "Asha Sharma",
        "referencePersonName": "Ravi Sharma",
        "referencePersonNumber": "9876543211",
    }
    schedule_example = {
        "mode": "LSP_PROVIDED",
        "installments": [
            {
                "installmentNumber": 1,
                "dueDate": "2026-07-19",
                "openingPrincipal": 150000,
                "principalDue": 12000,
                "interestDue": 1500,
                "installmentAmount": 13500,
                "closingPrincipal": 138000,
            }
        ],
    }
    disbursement_example = {
        "disbursalAmount": 147000,
        "bankAccountNumber": "123456789012",
        "ifscCode": "HDFC0001234",
        "accountHolderName": "Asha Sharma",
    }
    payment_example = {
        "targetInstallmentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "amount": 13500,
        "postedAt": "2026-07-05",
        "channel": "NEFT",
        "reference": "UTR123456789",
    }
    return f"""
  <section class="section"><h2>Authentication and Error Envelope</h2><div class="two"><div><p>Use <code>POST /api/v1/auth/login</code> for human users and <code>POST /api/v1/auth/token</code> for LSP API clients. Protected APIs require <code>Authorization: Bearer &lt;accessToken&gt;</code>. Success responses return bare resource bodies (no wrapper envelope) and always include <code>X-Correlation-Id</code>.</p><p>Refresh token is held in the server-issued refresh cookie. CORS exposes <code>X-Correlation-Id</code>, pagination headers (<code>X-Offset</code>, <code>X-Limit</code>, optional <code>X-Total-Count</code>), and content disposition.</p><p>Paginated list endpoints accept <code>offset</code>, <code>limit</code>, and <code>paginationDetails=ON|OFF</code>. <code>X-Offset</code> and <code>X-Limit</code> are always returned; <code>X-Total-Count</code> is emitted only when <code>paginationDetails=ON</code>.</p><p>LSP JSON write endpoints reject unknown properties (strict JSON). Admin and LSP mutating endpoints accept an optional <code>Idempotency-Key</code> UUID v4 header; LSP payment and foreclosure-execute require it.</p><p>PII: bank account numbers are masked as <code>XXXXXXXX&lt;last4&gt;</code> on LSP loan-application reads, borrower-admin detail, ops-alert details, and webhook payloads. Full bank details are returned only from the dedicated bank-details endpoints and payment rail.</p></div><div><pre><code>{esc_json(error_example)}</code></pre><p class="muted">Prefer <code>code</code> and <code>violations[]</code>; duplicate legacy fields (<code>error</code>, <code>errorCode</code>, <code>errors[]</code>) remain for backward compatibility.</p></div></div></section>
  <section class="section"><h2>Loan Approval Rules</h2><p class="callout">Auto-approval fires only when all intake-required documents have just become complete and the application is in <code>INITIALIZED</code> or <code>AWAITING_APPROVAL</code>. If every rule passes, the system moves <code>INITIALIZED -&gt; AWAITING_APPROVAL -&gt; APPROVED_PENDING_DISBURSAL</code>, creates the loan account, and generates the repayment schedule. If rules fail while already awaiting approval, it moves to <code>REJECTED</code> with <code>rejection_reason_json.failedRules</code>.</p><table><thead><tr><th>Rule code</th><th>Requirement</th></tr></thead><tbody><tr><td><code>PRODUCT_INACTIVE</code></td><td>Selected loan product must exist and be ACTIVE.</td></tr><tr><td><code>LSP_INACTIVE</code></td><td>Authenticated/requested LSP must be ACTIVE.</td></tr><tr><td><code>LSP_PRODUCT_MAPPING_INACTIVE</code></td><td>The product must be mapped to the LSP and the mapping must be enabled.</td></tr><tr><td><code>LOAN_AMOUNT_OUT_OF_RANGE</code></td><td>Requested amount must be within product minPrincipal and maxPrincipal.</td></tr><tr><td><code>LOAN_TENURE_OUT_OF_RANGE</code></td><td>Requested tenure must be within product minTenureMonths and maxTenureMonths.</td></tr><tr><td><code>BORROWER_REQUIRED_FIELDS_MISSING</code></td><td>Borrower must have name, PAN, mobile, Aadhaar, address, city, state, zip, income, reference name, and reference number.</td></tr><tr><td><code>REQUIRED_DOCUMENTS_NOT_UPLOADED</code></td><td>All intake-required checklist documents must be complete.</td></tr><tr><td><code>BORROWER_HAS_OPEN_LOAN</code></td><td>Borrower cannot already have another open loan across LSPs.</td></tr></tbody></table><h3>Required documents</h3><p><code>PAN_CARD</code>, <code>AADHAAR_FILE</code>, <code>ADDRESS_PROOF</code>, <code>INCOME_PROOF</code>, <code>BANK_STATEMENT</code>, <code>SELFIE_PHOTOGRAPH</code>, <code>KFS</code>, <code>LOAN_AGREEMENT</code></p><p>Upload constraints: PDF/JPEG/PNG globally, 10 MiB max; PAN and Aadhaar are PDF/JPEG only and 5 MiB max; loan agreement is PDF only. File name cannot be blank or contain <code>..</code>, and file bytes must match declared MIME type.</p><h3>Status workflow</h3><table><thead><tr><th>From</th><th>Allowed target</th></tr></thead><tbody><tr><td><code>INITIALIZED</code></td><td>AWAITING_APPROVAL or INVALID</td></tr><tr><td><code>AWAITING_APPROVAL</code></td><td>APPROVED_PENDING_DISBURSAL, REJECTED, or INVALID</td></tr><tr><td><code>APPROVED_PENDING_DISBURSAL</code></td><td>DISBURSED, DISBURSEMENT_RETRY, or INVALID</td></tr><tr><td><code>DISBURSEMENT_RETRY</code></td><td>DISBURSED or INVALID</td></tr><tr><td><code>DISBURSED</code></td><td>UNDER_REPAYMENT, CLOSED, or FORECLOSED</td></tr><tr><td><code>UNDER_REPAYMENT</code></td><td>CLOSED or FORECLOSED</td></tr><tr><td><code>REJECTED, INVALID, CLOSED, FORECLOSED</code></td><td>Terminal</td></tr></tbody></table></section>
  <section class="section"><h2>LSP Validation and Error Cases</h2><table><thead><tr><th>Error</th><th>When LSPs see it</th></tr></thead><tbody><tr><td><code>401 UNAUTHORIZED / INVALID_CREDENTIALS</code></td><td>Missing/invalid bearer token, client credentials, disabled client, lockout after repeated bad token attempts, expired/revoked session, or bad login credentials.</td></tr><tr><td><code>403 ACCESS_DENIED / IP_NOT_ALLOWED / IP_ENFORCEMENT_EMPTY_LIST</code></td><td>Role mismatch, password-change gate, or LSP API/UI IP allowlist failure.</td></tr><tr><td><code>404 NOT_FOUND</code></td><td>Unknown id, or cross-tenant LSP access intentionally hidden as not found.</td></tr><tr><td><code>409 DUPLICATE_EXTERNAL_LOAN_ID</code></td><td>LSP reused lspLoanId for the same LSP.</td></tr><tr><td><code>409 IDEMPOTENCY_CONFLICT</code></td><td>Idempotency-Key was reused for a different request or loan.</td></tr><tr><td><code>409 REPAYMENT_SCHEDULE_LOCKED / INSTALLMENT_ALREADY_PAID / LOAN_ALREADY_INVALID</code></td><td>Attempted to mutate a locked schedule, paid installment, or already invalid loan.</td></tr><tr><td><code>413 PAYLOAD_TOO_LARGE</code></td><td>LSP JSON body or multipart upload exceeds the configured size limit.</td></tr><tr><td><code>415 DOCUMENT_PREVIEW_UNSUPPORTED</code></td><td>Admin inline preview requested for unsupported stored document media.</td></tr><tr><td><code>422 PRODUCT_NOT_ACTIVE / LSP_NOT_ACTIVE / PRODUCT_NOT_MAPPED / PRODUCT_MAPPING_DISABLED</code></td><td>Loan creation violates active LSP/product/mapping rules.</td></tr><tr><td><code>422 AMOUNT_OUT_OF_RANGE / TENURE_OUT_OF_RANGE / INTEREST_RATE_MISMATCH</code></td><td>Loan terms do not match the mapped product configuration.</td></tr><tr><td><code>422 KYC_COMPLETION_REQUIRED / DOCUMENT_UPLOAD_REQUIRED</code></td><td>Approval or disbursement attempted before required documents are complete.</td></tr><tr><td><code>422 REPAYMENT_SCHEDULE_INVALID</code></td><td>LSP-provided schedule fails reconciliation; violations[] includes field-level detail.</td></tr><tr><td><code>422 DISBURSEMENT_VALIDATION_FAILED</code></td><td>Submitted bank details do not match borrower bank details on file.</td></tr><tr><td><code>422 REPAYMENT_NOT_ALLOWED / PAYMENT_AMOUNT_MISMATCH</code></td><td>Payment posted before disbursement or amount does not exactly match installment outstanding.</td></tr><tr><td><code>422 FORECLOSURE_NOT_ALLOWED / QUOTE_NOT_ACTIVE / SETTLEMENT_DATE_MISMATCH / LSP_BOUND_VIOLATION</code></td><td>Foreclosure requested/executed outside the allowed loan state, quote state, or quote terms.</td></tr><tr><td><code>400 VALIDATION_FAILED / INVALID_REQUEST</code></td><td>Bean validation failures, malformed JSON, unknown JSON properties on strict LSP writes, invalid enum values, missing multipart parts, missing/required UUID v4 Idempotency-Key, or invalid reason text rules.</td></tr></tbody></table><div class="two"><div><h3>Create loan example</h3><pre><code>{esc_json(create_example)}</code></pre><p class="muted"><code>POST /api/v1/lsp/loan-applications</code> returns the same detail shape as <code>GET /{'{applicationId}'}</code>.</p></div><div><h3>LSP-provided schedule example</h3><pre><code>{esc_json(schedule_example)}</code></pre></div><div><h3>Disbursement bank check</h3><pre><code>{esc_json(disbursement_example)}</code></pre></div><div><h3>Payment example</h3><pre><code>{esc_json(payment_example)}</code></pre><p class="muted">Requires <code>Idempotency-Key</code> UUID v4.</p></div></div></section>"""


def collect_operations(spec: dict) -> list[tuple[str, str, dict]]:
    ops: list[tuple[str, str, dict]] = []
    for path, path_item in spec["paths"].items():
        for method in HTTP_METHODS:
            if method in path_item:
                ops.append((method, path, path_item[method]))
    return ops


def main() -> None:
    today = date.today().isoformat()
    spec = json.loads(OPENAPI.read_text(encoding="utf-8"))
    schemas = spec.get("components", {}).get("schemas", {})
    ops = collect_operations(spec)

    admin_ops = [o for o in ops if not o[1].startswith("/api/v1/lsp/")]
    lsp_ops = [o for o in ops if o[1].startswith("/api/v1/lsp/")]

    admin_endpoints = [health_endpoint()] + [render_endpoint(m, p, op, schemas) for m, p, op in sorted(admin_ops, key=lambda x: (x[1], x[0]))]
    lsp_endpoints = [render_endpoint(m, p, op, schemas) for m, p, op in sorted(lsp_ops, key=lambda x: (x[1], x[0]))]

    total = len(admin_endpoints) + len(lsp_endpoints)

    doc = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bhawana LMS API Reference by Role</title>
<style>
:root{{--bg:#f6f7f9;--panel:#fff;--text:#17202a;--muted:#5b6573;--line:#d9dee7;--blue:#1f6feb;--green:#16833a;--orange:#b75f00;--red:#c33131;--purple:#6f42c1;--slate:#334155;--shadow:0 1px 3px rgba(15,23,42,.08)}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font-family:Inter,Segoe UI,Arial,sans-serif;line-height:1.5}}header{{background:#101820;color:#fff;padding:28px 32px 22px}}header h1{{margin:0 0 8px;font-size:28px;letter-spacing:0}}header p{{margin:0;color:#c9d3df;max-width:1100px}}.wrap{{max-width:1440px;margin:0 auto;padding:24px 28px 48px}}.tabs{{position:sticky;top:0;background:rgba(246,247,249,.96);backdrop-filter:saturate(180%) blur(8px);z-index:5;border-bottom:1px solid var(--line);display:flex;gap:8px;padding:12px 0}}.tab-btn{{border:1px solid var(--line);background:#fff;border-radius:8px;padding:9px 14px;font-weight:700;color:var(--slate);cursor:pointer}}.tab-btn.active{{background:#101820;color:#fff;border-color:#101820}}.grid{{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}}.metric{{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:14px;box-shadow:var(--shadow)}}.metric strong{{display:block;font-size:22px}}.section{{background:var(--panel);border:1px solid var(--line);border-radius:8px;margin:18px 0;padding:18px;box-shadow:var(--shadow)}}h2{{margin:0 0 8px;font-size:22px}}h3{{margin:10px 0 6px;font-size:17px}}.muted{{color:var(--muted)}}table{{width:100%;border-collapse:collapse;margin:10px 0;font-size:14px}}th,td{{border:1px solid var(--line);padding:8px 10px;text-align:left;vertical-align:top}}th{{background:#edf1f6}}code{{font-family:Consolas,Menlo,monospace}}.tab-panel{{display:none}}.tab-panel.active{{display:block}}.panel-head{{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;margin:18px 0}}.filter{{width:min(420px,100%);border:1px solid var(--line);border-radius:8px;padding:10px 12px;font:inherit}}.endpoint-list{{display:grid;grid-template-columns:1fr;gap:12px}}.endpoint{{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:16px;box-shadow:var(--shadow)}}.endpoint-head{{display:flex;gap:10px;align-items:center;flex-wrap:wrap}}.method{{display:inline-flex;min-width:66px;justify-content:center;border-radius:6px;padding:4px 8px;color:#fff;font-weight:800;font-size:12px}}.get{{background:var(--blue)}}.post{{background:var(--green)}}.put{{background:var(--orange)}}.patch{{background:var(--purple)}}.delete{{background:var(--red)}}.chips{{display:flex;gap:6px;flex-wrap:wrap;margin:10px 0}}.chips span,.mini span{{background:#edf1f6;border:1px solid var(--line);border-radius:999px;padding:4px 8px;font-size:12px;color:#334155}}.mini{{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px}}details{{border-top:1px solid var(--line);padding-top:8px;margin-top:8px}}summary{{font-weight:700;cursor:pointer}}pre{{background:#0f172a;color:#d8e2f1;border-radius:8px;padding:12px;overflow:auto;font-size:13px;max-height:360px}}.body-block{{margin:10px 0}}.callout{{border-left:4px solid var(--blue);background:#eef6ff;padding:12px 14px;border-radius:6px}}.two{{display:grid;grid-template-columns:1fr 1fr;gap:14px}}@media(max-width:900px){{.grid,.two{{grid-template-columns:1fr}}.panel-head{{align-items:stretch;flex-direction:column}}.wrap{{padding:18px}}header{{padding:22px 20px}}.tabs{{overflow:auto}}.endpoint-head code{{word-break:break-word}}}}
</style>
</head>
<body>
<header><h1>Bhawana LMS API Reference by Role</h1><p>Generated on {today} from openapi/openapi.json, backend controllers/services, and repository-wide API signal scan. Tabs separate Admin/internal and LSP-facing APIs. Reflects the 2026-07 API consistency contract: bare response bodies, strict LSP write JSON, PII masking, pagination headers, and idempotency semantics.</p></header>
<main class="wrap">
  <nav class="tabs"><button class="tab-btn active" data-tab="tab-admin">Admin</button><button class="tab-btn" data-tab="tab-lsp">LSP</button></nav>
  <div class="grid"><div class="metric"><strong>{total}</strong><span>Total documented APIs</span></div><div class="metric"><strong>{len(admin_endpoints)}</strong><span>Admin/shared APIs</span></div><div class="metric"><strong>{len(lsp_endpoints)}</strong><span>LSP/shared APIs</span></div><div class="metric"><strong>8</strong><span>Auto-approval rules</span></div></div>
{static_sections(today)}
  <section id="tab-admin" class="tab-panel">
    <div class="panel-head"><div><h2>Admin and internal operations</h2><p>{len(admin_endpoints)} endpoints including shared auth routes. Use the filter to narrow by path, method, role, or operation name.</p></div><input class="filter" data-panel="tab-admin" placeholder="Filter admin APIs"></div>
    <div class="endpoint-list">{"".join(admin_endpoints)}</div>
  </section>
  <section id="tab-lsp" class="tab-panel">
    <div class="panel-head"><div><h2>LSP-facing APIs</h2><p>{len(lsp_endpoints)} endpoints for LSP API clients and LSP UI roles. Use the filter to narrow by path, method, role, or operation name.</p></div><input class="filter" data-panel="tab-lsp" placeholder="Filter LSP APIs"></div>
    <div class="endpoint-list">{"".join(lsp_endpoints)}</div>
  </section>
</main>
<script>
const buttons=[...document.querySelectorAll('.tab-btn')];
const panels=[...document.querySelectorAll('.tab-panel')];
buttons.forEach(btn=>btn.addEventListener('click',()=>{{buttons.forEach(b=>b.classList.remove('active'));panels.forEach(p=>p.classList.remove('active'));btn.classList.add('active');document.getElementById(btn.dataset.tab).classList.add('active');}}));
document.getElementById('tab-admin').classList.add('active');
document.querySelectorAll('.filter').forEach(input=>input.addEventListener('input',()=>{{const panel=document.getElementById(input.dataset.panel);const q=input.value.trim().toLowerCase();panel.querySelectorAll('.endpoint').forEach(card=>{{card.style.display=!q||card.dataset.search.includes(q)?'block':'none';}});}}));
</script>
</body></html>
"""
    OUT.write_text(doc, encoding="utf-8")
    print(f"Wrote {OUT} ({total} endpoints: {len(admin_endpoints)} admin, {len(lsp_endpoints)} lsp)")


if __name__ == "__main__":
    main()
