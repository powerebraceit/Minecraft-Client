package dev.aurora.launcher.net;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small strict JSON codec for launcher manifests and OAuth responses; no third-party runtime dependency. */
public final class Json {
    private static final int MAX_DEPTH = 128;
    private static final int MAX_TEXT_LENGTH = 16 * 1024 * 1024;
    private Json() { }

    public static Object parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("JSON document exceeds 16 MiB");
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != text.length()) throw parser.error("Trailing input");
        return result;
    }

    public static Map<String, Object> object(String text) { return asObject(parse(text)); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) map;
    }

    /** Optional string fields use an empty value; unexpected types are rejected. */
    public static String str(Map<String, ?> object, String key) { return str(object, key, ""); }
    public static String str(Map<String, ?> object, String key, String fallback) {
        Object value = object.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string field: " + key);
        return text;
    }
    public static String requiredString(Map<String, ?> object, String key) {
        String value = str(object, key);
        if (value.isBlank()) throw new IllegalArgumentException("Missing string field: " + key);
        return value;
    }
    public static Map<String, Object> obj(Map<String, ?> object, String key) {
        Object value = object.get(key);
        return value == null ? Map.of() : asObject(value);
    }
    public static List<Object> arr(Map<String, ?> object, String key) {
        Object value = object.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected array field: " + key);
        return new ArrayList<>(list);
    }
    public static long number(Map<String, ?> object, String key, long fallback) {
        Object value = object.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected number field: " + key);
        try {
            // The parser preserves large or fractional numbers; never silently truncate manifest sizes.
            if (number instanceof BigDecimal decimal) return decimal.longValueExact();
            if (number instanceof java.math.BigInteger integer) return integer.longValueExact();
            if (number instanceof Double || number instanceof Float) {
                double decimal = number.doubleValue();
                if (!Double.isFinite(decimal) || decimal < Long.MIN_VALUE || decimal >= 0x1p63
                    || decimal != Math.rint(decimal)) throw new ArithmeticException();
            }
            return number.longValue();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected an in-range integer field: " + key);
        }
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        return output.toString();
    }

    private static void write(Object value, StringBuilder output, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH);
        if (value == null) output.append("null");
        else if (value instanceof String text) quote(text, output);
        else if (value instanceof Boolean bool) output.append(bool);
        else if (value instanceof Number number) {
            String text = number.toString();
            if (!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                throw new IllegalArgumentException("JSON numbers must be finite and valid");
            output.append(text);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON object keys must be strings");
                if (!first) output.append(',');
                first = false;
                quote(key, output);output.append(':');write(entry.getValue(), output, depth + 1);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> values) {
            output.append('[');boolean first = true;
            for (Object element : values) {
                if (!first) output.append(',');first = false;write(element, output, depth + 1);
            }
            output.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }

    private static void quote(String text, StringBuilder output) {
        validateSurrogates(text);
        output.append('"');
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4)
                            output.append(Character.forDigit((character >> shift) & 15, 16));
                    } else output.append(character);
                }
            }
        }
        output.append('"');
    }

    private static void validateSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Unpaired Unicode surrogate in JSON string");
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Unpaired Unicode surrogate in JSON string");
            }
        }
    }

    private static final class Parser {
        private final String text;
        private int position;
        private Parser(String text) { this.text = text; }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("Nesting limit exceeded");
            space();
            if (position == text.length()) throw error("Expected a value");
            return switch (text.charAt(position)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        private Map<String, Object> object(int depth) {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();if (take('}')) return result;
            do {
                space();
                if (position == text.length() || text.charAt(position) != '"') throw error("Expected an object key");
                String key = string();space();expect(':');
                Object value = value(depth + 1);
                if (result.containsKey(key)) throw error("Duplicate object key");
                result.put(key, value);space();
                if (take('}')) return result;
                expect(',');space();
            } while (true);
        }
        private List<Object> array(int depth) {
            position++;
            List<Object> result = new ArrayList<>();
            space();if (take(']')) return result;
            do {
                result.add(value(depth + 1));space();
                if (take(']')) return result;
                expect(',');space();
            } while (true);
        }
        private String string() {
            expect('"');StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == '"') {
                    String value = result.toString();validateSurrogates(value);return value;
                }
                if (character < 0x20) throw error("Unescaped control character");
                if (character != '\\') {result.append(character);continue;}
                if (position == text.length()) throw error("Truncated escape");
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (text.length() - position < 4) throw error("Truncated Unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(position++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw error("Invalid Unicode escape");
                            code = code * 16 + digit;
                        }
                        result.append((char) code);
                    }
                    default -> throw error("Invalid string escape");
                }
            }
            throw error("Unterminated string");
        }
        private Object literal(String expected, Object result) {
            if (!text.startsWith(expected, position)) throw error("Invalid literal");
            position += expected.length();return result;
        }
        private Number number() {
            int start = position;
            take('-');
            if (!take('0')) {
                if (!digit(false)) throw error("Expected a number");
                while (digit(true)) { }
            }
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                if (!digit(true)) throw error("Missing fraction digits");
                while (digit(true)) { }
            }
            if (take('e') || take('E')) {
                decimal = true;
                if (!take('+')) take('-');
                if (!digit(true)) throw error("Missing exponent digits");
                while (digit(true)) { }
            }
            String value = text.substring(start, position);
            try {
                if (!decimal) {
                    try { return Long.valueOf(value); }
                    catch (NumberFormatException tooLargeForLong) { return new BigDecimal(value); }
                }
                return new BigDecimal(value);
            } catch (NumberFormatException invalid) { throw error("Invalid number"); }
        }
        private boolean digit(boolean allowZero) {
            if (position == text.length()) return false;
            char character = text.charAt(position);
            if (character < (allowZero ? '0' : '1') || character > '9') return false;
            position++;return true;
        }
        private void space() {
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') return;
                position++;
            }
        }
        private boolean take(char expected) {
            if (position < text.length() && text.charAt(position) == expected) { position++;return true; }
            return false;
        }
        private void expect(char expected) { if (!take(expected)) throw error("Expected '" + expected + "'"); }
        private IllegalArgumentException error(String message) {
            // Deliberately do not include response contents: OAuth responses can contain tokens.
            return new IllegalArgumentException("Invalid JSON at character " + position + ": " + message);
        }
    }
}
