"""One-shot generator for LMS.postman_collection.json. Run once; commit the JSON output."""
import json, os, uuid, textwrap
OUT = os.path.join(os.path.dirname(__file__), "LMS.postman_collection.json")

# ---------- helpers ----------
def script(lines):
    return {"type": "text/javascript", "exec": lines if isinstance(lines, list) else [lines]}

def evt(kind, lines):
    return {"listen": kind, "script": script(lines)}

def url(path, query=None):
    # path is like "api/v1/auth/login"
    parts = [p for p in path.split("/") if p]
    u = {"raw": "{{baseUrl}}/" + path.lstrip("/"), "host": ["{{baseUrl}}"], "path": parts}
    if query:
        u["query"] = [{"key": k, "value": v} for k, v in query.items()]
        u["raw"] = u["raw"] + "?" + "&".join(f"{k}={v}" for k, v in query.items())
    return u

def hdr(k, v, desc=None):
    h = {"key": k, "value": v}
    if desc: h["description"] = desc
    return h

BEARER_ADMIN = [hdr("Authorization", "Bearer {{adminToken}}")]
BEARER_LSP_API = [hdr("Authorization", "Bearer {{lspApiToken}}")]
JSON_CT = [hdr("Content-Type", "application/json")]

def req(name, method, path, *, body=None, headers=None, query=None, description="", events=None, form=None):
    r = {
        "name": name,
        "request": {
            "method": method,
            "header": headers or [],
            "url": url(path, query),
            "description": description,
        },
    }
    if body is not None:
        if isinstance(body, str):
            raw = body
        else:
            raw = json.dumps(body, indent=2)
        r["request"]["body"] = {
            "mode": "raw",
            "raw": raw,
            "options": {"raw": {"language": "json"}},
        }
    if form is not None:
        r["request"]["body"] = {"mode": "formdata", "formdata": form}
    if events:
        r["event"] = events
    return r

def folder(name, description, items, events=None):
    f = {"name": name, "description": description, "item": items}
    if events:
        f["event"] = events
    return f

# ---------- collection-level scripts ----------
COLLECTION_PRE = [
    "// Ensure baseUrl always has a value",
    "if (!pm.environment.get('baseUrl')) {",
    "    pm.environment.set('baseUrl', 'http://localhost:8080');",
    "}",
    "// Fresh correlation ID per request — shows up in backend logs",
    "pm.request.headers.upsert({ key: 'X-Correlation-Id', value: pm.variables.replaceIn('{{$guid}}') });",
]

COLLECTION_TEST = [
    "// Print correlation ID so console shows the full trace",
    "const cid = pm.request.headers.get('X-Correlation-Id');",
    "if (cid) console.log('[' + pm.info.requestName + '] X-Correlation-Id=' + cid + ' status=' + pm.response.code);",
]

# ---------- Folder 0: System health ----------
health = folder("0. System Health", "Fail-fast if the backend is unreachable.", [
    req("Backend health", "GET", "actuator/health",
        description="Spring Boot Actuator probe. Run first to confirm the API is up.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const body = pm.response.json();",
            "pm.test('status UP', () => pm.expect(body.status).to.eql('UP'));",
        ])]),
])

# ---------- Folder 1: Admin auth & bootstrap ----------
LOGIN_TEST = [
    "function setAdminToken(token) {",
    "    pm.environment.set('adminToken', token);",
    "    pm.collectionVariables.set('adminToken', token);",
    "}",
    "pm.test('login returned 200 OK', () => pm.response.to.have.status(200));",
    "const r = pm.response.json();",
    "if (!r.accessToken) {",
    "    throw new Error('Login did not return accessToken. Status=' + pm.response.code + ' Body=' + pm.response.text());",
    "}",
    "setAdminToken(r.accessToken);",
    "pm.environment.set('adminPasswordChangeRequired', r.passwordChangeRequired ? 'true' : 'false');",
    "pm.collectionVariables.set('adminPasswordChangeRequired', r.passwordChangeRequired ? 'true' : 'false');",
    "pm.test('accessToken returned', () => pm.expect(r.accessToken).to.be.a('string').and.not.empty);",
    "pm.environment.set('adminCurrentPassword', pm.environment.get('adminCurrentPassword') || pm.environment.get('adminBootstrapPassword'));",
    "",
    "// If this is first-ever login with the bootstrap password, change it inline",
    "if (r.passwordChangeRequired) {",
    "    console.log('[auth] passwordChangeRequired=true — changing password inline.');",
    "    pm.sendRequest({",
    "        url: pm.environment.get('baseUrl') + '/api/v1/auth/password',",
    "        method: 'POST',",
    "        header: {",
    "            'Content-Type': 'application/json',",
    "            'Authorization': 'Bearer ' + r.accessToken,",
    "            'X-Correlation-Id': pm.variables.replaceIn('{{$guid}}')",
    "        },",
    "        body: { mode: 'raw', raw: JSON.stringify({ newPassword: pm.environment.get('adminNewPassword') }) }",
    "    }, (err, resp) => {",
    "        if (err) { console.error('[auth] password change failed', err); return; }",
    "        if (resp.code !== 200) { console.error('[auth] password change HTTP ' + resp.code + ': ' + resp.text()); return; }",
    "        const changed = resp.json();",
    "        if (!changed.accessToken) { console.error('[auth] password change response did not include accessToken: ' + resp.text()); return; }",
    "        setAdminToken(changed.accessToken);",
    "        pm.environment.set('adminCurrentPassword', pm.environment.get('adminNewPassword'));",
    "        pm.environment.set('adminPasswordChangeRequired', 'false');",
    "        pm.collectionVariables.set('adminPasswordChangeRequired', 'false');",
    "        console.log('[auth] admin password rotated to adminNewPassword');",
    "    });",
    "} else {",
    "    pm.environment.set('adminCurrentPassword', pm.environment.get('adminCurrentPassword') || pm.environment.get('adminNewPassword'));",
    "}",
]

