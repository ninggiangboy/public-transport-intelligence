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

.PHONY: doctor
doctor: ## Check tools, Docker, VM memory, disk, .env and host ports; exits 1 on any FAIL
	@deploy/compose/scripts/doctor.sh

.PHONY: secrets
secrets: ## Create .env with generated secrets and render deploy/compose/.generated/
	@deploy/compose/scripts/secrets.sh

.env:
	@echo ".env is missing; run 'make secrets' first" >&2; exit 1

.PHONY: images
images: .env ## Build every image: Jib for the Java apps, Dockerfiles through compose
	cd backend && ./gradlew --quiet jibDockerBuild
	$(COMPOSE) --profile '*' build

# One-shot jobs; wait-stack.sh requires them to exit 0 (compose's own --wait mishandles them).
JOBS := kafka-init s3-init db-migrate kafka-connect-init

.PHONY: up
up: images ## Build images if needed, start the core profile, wait for health and for every one-shot job
	$(COMPOSE) $(PROFILES) up -d
	@JOBS="$(JOBS)" deploy/compose/scripts/wait-stack.sh $(COMPOSE) --profile '*'

.PHONY: up-obs
up-obs: images ## Like up, plus the observability profile (Prometheus, Grafana, Loki, Tempo, Alertmanager, Mailpit) and tracing
	PTI_TRACING_ENABLED=true $(COMPOSE) --profile core --profile observability up -d
	@JOBS="$(JOBS)" deploy/compose/scripts/wait-stack.sh $(COMPOSE) --profile '*'
	@echo "Grafana http://localhost:$$(v=$$(sed -n 's/^HOST_PORT_GRAFANA=//p' .env); echo "$${v:-3000}") (admin, GRAFANA_ADMIN_PASSWORD in .env)"

.PHONY: up-exp
up-exp: images ## Core, experiment and observability for EXP-01…04: ETL through Toxiproxy, simulator emitting (DOC-45)
	PTI_TRACING_ENABLED=true PTI_WAREHOUSE_HOST=toxiproxy PTI_SIM_START_RATE=1 \
		$(COMPOSE) --profile core --profile experiment --profile observability up -d
	@JOBS="$(JOBS)" deploy/compose/scripts/wait-stack.sh $(COMPOSE) --profile '*'

.PHONY: reset-warehouse
reset-warehouse: .env ## Drop and recreate pti_warehouse, migrate, restart the apps; keeps Kafka and the raw zone [OFFSETS=earliest]
	@OFFSETS="$(OFFSETS)" deploy/compose/scripts/reset-warehouse.sh $(COMPOSE)

.PHONY: replay
replay: .env ## Queue a raw-zone replay: SOURCE=<etl_source> FROM=<ISO> TO=<ISO> [RECOMPUTE=true] [WAIT=1]
	@SOURCE="$(SOURCE)" FROM="$(FROM)" TO="$(TO)" RECOMPUTE="$(RECOMPUTE)" WAIT="$(WAIT)" \
		deploy/compose/scripts/replay.sh $(COMPOSE)

.PHONY: job-run
job-run: .env ## Queue a batch job: NAME=<Job> [PARAMS='k=v,…'] [WAIT=1]
	@NAME="$(NAME)" PARAMS="$(PARAMS)" WAIT="$(WAIT)" deploy/compose/scripts/job-run.sh $(COMPOSE)

.PHONY: stop-apps start-apps
APPS = etl-stream etl-batch etl-stream-baseline api triage-worker
# Only apps that have a container: api and triage-worker join the stack in P4 and P6.
EXISTING_APPS = $$(for a in $(APPS); do [ -n "$$($(COMPOSE) --profile '*' ps -a -q $$a 2>/dev/null)" ] && echo $$a; done)
stop-apps: .env ## Stop the apps and keep the infrastructure (DOC-43 §4.1)
	@$(COMPOSE) --profile '*' stop $(EXISTING_APPS)

start-apps: .env ## Start the apps stopped by stop-apps
	@$(COMPOSE) --profile '*' start $(EXISTING_APPS)

.PHONY: backup backup-verify restore-warehouse ensure-partitions
backup: .env ## pg_dump the warehouse (without VP facts), ticketing_source and pti_sim into backups/<ts>/ (DOC-43 §3.1)
	@deploy/compose/scripts/backup.sh $(COMPOSE)

backup-verify: .env ## Check a backup: SHA-256, pg_restore --list, test restore and row counts [TS=<dir>] (DOC-43 §3.3)
	@TS="$(TS)" deploy/compose/scripts/backup-verify.sh $(COMPOSE)

restore-warehouse: .env ## Recreate pti_warehouse from backups/<TS>/ and restart the apps (DOC-43 §4.2)
	@TS="$(TS)" deploy/compose/scripts/restore-warehouse.sh $(COMPOSE)

