import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import react from "eslint-plugin-react";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import jsxA11y from "eslint-plugin-jsx-a11y";
import prettierConfig from "eslint-config-prettier";

export default tseslint.config(
  {
    ignores: ["dist", "build", "coverage", "playwright-report", "test-results", "node_modules"],
  },
  {
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      react.configs.flat.recommended,
      react.configs.flat["jsx-runtime"],
      jsxA11y.flatConfigs.recommended,
    ],
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.node },
    },
    settings: {
      react: { version: "detect" },
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // shadcn pattern (Badge/Button/etc. co-export their cva variants) and
      // intentional cell-renderer factories trigger this; not actionable.
      "react-refresh/only-export-components": "off",
      "react/react-in-jsx-scope": "off",
      // Heuristic from eslint-plugin-react-hooks 7.x. Legitimate uses in this
      // codebase (dialog form resets on `open=false`, syncing input state with
      // URL search params, focus-on-mount via setTimeout) are intentional and
      // align with React's own "You might not need an effect" guidance. Revisit
      // if these patterns are refactored to controlled-from-parent.
      "react-hooks/set-state-in-effect": "off",
      // React Compiler can't memoize TanStack Table's useReactTable; this rule
      // surfaces that as a warning on every table. Informational, not a bug.
      "react-hooks/incompatible-library": "off",
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
    },
  },
  {
    files: ["e2e/**/*.{ts,tsx}", "playwright.config.ts"],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
  prettierConfig,
);