admin_login_pre = [
    "// Keep adminCurrentPassword explicit, but allow reruns to reuse the stored value",
    "const currentPassword = pm.environment.get('adminCurrentPassword');",
    "if (!currentPassword) {",
    "    const rotated = pm.environment.get('adminPasswordChangeRequired') === 'false';",
    "    pm.environment.set('adminCurrentPassword', rotated ? pm.environment.get('adminNewPassword') : pm.environment.get('adminBootstrapPassword'));",
    "}",
]

f1 = folder("1. Admin Auth & Bootstrap",
    "Authenticate as the seeded SYSTEM_ADMIN + rotate the bootstrap password on first run.", [
    req("Admin login", "POST", "api/v1/auth/login",
        headers=JSON_CT,
        body={"username": "{{adminUsername}}", "password": "{{adminCurrentPassword}}"},
        description="POST /api/v1/auth/login — password grant for ops.admin. If passwordChangeRequired, a nested call to /auth/password rotates the password to adminNewPassword (env var) and stores the new token.",
        events=[evt("prerequest", admin_login_pre), evt("test", LOGIN_TEST)]),

    req("Admin metadata", "GET", "api/v1/internal/admin/metadata",
        headers=BEARER_ADMIN,
        description="Enum catalog (roles, statuses, webhook event types). Useful for UI dropdowns.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const m = pm.response.json();",
            "pm.test('roles present', () => pm.expect(m.roles).to.be.an('array').and.not.empty);",
        ])]),

    req("System context (whoami)", "GET", "api/v1/internal/system/context",
        headers=BEARER_ADMIN,
        description="Whoami — returns roles, lspId (null for admins), correlationId.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const c = pm.response.json();",
            "pm.test('SYSTEM_ADMIN role', () => pm.expect(c.roles).to.include('SYSTEM_ADMIN'));",
        ])]),
])

# ---------- Folder 2: LSP onboarding ----------
f2 = folder("2. LSP Onboarding",
    "Create a demo LSP (partner lender) and optionally configure webhook delivery.", [
    req("Create LSP", "POST", "api/v1/internal/admin/lsps",
        headers=BEARER_ADMIN + JSON_CT,
        body={"code": "{{lspCode}}", "name": "{{lspName}}", "status": "ACTIVE"},
        description="Creates an LSP. Pre-request generates a unique code/name if not set.",
        events=[evt("prerequest", [
            "if (!pm.environment.get('lspCode')) {",
            "    const code = 'DEMO' + Date.now().toString().slice(-8);",
            "    pm.environment.set('lspCode', code);",
            "    pm.environment.set('lspName', 'Demo Lender ' + code);",
            "}",
        ]), evt("test", [
            "pm.test('201 Created or 200 OK', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            "pm.environment.set('lspId', r.id);",
            "pm.test('lspId stored', () => pm.expect(r.id).to.be.a('string'));",
        ])]),

    req("List LSPs", "GET", "api/v1/internal/admin/lsps",
        headers=BEARER_ADMIN,
        description="Includes portfolio summary and user count per LSP.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const list = pm.response.json();",
            "const target = pm.environment.get('lspId');",
            "pm.test('newly created LSP is in the list', () => pm.expect(list.some(l => l.id === target)).to.be.true);",
        ])]),

    req("Configure webhook subscription (optional)", "PUT",
        "api/v1/internal/admin/lsps/{{lspId}}/webhook-subscription",
        headers=BEARER_ADMIN + JSON_CT,
        body={
            "enabled": True,
            "endpointUrl": "https://webhook.site/{{webhookSiteId}}",
            "signingSecret": "demo-hmac-secret",
            "eventTypes": ["LOAN_APPLICATION_STATUS_CHANGED", "LOAN_DISBURSED", "LOAN_PAYMENT_RECEIVED"],
        },
        description="Optional: wire this LSP to https://webhook.site to watch webhooks arrive. Replace webhookSiteId env var with your own webhook.site UUID before running, or disable this request. Deliveries appear in Folder 9's webhook outbox view.",
        events=[evt("test", [
            "// Tolerate 200 or 400 — 400 happens if webhookSiteId is still the placeholder UUID",
            "if (pm.response.code >= 400) {",
            "    console.warn('[webhook] config skipped: ' + pm.response.code + ' ' + pm.response.text());",
            "}",
        ])]),
])

