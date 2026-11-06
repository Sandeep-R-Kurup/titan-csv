package com.lektik.titancsv.query;

// Jackson = the JSON library. TinkerPop's GraphSON is built on top of it.
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
// TitanIoRegistry teaches the serializer Titan-specific types (e.g. edge ids like "4r0-6e8-7x1-6dk").
import com.thinkaurelius.titan.graphdb.tinkerpop.TitanIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders query results as GraphSON-backed JSON or as console-style text (maps become a table).
 * Used by the CLI (query/repl) and by the REST server (JSON only).
 */
public final class ResultFormatter {

    // The two CLI output formats: --format text | json
    public enum Format { TEXT, JSON }

    // ---------------------------------------------------------------------------------------------
    // GRAPHSON MAPPER: one shared, thread-safe JSON mapper that knows how to write vertices, edges,
    // paths and Titan ids. Created once (static) because building it is relatively expensive.
    // ---------------------------------------------------------------------------------------------
    private static final ObjectMapper GRAPHSON = GraphSONMapper.build()
            .addRegistry(TitanIoRegistry.INSTANCE)
            .create()
            .createMapper();

    // Utility class: only static methods, no instances.
    private ResultFormatter() {
    }

    /** Jackson mapper that understands TinkerPop/Titan types (vertices, edges, paths, Titan edge ids). */
    public static ObjectMapper mapper() {
        return GRAPHSON; // REST server reuses it for both request parsing and responses
    }

    // ---------------------------------------------------------------------------------------------
    // TO JSON: any object (map, list, vertex...) -> JSON string; pretty = indented for humans.
    // ---------------------------------------------------------------------------------------------
    public static String toJson(Object value, boolean pretty) {
        try {
            return pretty ? GRAPHSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                    : GRAPHSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise result: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ENVELOPE: the standard JSON response shape used by both CLI --format json and REST /query:
    //   {"result":[...], "count":N, "truncated":false, "tookMs":12}
    // ---------------------------------------------------------------------------------------------
    public static Map<String, Object> envelope(GremlinExecutor.Result r) {
        Map<String, Object> m = new LinkedHashMap<>(); // LinkedHashMap keeps the key order stable in JSON
        m.put("result", r.items);
        m.put("count", r.items.size());
        m.put("truncated", r.truncated);
        m.put("tookMs", r.tookMs);
        return m;
    }

    // ---------------------------------------------------------------------------------------------
    // FORMAT FOR THE TERMINAL
    //  JSON -> pretty envelope
    //  TEXT -> if every result is a map (e.g. valueMap()) print a table, otherwise Gremlin-console style
    //          lines "==>value"; then a footer like "(4 results, 158 ms)"
    // ---------------------------------------------------------------------------------------------
    public static String format(GremlinExecutor.Result r, Format format) {
        if (format == Format.JSON) {
            return toJson(envelope(r), true);
        }
        StringBuilder sb = new StringBuilder();
        if (!r.items.isEmpty() && allMaps(r.items)) {
            table(r.items, sb);
        } else {
            for (Object o : r.items) {
                sb.append("==>").append(o).append('\n');
            }
        }
        sb.append(String.format("(%d result%s%s, %d ms)", r.items.size(), r.items.size() == 1 ? "" : "s",
                r.truncated ? ", truncated" : "", r.tookMs));
        return sb.toString();
    }

    // True when every result row is a Map -> table output makes sense.
    private static boolean allMaps(List<Object> items) {
        for (Object o : items) {
            if (!(o instanceof Map)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // TABLE RENDERER
    //  1. columns = union of all map keys (rows may have different keys)
    //  2. compute each column's width = longest value (or header)
    //  3. print header, a "----+----" separator, then the padded rows
    // ---------------------------------------------------------------------------------------------
    private static void table(List<Object> rows, StringBuilder sb) {
        // 1. collect column names in first-seen order
        Set<String> cols = new LinkedHashSet<>();
        for (Object row : rows) {
            for (Object k : ((Map<?, ?>) row).keySet()) cols.add(String.valueOf(k));
        }
        List<String> header = new ArrayList<>(cols);
        List<String[]> cells = new ArrayList<>();
        int[] width = new int[header.size()];
        for (int i = 0; i < header.size(); i++) width[i] = header.get(i).length();
        // 2. turn each row into strings and track widths
        for (Object row : rows) {
            Map<String, Object> byName = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) row).entrySet()) byName.put(String.valueOf(e.getKey()), e.getValue());
            String[] line = new String[header.size()];
            for (int i = 0; i < header.size(); i++) {
                Object v = byName.get(header.get(i));
                // valueMap() wraps single values in lists: show [x] as x
                if (v instanceof List && ((List<?>) v).size() == 1) v = ((List<?>) v).get(0);
                line[i] = byName.containsKey(header.get(i)) ? String.valueOf(v) : ""; // missing key -> blank cell
                width[i] = Math.max(width[i], line[i].length());
            }
            cells.add(line);
        }
        // 3. print
        appendRow(header.toArray(new String[0]), width, sb);
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < width.length; i++) {
            if (i > 0) sep.append("-+-");
            for (int j = 0; j < width[i]; j++) sep.append('-');
        }
        sb.append(sep).append('\n');
        for (String[] line : cells) appendRow(line, width, sb);
    }

    // Prints one row with " | " between columns, each value padded to its column width
    // (except the last column, so lines have no trailing spaces).
    private static void appendRow(String[] values, int[] width, StringBuilder sb) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(values[i]);
            if (i < values.length - 1) {
                for (int j = values[i].length(); j < width[i]; j++) sb.append(' ');
            }
        }
        sb.append('\n');
    }
}
