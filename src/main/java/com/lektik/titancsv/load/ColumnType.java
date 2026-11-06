package com.lektik.titancsv.load;

import java.util.Locale;

/**
 * Property value types that can be declared in a CSV header as {@code name:type}.
 *
 * <p>WHY: CSV cells are always text. Without types, "29" would be stored as a String and a query like
 * {@code has('age', gt(25))} would not work. A header such as {@code age:int} tells the loader to store an Integer.
 */
public enum ColumnType {
    // The supported types. STRING is the default when a header has no ":type" suffix.
    STRING, INT, LONG, FLOAT, DOUBLE, BOOLEAN;

    // ---------------------------------------------------------------------------------------------
    // PARSE TYPE NAME: turns the text after ':' in a header into an enum value.
    // Accepts a few friendly aliases (integer, bool, number...). Unknown names are a clear error.
    // ---------------------------------------------------------------------------------------------
    public static ColumnType parse(String name) {
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "string": case "str": case "text": return STRING;
            case "int": case "integer": return INT;
            case "long": return LONG;
            case "float": return FLOAT;
            case "double": case "number": return DOUBLE;
            case "bool": case "boolean": return BOOLEAN;
            default:
                throw new IllegalArgumentException("Unknown column type '" + name
                        + "' (expected string, int, long, float, double or boolean)");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CONVERT ONE CELL: raw CSV text -> Java object of this type.
    //  - empty cell -> null, which the loader treats as "don't set this property at all"
    //  - bad value (e.g. "abc" for int) -> IllegalArgumentException with a readable message;
    //    the loader catches it and reports it as file:line
    // ---------------------------------------------------------------------------------------------
    /** Converts a raw cell; an empty cell yields {@code null}, meaning "no property". */
    public Object convert(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String v = raw.trim();
        try {
            switch (this) {
                case INT: return Integer.valueOf(v);
                case LONG: return Long.valueOf(v);
                case FLOAT: return Float.valueOf(v);
                case DOUBLE: return Double.valueOf(v);
                case BOOLEAN:
                    // Strict: only true/false, so typos like "yes" are reported instead of silently becoming false.
                    if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
                    if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
                    throw new NumberFormatException();
                default: return raw; // STRING: keep the text as-is
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value '" + raw + "' is not a valid " + name().toLowerCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // COLUMN = one parsed header cell, e.g. "age:int" -> name "age", type INT.
    // Uses the LAST ':' so names containing ':' still work; "name" with no ':' is a STRING column.
    // ---------------------------------------------------------------------------------------------
    /** A CSV header cell split into property name and type, e.g. {@code age:int}. */
    public static final class Column {
        public final String name;
        public final ColumnType type;

        private Column(String name, ColumnType type) {
            this.name = name;
            this.type = type;
        }

        public static Column parse(String header) {
            String h = header.trim();
            int colon = h.lastIndexOf(':');
            if (colon <= 0) {                       // no type given (or header starts with ':')
                return new Column(h, STRING);
            }
            return new Column(h.substring(0, colon).trim(), ColumnType.parse(h.substring(colon + 1)));
        }
    }
}
