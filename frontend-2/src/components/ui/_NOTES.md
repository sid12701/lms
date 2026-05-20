# shadcn primitives — local notes

This project is **Tailwind v4 CSS-first**: the design tokens live in
`src/styles/tokens.css` (`@theme { ... }`) and there is **no
`tailwind.config.js`/`tailwind.config.ts`**. If running
`npx shadcn@latest add` ever materialises a `tailwind.config.ts` at the repo
root, **delete it** — the CSS-first config is authoritative.

## Install command

The full primitive set required by Agent A6 (Batch 2) was installed with:

```
npx shadcn@latest add button card dialog alert skeleton separator tooltip label form input
```

All ten primitives landed cleanly in `src/components/ui/`. No
`tailwind.config.ts` was generated; `src/lib/utils.ts` was untouched
(verified byte-for-byte against A1's canonical version).

## Patches

- `button.tsx` / `form.tsx`: appended a single `eslint-disable-next-line
react-refresh/only-export-components` directive on the trailing `export
{}` block. shadcn's primitives intentionally co-export non-component
  values (`buttonVariants`, the `useFormField` hook); this is fine for
  production but trips the strict React-Refresh lint with
  `max-warnings 0`. No runtime change.

The shadcn New-York generated files reference Tailwind v4 utility
classes that resolve through our `@theme` tokens (`bg-primary`,
`bg-destructive`, `text-muted-foreground`, etc.) — these aliases are
declared in `tokens.css` so all primitives render correctly without
modification.