# ---------- Folder 3: Product configuration ----------
f3 = folder("3. Product Configuration",
    "Create a loan product and map it to the demo LSP.", [
    req("Create loan product", "POST", "api/v1/internal/admin/products",
        headers=BEARER_ADMIN + JSON_CT,
        body={
            "code": "{{productCode}}",
            "name": "Personal Loan Demo",
            "minPrincipal": 10000,
            "maxPrincipal": 500000,
            "interestRate": 14.5,
            "processingFeeRate": 1.5,
            "minTenureMonths": 6,
            "maxTenureMonths": 36,
            "status": "ACTIVE",
        },
        description="Creates an ACTIVE personal loan product. Pre-request generates a unique code.",
        events=[evt("prerequest", [
            "if (!pm.environment.get('productCode')) {",
            "    pm.environment.set('productCode', 'PL-' + Date.now().toString().slice(-8));",
            "}",
        ]), evt("test", [
            "pm.test('201/200', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            "pm.environment.set('productId', r.id);",
            "pm.test('productId stored', () => pm.expect(r.id).to.be.a('string'));",
        ])]),

    req("Map product to LSP", "PUT",
        "api/v1/internal/admin/products/{{productId}}/mappings",
        headers=BEARER_ADMIN + JSON_CT,
        body={"lspIds": ["{{lspId}}"]},
        description="Replaces the full list of LSPs for this product — caller must include every LSP that should see the product.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "const expected = pm.environment.get('lspId');",
            "pm.test('mapped LSP matches', () => pm.expect(r.mappedLsps.map(l => l.id)).to.include(expected));",
        ])]),

    req("Product audit trail", "GET",
        "api/v1/internal/admin/products/{{productId}}/audit-events",
        headers=BEARER_ADMIN,
        description="Shows who created / edited / remapped the product — supports compliance review.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const events = pm.response.json();",
            "pm.test('at least one audit event', () => pm.expect(events.length).to.be.at.least(1));",
        ])]),
])

# ---------- Folder 4: Users & API clients ----------
LSP_UI_LOGIN_TEST = [
    "const r = pm.response.json();",
    "pm.environment.set('lspUiToken', r.accessToken);",
    "pm.environment.set('lspUiPasswordChangeRequired', r.passwordChangeRequired ? 'true' : 'false');",
    "pm.test('accessToken returned', () => pm.expect(r.accessToken).to.be.a('string').and.not.empty);",
    "if (r.passwordChangeRequired) {",
    "    const newPwd = pm.environment.get('lspUiNewPassword');",
    "    pm.sendRequest({",
        "        url: pm.environment.get('baseUrl') + '/api/v1/auth/password',",
        "        method: 'POST',",
    "        header: {",
    "            'Content-Type': 'application/json',",
    "            'Authorization': 'Bearer ' + r.accessToken,",
    "            'X-Correlation-Id': pm.variables.replaceIn('{{$guid}}')",
    "        },",
    "        body: { mode: 'raw', raw: JSON.stringify({ newPassword: newPwd }) }",
    "    }, (err, resp) => {",
    "        if (err) { console.error('[auth] lsp pwd change failed', err); return; }",
    "        if (resp.code !== 200) { console.error('[auth] lsp pwd change HTTP ' + resp.code + ': ' + resp.text()); return; }",
    "        const changed = resp.json();",
    "        pm.environment.set('lspUiToken', changed.accessToken);",
    "        pm.environment.set('lspUiCurrentPassword', newPwd);",
    "        pm.environment.set('lspUiPassword', newPwd);",
    "        pm.environment.set('lspUiPasswordChangeRequired', 'false');",
    "        console.log('[auth] LSP UI password rotated.');",
    "    });",
    "} else {",
    "    pm.environment.set('lspUiCurrentPassword', pm.environment.get('lspUiCurrentPassword') || pm.environment.get('lspUiNewPassword'));",
    "    pm.environment.set('lspUiPassword', pm.environment.get('lspUiCurrentPassword'));",
    "}",
]

lsp_ui_login_pre = [
    "// Keep the LSP UI auth variables explicit, but preserve the stored current password across reruns",
    "const currentPassword = pm.environment.get('lspUiCurrentPassword');",
    "if (!currentPassword) {",
    "    const rotated = pm.environment.get('lspUiPasswordChangeRequired') === 'false';",
    "    const nextPassword = rotated ? pm.environment.get('lspUiNewPassword') : pm.environment.get('lspUiBootstrapPassword');",
    "    pm.environment.set('lspUiCurrentPassword', nextPassword);",
    "    pm.environment.set('lspUiPassword', nextPassword);",
    "}",
]

