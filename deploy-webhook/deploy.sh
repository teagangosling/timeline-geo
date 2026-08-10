#!/bin/bash
set -euo pipefail

# The branch this fork's work actually lands on. NOT `main`: origin/main still
# tracks upstream (Release 1.36.1) and carries none of the fork's commits, so
# deploying from it would reset the host checkout to upstream code and wipe the
# fork off the deploy host. Kept in one place and read by server.py too, so the
# branch that triggers a deploy and the branch that gets deployed cannot drift.
DEPLOY_BRANCH="${DEPLOY_BRANCH:-teagan/main}"

# The production stack is deploy/docker-compose.yml. A bare `docker compose`
# from /repo would pick up the repo-root docker-compose.yml instead - that is
# upstream's stack, which pulls prebuilt images and has no public-app and no
# deploy-webhook.
cd /repo/deploy

git fetch origin "$DEPLOY_BRANCH"
git reset --hard "origin/$DEPLOY_BRANCH"

# Compose otherwise derives the project name from the container's mount path
# (/repo/deploy -> "deploy"), not the host's actual directory name, which would
# spin up a parallel stack against fresh, empty volumes instead of updating the
# running one.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-geopulse}"

# Guard against exactly that. If the project name is wrong, `up -d` does not
# fail - it silently creates a new empty geopulse-db-data volume and brings
# Postgres up blank, which is indistinguishable from having lost the database.
# Refuse to deploy rather than let that happen unattended.
if ! docker volume inspect "${COMPOSE_PROJECT_NAME}_geopulse-db-data" >/dev/null 2>&1; then
  echo "[deploy] ABORT: no volume ${COMPOSE_PROJECT_NAME}_geopulse-db-data" >&2
  echo "[deploy] COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME} does not match the running stack." >&2
  echo "[deploy] Check with: docker volume ls | grep geopulse-db-data" >&2
  exit 1
fi

# geopulse-backend and geopulse-ui, not `app`: `app` is the retired timeline
# stack's service name and exists in neither compose file here, so this line
# used to abort the whole script under `set -e`.
docker compose build geopulse-backend geopulse-ui public-app
docker compose up -d geopulse-backend geopulse-ui public-app

# Each build orphans the previous images (~1.1GB per commit). Without this the
# root disk fills in a few months. Scoped by project label: an unfiltered
# `docker image prune -f` is host-wide and would collect dangling images
# belonging to every other stack on this host.
docker image prune -f --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}"
