# Fallow (frontend)

Static analysis for unused code, duplication, and complexity. Config: [`.fallowrc.json`](../../.fallowrc.json).

## Agent / CI rules

1. **Never run `fallow fix --yes` without a reviewed dry-run.** Bulk auto-fix can remove `Component` exports that [`lazyPage`](../../src/routes/lazy-page.ts) reads at runtime (`mod.Component ?? mod.default`), which breaks lazy-loaded routes.
2. Prefer **`fallow fix --dry-run --format json`** (or per-issue manual edits) after triage.
3. Use **`--format json --quiet`** for machine-readable output; exit code `1` means issues found, not tool failure.
4. **Accessibility and CSS build tools stay in `devDependencies`.** `axe-core` and `@axe-core/react` are loaded only behind the development-only gate in [`axe-runtime.tsx`](../../src/app/axe-runtime.tsx). `tailwindcss` and `shadcn` are consumed by Vite/CSS processing and component generation, not by the deployed JavaScript runtime. They are listed in `ignoreDependencies` so Fallow does not recommend moving build-time tooling into production dependencies.

## Common commands

```bash
cd frontend
npx fallow dead-code --format json
npx fallow dupes --format json
npx fallow health --format json
npx fallow fix --dry-run --format json
```

Reports from audits may be stored under `.fallow-reports/` (local only; not required for CI).
