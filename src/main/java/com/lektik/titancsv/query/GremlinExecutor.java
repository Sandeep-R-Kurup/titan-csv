package com.lektik.titancsv.query;

import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.core.TitanTransaction;
// Gremlin-Groovy script engine + compile-time "customizers" (default imports, interrupt checks).
import org.apache.tinkerpop.gremlin.groovy.DefaultImportCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.ThreadInterruptCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.TimedInterruptCustomizerProvider;
// Graph result types we must "detach" from the transaction before returning them.
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedFactory;

import javax.script.Bindings;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evaluates Gremlin-Groovy scripts against a Titan graph. Shared by the CLI and the REST server.
 *
 * <p>Each script runs in its own Titan transaction, bound to the script as {@code graph} with {@code g} as its
 * traversal source. Unless writes are allowed the transaction is read-only, so mutating steps fail. Results are fully
 * iterated (up to {@code maxResults}) and detached from the transaction, so they can be serialised afterwards.
 *
 * <p>Note: Groovy scripts can run arbitrary JVM code; the read-only transaction prevents accidental graph writes, it
 * is not a security sandbox.
 */
public final class GremlinExecutor implements AutoCloseable {

    private final TitanGraph graph;
    private final GremlinGroovyScriptEngine engine; // compiles + runs Gremlin text; caches compiled scripts
    private final ExecutorService pool;             // threads that run queries (so we can time them out)
    private final int maxResults;                   // result cap, protects memory
    private final boolean allowWrites;              // false = read-only transactions

