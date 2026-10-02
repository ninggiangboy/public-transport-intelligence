/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Build-time switch that keeps the `/_ui` design-system catalogue in a production build (DOC-35 §10). */
  readonly VITE_PTI_UI_CATALOG?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
