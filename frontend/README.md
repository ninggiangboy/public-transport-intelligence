# frontend

React 19 + Vite SPA for the dashboard (ADR-0020). Screen specs are in [docs/08-ux-ui/](../docs/08-ux-ui/), the folder
layout and routing rules in [DOC-34 §9](../docs/08-ux-ui/ux-principles-and-ia.md#9-kiến-trúc-frontend), the pinned
library lines in [DOC-11 §3](../docs/03-architecture/tech-stack-and-versions.md#3-frontend).

## Commands

Node 24 and pnpm 10 come from `mise install` in the repo root (or `corepack enable`, which reads `packageManager`).

| Command | What it does |
| --- | --- |
| `pnpm install` | Install dependencies from `pnpm-lock.yaml` |
| `pnpm dev` | Vite dev server on http://localhost:5173; `/api` is proxied to the api on http://localhost:8081 |
| `pnpm dev:mock` | Same dev server without a backend: MSW answers `/api` with the examples of `openapi.json` |
| `pnpm gen:api` | Regenerate `src/api/generated/` (types and examples) from `../backend/api/openapi.json`; CI fails when the committed files differ. `make openapi` in the repo root rewrites `openapi.json` first |
| `pnpm build` | Typecheck, then build to `dist/` (with `dist/.vite/manifest.json` for the bundle budget) |
| `pnpm preview` | Serve `dist/` on http://localhost:4173 |
| `pnpm lint` / `pnpm format` / `pnpm typecheck` | ESLint, Prettier, `tsc -b` |
| `pnpm test` | Vitest in watch mode; `pnpm test --run --coverage` is what CI runs |
| `pnpm e2e` | Playwright against `vite preview`, or against `E2E_BASE_URL` (the compose stack: http://localhost:8080) |

`pnpm exec playwright install chromium` downloads the browser once before the first `pnpm e2e`.

## Rules that lint enforces

- Every user-visible string lives in `src/i18n/en.ts` (`i18next/no-literal-string`, DR-48).
- Components never call `fetch` or open an `EventSource`; requests go through `src/api/`, events through `src/realtime/`.
- `src/features/*` do not import each other, and `src/components/` imports neither `features/` nor `api/`.
- No `dangerouslySetInnerHTML`.

## Design system

Tokens live in `src/styles/tokens.css` (the only file with hex colours) and are mapped to Tailwind utilities in
`globals.css`; shared components are in `src/components/` ([DOC-35](../docs/08-ux-ui/design-system.md)). Open
http://localhost:5173/_ui under `pnpm dev` to see every component and variant in light and dark. The page is not in the
image: a production build serves it only when built with `VITE_PTI_UI_CATALOG=true`, which `pnpm e2e` does for its axe
check (DS-01).

## API client

Calls go through `api` from `src/api/client.ts`, which is typed from the generated `paths`:

```ts
const { data, asOf } = await read(api.GET('/api/v1/stops/{stopId}', { params: { path: { stopId } } }));
```

`read` and `write` throw `ApiError` (Problem Details with its `slug` and `traceId`) on a non-2xx response. In tests,
`server.use(respond('get', '/api/v1/routes', body))` overrides one operation with a body that must match its type,
and `respondProblem(...)` answers with Problem Details.

Routes are files under `src/routes/` (TanStack Router); the Vite plugin regenerates `src/routeTree.gen.ts`, which is
committed so that `tsc` works on a fresh clone.

## Realtime

`RealtimeProvider` (inside `QueryClientProvider`) owns the one `GET /api/v1/stream` connection of the app (DOC-26 §8). A
screen declares what it needs with `useRealtime({ channels, routeIds })`; with no subscriber nothing is opened. Events
never reach components directly: `src/realtime/handlers.ts` patches or invalidates the TanStack Query cache, using the
keys of `src/api/keys.ts`, so screens must build their query keys with `keys`. Cached entries may be `read()` results
(`{ data, asOf }`), plain bodies or infinite-query pages; the handlers keep whichever shape is there. While the stream
is down for more than 5 s the provider polls the REST endpoints instead (`status` is `polling`).

The zod schemas in `src/realtime/schemas/` are hand-written from DOC-33 §5 until the backend publishes the JSON Schemas.
Tests use `FakeSseServer` from `src/test/sse.ts` (an injectable `fetch` whose streams the test pushes frames into).
The auth provider calls `useRealtimeControls().reconnect()` after a token refresh.

## Image

`frontend/Dockerfile` builds `pti-frontend`: the static build behind nginx, which also proxies `/api/` (SSE without
buffering) to the api and serves the PMTiles mounted at `/tiles/`. At startup `nginx/40-pti-runtime-config.sh` writes
`/env.js` and the CSP from the `PTI_*` variables (DOC-29 §3.5), so one image serves every environment. `make up` builds
it and starts it in the compose `core` profile on http://localhost:8080.
