/**
 * Persistence round-trip tests.
 */
import { beforeEach, describe, expect, it } from "vitest";
import { createEmptyDb } from "./state";
import { flushPendingSave, loadDb, saveDb, STORAGE_KEY, wipeDb } from "./persistence";
import { seedAuthFixtures, USER_OPS_ADMIN } from "./seed";

beforeEach(() => {
  wipeDb();
  window.localStorage.clear();
});

describe("persistence", () => {
  it("returns null when nothing is stored", () => {
    expect(loadDb()).toBeNull();
  });

  it("round-trips a seeded db (Maps preserved)", () => {
    const db = seedAuthFixtures(createEmptyDb());
    saveDb(db);
    flushPendingSave();

    const loaded = loadDb();
    expect(loaded).not.toBeNull();
    expect(loaded!.users.size).toBe(db.users.size);
    expect(loaded!.users.get(USER_OPS_ADMIN)?.username).toBe("ops.admin");
    // Phase 9: auth seed chains in 4 admin LSPs (8 total).
    expect(loaded!.lsps.size).toBe(8);
    expect(loaded!.products.size).toBe(5);
  });

  it("wipes on schema-version mismatch", () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 999, data: {} }));
    const loaded = loadDb();
    expect(loaded).toBeNull();
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("round-trips currentSession", () => {
    const db = seedAuthFixtures(createEmptyDb());
    db.currentSession = {
      userId: USER_OPS_ADMIN,
      accessToken: "mock.tok",
      expiresAt: "2099-01-01T00:00:00.000Z",
    };
    saveDb(db);
    flushPendingSave();

    const loaded = loadDb();
    expect(loaded?.currentSession?.userId).toBe(USER_OPS_ADMIN);
  });

  it("ignores corrupt JSON", () => {
    window.localStorage.setItem(STORAGE_KEY, "not-json{");
    expect(loadDb()).toBeNull();
  });
});
