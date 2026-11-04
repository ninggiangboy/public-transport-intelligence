import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import sirv from 'sirv';

const here = dirname(fileURLToPath(import.meta.url));
const tilesDir = resolve(here, '../site/tiles');

// The real extract is a gitignored symlink; sirv serves it with Range support (PMTiles needs 206s)
// for both `vite dev` and `vite preview`, so production numbers come from the real build.
function tiles() {
  const serve = sirv(tilesDir, {
    etag: false,
    maxAge: 0,
    immutable: false,
    setHeaders(res) { res.setHeader('Accept-Ranges', 'bytes'); },
  });
  return {
    name: 's07-tiles',
    configureServer(server) { server.middlewares.use('/tiles', serve); },
    configurePreviewServer(server) { server.middlewares.use('/tiles', serve); },
  };
}

export default defineConfig({
  plugins: [tiles()],
  build: {
    target: 'es2022',
    rollupOptions: {
      output: {
        // Keep the 3D cost directly measurable: motion.js, the deck.gl libs, and each impl get
        // their own chunk. maplibre / pmtiles / basemaps stay in the entry ("/map"-style) chunk.
        manualChunks(id) {
          if (
            id.includes('node_modules/@deck.gl') ||
            id.includes('node_modules/@luma.gl') ||
            id.includes('node_modules/@math.gl') ||
            id.includes('node_modules/@loaders.gl')
          ) {
            return 'deckgl';
          }
          if (id.replace(/\\/g, '/').endsWith('/src/motion.js')) return 'motion';
          return undefined;
        },
      },
    },
  },
});
