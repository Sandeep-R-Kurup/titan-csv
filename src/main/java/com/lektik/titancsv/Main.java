package com.lektik.titancsv;

import com.lektik.titancsv.graph.GraphManager;
import com.lektik.titancsv.load.CsvLoader;
import com.lektik.titancsv.query.GremlinExecutor;
import com.lektik.titancsv.query.ResultFormatter;
import com.lektik.titancsv.rest.RestServer;
// jline: terminal line editing + history for the REPL (arrow keys work).
import jline.console.ConsoleReader;
// picocli: turns annotated classes into a command-line interface (parsing, --help, validation).
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

/**
 * Entry point of the jar: {@code java -jar titan-csv.jar <subcommand> [options]}.
 *
 * <p>Subcommands: {@code load} (CSV -> graph), {@code query} (one query), {@code repl} (interactive), {@code serve}
 * (REST API). Each subcommand is a small class below; picocli fills its fields from the command-line arguments and
 * then calls {@code call()}. The returned Integer becomes the process exit code.
 */
@Command(name = "titan-csv", mixinStandardHelpOptions = true, version = "titan-csv 0.1.0 (Titan 1.0.0, TinkerPop 3.0.1)",
        description = "Load CSV files into a Titan graph and query them with Gremlin.",
        subcommands = {Main.Load.class, Main.Query.class, Main.Repl.class, Main.Serve.class})
public final class Main implements Runnable {

