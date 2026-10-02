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

Routes are files under `src/routes/` (TanStack Router); the Vite plugin regenerates `src/routeTree.gen.ts`, which is
committed so that `tsc` works on a fresh clone.