f4 = folder("4. Users & API Clients",
    "Create one LSP UI user (human operator) and one LSP API client (machine).", [
    req("Create LSP UI user", "POST", "api/v1/internal/admin/users",
        headers=BEARER_ADMIN + JSON_CT,
        body={
            "username": "{{lspUiUsername}}",
            "email": "{{lspUiUsername}}@demo.local",
            "password": "{{lspUiBootstrapPassword}}",
            "lspId": "{{lspId}}",
            "roles": ["LSP_UI_WRITE"],
        },
        description="Human operator for the LSP tenant. Admin-set password will typically require rotation on first login — the LSP UI login below handles that inline.",
        events=[evt("prerequest", [
            "if (!pm.environment.get('lspUiUsername')) {",
            "    pm.environment.set('lspUiUsername', 'lsp.' + String(pm.environment.get('lspCode')).toLowerCase());",
            "}",
            "if (!pm.environment.get('lspUiCurrentPassword')) {",
            "    pm.environment.set('lspUiCurrentPassword', pm.environment.get('lspUiBootstrapPassword'));",
            "}",
            "if (!pm.environment.get('lspUiPassword')) {",
            "    pm.environment.set('lspUiPassword', pm.environment.get('lspUiBootstrapPassword'));",
            "}",
            "if (!pm.environment.get('lspUiPasswordChangeRequired')) {",
            "    pm.environment.set('lspUiPasswordChangeRequired', 'true');",
            "}",
        ]), evt("test", [
            "pm.test('201/200', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            "pm.environment.set('lspUiUserId', r.id);",
        ])]),

    req("Create LSP API client (machine user)", "POST", "api/v1/internal/admin/api-clients",
        headers=BEARER_ADMIN + JSON_CT,
        body={
            "name": "{{lspCode}} API Client",
            "description": "Demo machine user used by LSP backend to call LMS",
            "lspId": "{{lspId}}",
            "status": "ACTIVE",
        },
        description="⚠️ clientSecret is returned ONLY on creation — immediately saved to env as lspApiClientSecret.",
        events=[evt("test", [
            "pm.test('201/200', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            "pm.environment.set('lspApiClientId', r.clientId);",
            "pm.environment.set('lspApiClientSecret', r.clientSecret);",
            "pm.test('clientSecret captured', () => pm.expect(r.clientSecret).to.be.a('string').and.not.empty);",
        ])]),

    req("LSP UI user login", "POST", "api/v1/auth/login",
        headers=JSON_CT,
        body={"username": "{{lspUiUsername}}", "password": "{{lspUiCurrentPassword}}"},
        description="Password grant for the LSP UI user. If passwordChangeRequired, rotates inline and stores the new password.",
        events=[evt("prerequest", lsp_ui_login_pre), evt("test", LSP_UI_LOGIN_TEST)]),

    req("LSP API client token exchange", "POST", "api/v1/auth/token",
        headers=JSON_CT,
        body={"clientId": "{{lspApiClientId}}", "clientSecret": "{{lspApiClientSecret}}"},
        description="OAuth2-style client credentials grant. Issues a JWT with LSP_API_CLIENT role and lspId claim.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "pm.environment.set('lspApiToken', r.accessToken);",
            "pm.test('bearer token issued', () => pm.expect(r.tokenType).to.eql('Bearer'));",
        ])]),
])

# ---------- Folder 5: Loan origination ----------
LOAN_APP_BODY = {
    "lspId": "{{lspId}}",
    "productId": "{{productId}}",
    "lspLoanId": "{{externalLoanId}}",
    "fullName": "Demo Borrower",
    "emailAddress": "demo.borrower@example.com",
    "mobileNumber": "9876543210",
    "dob": "1990-05-15",
    "gender": "MALE",
    "maritalStatus": "SINGLE",
    "fatherName": "Parent Borrower",
    "aadharNumber": "123456789012",
    "panNumber": "ABCDE1234F",
    "loanAmount": 150000,
    "interestRate": 14.5,
    "loanTenure": 12,
    "addressLine1": "42 Demo Street",
    "addressLine2": "Apt 3B",
    "addressCity": "Mumbai",
    "addressState": "MH",
    "addressZipcode": "400001",
    "employmentStatus": "SALARIED",
    "organizationName": "Demo Corp",
    "empId": "EMP-1001",
    "employmentCity": "Mumbai",
    "employmentState": "MH",
    "employmentZip": "400001",
    "monthlyIncome": 60000,
    "annualIncome": 720000,
    "bankAccountNumber": "1234567890",
    "bankName": "HDFC Bank",
    "ifscCode": "HDFC0001234",
    "accountHolderName": "Demo Borrower",
    "referencePersonName": "Ref Person",
    "referencePersonNumber": "9123456780",
}

