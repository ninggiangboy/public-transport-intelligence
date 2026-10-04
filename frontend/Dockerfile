# syntax=docker/dockerfile:1
# pti-frontend (DOC-41 §5): the static build served by nginx, about 30 MB (ADR-0020). Runtime configuration comes
# from PTI_* variables at startup (nginx/40-pti-runtime-config.sh), so one image serves every environment.

FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
WORKDIR /app
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0 CI=true
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pti-pnpm-store,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY . .
# Hashed assets are gzipped once here, at the level the bundle budgets are measured at (DOC-34 §7), and served with
# gzip_static instead of being compressed per request at nginx's default level 1.
RUN pnpm build && rm -rf dist/.vite \
  && find dist/assets -type f \( -name '*.js' -o -name '*.css' -o -name '*.svg' -o -name '*.json' \) \
    -exec gzip -9 -k -n {} +

FROM nginx:1.31.0-alpine@sha256:2f07d83bf561b506400dc183b1b2003803e39efbd22451f848adaba14d28c7c7
# The entrypoint exports the container's DNS servers as NGINX_LOCAL_RESOLVERS for the resolver directive, and sizes
# worker_processes to the CPU quota: one worker per host core would not fit the 32 MB of DOC-39.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE=1
COPY nginx/default.conf.template /etc/nginx/pti/default.conf.template
COPY --chmod=755 nginx/40-pti-runtime-config.sh /docker-entrypoint.d/40-pti-runtime-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
