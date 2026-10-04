import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';

import { server } from '@/test/server';

// jsdom lacks these; cmdk (the search dialog) and Radix use them for layout only.
if (!('ResizeObserver' in globalThis)) {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = () => undefined;
      unobserve = () => undefined;
      disconnect = () => undefined;
    },
  );
}
if (!('scrollIntoView' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'scrollIntoView', { value: () => undefined, configurable: true });
}

// jsdom has no canvas: charts get an inert ECharts that records nothing (their table view is tested instead).
vi.mock('@/components/charts/echarts', () => ({
  initChart: () => ({ setOption: () => undefined, resize: () => undefined, dispose: () => undefined }),
}));

// jsdom has no WebGL either: the live map draws on a double that records its layers (src/test/map.tsx).
vi.mock('@/features/map/components/MapCanvas', () => import('@/test/map'));

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
  cleanup();
});

afterAll(() => {
  server.close();
});