f5 = folder("5. Loan Origination (LSP API)",
    "Act as the LSP machine user — list products, create a loan application.", [
    req("List products available to this LSP", "GET", "api/v1/lsp/products",
        headers=BEARER_LSP_API,
        description="Returns only products this LSP is mapped to.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const list = pm.response.json();",
            "const target = pm.environment.get('productId');",
            "pm.test('demo product visible', () => pm.expect(list.some(p => p.id === target)).to.be.true);",
        ])]),

    req("Create loan application", "POST", "api/v1/lsp/loan-applications",
        headers=BEARER_LSP_API + JSON_CT + [hdr("Idempotency-Key", "{{$guid}}")],
        body=LOAN_APP_BODY,
        description="Submits borrower PII + loan terms. Creates a Borrower if new, a LoanApplication in INITIALIZED status.",
        events=[evt("prerequest", [
            "if (!pm.environment.get('externalLoanId')) {",
            "    pm.environment.set('externalLoanId', 'EXT-' + Date.now().toString().slice(-10));",
            "}",
        ]), evt("test", [
            "pm.test('201/200', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            "pm.environment.set('applicationId', r.id);",
            "pm.environment.set('borrowerId', r.borrowerId);",
            "pm.test('status=INITIALIZED', () => pm.expect(r.status).to.eql('INITIALIZED'));",
        ])]),

    req("Get loan application (LSP view)", "GET",
        "api/v1/lsp/loan-applications/{{applicationId}}",
        headers=BEARER_LSP_API,
        description="Full application detail with borrower and loan account summary (empty at this stage).",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "pm.test('status=INITIALIZED', () => pm.expect(r.status).to.eql('INITIALIZED'));",
        ])]),
])

# ---------- Folder 6: Document upload ----------
def doc_upload(name, doc_type, file_name, var_name):
    return req(name, "POST", "api/v1/lsp/loan-applications/{{applicationId}}/documents",
        headers=BEARER_LSP_API,
        form=[
            {"key": "documentType", "value": doc_type, "type": "text"},
            {"key": "note", "value": f"Auto-uploaded via Postman demo", "type": "text"},
            {"key": "file", "type": "file", "src": f"./assets/{file_name}"},
        ],
        description=f"Multipart upload. In Postman desktop you may need to re-select the file once after import — browse to postman/assets/{file_name}.",
        events=[evt("test", [
            "pm.test('201/200', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            f"pm.environment.set('{var_name}', r.id);",
        ])])

f6 = folder("6. Document Upload",
    "Upload PAN / Aadhaar / Bank statement via LSP multipart, then verify via admin view.", [
    doc_upload("Upload PAN (multipart)", "PAN", "sample-pan.pdf", "panDocId"),
    doc_upload("Upload Aadhaar (multipart)", "AADHAR", "sample-aadhaar.pdf", "aadhaarDocId"),
    doc_upload("Upload Bank statement (multipart)", "BANK_STATEMENT", "sample-bank-statement.pdf", "bankDocId"),
    req("(Admin) List loan documents", "GET",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/kyc-documents",
        headers=BEARER_ADMIN,
        description="Admin view of the KYC checklist — confirms the three docs landed on the server.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const docs = pm.response.json();",
            "const received = docs.filter(d => d.status === 'RECEIVED' || d.status === 'APPROVED').length;",
            "console.log('[docs] received=' + received + ' total=' + docs.length);",
            "pm.test('at least 3 docs in RECEIVED state', () => pm.expect(received).to.be.at.least(3));",
        ])]),
])

# ---------- Folder 7: Approval, disbursement, schedule ----------
transition = lambda target, note: req(f"Move to {target}", "POST",
    "api/v1/internal/ops/loan-applications/{{applicationId}}/status-transitions",
    headers=BEARER_ADMIN + JSON_CT,
    body={"targetStatus": target, "note": note},
    description=f"Admin transitions application to {target}.",
    events=[evt("test", [
        "pm.test('200 OK', () => pm.response.to.have.status(200));",
        "const r = pm.response.json();",
        f"pm.test('status={target}', () => pm.expect(r.status).to.eql('{target}'));",
        "if (r.loanAccount && r.loanAccount.id) {",
        "    pm.environment.set('loanAccountId', r.loanAccount.id);",
        "}",
    ])])

