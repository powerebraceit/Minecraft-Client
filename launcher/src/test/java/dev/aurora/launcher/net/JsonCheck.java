package dev.aurora.launcher.net;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone deterministic checks; does not contact Microsoft or run any game. */
public final class JsonCheck {
    private static int checks;
    public static void main(String[] args) {
        Map<String, Object> parsed = Json.object(" {\"name\":\"Aurora\",\"count\":42,\"ready\":true,\"nothing\":null,\"nested\":{\"items\":[1,false,null]}} ");
        equal("Aurora", Json.str(parsed, "name"));equal(42L, parsed.get("count"));equal(true, parsed.get("ready"));
        require(parsed.containsKey("nothing") && parsed.get("nothing") == null);
        equal(Arrays.asList(1L, false, null), Json.arr(Json.obj(parsed, "nested"), "items"));
        equal("åäö / 😀", Json.parse("\"åäö \\/ \\uD83D\\uDE00\""));
        equal("\"\\\b\f\n\r\t", Json.parse("\"\\\"\\\\\\b\\f\\n\\r\\t\""));
        equal(new BigDecimal("-12.50e+2"), Json.parse("-12.50e+2"));
        equal(new BigDecimal("9223372036854775808"), Json.parse("9223372036854775808"));
        equal(-9223372036854775808L, Json.parse("-9223372036854775808"));

        var value = new LinkedHashMap<String, Object>();
        value.put("unicode", "Svenska åäö 😀");value.put("escaped", "\"\\\n\u0001");
        value.put("empty", List.of());value.put("null", null);value.put("values", Arrays.asList(true, false, null, 5L));
        equal(value, Json.parse(Json.stringify(value)));
        equal("fallback", Json.str(parsed, "missing", "fallback"));equal(Map.of(), Json.obj(parsed, "missing"));
        equal(List.of(), Json.arr(parsed, "missing"));equal(12L, Json.number(parsed, "missing", 12));
        reject(() -> Json.number(Json.object("{\"size\":9223372036854775808}"), "size", 0));
        reject(() -> Json.number(Json.object("{\"size\":1.5}"), "size", 0));
        reject(() -> Json.number(Map.of("size", Double.NaN), "size", 0));
        equal(4L, Json.number(Json.object("{\"size\":4.0}"), "size", 0));
        reject(() -> Json.requiredString(parsed, "missing"));reject(() -> Json.str(parsed, "count"));
        reject(() -> Json.object("[]"));reject(() -> Json.asObject(Map.of(1, "not a string key")));

        for (String invalid : List.of("", " ", "01", "-01", "1.", "1e", "1e++2", "+1", "NaN", "Infinity",
            "true trailing", "[1,]", "{\"a\":1,}", "{a:1}", "{\"a\":1,\"a\":2}", "{}{}", "\"bad\\x\"",
            "\"unterminated", "\"line\nfeed\"", "\"\\uD800\"", "\"\\uDC00\"", "\"\\uFFＦF\"")) {
            reject(() -> Json.parse(invalid));
        }
        reject(() -> Json.parse("[".repeat(130) + "0" + "]".repeat(130)));
        reject(() -> Json.stringify(Double.NaN));reject(() -> Json.stringify(Double.POSITIVE_INFINITY));
        reject(() -> Json.stringify(new Object()));reject(() -> Json.stringify(Map.of(1, "bad key")));
        reject(() -> Json.stringify("\uD800"));
        var cyclic = new ArrayList<Object>();cyclic.add(cyclic);reject(() -> Json.stringify(cyclic));
        System.out.println("PASS: JsonCheck " + checks + " checks");
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("JSON value mismatch");checks++;
    }
    private static void require(boolean value) {if (!value) throw new AssertionError("JSON check failed");checks++;}
    private static void reject(Runnable action) {
        try {action.run();}catch (IllegalArgumentException expected) {checks++;return;}
        throw new AssertionError("Invalid JSON value was accepted");
    }
}