ensure-partitions: .env ## Create fact partitions from FROM=<YYYY-MM-DD> to today + 7 (DOC-43 §4.1)
	@FROM="$(FROM)" deploy/compose/scripts/ensure-partitions.sh $(COMPOSE)

.PHONY: check-dashboards
check-dashboards: .env ## Run every query of the Grafana dashboards and report errors or empty panels (DOC-28 O-09)
	@GRAFANA_URL=http://localhost:$$(v=$$(sed -n 's/^HOST_PORT_GRAFANA=//p' .env); echo "$${v:-3000}") \
		GRAFANA_PASSWORD=$$(sed -n 's/^GRAFANA_ADMIN_PASSWORD=//p' .env) deploy/compose/observability/check-dashboards.py $(ARGS)

.PHONY: down
down: .env ## Stop and remove containers, keep volumes
	$(COMPOSE) --profile '*' down

.PHONY: reset
reset: .env ## Remove containers and every volume (keeps .env)
	$(COMPOSE) --profile '*' down -v

.PHONY: restart
restart: .env ## Restart one service (S=<service>)
	@test -n "$(S)" || { echo "usage: make restart S=<service>" >&2; exit 2; }
	$(COMPOSE) --profile '*' restart $(S)

# ---------------------------------------------------------------- observation

.PHONY: ps
ps: .env ## Container status and health
	$(COMPOSE) --profile '*' ps -a

.PHONY: logs
logs: .env ## Follow logs (S=<service>)
	$(COMPOSE) --profile '*' logs -f --tail=200 $(S)

# psql inside the container over the local socket, which the Postgres image trusts, so no password is handled here.
# SU=1 connects as the superuser; Q='<sql>' runs one statement and exits.
PSQL_FLAGS = $(if $(Q),-c "$(Q)")
PSQL = $(COMPOSE) exec $(if $(Q),-T) $(1) psql -U $(if $(SU),postgres,$(3)) -d $(2) $(PSQL_FLAGS)

.PHONY: psql-wh psql-src psql-sim
psql-wh: .env ## psql into pti_warehouse as pti_owner [SU=1] [Q='<sql>']
	@$(call PSQL,pg-warehouse,pti_warehouse,pti_owner)

psql-src: .env ## psql into ticketing_source as ticketing_owner [SU=1] [Q='<sql>']
	@$(call PSQL,pg-source,ticketing_source,ticketing_owner)

psql-sim: .env ## psql into pti_sim as sim_owner [SU=1] [Q='<sql>']
	@$(call PSQL,pg-source,pti_sim,sim_owner)

# Kafka CLI tools run in the broker container with a small heap of their own, not the broker's KAFKA_HEAP_OPTS.
KAFKA_EXEC = $(COMPOSE) exec -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin
KAFKA_BOOTSTRAP = --bootstrap-server kafka:9092

.PHONY: topics
topics: .env ## Topics with partition counts, then the lag of every consumer group
	@$(KAFKA_EXEC)/kafka-topics.sh $(KAFKA_BOOTSTRAP) --describe --exclude-internal | grep -E '^Topic:' \
		| awk '{printf "%-36s partitions=%s\n", $$2, $$6}'
	@echo
	@$(KAFKA_EXEC)/kafka-consumer-groups.sh $(KAFKA_BOOTSTRAP) --describe --all-groups 2>/dev/null \
		|| echo "No consumer groups yet."

.PHONY: tail-%
tail-%: .env ## Print new messages of a topic with key and headers, e.g. make tail-gtfs.trip_updates
	@$(KAFKA_EXEC)/kafka-console-consumer.sh $(KAFKA_BOOTSTRAP) --topic $* \
		--formatter-property print.timestamp=true --formatter-property print.key=true --formatter-property print.headers=true

.PHONY: connectors
connectors: .env ## Kafka Connect connectors and task states
	@$(COMPOSE) exec -T kafka-connect curl -fsS 'http://localhost:8083/connectors?expand=status' | python3 -c \
		'import json, sys; [print("{:<28} {:<9} tasks: {}".format(n, c["status"]["connector"]["state"], " ".join(t["state"] for t in c["status"]["tasks"]) or "-")) for n, c in sorted(json.load(sys.stdin).items())]'

.PHONY: s3-ls
s3-ls: .env ## List objects in the raw bucket [P=<prefix>]
	@$(COMPOSE) run --rm --no-deps -T --entrypoint bash s3-init -c \
		'AWS_ACCESS_KEY_ID="$$S3_ADMIN_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$$S3_ADMIN_SECRET_KEY" AWS_DEFAULT_REGION=us-east-1 \
		aws --endpoint-url http://seaweedfs:8333 s3 ls --recursive "s3://raw/$(P)"'

