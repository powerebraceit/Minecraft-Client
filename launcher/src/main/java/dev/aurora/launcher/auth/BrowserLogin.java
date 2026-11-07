package dev.aurora.launcher.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** System-browser public-client OAuth with a one-use loopback callback and PKCE. */
public final class BrowserLogin {
    private static final String AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int QUERY_LIMIT = 65_536;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private BrowserLogin() { }

    /** Allows Desktop.browse failures to be reported immediately instead of waiting for a timeout. */
    @FunctionalInterface public interface BrowserOpener {
        void open(URI uri) throws Exception;
    }

    public static Account login(AuthService auth, String clientId, BrowserOpener openBrowser,
                                Consumer<String> status, BooleanSupplier cancelled) throws AuthException {
        return login(auth, clientId, openBrowser, status, cancelled, TIMEOUT, new SecureRandom());
    }

    // The bounded overload keeps protocol checks fast without exposing test switches in the UI.
    static Account login(AuthService auth, String clientId, BrowserOpener openBrowser,
                         Consumer<String> status, BooleanSupplier cancelled,
                         Duration timeout, SecureRandom random) throws AuthException {
        Objects.requireNonNull(auth); Objects.requireNonNull(openBrowser);
        Objects.requireNonNull(status); Objects.requireNonNull(cancelled);
        Objects.requireNonNull(timeout); Objects.requireNonNull(random);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(TIMEOUT) > 0)
            throw new IllegalArgumentException("Login timeout must be positive and at most five minutes");
        AuthService.validateClientId(clientId);
        checkCancelled(cancelled);

