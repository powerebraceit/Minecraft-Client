package dev.aurora.launcher.auth;

import dev.aurora.launcher.net.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic protocol checks: no real endpoints, accounts, credentials or waiting are used. */
public final class AuthServiceCheck {
    private static final String CLIENT = "12345678-1234-1234-1234-123456789abc";
    private static final String SECRET = "SENSITIVE-TEST-ONLY";
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String REDIRECT = "http://localhost:49321/";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static int checks;
    public static void main(String[] args) throws Exception {
        deviceFlowHonorsPendingAndSlowDown();
        invalidClientIdDoesNotContactMicrosoft();
        refreshRotatesTokensAndSupportsRetainedRefresh();
        browserCodeUsesPkceAndVerifiesMinecraftOwnership();
        browserCodeValidatesInputsBeforeNetworkAccess();
        browserCodeCancellationStopsTokenExchange();
        browserCodeErrorsRemainFriendlyAndRedacted();
        browserCodeRejectsUnlicensedAccountsAndMalformedTokens();
        cancellationStopsPolling();
        expiryStopsPolling();
        deniedLoginHasFriendlyRedactedError();
        absentLicenseStopsBeforeProfile();
        xboxChildFailureIsExplained();
        missingProfileIsExplained();
        malformedResponsesAndNetworkErrorsStayRedacted();
        credentialsAndRequestsDoNotPrintSecrets();
        tokenStorageIsPrivateAndClientSpecific();
        System.out.println("PASS: " + checks + " authentication and session-store checks");
    }
    private static void deviceFlowHonorsPendingAndSlowDown() throws Exception {
        Fixture f = new Fixture();
        f.add(200, device(900, 5));
        f.add(400, Map.of("error", "authorization_pending"));
        f.add(400, Map.of("error", "slow_down"));
        f.add(200, microsoft("refresh-new"));f.addExchange(true, 200);
        List<DeviceCode> shown = new ArrayList<>();List<String> statuses = new ArrayList<>();
        Account account = f.service().login(CLIENT, shown::add, statuses::add, () -> false);
        check(f.sleeps.equals(List.of(5L, 5L, 10L)), "Device polling follows Microsoft intervals");
        check(shown.size() == 1 && shown.get(0).verificationUri().equals(URI.create("https://microsoft.com/link")), "Show only the Microsoft challenge");
        check(account.name().equals("AuroraUser") && account.uuid().equals(ID), "Use the Minecraft profile identity");
        check(account.xuid().equals("2533270000000001"), "Expose the real Xbox XUID when supplied");
        check(account.accessToken().equals(SECRET + "-minecraft") && account.refreshToken().equals("refresh-new"), "Keep Minecraft and Microsoft tokens separate");
        check(f.requests.get(0).body().contains("offline_access") && f.requests.get(0).body().contains("XboxLive.signin"), "Request the configured public-client scopes");
        var xbox = object(f.requests.get(4).body());
        check(object(xbox.get("Properties")).get("RpsTicket").equals("d=" + SECRET + "-microsoft"), "Xbox receives the Microsoft RPS ticket");
        var xsts = object(f.requests.get(5).body());
        check(xsts.get("RelyingParty").equals("rp://api.minecraftservices.com/"), "XSTS token is scoped to Minecraft");
        check(object(f.requests.get(6).body()).get("identityToken").equals("XBL3.0 x=userhash;" + SECRET + "-xsts"), "Minecraft exchange uses the XSTS user hash");
        check(f.requests.get(7).headers().get("Authorization").equals("Bearer " + account.accessToken()), "Ownership uses the Minecraft token");
        check(statuses.stream().noneMatch(s -> s.contains(SECRET)), "Progress contains no tokens");
        f.done();
    }
    private static void invalidClientIdDoesNotContactMicrosoft() throws Exception {
        Fixture f = new Fixture();
        expect("CLIENT_ID_REQUIRED", () -> f.service().login("", code -> {}, message -> {}, () -> false));
        expect("CLIENT_ID_REQUIRED", () -> f.service().login("my-microsoft-account", code -> {}, message -> {}, () -> false));
        check(f.requests.isEmpty(), "Account names are never treated as application IDs");
    }
    private static void refreshRotatesTokensAndSupportsRetainedRefresh() throws Exception {
        Fixture f = new Fixture();f.add(200, microsoft("rotated-refresh"));f.addExchange(true, 200);
        Account account = f.service().refresh(CLIENT, "old-refresh", message -> {}, () -> false);
        check(account.refreshToken().equals("rotated-refresh"), "Persist refreshed Microsoft refresh tokens");
        check(f.requests.get(0).body().contains("grant_type=refresh_token") && f.requests.get(0).body().contains("old-refresh"), "Refresh is an OAuth refresh grant");f.done();
        f = new Fixture();f.add(200, Map.of("access_token", SECRET + "-microsoft"));f.addExchange(true, 200);
        account = f.service().refresh(CLIENT, "retained-refresh", message -> {}, () -> false);
        check(account.refreshToken().equals("retained-refresh"), "Retain a refresh token when Microsoft does not rotate it");f.done();
    }
    private static void browserCodeUsesPkceAndVerifiesMinecraftOwnership() throws Exception {
        Fixture f = new Fixture();f.add(200, microsoft("browser-refresh"));f.addExchange(true, 200);
        String code = SECRET + " authorization+code/&?=å";
        List<String> statuses = new ArrayList<>();
        Account account = f.service().authenticateAuthorizationCode(CLIENT, code, REDIRECT, VERIFIER, statuses::add, () -> false);
        AuthService.Request request = f.requests.get(0);
        check(request.method().equals("POST") && request.uri().equals(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")), "Browser codes use the Microsoft consumer token endpoint");
        check(request.headers().get("Content-Type").equals("application/x-www-form-urlencoded"), "Authorization codes use a form token request");
        check(formValues(request.body()).equals(Map.of("client_id", CLIENT, "grant_type", "authorization_code", "code", code,
            "redirect_uri", REDIRECT, "code_verifier", VERIFIER)), "PKCE grant encodes the exact callback, code and verifier without a client secret");
        check(!request.body().contains(code) && request.body().contains("%2B") && request.body().contains("%26"), "Reserved code characters cannot inject additional form fields");
        check(account.name().equals("AuroraUser") && account.uuid().equals(ID) && account.xuid().equals("2533270000000001"), "Browser login derives the real licensed Minecraft identity");
        check(account.refreshToken().equals("browser-refresh") && account.accessToken().equals(SECRET + "-minecraft"), "Browser login separates the refresh session from Minecraft access");
        check(f.requests.size() == 6 && f.requests.get(4).uri().getPath().endsWith("/entitlements/mcstore")
            && f.requests.get(5).uri().getPath().endsWith("/minecraft/profile"), "A browser callback completes ownership and profile verification");
        check(statuses.stream().noneMatch(message -> message.contains(SECRET) || message.contains(VERIFIER))
            && !request.toString().contains(VERIFIER) && !request.toString().contains(code), "Browser status and request descriptions do not reveal credentials");
        check(f.sleeps.isEmpty(), "Authorization code flow does not poll or wait for device codes");f.done();
    }
    private static void browserCodeValidatesInputsBeforeNetworkAccess() throws Exception {
        Fixture f = new Fixture();
        expect("CLIENT_ID_REQUIRED", () -> f.service().authenticateAuthorizationCode("", "code", REDIRECT, VERIFIER, message -> {}, () -> false));
        for (String code : Arrays.asList(null, "", "  ", "x".repeat(16_385)))
            expect("AUTHORIZATION_CODE_INVALID", () -> f.service().authenticateAuthorizationCode(CLIENT, code, REDIRECT, VERIFIER, message -> {}, () -> false));
        for (String verifier : Arrays.asList(null, "", "x".repeat(42), "x".repeat(129), "+".repeat(43), "/".repeat(43), "å".repeat(43), " ".repeat(43)))
            expect("PKCE_INVALID", () -> f.service().authenticateAuthorizationCode(CLIENT, "code", REDIRECT, verifier, message -> {}, () -> false));
        for (String redirect : Arrays.asList(null, "", "not a URI", "https://localhost:49321/", "http://localhost/",
            "http://localhost:0/", "http://localhost:65536/", "http://example.invalid:49321/", "http://localhost@example.invalid:49321/",
            "http://user@localhost:49321/", "http://127.0.0.1:49321/", "http://localhost:49321/callback", "http://localhost:49321//",
            "http://localhost:49321/?code=injected", "http://localhost:49321/#fragment", "http://localhost:49321/%2F"))
            expect("REDIRECT_URI_INVALID", () -> f.service().authenticateAuthorizationCode(CLIENT, "code", redirect, VERIFIER, message -> {}, () -> false));
        check(f.requests.isEmpty(), "Invalid browser login inputs never contact Microsoft");
        for (String verifier : List.of("x".repeat(43), "x".repeat(124) + "._~-")) {
            Fixture accepted = new Fixture();accepted.add(400, Map.of("error", "access_denied"));
            expect("ACCESS_DENIED", () -> accepted.service().authenticateAuthorizationCode(CLIENT, "code", REDIRECT, verifier, message -> {}, () -> false));
            check(formValues(accepted.requests.get(0).body()).get("code_verifier").equals(verifier), "RFC7636 verifier boundary lengths and unreserved characters are accepted");accepted.done();
        }
    }
    private static void browserCodeCancellationStopsTokenExchange() throws Exception {
        Fixture untouched = new Fixture();
        expect("CANCELLED", () -> untouched.service().authenticateAuthorizationCode(CLIENT, "code", REDIRECT, VERIFIER, message -> {}, () -> true));
        check(untouched.requests.isEmpty(), "Cancelled browser login sends no code to Microsoft");
        Fixture f = new Fixture();f.add(200, microsoft("browser-refresh"));AtomicBoolean cancelled = new AtomicBoolean();
        AuthService service = new AuthService(request -> {
            AuthService.Response response = f.send(request);cancelled.set(true);return response;
        }, f.clock, duration -> {throw new AssertionError("Browser login must not wait");});
        expect("CANCELLED", () -> service.authenticateAuthorizationCode(CLIENT, "code", REDIRECT, VERIFIER, message -> {}, cancelled::get));
        check(f.requests.size() == 1, "Cancellation after the code response prevents the Xbox and Minecraft exchanges");f.done();
    }
    private static void browserCodeErrorsRemainFriendlyAndRedacted() throws Exception {
        for (String oauthError : List.of("access_denied", "invalid_grant", "invalid_client")) {
            Fixture f = new Fixture();f.add(400, Map.of("error", oauthError, "error_description", SECRET, "code", SECRET));
            String expected = switch (oauthError) {
                case "access_denied" -> "ACCESS_DENIED";
                case "invalid_grant" -> "LOGIN_REQUIRED";
                default -> "CLIENT_CONFIGURATION";
            };
            AuthException error = expect(expected, () -> f.service().authenticateAuthorizationCode(CLIENT, SECRET, REDIRECT, VERIFIER, message -> {}, () -> false));
            check(!error.toString().contains(SECRET) && !error.getMessage().contains(VERIFIER), "Authorization code errors omit Microsoft's raw credential-bearing details");
            if (oauthError.equals("invalid_grant")) check(!error.getMessage().contains("sparade"), "An expired browser code is not described as a saved-session error");f.done();
        }
    }
    private static void browserCodeRejectsUnlicensedAccountsAndMalformedTokens() throws Exception {
        Fixture unlicensed = new Fixture();unlicensed.add(200, microsoft("browser-refresh"));unlicensed.addExchange(false, 200);
        expect("MINECRAFT_NOT_OWNED", () -> unlicensed.service().authenticateAuthorizationCode(CLIENT, "code", REDIRECT, VERIFIER, message -> {}, () -> false));
        check(unlicensed.requests.stream().noneMatch(request -> request.uri().getPath().endsWith("/minecraft/profile")), "A browser login cannot bypass Minecraft ownership");unlicensed.done();
        for (String body : List.of(SECRET, "[]", "null", "{}", "{\"access_token\":123,\"refresh_token\":\"refresh\"}")) {
            Fixture f = new Fixture();f.responses.add(new AuthService.Response(200, body));
            AuthException error = expect("INVALID_RESPONSE", () -> f.service().authenticateAuthorizationCode(CLIENT, "code", REDIRECT, VERIFIER, message -> {}, () -> false));
            check(!error.toString().contains(SECRET) && f.requests.size() == 1, "Malformed browser token responses stop without leaking or continuing");f.done();
        }
        Fixture missingRefresh = new Fixture();missingRefresh.add(200, Map.of("access_token", SECRET));
        expect("REFRESH_TOKEN_MISSING", () -> missingRefresh.service().authenticateAuthorizationCode(CLIENT, "code", REDIRECT, VERIFIER, message -> {}, () -> false));
        check(missingRefresh.requests.size() == 1, "A browser login requires its own refresh session, rather than retaining a previous account's token");missingRefresh.done();
    }
    private static void cancellationStopsPolling() throws Exception {
        Fixture f = new Fixture();f.add(200, device(900, 5));AtomicBoolean cancelled = new AtomicBoolean();
        AuthService service = new AuthService(f, f.clock, duration -> {f.clock.advance(duration);cancelled.set(true);});
        expect("CANCELLED", () -> service.login(CLIENT, code -> {}, message -> {}, cancelled::get));
        check(f.requests.size() == 1, "Cancellation does not request another token");
    }
    private static void expiryStopsPolling() throws Exception {
        Fixture f = new Fixture();f.add(200, device(1, 5));
        expect("DEVICE_CODE_EXPIRED", () -> f.service().login(CLIENT, code -> {}, message -> {}, () -> false));
        check(f.requests.size() == 1, "Do not poll after the device code expires");
    }
    private static void deniedLoginHasFriendlyRedactedError() throws Exception {
        Fixture f = new Fixture();f.add(200, device(900, 5));f.add(400, Map.of("error", "authorization_declined", "error_description", SECRET));
        AuthException error = expect("ACCESS_DENIED", () -> f.service().login(CLIENT, code -> {}, message -> {}, () -> false));
        check(!error.toString().contains(SECRET), "Microsoft descriptions are not shown or logged");
    }
    private static void absentLicenseStopsBeforeProfile() throws Exception {
        Fixture f = new Fixture();f.add(200, microsoft("refresh"));f.addExchange(false, 200);
        expect("MINECRAFT_NOT_OWNED", () -> f.service().refresh(CLIENT, "refresh", message -> {}, () -> false));
        check(f.requests.stream().noneMatch(r -> r.uri().getPath().endsWith("/minecraft/profile")), "An unlicensed account cannot be authenticated as a player");f.done();
    }
    private static void xboxChildFailureIsExplained() throws Exception {
        Fixture f = new Fixture();f.add(200, microsoft("refresh"));f.add(200, Map.of("Token", SECRET + "-xbox"));
        f.add(401, Map.of("XErr", 2148916238L, "Message", SECRET));
        AuthException error = expect("XBOX_CHILD_ACCOUNT", () -> f.service().refresh(CLIENT, "refresh", message -> {}, () -> false));
        check(error.getMessage().contains("familj") && !error.getMessage().contains(SECRET), "Explain family permissions without exposing service payloads");f.done();
    }
    private static void missingProfileIsExplained() throws Exception {
        Fixture f = new Fixture();f.add(200, microsoft("refresh"));f.addExchange(true, 404);
        expect("MINECRAFT_PROFILE_MISSING", () -> f.service().refresh(CLIENT, "refresh", message -> {}, () -> false));f.done();
    }
    private static void malformedResponsesAndNetworkErrorsStayRedacted() throws Exception {
        Fixture f = new Fixture();f.responses.add(new AuthService.Response(200, SECRET));
        Fixture malformedFixture = f;
        AuthException invalid = expect("INVALID_RESPONSE", () -> malformedFixture.service().login(CLIENT, code -> {}, message -> {}, () -> false));
        check(!invalid.toString().contains(SECRET), "Malformed JSON does not leak payload text");
        AuthService service = new AuthService(request -> {throw new IOException(SECRET);}, Clock.systemUTC(), duration -> {});
        AuthException network = expect("NETWORK", () -> service.login(CLIENT, code -> {}, message -> {}, () -> false));
        check(!network.toString().contains(SECRET) && network.getCause() == null, "Transport exceptions do not expose authorization data");
        f = new Fixture();f.add(200, Map.of("device_code", SECRET, "user_code", "ABC", "verification_uri", "https://example.invalid/phish", "expires_in", 900));
        Fixture malicious = f;
        expect("INVALID_RESPONSE", () -> malicious.service().login(CLIENT, code -> {}, message -> {}, () -> false));
    }
    private static void credentialsAndRequestsDoNotPrintSecrets() {
        Account account = new Account(SECRET, SECRET, "AuroraUser", ID, Instant.now().plusSeconds(3600));
        check(!account.toString().contains(SECRET), "Account.toString redacts credentials");
        check(!new DeviceCode(SECRET, URI.create("https://microsoft.com/link?code=" + SECRET), Instant.now()).toString().contains(SECRET), "Challenge.toString redacts the code and URI");
        var request = new AuthService.Request("POST", URI.create("https://login.microsoftonline.com/token"), Map.of("Authorization", SECRET), SECRET);
        check(!request.toString().contains(SECRET) && !new AuthService.Response(200, SECRET).toString().contains(SECRET), "HTTP metadata string representations redact secrets");
    }
    private static void tokenStorageIsPrivateAndClientSpecific() throws Exception {
        Path folder = Files.createTempDirectory("aurora-auth-check");
        try {
            Path file = folder.resolve("session.properties");TokenStore store = new TokenStore(file);
            check(store.load(CLIENT).isEmpty(), "A new installation has no implicit account");
            store.save(CLIENT, SECRET);
            check(store.load(CLIENT).orElseThrow().equals(SECRET), "Refresh session round-trips");
            check(store.load("different-client").isEmpty(), "Application ID changes do not reuse another app's session");
            if (Files.getFileStore(file).supportsFileAttributeView("posix"))
                check(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")), "Stored refresh tokens are owner-readable only");
            store.save(CLIENT, "rotated");check(store.load(CLIENT).orElseThrow().equals("rotated"), "Saving a rotated token atomically replaces the previous session");
            check(Files.list(folder).filter(p -> p.getFileName().toString().startsWith(".aurora-session-")).count() == 0, "No token temp files remain");
            store.clear();check(!Files.exists(file), "Logout removes the persisted session");
            Path target = folder.resolve("elsewhere");Files.writeString(target, "retained");
            try {
                Files.createSymbolicLink(file, target);
                try {store.save(CLIENT, SECRET);throw new AssertionError("A symbolic session path was accepted");}
                catch (IOException expected) {check(Files.readString(target).equals("retained"), "Session storage does not overwrite a symlink target");}
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { }
        } finally {
            try (var paths = Files.walk(folder)) {for (Path path : paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private static Map<String, Object> microsoft(String refresh) {return Map.of("access_token", SECRET + "-microsoft", "refresh_token", refresh);}
    private static Map<String, String> formValues(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
            if (values.put(key, value) != null)throw new AssertionError("Duplicate form key " + key);
        }
        return values;
    }
    private static Map<String, Object> device(int expires, int interval) {
        return Map.of("device_code", SECRET, "user_code", "ABCD-EFGH", "verification_uri", "https://microsoft.com/link", "expires_in", expires, "interval", interval);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        return value instanceof String json ? (Map<String, Object>)Json.parse(json) : (Map<String, Object>)value;
    }
    private static final class FakeClock extends Clock {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        void advance(Duration duration) {now = now.plus(duration);}
        @Override public ZoneId getZone() {return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone) {return this;}
        @Override public Instant instant() {return now;}
    }
    private static final class Fixture implements AuthService.Transport {
        final List<AuthService.Request> requests = new ArrayList<>();
        final ArrayDeque<AuthService.Response> responses = new ArrayDeque<>();
        final List<Long> sleeps = new ArrayList<>();final FakeClock clock = new FakeClock();
        void add(int status, Object body) {responses.add(new AuthService.Response(status, Json.stringify(body)));}
        void addExchange(boolean ownsJava, int profileStatus) {
            add(200, Map.of("Token", SECRET + "-xbox"));
            add(200, Map.of("Token", SECRET + "-xsts", "DisplayClaims", Map.of("xui", List.of(Map.of("uhs", "userhash", "xid", "2533270000000001")))));
            add(200, Map.of("access_token", SECRET + "-minecraft", "expires_in", 3600));
            add(200, Map.of("items", ownsJava ? List.of(Map.of("name", "game_minecraft")) : List.of()));
            if (ownsJava) add(profileStatus, profileStatus == 200 ? Map.of("id", ID, "name", "AuroraUser") : Map.of("error", "NOT_FOUND"));
        }
        AuthService service() {return new AuthService(this, clock, duration -> {sleeps.add(duration.toSeconds());clock.advance(duration);});}
        @Override public AuthService.Response send(AuthService.Request request) {
            requests.add(request);
            if (responses.isEmpty())throw new AssertionError("Unexpected auth stage: " + request);
            return responses.removeFirst();
        }
        void done() {check(responses.isEmpty(), "All planned protocol stages were exercised");}
    }
    @FunctionalInterface private interface Attempt {void run() throws Exception;}
    private static AuthException expect(String code, Attempt action) throws Exception {
        try {action.run();throw new AssertionError("Expected authentication failure " + code);}
        catch (AuthException error) {check(error.code().equals(code), "Expected error code " + code + ", got " + error.code());return error;}
    }
    private static void check(boolean condition, String message) {if (!condition)throw new AssertionError(message);checks++;}
}
