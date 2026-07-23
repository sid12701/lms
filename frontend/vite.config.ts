import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    manifest: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return undefined;
          const normalizedId = id.split(path.sep).join("/");
          if (/node_modules\/(react|react-dom|scheduler)\//.test(normalizedId)) {
            return "vendor-react";
          }
          if (normalizedId.includes("@radix-ui") || normalizedId.includes("@floating-ui")) {
            return "vendor-ui";
          }
          if (normalizedId.includes("@tanstack")) return "vendor-data";
          if (normalizedId.includes("date-fns") || normalizedId.includes("react-day-picker")) {
            return "vendor-date";
          }
          // Everything else is left to Rollup, which places a module by where it
          // is actually reachable from. A catch-all "vendor" chunk here would
          // force route-specific dependencies into an eagerly preloaded chunk:
          // recharts' tree alone (lodash, d3-*, react-transition-group and ~20
          // more) is chart-only and reachable solely from the lazy dashboard
          // route, but a catch-all shipped it to every page.
          return undefined;
        },
      },
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    css: true,
    fileParallelism: false,
    testTimeout: 15_000,
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/**/*.{test,spec}.{ts,tsx}",
        "src/main.tsx",
        "src/vite-env.d.ts",
        "src/test/**",
      ],
    },
  },
});
