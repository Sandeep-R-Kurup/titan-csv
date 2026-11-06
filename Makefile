# Shortcuts for building, loading, querying and demoing titan-csv.
# Run `make` (or `make help`) to list the targets.
# Every recipe sources env.sh so Java 8 is used even if your default java is newer.

SHELL  := /bin/bash
JAR    := target/titan-csv.jar
RUN     = source ./env.sh >/dev/null && java -jar $(JAR)

# Override on the command line, e.g.  make serve PORT=9000 TOKEN=secret
CONFIG ?= conf/titan-berkeleyje.properties
PORT   ?= 8182
TOKEN  ?= demo123
URL    ?= http://127.0.0.1:$(PORT)

SAMPLES := -v samples/people.csv -v samples/software.csv -e samples/knows.csv -e samples/created.csv
# The query is passed to recipes as an environment variable ($$Q), so quotes inside it survive intact.
export Q
# Builds the JSON body {"gremlin": Q} safely, whatever quotes the query contains.
JSON_Q   = python3 -c 'import json,os; print(json.dumps({"gremlin": os.environ["Q"]}))'
PRETTY   = python3 -m json.tool

.DEFAULT_GOAL := help
.PHONY: help build test clean data graph load query repl serve serve-readonly stop health schema api

help: ## Show this list
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "}{printf "  make %-15s %s\n", $$1, $$2}'
	@echo
	@echo "  Examples:"
	@echo "    make data                                       (show the CSV input)"
	@echo "    make load"
	@echo "    make graph                                      (show what was loaded)"
	@echo "    make query Q=\"g.V().has('name','marko').out('knows').values('name')\""
	@echo "    make serve                                      (terminal 1)"
	@echo "    make api Q=\"g.V().groupCount().by(label)\"       (terminal 2)"

# ---------------------------------------------------------------- build

# Rebuilding the jar while `serve` runs from it makes the JVM crash on shutdown (SIGBUS), so refuse.
$(JAR): pom.xml $(shell find src -type f)
	@if lsof -ti tcp:$(PORT) >/dev/null; then echo "Sources changed but the server on port $(PORT) is running from the jar. Stop it first (make stop), then retry."; exit 1; fi
	@source ./env.sh >/dev/null && mvn -q -DskipTests package

build: $(JAR) ## Build target/titan-csv.jar (skips tests)

test: ## Run the 7 JUnit tests
	@source ./env.sh >/dev/null && mvn -q test && grep -h "Tests run" target/surefire-reports/*.txt

clean: ## Delete build output and local graph data
	rm -rf target data

# ---------------------------------------------------------------- CLI

data: ## Show the sample CSV files and the relationships they describe
	@./scripts/show-data.sh samples

# Queries used by `make graph` to show what is actually stored in Titan after loading.
GRAPH_V = g.V().order().by('csvId', incr).valueMap(true, 'csvId', 'name', 'age', 'lang')
GRAPH_E = g.E().order().by(label, incr).by(outV().values('csvId'), incr).by(inV().values('csvId'), incr).collect{ e -> String.format('%-8s %-13s %-8s weight %s', e.outVertex().value('name'), '--' + e.label() + '-->', e.inVertex().value('name'), e.value('weight')) }

graph: ## Show what is stored in the graph (via REST if the server is running)
	@if lsof -ti tcp:$(PORT) >/dev/null; then \
	  echo "== Vertices (server running, using REST API) =="; Q="$(GRAPH_V)" $(MAKE) -s api; \
	  echo "== Edges =="; Q="$(GRAPH_E)" $(MAKE) -s api; \
	else \
	  echo "== Vertices stored in Titan (id = Titan internal id, csvId = id from the CSV) =="; \
	  Q="$(GRAPH_V)" $(MAKE) -s query; echo; \
	  echo "== Edges stored in Titan =="; Q="$(GRAPH_E)" $(MAKE) -s query; \
	fi

load: $(JAR) ## Wipe the graph and load the sample CSVs
	@$(RUN) load --drop -c $(CONFIG) $(SAMPLES)

query: $(JAR) ## Run one query: make query Q="g.V().count()" [FORMAT=json]
	@test -n "$$Q" || { echo 'usage: make query Q="g.V().count()"'; exit 1; }
	@$(RUN) query -c $(CONFIG) --format $(or $(FORMAT),text) "$$Q"

repl: $(JAR) ## Interactive Gremlin prompt (:quit to exit)
	@$(RUN) repl -c $(CONFIG)

# ---------------------------------------------------------------- REST server

serve: $(JAR) ## Start REST API, token + writes enabled (Ctrl+C stops)
	@$(RUN) serve -c $(CONFIG) --port $(PORT) --token $(TOKEN) --allow-writes || true

serve-readonly: $(JAR) ## Start REST API read-only (no /load, no writes)
	@$(RUN) serve -c $(CONFIG) --port $(PORT) --token $(TOKEN) || true

stop: ## Stop a server left running on PORT
	@pid=$$(lsof -ti tcp:$(PORT)); if [ -n "$$pid" ]; then kill $$pid && echo "stopped $$pid"; else echo "nothing running on port $(PORT)"; fi

# ---------------------------------------------------------------- REST client (server must be running)

health: ## GET /health
	@curl -s -H "Authorization: Bearer $(TOKEN)" $(URL)/health | $(PRETTY)

schema: ## GET /schema
	@curl -s -H "Authorization: Bearer $(TOKEN)" $(URL)/schema | $(PRETTY)

api: ## POST /query: make api Q="g.V().count()"
	@test -n "$$Q" || { echo 'usage: make api Q="g.V().count()"'; exit 1; }
	@$(JSON_Q) | curl -s -H "Authorization: Bearer $(TOKEN)" -d @- $(URL)/query | $(PRETTY)