f7 = folder("7. Approval, Disbursement & Repayment Schedule",
    "Approve → LSP requests disbursement → mock success → schedule generated.", [
    transition("AWAITING_APPROVAL", "All KYC documents received"),
    transition("APPROVED_PENDING_DISBURSAL", "Credit approved"),

    req("(LSP) Request disbursement", "POST",
        "api/v1/lsp/loan-applications/{{applicationId}}/disbursement",
        headers=BEARER_LSP_API + JSON_CT + [hdr("Idempotency-Key", "{{$guid}}")],
        body={
            "disbursalAmount": 150000,
            "bankAccountNumber": "1234567890",
            "ifscCode": "HDFC0001234",
            "accountHolderName": "Demo Borrower",
        },
        description="LSP calls the disbursement endpoint with final bank details. Locally this hits MockLoanDisbursementAdapter which stays PENDING until admin resolves it.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "if (r.loanAccount && r.loanAccount.id) pm.environment.set('loanAccountId', r.loanAccount.id);",
        ])]),

    req("(Admin) Resolve mock disbursement → SUCCESS", "POST",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/disbursement-requests/mock-outcome",
        headers=BEARER_ADMIN + JSON_CT,
        body={"outcome": "SUCCESS"},
        description="Demo-only shortcut. Flips the pending mock disbursement to SUCCESS → loan transitions to DISBURSED → repayment schedule is generated automatically.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "if (r.loanAccount && r.loanAccount.id) pm.environment.set('loanAccountId', r.loanAccount.id);",
        ])]),

    req("(LSP) Repayment schedule", "GET",
        "api/v1/lsp/loans/{{loanAccountId}}/repayment-schedule",
        headers=BEARER_LSP_API,
        description="Fetches the 12-installment schedule. Stored to env for the repayment loop in Folder 8.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const schedule = pm.response.json();",
            "pm.test('12 installments', () => pm.expect(schedule.length).to.eql(12));",
            "const slim = schedule.map(i => ({ id: i.id, n: i.installmentNumber, amount: Number(i.installmentAmount), dueDate: i.dueDate }));",
            "pm.environment.set('installments', JSON.stringify(slim));",
            "pm.environment.set('nextInstallmentIndex', '0');",
        ])]),
])

# ---------- Folder 8: Full repayment & closure ----------
PAY_PRE = [
    "const raw = pm.environment.get('installments');",
    "if (!raw) { console.error('[repay] installments env var is empty — run Folder 7 first.'); return; }",
    "const installments = JSON.parse(raw);",
    "const idx = parseInt(pm.environment.get('nextInstallmentIndex') || '0', 10);",
    "const inst = installments[idx];",
    "if (!inst) { console.error('[repay] no installment at index ' + idx); return; }",
    "pm.variables.set('currentInstallmentNumber', inst.n);",
    "pm.variables.set('currentInstallmentAmount', inst.amount);",
    "pm.variables.set('currentPaymentDate', new Date().toISOString().slice(0,10));",
    "console.log('[repay] paying installment ' + inst.n + '/12  amount=' + inst.amount);",
]

PAY_TEST = [
    "pm.test('200 OK', () => pm.response.to.have.status(200));",
    "const installments = JSON.parse(pm.environment.get('installments'));",
    "const idx = parseInt(pm.environment.get('nextInstallmentIndex') || '0', 10);",
    "const nextIdx = idx + 1;",
    "pm.environment.set('nextInstallmentIndex', String(nextIdx));",
    "if (nextIdx < installments.length) {",
    "    postman.setNextRequest('Pay installment (loops 12x)');",
    "} else {",
    "    console.log('[repay] all installments paid — continuing collection.');",
    "}",
]

f8 = folder("8. Full Repayment & Loan Closure",
    "Loop through the 12 installments, then assert the loan auto-closes.", [
    req("Pay installment (loops 12x)", "POST",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/payments",
        headers=BEARER_ADMIN + JSON_CT,
        body={
            "amount": "{{currentInstallmentAmount}}",
            "paymentDate": "{{currentPaymentDate}}",
            "reference": "EMI-{{currentInstallmentNumber}}",
            "channel": "UPI",
            "status": "RECEIVED",
            "note": "Postman demo repayment",
        },
        description="Pays one installment then re-queues itself via postman.setNextRequest until all 12 are done. Use Postman Collection Runner (not single-request Send) for the loop to fire.",
        events=[evt("prerequest", PAY_PRE), evt("test", PAY_TEST)]),

    req("Verify loan CLOSED", "GET",
        "api/v1/internal/ops/loan-applications/{{applicationId}}",
        headers=BEARER_ADMIN,
        description="All 12 payments applied — loan account should be CLOSED with closureReason=FULLY_REPAID.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "if (r.loanAccount) {",
            "    console.log('[close] loanAccount.status=' + r.loanAccount.status + ' closureReason=' + r.loanAccount.closureReason);",
            "}",
            "pm.test('application CLOSED', () => pm.expect(r.status).to.eql('CLOSED'));",
            "pm.test('loan account CLOSED', () => pm.expect(r.loanAccount && r.loanAccount.status).to.eql('CLOSED'));",
        ])]),

    req("(Fallback) Force-close loan application", "POST",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/status-transitions",
        headers=BEARER_ADMIN + JSON_CT,
        body={"targetStatus": "CLOSED", "note": "All installments settled — forcing close"},
        description="Only run if auto-close did not fire (e.g., pending reconciliation). Not part of the happy-path runner.",
        events=[evt("test", [
            "if (pm.response.code === 200) {",
            "    pm.test('transitioned to CLOSED', () => pm.expect(pm.response.json().status).to.eql('CLOSED'));",
            "}",
        ])]),
])

