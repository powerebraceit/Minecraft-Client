package dev.aurora.launcher.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;

/**
 * Aurora's desktop presentation. The controller owns installation, processes and persistence;
 * this window only renders state and emits deliberate user actions on Swing's event thread.
 */
public final class AuroraWindow extends JFrame {
    public record Settings(String instancePath, String javaPath, String clientId,
                           int memoryMb, boolean closeOnLaunch) {
        public Settings {
            instancePath = instancePath == null ? "" : instancePath;
            javaPath = javaPath == null ? "" : javaPath;
            clientId = clientId == null ? "" : clientId;
            memoryMb = Math.max(2048, Math.min(16384, memoryMb));
        }
    }

    public interface Actions {
        void login();
        void logout();
        void installAndPlay();
        void launch();
        void chooseInstance();
        void chooseJava();
        void openGameFolder();
        void saveSettings(Settings settings);
        default void openLoginPage(URI uri) { }
        default void cancelLogin() { }
    }

    private static final Color BASE = new Color(10, 13, 23);
    private static final Color SIDEBAR = new Color(12, 16, 28);
    private static final Color PANEL = new Color(20, 26, 42);
    private static final Color LINE = new Color(43, 51, 73);
    private static final Color TEXT = new Color(238, 242, 255);
    private static final Color MUTED = new Color(148, 161, 187);
    private static final Color PURPLE = new Color(162, 137, 255);
    private static final Color CYAN = new Color(102, 222, 218);
    private static final Font REGULAR = loadFont("/fonts/Poppins-Regular.ttf", Font.PLAIN);
    private static final Font SEMIBOLD = loadFont("/fonts/Poppins-SemiBold.ttf", Font.BOLD);

    private final Actions actions;
    private final CardLayout cards = new CardLayout();
    private final JPanel pageHost = new JPanel(cards);
    private final JLabel pageTitle = label("Översikt", 19, TEXT, true);
    private final JLabel instanceSummary = label("Välj din Minecraft-instans", 12, TEXT, false);
    private final JLabel statusLabel = label("Redo när du är.", 12, TEXT, false);
    private final JLabel progressText = label("Välj en instans för att komma igång", 11, MUTED, false);
    private final JLabel settingsNotice = label("Inställningarna sparas lokalt på den här datorn.", 11, MUTED, false);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea log = new JTextArea();
    private final JTextField instanceField = field("instancePath");
    private final JTextField javaField = field("javaPath");
    private final JTextField clientField = field("clientId");
    private final JSlider memory = new JSlider(2, 16, 4);
    private final JLabel memoryValue = label("4 GB", 12, PURPLE, true);
    private final JLabel accountName = label("Inte inloggad", 11, TEXT, true);
    private final JLabel accountDetail = label("Microsoft-konto", 9, MUTED, false);
    private SoftButton loginButton, accountAction;
    private JDialog deviceDialog;
    private JDialog browserDialog;
    private boolean loggedIn;
    private final JCheckBox closeOnLaunch = new JCheckBox("Stäng Aurora när spelet startar");
    private final List<SoftButton> serviceButtons = new ArrayList<>();
    private final List<NavButton> navButtons = new ArrayList<>();
    private final List<SoftButton> animatedButtons = new ArrayList<>();
    private final Background background = new Background();
    private final Hero hero = new Hero();
    private final Timer animation;
    private SoftButton installButton;
    private double phase;
    private Settings settings;

    public AuroraWindow(Actions actions, Settings settings) {
        super("Aurora Launcher");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.settings = Objects.requireNonNull(settings, "settings");
        setName("auroraLauncher");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1200, 760);
        setLocationRelativeTo(null);
        setIconImage(icon());
        background.setLayout(new BorderLayout());
        setContentPane(background);
        background.add(sidebar(), BorderLayout.WEST);
        background.add(mainArea(), BorderLayout.CENTER);
        applySettings(settings);
        getRootPane().setDefaultButton(installButton);

