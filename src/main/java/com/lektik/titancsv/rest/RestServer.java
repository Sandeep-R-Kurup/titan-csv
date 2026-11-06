package com.lektik.titancsv.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lektik.titancsv.load.CsvLoader;
import com.lektik.titancsv.query.GremlinExecutor;
import com.lektik.titancsv.query.ResultFormatter;
// The JDK's built-in HTTP server (ships with Java, no extra dependency or framework).
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
// Titan schema types, used by /schema.
import com.thinkaurelius.titan.core.EdgeLabel;
import com.thinkaurelius.titan.core.PropertyKey;
import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.core.TitanTransaction;
import com.thinkaurelius.titan.core.VertexLabel;
import com.thinkaurelius.titan.core.schema.TitanManagement;

import javax.script.ScriptException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Minimal JSON-over-HTTP interface built on the JDK's {@link HttpServer}.
 *
 * <pre>
 * GET  /health   -> {"status":"ok","vertices":N,"edges":M}
 * GET  /schema   -> vertex labels, edge labels, property keys
 * POST /query    {"gremlin":"g.V().count()","bindings":{...},"timeoutMs":30000}
 * POST /load     {"vertices":["a.csv"],"edges":["b.csv"],"strict":false,"batchSize":10000}  (needs --allow-writes)
 * </pre>
 *
 * <p>WHY NO SPRING/JAVALIN: Titan 1.0 drags in old Guava/Jackson versions; a modern web framework would
 * clash with them. The JDK server needs no extra jars.
 */
public final class RestServer implements AutoCloseable {

    // Reject request bodies over 1 MB (protects memory from huge/abusive requests).
    private static final int MAX_BODY_BYTES = 1 << 20;

    private final TitanGraph graph;
    private final GremlinExecutor executor;  // the SAME executor the CLI uses -> identical query behaviour
    private final long defaultTimeoutMs;     // also the maximum a client may request
    private final String token;              // null = auth disabled
    private final HttpServer server;
    private final ObjectMapper json = ResultFormatter.mapper();