.PHONY: s3-shell
s3-shell: .env ## Shell with the AWS CLI on the raw zone as admin (versions, restores: DOC-43 §4.5); aws needs $$S3
	@$(COMPOSE) run --rm --no-deps --entrypoint bash \
		-e AWS_DEFAULT_REGION=us-east-1 -e S3='--endpoint-url http://seaweedfs:8333' s3-init -c \
		'export AWS_ACCESS_KEY_ID="$$S3_ADMIN_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$$S3_ADMIN_SECRET_KEY"; exec bash'

# ---------------------------------------------------------------- simulator (DOC-25 §8)

SIM_URL = http://localhost:$$(v=$$(sed -n 's/^HOST_PORT_SIM=//p' .env); echo "$${v:-8084}")/sim
# PUT /sim/rate with the JSON in $$body; prints the new rates, or the Problem Details and fails.
SIM_PUT_RATE = set -o pipefail; curl -sS -X PUT -H 'Content-Type: application/json' -d "$$body" $(SIM_URL)/rate \
	| python3 -c 'import json, sys; d = json.load(sys.stdin); r = d.get("rate"); \
	print("gtfsRt={gtfsRt} ticketing={ticketing}".format(**r)) if r else sys.exit(json.dumps(d, indent=2))'

.PHONY: clock-offset
clock-offset: .env ## Shift the business clock so that Chicago time is now AT=<HH:MM>, or AT=now for no shift; then make up
	@deploy/compose/scripts/clock-offset.sh "$(AT)"

.PHONY: sim-status
sim-status: .env ## GET /sim/status
	@set -o pipefail; curl -sS --fail-with-body $(SIM_URL)/status | python3 -m json.tool

.PHONY: sim-start
sim-start: .env ## Start emitting: both rates to 1, or [GTFS=<x>] [TICKETING=<y>] (not kept across restarts)
	@body='{"gtfsRt": $(or $(GTFS),1), "ticketing": $(or $(TICKETING),1)}'; $(SIM_PUT_RATE)

.PHONY: sim-stop
sim-stop: .env ## Stop emitting; the container keeps running and live data goes stale in about 2 minutes
	@body='{"gtfsRt": 0, "ticketing": 0}'; $(SIM_PUT_RATE)

.PHONY: sim-rate
sim-rate: .env ## Set the rate multipliers: GTFS=<x> and/or TICKETING=<y> (0, or 0.1 to 20)
	@body=$$(python3 -c 'import json, sys; print(json.dumps({k: float(v) for k, v in zip(("gtfsRt", "ticketing"), sys.argv[1:]) if v}))' \
		'$(GTFS)' '$(TICKETING)'); $(SIM_PUT_RATE)

.PHONY: scenarios
scenarios: .env ## List the scenarios and their parameters (GET /sim/scenarios)
	@set -o pipefail; curl -sS --fail-with-body $(SIM_URL)/scenarios | python3 -c 'import json, sys; \
	[print(s["name"].ljust(14), ", ".join(p["name"] + ("*" if p["required"] else "") for p in s["params"])) \
	 for s in json.load(sys.stdin)["items"]]'

.PHONY: scenario
scenario: .env ## Start a scenario: NAME=<name> [ARGS='<json>'], e.g. NAME=bunching ARGS='{"routeId":"18"}'
	@test -n "$(NAME)" || { echo "Usage: make scenario NAME=<name> [ARGS='<json>']" >&2; exit 2; }
	@set -o pipefail; curl -sS --fail-with-body -X POST -H 'Content-Type: application/json' \
		-H 'X-Requested-By: cli' -d '$(or $(ARGS),{})' $(SIM_URL)/scenarios/$(NAME) | python3 -m json.tool

.PHONY: scenario-stop
scenario-stop: .env ## Stop every running run of a scenario: NAME=<name>
	@test -n "$(NAME)" || { echo "Usage: make scenario-stop NAME=<name>" >&2; exit 2; }
	@curl -sS --fail-with-body -X DELETE $(SIM_URL)/scenarios/$(NAME) && echo "Stopped $(NAME)"

# ---------------------------------------------------------------- development

.PHONY: fmt
fmt: ## Format the code (Spotless, Prettier)
	cd backend && ./gradlew --quiet spotlessApply
	@if [ -f frontend/package.json ]; then pnpm -C frontend format; fi

.PHONY: lint
lint: ## Static checks: Spotless, Checkstyle, ESLint, tsc
	cd backend && ./gradlew --quiet spotlessCheck checkstyleMain checkstyleTest
	@if [ -f frontend/package.json ]; then pnpm -C frontend lint && pnpm -C frontend exec tsc --noEmit; fi

.PHONY: test
test: ## Unit tests of every module
	cd backend && ./gradlew --quiet test
	@if [ -f frontend/package.json ]; then pnpm -C frontend test; fi

.PHONY: it
it: ## Integration and contract tests (Testcontainers; needs Docker, not the compose stack)
	cd backend && ./gradlew --quiet integrationTest contractTest
