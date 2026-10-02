import '@/styles/globals.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { AppProviders } from '@/app/providers';

async function main() {
  // Vite replaces MODE at build time, so the production bundle drops this branch and the MSW worker with it.
  if (import.meta.env.MODE === 'mock') {
    const { startMockApi } = await import('@/mocks/browser');
    await startMockApi();
  }
  const root = document.getElementById('root');
  if (!root) throw new Error('index.html has no #root element');
  createRoot(root).render(
    <StrictMode>
      <AppProviders />
    </StrictMode>,
  );
}

void main();