    // ---------------------------------------------------------------------------------------------
    // MAIN: hand the arguments to picocli, which picks the subcommand, parses options, runs it,
    // and returns an exit code (0 = ok, 1 = query error, 2 = rows skipped / bad usage).
    // ---------------------------------------------------------------------------------------------
    public static void main(String[] args) {
        int code = new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true) // allows --format json / JSON / Json
                // Unexpected errors: print one clear line instead of a Java stack trace.
                // Set TITAN_CSV_DEBUG=1 to see the full stack trace when troubleshooting.
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    if (System.getenv("TITAN_CSV_DEBUG") != null) {
                        ex.printStackTrace();
                    }
                    System.err.println("Error: " + ex.getMessage());
                    return 1;
                })
                .execute(args);
        System.exit(code);
    }

    // Runs when no subcommand is given: just print usage.
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    // =============================================================================================
    // SHARED OPTION GROUPS ("mixins"): defined once, reused by several subcommands via @Mixin.
    // =============================================================================================

    // -c / --config: which Titan properties file to use (persistent BerkeleyDB by default).
    static final class GraphOptions {
        @Option(names = {"-c", "--config"}, defaultValue = "conf/titan-berkeleyje.properties",
                description = "Titan properties file (default: ${DEFAULT-VALUE})")
        String config;
    }

    // CSV loading options; used by `load`, and optionally by query/repl/serve to load before starting.
    static final class CsvOptions {
        @Option(names = {"-v", "--vertices"}, paramLabel = "FILE",
                description = "Vertex CSV (columns id,label,...). Repeatable.")
        List<Path> vertices = new ArrayList<>();

        @Option(names = {"-e", "--edges"}, paramLabel = "FILE",
                description = "Edge CSV (columns from,to,label,...). Repeatable.")
        List<Path> edges = new ArrayList<>();

        @Option(names = "--strict", description = "Abort on the first bad row instead of skipping it")
        boolean strict;

        @Option(names = "--batch", defaultValue = "10000", description = "Rows per commit (default: ${DEFAULT-VALUE})")
        int batch;

        // Were any CSV files given?
        boolean any() {
            return !vertices.isEmpty() || !edges.isEmpty();
        }

        // Run the loader and print its report to stderr (stderr so stdout stays clean for query results).
        CsvLoader.Report load(GraphManager gm) throws Exception {
            CsvLoader.Report r = new CsvLoader(gm.graph(), batch, strict).load(vertices, edges);
            System.err.println(r);
            return r;
        }
    }

    // Query execution options: result cap, timeout, and whether queries may modify the graph.
    static final class ExecOptions {
        @Option(names = "--max-results", defaultValue = "1000", description = "Result cap (default: ${DEFAULT-VALUE})")
        int maxResults;

        @Option(names = "--timeout", defaultValue = "30000", description = "Query timeout in ms (default: ${DEFAULT-VALUE})")
        long timeoutMs;

        @Option(names = "--allow-writes", description = "Commit graph mutations made by queries (and enable REST /load)")
        boolean allowWrites;
    }

    // =============================================================================================
    // SUBCOMMAND: load
    //   java -jar titan-csv.jar load [--drop] -v people.csv -e knows.csv
    //   1. optionally wipe the graph (--drop)
    //   2. open the graph (creates csvId index if needed)
    //   3. load the CSVs, print the report
    //   4. exit 2 if any rows were skipped, so scripts/CI can detect partial loads
    // =============================================================================================
    @Command(name = "load", mixinStandardHelpOptions = true, description = "Load vertex and edge CSV files into the graph.")
    static final class Load implements Callable<Integer> {
        @Mixin GraphOptions graph = new GraphOptions();
        @Mixin CsvOptions csv = new CsvOptions();

        @Option(names = "--drop", description = "Delete all existing graph data before loading")
        boolean drop;

        @Override
        public Integer call() throws Exception {
            if (!csv.any() && !drop) {
                throw new CommandLine.ParameterException(new CommandLine(this), "give --vertices and/or --edges");
            }
            if (drop) {
                GraphManager.drop(graph.config);
                System.err.println("Dropped existing graph data.");
            }
            // try-with-resources: graph is closed (and BerkeleyDB lock released) when done
            try (GraphManager gm = GraphManager.open(graph.config)) {
                CsvLoader.Report r = csv.load(gm);
                return r.rowsSkipped > 0 ? 2 : 0;
            }
        }
    }

    // =============================================================================================
    // SUBCOMMAND: query
    //   java -jar titan-csv.jar query "g.V().count()"  [-f file.groovy] [-p name=value] [--format json]
    //   1. get the script from the argument or from --file
    //   2. open graph + executor (optionally load CSVs first, handy with the in-memory config)
    //   3. run with -p bindings, print formatted result; exit 1 on query error/timeout
    // =============================================================================================
    @Command(name = "query", mixinStandardHelpOptions = true,
            description = "Run one Gremlin query, e.g. \"g.V().hasLabel('person').values('name')\".")
    static final class Query implements Callable<Integer> {
        @Mixin GraphOptions graph = new GraphOptions();
        @Mixin CsvOptions csv = new CsvOptions();
        @Mixin ExecOptions exec = new ExecOptions();

        @Parameters(arity = "0..1", paramLabel = "GREMLIN", description = "Gremlin-Groovy script")
        String script;

        @Option(names = {"-f", "--file"}, description = "Read the script from a file")
        Path file;

        @Option(names = {"-p", "--param"}, paramLabel = "NAME=VALUE",
                description = "Script binding (string value), e.g. -p name=marko. Repeatable.")
        Map<String, String> params = new LinkedHashMap<>();

        @Option(names = "--format", defaultValue = "text", description = "text or json (default: ${DEFAULT-VALUE})")
        ResultFormatter.Format format;

        @Override
        public Integer call() throws Exception {
            // 1. script source: --file wins over the positional argument
            String gremlin = file != null ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : script;
            if (gremlin == null) {
                throw new CommandLine.ParameterException(new CommandLine(this), "give a GREMLIN script or --file");
            }
            // 2. open graph + executor; both closed automatically at the end
            try (GraphManager gm = GraphManager.open(graph.config);
                 GremlinExecutor ex = new GremlinExecutor(gm.graph(), exec.maxResults, exec.allowWrites, exec.timeoutMs)) {
                if (csv.any()) csv.load(gm);
                // 3. run and print
                Map<String, Object> bindings = new LinkedHashMap<String, Object>(params);
                try {
                    System.out.println(ResultFormatter.format(ex.execute(gremlin, bindings, exec.timeoutMs), format));
                    return 0;
                } catch (ScriptException | TimeoutException e) {
                    System.err.println("Error: " + e.getMessage());
                    return 1;
                }
            }
        }
    }

    // =============================================================================================
    // SUBCOMMAND: repl  (Read-Eval-Print Loop, like the Gremlin console)
    //   Reads one query per line, runs it, prints the result, repeats until :quit / Ctrl+D.
    //   Extras: lines ending in "\" continue on the next line; :format text|json; :help.
    //   One graph + executor stay open for the whole session (fast after the first query).
    // =============================================================================================
    @Command(name = "repl", mixinStandardHelpOptions = true, description = "Interactive Gremlin prompt.")
    static final class Repl implements Callable<Integer> {
        @Mixin GraphOptions graph = new GraphOptions();
        @Mixin CsvOptions csv = new CsvOptions();
        @Mixin ExecOptions exec = new ExecOptions();

        @Override
        public Integer call() throws Exception {
            try (GraphManager gm = GraphManager.open(graph.config);
                 GremlinExecutor ex = new GremlinExecutor(gm.graph(), exec.maxResults, exec.allowWrites, exec.timeoutMs)) {
                if (csv.any()) csv.load(gm);
                LineSource in = LineSource.create();
                ResultFormatter.Format format = ResultFormatter.Format.TEXT;
                System.out.println("Gremlin over Titan. Bindings: g, graph. Commands: :format text|json, :help, :quit."
                        + " End a line with \\ to continue it.");
                StringBuilder pending = new StringBuilder(); // collects multi-line input
                String line;
                // readLine returns null on Ctrl+D / end of input -> loop ends
                while ((line = in.readLine(pending.length() == 0 ? "gremlin> " : "......> ")) != null) {
                    // multi-line: strip the trailing "\" and keep reading
                    if (line.endsWith("\\")) {
                        pending.append(line, 0, line.length() - 1).append('\n');
                        continue;
                    }
                    String cmd = pending.append(line).toString().trim();
                    pending.setLength(0);
                    if (cmd.isEmpty()) continue;
                    // REPL commands (start with ':'), handled here and not sent to Gremlin
                    if (cmd.equals(":quit") || cmd.equals(":q") || cmd.equals(":exit")) break;
                    if (cmd.equals(":help")) {
                        System.out.println("Examples:\n  g.V().count()\n  g.V().hasLabel('person').valueMap()\n"
                                + "  g.V().has('name','marko').out('knows').values('name')");
                        continue;
                    }
                    if (cmd.startsWith(":format")) {
                        String f = cmd.substring(7).trim().toUpperCase(Locale.ROOT);
                        try {
                            format = ResultFormatter.Format.valueOf(f);
                        } catch (IllegalArgumentException e) {
                            System.out.println("usage: :format text|json");
                        }
                        continue;
                    }
                    // Anything else is a Gremlin query. Errors are printed but don't end the session.
                    try {
                        System.out.println(ResultFormatter.format(ex.execute(cmd, null, exec.timeoutMs), format));
                    } catch (ScriptException | TimeoutException e) {
                        System.out.println("Error: " + e.getMessage());
                    }
                }
                return 0;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // LINE SOURCE: where the REPL reads input from.
    //   real terminal -> jline (history, arrow keys); setExpandEvents(false) so "!" in queries isn't mangled
    //   piped input   -> plain stdin reader (lets you script the REPL: printf "g.V().count()\n" | ... repl)
    // ---------------------------------------------------------------------------------------------
    /** jline when attached to a terminal (history, line editing), plain stdin otherwise. */
    interface LineSource {
        String readLine(String prompt) throws Exception;

        static LineSource create() throws Exception {
            if (System.console() != null) {
                ConsoleReader reader = new ConsoleReader();
                reader.setExpandEvents(false);
                return reader::readLine;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return prompt -> br.readLine();
        }
    }

    // =============================================================================================
    // SUBCOMMAND: serve  (REST API)
    //   java -jar titan-csv.jar serve [--port 8182] [--host 127.0.0.1] [--token X] [--allow-writes]
    //   1. open graph + executor (+ optional CSV load)
    //   2. start the RestServer
    //   3. register a shutdown hook so Ctrl+C stops the server cleanly
    //   4. block the main thread until shutdown (CountDownLatch)
    // =============================================================================================
    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Start the JSON REST API.")
    static final class Serve implements Callable<Integer> {
        @Mixin GraphOptions graph = new GraphOptions();
        @Mixin CsvOptions csv = new CsvOptions();
        @Mixin ExecOptions exec = new ExecOptions();

        // Default 127.0.0.1 = only reachable from this machine (safe default for a code-executing API).
        @Option(names = "--host", defaultValue = "127.0.0.1", description = "Bind address (default: ${DEFAULT-VALUE})")
        String host;

        // 8182 is the conventional Gremlin Server port.
        @Option(names = "--port", defaultValue = "8182", description = "Port (default: ${DEFAULT-VALUE})")
        int port;

        // Token can come from the flag or the TITAN_CSV_TOKEN environment variable (keeps it out of shell history).
        @Option(names = "--token", defaultValue = "${env:TITAN_CSV_TOKEN}",
                description = "Require 'Authorization: Bearer TOKEN' (or set TITAN_CSV_TOKEN)")
        String token;

        @Override
        public Integer call() throws Exception {
            // 1. graph + executor (not try-with-resources: they must live until shutdown)
            GraphManager gm = GraphManager.open(graph.config);
            GremlinExecutor ex = new GremlinExecutor(gm.graph(), exec.maxResults, exec.allowWrites, exec.timeoutMs);
            if (csv.any()) csv.load(gm);
            // 2. server
            RestServer server = new RestServer(gm.graph(), ex, host, port, token, exec.timeoutMs);
            // 3. Ctrl+C handling
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // Titan registers its own shutdown hook that closes the graph.
                // (Closing it here too caused a "Shutdown in progress" stack trace, so we don't.)
                server.close();
                ex.close();
                stopped.countDown();
            }));
            server.start();
            System.err.printf("Listening on http://%s:%d  (writes %s, auth %s)%n", host, server.port(),
                    exec.allowWrites ? "enabled" : "disabled", token == null || token.isEmpty() ? "off" : "on");
            // Warn when exposed beyond localhost without a token.
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host) && (token == null || token.isEmpty())) {
                System.err.println("WARNING: /query runs arbitrary Groovy code; use --token when binding beyond localhost.");
            }
            // 4. wait here until the shutdown hook counts down
            stopped.await();
            return 0;
        }
    }
}
