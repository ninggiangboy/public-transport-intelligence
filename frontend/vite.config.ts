/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';

import tailwindcss from '@tailwindcss/vite';
import { tanstackRouter } from '@tanstack/router-plugin/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

// `pnpm dev:mock` (vite --mode mock) answers /api with MSW in the browser. Its service worker is served straight from
// node_modules, so it never lands in public/ and never ships in the image.
function mswWorker(): Plugin {
  const worker = fileURLToPath(new URL('./node_modules/msw/lib/mockServiceWorker.js', import.meta.url));
  return {
    name: 'pti-msw-worker',
    apply: (_config, { command, mode }) => command === 'serve' && mode === 'mock',
    configureServer(server) {
      server.middlewares.use('/mockServiceWorker.js', (_request, response) => {
        response.setHeader('Content-Type', 'text/javascript');
        response.end(readFileSync(worker));
      });
    },
  };
}

// DOC-34 §9; the dev server proxies /api to the api on the host so that requests stay same-origin (DOC-27 §5.2).
export default defineConfig(({ mode }) => ({
  plugins: [
    mswWorker(),
    // Must come before react(): it generates src/routeTree.gen.ts and splits every route into its own chunk.
    tanstackRouter({ target: 'react', autoCodeSplitting: true }),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: mode === 'mock' ? undefined : { '/api': { target: 'http://localhost:8081', changeOrigin: false } },
  },
  preview: {
    port: 4173,
    strictPort: true,
  },
  build: {
    // dist/.vite/manifest.json is read by the bundle budget check (DOC-34 §7, UX-08).
    manifest: true,
    target: 'es2023',
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/api/generated/**',
        'src/routeTree.gen.ts',
        'src/test/**',
        'src/main.tsx',
      ],
      // DOC-44 §7.
      thresholds: { lines: 70 },
    },
  },
}));
