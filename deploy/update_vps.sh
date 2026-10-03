#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f docker-compose.no-domain.yml ]]; then
  docker compose -f docker-compose.no-domain.yml up -d --build --remove-orphans
  docker compose -f docker-compose.no-domain.yml ps
else
  docker compose up -d --build --remove-orphans
  docker compose ps
fi