# ---------- Folder 9: Business-management demo views ----------
f9 = folder("9. Business-Management Demo Views",
    "Portfolio KPIs, DPD breakdown, alerts, webhook outbox, borrower 360, lifecycle audit.", [
    req("Portfolio KPI overview", "GET", "api/v1/internal/home/overview",
        headers=BEARER_ADMIN,
        description="Total disbursed / outstanding / DPD 90+ with per-LSP breakdown and share percentages.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "console.log('[kpi] disbursed=' + r.totalDisbursedAmount + ' outstanding=' + r.totalOutstandingAmount + ' dpd90+=' + r.dpd90PlusAmount);",
            "(r.lspBreakdown || []).forEach(l => console.log('  [lsp] ' + l.lspCode + ' disbursed=' + l.disbursedAmount + ' share=' + l.shareOfDisbursedPercent + '%'));",
        ])]),

    req("DPD bucket breakdown (pretty print)", "GET",
        "api/v1/internal/home/overview",
        headers=BEARER_ADMIN,
        description="Same endpoint — test script formats the bucketBreakdown as a readable table.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "(r.lspBreakdown || []).forEach(l => {",
            "    console.log('--- ' + l.lspCode + ' ---');",
            "    (l.bucketBreakdown || []).forEach(b => console.log('  ' + b.bucket.padEnd(14) + ' loans=' + b.loanCount + ' outstanding=' + b.outstandingAmount));",
            "});",
        ])]),

    req("Ops alerts feed (open)", "GET", "api/v1/internal/alerts",
        headers=BEARER_ADMIN,
        query={"status": "OPEN"},
        description="Compliance & system alerts visible to ops — filterable by status.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const alerts = pm.response.json();",
            "if (alerts.length) pm.environment.set('firstAlertId', alerts[0].id);",
            "console.log('[alerts] open=' + alerts.length);",
        ])]),

    req("Acknowledge first alert (if any)", "POST",
        "api/v1/internal/alerts/{{firstAlertId}}/acknowledge",
        headers=BEARER_ADMIN,
        description="Acknowledges the first open alert — demonstrates triage workflow. Skipped if no alerts exist.",
        events=[evt("prerequest", [
            "if (!pm.environment.get('firstAlertId')) {",
            "    console.log('[alerts] no open alert — skipping acknowledge.');",
            "    postman.setNextRequest('Webhook outbox (per LSP)');",
            "}",
        ]), evt("test", [
            "if (pm.response.code === 200) {",
            "    const r = pm.response.json();",
            "    console.log('[alerts] acknowledged by ' + r.acknowledgedByUsername);",
            "}",
        ])]),

    req("Webhook outbox (per LSP)", "GET",
        "api/v1/internal/admin/webhook-outbox",
        headers=BEARER_ADMIN,
        query={"lspId": "{{lspId}}"},
        description="Lists every webhook event queued for this LSP — status, attempt count, last error.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const events = pm.response.json();",
            "const byStatus = events.reduce((a,e) => { a[e.status]=(a[e.status]||0)+1; return a; }, {});",
            "console.log('[webhook] outbox=' + JSON.stringify(byStatus));",
        ])]),

    req("Webhook dispatch batch", "POST",
        "api/v1/internal/admin/webhook-outbox/dispatch",
        headers=BEARER_ADMIN,
        query={"batchSize": "20"},
        description="Manually fires the outbox worker once. Shows delivered / retryable / permanent counts.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "console.log('[webhook] processed=' + r.processed + ' delivered=' + r.delivered + ' retryable=' + r.retryableFailures + ' permanent=' + r.permanentFailures);",
        ])]),

    req("Borrower 360", "GET",
        "api/v1/internal/admin/borrowers/{{borrowerId}}",
        headers=BEARER_ADMIN,
        description="Full borrower profile (PII masked to last 4) plus every loan across LSPs.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const b = pm.response.json();",
            "console.log('[borrower] ' + b.fullName + ' loans=' + (b.loans || []).length);",
        ])]),

    req("Loan status transitions", "GET",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/status-transitions",
        headers=BEARER_ADMIN,
        description="Auditable trail of every status change — who, when, why."),

    req("Loan assignment events", "GET",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/assignment-events",
        headers=BEARER_ADMIN,
        description="Chain of ops ownership for this loan."),

    req("Document access audits", "GET",
        "api/v1/internal/ops/loan-applications/{{applicationId}}/document-access-audits",
        headers=BEARER_ADMIN,
        description="Who viewed / downloaded which documents — compliance evidence."),
])

