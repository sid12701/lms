## Codebase audit

Before answering architecture or codebase questions, and before making non-trivial changes, do a deep, proper, thorough audit of the relevant parts of the codebase. Read the actual code — trace the real call paths, data flow, and edge cases involved rather than relying on assumptions or surface-level pattern matching. Scope the audit to what's relevant to the task, but within that scope be exhaustive.

## Domain docs

Single-context layout: one `CONTEXT.md` and `docs/adr/` at the repo root. `CONTEXT.md` is the authority on domain language — including the terms it explicitly rejects — and `docs/adr/` records accepted decisions.

Product context (users, purpose, constraints, accessibility standard) lives in `PRODUCT.md` at the repo root.

## AXI tooling

Use `gh-axi` for GitHub and `chrome-devtools-axi` for browser automation.
