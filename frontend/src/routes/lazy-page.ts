import { lazy, type ComponentType, type LazyExoticComponent } from "react";

interface PageModule {
  default?: ComponentType;
  Component?: ComponentType;
}

export function lazyPage(load: () => Promise<PageModule>): LazyExoticComponent<ComponentType> {
  return lazy(async () => {
    const mod = await load();
    const Resolved = mod.Component ?? mod.default;
    if (!Resolved) {
      throw new Error("Lazy route module is missing a default or Component export");
    }
    return { default: Resolved };
  });
}