    // ---------------------------------------------------------------------------------------------
    // CONSTRUCTOR: build the script engine and the thread pool.
    //
    // Engine "customizers" change how every script is compiled:
    //  - DefaultImportCustomizerProvider : auto-imports TinkerPop classes, so scripts can use __, P, T, Order...
    //  - ThreadInterruptCustomizerProvider: inserts "was I interrupted?" checks into loops/methods, so
    //                                        future.cancel(true) actually stops the script
    //  - TimedInterruptCustomizerProvider : hard time limit compiled into the script (backstop)
    // WHY: without these, a script like while(true){} kept burning CPU even after we returned "timeout".
    // ---------------------------------------------------------------------------------------------
    /**
     * @param hardTimeoutMs upper bound for any single script; compiled into the script's loops so even
     *                      pure-Groovy code like {@code while(true){}} stops (0 = no hard limit)
     */
    public GremlinExecutor(TitanGraph graph, int maxResults, boolean allowWrites, long hardTimeoutMs) {
        this.graph = graph;
        // Thread-interrupt checks let cancel(true) stop running loops; the timed check is a backstop.
        this.engine = hardTimeoutMs > 0
                ? new GremlinGroovyScriptEngine(new DefaultImportCustomizerProvider(),
                        new ThreadInterruptCustomizerProvider(), new TimedInterruptCustomizerProvider(hardTimeoutMs))
                : new GremlinGroovyScriptEngine(new DefaultImportCustomizerProvider(),
                        new ThreadInterruptCustomizerProvider());
        this.maxResults = maxResults;
        this.allowWrites = allowWrites;
        // Thread pool: named threads ("gremlin-exec-1"...) for readable logs; daemon so they never block JVM exit.
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "gremlin-exec-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    // Used by the REST server to decide whether /load is allowed.
    public boolean allowsWrites() {
        return allowWrites;
    }

    // ---------------------------------------------------------------------------------------------
    // EXECUTE (public API): run a script with optional bindings and a timeout.
    //  1. submit the work to the pool -> we get a Future
    //  2. wait at most timeoutMs for it
    //  3. translate every failure into ScriptException (bad query) or TimeoutException (too slow),
    //     which the CLI prints and the REST server maps to HTTP 400 / 408
    // ---------------------------------------------------------------------------------------------
    public Result execute(String script, Map<String, Object> params, long timeoutMs)
            throws ScriptException, TimeoutException {
        if (script == null || script.trim().isEmpty()) {
            throw new ScriptException("empty Gremlin script");
        }
        long start = System.currentTimeMillis();
        Future<Result> f = pool.submit(() -> run(script, params, start)); // runs on a pool thread
        try {
            return timeoutMs > 0 ? f.get(timeoutMs, TimeUnit.MILLISECONDS) : f.get();
        } catch (TimeoutException e) {
            f.cancel(true); // interrupt the worker thread -> the script's interrupt checks make it stop
            throw new TimeoutException("query exceeded timeout of " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt(); // restore interrupt flag (Java best practice)
            throw new ScriptException("interrupted");
        } catch (CancellationException e) {
            throw new ScriptException("query cancelled");
        } catch (ExecutionException e) {
            // The script itself threw (syntax error, unknown step, read-only violation...). Unwrap the real cause.
            Throwable c = e.getCause();
            if (c instanceof ScriptException) throw (ScriptException) c;
            ScriptException se = new ScriptException(c.getClass().getSimpleName() + ": " + c.getMessage());
            se.initCause(c);
            throw se;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // RUN (on the worker thread): one transaction per query.
    //  1. open a transaction: read-only by default, so g.addV(...) fails with "read-only transaction"
    //  2. put user bindings + "graph" + "g" into the script's variables
    //       bindings = parameters, like PreparedStatement in SQL: values are never pasted into the query text
    //  3. evaluate the script
    //  4. iterate + detach the results (must happen while the transaction is still open)
    //  5. finally: commit (writes allowed and success) or roll back (everything else)
    // ---------------------------------------------------------------------------------------------
    private Result run(String script, Map<String, Object> params, long start) throws Exception {
        TitanTransaction tx = allowWrites ? graph.newTransaction() : graph.buildTransaction().readOnly().start();
        boolean ok = false;
        try {
            Bindings b = engine.createBindings();
            if (params != null) {
                b.putAll(params);           // e.g. who -> "josh" for the script  g.V().has('name', who)
            }
            b.put("graph", tx);             // set last, so a user binding can't replace them
            b.put("g", tx.traversal());     // "g" = traversal source on THIS transaction
            Object raw = engine.eval(script, b);

            List<Object> items = new ArrayList<>();
            boolean truncated = collect(raw, items);
            ok = true;
            return new Result(items, truncated, System.currentTimeMillis() - start);
        } finally {
            if (tx.isOpen()) {
                if (ok && allowWrites) tx.commit();
                else tx.rollback();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // COLLECT: Gremlin traversals are LAZY (nothing runs until you iterate), so we iterate here.
    // Handles every shape a script can return:
    //   Traversal / Iterator -> iterate it      Collection -> iterate it      array -> iterate it
    //   single value (e.g. a number, a map)    -> one result
    // Stops at maxResults and reports truncated=true. Also checks for interrupts so timeouts work mid-iteration.
    // (Path is Iterable but represents ONE result, so it is not expanded.)
    // ---------------------------------------------------------------------------------------------
    /** Iterates the script's return value into {@code out}; returns true if it was cut at maxResults. */
    private boolean collect(Object raw, List<Object> out) throws InterruptedException {
        Iterator<?> it;
        if (raw instanceof Iterator) {
            it = (Iterator<?>) raw;           // includes Traversal
        } else if (raw instanceof Iterable && !(raw instanceof Path)) {
            it = ((Iterable<?>) raw).iterator();
        } else if (raw instanceof Object[]) {
            List<Object> l = new ArrayList<>();
            Collections.addAll(l, (Object[]) raw);
            it = l.iterator();
        } else {
            if (raw != null) out.add(detach(raw));
            return false;
        }
        while (it.hasNext()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (out.size() >= maxResults) {
                return true;
            }
            out.add(detach(it.next()));
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // DETACH: a Titan vertex/edge is "live" and tied to its transaction. Once we roll the transaction back,
    // reading its properties (e.g. when writing JSON) would fail. DetachedFactory makes a plain copy
    // (id, label, properties). Maps and lists are walked recursively because group()/valueMap()/fold()
    // can contain elements inside them.
    // ---------------------------------------------------------------------------------------------
    /** Copies graph elements out of the transaction so they stay readable after it closes. */
    private static Object detach(Object o) {
        if (o instanceof Element || o instanceof Property || o instanceof Path) {
            return DetachedFactory.detach(o, true); // true = include properties
        }
        if (o instanceof Map) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                m.put(detach(e.getKey()), detach(e.getValue()));
            }
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object x : (List<?>) o) l.add(detach(x));
            return l;
        }
        return o; // plain values (String, Long, Double...) need no detaching
    }

    // Stop the worker threads (called on shutdown / end of CLI command).
    @Override
    public void close() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // RESULT: what a query returns -> items, whether it was cut off, and how long it took.
    // ---------------------------------------------------------------------------------------------
    /** Materialised query result. */
    public static final class Result {
        public final List<Object> items;
        public final boolean truncated;
        public final long tookMs;

        Result(List<Object> items, boolean truncated, long tookMs) {
            this.items = items;
            this.truncated = truncated;
            this.tookMs = tookMs;
        }
    }
}
