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
      "react-refresh/only-export-components": [
        "warn",
        { allowConstantExport: true, allowExportNames: ["badgeVariants", "buttonVariants"] },
      ],
      "react/react-in-jsx-scope": "off",
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
    },
  },
  {
    files: [
      "**/*Table*.tsx",
      "**/DataTable.tsx",
      "**/components-sandbox.tsx",
      "**/ScheduleTable.tsx",
      "**/LoansTab.tsx",
    ],
    rules: {
      "react-hooks/incompatible-library": "off",
    },
  },
  {
    files: ["**/*-context.tsx", "**/session-context.tsx"],
    rules: {
      "react-refresh/only-export-components": "off",
    },
  },
  {
    files: ["**/*Dialog.tsx"],
    rules: {
      "react-hooks/incompatible-library": "off",
    },
  },
  {
    files: [
      "src/components/ui/**",
      "src/components/app/documents/DocumentChecklistRow.tsx",
      "src/components/app/lifecycle/TransitionDisabledTooltip.tsx",
      "src/features/alerts/components/AlertsTable.tsx",
      "src/features/loan-applications/components/detail-tabs/DocumentsTab.tsx",
    ],
    rules: {
      "react-refresh/only-export-components": "off",
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
