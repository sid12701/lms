/**
 * Bridge a live-backend Session into the in-process mock db so the features
 * that have not yet been wired to the real backend continue to work as
 * before.
 *
 * Wiring is feature-by-feature (see docs/INTEGRATION-STATUS.md). Until every
 * feature is migrated, this bridge keeps the legacy mock layer functional
 * for whichever surfaces still use it — the alternative is broken pages
 * after sign-in.
 */
import type { Session } from "@/mocks/api/auth";
import { bootstrapMockApi } from "@/mocks/api";
import { getDb } from "@/mocks/db/state";
import { SEED_USERS } from "@/mocks/db/seed";

function pickSeedFor(session: Session): (typeof SEED_USERS)[number] | undefined {
  const byUsername = SEED_USERS.find((candidate) => candidate.username === session.user.username);
  if (byUsername) return byUsername;
  return SEED_USERS.find((candidate) => candidate.role === session.user.role);
}

export function syncMockSession(session: Session): void {
  try {
    bootstrapMockApi();
    const db = getDb();
    const seed = pickSeedFor(session);
    if (!seed) {
      db.currentSession = null;
      return;
    }
    db.currentSession = {
      userId: seed.id,
      accessToken: session.accessToken,
      expiresAt: session.expiresAt,
    };
  } catch {
    // Mock-bridge errors are non-fatal — wired features always work,
    // unwired ones simply lose their session signal.
  }
}

export function clearMockSession(): void {
  try {
    const db = getDb();
    db.currentSession = null;
  } catch {
    // ignore
  }
}
