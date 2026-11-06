package com.lektik.titancsv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lektik.titancsv.graph.GraphManager;
import com.lektik.titancsv.load.CsvLoader;
import com.lektik.titancsv.query.GremlinExecutor;
import com.lektik.titancsv.query.ResultFormatter;
import com.lektik.titancsv.rest.RestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.script.ScriptException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** End-to-end tests on the in-memory Titan backend using the TinkerPop "modern" sample graph in samples/. */
public class TitanCsvTest {

    private static final String CONFIG = "conf/titan-inmemory.properties";
    private static final List<Path> VERTICES = Arrays.asList(Paths.get("samples/people.csv"), Paths.get("samples/software.csv"));
    private static final List<Path> EDGES = Arrays.asList(Paths.get("samples/knows.csv"), Paths.get("samples/created.csv"));

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private GraphManager gm;
    private GremlinExecutor exec;

    // Runs before EVERY test: a fresh in-memory graph (empty) + an executor (read-only, 1000 results, 10 s hard limit).
    @Before
    public void setUp() throws Exception {
        gm = GraphManager.open(CONFIG);
        exec = new GremlinExecutor(gm.graph(), 1000, false, 10_000);
    }

    // Runs after every test: release threads and close the graph.
    @After
    public void tearDown() {
        exec.close();
        gm.close();
    }

    // Helper: load the 4 sample CSVs with batch size 3 (forces several commits) in strict mode.
    private CsvLoader.Report loadSamples() throws Exception {
        return new CsvLoader(gm.graph(), 3, true).load(VERTICES, EDGES);
    }

    // Helper: run a query, return just the result items.
    private List<Object> q(String gremlin) throws Exception {
        return exec.execute(gremlin, null, 5000).items;
    }

    // ---------------------------------------------------------------- loader

    // Loader happy path: counts are right, "age:int" is stored as Integer, "weight:double" as Double,
    // and edges connect the right vertices.
    @Test
    public void loadsVerticesEdgesAndTypedProperties() throws Exception {
        CsvLoader.Report r = loadSamples();
        assertEquals(6, r.verticesLoaded);
        assertEquals(6, r.edgesLoaded);
        assertEquals(0, r.rowsSkipped);

        assertEquals(Collections.singletonList(6L), q("g.V().count()"));
        assertEquals(Collections.singletonList(29), q("g.V().has('name','marko').values('age')"));
        assertEquals(Collections.singletonList(1.0d), q("g.V().has('csvId','4').outE('created').has('weight', 1.0d).values('weight')"));
        assertEquals(new HashSet<Object>(Arrays.asList("vadas", "josh")),
                new HashSet<>(q("g.V().has('name','marko').out('knows').values('name')")));
    }

    // Non-strict mode: a bad int, a duplicate id and an edge to a missing vertex are all skipped,
    // reported with "file:line", and only the good row ends up in the graph.
    @Test
    public void skipsBadRowsAndReportsLineNumbers() throws Exception {
        Path v = write("v.csv", "id,label,age:int\na,person,1\nb,person,notanint\na,person,3\n");
        Path e = write("e.csv", "from,to,label\na,missing,knows\n");
        CsvLoader.Report r = new CsvLoader(gm.graph(), 100, false).load(
                Collections.singletonList(v), Collections.singletonList(e));
        assertEquals(1, r.verticesLoaded);
        assertEquals(0, r.edgesLoaded);
        assertEquals(3, r.rowsSkipped);
        assertTrue(r.errors.get(0), r.errors.get(0).startsWith("v.csv:3:"));
        assertTrue(r.errors.get(1), r.errors.get(1).contains("duplicate vertex id 'a'"));
        assertTrue(r.errors.get(2), r.errors.get(2).contains("no vertex with id 'missing'"));
        assertEquals(Collections.singletonList(1L), q("g.V().count()"));
    }