# ---------- Folder 10: MIS reports ----------
f10 = folder("10. MIS Reports",
    "Async MIS export with polling + synchronous shortcuts + paginated preview.", [
    req("Request async MIS (email delivery)", "POST",
        "api/v1/internal/reports/portfolio-mis/requests",
        headers=BEARER_ADMIN + JSON_CT,
        body={
            "lspId": "{{lspId}}",
            "disbursalDateFrom": "{{disbursalDateFrom}}",
            "disbursalDateTo": "{{disbursalDateTo}}",
            "recipientEmail": "{{adminEmail}}",
        },
        description="Queues an MIS export. A background worker generates the CSV and emails it to recipientEmail.",
        events=[evt("test", [
            "pm.test('201/200', () => pm.expect([200,201]).to.include(pm.response.code));",
            "const r = pm.response.json();",
            "pm.environment.set('reportRequestId', r.id);",
            "pm.environment.set('reportPollAttempts', '0');",
            "console.log('[mis] queued reportRequestId=' + r.id);",
        ])]),

    req("Poll MIS status", "GET",
        "api/v1/internal/reports/requests",
        headers=BEARER_ADMIN,
        description="Polls until the MIS request is COMPLETED. Re-queues itself up to 10x; backend worker runs every 15s.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const target = pm.environment.get('reportRequestId');",
            "const list = pm.response.json();",
            "const me = (list.content || list).find(r => r.id === target);",
            "if (!me) { console.warn('[mis] request not found in list'); return; }",
            "console.log('[mis] status=' + me.status);",
            "const attempts = parseInt(pm.environment.get('reportPollAttempts') || '0', 10) + 1;",
            "pm.environment.set('reportPollAttempts', String(attempts));",
            "if (me.status !== 'COMPLETED' && me.status !== 'FAILED' && attempts < 10) {",
            "    setTimeout(() => {}, 0);",
            "    postman.setNextRequest('Poll MIS status');",
            "} else if (me.status !== 'COMPLETED') {",
            "    console.warn('[mis] giving up — status=' + me.status + ' attempts=' + attempts);",
            "}",
        ])]),

    req("Download MIS CSV (via request)", "GET",
        "api/v1/internal/reports/requests/{{reportRequestId}}/download",
        headers=BEARER_ADMIN,
        description="Binary CSV download. Postman's 'Save response → Save to file' exports the CSV.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const ct = pm.response.headers.get('Content-Type') || '';",
            "pm.test('content-type is csv', () => pm.expect(ct.toLowerCase()).to.include('csv'));",
            "pm.test('body not empty', () => pm.expect(pm.response.responseSize).to.be.above(0));",
        ])]),

    req("Synchronous MIS CSV (no queue)", "GET",
        "api/v1/internal/reports/portfolio-mis",
        headers=BEARER_ADMIN,
        query={
            "lspId": "{{lspId}}",
            "disbursalDateFrom": "{{disbursalDateFrom}}",
            "disbursalDateTo": "{{disbursalDateTo}}",
        },
        description="Generates and returns the same CSV inline — useful when local mail is off.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const ct = pm.response.headers.get('Content-Type') || '';",
            "pm.test('content-type is csv', () => pm.expect(ct.toLowerCase()).to.include('csv'));",
        ])]),

    req("MIS preview (paginated JSON)", "GET",
        "api/v1/internal/reports/portfolio-mis/preview",
        headers=BEARER_ADMIN,
        query={
            "lspId": "{{lspId}}",
            "disbursalDateFrom": "{{disbursalDateFrom}}",
            "disbursalDateTo": "{{disbursalDateTo}}",
            "page": "0", "size": "50",
        },
        description="JSON preview of the same rows — easier for review agents to read inline.",
        events=[evt("test", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const r = pm.response.json();",
            "console.log('[mis preview] rows=' + (r.content ? r.content.length : (r.rows ? r.rows.length : 'unknown')));",
        ])]),
])

# ---------- Folder 11: Teardown ----------
f11 = folder("11. Teardown (optional)", "Revokes the refresh cookie.", [
    req("Admin logout", "POST", "api/v1/auth/logout",
        description="Clears the lms-refresh cookie and revokes the stored refresh token.",
        events=[evt("test", [
            "pm.test('204 No Content', () => pm.response.to.have.status(204));",
            "['adminToken', 'lspUiToken', 'lspApiToken'].forEach(key => pm.environment.unset(key));",
        ])]),
])

# ---------- Assemble ----------
collection = {
    "info": {
        "_postman_id": str(uuid.uuid4()),
        "name": "LMS — Bhawana Capital",
        "description": textwrap.dedent("""
            End-to-end Postman collection for the Bhawana LMS (Loan Management System).

            Run folders top-to-bottom. Every request writes any extracted IDs/tokens back
            to the environment, so you can execute the full chain via Collection Runner
            without copy-pasting values.

            Lifecycle covered:
              0   → health
              1   → admin auth (auto-rotates bootstrap password on first run)
              2–4 → LSP / product / user / API-client onboarding
              5–7 → loan origination → documents → approval → disbursement → schedule
              8   → 12 installment payments → loan closure
              9   → business-management demo views (KPIs, DPD, alerts, webhooks, 360)
              10  → MIS report (async queue + sync + preview)
              11  → logout

            Pair this collection with the three manual UI checks listed in postman/README.md.
        """).strip(),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "item": [health, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11],
    "event": [evt("prerequest", COLLECTION_PRE), evt("test", COLLECTION_TEST)],
    "variable": [
        {"key": "baseUrl", "value": "http://localhost:8080", "type": "default"},
        {"key": "adminToken", "value": "", "type": "secret"},
        {"key": "adminPasswordChangeRequired", "value": "", "type": "default"},
    ],
}

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(collection, f, indent=2)
print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes, {len(collection['item'])} folders)")

