/**
 * H22 isolated HTTP harness: same-origin Vite-served frontend modules +
 * real auth endpoints with actual Set-Cookie headers (real browser jar).
 * No page.route. Server-family PostgreSQL semantics are owned by H21 and
 * are NOT exercised here — this harness proves cookie transport ordering
 * only (barrier-held overlapping exchanges, queueing, two-tab propagation,
 * simultaneous intents, close-peer fail-closed) while running production
 * coordinator/service/provider logic.
 *
 * Coordination model (no sleep-based race proof):
 * - `POST /__harness/hold {slot}` arms a one-shot gate for the next
 *   request of that slot ("refresh" | "logout" | "login"). The request's
 *   RESPONSE HEADERS are withheld until `POST /__harness/release {slot}`.
 * - `GET /__harness/milestones` returns server-observed request ARRIVALS
 *   (proving what was/wasn't sent and when), RESPONSES (proving header
 *   order), and PEERGONE (peer teardowns observed while a hold was consumed).
 *   Ordering proof comes from these timestamps, not client sleeps.
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createServer as createViteServer, type ViteDevServer } from "vite";

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(here, "..");

export type HarnessSlot = "refresh" | "logout" | "login";

export interface HarnessArrival {
  type: HarnessSlot | "context";
  at: string;
  receivedCookie: string;
}

export interface HarnessResponse {
  type: HarnessSlot | "context";
  at: string;
  setCookie: string | null;
  status: number;
}

export interface HarnessPeerGone {
  type: HarnessSlot;
  at: string;
}

export interface HarnessUser {
  id: string;
  username: string;
  role: string;
  lspId: string | null;
}

export type HarnessControl = {
  refreshMode?: "success" | "rotated" | "revoked";
  loginBodyMode?: "normal" | "malformed";
};

const DEFAULT_USER: HarnessUser = {
  id: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  username: "ops.admin",
  role: "SYSTEM_ADMIN",
  lspId: null,
};

function userForEmail(email: string): HarnessUser {
  if (email.includes("c-")) {
    return {
      id: "aaaaaaaa-cccc-4aaa-8aaa-aaaaaaaaaaaa",
      username: "c.user",
      role: "SYSTEM_ADMIN",
      lspId: null,
    };
  }
  if (email.includes("b-")) {
    return {
      id: "aaaaaaaa-bbbb-4aaa-8aaa-aaaaaaaaaaaa",
      username: "b.user",
      role: "SYSTEM_ADMIN",
      lspId: null,
    };
  }
  return DEFAULT_USER;
}

export interface HarnessState {
  counter: number;
  refreshMode: "success" | "rotated" | "revoked";
  /** Refresh-cookie value -> identity it belongs to. */
  cookies: Map<string, HarnessUser>;
  /** Access token -> identity it belongs to. */
  tokens: Map<string, HarnessUser>;
  arrivals: HarnessArrival[];
  responses: HarnessResponse[];
  /** Peer teardowns observed while a one-shot hold withheld the response. */
  peerGone: HarnessPeerGone[];
  /** Armed one-shot holds per slot (consumed by the next request). */
  armed: Record<HarnessSlot, number>;
  /** Waiters blocked on a consumed header hold, per slot. */
  waiters: Record<HarnessSlot, Array<() => void>>;
  /** Waiters blocked on a consumed body hold, per slot. */
  /** Armed one-shot body holds per slot (headers already sent). */
  bodyArmed: Record<HarnessSlot, number>;
  loginBodyMode: "normal" | "malformed";
}

export function createHarnessState(): HarnessState {
  return {
    counter: 0,
    refreshMode: "success",
    cookies: new Map(),
    tokens: new Map(),
    arrivals: [],
    responses: [],
    peerGone: [],
    armed: { refresh: 0, logout: 0, login: 0 },
    waiters: { refresh: [], logout: [], login: [] },
    bodyArmed: { refresh: 0, logout: 0, login: 0 },
    bodyWaiters: { refresh: [], logout: [], login: [] },
    loginBodyMode: "normal",
  };
}

