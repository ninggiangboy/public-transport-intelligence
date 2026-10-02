import { createFileRoute, notFound } from '@tanstack/react-router';

import { UiCatalog } from '@/catalog/UiCatalog';

/**
 * The design-system catalogue is a development tool (DOC-35 §10): it exists in the dev server and in builds made with
 * VITE_PTI_UI_CATALOG=true (the e2e build); the image build leaves the variable unset, so it answers 404 there.
 * Read at navigation time rather than at import time so a test can switch it.
 */
export function isUiCatalogEnabled(): boolean {
  return import.meta.env.DEV || import.meta.env.VITE_PTI_UI_CATALOG === 'true';
}

// The brackets escape the underscore: a bare leading `_` would make this file a pathless layout route.
export const Route = createFileRoute('/_ui')({
  beforeLoad: () => {
    if (!isUiCatalogEnabled()) throw notFound();
  },
  component: UiCatalog,
});