        animation = new Timer(32, event -> {
            // Keep the desktop quiet while Minecraft or the sign-in browser is in front.
            if (!isShowing() || (!isActive() && (deviceDialog == null || !deviceDialog.isActive())
                    && (browserDialog == null || !browserDialog.isActive()))) return;
            phase += 0.016;
            for (SoftButton button : animatedButtons) button.advance();
            hero.repaint();
            background.repaint();
        });
        animation.setCoalesce(true);
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) { animation.start(); }
            @Override public void windowClosed(WindowEvent event) { animation.stop(); }
            @Override public void windowIconified(WindowEvent event) { animation.stop(); }
            @Override public void windowDeiconified(WindowEvent event) { animation.start(); }
        });
    }

    /** All controller-facing updates are safe to call from worker threads. */
    public void setSettings(Settings settings) { onEdt(() -> applySettings(settings)); }
    public void setBusy(boolean busy) {
        onEdt(() -> {
            for (SoftButton button : serviceButtons) button.setEnabled(!busy);
            instanceField.setEnabled(!busy);
            javaField.setEnabled(!busy);
            clientField.setEnabled(!busy);
            memory.setEnabled(!busy);
            closeOnLaunch.setEnabled(!busy);
            installButton.setText(busy ? "Arbetar…" : "Installera & spela");
            progress.setIndeterminate(busy && progress.getValue() == 0);
        });
    }
    public void setProgress(int percent, String message) {
        onEdt(() -> {
            progress.setIndeterminate(percent < 0);
            if (percent >= 0) progress.setValue(Math.max(0, Math.min(100, percent)));
            progressText.setText(safe(message));
            progressText.setToolTipText(safe(message));
        });
    }
    public void setStatus(String message, boolean error) {
        onEdt(() -> {
            statusLabel.setForeground(error ? new Color(255, 145, 161) : TEXT);
            statusLabel.setText(safe(message));
            statusLabel.setToolTipText(safe(message));
            settingsNotice.setText(safe(message));
            settingsNotice.setForeground(error ? new Color(255, 145, 161) : MUTED);
        });
    }
    public void appendLog(String message) {
        onEdt(() -> {
            // Bound the view; the controller may retain a full log separately if desired.
            if (log.getDocument().getLength() > 200_000) log.setText(log.getText().substring(100_000));
            log.append(safe(message) + System.lineSeparator());
        });
    }
    public void setAccount(String username, boolean signedIn) {
        onEdt(() -> {
            loggedIn = signedIn;
            accountName.setText(signedIn ? safe(username) : "Inte inloggad");
            accountName.setToolTipText(signedIn ? safe(username) : "Logga in för att spela Minecraft");
            accountDetail.setText(signedIn ? "Minecraft Java Edition" : "Microsoft-konto");
            if (loginButton != null) loginButton.setText(signedIn ? "Byt konto" : "Logga in med Microsoft");
            if (accountAction != null) accountAction.setText(signedIn ? "Logga ut" : "Logga in");
            if (signedIn && deviceDialog != null) { deviceDialog.dispose(); deviceDialog = null; }
            if (signedIn && browserDialog != null) { browserDialog.dispose(); browserDialog = null; }
        });
    }

    /** Shows browser sign-in progress; only the controller opens the browser and handles OAuth data. */
    public void showBrowserLogin(URI authorizationUri) {
        Objects.requireNonNull(authorizationUri, "authorizationUri");
        onEdt(() -> {
            if (browserDialog != null) browserDialog.dispose();
            JDialog dialog = new JDialog(this, "Logga in hos Microsoft", false);
            browserDialog = dialog;
            dialog.setName("browserLogin");
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            boolean[] cancelled = {false};
            Runnable cancel = () -> {
                if (cancelled[0] || !dialog.isDisplayable()) return;
                cancelled[0] = true;
                dialog.dispose();
                if (browserDialog == dialog) browserDialog = null;
                actions.cancelLogin();
            };
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { cancel.run(); }
            });
            JPanel content = new JPanel();content.setBackground(BASE);
            content.setBorder(new EmptyBorder(28, 30, 28, 30));
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.add(label("Logga in hos Microsoft", 23, TEXT, true));gap(content, 14);
            content.add(label("<html>Microsofts inloggning öppnas i din webbläsare.<br>"
                + "Aurora fortsätter automatiskt när kontot är verifierat.</html>", 12, MUTED, false));
            gap(content, 18);
            // OAuth query parameters stay inside the controller callback; never render them as text or tooltips.
            content.add(label("Microsoft", 11, CYAN, true));gap(content, 22);
            JPanel controls = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
            SoftButton open = button("Öppna Microsoft igen", true, "openBrowserLoginPage",
                () -> actions.openLoginPage(authorizationUri));
            SoftButton abort = button("Avbryt", false, "cancelBrowserLogin", cancel);
            controls.add(open);controls.add(abort);controls.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(controls);
            dialog.setContentPane(content);dialog.setMinimumSize(new Dimension(560, 265));
            dialog.pack();dialog.setLocationRelativeTo(this);dialog.setVisible(true);
        });
    }

    /** Completing or failing authentication closes presentation without issuing another cancellation. */
    public void closeBrowserLogin() {
        onEdt(() -> {if(browserDialog!=null){browserDialog.dispose();browserDialog=null;}});
    }

    /** The controller supplies Microsoft's real device code and verification URI. No login is simulated. */
    public void showDeviceLogin(String code, URI uri) {
        Objects.requireNonNull(uri, "uri");
        onEdt(() -> {
            if (deviceDialog != null) deviceDialog.dispose();
            deviceDialog = new JDialog(this, "Logga in med Microsoft", false);
            deviceDialog.setName("deviceLogin");
            deviceDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            deviceDialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) {actions.cancelLogin();}
            });
            JPanel content = new JPanel();content.setBackground(BASE);
            content.setBorder(new EmptyBorder(28, 30, 28, 30));
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.add(label("Välkommen till Aurora", 23, TEXT, true));gap(content, 12);
            content.add(label("Öppna Microsofts inloggningssida och ange koden:", 12, MUTED, false));gap(content, 18);
            JTextField codeField = field("deviceCode");codeField.setText(safe(code));codeField.setEditable(false);
            codeField.setFont(SEMIBOLD.deriveFont(27f));codeField.setHorizontalAlignment(SwingConstants.CENTER);
            codeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));content.add(codeField);gap(content, 12);
            JLabel location = label(uri.toString(), 11, CYAN, false);location.setToolTipText(uri.toString());content.add(location);gap(content, 20);
            JPanel controls = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
            SoftButton open = button("Öppna Microsoft", true, "openLoginPage", () -> actions.openLoginPage(uri));
            SoftButton copy = button("Kopiera kod", false, "copyDeviceCode", () -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(safe(code)), null);
                codeField.selectAll();codeField.requestFocusInWindow();
            });
            controls.add(open);controls.add(copy);controls.setAlignmentX(Component.LEFT_ALIGNMENT);content.add(controls);gap(content, 18);
            content.add(label("Aurora fortsätter automatiskt när inloggningen är klar.", 11, MUTED, false));
            gap(content, 6);content.add(label("Stäng det här fönstret för att avbryta.", 10, MUTED, false));
            deviceDialog.setContentPane(content);deviceDialog.setMinimumSize(new Dimension(510, 350));
            deviceDialog.pack();deviceDialog.setLocationRelativeTo(this);deviceDialog.setVisible(true);
        });
    }

    public void closeDeviceLogin() {
        onEdt(() -> {if(deviceDialog!=null){deviceDialog.dispose();deviceDialog=null;}});
    }

    public void showPage(String page) {
        onEdt(() -> {
            String chosen = switch (safe(page).toLowerCase()) {
                case "settings", "inställningar" -> "settings";
                case "log", "logg" -> "log";
                default -> "home";
            };
            cards.show(pageHost, chosen);
            pageTitle.setText(switch (chosen) { case "settings" -> "Inställningar"; case "log" -> "Aktivitet"; default -> "Översikt"; });
            for (NavButton button : navButtons) button.setSelectedPage(button.page.equals(chosen));
            getRootPane().setDefaultButton(chosen.equals("home") ? installButton : null);
        });
    }

    private JPanel sidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(210, 0));
        sidebar.setBackground(SIDEBAR);
        sidebar.setBorder(new EmptyBorder(28, 20, 24, 20));
        JPanel top = transparent(new BorderLayout(10, 0));
        JComponent mark = new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = quality(graphics); drawMark(g, 1, 1, 32); g.dispose();
            }
        };
        mark.setPreferredSize(new Dimension(34, 36));
        top.add(mark, BorderLayout.WEST);
        JPanel wordmark = transparent(new GridLayout(2, 1));
        wordmark.add(label("A U R O R A", 16, TEXT, true));
        wordmark.add(label("LAUNCHER", 9, MUTED, false));
        top.add(wordmark, BorderLayout.CENTER);

        JPanel upper = transparent();
        upper.setLayout(new BoxLayout(upper, BoxLayout.Y_AXIS));
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));top.setAlignmentX(Component.LEFT_ALIGNMENT);
        upper.add(top);
        upper.add(Box.createVerticalStrut(44));
        JLabel section = label("DIN SPELPLATS", 9, MUTED, true);
        section.setBorder(new EmptyBorder(0, 12, 12, 0));
        upper.add(section);
        nav(upper, "Översikt", "home", 0);
        nav(upper, "Inställningar", "settings", 1);
        nav(upper, "Aktivitet", "log", 2);
        sidebar.add(upper, BorderLayout.NORTH);

        JPanel footer = transparent();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        Card account = new Card();account.setLayout(new BoxLayout(account, BoxLayout.Y_AXIS));
        account.setBorder(new EmptyBorder(14, 13, 14, 13));
        accountName.setName("accountName");accountName.setAlignmentX(Component.LEFT_ALIGNMENT);account.add(accountName);
        gap(account, 4);accountDetail.setAlignmentX(Component.LEFT_ALIGNMENT);account.add(accountDetail);gap(account, 12);
        accountAction = button("Logga in", false, "accountAction", () -> {if (loggedIn) actions.logout();else actions.login();});
        accountAction.setAlignmentX(Component.LEFT_ALIGNMENT);serviceButtons.add(accountAction);account.add(accountAction);
        footer.add(account);gap(footer, 17);
        JLabel version = label("Aurora Launcher 1.0.1", 10, new Color(103, 119, 148), false);
        version.setBorder(new EmptyBorder(0, 5, 0, 0));footer.add(version);
        sidebar.add(footer, BorderLayout.SOUTH);
        return sidebar;
    }

    private void nav(JPanel host, String title, String page, int symbol) {
        NavButton button = new NavButton(title, page, symbol);
        button.setName("nav-" + page);
        button.addActionListener(event -> showPage(page));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 47));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setSelectedPage(page.equals("home"));
        host.add(button);host.add(Box.createVerticalStrut(7));navButtons.add(button);
    }

    private JPanel mainArea() {
        JPanel main = transparent(new BorderLayout());
        main.setBorder(new EmptyBorder(0, 30, 22, 30));
        JPanel header = transparent(new BorderLayout());
        header.setBorder(new EmptyBorder(23, 0, 21, 0));
        header.add(pageTitle, BorderLayout.WEST);
        header.add(pill("JAVA EDITION  ·  26.3", new Color(23, 35, 48), CYAN), BorderLayout.EAST);
        main.add(header, BorderLayout.NORTH);
        pageHost.setOpaque(false);
        pageHost.add(home(), "home");
        pageHost.add(settings(), "settings");
        pageHost.add(activity(), "log");
        main.add(pageHost, BorderLayout.CENTER);
        return main;
    }

    private JComponent home() {
        JPanel content = transparent();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        hero.setLayout(new BorderLayout());
        hero.setBorder(new EmptyBorder(25, 28, 24, 24));
        hero.setPreferredSize(new Dimension(700, 260));
        hero.setMinimumSize(new Dimension(100, 260));
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        JPanel copy = transparent();
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.setPreferredSize(new Dimension(390, 0));
        JLabel eyebrow = label("EN NY BÖRJAN", 10, CYAN, true);
        copy.add(eyebrow);copy.add(Box.createVerticalStrut(14));
        copy.add(label("Bygg din", 38, TEXT, true));
        copy.add(label("nästa värld.", 38, TEXT, true));
        copy.add(Box.createVerticalStrut(11));
        copy.add(label("Minecraft, Fabric och Aurora på samma plats.", 11, new Color(183, 195, 218), false));
        copy.add(Box.createVerticalGlue());
        hero.add(copy, BorderLayout.WEST);
        content.add(hero);
        gap(content, 16);

        JPanel metrics = transparent(new GridLayout(1, 3, 12, 0));
        metrics.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
        metrics.setPreferredSize(new Dimension(600, 72));
        metrics.add(metric("SPELVERSION", "Minecraft 26.3", CYAN));
        metrics.add(metric("KLIENT", "Aurora 1.10.0", PURPLE));
        metrics.add(metric("MODLADDARE", "Fabric 0.20.1", new Color(230, 192, 126)));
        content.add(metrics);gap(content, 16);

        Card instance = new Card();instance.setLayout(new BorderLayout(16, 0));
        instance.setBorder(new EmptyBorder(17, 20, 17, 18));
        instance.setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));
        JPanel info = transparent(new GridLayout(2, 1, 0, 5));
        info.add(label("DIN MINECRAFT-INSTANS", 9, MUTED, true));
        instanceSummary.setName("instanceSummary");info.add(instanceSummary);
        instance.add(info, BorderLayout.CENTER);
        SoftButton choose = button("Välj mapp", false, "chooseInstance", actions::chooseInstance);
        serviceButtons.add(choose);instance.add(choose, BorderLayout.EAST);
        content.add(instance);gap(content, 18);

        JPanel controls = transparent(new BorderLayout(12, 0));
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 49));
        installButton = button("Installera & spela", true, "installAndPlay", actions::installAndPlay);
        installButton.setPreferredSize(new Dimension(245, 49));serviceButtons.add(installButton);
        controls.add(installButton, BorderLayout.WEST);
        JPanel secondary = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        loginButton = button("Logga in med Microsoft", false, "login", actions::login);
        SoftButton launch = loginButton;
        launch.setPreferredSize(new Dimension(204, 49));serviceButtons.add(launch);secondary.add(launch);
        controls.add(secondary, BorderLayout.CENTER);
        SoftButton folder = button("Öppna spelmapp", false, "openGameFolder", actions::openGameFolder);
        controls.add(folder, BorderLayout.EAST);
        content.add(controls);gap(content, 17);

        Card status = new Card();status.setLayout(new BorderLayout(0, 9));
        status.setBorder(new EmptyBorder(15, 18, 15, 18));
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 95));
        statusLabel.setName("status");status.add(statusLabel, BorderLayout.NORTH);
        progress.setName("progress");progress.setBorderPainted(false);progress.setStringPainted(false);
        progress.setBackground(new Color(34, 42, 61));progress.setForeground(PURPLE);
        progress.setPreferredSize(new Dimension(100, 4));status.add(progress, BorderLayout.CENTER);
        progressText.setName("progressText");status.add(progressText, BorderLayout.SOUTH);
        content.add(status);gap(content, 8);
        content.add(Box.createVerticalGlue());
        return scroll(content);
    }

    private JComponent settings() {
        JPanel content = transparent();content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(label("Gör Aurora till din egen.", 27, TEXT, true));gap(content, 7);
        content.add(label("Välj spelmapp, Java och minne för din Minecraft-instans.", 12, MUTED, false));gap(content, 24);
        Card card = new Card();card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(24, 24, 24, 24));
        settingsField(card, "Minecraft-instans", "Mappen som innehåller instansens mods-mapp.", instanceField,
            "Bläddra…", "settingsChooseInstance", actions::chooseInstance);
        gap(card, 22);
        settingsField(card, "Java 25", "Tomt fält: Java 25 väljs eller installeras automatiskt.", javaField,
            "Bläddra…", "chooseJava", actions::chooseJava);
        gap(card, 22);
        JPanel memoryRow = transparent(new BorderLayout());
        memoryRow.add(label("Minne för Minecraft", 13, TEXT, true), BorderLayout.WEST);memoryRow.add(memoryValue, BorderLayout.EAST);
        memoryRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));memoryRow.setAlignmentX(Component.LEFT_ALIGNMENT);card.add(memoryRow);
        memory.setName("memoryGb");memory.setOpaque(false);memory.setForeground(PURPLE);
        memory.setMajorTickSpacing(2);memory.setPaintTicks(false);memory.setAlignmentX(Component.LEFT_ALIGNMENT);
        memory.setUI(new BasicSliderUI(memory) {
            @Override protected Dimension getThumbSize() {return new Dimension(15, 15);}
            @Override public void paintTrack(Graphics graphics) {
                Graphics2D g=quality(graphics);int y=trackRect.y+trackRect.height/2;
                g.setColor(LINE);g.fillRoundRect(trackRect.x,y-2,trackRect.width,4,4,4);
                g.setColor(PURPLE);g.fillRoundRect(trackRect.x,y-2,Math.max(0,thumbRect.x-trackRect.x+thumbRect.width/2),4,4,4);g.dispose();
            }
            @Override public void paintThumb(Graphics graphics) {
                Graphics2D g=quality(graphics);g.setColor(PURPLE);g.fillOval(thumbRect.x,thumbRect.y,thumbRect.width,thumbRect.height);
                if(memory.isFocusOwner()){g.setColor(CYAN);g.drawOval(thumbRect.x-2,thumbRect.y-2,thumbRect.width+4,thumbRect.height+4);}g.dispose();
            }
            @Override public void paintFocus(Graphics graphics) { }
        });
        memory.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));memory.addChangeListener(event -> memoryValue.setText(memory.getValue()+" GB"));card.add(memory);
        gap(card, 22);
        settingsField(card, "Microsoft-app-ID", "Publikt klient-ID för Microsoft-inloggningen. Ingen klienthemlighet behövs.", clientField,
            null, null, null);
        gap(card, 22);
        closeOnLaunch.setName("closeOnLaunch");closeOnLaunch.setFont(REGULAR.deriveFont(12f));
        closeOnLaunch.setForeground(TEXT);closeOnLaunch.setOpaque(false);closeOnLaunch.setFocusPainted(false);
        closeOnLaunch.setAlignmentX(Component.LEFT_ALIGNMENT);card.add(closeOnLaunch);
        gap(card, 24);
        SoftButton save = button("Spara inställningar", true, "saveSettings", () -> actions.saveSettings(readSettings()));
        serviceButtons.add(save);save.setAlignmentX(Component.LEFT_ALIGNMENT);card.add(save);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 565));
        content.add(card);gap(content, 17);
        settingsNotice.setName("settingsNotice");content.add(settingsNotice);
        content.add(Box.createVerticalGlue());
        return scroll(content);
    }

    private JComponent activity() {
        JPanel content = transparent(new BorderLayout(0, 18));
        JPanel heading = transparent(new BorderLayout());
        JPanel copy = transparent(new GridLayout(2, 1, 0, 7));
        copy.add(label("Allt som händer, på ett ställe.", 24, TEXT, true));
        copy.add(label("Här visas förloppet när du installerar och startar.", 12, MUTED, false));
        heading.add(copy, BorderLayout.CENTER);
        content.add(heading, BorderLayout.NORTH);
        Card logCard = new Card();logCard.setLayout(new BorderLayout());logCard.setBorder(new EmptyBorder(17, 18, 17, 10));
        log.setName("activityLog");log.setEditable(false);log.setLineWrap(true);log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));log.setForeground(new Color(188, 201, 224));
        log.setBackground(PANEL);log.setCaretColor(PURPLE);log.setBorder(new EmptyBorder(0, 0, 0, 6));
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        logCard.add(scroll(log), BorderLayout.CENTER);content.add(logCard, BorderLayout.CENTER);
        return content;
    }

    private void settingsField(JPanel panel, String title, String hint, JTextField input,
                               String actionText, String name, Runnable action) {
        JLabel titleLabel = label(title, 13, TEXT, true);titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(titleLabel);
        gap(panel, 5);JLabel hintLabel = label(hint, 11, MUTED, false);hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(hintLabel);
        gap(panel, 9);
        JPanel row = transparent(new BorderLayout(10, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));row.setPreferredSize(new Dimension(600, 42));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);row.add(input, BorderLayout.CENTER);
        if (actionText != null) {
            SoftButton browse = button(actionText, false, name, action);
            serviceButtons.add(browse);row.add(browse, BorderLayout.EAST);
        }
        panel.add(row);
    }

    private Settings readSettings() {
        return new Settings(instanceField.getText().trim(), javaField.getText().trim(),
            clientField.getText().trim(), memory.getValue()*1024, closeOnLaunch.isSelected());
    }
    private void applySettings(Settings value) {
        settings = Objects.requireNonNull(value, "settings");
        instanceField.setText(value.instancePath());javaField.setText(value.javaPath());
        clientField.setText(value.clientId());memory.setValue(value.memoryMb()/1024);closeOnLaunch.setSelected(value.closeOnLaunch());
        instanceSummary.setText(value.instancePath().isBlank() ? "Välj din Minecraft-instans" : value.instancePath());
        instanceSummary.setToolTipText(value.instancePath());
    }
    private JComponent metric(String caption, String value, Color accent) {
        Card card = new Card();card.setLayout(new BorderLayout(9, 7));card.setBorder(new EmptyBorder(13, 17, 13, 12));
        card.add(label(caption, 9, MUTED, true), BorderLayout.NORTH);
        JLabel text = label(value, 12, TEXT, true);card.add(text, BorderLayout.CENTER);
        JPanel dot = new JPanel();dot.setBackground(accent);dot.setPreferredSize(new Dimension(3, 20));
        card.add(dot, BorderLayout.WEST);return card;
    }
    private SoftButton button(String title, boolean primary, String name, Runnable action) {
        SoftButton button = new SoftButton(title, primary);button.setName(name);
        button.addActionListener(event -> action.run());animatedButtons.add(button);return button;
    }
    private static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel label = new JLabel(text);label.setFont((bold ? SEMIBOLD : REGULAR).deriveFont((float) size));
        label.setForeground(color);label.setAlignmentX(Component.LEFT_ALIGNMENT);return label;
    }
    private static JTextField field(String name) {
        JTextField field = new JTextField();field.setName(name);field.setFont(REGULAR.deriveFont(12f));
        field.setForeground(TEXT);field.setCaretColor(PURPLE);field.setBackground(new Color(13, 19, 32));
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new EmptyBorder(10, 12, 10, 12)));
        field.setSelectionColor(new Color(85, 69, 138));field.setAlignmentX(Component.LEFT_ALIGNMENT);return field;
    }
    private static JPanel transparent() {JPanel panel = new JPanel();panel.setOpaque(false);panel.setAlignmentX(Component.LEFT_ALIGNMENT);return panel;}
    private static JPanel transparent(LayoutManager layout) {JPanel panel = new JPanel(layout);panel.setOpaque(false);panel.setAlignmentX(Component.LEFT_ALIGNMENT);return panel;}
    private static void gap(JPanel panel, int height) {JComponent gap=(JComponent)Box.createVerticalStrut(height);gap.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(gap);}
    private static JScrollPane scroll(Component content) {
        JScrollPane scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);scroll.getViewport().setOpaque(false);scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int direction) {return zeroButton();}
            @Override protected JButton createIncreaseButton(int direction) {return zeroButton();}
            private JButton zeroButton(){JButton b=new JButton();b.setPreferredSize(new Dimension());return b;}
            @Override protected void paintTrack(Graphics graphics,JComponent c,Rectangle bounds){graphics.setColor(BASE);graphics.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}
            @Override protected void paintThumb(Graphics graphics,JComponent c,Rectangle bounds){
                if(bounds.isEmpty())return;Graphics2D g=quality(graphics);g.setColor(new Color(62,70,96));g.fillRoundRect(bounds.x+1,bounds.y,bounds.width-2,bounds.height,6,6);g.dispose();
            }
        });
        return scroll;
    }
    private static JComponent pill(String text, Color fill, Color ink) {
        JLabel label = label(text, 9, ink, true);label.setBorder(new EmptyBorder(8, 12, 8, 12));
        JPanel pill = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = quality(graphics);g.setColor(fill);g.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);g.dispose();
            }
        };
        pill.setOpaque(false);pill.add(label);return pill;
    }
    private static Font loadFont(String path, int style) {
        try (InputStream input = AuroraWindow.class.getResourceAsStream(path)) {
            if (input != null) return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(style, 13f);
        } catch (Exception ignored) { /* Optional bundled font; platform fallback keeps the launcher usable. */ }
        return new Font("SansSerif", style, 13);
    }
    private static String safe(String value) {return value == null ? "" : value;}
    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();else SwingUtilities.invokeLater(action);
    }
    private static Graphics2D quality(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static class Card extends JPanel {
        Card() {setOpaque(false);setAlignmentX(Component.LEFT_ALIGNMENT);}
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = quality(graphics);g.setColor(PANEL);g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            g.setColor(LINE);g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 20, 20);g.dispose();
        }
    }
    private final class Background extends JPanel {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = quality(graphics);g.setColor(BASE);g.fillRect(0, 0, getWidth(), getHeight());
            float glowX = (float) (getWidth() * 0.77 + Math.sin(phase * 0.3) * 20);
            g.setPaint(new RadialGradientPaint(glowX, 80, Math.max(300, getWidth() * 0.6f),
                new float[]{0, 1}, new Color[]{new Color(51, 35, 89, 88), new Color(10, 13, 23, 0)}));
            g.fillRect(200, 0, getWidth()-200, getHeight());g.dispose();
        }
    }
    private final class Hero extends JPanel {
        Hero() {setOpaque(false);setAlignmentX(Component.LEFT_ALIGNMENT);}
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = quality(graphics);int width=getWidth(), height=getHeight();
            Shape shape = new RoundRectangle2D.Double(0, 0, width, height, 24, 24);g.clip(shape);
            g.setPaint(new GradientPaint(0, height, new Color(26, 30, 54), width, 0, new Color(37, 35, 75)));
            g.fillRect(0, 0, width, height);
            float glowX = width - 115 + (float)Math.sin(phase * .6) * 18;
            g.setPaint(new RadialGradientPaint(glowX, 96, 210,
                new float[]{0, .6f, 1}, new Color[]{new Color(110, 102, 225, 135), new Color(62, 66, 119, 50), new Color(30, 32, 59, 0)}));
            g.fillRect(0, 0, width, height);
            for (int band=0;band<5;band++) {
                double drift=Math.sin(phase+band*.7)*8;
                Path2D ribbon = new Path2D.Double();ribbon.moveTo(width*.48, height+20);
                ribbon.curveTo(width*.66, height*.5+drift, width*.55, -50+band*15, width+30, 25+band*21);
                g.setStroke(new BasicStroke(24-band*3f));g.setColor(new Color(89+band*10, 195-band*12, 221, 13+band*3));g.draw(ribbon);
            }
            // Floating isometric islands are drawn as vectors; no game assets or downloaded images are required.
            int x=width-145, y=115+(int)(Math.sin(phase*.9)*5);
            cube(g, x, y, 79, new Color(135, 125, 226), new Color(75, 67, 139), new Color(48, 53, 98));
            cube(g, x+60, y+59, 45, new Color(102, 205, 196), new Color(41, 113, 120), new Color(39, 78, 102));
            cube(g, x-72, y+49, 30, new Color(165, 153, 239), new Color(88, 79, 153), new Color(60, 61, 107));
            g.setColor(new Color(213, 224, 255, 90));
            for(int i=0;i<13;i++) {
                int sx=width-280+(i*59%270), sy=25+(i*41%190);
                int alpha=(int)(45+Math.sin(phase+i)*30);g.setColor(new Color(202, 221, 255, alpha));g.fillOval(sx, sy, 2, 2);
            }
            g.setClip(null);g.setStroke(new BasicStroke(1));g.setColor(new Color(89, 84, 135, 105));g.drawRoundRect(0, 0, width-1, height-1, 24, 24);g.dispose();
        }
    }
    private static void cube(Graphics2D g, int x, int y, int size, Color top, Color left, Color right) {
        int half=size/2, rise=size/4, depth=size/2;
        Polygon upper=new Polygon(new int[]{x,x+half,x,x-half},new int[]{y-rise,y,y+rise,y},4);
        Polygon lhs=new Polygon(new int[]{x-half,x,x,x-half},new int[]{y,y+rise,y+rise+depth,y+depth},4);
        Polygon rhs=new Polygon(new int[]{x,x+half,x+half,x},new int[]{y+rise,y,y+depth,y+rise+depth},4);
        g.setColor(left);g.fillPolygon(lhs);g.setColor(right);g.fillPolygon(rhs);g.setColor(top);g.fillPolygon(upper);
        g.setColor(new Color(230, 232, 255, 28));g.drawPolygon(upper);
    }
    private static void drawMark(Graphics2D g, int x, int y, int size) {
        g.setPaint(new GradientPaint(x, y+size, CYAN, x+size, y, PURPLE));
        Path2D mark = new Path2D.Double();mark.moveTo(x+size*.05, y+size*.93);mark.lineTo(x+size*.44,y+size*.08);
        mark.quadTo(x+size*.5,y-size*.03,x+size*.57,y+size*.1);mark.lineTo(x+size*.95,y+size*.93);
        mark.lineTo(x+size*.71,y+size*.93);mark.lineTo(x+size*.51,y+size*.45);mark.lineTo(x+size*.29,y+size*.93);mark.closePath();g.fill(mark);
        g.setColor(new Color(11, 16, 28));Path2D cut=new Path2D.Double();cut.moveTo(x+size*.28,y+size*.7);cut.lineTo(x+size*.68,y+size*.7);cut.lineTo(x+size*.77,y+size*.86);cut.lineTo(x+size*.2,y+size*.86);cut.closePath();g.fill(cut);
    }
    private static BufferedImage icon() {
        BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(SIDEBAR);g.fillRoundRect(0,0,64,64,16,16);drawMark(g,10,9,44);g.dispose();return image;
    }

    private static final class SoftButton extends JButton {
        private final boolean primary;
        private float hover;
        SoftButton(String text, boolean primary) {
            super(text);this.primary=primary;
            setFont(SEMIBOLD.deriveFont(12f));setForeground(primary ? new Color(20, 16, 43) : TEXT);
            setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setOpaque(false);
            setBorder(new EmptyBorder(12, 17, 12, 17));setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }
        void advance() {
            float target=isEnabled() && (getModel().isRollover() || isFocusOwner())?1:0;
            float previous=hover;hover+=(target-hover)*.22f;
            if(Math.abs(previous-hover)>.001)repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=quality(graphics);int width=getWidth(),height=getHeight();
            float brightness=isEnabled()?1:.45f;
            g.setComposite(AlphaComposite.SrcOver.derive(brightness));
            if(primary) {
                g.setPaint(new GradientPaint(0,0,new Color(174+(int)(hover*14),151+(int)(hover*12),255),width,height,new Color(135,211,224)));
            }else g.setColor(new Color(32+(int)(hover*10),40+(int)(hover*9),60+(int)(hover*12)));
            g.fillRoundRect(0,0,width,height,12,12);
            if(!primary) {g.setColor(new Color(68+(int)(hover*14),77+(int)(hover*12),105+(int)(hover*18)));g.drawRoundRect(0,0,width-1,height-1,12,12);}
            if(isFocusOwner()) {g.setColor(CYAN);g.drawRoundRect(2,2,width-5,height-5,9,9);}
            g.dispose();super.paintComponent(graphics);
        }
    }
    private final class NavButton extends JButton {
        private final String page;
        private final int symbol;
        private boolean selected;
        NavButton(String title,String page,int symbol) {
            super(title);this.page=page;this.symbol=symbol;
            setFont(SEMIBOLD.deriveFont(12f));setForeground(MUTED);setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(new EmptyBorder(12,39,12,10));setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setRolloverEnabled(true);
        }
        void setSelectedPage(boolean value) {selected=value;setForeground(value?TEXT:MUTED);repaint();}
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=quality(graphics);
            if(selected || getModel().isRollover()) {
                g.setColor(selected?new Color(49,41,78):new Color(25,31,47));g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);
            }
            if(selected) {g.setColor(PURPLE);g.fillRoundRect(0,10,3,getHeight()-20,3,3);}
            g.setColor(selected?PURPLE:MUTED);g.setStroke(new BasicStroke(1.5f));
            int x=15,y=getHeight()/2-6;
            if(symbol==0) {g.drawRoundRect(x,y,13,12,3,3);g.drawLine(x+5,y,x+5,y+12);g.drawLine(x+5,y+5,x+13,y+5);}
            else if(symbol==1) {g.drawOval(x+3,y+3,7,7);for(int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine(x+6+(int)(Math.cos(a)*5),y+6+(int)(Math.sin(a)*5),x+6+(int)(Math.cos(a)*8),y+6+(int)(Math.sin(a)*8));}}
            else {g.drawRoundRect(x,y,13,13,3,3);g.drawLine(x+3,y+4,x+10,y+4);g.drawLine(x+3,y+8,x+10,y+8);}
            if(isFocusOwner()) {g.setColor(CYAN);g.drawRoundRect(2,2,getWidth()-5,getHeight()-5,10,10);}
            g.dispose();super.paintComponent(graphics);
        }
    }
}
