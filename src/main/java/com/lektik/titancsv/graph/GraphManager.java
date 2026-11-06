package com.lektik.titancsv.graph;

// Titan classes: the graph itself, the factory that opens it, schema-management types, and the cleanup utility.
import com.thinkaurelius.titan.core.Cardinality;
import com.thinkaurelius.titan.core.PropertyKey;
import com.thinkaurelius.titan.core.TitanFactory;
import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.core.schema.TitanManagement;
import com.thinkaurelius.titan.core.util.TitanCleanup;
// TinkerPop's generic Vertex type, used to say "this index is on vertices" (not edges).
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.io.File;

/**
 * Opens a Titan graph from a properties file and makes sure the schema needed by the CSV loader exists:
 * a {@code csvId} property key with a unique composite index, used to resolve edge endpoints.
 *
 * <p>WHY THIS CLASS EXISTS: every entry point (CLI load/query/repl/serve, tests) needs the same
 * "open graph + make sure the index exists" logic, so it lives in one place.
 */
public final class GraphManager implements AutoCloseable { // AutoCloseable -> usable in try-with-resources

    // ---------------------------------------------------------------------------------------------
    // CONSTANTS
    // Titan generates its own internal numeric vertex ids, so the "id" column from the CSV cannot be
    // used as the vertex id. We store it in a normal property called "csvId" and index it.
    // ---------------------------------------------------------------------------------------------
    /** Vertex property holding the external id from the CSV {@code id} column. */
    public static final String CSV_ID = "csvId";
    /** Name of the unique composite index on csvId. */
    public static final String CSV_ID_INDEX = "byCsvId";

    // The open Titan graph instance that this manager owns.
    private final TitanGraph graph;

    // Private constructor: callers must go through open(), which guarantees the schema is set up.
    private GraphManager(TitanGraph graph) {
        this.graph = graph;
    }

    // ---------------------------------------------------------------------------------------------
    // OPEN: read the .properties file (storage backend, directory...), open the graph, ensure schema.
    // ---------------------------------------------------------------------------------------------
    public static GraphManager open(String configPath) {
        TitanGraph graph = openTitan(configPath);  // connects to BerkeleyDB / in-memory store
        ensureSchema(graph);                       // create csvId key + index if missing
        return new GraphManager(graph);
    }

    // ---------------------------------------------------------------------------------------------
    // OPEN TITAN with a friendly error for the most common problem: BerkeleyDB allows only ONE process
    // to open the database folder. If `serve` is running, a second `load`/`query` would otherwise fail
    // with a 40-line stack trace ending in EnvironmentLockedException.
    // ---------------------------------------------------------------------------------------------
    private static TitanGraph openTitan(String configPath) {
        try {
            return TitanFactory.open(requireConfig(configPath));
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t.getClass().getSimpleName().equals("EnvironmentLockedException")) {
                    throw new IllegalStateException("The graph database is already open in another process "
                            + "(probably a running `serve`). Stop it first (Ctrl+C or `make stop`), "
                            + "or query it through the REST API instead.", e);
                }
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DROP: delete ALL data of the graph described by the config (used by `load --drop`).
    // Titan's TitanCleanup.clear() only works on a CLOSED graph, so we open it, close it, then clear it.
    // ---------------------------------------------------------------------------------------------
    /** Deletes all data stored by the graph described by {@code configPath}. */
    public static void drop(String configPath) {
        TitanGraph graph = openTitan(configPath);
        graph.close();
        TitanCleanup.clear(graph);
    }

    // Accessor used by the loader, executor and REST server.
    public TitanGraph graph() {
        return graph;
    }

    // Close the graph (flushes and releases the BerkeleyDB lock). Safe to call twice.
    @Override
    public void close() {
        if (graph.isOpen()) {
            graph.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CONFIG CHECK: fail early with a clear message instead of Titan's confusing error,
    // and pass Titan an absolute path (Titan resolves relative storage.directory against this file's folder).
    // ---------------------------------------------------------------------------------------------
    private static String requireConfig(String configPath) {
        File f = new File(configPath);
        if (!f.isFile()) {
            throw new IllegalArgumentException("Titan config file not found: " + f.getAbsolutePath());
        }
        return f.getAbsolutePath();
    }

    // ---------------------------------------------------------------------------------------------
    // SCHEMA SETUP (runs once per graph; a no-op if the index already exists)
    //  1. TitanManagement = Titan's schema API; it works like a transaction (commit / rollback).
    //  2. Create property key "csvId" of type String, one value per vertex (Cardinality.SINGLE).
    //  3. Build a UNIQUE COMPOSITE index on it:
    //       - composite = exact-match lookups, stored in the storage backend itself (no Elasticsearch needed)
    //       - unique    = two vertices can never share a csvId
    //     Without this index every edge row would need a full scan of all vertices to find its endpoints.
    // ---------------------------------------------------------------------------------------------
    private static void ensureSchema(TitanGraph graph) {
        TitanManagement mgmt = graph.openManagement();
        try {
            // Already set up (e.g. second run on the same BerkeleyDB data) -> nothing to do.
            if (mgmt.containsGraphIndex(CSV_ID_INDEX)) {
                mgmt.rollback();
                return;
            }
            // Reuse the key if it somehow exists, otherwise define it.
            PropertyKey key = mgmt.containsPropertyKey(CSV_ID)
                    ? mgmt.getPropertyKey(CSV_ID)
                    : mgmt.makePropertyKey(CSV_ID).dataType(String.class).cardinality(Cardinality.SINGLE).make();
            // Index on vertices, keyed by csvId, unique, composite.
            mgmt.buildIndex(CSV_ID_INDEX, Vertex.class).addKey(key).unique().buildCompositeIndex();
            mgmt.commit(); // persist the schema change
        } catch (RuntimeException e) {
            // Never leave a schema transaction hanging open on failure.
            if (mgmt.isOpen()) {
                mgmt.rollback();
            }
            throw e;
        }
    }
}
