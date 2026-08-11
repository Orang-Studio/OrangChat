
/// <reference types="vite/client" />
declare const __APP_VERSION__: string;
declare const __BUILD_TIME__: string;

interface ImportMetaEnv {
  readonly VITE_KLIPY_API_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
