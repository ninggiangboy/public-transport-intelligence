/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';

import tailwindcss from '@tailwindcss/vite';
import { tanstackRouter } from '@tanstack/router-plugin/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// DOC-34 §9; the dev server proxies /api to the api on the host so that requests stay same-origin (DOC-27 §5.2).
export default defineConfig({
  plugins: [
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
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: false },
    },
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
});
