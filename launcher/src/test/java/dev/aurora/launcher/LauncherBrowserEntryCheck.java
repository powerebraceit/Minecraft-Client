package dev.aurora.launcher;

import dev.aurora.launcher.auth.AuthService;
import dev.aurora.launcher.auth.BrowserLogin;
import dev.aurora.launcher.auth.TokenStore;
import dev.aurora.launcher.net.Json;
import dev.aurora.launcher.service.LauncherSettings;
import dev.aurora.launcher.ui.AuroraWindow;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Real controller, Swing buttons, OAuth callback server and token persistence.
 * A synthetic browser sends only a localhost callback; every Microsoft, Xbox and
 * Minecraft response is supplied by an injected in-memory transport. No real
 * account, browser, Microsoft connection, game download or game process is used.
 */
public final class LauncherBrowserEntryCheck {
    private static final String CLIENT = "11111111-2222-4333-8444-555555555555";
    private static final String CODE = "synthetic-authorization+code/with&characters";
    private static final String REFRESH = "synthetic-microsoft-refresh-private";
    private static final String MC_TOKEN = "synthetic-minecraft-access-private";
    private static final String PLAYER = "AuroraBrowserUser";
    private static final AtomicInteger checks = new AtomicInteger();
    private static Robot robot;

    private LauncherBrowserEntryCheck() { }

    public static void main(String[] args) throws Exception {
        boolean screenshots = Arrays.asList(args).contains("--screenshots");
        for (String arg : args) if (!arg.equals("--screenshots"))
            throw new IllegalArgumentException("Unknown browser entry check argument: " + arg);
        if (GraphicsEnvironment.isHeadless())
            throw new IllegalStateException("LauncherBrowserEntryCheck needs DISPLAY and java.awt.headless=false");
        robot = new Robot();robot.setAutoDelay(40);
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> asynchronousFailure.compareAndSet(null, error));
        try {
            licensedAccountCompletesAndLogsOut(screenshots);
            accountWithoutJavaLicenseIsRejected();
            check(asynchronousFailure.get() == null, "Controller has no uncaught asynchronous failure");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
        System.out.println("PASS: Launcher browser entry " + checks.get()
            + " checks (real controller/localhost callback; simulated Microsoft, Xbox and Minecraft responses)");
    }

    private static void licensedAccountCompletesAndLogsOut(boolean screenshots) throws Exception {
        try (Scenario scenario = new Scenario(true)) {
            scenario.begin();
            scenario.browser.allowCallback.countDown();
            scenario.awaitFinished();
            check(edt(() -> named(scenario.window, "accountName", JLabel.class).getText().equals(PLAYER)),
                "Verified Minecraft profile appears in the actual controller window");
            check(edt(() -> named(scenario.window, "accountAction", JButton.class).getText().equals("Logga ut")),
                "Verified account enables the actual logout action");
            check(new TokenStore(scenario.session).load(CLIENT).orElseThrow().equals(REFRESH),
                "Actual controller persists the Microsoft refresh session for this app ID");
            String stored = Files.readString(scenario.session);
            check(!stored.contains(MC_TOKEN) && !stored.contains(CODE), "Session excludes Minecraft access token and authorization code");
            check(scenario.transport.requests.size() == 6, "All six authorization, ownership and profile stages completed");
            scenario.verifyUnchangedFiles(true);
            if (screenshots) capture(scenario.window);
            click(edt(() -> named(scenario.window, "accountAction", JButton.class)));
            await(() -> named(scenario.window, "accountName", JLabel.class).getText().equals("Inte inloggad"),
                "Actual logout action did not update the account");
            check(!Files.exists(scenario.session), "Logout deletes the saved refresh session");
            scenario.verifyUnchangedFiles(false);
            check(edt(() -> named(scenario.window, "accountAction", JButton.class).getText().equals("Logga in")),
                "Logout restores the login action");
        }
    }

