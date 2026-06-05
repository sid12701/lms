/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_LOGIN_BOOTSTRAP_PASSWORD?: string;
  readonly VITE_LOGIN_DEFAULT_PASSWORD?: string;
  readonly VITE_LOGIN_SYSTEM_ADMIN_USERNAME?: string;
  readonly VITE_LOGIN_OPS_USER_USERNAME?: string;
  readonly VITE_LOGIN_PRODUCT_ADMIN_USERNAME?: string;
  readonly VITE_LOGIN_LSP_UI_READ_USERNAME?: string;
  readonly VITE_LOGIN_LSP_UI_WRITE_USERNAME?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