function sendJson(
  res: http.ServerResponse,
  status: number,
  body: unknown,
  setCookie?: string,
  skipWriteHead = false,
): void {
  if (!skipWriteHead) {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (setCookie) headers["Set-Cookie"] = setCookie;
    res.writeHead(status, headers);
  }
  res.end(JSON.stringify(body));
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
    });
    req.on("end", () => resolve(data));
  });
}

function parseCookies(header: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq > 0) out.set(part.slice(0, eq).trim(), part.slice(eq + 1).trim());
  }
  return out;
}

/**
 * True only when the peer actually went away (connection dead or response
 * already finished). NOTE: `req.destroyed` is NOT usable here — Node
 * auto-destroys a fully-read keep-alive request stream while the peer is
 * still waiting, so it reads true for every healthy held request.
 */
function clientGone(req: http.IncomingMessage, res: http.ServerResponse): boolean {
  if (res.writableEnded) return true;
  const socket = req.socket;
  return !socket || socket.destroyed;
}

function isSlot(value: unknown): value is HarnessSlot {
  return value === "refresh" || value === "logout" || value === "login";
}

export async function startH22Harness(port = 0): Promise<{
  url: string;
  port: number;
  state: HarnessState;
  close: () => Promise<void>;
}> {
  const state = createHarnessState();
  let vite: ViteDevServer | null = null;

  /** Withhold response headers while a one-shot hold armed for slot is consumed.
   * While held, a peer teardown is recorded as a `peerGone` milestone on the
   * request socket — the same `socket.destroyed` predicate `clientGone` checks
   * after release — so tests can wait for the teardown BEFORE releasing instead
   * of racing it. */
  async function gateFor(slot: HarnessSlot, req: http.IncomingMessage): Promise<void> {
    if (state.armed[slot] === 0) return;
    state.armed[slot] -= 1;
    const socket = req.socket;
    const recordPeerGone = (): void => {
      state.peerGone.push({ type: slot, at: new Date().toISOString() });
    };
    if (socket.destroyed) {
      // Teardown already propagated before the hold was consumed.
      recordPeerGone();
    } else {
      socket.once("close", recordPeerGone);
    }
    await new Promise<void>((resolve) => {
      state.waiters[slot].push(() => {
        socket.removeListener("close", recordPeerGone);
        resolve();
      });
    });
  }

  /** Withhold response body while headers (incl. Set-Cookie) are already sent. */
  async function gateBodyFor(slot: HarnessSlot): Promise<void> {
    if (state.bodyArmed[slot] === 0) return;
    state.bodyArmed[slot] -= 1;
    await new Promise<void>((resolve) => {
      state.bodyWaiters[slot].push(resolve);
    });
  }

  async function sendLoginResponse(
    req: http.IncomingMessage,
    res: http.ServerResponse,
    user: HarnessUser,
    accessToken: string,
    cookieValue: string,
    setCookie: string,
  ): Promise<void> {
    state.responses.push({
      type: "login",
      at: new Date().toISOString(),
      setCookie,
      status: 200,
    });
    if (state.loginBodyMode === "malformed") {
      res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": setCookie });
      res.end("{not-json");
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": setCookie });
    await gateBodyFor("login");
    if (clientGone(req, res)) return;
    sendJson(
      res,
      200,
      {
        accessToken,
        tokenType: "Bearer",
        expiresInSeconds: 1800,
        passwordChangeRequired: false,
      },
      undefined,
      true,
    );
  }

  async function handle(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const host = req.headers.host ?? "127.0.0.1";
    const url = new URL(req.url ?? "/", `http://${host}`);
    const receivedCookie = String(req.headers.cookie ?? "");
    const authorization =
      typeof req.headers.authorization === "string" ? req.headers.authorization : null;

    if (req.method === "POST" && url.pathname === "/api/v1/auth/refresh") {
      await readBody(req);
      state.arrivals.push({ type: "refresh", at: new Date().toISOString(), receivedCookie });
      await gateFor("refresh", req);
      if (clientGone(req, res)) return;
      if (state.refreshMode === "rotated") {
        state.responses.push({
          type: "refresh",
          at: new Date().toISOString(),
          setCookie: null,
          status: 401,
        });
        sendJson(res, 401, { code: "TOKEN_ROTATED", message: "Direct parent benign loser" });
        return;
      }
      if (state.refreshMode === "revoked") {
        state.responses.push({
          type: "refresh",
          at: new Date().toISOString(),
          setCookie: null,
          status: 401,
        });
        sendJson(res, 401, { code: "TOKEN_REVOKED", message: "Family revoked" });
        return;
      }
      const presented = parseCookies(receivedCookie).get("lms-refresh") ?? null;
      const user = (presented && state.cookies.get(presented)) ?? DEFAULT_USER;
      state.counter += 1;
      const cookieValue = `harness-refresh-${state.counter}`;
      state.cookies.set(cookieValue, user);
      const accessToken = `harness-access-${state.counter}`;
      state.tokens.set(accessToken, user);
      const setCookie = `lms-refresh=${cookieValue}; Path=/api/v1/auth; HttpOnly; SameSite=Strict`;
      state.responses.push({
        type: "refresh",
        at: new Date().toISOString(),
        setCookie,
        status: 200,
      });
      sendJson(
        res,
        200,
        {
          accessToken,
          tokenType: "Bearer",
          expiresInSeconds: 1800,
          passwordChangeRequired: false,
        },
        setCookie,
      );
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/v1/auth/logout") {
      await readBody(req);
      state.arrivals.push({ type: "logout", at: new Date().toISOString(), receivedCookie });
      await gateFor("logout", req);
      if (clientGone(req, res)) return;
      const setCookie = `lms-refresh=; Path=/api/v1/auth; Max-Age=0; HttpOnly; SameSite=Strict`;
      state.responses.push({
        type: "logout",
        at: new Date().toISOString(),
        setCookie,
        status: 204,
      });
      res.writeHead(204, { "Set-Cookie": setCookie });
      res.end();
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/v1/auth/login") {
      const raw = await readBody(req);
      state.arrivals.push({ type: "login", at: new Date().toISOString(), receivedCookie });
      await gateFor("login", req);
      if (clientGone(req, res)) return;
      let email = "";
      try {
        email = String((JSON.parse(raw || "{}") as { email?: unknown }).email ?? "");
      } catch {
        email = "";
      }
      const user = userForEmail(email);
      state.counter += 1;
      const cookieValue = `harness-login-${state.counter}`;
      state.cookies.set(cookieValue, user);
      const accessToken = `harness-access-${state.counter}`;
      state.tokens.set(accessToken, user);
      const setCookie = `lms-refresh=${cookieValue}; Path=/api/v1/auth; HttpOnly; SameSite=Strict`;
      await sendLoginResponse(req, res, user, accessToken, cookieValue, setCookie);
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/v1/internal/system/context") {
      state.arrivals.push({ type: "context", at: new Date().toISOString(), receivedCookie });
      if (clientGone(req, res)) return;
      const presented = (authorization ?? "").replace(/^Bearer\s+/i, "");
      const user = state.tokens.get(presented) ?? DEFAULT_USER;
      state.responses.push({
        type: "context",
        at: new Date().toISOString(),
        setCookie: null,
        status: 200,
      });
      sendJson(res, 200, {
        application: "bhawana-lms",
        activeProfiles: ["harness"],
        id: user.id,
        username: user.username,
        roles: [user.role],
        correlationId: null,
        lspId: user.lspId,
        lspName: null,
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/__harness/hold") {
      const raw = await readBody(req);
      const slot = (JSON.parse(raw || "{}") as { slot?: unknown }).slot;
      if (!isSlot(slot)) {
        sendJson(res, 400, { error: "unknown slot" });
        return;
      }
      state.armed[slot] += 1;
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/__harness/release") {
      const raw = await readBody(req);
      const slot = (JSON.parse(raw || "{}") as { slot?: unknown }).slot;
      if (!isSlot(slot)) {
        sendJson(res, 400, { error: "unknown slot" });
        return;
      }
      const waiter = state.waiters[slot].shift();
      if (waiter) waiter();
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/__harness/hold-body") {
      const raw = await readBody(req);
      const slot = (JSON.parse(raw || "{}") as { slot?: unknown }).slot;
      if (!isSlot(slot)) {
        sendJson(res, 400, { error: "unknown slot" });
        return;
      }
      state.bodyArmed[slot] += 1;
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/__harness/release-body") {
      const raw = await readBody(req);
      const slot = (JSON.parse(raw || "{}") as { slot?: unknown }).slot;
      if (!isSlot(slot)) {
        sendJson(res, 400, { error: "unknown slot" });
        return;
      }
      const waiter = state.bodyWaiters[slot].shift();
      if (waiter) waiter();
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && url.pathname === "/__harness/milestones") {
      sendJson(res, 200, {
        arrivals: state.arrivals,
        responses: state.responses,
        peerGone: state.peerGone,
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/__harness/control") {
      const raw = await readBody(req);
      const control = JSON.parse(raw || "{}") as HarnessControl;
      if (control.refreshMode) state.refreshMode = control.refreshMode;
      if (control.loginBodyMode) state.loginBodyMode = control.loginBodyMode;
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/__harness/reset") {
      await readBody(req);
      const fresh = createHarnessState();
      state.counter = fresh.counter;
      state.refreshMode = fresh.refreshMode;
      state.cookies = fresh.cookies;
      state.tokens = fresh.tokens;
      state.arrivals = fresh.arrivals;
      state.responses = fresh.responses;
      state.peerGone = fresh.peerGone;
      state.armed = fresh.armed;
      state.waiters = fresh.waiters;
      state.bodyArmed = fresh.bodyArmed;
      state.bodyWaiters = fresh.bodyWaiters;
      state.loginBodyMode = fresh.loginBodyMode;
      sendJson(res, 200, { ok: true });
      return;
    }

    if (!vite) {
      sendJson(res, 503, { error: "harness vite not ready" });
      return;
    }

    if (req.method === "GET" && url.pathname === "/__harness/app.html") {
      const htmlPath = path.join(here, "h22-cookie-app.html");
      const raw = fs.readFileSync(htmlPath, "utf8");
      const transformed = await vite.transformIndexHtml(url.pathname, raw);
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(transformed);
      return;
    }

    // All other paths (frontend TS modules, vite client, etc.) via Vite.
    vite.middlewares(req, res, () => {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("not found");
    });
  }

  const server = http.createServer((req, res) => {
    void handle(req, res).catch((error) => {
      console.error("[h22-harness] handler error", error);
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain" });
      }
      res.end("harness error");
    });
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => {
      server.removeListener("error", reject);
      resolve();
    });
  });
  const actualPort = (server.address() as { port: number }).port;
  const origin = `http://127.0.0.1:${actualPort}`;

  // The API base the browser bundle uses must be THIS same origin so the
  // browser jar, cookie paths/flags, and same-origin guards are real. Set it
  // before the Vite server is created AND pin it via define so transforms
  // inline this instance's origin deterministically.
  process.env["VITE_API_BASE_URL"] = origin;
  vite = await createViteServer({
    root: frontendRoot,
    server: { middlewareMode: true },
    appType: "custom",
    define: {
      "import.meta.env.VITE_API_BASE_URL": JSON.stringify(origin),
    },
  });

  return {
    url: origin,
    port: actualPort,
    state,
    close: async () => {
      server.closeAllConnections();
      await new Promise<void>((resolve) => {
        server.close(() => resolve());
      });
      await vite?.close();
    },
  };
}