    private static void accountWithoutJavaLicenseIsRejected() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            scenario.begin();
            scenario.browser.allowCallback.countDown();
            scenario.awaitFinished();
            check(edt(() -> named(scenario.window, "accountName", JLabel.class).getText().equals("Inte inloggad")),
                "An account without Java ownership remains logged out");
            check(edt(() -> named(scenario.window, "status", JLabel.class).getText().contains("saknar en Minecraft Java-licens")),
                "The real controller displays the Minecraft ownership error");
            check(!Files.exists(scenario.session), "Failed ownership verification never saves a session");
            check(scenario.transport.requests.size() == 5, "Missing ownership stops before the profile request");
            scenario.verifyUnchangedFiles(false);
        }
    }

    private static final class Scenario implements AutoCloseable {
        final Path temporary, config, instance, session, sentinel;
        final byte[] originalConfig;
        final SyntheticBrowser browser = new SyntheticBrowser();
        final SyntheticTransport transport;
        final AuroraLauncher.Controller controller;
        final AuroraWindow window;

        Scenario(boolean ownsJava) throws Exception {
            temporary = Files.createTempDirectory("aurora-browser-entry-");
            config = temporary.resolve("settings.properties");
            instance = temporary.resolve("game-instance");
            session = temporary.resolve("account-session.json");
            sentinel = temporary.resolve("unrelated-file.txt");
            Files.writeString(sentinel, "preserve this unrelated file");
            new LauncherSettings(instance, null, CLIENT, 4096, false).save(config);
            originalConfig = Files.readAllBytes(config);
            transport = new SyntheticTransport(ownsJava, browser);
            AuthService authentication = new AuthService(transport, Clock.systemUTC(), duration -> {
                throw new AssertionError("Browser authorization must not use device-code polling");
            });
            controller = edt(() -> new AuroraLauncher.Controller(config, authentication, browser));
            window = edt(() -> {
                controller.show();
                for (Frame frame : Frame.getFrames()) if (frame instanceof AuroraWindow candidate && candidate.isShowing()) {
                    candidate.setLocation(24, 12);candidate.toFront();return candidate;
                }
                throw new AssertionError("Injected production controller did not create its window");
            });
            settle();
        }

        void begin() throws Exception {
            check(edt(() -> named(window, "accountName", JLabel.class).getText().equals("Inte inloggad")),
                "Configured controller starts without an implicit account");
            click(edt(() -> named(window, "login", JButton.class)));
            await(() -> browser.opens.get() == 1 && browserDialog(window) != null, "Real browser login dialog did not appear");
            check(edt(() -> !named(window, "installAndPlay", JButton.class).isEnabled()
                && !named(window, "login", JButton.class).isEnabled()), "Controller is busy while waiting for browser authentication");
            check(edt(() -> named(browserDialog(window), "cancelBrowserLogin", JButton.class).isShowing()),
                "The production browser login dialog offers cancellation");
        }

        void awaitFinished() throws Exception {
            await(() -> named(window, "login", JButton.class).isEnabled() && browserDialog(window) == null,
                "Controller did not finish browser login and close the dialog");
            browser.rethrowFailure();transport.rethrowFailure();
            check(browser.opens.get() == 1 && browser.callbackStatus == 200,
                "Synthetic browser opened once and the real localhost callback accepted its exact state");
            check(edt(() -> named(window, "installAndPlay", JButton.class).isEnabled()), "Controller clears busy after authentication");
            check(edt(() -> browserDialog(window) == null), "Authentication completion closes the actual browser dialog");
            String status = edt(() -> named(window, "status", JLabel.class).getText());
            check(!status.contains(CODE) && !status.contains(REFRESH) && !status.contains(MC_TOKEN),
                "Controller status does not reveal synthetic credentials");
        }

        void verifyUnchangedFiles(boolean signedIn) throws Exception {
            check(Arrays.equals(originalConfig, Files.readAllBytes(config)), "Login/logout preserves preferences exactly");
            check(Files.readString(sentinel).equals("preserve this unrelated file"), "Login/logout preserves unrelated files");
            check(!Files.exists(instance) && !Files.exists(temporary.resolve("runtimes")), "Login never downloads or launches a game/runtime");
            try (var files = Files.walk(temporary)) {
                check(files.filter(Files::isRegularFile).allMatch(path -> path.equals(config) || path.equals(sentinel)
                    || signedIn && path.equals(session)), "Authentication only writes its own refresh session");
            }
        }

        @Override public void close() throws Exception {
            browser.allowCallback.countDown();
            edt(() -> {
                controller.cancelLogin();
                for (Window owned : window.getOwnedWindows()) owned.dispose();
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                return null;
            });
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                && thread.getName().equals("aurora-launcher-worker")), "Controller worker did not stop on window close");
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static final class SyntheticBrowser implements BrowserLogin.BrowserOpener {
        final AtomicInteger opens = new AtomicInteger();
        final CountDownLatch allowCallback = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile Map<String, String> authorization;
        volatile int callbackStatus;

        @Override public void open(URI uri) throws IOException {
            try {
                check(opens.incrementAndGet() == 1, "Login opens the browser once");
                check(uri.getScheme().equals("https") && uri.getHost().equals("login.microsoftonline.com")
                    && uri.getPath().equals("/consumers/oauth2/v2.0/authorize"), "Browser opens the official Microsoft consumer authorization endpoint");
                authorization = form(uri.getRawQuery());
                check(authorization.get("client_id").equals(CLIENT) && authorization.get("response_type").equals("code")
                    && authorization.get("code_challenge_method").equals("S256"), "Authorization uses the configured client and PKCE code flow");
                URI redirect = URI.create(authorization.get("redirect_uri"));
                check(redirect.getScheme().equals("http") && redirect.getHost().equals("localhost") && redirect.getPort() > 0
                    && redirect.getPath().equals("/") && redirect.getRawQuery() == null,
                    "Synthetic browser sends credentials only to the actual localhost callback");
                check(authorization.get("state").length() >= 32, "Browser authorization includes unpredictable state");
                if (!allowCallback.await(10, TimeUnit.SECONDS)) throw new IOException("Test did not release the browser callback");
                URI callback = URI.create(redirect + "?code=" + encode(CODE) + "&state=" + encode(authorization.get("state")));
                HttpURLConnection connection = (HttpURLConnection)callback.toURL().openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);
                try {
                    callbackStatus = connection.getResponseCode();
                    try (var body = connection.getInputStream()) { body.readNBytes(8192); }
                } finally { connection.disconnect(); }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IOException("Synthetic browser callback failed", error);
            }
        }
        void rethrowFailure() { if (failure.get() != null) throw new AssertionError("Synthetic browser assertion failed", failure.get()); }
    }

    private static final class SyntheticTransport implements AuthService.Transport {
        final boolean ownsJava;
        final SyntheticBrowser browser;
        final List<AuthService.Request> requests = new ArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SyntheticTransport(boolean ownsJava, SyntheticBrowser browser) { this.ownsJava = ownsJava;this.browser = browser; }

        @Override public AuthService.Response send(AuthService.Request request) throws IOException {
            try {
                int stage = requests.size();requests.add(request);
                String[] endpoints = {
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    "https://user.auth.xboxlive.com/user/authenticate",
                    "https://xsts.auth.xboxlive.com/xsts/authorize",
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    "https://api.minecraftservices.com/entitlements/mcstore",
                    "https://api.minecraftservices.com/minecraft/profile"
                };
                check(stage < endpoints.length && request.uri().toString().equals(endpoints[stage])
                    && request.method().equals(stage < 4 ? "POST" : "GET"), "Synthetic transport receives the expected authorization stage " + stage);
                Object response;
                switch (stage) {
                    case 0 -> {
                        Map<String, String> grant = form(request.body());
                        check(grant.get("grant_type").equals("authorization_code") && grant.get("code").equals(CODE)
                            && grant.get("client_id").equals(CLIENT) && grant.get("redirect_uri").equals(browser.authorization.get("redirect_uri")),
                            "Real callback exchanges its exact code, configured app and redirect");
                        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(grant.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                        check(challenge.equals(browser.authorization.get("code_challenge")), "Callback token grant proves the original browser PKCE challenge");
                        response = Map.of("access_token", "synthetic-microsoft-access", "refresh_token", REFRESH);
                    }
                    case 1 -> response = Map.of("Token", "synthetic-xbox-token");
                    case 2 -> response = Map.of("Token", "synthetic-xsts-token", "DisplayClaims", Map.of("xui",
                        List.of(Map.of("uhs", "synthetic-userhash", "xid", "2533270000000001"))));
                    case 3 -> response = Map.of("access_token", MC_TOKEN, "expires_in", 3600);
                    case 4 -> {
                        check(request.headers().get("Authorization").equals("Bearer " + MC_TOKEN), "Ownership check uses the exchanged Minecraft token");
                        response = Map.of("items", ownsJava ? List.of(Map.of("name", "game_minecraft")) : List.of());
                    }
                    case 5 -> {
                        check(ownsJava, "Unlicensed account must never request a player profile");
                        response = Map.of("name", PLAYER, "id", "123456781234423481234567890abcde");
                    }
                    default -> throw new AssertionError("Unexpected synthetic authentication request");
                }
                return new AuthService.Response(200, Json.stringify(response));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);throw new IOException("Synthetic transport assertion failed", error);
            }
        }
        void rethrowFailure() { if (failure.get() != null) throw new AssertionError("Synthetic transport assertion failed", failure.get()); }
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
            if (result.put(key, value) != null) throw new AssertionError("Duplicate query/form field");
        }
        return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static JDialog browserDialog(AuroraWindow window) {
        for (Window owned : window.getOwnedWindows())
            if (owned instanceof JDialog dialog && dialog.isShowing() && "browserLogin".equals(dialog.getName())) return dialog;
        return null;
    }
    private static <T extends Component> T named(Component root, String name, Class<T> type) {
        Component found = find(root, name);
        if (!type.isInstance(found)) throw new AssertionError("Missing component: " + name);
        return type.cast(found);
    }
    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            Component found = find(child, name);if (found != null) return found;
        }
        return null;
    }
    private static void click(JButton button) throws Exception {
        settle();
        Point center = edt(() -> {
            if (!button.isShowing() || !button.isEnabled()) throw new AssertionError("Button cannot be clicked: " + button.getName());
            Point point = button.getLocationOnScreen();return new Point(point.x + button.getWidth()/2, point.y + button.getHeight()/2);
        });
        robot.mouseMove(center.x, center.y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        settle();
    }
    private static void capture(AuroraWindow window) throws Exception {
        Path image = Path.of("launcher/build/smoke/aurora-launcher-browser.png");Files.createDirectories(image.getParent());
        window.setStatus("TEST: simulerade Microsoft-svar, inget riktigt konto.", false);
        Rectangle bounds = edt(window::getBounds);settle();
        check(ImageIO.write(robot.createScreenCapture(bounds), "png", image.toFile()), "Optional verified-account screenshot is written");
    }
    private static void await(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do { if (edt(condition)) return;Thread.sleep(30); } while (System.nanoTime() < deadline);
        throw new AssertionError(message);
    }
    private static void settle() throws Exception { Toolkit.getDefaultToolkit().sync();edt(() -> null);Thread.sleep(90);edt(() -> null); }
    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        AtomicReference<T> value = new AtomicReference<>();AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {try {value.set(action.call());} catch (Throwable error) {failure.set(error);}});
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return value.get();
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);checks.incrementAndGet();
    }
}
