package dev.aurora.launcher.auth;

import dev.aurora.launcher.net.Json;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback callbacks; all Microsoft/Xbox/Minecraft requests use an in-memory transport. */
public final class BrowserLoginCheck {
    private static final String CLIENT = "12345678-1234-1234-1234-123456789abc";
    private static final String CODE = "TEST-CODE-SECRET+&= ü";
    private static final String SECRET = "TEST-ONLY-NO-REAL-TOKEN";
    private static int checks;

    public static void main(String[] args) throws Exception {
        browserLoginUsesPkceAndClosesItsCallback();
        malformedRequestsDoNotConsumeTheLogin();
        asynchronousBrowserGetsTheCompletePage();
        stateAndVerifierChangeBetweenAttempts();
        browserErrorsStayRedacted();
        browserFailureAndTimeoutCloseTheListener();
        cancellationStopsPromptlyAndClosesTheListener();
        invalidClientAndPreCancelledLoginNeverOpenABrowser();
        System.out.println("PASS: " + checks + " browser OAuth and loopback checks");
    }

    private static void browserLoginUsesPkceAndClosesItsCallback() throws Exception {
        Fixture fixture = new Fixture(true);
        AtomicReference<URI> authorization = new AtomicReference<>();
        AtomicReference<Page> page = new AtomicReference<>();
        List<String> status = new ArrayList<>();
        Account account = BrowserLogin.login(fixture.service(), CLIENT, uri -> {
            authorization.set(uri);
            Map<String, String> query = query(uri);
            check(uri.getScheme().equals("https") && uri.getHost().equals("login.microsoftonline.com")
                && uri.getPath().equals("/consumers/oauth2/v2.0/authorize"), "Open only Microsoft's official authorize endpoint");
            check(query.get("client_id").equals(CLIENT) && query.get("response_type").equals("code"), "Use the supplied application ID and authorization-code flow");
            check(query.get("scope").equals("XboxLive.signin offline_access") && query.get("response_mode").equals("query"), "Request Xbox sign-in and refresh permission");
            check(query.get("state").matches("[A-Za-z0-9_-]{43}"), "State has 256 random bits in a URL-safe form");
            check(query.get("code_challenge_method").equals("S256") && query.get("code_challenge").length() == 43, "Require S256 PKCE");
            URI redirect = URI.create(query.get("redirect_uri"));
            check(redirect.getScheme().equals("http") && redirect.getHost().equals("localhost")
                && redirect.getPort() > 0 && redirect.getPath().equals("/"), "Use this attempt's ephemeral root loopback URI");
            check(!query.containsKey("client_secret") && !query.containsKey("code_verifier"), "Never send a client secret or verifier in the browser URL");
            page.set(send(callback(query, CODE), "GET"));
        }, status::add, () -> false);
        check(account.name().equals("AuroraUser"), "Automatically authenticate the owned Minecraft profile");
        Map<String, String> authorizeQuery = query(authorization.get());
        Map<String, String> token = form(fixture.requests.get(0).body());
        check(token.get("grant_type").equals("authorization_code") && token.get("code").equals(CODE), "Redeem the exact callback code without a device-code step");
        check(token.get("redirect_uri").equals(authorizeQuery.get("redirect_uri")), "Redeem against the exact registered loopback redirect");
        String verifier = token.get("code_verifier");
        check(verifier.matches("[A-Za-z0-9_-]{43}"), "Keep the independent 256-bit verifier in memory");
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        check(challenge.equals(authorizeQuery.get("code_challenge")) && !verifier.equals(authorizeQuery.get("state")), "PKCE challenge matches this attempt's separate verifier");
        assertSafePage(page.get(), authorizeQuery.get("state"));
        check(status.stream().noneMatch(text -> text.contains(CODE) || text.contains(SECRET) || text.contains(verifier)), "Progress does not expose codes, tokens or verifier");
        fixture.done();
        assertClosed(URI.create(authorizeQuery.get("redirect_uri")));
    }

    private static void malformedRequestsDoNotConsumeTheLogin() throws Exception {
        Fixture fixture = new Fixture(true);
        BrowserLogin.login(fixture.service(), CLIENT, uri -> {
            Map<String, String> query = query(uri);
            String base = query.get("redirect_uri");
            String state = encoded(query.get("state"));
            List<String> invalid = List.of(
                "?code=x&state=WRONG-STATE", "?code=x", "?state=" + state,
                "?state=" + state + "&code=x&error=access_denied",
                "?state=" + state + "&code=", "?state=" + state + "&error=",
                "?state=" + state + "&state=" + state + "&code=x",
                "?state=" + state + "&%73tate=" + state + "&code=x",
                "?state=" + state + "&code=x&code=y",
                "?state=" + state + "&code=%FF", "?state=" + state + "&code=%00",
                "?state=" + state + "&code=x&badfield");
            for (String suffix : invalid)
                check(send(URI.create(base + suffix), "GET").status() == 400, "Reject malformed or unrelated callbacks without accepting a code");
            check(send(URI.create(base + "favicon.ico?code=x&state=" + state), "GET").status() == 404, "Ignore callbacks to another path");
            check(send(callback(query, CODE), "POST").status() == 405, "Only a GET callback may finish login");
            check(fixture.requests.isEmpty(), "Invalid callbacks never contact the token endpoint");
            assertSafePage(send(URI.create(callback(query, CODE) + "&session_state=optional"), "GET"), query.get("state"));
        }, message -> {}, () -> false, Duration.ofSeconds(10), new SecureRandom());
        fixture.done();
    }

