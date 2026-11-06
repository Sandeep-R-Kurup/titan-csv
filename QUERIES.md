# Example queries

Every query below was run against the sample data (`make load`). The outputs shown are real.
The order of results can differ between runs unless the query uses `order()`.

**Setup:** in terminal 1, run `make load` and then `make serve`. Run the commands below in terminal 2.

`make api Q="..."` sends `POST /query` to the REST API. Without a server running, `make query Q="..."` runs the same
Gremlin from the CLI (read-only by default).

The sample graph:
```
marko(29) --knows 0.5--> vadas(27)        marko --created 0.4--> lop (java)
marko     --knows 1.0--> josh(32)         josh  --created 1.0--> ripple (java)
                                          josh  --created 0.4--> lop
peter(35)                                 peter --created 0.2--> lop
```

---

## 1. Basics: find and filter

| # | Command | What it does | Result |
|---|---|---|---|
| 1 | `make api Q="g.V().count()"` | Count all vertices | `6` |
| 2 | `make api Q="g.V().hasLabel('person').values('name')"` | Names of every vertex labelled `person` | `vadas, marko, josh, peter` |
| 3 | `make api Q="g.V().has('name','marko').valueMap()"` | All properties of marko. Values come back in lists because a property can hold several values. | `{csvId:[1], name:[marko], age:[29]}` |
| 4 | `make api Q="g.V().has('age', gt(30)).values('name')"` | People older than 30. `gt` is a predicate (others: `lt`, `gte`, `between`, `within`). | `josh, peter` |
| 5 | `make api Q="g.V().hasLabel('person').order().by('age', decr).values('name')"` | People sorted by age, oldest first | `peter, josh, marko, vadas` |

## 2. Traversals: follow relationships

| # | Command | What it does | Result |
|---|---|---|---|
| 6 | `make api Q="g.V().has('name','marko').out('knows').values('name')"` | Who marko knows. `out()` follows outgoing edges. | `vadas, josh` |
| 7 | `make api Q="g.V().has('name','lop').in('created').values('name')"` | Who created lop. `in()` follows incoming edges, the reverse direction. | `marko, josh, peter` |
| 8 | `make api Q="g.V().has('name','vadas').both().values('name')"` | Every neighbour of vadas in either direction | `marko` |
| 9 | `make api Q="g.V().has('name','marko').out('knows').out('created').dedup().values('name')"` | Software built by marko's friends (2 hops). `dedup()` removes repeats. | `lop, ripple` |
| 10 | `make api Q="g.V().has('name','marko').as('m').out('created').in('created').where(neq('m')).dedup().values('name')"` | **Co-workers.** Finds people who worked on the same software as marko, excluding marko himself. `as('m')` labels a step, and `where(neq('m'))` filters against that label. This is a recommendation-style query. | `josh, peter` |

## 3. Paths, edge properties, projections

| # | Command | What it does | Result |
|---|---|---|---|
| 11 | `make api Q="g.V().has('name','marko').repeat(out()).times(2).path().by('name')"` | Every 2-hop route starting from marko, with the full path | `[marko, josh, lop]`, `[marko, josh, ripple]` |
| 12 | `make api Q="g.V().has('name','peter').repeat(both().simplePath()).until(has('name','vadas')).path().by('name').limit(1)"` | **Shortest connection** from peter to vadas. `simplePath()` prevents walking in circles. | `[peter, lop, marko, vadas]` |
| 13 | `make api Q="g.V().has('name','marko').outE('knows').has('weight', gt(0.5d)).inV().values('name')"` | Strong friendships only, by filtering on an **edge** property. `outE` returns the edge itself, and `inV` steps to the vertex at its other end. | `josh` |
| 14 | `make api Q="g.V().hasLabel('person').as('person').out('created').as('software').select('person','software').by('name')"` | A "who created what" table, like a SQL join | `{marko, lop}, {josh, lop}, {josh, ripple}, {peter, lop}` |

**Write `0.5d`, not `0.5`.** Groovy reads `0.5` as a BigDecimal, which Titan rejects with
*"Property value [0.5] is of type class java.math.BigDecimal is not supported"*. The `d` makes it a double.

## 4. Aggregation and analytics

