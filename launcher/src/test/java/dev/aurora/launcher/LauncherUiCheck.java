package dev.aurora.launcher;

import dev.aurora.launcher.ui.AuroraWindow;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Real Swing/Robot integration check with a deliberately inert controller.
 * It opens actual windows and resources, but never authenticates, downloads a
 * game, launches a process, or opens a browser. Device codes below are mock UI data.
 *
 * Run with DISPLAY set (for example under Xvfb). Only --screenshots writes images.
 */
public final class LauncherUiCheck {
    private static final AtomicInteger checks = new AtomicInteger();
    private static final URI VERIFICATION_URI = URI.create("https://microsoft.com/link");
    private static AuroraWindow window;
    private static Robot robot;

    private LauncherUiCheck() { }

    public static void main(String[] args) throws Exception {
        boolean screenshots = Arrays.asList(args).contains("--screenshots");
        for (String arg : args) if (!arg.equals("--screenshots")) {
            throw new IllegalArgumentException("Unknown UI check argument: " + arg);
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("LauncherUiCheck needs a real display; run under Xvfb with java.awt.headless=false");
        }
        check(LauncherUiCheck.class.getResource("/fonts/Poppins-Regular.ttf") != null, "Bundled regular font is available");
        check(LauncherUiCheck.class.getResource("/fonts/Poppins-SemiBold.ttf") != null, "Bundled semibold font is available");
        check(LauncherUiCheck.class.getResource("/licenses/OFL-Poppins.txt") != null, "Bundled font license is available");
        MockActions actions = new MockActions();
        AuroraWindow.Settings initial = new AuroraWindow.Settings(
            "/tmp/aurora-ui-example-instance", "", "", 4096, false);
        try {
            edt(() -> {
                window = new AuroraWindow(actions, initial);
                window.setLocation(24, 12);
                window.setVisible(true);
                window.toFront();
                return null;
            });
            robot = new Robot();
            robot.setAutoDelay(55);
            settle();
            check(edt(() -> window.isShowing()), "Real launcher frame is showing");
            check(edt(() -> window.getTitle().equals("Aurora Launcher")), "Window title uses Aurora branding");
            check(edt(() -> named("accountName", JLabel.class).getText().equals("Inte inloggad")), "Home initially displays a logged-out account");
            check(edt(() -> named("accountName", JLabel.class).getFont().getFamily().toLowerCase().contains("poppins")), "Actual bundled Poppins font renders in the window");
            check(edt(() -> named("instanceSummary", JLabel.class).getText().equals(initial.instancePath())), "Home shows the configured instance");
            check(edt(() -> window.getRootPane().getDefaultButton() == named("installAndPlay", JButton.class)), "Home has the install/play default button");

            click("installAndPlay");
            check(actions.install.get() == 1, "Home install/play button invokes its controller callback once");
            click("login");
            check(actions.login.get() == 1, "Home Microsoft button invokes login without running authentication");
            click("chooseInstance");
            check(actions.chooseInstance.get() == 1, "Home instance chooser invokes its callback");
            click("openGameFolder");
            check(actions.openFolder.get() == 1, "Home folder button invokes its callback");
            click("accountAction");
            check(actions.login.get() == 2, "Logged-out sidebar action invokes login");

            // This tests keyboard activation of an actual focused Swing button.
            edt(() -> { named("installAndPlay", JButton.class).requestFocusInWindow(); return null; });
            settle();
            robot.keyPress(KeyEvent.VK_SPACE);robot.keyRelease(KeyEvent.VK_SPACE);settle();
            check(actions.install.get() == 2, "Focused install/play button supports keyboard activation");

            window.setBusy(true);settle();
            for (String name : new String[]{"installAndPlay", "login", "chooseInstance", "accountAction", "chooseJava", "saveSettings"}) {
                check(!edt(() -> named(name, JButton.class).isEnabled()), "Busy state disables " + name);
            }
            check(!edt(() -> named("instancePath", JTextField.class).isEnabled()), "Busy state disables path editing");
            check(!edt(() -> named("memoryGb", JSlider.class).isEnabled()), "Busy state disables memory editing");
            check(edt(() -> named("nav-log", JButton.class).isEnabled()), "Navigation stays available while a task is busy");
            int before = actions.install.get();click("installAndPlay");
            check(actions.install.get() == before, "Clicking a disabled install button cannot start another task");
            window.setProgress(42, "UI-test: 42 procent");window.setStatus("UI-test: väntar på en mockåtgärd", false);settle();
            check(edt(() -> named("progress", JProgressBar.class).getValue() == 42), "Worker-facing progress updates reach the real progress bar");
            check(edt(() -> named("progressText", JLabel.class).getText().equals("UI-test: 42 procent")), "Progress text updates on the event thread");
            window.setBusy(false);settle();
            check(edt(() -> named("installAndPlay", JButton.class).isEnabled()), "Completion restores enabled buttons");

            click("nav-settings");
            check(edt(() -> named("instancePath", JTextField.class).isShowing()), "Settings navigation shows the settings card");
            check(!edt(() -> named("installAndPlay", JButton.class).isShowing()), "Home controls are hidden while settings are open");
            check(edt(() -> window.getRootPane().getDefaultButton() == null), "Settings does not retain a hidden play default button");
            check(edt(() -> named("memoryGb", JSlider.class).getValue() == 4), "Initial memory setting appears correctly");
            click("chooseJava");
            check(actions.chooseJava.get() == 1, "Java chooser invokes the controller callback");
            edt(() -> {
                named("instancePath", JTextField.class).setText("/tmp/Aurora UI settings instance");
                named("javaPath", JTextField.class).setText("/tmp/example-java/bin/java");
                named("clientId", JTextField.class).setText("00000000-0000-0000-0000-000000000001");
                named("memoryGb", JSlider.class).setValue(6);
                named("closeOnLaunch", JCheckBox.class).setSelected(true);
                return null;
            });
            click("saveSettings");
            AuroraWindow.Settings expected = new AuroraWindow.Settings("/tmp/Aurora UI settings instance",
                "/tmp/example-java/bin/java", "00000000-0000-0000-0000-000000000001", 6144, true);
            check(expected.equals(actions.saved), "Saving settings emits the exact edited record, including memory and close behavior");
            check(actions.save.get() == 1, "Save callback runs exactly once");
            // Apply a controller-returned record and verify both cards reflect the same model.
            window.setSettings(initial);settle();
            check(edt(() -> named("javaPath", JTextField.class).getText().isEmpty()), "Controller settings update clears Java path when automatic discovery is selected");
            check(!edt(() -> named("closeOnLaunch", JCheckBox.class).isSelected()), "Controller settings update restores close preference");
            if (screenshots) capture("aurora-launcher-settings.png");

            click("nav-log");
            window.appendLog("UI-test: första raden");window.appendLog("UI-test: andra raden");settle();
            check(edt(() -> named("activityLog", JTextArea.class).isShowing()), "Activity navigation shows the real log view");
            check(edt(() -> named("activityLog", JTextArea.class).getText().contains("UI-test: andra raden")), "Worker log updates reach the log component");
            check(!edt(() -> named("activityLog", JTextArea.class).isEditable()), "Log output cannot be edited as user input");
            window.setStatus("UI-test: avsiktligt visningsfel", true);settle();
            check(edt(() -> named("status", JLabel.class).getText().equals("UI-test: avsiktligt visningsfel")), "Errors appear as explicit status text");

            click("nav-home");
            window.setProgress(0, "Välj en instans för att komma igång");window.setStatus("Redo när du är.", false);settle();
            check(edt(() -> named("installAndPlay", JButton.class).isShowing()), "Home navigation restores the play controls");
            if (screenshots) capture("aurora-launcher-home.png");

            window.setAccount("UiTestPlayer", true);settle();
            check(edt(() -> named("accountName", JLabel.class).getText().equals("UiTestPlayer")), "Account rendering displays controller-provided mock data");
            check(edt(() -> named("accountAction", JButton.class).getText().equals("Logga ut")), "Signed-in sidebar exposes sign-out");
            click("accountAction");
            check(actions.logout.get() == 1, "Sign-out invokes its callback");
            window.setAccount("", false);settle();
            check(edt(() -> named("accountName", JLabel.class).getText().equals("Inte inloggad")), "Account rendering can return to logged-out state");

            window.showDeviceLogin("UI-TEST-CODE", VERIFICATION_URI);settle();
            check(edt(() -> deviceDialog().isShowing()), "Device-login dialog is a visible modeless Swing window");
            check(!edt(() -> deviceDialog().isModal()), "Device login does not block the launcher's event thread");
            check(edt(() -> named(deviceDialog(), "deviceCode", JTextField.class).getText().equals("UI-TEST-CODE")), "Device code is displayed exactly");
            check(!edt(() -> named(deviceDialog(), "deviceCode", JTextField.class).isEditable()), "Device code is read-only");
            click(deviceDialog(), "openLoginPage");
            check(VERIFICATION_URI.equals(actions.opened), "Microsoft-link button passes the exact supplied URI to the mock controller");
            check(actions.openLogin.get() == 1, "Opening the login page emits one callback and opens no real browser in this test");
            window.closeDeviceLogin();settle();
            check(!edt(LauncherUiCheck::hasVisibleDeviceDialog), "Programmatic close disposes the login dialog");
            check(actions.cancelLogin.get() == 0, "Programmatic close does not cancel a successfully completed sign-in");

            window.showDeviceLogin("UI-CANCEL-CODE", VERIFICATION_URI);settle();
            // Xvfb has no window-manager close button. Dispatch the exact Swing window event it would send.
            edt(() -> { deviceDialog().dispatchEvent(new WindowEvent(deviceDialog(), WindowEvent.WINDOW_CLOSING)); return null; });
            settle();
            check(actions.cancelLogin.get() == 1, "Closing the device dialog requests cancellation exactly once");
            check(!edt(LauncherUiCheck::hasVisibleDeviceDialog), "Cancellation closes the real device dialog");

            edt(() -> { window.setSize(window.getMinimumSize());window.setLocation(12, 10);return null; });
            settle();
            check(edt(() -> window.getWidth() >= 960 && window.getHeight() >= 640), "Minimum window size is maintained");
            check(edt(() -> named("nav-settings", JButton.class).isShowing()), "Navigation remains visible after minimum-size resize");
            click("nav-settings");
            click("saveSettings"); // Scrolling into view is required at this viewport height.
            check(actions.save.get() == 2, "Settings remain usable at minimum window size");
            check(!actions.offEventThread.get(), "All user-action callbacks ran on Swing's event thread");
            check(actions.launch.get() == 0, "The UI never launches a process on its own");
        } finally {
            edt(() -> {
                if (window != null) {window.closeDeviceLogin();window.dispose();}
                return null;
            });
            if (robot != null) settle();
        }
        check(!edt(() -> window != null && window.isDisplayable()), "Window cleanup releases its native peer");
        System.out.println("PASS: Launcher UI " + checks.get() + " checks (real Swing/Robot; mock controller, no production authentication)");
        if (screenshots) System.out.println("Screenshots: launcher/build/smoke/aurora-launcher-home.png and aurora-launcher-settings.png");
    }

