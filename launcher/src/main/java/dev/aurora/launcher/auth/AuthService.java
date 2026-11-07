package dev.aurora.launcher.auth;

import dev.aurora.launcher.net.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Public-client OAuth browser/PKCE and device flows, followed by the official
 * Xbox/Minecraft token exchange.
 * No password or client secret is collected. Each launcher publisher supplies their own
 * Microsoft application ID; possessing a Microsoft account is not an application registration.
 */
public final class AuthService {
    private static final URI DEVICE = URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode");
    private static final URI TOKEN = URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token");
    private static final URI XBOX = URI.create("https://user.auth.xboxlive.com/user/authenticate");
    private static final URI XSTS = URI.create("https://xsts.auth.xboxlive.com/xsts/authorize");
    private static final URI MINECRAFT = URI.create("https://api.minecraftservices.com/authentication/login_with_xbox");
    private static final URI ENTITLEMENTS = URI.create("https://api.minecraftservices.com/entitlements/mcstore");
    private static final URI PROFILE = URI.create("https://api.minecraftservices.com/minecraft/profile");
    private static final String SCOPES = "XboxLive.signin offline_access";

    /** Request bodies and authorization headers are deliberately omitted from toString(). */
    public record Request(String method, URI uri, Map<String, String> headers, String body) {
        public Request { headers = Map.copyOf(headers); }
        @Override public String toString() { return "AuthRequest[" + method + " " + uri.getHost() + uri.getPath() + "]"; }
    }
    public record Response(int status, String body, Map<String, String> headers) {
        public Response(int status, String body) { this(status, body, Map.of()); }
        public Response { headers = Map.copyOf(headers); }
        @Override public String toString() { return "AuthResponse[status=" + status + ", body=REDACTED]"; }
    }
    @FunctionalInterface public interface Transport {
        Response send(Request request) throws IOException, InterruptedException;
    }
    @FunctionalInterface public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Transport transport;
    private final Clock clock;
    private final Sleeper sleeper;