| # | Command | What it does | Result |
|---|---|---|---|
| 15 | `make api Q="g.V().groupCount().by(label)"` | Count vertices per label | `{software: 2, person: 4}` |
| 16 | `make api Q="g.V().hasLabel('software').groupCount().by('lang')"` | Count software per language | `{java: 2}` |
| 17 | `make api Q="g.V().hasLabel('person').values('age').mean()"` | Average age | `30.75` |
| 18 | `make api Q="g.V().hasLabel('person').values('age').max()"` | Oldest age | `35` |
| 19 | `make api Q="g.V().has('name','lop').inE('created').values('weight').sum()"` | Sum of the `created` edge weights on lop | `1.0` |
| 20 | `make api Q="g.V().hasLabel('software').order().by(inE('created').count(), decr).limit(1).values('name')"` | **Most popular software**, the one with the most creators | `lop` |
| 21 | `make api Q="g.V().hasLabel('person').not(out('created')).values('name')"` | People who have created nothing | `vadas` |

---

## 5. POST requests with curl

`make api` is a shortcut for the request below. When showing the HTTP call itself, use curl directly.

**Query with bindings.** Values are passed separately, like a SQL prepared statement:
```sh
curl -s -X POST localhost:8182/query \
  -H "Authorization: Bearer demo123" -H "Content-Type: application/json" -d @- <<'EOF' | jq
{"gremlin": "g.V().has('name', who).out('created').values('name')", "bindings": {"who": "josh"}}
EOF
```
Result: `{"result":["lop","ripple"],"count":2,"truncated":false,"tookMs":7}`

## 6. POST write queries (need `make serve`, which enables writes)

By default, queries run in a **read-only** transaction. `make serve` starts the server with `--allow-writes`, so the
requests below work. Against `make serve-readonly`, they fail with *"Cannot create new entities in read-only transaction"*.

**Add a vertex:**
```sh
curl -s -X POST localhost:8182/query \
  -H "Authorization: Bearer demo123" -H "Content-Type: application/json" -d @- <<'EOF' | jq
{"gremlin": "g.addV(label, 'person', 'name', 'sandeep', 'age', 30).values('name')"}
EOF
```
Result: `["sandeep"]`

**Add an edge** (sandeep knows marko). Several Groovy statements separated by `;`. The value of the last one is returned.
```sh
curl -s -X POST localhost:8182/query \
  -H "Authorization: Bearer demo123" -H "Content-Type: application/json" -d @- <<'EOF' | jq
{"gremlin": "me = g.V().has('name','sandeep').next(); m = g.V().has('name','marko').next(); me.addEdge('knows', m, 'weight', 0.9d); g.V(me).out('knows').values('name')"}
EOF
```
Result: `["marko"]`

**Update a property:**
```sh
curl -s -X POST localhost:8182/query \
  -H "Authorization: Bearer demo123" -H "Content-Type: application/json" -d @- <<'EOF' | jq
{"gremlin": "g.V().has('name','sandeep').property('age', 31).valueMap('name','age')"}
EOF
```
Result: `{name: [sandeep], age: [31]}`

**Query the new data** (friends of sandeep's friends):
```sh
make api Q="g.V().has('name','sandeep').out('knows').out('knows').values('name')"
```
Result: `vadas, josh`

**Delete a vertex.** Its edges are removed too:
```sh
curl -s -X POST localhost:8182/query \
  -H "Authorization: Bearer demo123" -H "Content-Type: application/json" -d @- <<'EOF' | jq
{"gremlin": "g.V().has('name','sandeep').drop().iterate(); g.V().count()"}
EOF
```
Result: `[6]` (back to the original 6 vertices)

## 7. POST /load: load new CSV files through the API

```sh
printf 'id,label,name,age:int\n7,person,sandeep,30\n'   > /tmp/demo-person.csv
printf 'from,to,label,weight:double\n7,1,knows,0.9\n'   > /tmp/demo-knows.csv

curl -s -X POST localhost:8182/load \
  -H "Authorization: Bearer demo123" -H "Content-Type: application/json" -d @- <<'EOF' | jq
{"vertices": ["/tmp/demo-person.csv"], "edges": ["/tmp/demo-knows.csv"]}
EOF
```
Result: `{"verticesLoaded":1,"edgesLoaded":1,"rowsSkipped":0,"errors":[]}`

Check the result with `make api Q="g.V().has('csvId','7').out('knows').values('name')"`, which returns `marko`.

---

**Reset after write demos:** `make stop` then `make load` puts the graph back to the 6 sample vertices and 6 edges.