    /** An animated window never becomes globally idle; flush events without waiting for its timer to stop. */
    private static void settle() throws Exception {
        Toolkit.getDefaultToolkit().sync();
        edt(() -> null);
        Thread.sleep(90);
        edt(() -> null);
    }

    private static void click(String name) throws Exception {click(window, name);}
    private static void click(Container parent, String name) throws Exception {
        Point center = edt(() -> {
            JButton button = named(parent, name, JButton.class);
            check(button.isShowing(), "Button is showing: " + name);
            button.scrollRectToVisible(new Rectangle(0, 0, button.getWidth(), button.getHeight()));
            return null;
        });
        settle();
        center = edt(() -> {
            JButton button = named(parent, name, JButton.class);
            Point location = button.getLocationOnScreen();
            return new Point(location.x + button.getWidth() / 2, location.y + button.getHeight() / 2);
        });
        robot.mouseMove(center.x, center.y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);settle();
    }
    private static void capture(String filename) throws Exception {
        Path target = Path.of("launcher", "build", "smoke", filename);
        Files.createDirectories(target.getParent());
        Rectangle bounds = edt(() -> window.getBounds());
        robot.mouseMove(bounds.x + bounds.width - 16, bounds.y + bounds.height - 16);
        settle();Thread.sleep(120);
        check(ImageIO.write(robot.createScreenCapture(bounds), "png", target.toFile()), "PNG screenshot writer is available");
    }
    private static JDialog deviceDialog() {
        for (Window owned : window.getOwnedWindows()) {
            if (owned instanceof JDialog dialog && "deviceLogin".equals(dialog.getName()) && dialog.isDisplayable()) return dialog;
        }
        throw new AssertionError("Device-login dialog not found");
    }
    private static boolean hasVisibleDeviceDialog() {
        for (Window owned : window.getOwnedWindows()) if ("deviceLogin".equals(owned.getName()) && owned.isShowing()) return true;
        return false;
    }
    private static <T extends Component> T named(String name, Class<T> type) {return named(window, name, type);}
    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        Component result = find(root, name);
        if (!type.isInstance(result)) throw new AssertionError("Missing component " + name + " of type " + type.getSimpleName());
        return type.cast(result);
    }
    private static Component find(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                Component found = find(child, name);if (found != null) return found;
            }
        }
        return null;
    }
    private static <T> T edt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        Object[] result = new Object[1];Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {try {result[0] = operation.call();}catch (Throwable error) {failure[0] = error;}});
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception error) throw error;
        @SuppressWarnings("unchecked") T value = (T) result[0];return value;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks.incrementAndGet();
    }
    private static final class MockActions implements AuroraWindow.Actions {
        final AtomicInteger login = new AtomicInteger(), logout = new AtomicInteger(), install = new AtomicInteger();
        final AtomicInteger chooseInstance = new AtomicInteger(), chooseJava = new AtomicInteger(), openFolder = new AtomicInteger();
        final AtomicInteger save = new AtomicInteger(), launch = new AtomicInteger(), openLogin = new AtomicInteger(), cancelLogin = new AtomicInteger();
        final AtomicBoolean offEventThread = new AtomicBoolean();
        volatile AuroraWindow.Settings saved;
        volatile URI opened;
        private void record(AtomicInteger count) {
            if (!SwingUtilities.isEventDispatchThread()) offEventThread.set(true);
            count.incrementAndGet();
        }
        @Override public void login() {record(login);}
        @Override public void logout() {record(logout);}
        @Override public void installAndPlay() {record(install);}
        @Override public void launch() {record(launch);}
        @Override public void chooseInstance() {record(chooseInstance);}
        @Override public void chooseJava() {record(chooseJava);}
        @Override public void openGameFolder() {record(openFolder);}
        @Override public void saveSettings(AuroraWindow.Settings settings) {saved = settings;record(save);}
        @Override public void openLoginPage(URI uri) {opened = uri;record(openLogin);}
        @Override public void cancelLogin() {record(cancelLogin);}
    }
}
