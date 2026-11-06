# titan-csv

Load CSV files into a [Titan 1.0.0](https://github.com/thinkaurelius/titan) graph and query them with Gremlin
(TinkerPop 3.0.1) through a **CLI** (one-shot queries + REPL) or a **JSON REST API**. No UI.

> Titan is unmaintained since 2016 and only runs on **Java 8**. JanusGraph is its maintained, API-compatible fork.

## Setup

You need **Java 8**, because Titan 1.0 does not run on newer JDKs. You also need **Maven 3**.

```sh
# macOS (Intel or Apple Silicon). Temurin has no JDK 8 build for Apple Silicon, Azul Zulu does.
brew install --cask zulu@8
brew install maven

source env.sh          # selects Java 8 for this shell (also finds a JDK unpacked under ~/.jdks)
mvn package            # compiles, runs the 7 tests, builds target/titan-csv.jar
```

On Linux or Windows, install any OpenJDK 8 and point `JAVA_HOME` at it.

## Quick start with make

```sh
make                 # list all shortcuts
make build           # build the jar
make test            # run the tests
make data            # show the sample CSV files and the relationships they describe
make load            # wipe the graph, load the sample CSVs
make graph           # show what is now stored in Titan (vertices with ids, edges)
make query Q="g.V().has('name','marko').out('knows').values('name')"
make repl            # interactive prompt

make serve           # terminal 1: REST API on :8182, token demo123, writes enabled
make health          # terminal 2: GET /health
make schema          #             GET /schema
make api Q="g.V().groupCount().by(label)"      # POST /query
make stop            # stop a server left running
```

Override the defaults inline, e.g. `make serve PORT=9000 TOKEN=secret` or `make query CONFIG=conf/titan-inmemory.properties Q="..."`.
The BerkeleyDB graph can be opened by only one process at a time. While `make serve` is running, use the REST targets (`health`, `schema`, `api`), not `load`/`query`/`repl`.

## CSV format

**Vertex files** need `id` and `label` columns. **Edge files** need `from`, `to` and `label` columns (`from`/`to` are vertex `id`s).
Any other column becomes a property. You can give a column a type as `name:type`, using `string` (default), `int`,
`long`, `float`, `double` or `boolean`. Empty cells create no property.

```csv
id,label,name,age:int          from,to,label,weight:double
1,person,marko,29              1,2,knows,0.5
```

* The CSV `id` is stored as the indexed, unique vertex property `csvId`. Titan assigns its own internal ids.
* Vertex files load before edge files. Edges can also point at vertices loaded in an earlier run.
* Bad rows (bad type, duplicate id, unknown endpoint) are skipped and reported as `file:line`. Use `--strict` to abort instead.
  `load` exits with code 2 when it skipped any rows.
* Loading the same edge file twice creates duplicate edges. Use `load --drop` to start over.

## CLI

```sh
J="java -jar target/titan-csv.jar"

# load into the persistent BerkeleyDB graph (./data/graph)
$J load --drop -v samples/people.csv -v samples/software.csv -e samples/knows.csv -e samples/created.csv

$J query "g.V().has('name','marko').out('knows').values('name')"
$J query "g.V().hasLabel('person').valueMap('name','age')"            # maps render as a table
$J query --format json -p who=josh "g.V().has('name',who).outE()"     # bindings + GraphSON JSON
$J query -f my-query.groovy

# throwaway in-memory graph: load + query in one go
$J query -c conf/titan-inmemory.properties -v samples/people.csv "g.V().count()"

$J repl                                                                # :format json|text, :help, :quit
$J --help ; $J query --help
```

Common options include `-c/--config` (default `conf/titan-berkeleyje.properties`), `--timeout` (ms, default 30000),
`--max-results` (default 1000) and `--allow-writes`.

Scripts see `g` (traversal source) and `graph`. Each script runs in its own transaction, which is **read-only** unless you
pass `--allow-writes`.

## REST API

```sh
$J serve [--port 8182] [--host 127.0.0.1] [--token SECRET | env TITAN_CSV_TOKEN] [--allow-writes]
```

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/health` | none | `{"status":"ok","vertices":6,"edges":6,"writesAllowed":false}` |
| GET | `/schema` | none | vertex labels, edge labels, property keys |
| POST | `/query` | `{"gremlin":"g.V().has('name',n)","bindings":{"n":"marko"},"timeoutMs":5000}` | `{"result":[...],"count":1,"truncated":false,"tookMs":12}` |
| POST | `/load` | `{"vertices":["/abs/a.csv"],"edges":["/abs/b.csv"],"strict":false,"batchSize":10000}` | load report (requires `--allow-writes`) |

```sh
curl -s localhost:8182/query -H 'Authorization: Bearer SECRET' -d '{"gremlin":"g.V().count()"}'
```

Errors come back as `{"error":"..."}` with one of these statuses:

* 400: script or CSV error
* 401: bad token
* 403: load without `--allow-writes`
* 405: wrong method
* 408: timeout
* 413: body larger than 1 MB

`/load` reads file paths on the server's filesystem. It does not accept uploads.

**Security:** `/query` evaluates Gremlin-**Groovy**, which can run arbitrary JVM code. A read-only transaction stops
accidental graph writes, but it is not a sandbox. The server binds to `127.0.0.1` by default. Use `--token` and keep it
off public networks. Timed-out scripts are interrupted, including plain Groovy loops.

## Tests

`mvn test` runs 7 end-to-end tests against the in-memory backend:
* loading with typed properties
* bad-row reporting and `--strict`
* header validation
* query bindings and timeouts
* read-only mode and the result cap
* REST status codes and token auth

## Known limitations / next steps

* **Re-loading creates duplicate edges.** Vertices are de-duplicated by `id`, but edges aren't. An upsert mode would fix this.
* **Only `csvId` is indexed.** Lookups like `has('name', …)` scan every vertex. Big graphs should add composite or mixed indexes for properties they query often.
* **Property keys are auto-created** with type `Object`. A schema file could declare the types up front.
* **Bulk loading:** for very large CSVs, use `storage.batch-loading`, an in-memory id cache, or TinkerPop's `BulkLoaderVertexProgram`.
* **Titan is end-of-life.** The code moves to JanusGraph by changing package names.

## Layout

```
conf/                        Titan configs (berkeleyje = persistent, inmemory = per-process)
samples/                     TinkerPop "modern" graph as CSV
src/main/java/com/lektik/titancsv/
  Main.java                  picocli CLI: load | query | repl | serve
  graph/GraphManager.java    open/drop graph, csvId unique index
  load/CsvLoader.java        CSV → vertices/edges, batching, error report
  load/ColumnType.java       typed headers
  query/GremlinExecutor.java Groovy script engine, per-query tx, timeout, result cap
  query/ResultFormatter.java GraphSON JSON / text / table output
  rest/RestServer.java       JDK HttpServer JSON API
```

Titan resolves a relative `storage.directory` against the folder of the properties file, so `../data/graph` means `<project>/data/graph`.
