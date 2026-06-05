import { RouterProvider } from "react-router-dom";
import { Providers } from "./providers";
import { AxeRuntime } from "./axe-runtime";
import { ErrorBoundary } from "./error-boundary";
import { createAppRouter } from "@/routes/router";

const router = createAppRouter();

export function App() {
  return (
    <ErrorBoundary>
      <Providers>
        <AxeRuntime />
        <RouterProvider router={router} />
      </Providers>
    </ErrorBoundary>
  );
}
