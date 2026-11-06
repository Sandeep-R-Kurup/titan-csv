package com.lektik.titancsv.load;

import com.lektik.titancsv.graph.GraphManager;
import com.thinkaurelius.titan.core.TitanGraph;
// Apache Commons CSV: handles quoting, commas inside values, header row, etc.
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
// TinkerPop types: traversal source "g", Edge, Vertex, and T (special keys like T.label).
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads vertex and edge CSV files into a Titan graph.
 *
 * <p>Vertex files need {@code id,label} columns, edge files need {@code from,to,label}. Every other column becomes a
 * property; headers may declare a type as {@code name:type}. Vertex files are loaded before edge files so edges can
 * reference vertices from any file. Endpoints are resolved through the unique {@code csvId} index, so edges may also
 * point at vertices loaded in an earlier run.
 */
public final class CsvLoader {

    // Keep the error list small so a file with a million bad rows doesn't flood memory/output.
    private static final int MAX_REPORTED_ERRORS = 100;

    private final TitanGraph graph;
    private final int batchSize;   // rows per commit
    private final boolean strict;  // true = stop at first bad row, false = skip + report

    // ---------------------------------------------------------------------------------------------
    // CONSTRUCTOR: just stores settings and validates the batch size.
    // ---------------------------------------------------------------------------------------------
    public CsvLoader(TitanGraph graph, int batchSize, boolean strict) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be >= 1");
        }
        this.graph = graph;
        this.batchSize = batchSize;
        this.strict = strict;
    }

    // ---------------------------------------------------------------------------------------------
    // MAIN ENTRY: load all vertex files FIRST, then all edge files.
    // Order matters: an edge needs both of its endpoint vertices to exist already.
    // On a fatal error the current (uncommitted) batch is rolled back so no half-batch is left behind.
    // ---------------------------------------------------------------------------------------------
    public Report load(List<Path> vertexFiles, List<Path> edgeFiles) throws IOException {
        long start = System.currentTimeMillis();
        Report report = new Report();
        try {
            for (Path p : vertexFiles) {
                loadFile(p, true, report);   // true  = vertex file
            }
            for (Path p : edgeFiles) {
                loadFile(p, false, report);  // false = edge file
            }
        } catch (IOException | RuntimeException e) {
            graph.tx().rollback();
            throw e;
        }
        report.tookMs = System.currentTimeMillis() - start;
        return report;
    }

    // ---------------------------------------------------------------------------------------------
    // LOAD ONE FILE
    // ---------------------------------------------------------------------------------------------
    private void loadFile(Path file, boolean vertices, Report report) throws IOException {
        // --- 1. File must exist ---
        if (!Files.isRegularFile(file)) {
            throw new IOException("CSV file not found: " + file.toAbsolutePath());
        }
        // --- 2. CSV settings: first row is the header, skip blank lines, trim spaces around values ---
        CSVFormat format = CSVFormat.DEFAULT.withHeader().withIgnoreEmptyLines().withIgnoreSurroundingSpaces();
        // try-with-resources: the file is closed automatically even if an exception happens
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(in, format)) {

            // --- 3. Validate the header: required columns must be present ---
            Map<String, Integer> header = parser.getHeaderMap(); // column name -> column position
            String[] required = vertices ? new String[]{"id", "label"} : new String[]{"from", "to", "label"};
            for (String r : required) {
                if (!header.containsKey(r)) {
                    throw new IllegalArgumentException(file + ": missing required column '" + r + "' (have "
                            + header.keySet() + ")");
                }
            }

            // --- 4. Every NON-required column becomes a property; parse "name:type" once per file ---
            Map<Integer, ColumnType.Column> propertyColumns = new LinkedHashMap<>(); // position -> (name, type)
            for (Map.Entry<String, Integer> h : header.entrySet()) {
                if (!contains(required, h.getKey())) {
                    ColumnType.Column col = ColumnType.Column.parse(h.getKey());
                    // "csvId" is our internal property; a CSV column with that name would overwrite it.
                    if (col.name.equals(GraphManager.CSV_ID)) {
                        throw new IllegalArgumentException(file + ": column name '" + GraphManager.CSV_ID + "' is reserved");
                    }
                    propertyColumns.put(h.getValue(), col);
                }
            }

            // --- 5. Row loop ---
            GraphTraversalSource g = graph.traversal(); // "g" used for index lookups of edge endpoints
            int pending = 0;                            // rows added since the last commit
            for (CSVRecord rec : parser) {
                long line = rec.getRecordNumber() + 1; // record 1 is on line 2 because line 1 is the header
                try {
                    if (vertices) {
                        addVertex(rec, propertyColumns);
                        report.verticesLoaded++;
                    } else {
                        addEdge(g, rec, propertyColumns);
                        report.edgesLoaded++;
                    }
                } catch (RuntimeException e) {
                    // --- 5a. Bad row: build "file:line: reason" ---
                    String msg = file.getFileName() + ":" + line + ": " + e.getMessage();
                    if (strict) {
                        throw new IllegalArgumentException(msg, e); // --strict: abort the whole load
                    }
                    report.rowsSkipped++;                           // default: skip, remember, continue
                    if (report.errors.size() < MAX_REPORTED_ERRORS) {
                        report.errors.add(msg);
                    }
                    continue;
                }
                // --- 5b. BATCH COMMIT: commit every N rows.
                //     One huge transaction would hold everything in memory; committing every row is slow.
                if (++pending >= batchSize) {
                    graph.tx().commit();
                    pending = 0;
                }
            }
            // --- 6. Commit whatever is left of the last partial batch ---
            graph.tx().commit();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ADD ONE VERTEX from a CSV row
    // ---------------------------------------------------------------------------------------------
    private void addVertex(CSVRecord rec, Map<Integer, ColumnType.Column> cols) {
        String id = requireValue(rec, "id");
        String label = requireValue(rec, "label");
        // Duplicate check via the csvId index (fast). Gives a clear message instead of an index-violation error.
        if (graph.traversal().V().has(GraphManager.CSV_ID, id).hasNext()) {
            throw new IllegalArgumentException("duplicate vertex id '" + id + "'");
        }
        // Create the vertex with its label and csvId in one call: addVertex(T.label, "person", "csvId", "1")
        Vertex v = graph.addVertex(T.label, label, GraphManager.CSV_ID, id);
        try {
            // Set each extra column as a typed property; null (empty cell) = skip.
            for (Map.Entry<Integer, ColumnType.Column> c : cols.entrySet()) {
                Object value = c.getValue().type.convert(rec.get(c.getKey()));
                if (value != null) {
                    v.property(c.getValue().name, value);
                }
            }
        } catch (RuntimeException e) {
            // A property failed (e.g. "abc" in an int column): delete the half-built vertex so no partial data stays.
            v.remove();
            throw e;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ADD ONE EDGE from a CSV row: find both endpoints by csvId, connect them, set properties.
    // ---------------------------------------------------------------------------------------------
    private void addEdge(GraphTraversalSource g, CSVRecord rec, Map<Integer, ColumnType.Column> cols) {
        String from = requireValue(rec, "from");
        String to = requireValue(rec, "to");
        String label = requireValue(rec, "label");
        Vertex out = lookup(g, from);           // edge goes FROM this vertex (out-vertex)...
        Vertex in = lookup(g, to);              // ...TO this vertex (in-vertex)
        Edge e = out.addEdge(label, in);        // e.g. marko --knows--> vadas
        try {
            for (Map.Entry<Integer, ColumnType.Column> c : cols.entrySet()) {
                Object value = c.getValue().type.convert(rec.get(c.getKey()));
                if (value != null) {
                    e.property(c.getValue().name, value);
                }
            }
        } catch (RuntimeException ex) {
            e.remove(); // same cleanup idea as addVertex
            throw ex;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // LOOKUP a vertex by its CSV id. Uses the unique csvId index, so it's a direct lookup, not a scan.
    // ---------------------------------------------------------------------------------------------
    private static Vertex lookup(GraphTraversalSource g, String csvId) {
        Iterator<Vertex> it = g.V().has(GraphManager.CSV_ID, csvId); // a Gremlin traversal is also an Iterator
        if (!it.hasNext()) {
            throw new IllegalArgumentException("no vertex with id '" + csvId + "'");
        }
        return it.next();
    }

    // Reads a required cell and rejects blank values (e.g. an edge with an empty "from").
    private static String requireValue(CSVRecord rec, String column) {
        String v = rec.get(column);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("empty '" + column + "' value");
        }
        return v.trim();
    }

    // Small helper: is s one of the required column names?
    private static boolean contains(String[] arr, String s) {
        for (String a : arr) {
            if (a.equals(s)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // REPORT: result of a load run. Printed as text by the CLI, returned as JSON by REST /load (toMap()).
    // ---------------------------------------------------------------------------------------------
    /** Summary of a load run. */
    public static final class Report {
        public long verticesLoaded;
        public long edgesLoaded;
        public long rowsSkipped;
        public long tookMs;
        public final List<String> errors = new ArrayList<>(); // first 100 "file:line: reason" messages

        // Map form -> serialised to JSON by the REST server.
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verticesLoaded", verticesLoaded);
            m.put("edgesLoaded", edgesLoaded);
            m.put("rowsSkipped", rowsSkipped);
            m.put("tookMs", tookMs);
            m.put("errors", errors);
            return m;
        }

        // Human-readable form -> printed by the CLI to stderr.
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Loaded %d vertices, %d edges in %d ms; %d rows skipped",
                    verticesLoaded, edgesLoaded, tookMs, rowsSkipped));
            for (String e : errors) {
                sb.append(System.lineSeparator()).append("  ! ").append(e);
            }
            if (rowsSkipped > errors.size()) {
                sb.append(System.lineSeparator()).append("  ... ").append(rowsSkipped - errors.size()).append(" more");
            }
            return sb.toString();
        }
    }
}
