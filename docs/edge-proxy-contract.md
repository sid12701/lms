# H03 edge proxy contract (required ingress behavior)

The application trusts forwarding input only from explicitly configured proxy CIDRs
(`app.edge.trusted-proxies`) and only as **one** `X-Forwarded-For` line of bare IP
literals. The edge (ingress / reverse proxy / load balancer) MUST do all of the following;
anything else is rejected with `400 INVALID_FORWARDED_HEADER` or attributed to the
socket peer:

1. **Strip** any client-supplied `X-Forwarded-For`, `X-Real-IP`, and `Forwarded` headers.
   Never forward them, never append to them.
2. **Set exactly one** `X-Forwarded-For` header line whose value is the observed client
   address as a bare IP literal — no port (`203.0.113.7`, not `203.0.113.7:1234`), no
   brackets, no hostnames, no `unknown`/obfuscated tokens. For a multi-proxy edge, append
   comma-separated bare literals left-to-right within that single line, bounded by
   `app.edge.max-forwarded-hops` (default 8). The line is **required**, not optional:
   a trusted peer that sends no `X-Forwarded-For` is rejected with `400
   INVALID_FORWARDED_HEADER`, because attributing the proxy itself would authorize an
   unidentified client as a possibly allowlisted address. Direct (untrusted) clients
   send no forwarding headers and are attributed by socket peer.
3. **Never emit duplicate header lines** for `X-Forwarded-For`; duplicates are ambiguous
   and rejected.
4. Terminate TLS at (or before) the edge so the socket peer the application sees is the
   configured proxy; list every proxy hop CIDR in `APP_EDGE_TRUSTED_PROXIES`.

Runtime behavior on the application side:

- `server.forward-headers-strategy: NONE` — the container never rewrites the socket peer.
- Untrusted peer: all forwarding input ignored, peer attributed, request continues
  (with or without headers).
- Trusted peer, exactly one well-formed line: right-to-left walk past trusted hops;
  first untrusted hop is the client, shared by auth, allowlist, rate limiting, audit.
- Trusted peer with missing, malformed, or ambiguous input: `400
  INVALID_FORWARDED_HEADER` **before** authentication/routing. There is deliberately no
  fallback to the peer, because the proxy address itself may be allowlisted.

Staging note: no ingress definition is checked in (only `infra/docker-compose.yml`,
which defines no proxy). Staging header-stripping proof is externally blocked until the
deployment ingress is provided; see the H03 handoff.