    // ---------------------------------------------------------------------------------------------
    // CONSTRUCTOR: create the server, give it 8 worker threads, register the 4 routes.
    // Every route goes through handle(), which does auth, method check, JSON parsing and error mapping.
    // ---------------------------------------------------------------------------------------------
    public RestServer(TitanGraph graph, GremlinExecutor executor, String host, int port,
                      String token, long defaultTimeoutMs) throws IOException {
        this.graph = graph;
        this.executor = executor;
        this.token = (token == null || token.isEmpty()) ? null : token;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0); // port 0 = pick a free port (tests)
        server.setExecutor(Executors.newFixedThreadPool(8));                   // up to 8 concurrent requests
        server.createContext("/health", ex -> handle(ex, "GET", this::health));
        server.createContext("/schema", ex -> handle(ex, "GET", this::schema));
        server.createContext("/query", ex -> handle(ex, "POST", this::query));
        server.createContext("/load", ex -> handle(ex, "POST", this::load));
    }

    public void start() {
        server.start();
    }

    // Actual bound port (useful when started with port 0).
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0); // stop immediately
    }

    // =============================================================================================
    // ROUTES: each returns a plain Java object (usually a Map) that handle() turns into JSON.
    // =============================================================================================

    // ---------------------------------------------------------------------------------------------
    // GET /health: liveness check + vertex/edge counts, read in a read-only transaction.
    // (Counting is a full scan: fine for small demo data, would be removed/cached for big graphs.)
    // ---------------------------------------------------------------------------------------------
    private Object health(Map<String, Object> body) {
        TitanTransaction tx = graph.buildTransaction().readOnly().start();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            m.put("vertices", tx.traversal().V().count().next());
            m.put("edges", tx.traversal().E().count().next());
            m.put("writesAllowed", executor.allowsWrites());
            return m;
        } finally {
            tx.rollback(); // read-only: nothing to commit, just release the transaction
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GET /schema: what labels and property keys exist. Helps a client (or an AI agent) write queries.
    // Uses TitanManagement, Titan's schema API, and rolls back because we only read.
    // ---------------------------------------------------------------------------------------------
    private Object schema(Map<String, Object> body) {
        TitanManagement mgmt = graph.openManagement();
        try {
            List<String> vertexLabels = new ArrayList<>();
            for (VertexLabel l : mgmt.getVertexLabels()) vertexLabels.add(l.name());
            List<String> edgeLabels = new ArrayList<>();
            for (EdgeLabel l : mgmt.getRelationTypes(EdgeLabel.class)) edgeLabels.add(l.name());
            Map<String, String> keys = new LinkedHashMap<>();
            for (PropertyKey k : mgmt.getRelationTypes(PropertyKey.class)) {
                keys.put(k.name(), k.dataType().getSimpleName()); // auto-created keys show as "Object"
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vertexLabels", vertexLabels);
            m.put("edgeLabels", edgeLabels);
            m.put("propertyKeys", keys);
            return m;
        } finally {
            mgmt.rollback();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // POST /query: validate the body, then delegate to GremlinExecutor.
    //  - "gremlin"   : required string
    //  - "bindings"  : optional object of parameters (g / graph are reserved)
    //  - "timeoutMs" : optional, but capped at the server default so clients can't ask for hours
    //  Errors: bad script -> 400, timeout -> 408
    // ---------------------------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private Object query(Map<String, Object> body) throws Exception {
        Object script = body.get("gremlin");
        if (!(script instanceof String)) {
            throw new HttpError(400, "body must contain a string field 'gremlin'");
        }
        Object b = body.get("bindings");
        if (b != null && !(b instanceof Map)) {
            throw new HttpError(400, "'bindings' must be an object");
        }
        Map<String, Object> bindings = b == null ? Collections.<String, Object>emptyMap() : (Map<String, Object>) b;
        if (bindings.containsKey("g") || bindings.containsKey("graph")) {
            throw new HttpError(400, "'g' and 'graph' are reserved binding names");
        }
        long timeout = body.get("timeoutMs") instanceof Number
                ? Math.min(((Number) body.get("timeoutMs")).longValue(), defaultTimeoutMs) : defaultTimeoutMs;
        try {
            return ResultFormatter.envelope(executor.execute((String) script, bindings, timeout));
        } catch (TimeoutException e) {
            throw new HttpError(408, e.getMessage());
        } catch (ScriptException e) {
            throw new HttpError(400, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // POST /load: load CSV files that already exist on the server's disk (paths, not uploads).
    // Only allowed when the server was started with --allow-writes (otherwise 403).
    // Reuses the exact same CsvLoader as the CLI and returns its report as JSON.
    // ---------------------------------------------------------------------------------------------
    private Object load(Map<String, Object> body) throws Exception {
        if (!executor.allowsWrites()) {
            throw new HttpError(403, "loading is disabled; start the server with --allow-writes");
        }
        List<Path> vertices = paths(body.get("vertices"), "vertices");
        List<Path> edges = paths(body.get("edges"), "edges");
        if (vertices.isEmpty() && edges.isEmpty()) {
            throw new HttpError(400, "give at least one file in 'vertices' or 'edges'");
        }
        boolean strict = Boolean.TRUE.equals(body.get("strict"));
        int batch = body.get("batchSize") instanceof Number ? ((Number) body.get("batchSize")).intValue() : 10000;
        try {
            return new CsvLoader(graph, batch, strict).load(vertices, edges).toMap();
        } catch (IllegalArgumentException | IOException e) {
            throw new HttpError(400, e.getMessage()); // bad CSV / missing file -> client error
        }
    }

    // Converts a JSON array of strings into file Paths, validating the type.
    private static List<Path> paths(Object v, String field) {
        List<Path> out = new ArrayList<>();
        if (v == null) return out;
        if (!(v instanceof List)) throw new HttpError(400, "'" + field + "' must be an array of file paths");
        for (Object o : (List<?>) v) out.add(Paths.get(String.valueOf(o)));
        return out;
    }

    // =============================================================================================
    // PLUMBING shared by all routes
    // =============================================================================================

    // A route = "take the parsed JSON body, return something to serialise".
    private interface Route {
        Object apply(Map<String, Object> body) throws Exception;
    }

    // Exception that carries an HTTP status code; thrown anywhere, converted to a response in handle().
    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HANDLE: the request pipeline, run for every request:
    //   1. auth check            -> 401
    //   2. HTTP method check     -> 405 (+ Allow header)
    //   3. read + parse JSON body (POST only, max 1 MB) -> 400 / 413
    //   4. call the route
    //   5. any HttpError -> its status; any other exception -> 500
    //   6. write the JSON response with Content-Type and status
    // ---------------------------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex, String method, Route route) throws IOException {
        int status = 200;
        Object response;
        try {
            // 1. auth
            if (!authorized(ex)) {
                throw new HttpError(401, "missing or invalid bearer token");
            }
            // 2. method
            if (!method.equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", method);
                throw new HttpError(405, "use " + method);
            }
            // 3. body
            Map<String, Object> body = Collections.emptyMap();
            if ("POST".equals(method)) {
                byte[] raw = readBody(ex.getRequestBody());
                try {
                    body = raw.length == 0 ? Collections.<String, Object>emptyMap() : json.readValue(raw, Map.class);
                } catch (IOException e) {
                    throw new HttpError(400, "invalid JSON body: " + e.getMessage());
                }
            }
            // 4. route
            response = route.apply(body);
        } catch (HttpError e) {
            // 5a. expected errors
            status = e.status;
            response = error(e.getMessage());
        } catch (Exception e) {
            // 5b. unexpected errors
            status = 500;
            response = error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // 6. response
        byte[] out = ResultFormatter.toJson(response, false).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AUTH: if a token is configured, require header "Authorization: Bearer <token>".
    // MessageDigest.isEqual = constant-time comparison, so attackers can't guess the token
    // character by character by measuring response times (timing attack).
    // ---------------------------------------------------------------------------------------------
    private boolean authorized(HttpExchange ex) {
        if (token == null) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + token;
        return h != null && MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    // Standard error body: {"error":"message"}
    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    // Reads the request body in 8 KB chunks, refusing anything larger than MAX_BODY_BYTES (413).
    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > MAX_BODY_BYTES) {
                throw new HttpError(413, "request body too large");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