    // --strict: the first bad row (empty id on line 3) aborts the load with its line number.
    @Test
    public void strictModeAborts() throws Exception {
        Path v = write("v.csv", "id,label\na,person\n,person\n");
        try {
            new CsvLoader(gm.graph(), 100, true).load(Collections.singletonList(v), Collections.<Path>emptyList());
            fail("expected failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("v.csv:3"));
        }
    }

    // A vertex file without the required "id" column is rejected before any row is read.
    @Test(expected = IllegalArgumentException.class)
    public void missingRequiredColumnIsRejected() throws Exception {
        Path v = write("v.csv", "name,label\na,person\n");
        new CsvLoader(gm.graph(), 100, false).load(Collections.singletonList(v), Collections.<Path>emptyList());
    }

    // ---------------------------------------------------------------- executor

    // Executor: bindings work like SQL parameters, __ and P are auto-imported,
    // and an infinite loop is stopped by the timeout.
    @Test
    public void bindingsAndTimeouts() throws Exception {
        loadSamples();
        Map<String, Object> b = Collections.<String, Object>singletonMap("who", "josh");
        assertEquals(new HashSet<Object>(Arrays.asList("lop", "ripple")),
                new HashSet<>(exec.execute("g.V().has('name',who).out('created').values('name')", b, 5000).items));
        // default imports (__ and P) are available to scripts
        assertEquals(Collections.singletonList("josh"),
                q("g.V().hasLabel('person').where(__.out('created').count().is(P.gt(1L))).values('name')"));
        try {
            exec.execute("while(true){}", null, 500);
            fail("expected timeout");
        } catch (TimeoutException expected) {
            // ok
        }
    }

    // Safety: writes fail in read-only mode; results are capped (truncated=true); JSON output contains GraphSON vertices.
    @Test
    public void readOnlyByDefaultAndResultCap() throws Exception {
        try {
            q("g.addV(label,'x')");
            fail("write should fail");
        } catch (ScriptException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        GremlinExecutor small = new GremlinExecutor(gm.graph(), 2, false, 0);
        try {
            loadSamples();
            GremlinExecutor.Result r = small.execute("g.V()", null, 5000);
            assertEquals(2, r.items.size());
            assertTrue(r.truncated);
            assertTrue(ResultFormatter.format(r, ResultFormatter.Format.JSON).contains("\"type\" : \"vertex\""));
        } finally {
            small.close();
        }
    }

    // ---------------------------------------------------------------- REST

    // REST: starts the real HTTP server on a random port and checks 401 (no token), 200 (health/query),
    // 400 (bad query), 403 (/load without --allow-writes) and 405 (wrong HTTP method).
    @Test
    @SuppressWarnings("unchecked")
    public void restQueryAndAuth() throws Exception {
        loadSamples();
        RestServer server = new RestServer(gm.graph(), exec, "127.0.0.1", 0, "t0k", 5000);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(401, call(base + "/health", null, null)[0]);

            Object[] health = call(base + "/health", null, "t0k");
            assertEquals(200, health[0]);
            assertEquals(6, ((Map<String, Object>) health[1]).get("vertices"));

            Object[] r = call(base + "/query", "{\"gremlin\":\"g.E().count()\"}", "t0k");
            assertEquals(200, r[0]);
            assertEquals(Collections.singletonList(6), ((Map<String, Object>) r[1]).get("result"));

            assertEquals(400, call(base + "/query", "{\"gremlin\":\"g.V().nope()\"}", "t0k")[0]);
            assertEquals(403, call(base + "/load", "{\"vertices\":[\"samples/people.csv\"]}", "t0k")[0]);
            assertEquals(405, call(base + "/query", null, "t0k")[0]);
        } finally {
            server.close();
        }
    }

    // Tiny HTTP client for the REST test: returns {status code, parsed JSON body}.
    private static Object[] call(String url, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return new Object[]{status, new ObjectMapper().readValue(buf.toByteArray(), Map.class)};
    }

    // Writes a temporary CSV file for a test (deleted automatically by the TemporaryFolder rule).
    private Path write(String name, String content) throws Exception {
        Path p = tmp.getRoot().toPath().resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }
}
