/**
 * Local type augmentation so vitest-axe's `toHaveNoViolations` matcher is
 * visible in `tsc --noEmit` and `tsc -b` runs.
 *
 * vitest-axe ships an `extend-expect.d.ts` that augments `Vi.Assertion`,
 * but Vitest 2's Assertion type (re-exported from chai) is not always
 * picked up by the build run depending on type resolution order. This
 * shim guarantees the matcher is on every assertion instance.
 */
import "vitest";

interface AxeMatchers<R = unknown> {
  toHaveNoViolations(): R;
}

declare module "vitest" {
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars
  interface Assertion<T = unknown> extends AxeMatchers {}
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type
  interface AsymmetricMatchersContaining extends AxeMatchers {}
}

declare global {
  namespace Vi {
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars
    interface Assertion<T = unknown> extends AxeMatchers {}
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type
    interface AsymmetricMatchersContaining extends AxeMatchers {}
  }
}

export {};
