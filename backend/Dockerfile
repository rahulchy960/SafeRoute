# SPDX-License-Identifier: AGPL-3.0-only
#
# saferoute-api container image (P006, ADR 0007). Build context: backend/.
#
#   docker buildx build --load --build-arg GIT_SHA=$(git rev-parse HEAD) -t saferoute-api:local backend
#
# One image, three commands:
#   node dist/server.js             the API (default)
#   node dist/db/migrate.js         the migration Cloud Run Job
#   node dist/scripts/set-role.js   the admin Cloud Run Job
#
# Nothing sensitive is baked in. Configuration and secrets arrive as environment variables at
# runtime (backend/README.md "Environment variables"). Check a build with scripts/container-smoke.mjs.

# Node 24 (Active LTS, same major as .nvmrc) on Debian 13 slim, pinned by tag AND multi-arch digest.
# To update: `docker buildx imagetools inspect node:<tag>`, change both parts, rerun the smoke test.
ARG NODE_IMAGE=node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe

# ── deps: production dependencies only ────────────────────────────────────────────────────────
FROM ${NODE_IMAGE} AS deps
WORKDIR /app
# Corepack (bundled with Node 24) installs the pnpm version named in package.json "packageManager".
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --prod

# ── build: adds the dev dependencies and compiles src/ → dist/ ────────────────────────────────
FROM deps AS build
RUN pnpm install --frozen-lockfile
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN pnpm build

# ── runtime: what is deployed ─────────────────────────────────────────────────────────────────
FROM ${NODE_IMAGE} AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=deps /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
# src/db/migrate.ts resolves ../../drizzle from dist/db/, so the migrations sit next to dist/.
COPY drizzle ./drizzle
COPY package.json ./

# The base image's unprivileged user (uid 1000). The files above stay owned by root: the process
# can read them but not change them.
USER node
EXPOSE 8080

# Last, so a new commit only rebuilds these metadata layers. The git SHA is public, not a secret.
# APP_VERSION is what GET /health reports as `version`; the deploy may override both at runtime.
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA} \
    APP_VERSION=${GIT_SHA}
LABEL org.opencontainers.image.title="saferoute-api" \
      org.opencontainers.image.source="https://github.com/rahulchy960/SafeRoute" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}" \
      org.opencontainers.image.licenses="AGPL-3.0-only"

# Exec form: node is PID 1 and receives Cloud Run's SIGTERM directly (graceful shutdown, server.ts).
CMD ["node", "dist/server.js"]
