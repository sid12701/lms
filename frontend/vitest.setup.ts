import "@testing-library/jest-dom/vitest";
import { expect } from "vitest";
import * as matchers from "vitest-axe/matchers";

expect.extend(matchers);

function createMemoryStorage(): Storage {
  const entries = new Map<string, string>();
  return {
    get length() {
      return entries.size;
    },
    clear() {
      entries.clear();
    },
    getItem(key) {
      return entries.get(key) ?? null;
    },
    key(index) {
      return [...entries.keys()][index] ?? null;
    },
    removeItem(key) {
      entries.delete(key);
    },
    setItem(key, value) {
      entries.set(key, String(value));
    },
  };
}

// Node 26 exposes an unconfigured global `localStorage` that shadows jsdom's
// implementation and resolves to undefined unless a CLI file is supplied.
// Install a deterministic Storage implementation for browser-facing tests.
const localStorage = createMemoryStorage();
Object.defineProperty(window, "localStorage", { configurable: true, value: localStorage });
Object.defineProperty(globalThis, "localStorage", { configurable: true, value: localStorage });