    private static void asynchronousBrowserGetsTheCompletePage() throws Exception {
        // The login worker is already blocked on the future when this callback arrives. Completing
        // that future before closing the response body used to let server.stop truncate the page.
        for (int attempt = 0; attempt < 8; attempt++) {
            Fixture fixture = new Fixture(true);
            AtomicReference<CompletableFuture<Page>> browser = new AtomicReference<>();
            BrowserLogin.login(fixture.service(), CLIENT, uri -> browser.set(CompletableFuture.supplyAsync(() -> {
                try { return send(callback(query(uri), CODE), "GET"); }
                catch (IOException error) { throw new RuntimeException(error); }
            })), message -> {}, () -> false);
            Page page = browser.get().get(3, TimeUnit.SECONDS);
            check(page.status() == 200 && page.body().endsWith("</body></html>"), "Asynchronous callback receives its complete success page before listener closes");
            fixture.done();
        }
    }

    private static void stateAndVerifierChangeBetweenAttempts() throws Exception {
        List<String> states = new ArrayList<>();
        List<String> verifiers = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            Fixture fixture = new Fixture(true);
            BrowserLogin.login(fixture.service(), CLIENT, uri -> {
                Map<String, String> query = query(uri);
                states.add(query.get("state"));
                send(callback(query, CODE), "GET");
            }, message -> {}, () -> false);
            verifiers.add(form(fixture.requests.get(0).body()).get("code_verifier"));
            fixture.done();
        }
        check(!states.get(0).equals(states.get(1)) && !verifiers.get(0).equals(verifiers.get(1)), "Each login gets fresh state and PKCE verifier");
    }

    private static void browserErrorsStayRedacted() throws Exception {
        for (String errorCode : List.of("access_denied", "untrusted-error-" + SECRET)) {
            Fixture fixture = new Fixture(false);
            AtomicReference<URI> redirect = new AtomicReference<>();
            AuthException error = expect(errorCode.equals("access_denied") ? "ACCESS_DENIED" : "BROWSER_LOGIN_FAILED", () ->
                BrowserLogin.login(fixture.service(), CLIENT, uri -> {
                    Map<String, String> query = query(uri);
                    redirect.set(URI.create(query.get("redirect_uri")));
                    Page page = send(URI.create(redirect.get() + "?state=" + encoded(query.get("state"))
                        + "&error=" + encoded(errorCode) + "&error_description=" + SECRET), "GET");
                    check(page.status() == 200 && !page.body().contains(SECRET), "Error page never reflects Microsoft's error payload");
                }, message -> {}, () -> false));
            check(error.getCause() == null && !error.toString().contains(SECRET), "Display a fixed redacted browser error");
            check(fixture.requests.isEmpty(), "An error callback does not redeem a code");
            assertClosed(redirect.get());
        }
    }

    private static void browserFailureAndTimeoutCloseTheListener() throws Exception {
        Fixture fixture = new Fixture(false);
        AtomicReference<URI> redirect = new AtomicReference<>();
        AuthException unavailable = expect("BROWSER_UNAVAILABLE", () -> BrowserLogin.login(fixture.service(), CLIENT, uri -> {
            redirect.set(URI.create(query(uri).get("redirect_uri")));
            throw new IOException(SECRET + uri);
        }, message -> {}, () -> false));
        check(unavailable.getCause() == null && !unavailable.toString().contains(SECRET), "Browser launch exceptions cannot leak the authorization URI");
        assertClosed(redirect.get());
        long started = System.nanoTime();
        expect("LOGIN_TIMEOUT", () -> BrowserLogin.login(fixture.service(), CLIENT,
            uri -> redirect.set(URI.create(query(uri).get("redirect_uri"))), message -> {}, () -> false,
            Duration.ofMillis(100), new SecureRandom()));
        check(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0, "A bounded timeout does not wait for the production five-minute limit");
        assertClosed(redirect.get());
        check(fixture.requests.isEmpty(), "Browser failure and timeout never contact external endpoints");
    }

    private static void cancellationStopsPromptlyAndClosesTheListener() throws Exception {
        Fixture fixture = new Fixture(false);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<URI> redirect = new AtomicReference<>();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        long started = System.nanoTime();
        try {
            expect("CANCELLED", () -> BrowserLogin.login(fixture.service(), CLIENT, uri -> {
                redirect.set(URI.create(query(uri).get("redirect_uri")));
                scheduler.schedule(() -> cancelled.set(true), 50, TimeUnit.MILLISECONDS);
            }, message -> {}, cancelled::get));
        } finally { scheduler.shutdownNow(); }
        check(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0, "Cancellation is polled promptly while waiting for the browser");
        assertClosed(redirect.get());
        check(fixture.requests.isEmpty(), "Cancelled login never redeems a code");
    }

    private static void invalidClientAndPreCancelledLoginNeverOpenABrowser() throws Exception {
        Fixture fixture = new Fixture(false);
        AtomicBoolean opened = new AtomicBoolean();
        expect("CLIENT_ID_REQUIRED", () -> BrowserLogin.login(fixture.service(), "",
            uri -> opened.set(true), message -> {}, () -> false));
        expect("CANCELLED", () -> BrowserLogin.login(fixture.service(), CLIENT,
            uri -> opened.set(true), message -> {}, () -> true));
        Thread.currentThread().interrupt();
        try {
            expect("CANCELLED", () -> BrowserLogin.login(fixture.service(), CLIENT,
                uri -> opened.set(true), message -> {}, () -> false));
            check(Thread.currentThread().isInterrupted(), "Preserve thread interruption for the controller");
        } finally { Thread.interrupted(); }
        check(!opened.get() && fixture.requests.isEmpty(), "Missing application ID and pre-cancellation open no browser or external connection");
    }

    private static void assertSafePage(Page page, String state) {
        check(page.status() == 200 && page.body().contains("Aurora") && page.body().endsWith("</body></html>"), "Return a complete fixed Aurora callback page");
        check(!page.body().contains(CODE) && !page.body().contains(state) && !page.body().contains(SECRET), "Callback HTML contains no code, state or token");
        check("no-store".equals(page.cacheControl()) && page.csp().contains("default-src 'none'"), "Callback pages disable caching and external content");
    }

    private static URI callback(Map<String, String> query, String code) {
        return URI.create(query.get("redirect_uri") + "?state=" + encoded(query.get("state")) + "&code=" + encoded(code));
    }
    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Map<String, String> query(URI uri) { return form(uri.getRawQuery()); }
    private static Map<String, String> form(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : text.split("&")) {
            int equals = field.indexOf('=');
            values.put(URLDecoder.decode(field.substring(0, equals), StandardCharsets.UTF_8),
                URLDecoder.decode(field.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
    private static Page send(URI uri, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(1_000); connection.setReadTimeout(2_000);
        connection.setRequestMethod(method);
        try {
            int status = connection.getResponseCode();
            try (var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                return new Page(status, new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    connection.getHeaderField("Cache-Control"), connection.getHeaderField("Content-Security-Policy"));
            }
        } finally { connection.disconnect(); }
    }
    private static void assertClosed(URI uri) throws Exception {
        try { send(uri, "GET"); throw new AssertionError("Callback listener was left running"); }
        catch (IOException expected) { check(true, "Close the callback listener after every login outcome"); }
    }
    private record Page(int status, String body, String cacheControl, String csp) { }

    private static final class Fixture implements AuthService.Transport {
        final List<AuthService.Request> requests = new ArrayList<>();
        final ArrayDeque<AuthService.Response> responses = new ArrayDeque<>();
        Fixture(boolean success) {
            if (!success) return;
            add(Map.of("access_token", SECRET + "-microsoft", "refresh_token", SECRET + "-refresh"));
            add(Map.of("Token", SECRET + "-xbox"));
            add(Map.of("Token", SECRET + "-xsts", "DisplayClaims", Map.of("xui", List.of(Map.of("uhs", "hash")))));
            add(Map.of("access_token", SECRET + "-minecraft", "expires_in", 3600));
            add(Map.of("items", List.of(Map.of("name", "game_minecraft"))));
            add(Map.of("id", "0123456789abcdef0123456789abcdef", "name", "AuroraUser"));
        }
        void add(Object value) { responses.add(new AuthService.Response(200, Json.stringify(value))); }
        AuthService service() { return new AuthService(this, Clock.systemUTC(), duration -> {throw new AssertionError("No device polling in browser login");}); }
        @Override public AuthService.Response send(AuthService.Request request) {
            requests.add(request);
            if (responses.isEmpty()) throw new AssertionError("Unexpected external authentication step");
            return responses.removeFirst();
        }
        void done() { check(responses.isEmpty() && requests.size() == 6, "Use exactly the mocked code, Xbox, XSTS, Minecraft, ownership and profile exchanges"); }
    }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static AuthException expect(String code, Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("Expected authentication failure " + code); }
        catch (AuthException error) { check(error.code().equals(code), "Expected error " + code + ", got " + error.code()); return error; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
