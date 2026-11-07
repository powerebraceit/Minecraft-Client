package dev.aurora.launcher;

import dev.aurora.launcher.service.LauncherSettings;
import dev.aurora.launcher.ui.AuroraWindow;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Exercises the shipped entry point and controller with isolated preferences.
 * The missing app-ID guard must stop play before any login, download or game launch.
 */
public final class LauncherEntryCheck {
    private static int checks;
    private static AuroraWindow window;
    private static Robot robot;

    private LauncherEntryCheck() { }

    public static void main(String[] args) throws Exception {
        boolean screenshots = Arrays.asList(args).contains("--screenshots");
        for (String arg : args) if (!arg.equals("--screenshots")) {
            throw new IllegalArgumentException("Unknown entry check argument: " + arg);
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("LauncherEntryCheck needs DISPLAY and java.awt.headless=false");
        }
        Path temporary = Files.createTempDirectory("aurora-entry-check-");
        Path config = temporary.resolve("config/settings.properties");
        Path originalInstance = temporary.resolve("original-instance");
        Path editedInstance = temporary.resolve("edited instance");
        new LauncherSettings(originalInstance, null, "", 4096, false).save(config);
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> asynchronousFailure.compareAndSet(null, error));
        try {
            AuroraLauncher.main(new String[]{"--config", config.toString()});
            await(() -> {
                for (Frame frame : Frame.getFrames()) {
                    if (frame instanceof AuroraWindow candidate && frame.isShowing()) {
                        window = candidate;
                        return true;
                    }
                }
                return false;
            }, "Production launcher window did not appear");
            robot = new Robot();
            robot.setAutoDelay(55);
            edt(() -> { window.setLocation(24, 12); window.toFront(); return null; });
            settle();
            check(edt(() -> window.getTitle().equals("Aurora Launcher")), "Production entry creates the Aurora window");
            check(edt(() -> named("accountName", JLabel.class).getText().equals("Inte inloggad")), "Production controller starts logged out");
            check(edt(() -> named("instanceSummary", JLabel.class).getText().equals(originalInstance.toString())), "--config loads the isolated instance into the actual home page");
            check(edt(() -> named("clientId", JTextField.class).getText().isBlank()), "Production settings start without an application ID");
            checkNoDownloads(temporary, config, originalInstance, editedInstance);
            if (screenshots) capture();

            // A Robot click lets the nested modal event loop run without blocking invokeAndWait.
            click(namedOnEdt("installAndPlay", JButton.class));
            await(() -> configurationDialog() != null, "Play did not show the application-ID explanation");
            JDialog dialog = edt(LauncherEntryCheck::configurationDialog);
            check(edt(dialog::isModal), "Missing application ID is explained in an actual modal dialog");
            check(edt(() -> named("instancePath", JTextField.class).isShowing()), "Play directs the actual controller to settings");
            check(edt(() -> window.getRootPane().getDefaultButton() == null), "Settings clears the hidden play default button");
            check(edt(() -> named("status", JLabel.class).getText().contains("Microsoft-app-ID")), "Controller explains the separate Microsoft application ID");
            JButton okay = edt(() -> firstButton(dialog));
            click(okay);
            await(() -> configurationDialog() == null, "Application-ID dialog did not close");
            checkNoDownloads(temporary, config, originalInstance, editedInstance);
            check(edt(() -> named("installAndPlay", JButton.class).isEnabled()), "Guarded play never leaves the controller busy");

            edt(() -> {
                named("instancePath", JTextField.class).setText(editedInstance.toString());
                named("javaPath", JTextField.class).setText("");
                named("clientId", JTextField.class).setText("");
                named("memoryGb", JSlider.class).setValue(6);
                named("closeOnLaunch", JCheckBox.class).setSelected(true);
                return null;
            });
            click(namedOnEdt("saveSettings", JButton.class));
            LauncherSettings saved = LauncherSettings.load(config);
            check(saved.instancePath().equals(editedInstance), "Save persists the edited instance through the real controller");
            check(saved.memoryMb() == 6144, "Save persists the edited memory value");
            check(saved.closeOnLaunch(), "Save persists the close-on-launch preference");
            check(saved.javaPath() == null && saved.clientId().isBlank(), "Automatic Java and blank application ID round-trip correctly");
            check(edt(() -> named("status", JLabel.class).getText().contains("sparade")), "Actual controller confirms successful persistence");
            checkNoDownloads(temporary, config, originalInstance, editedInstance);
            check(edt(() -> named("accountName", JLabel.class).getText().equals("Inte inloggad")), "Editing settings never authenticates the account");
            check(!hasWorker(), "Guarded play never starts the production account/game worker");

            edt(() -> { window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)); return null; });
            await(() -> !window.isDisplayable(), "Production window did not dispose");
            settle();
            check(!hasWorker(), "Closing the actual controller leaves no live launcher worker");
            check(asynchronousFailure.get() == null, "Entry point, modal guard, persistence and close have no asynchronous exception");
        } finally {
            edt(() -> {
                if (window != null) {
                    for (Window owned : window.getOwnedWindows()) owned.dispose();
                    window.dispose();
                }
                return null;
            });
            Thread.setDefaultUncaughtExceptionHandler(previous);
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: Launcher production entry " + checks + " checks (real controller; no network, login or game)");
        if (screenshots) System.out.println("Screenshot: launcher/build/smoke/aurora-launcher-entry.png");
    }

    private static void checkNoDownloads(Path root, Path config, Path first, Path second) throws Exception {
        check(!Files.exists(first) && !Files.exists(second), "Startup, guarded play and saving do not create game instances");
        check(!Files.exists(config.getParent().resolve("account-session.json")), "No account session is written");
        try (var files = Files.walk(root)) {
            check(files.filter(Files::isRegularFile).allMatch(config::equals), "Only the isolated preferences file exists; no runtime or game is downloaded");
        }
    }

    private static boolean hasWorker() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && thread.getName().equals("aurora-launcher-worker"));
    }
    private static JDialog configurationDialog() {
        for (Window owned : window.getOwnedWindows()) {
            if (owned instanceof JDialog dialog && dialog.isShowing()
                && "Konfigurera Microsoft-inloggning".equals(dialog.getTitle())) return dialog;
        }
        return null;
    }
    private static JButton firstButton(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button) return button;
            if (child instanceof Container container) {
                JButton nested = firstButton(container);
                if (nested != null) return nested;
            }
        }
        return null;
    }
    private static void click(JButton button) throws Exception {
        if (button == null) throw new AssertionError("Button not found");
        edt(() -> { button.scrollRectToVisible(new Rectangle(0, 0, button.getWidth(), button.getHeight())); return null; });
        settle();
        Point center = edt(() -> {
            if (!button.isShowing()) throw new AssertionError("Button is hidden: " + button.getName());
            Point point = button.getLocationOnScreen();
            return new Point(point.x + button.getWidth() / 2, point.y + button.getHeight() / 2);
        });
        robot.mouseMove(center.x, center.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        settle();
    }
    private static void capture() throws Exception {
        Path image = Path.of("launcher/build/smoke/aurora-launcher-entry.png");
        Files.createDirectories(image.getParent());
        Rectangle bounds = edt(window::getBounds);
        robot.mouseMove(bounds.x + bounds.width - 16, bounds.y + bounds.height - 16);
        settle();
        check(ImageIO.write(robot.createScreenCapture(bounds), "png", image.toFile()), "Actual production entry screenshot is written");
    }
    private static <T extends Component> T namedOnEdt(String name, Class<T> type) throws Exception {
        return edt(() -> named(name, type));
    }
    private static <T extends Component> T named(String name, Class<T> type) {
        Component found = find(window, name);
        if (!type.isInstance(found)) throw new AssertionError("Missing component: " + name);
        return type.cast(found);
    }
    private static Component find(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void await(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            if (edt(condition)) return;
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(message);
    }
    private static void settle() throws Exception {
        Toolkit.getDefaultToolkit().sync();
        edt(() -> null);
        Thread.sleep(90);
        edt(() -> null);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { value.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return value.get();
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
