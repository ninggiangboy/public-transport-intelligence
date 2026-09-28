# Single entry point for every stack (DOC-38 §4). Targets are added phase by phase.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f deploy/compose/compose.yaml --env-file deploy/versions.env --env-file .env
PROFILES ?= --profile core
# Provenance attestations embed a timestamp, which changes the image id on every build and makes compose recreate
# containers whose code did not change.
export BUILDX_NO_DEFAULT_ATTESTATIONS := 1

.PHONY: help
help: ## List available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_%.-]+:.*## / {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ---------------------------------------------------------------- lifecycle

.PHONY: secrets
secrets: ## Create .env with generated secrets and render deploy/compose/.generated/
	@deploy/compose/scripts/secrets.sh

.env:
	@echo ".env is missing; run 'make secrets' first" >&2; exit 1

.PHONY: images
images: .env ## Build every image (Dockerfiles through compose; Jib images from P1-14)
	$(COMPOSE) --profile '*' build

.PHONY: up
up: images ## Build images if needed, start the core profile and wait until every service is healthy
	$(COMPOSE) $(PROFILES) up -d --wait

.PHONY: down
down: .env ## Stop and remove containers, keep volumes
	$(COMPOSE) --profile '*' down

.PHONY: reset
reset: .env ## Remove containers and every volume (keeps .env)
	$(COMPOSE) --profile '*' down -v

# ---------------------------------------------------------------- observation

.PHONY: ps
ps: .env ## Container status and health
	$(COMPOSE) --profile '*' ps -a

.PHONY: logs
logs: .env ## Follow logs (S=<service>)
	$(COMPOSE) --profile '*' logs -f --tail=200 $(S)