        String state = randomToken(random);
        String verifier = randomToken(random);
        Callback callback;
        String redirect;
        HttpServer server = null;
        ExecutorService executor = null;
        try {
            // Bind only IPv4 loopback; the actual ephemeral port belongs to this login attempt.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            redirect = "http://localhost:" + server.getAddress().getPort() + "/";
            CompletableFuture<Callback> received = new CompletableFuture<>();
            byte[] expectedState = state.getBytes(StandardCharsets.US_ASCII);
            server.createContext("/", exchange -> handle(exchange, expectedState, received));
            executor = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "aurora-oauth-callback");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            long started = System.nanoTime();
            checkCancelled(cancelled);
            status.accept("Öppnar Microsoft-inloggningen i din webbläsare…");
            try { openBrowser.open(authorizeUri(clientId, redirect, state, verifier)); }
            catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw cancelled();
                }
                // Browser exceptions may contain the authorization URI. Do not retain that cause.
                throw new AuthException("BROWSER_UNAVAILABLE", "Webbläsaren kunde inte öppnas. Kontrollera din standardwebbläsare och försök igen.");
            }
            status.accept("Logga in hos Microsoft i webbläsaren. Aurora fortsätter automatiskt.");
            callback = awaitCallback(received, cancelled, started, timeout.toNanos());
        } catch (IOException unavailable) {
            throw new AuthException("CALLBACK_UNAVAILABLE", "Den lokala inloggningsanslutningen kunde inte startas. Försök igen.");
        } finally {
            if (server != null) server.stop(0);
            if (executor != null) executor.shutdownNow();
        }

        // The listener is already closed before contacting Microsoft or Minecraft services.
        checkCancelled(cancelled);
        if (callback.error() != null) {
            if (callback.error().equals("access_denied"))
                throw new AuthException("ACCESS_DENIED", "Microsoft-inloggningen avbröts. Försök igen när du vill logga in.");
            throw new AuthException("BROWSER_LOGIN_FAILED", "Microsoft kunde inte slutföra inloggningen. Kontrollera appens inställningar och försök igen.");
        }
        return auth.authenticateAuthorizationCode(clientId, callback.code(), redirect, verifier, status, cancelled);
    }

    private static URI authorizeUri(String clientId, String redirect, String state, String verifier) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("response_type", "code");
        query.put("redirect_uri", redirect);
        query.put("response_mode", "query");
        query.put("scope", "XboxLive.signin offline_access");
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        query.put("state", state);
        query.put("prompt", "select_account");
        StringBuilder encoded = new StringBuilder(AUTHORIZE).append('?');
        query.forEach((key, value) -> {
            if (encoded.charAt(encoded.length() - 1) != '?') encoded.append('&');
            encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return URI.create(encoded.toString());
    }

    private static String randomToken(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError("SHA-256 is required by Java", impossible); }
    }

    private static Callback awaitCallback(CompletableFuture<Callback> result, BooleanSupplier cancelled,
                                          long started, long timeoutNanos) throws AuthException {
        while (true) {
            checkCancelled(cancelled);
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0)
                throw new AuthException("LOGIN_TIMEOUT", "Microsoft-inloggningen tog för lång tid. Försök logga in igen.");
            try { return result.get(Math.min(POLL_NANOS, remaining), TimeUnit.NANOSECONDS); }
            catch (TimeoutException waiting) { /* Recheck cancellation at least every 200 ms. */ }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw cancelled();
            } catch (ExecutionException impossible) {
                throw new AuthException("BROWSER_LOGIN_FAILED", "Inloggningen kunde inte slutföras. Försök igen.");
            }
        }
    }

    private static void handle(HttpExchange exchange, byte[] expectedState,
                               CompletableFuture<Callback> result) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, false);
                return;
            }
            if (!exchange.getRequestURI().getRawPath().equals("/")) {
                respond(exchange, 404, false);
                return;
            }
            Map<String, String> query;
            try { query = parseQuery(exchange.getRequestURI().getRawQuery()); }
            catch (IllegalArgumentException malformed) {
                respond(exchange, 400, false);
                return;
            }
            String state = query.get("state");
            boolean codePresent = query.containsKey("code");
            boolean errorPresent = query.containsKey("error");
            if (state == null || !MessageDigest.isEqual(expectedState, state.getBytes(StandardCharsets.UTF_8))
                || codePresent == errorPresent
                || (codePresent && (query.get("code").isBlank() || query.get("code").length() > 16_384))
                || (errorPresent && (query.get("error").isBlank() || query.get("error").length() > 256))) {
                respond(exchange, 400, false);
                return;
            }
            Callback callback = new Callback(query.get("code"), query.get("error"));
            // Finish the page before waking the login thread, which immediately closes the server.
            // A browser disconnect still delivers its valid code; later callbacks cannot replace it.
            synchronized (result) {
                if (result.isDone()) respond(exchange, 410, false);
                else {
                    try { respond(exchange, 200, codePresent); }
                    finally { result.complete(callback); }
                }
            }
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > QUERY_LIMIT) throw new IllegalArgumentException();
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : raw.split("&", -1)) {
            if (values.size() >= 16) throw new IllegalArgumentException();
            int equals = field.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException();
            String key = decode(field.substring(0, equals));
            String value = decode(field.substring(equals + 1));
            if (key.isEmpty() || values.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
        }
        return values;
    }

    /** Strict UTF-8 form decoding rejects malformed escapes and encoded duplicate parameter names. */
    private static String decode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%') {
                if (index + 2 >= value.length()) throw new IllegalArgumentException();
                int high = Character.digit(value.charAt(++index), 16);
                int low = Character.digit(value.charAt(++index), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException();
                bytes.write((high << 4) | low);
            } else if (character == '+') bytes.write(' ');
            else if (character > 0x7f) throw new IllegalArgumentException();
            else bytes.write(character);
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            if (decoded.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            return decoded;
        } catch (CharacterCodingException malformed) { throw new IllegalArgumentException(); }
    }

    private static void respond(HttpExchange exchange, int status, boolean success) throws IOException {
        String message = status == 200
            ? (success ? "Microsoft-svaret har tagits emot. Återgå till Aurora för att slutföra inloggningen."
                       : "Inloggningen kunde inte slutföras. Återgå till Aurora för att försöka igen.")
            : "Den här inloggningsförfrågan är ogiltig. Återgå till Microsoft-fliken eller Aurora.";
        // No query parameter, authorization code, state, error text or token is reflected in this page.
        byte[] body = ("<!doctype html><html lang=\"sv\"><meta charset=\"utf-8\"><title>Aurora</title>"
            + "<meta name=\"viewport\" content=\"width=device-width\"><body><h1>Aurora</h1><p>"
            + message + "</p><p>Du kan stänga den här fliken.</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws AuthException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
    }
    private static AuthException cancelled() { return new AuthException("CANCELLED", "Inloggningen avbröts."); }

    private record Callback(String code, String error) {
        @Override public String toString() { return "BrowserCallback[REDACTED]"; }
    }
}