    public AuthService() { this(new HttpTransport(), Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis())); }
    public AuthService(Transport transport, Clock clock, Sleeper sleeper) {
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public Account login(String clientId, Consumer<DeviceCode> showCode, Consumer<String> status,
                         BooleanSupplier cancelled) throws AuthException {
        validateClientId(clientId);
        Objects.requireNonNull(showCode); Objects.requireNonNull(status); Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        status.accept("Ansluter till Microsoft…");
        Response response = send(form(DEVICE, Map.of("client_id", clientId, "scope", SCOPES)), cancelled);
        Map<String, Object> device = data(response);
        if (!success(response)) throw oauthError(device);
        String secretCode = required(device, "device_code");
        String userCode = required(device, "user_code");
        URI verification = verificationUri(required(device, "verification_uri"));
        long expires = integer(device, "expires_in", 900);
        long interval = integer(device, "interval", 5);
        if (expires < 1 || expires > 1800 || interval < 1 || interval > 60 || userCode.length() > 128)
            throw malformed();
        Instant deadline = clock.instant().plusSeconds(expires);
        showCode.accept(new DeviceCode(userCode, verification, deadline));
        status.accept("Logga in i Microsofts webbläsarfönster med koden.");

        while (clock.instant().isBefore(deadline)) {
            checkCancelled(cancelled);
            try { sleeper.sleep(Duration.ofSeconds(interval)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw cancelled();
            }
            checkCancelled(cancelled);
            if (!clock.instant().isBefore(deadline)) break;
            response = send(form(TOKEN, Map.of("client_id", clientId,
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "device_code", secretCode)), cancelled);
            Map<String, Object> token = data(response);
            if (success(response)) return exchange(token, null, status, cancelled);
            String error = text(token, "error");
            if (error.equals("authorization_pending")) continue;
            if (error.equals("slow_down") || response.status() == 429) {
                interval = Math.min(60, interval + 5);
                continue;
            }
            throw oauthError(token);
        }
        throw new AuthException("DEVICE_CODE_EXPIRED", "Inloggningskoden har gått ut. Försök logga in igen.");
    }

    public Account refresh(String clientId, String refreshToken, Consumer<String> status,
                           BooleanSupplier cancelled) throws AuthException {
        validateClientId(clientId);
        if (refreshToken == null || refreshToken.isBlank())
            throw new AuthException("LOGIN_REQUIRED", "Logga in med Microsoft för att fortsätta.");
        checkCancelled(cancelled);
        status.accept("Återställer din Microsoft-session…");
        Response response = send(form(TOKEN, Map.of("client_id", clientId, "grant_type", "refresh_token",
            "refresh_token", refreshToken, "scope", SCOPES)), cancelled);
        Map<String, Object> token = data(response);
        if (!success(response)) throw oauthError(token);
        return exchange(token, refreshToken, status, cancelled);
    }

    /**
     * Redeems a browser callback code using the verifier belonging to that same
     * authorization attempt. The caller checks the callback's OAuth state before
     * calling this method. Codes, verifiers and token responses must never be logged.
     */
    public Account authenticateAuthorizationCode(String clientId, String code, String redirectUri,
                                                 String codeVerifier, Consumer<String> status,
                                                 BooleanSupplier cancelled) throws AuthException {
        validateClientId(clientId);
        Objects.requireNonNull(status); Objects.requireNonNull(cancelled);
        if (code == null || code.isBlank() || code.length() > 16_384)
            throw new AuthException("AUTHORIZATION_CODE_INVALID", "Microsoft gav ingen giltig inloggningskod. Börja inloggningen igen.");
        if (codeVerifier == null || !codeVerifier.matches("[A-Za-z0-9._~-]{43,128}"))
            throw new AuthException("PKCE_INVALID", "Inloggningens säkerhetsverifiering saknas eller är ogiltig. Börja inloggningen igen.");
        validateLoopbackRedirect(redirectUri);
        checkCancelled(cancelled);
        status.accept("Slutför Microsoft-inloggningen…");
        Response response = send(form(TOKEN, Map.of("client_id", clientId, "grant_type", "authorization_code",
            "code", code, "redirect_uri", redirectUri, "code_verifier", codeVerifier)), cancelled);
        Map<String, Object> token = data(response);
        if (!success(response)) {
            AuthException error = oauthError(token);
            if (error.code().equals("LOGIN_REQUIRED"))
                throw new AuthException("LOGIN_REQUIRED", "Microsoft-inloggningen har gått ut eller kan inte användas. Logga in igen.");
            throw error;
        }
        return exchange(token, null, status, cancelled);
    }

    private Account exchange(Map<String, Object> microsoft, String oldRefresh, Consumer<String> status,
                             BooleanSupplier cancelled) throws AuthException {
        try { return exchangeAccount(microsoft, oldRefresh, status, cancelled); }
        catch (IllegalArgumentException | ClassCastException invalid) { throw malformed(); }
    }

    private Account exchangeAccount(Map<String, Object> microsoft, String oldRefresh, Consumer<String> status,
                                    BooleanSupplier cancelled) throws AuthException {
        String microsoftAccess = required(microsoft, "access_token");
        String refreshToken = text(microsoft, "refresh_token");
        if (refreshToken.isBlank()) refreshToken = oldRefresh;
        if (refreshToken == null || refreshToken.isBlank())
            throw new AuthException("REFRESH_TOKEN_MISSING", "Microsoft gav ingen sparbar session. Kontrollera appens offline_access-behörighet och logga in igen.");
        status.accept("Kontrollerar Xbox-kontot…");
        Map<String, Object> xbox = jsonResponse(XBOX, Map.of(
            "Properties", Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com", "RpsTicket", "d=" + microsoftAccess),
            "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT"), cancelled, "XBOX_AUTH");
        String xboxToken = required(xbox, "Token");
        Map<String, Object> xsts = jsonResponse(XSTS, Map.of(
            "Properties", Map.of("SandboxId", "RETAIL", "UserTokens", List.of(xboxToken)),
            "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT"), cancelled, "XSTS_AUTH");
        String xstsToken = required(xsts, "Token");
        Map<String, Object> claims = map(xsts.get("DisplayClaims"));
        List<?> users = array(claims.get("xui"));
        if (users.isEmpty()) throw malformed();
        Map<String, Object> xboxUser = map(users.get(0));
        String userHash = required(xboxUser, "uhs");
        String xuid = text(xboxUser, "xid");
        if (xuid.isBlank() && xbox.get("DisplayClaims") instanceof Map<?, ?>) {
            List<?> originalUsers = array(map(xbox.get("DisplayClaims")).get("xui"));
            if (!originalUsers.isEmpty()) xuid = text(map(originalUsers.get(0)), "xid");
        }
        if (!xuid.isEmpty() && !xuid.matches("[0-9]{1,24}")) throw malformed();
        status.accept("Kontrollerar Minecraft Java…");
        Map<String, Object> minecraft = jsonResponse(MINECRAFT,
            Map.of("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken), cancelled, "MINECRAFT_AUTH");
        String minecraftToken = required(minecraft, "access_token");
        long lifetime = integer(minecraft, "expires_in", 86400);
        if (lifetime <= 0 || lifetime > 7 * 86400) throw malformed();

        Response entitlementResponse = send(get(ENTITLEMENTS, minecraftToken), cancelled);
        if (!success(entitlementResponse)) throw serviceError(entitlementResponse, "MINECRAFT_OWNERSHIP");
        Map<String, Object> ownership = data(entitlementResponse);
        boolean ownsJava = array(ownership.get("items")).stream().filter(Map.class::isInstance)
            .map(AuthService::map).map(item -> text(item, "name"))
            .anyMatch(name -> name.equals("game_minecraft") || name.equals("product_minecraft"));
        if (!ownsJava) throw new AuthException("MINECRAFT_NOT_OWNED", "Det här Microsoft-kontot saknar en Minecraft Java-licens. Välj kontot som äger spelet.");
        Response profileResponse = send(get(PROFILE, minecraftToken), cancelled);
        if (profileResponse.status() == 404)
            throw new AuthException("MINECRAFT_PROFILE_MISSING", "Kontot har ingen Minecraft Java-profil. Skapa profilen på minecraft.net och försök igen.");
        if (!success(profileResponse)) throw serviceError(profileResponse, "MINECRAFT_PROFILE");
        Map<String, Object> profile = data(profileResponse);
        String name = required(profile, "name");
        String id = required(profile, "id").replace("-", "").toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{32}") || name.length() > 64) throw malformed();
        status.accept("Inloggad som " + name + ".");
        return new Account(minecraftToken, refreshToken, name, id, xuid, clock.instant().plusSeconds(lifetime));
    }

    private Map<String, Object> jsonResponse(URI uri, Map<String, Object> body, BooleanSupplier cancelled,
                                             String stage) throws AuthException {
        Response response = send(new Request("POST", uri, Map.of("Content-Type", "application/json"), Json.stringify(body)), cancelled);
        if (!success(response)) throw serviceError(response, stage);
        return data(response);
    }
    private Response send(Request request, BooleanSupplier cancelled) throws AuthException {
        checkCancelled(cancelled);
        try {
            Response response = transport.send(request);
            checkCancelled(cancelled);
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (IOException network) {
            throw new AuthException("NETWORK", "Det gick inte att nå inloggningstjänsten. Kontrollera anslutningen och försök igen.");
        }
    }
    private static Request get(URI uri, String accessToken) {
        return new Request("GET", uri, Map.of("Authorization", "Bearer " + accessToken), "");
    }
    private static Request form(URI uri, Map<String, String> values) {
        StringJoiner encoded = new StringJoiner("&");
        values.forEach((key, value) -> encoded.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return new Request("POST", uri, Map.of("Content-Type", "application/x-www-form-urlencoded"), encoded.toString());
    }
    public static void validateClientId(String clientId) throws AuthException {
        if (clientId == null || !clientId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || clientId.equals("00000000-0000-0000-0000-000000000000"))
            throw new AuthException("CLIENT_ID_REQUIRED", "Ange launcherns offentliga Microsoft Application (client) ID i Inställningar. Ditt Minecraft-konto är inte ett app-ID.");
    }
    /** BrowserLogin binds a local listener and registers only its root callback. */
    private static void validateLoopbackRedirect(String value) throws AuthException {
        try {
            URI uri = value == null ? null : URI.create(value);
            if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())
                || !"localhost".equalsIgnoreCase(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65_535
                || !"/".equals(uri.getRawPath()) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("Invalid loopback callback");
        } catch (IllegalArgumentException invalid) {
            throw new AuthException("REDIRECT_URI_INVALID", "Inloggningens lokala returadress är ogiltig. Börja inloggningen igen.");
        }
    }
    private static URI verificationUri(String value) throws AuthException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || !Set.of("microsoft.com", "www.microsoft.com", "login.microsoftonline.com", "login.live.com").contains(host.toLowerCase(Locale.ROOT)))
                throw malformed();
            return uri;
        } catch (IllegalArgumentException invalid) { throw malformed(); }
    }
    private static boolean success(Response response) { return response.status() >= 200 && response.status() < 300; }
    private static void checkCancelled(BooleanSupplier cancelled) throws AuthException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw cancelled();
    }
    private static AuthException cancelled() { return new AuthException("CANCELLED", "Inloggningen avbröts."); }
    private static AuthException malformed() { return new AuthException("INVALID_RESPONSE", "Inloggningstjänsten svarade med oväntade uppgifter. Försök igen senare."); }
    private static AuthException oauthError(Map<String, Object> error) {
        String code = text(error, "error");
        return switch (code) {
            case "access_denied", "authorization_declined" -> new AuthException("ACCESS_DENIED", "Du avbröt eller nekade Microsoft-inloggningen.");
            case "expired_token", "bad_verification_code" -> new AuthException("DEVICE_CODE_EXPIRED", "Inloggningskoden är inte längre giltig. Logga in igen.");
            case "invalid_grant", "interaction_required" -> new AuthException("LOGIN_REQUIRED", "Din sparade Microsoft-session har gått ut. Logga in igen.");
            case "invalid_client", "unauthorized_client", "invalid_request" -> new AuthException("CLIENT_CONFIGURATION", "Microsoft godkände inte launcher-appens konfiguration. Kontrollera app-ID, personliga Microsoft-konton, public client och returadressen http://localhost för webbläsarinloggning.");
            default -> new AuthException("MICROSOFT_AUTH", "Microsoft-inloggningen misslyckades. Kontrollera appens behörigheter och försök igen.");
        };
    }
    private static AuthException serviceError(Response response, String stage) {
        Map<String, Object> details;
        try { details = data(response); } catch (AuthException ignored) { details = Map.of(); }
        long xerr = integer(details, "XErr", 0);
        if (xerr == 2148916233L) return new AuthException("XBOX_PROFILE_MISSING", "Kontot saknar en Xbox-profil. Logga in på xbox.com och skapa profilen först.");
        if (xerr == 2148916238L) return new AuthException("XBOX_CHILD_ACCOUNT", "Barnkontot behöver ingå i en Microsoft-familj med godkända Xbox-behörigheter.");
        if (xerr == 2148916236L || xerr == 2148916237L) return new AuthException("XBOX_AGE_VERIFICATION", "Xbox kräver åldersverifiering för kontot. Slutför den hos Microsoft och försök igen.");
        if (xerr == 2148916235L) return new AuthException("XBOX_REGION", "Xbox Live är inte tillgängligt för kontots valda region.");
        if (xerr == 2148916227L) return new AuthException("XBOX_RESTRICTED", "Xbox-kontot är begränsat. Kontrollera kontots status hos Microsoft.");
        if (response.status() == 429) return new AuthException("RATE_LIMITED", "För många inloggningsförsök. Vänta en stund och försök igen.");
        if (response.status() == 401 || response.status() == 403)
            return new AuthException(stage, "Inloggningstjänsten nekade åtkomst. Logga in igen eller kontrollera launcher-appens Minecraft/Xbox-behörigheter.");
        return new AuthException(stage, "Det gick inte att slutföra kontokontrollen hos Microsoft/Xbox/Minecraft. Försök igen senare.");
    }
    private static Map<String, Object> data(Response response) throws AuthException {
        try { return map(Json.parse(response.body())); }
        catch (RuntimeException malformed) { throw malformed(); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }
    private static List<?> array(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static String text(Map<String, Object> map, String key) { Object value = map.get(key); return value instanceof String text ? text : ""; }
    private static String required(Map<String, Object> map, String key) throws AuthException {
        String value = text(map, key);
        if (value.isBlank()) throw malformed();
        return value;
    }
    private static long integer(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public Response send(Request request) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("User-Agent", "AuroraLauncher/1.0");
            request.headers().forEach(builder::header);
            if (request.method().equals("GET")) builder.GET();
            else builder.method(request.method(), HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body().length() > 1_048_576) throw new IOException("Authentication response exceeds size limit");
            Map<String, String> headers = new HashMap<>();
            response.headers().map().forEach((name, values) -> { if (!values.isEmpty()) headers.put(name, values.get(0)); });
            return new Response(response.statusCode(), response.body(), headers);
        }
    }
}
