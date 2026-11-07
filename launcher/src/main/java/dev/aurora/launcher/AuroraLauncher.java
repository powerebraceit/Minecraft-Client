package dev.aurora.launcher;

import dev.aurora.launcher.auth.*;
import dev.aurora.launcher.minecraft.*;
import dev.aurora.launcher.service.*;
import dev.aurora.launcher.ui.AuroraWindow;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.*;

/** Standalone entry point. UI actions are explicit; no account or game is opened on startup. */
public final class AuroraLauncher {
    public static final String VERSION="1.0.1";
    private AuroraLauncher() { }

    public static void main(String[] args) {
        if(args.length==1 && args[0].equals("--version")) {
            System.out.println("Aurora Launcher "+VERSION);return;
        }
        Path config=LauncherSettings.defaultFile();
        if(args.length==2 && args[0].equals("--config"))config=Path.of(args[1]);
        else if(args.length!=0)throw new IllegalArgumentException("Använd: java -jar AuroraLauncher.jar [--version | --config sökväg]");
        Path selectedConfig=config;
        EventQueue.invokeLater(()->{
            try {UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());}
            catch(Exception ignored) { }
            new Controller(selectedConfig).show();
        });
    }

    /** All network/files/process work lives off Swing's event thread. Tokens are never logged. */
    static final class Controller implements AuroraWindow.Actions {
        private final Path configFile;
        private final TokenStore tokens;
        private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
            // Continue draining the game's output if the launcher window is closed.
            Thread thread=new Thread(task,"aurora-launcher-worker");thread.setDaemon(false);return thread;
        });
        private final AtomicBoolean busy=new AtomicBoolean(),cancelled=new AtomicBoolean();
        private final AtomicBoolean gameRunning=new AtomicBoolean();
        private final AuthService authentication;
        private final BrowserLogin.BrowserOpener browserOpener;
        private final ModInstaller modInstaller=new ModInstaller();
        private final MinecraftInstaller minecraft=new MinecraftInstaller();
        private final JavaRuntime runtime=new JavaRuntime();
        private LauncherSettings settings;
        private final AuroraWindow window;
        private volatile Account account;
        private volatile String authenticatingSecret="";

        Controller(Path configFile) {
            this(configFile,new AuthService(),null);
        }
        /** Injectable browser and protocol transport allow tests without opening a real account. */
        Controller(Path configFile,AuthService authentication,BrowserLogin.BrowserOpener browserOpener) {
            this.authentication=Objects.requireNonNull(authentication);
            this.browserOpener=browserOpener==null?this::browseMicrosoft:browserOpener;
            this.configFile=configFile.toAbsolutePath().normalize();
            tokens=new TokenStore(this.configFile.getParent().resolve("account-session.json"));
            String initialNotice="";
            try {settings=LauncherSettings.load(this.configFile);}
            catch(IOException error) {settings=LauncherSettings.defaults();initialNotice="Inställningarna kunde inte läsas. Välj mapparna igen och spara.";}
            window=new AuroraWindow(this,toUi(settings));
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    if(gameRunning.get())worker.shutdown();
                    else {cancelled.set(true);worker.shutdownNow();}
                }
            });
            if(!initialNotice.isEmpty())window.setStatus(initialNotice,true);
        }
        void show() {
            window.setVisible(true);
            window.appendLog("Aurora Launcher "+VERSION+" • Minecraft 26.3 • Fabric 0.20.1");
            if(settings.clientId().isBlank())window.setStatus("Lägg till launcherns Microsoft-app-ID i inställningarna före inloggning.",false);
        }

        @Override public void login() {
            if(!requireClientId())return;
            LauncherSettings selected=settings;
            submit("Loggar in med Microsoft…",()->{
                Account result=browserLogin(selected);
                acceptAccount(selected,result);
            });
        }
        @Override public void logout() {
            if(busy.get())return;
            try {tokens.clear();account=null;window.setAccount("",false);window.setStatus("Du är utloggad.",false);}
            catch(IOException error) {window.setStatus("Den sparade sessionen kunde inte tas bort.",true);}
        }
        @Override public void cancelLogin() {cancelled.set(true);}
        @Override public void installAndPlay() {play();}
        @Override public void launch() {play();}

        private void play() {
            if(!requireClientId())return;
            LauncherSettings selected=settings;
            submit("Förbereder Minecraft…",()->{
                Account current=account;
                if(current==null || current.expiresAt().isBefore(Instant.now().plusSeconds(90))) {
                    var saved=tokens.load(selected.clientId());
                    if(saved.isPresent()) {
                        authenticatingSecret=saved.get();
                        try {current=authentication.refresh(selected.clientId(),saved.get(),this::log,cancelled::get);}
                        catch(AuthException error) {
                            if(!error.code().equals("LOGIN_REQUIRED"))throw error;
                            tokens.clear();current=null;account=null;window.setAccount("",false);
                            log("Den sparade sessionen har gått ut. Logga in igen.");
                        }
                        finally {authenticatingSecret="";}
                    }
                    if(current==null || current.expiresAt().isBefore(Instant.now().plusSeconds(90))) {
                        current=browserLogin(selected);
                    }
                    acceptAccount(selected,current);
                }
                window.setProgress(-1,"Förbereder Java 25…");
                Path java=runtime.resolve(selected.javaPath(),configFile.getParent().resolve("runtimes"),this::log,cancelled::get);
                InstalledGame installed=minecraft.prepare(selected.instancePath(),p->window.setProgress(p.percent(),p.message()),cancelled::get);
                modInstaller.install(selected.instancePath(),this::log);
                if(cancelled.get())throw new CancellationException();
                List<String> command=installed.command(java,selected.memoryMb(),new GameIdentity(current.name(),current.uuid(),current.accessToken(),current.xuid(),selected.clientId()));
                log("Startar Minecraft 26.3 med Aurora 1.10.0.");
                window.setProgress(100,"Minecraft startar…");
                Path logFile=selected.instancePath().resolve("logs/aurora-launcher.log");
                Files.createDirectories(logFile.getParent());
                gameRunning.set(true);
                try(BufferedWriter writer=Files.newBufferedWriter(logFile,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
                    GameProcess.RunningGame running=GameProcess.start(command,installed.directory(),configFile.getParent().resolve("launch-arguments"))) {
                    Process process=running.process();
                    window.setStatus("Minecraft körs.",false);
                    if(selected.closeOnLaunch())EventQueue.invokeLater(window::dispose);
                    boolean loggingFailed=false;
                    try(BufferedReader reader=process.inputReader(StandardCharsets.UTF_8)) {
                        String line;
                        while((line=reader.readLine())!=null) {
                            String safe=redact(line);
                            // A full disk must not stop reading stdout and freeze Minecraft.
                            if(!loggingFailed)try {writer.write(safe);writer.newLine();}
                            catch(IOException error) {loggingFailed=true;log("Spelloggen kunde inte fortsätta skrivas.");}
                            if(window.isDisplayable())window.appendLog(safe.length()>1500?safe.substring(0,1500):safe);
                        }
                    }
                    int exit=process.waitFor();
                    if(exit!=0)throw new IOException("Minecraft avslutades med kod "+exit+". Se aktivitetsloggen eller "+logFile+".");
                }finally {gameRunning.set(false);}
                window.setStatus("Minecraft har stängts. Redo att spela igen.",false);
            });
        }
        private Account browserLogin(LauncherSettings selected) throws AuthException {
            return BrowserLogin.login(authentication,selected.clientId(),uri->{
                window.showBrowserLogin(uri);
                browserOpener.open(uri);
            },this::log,cancelled::get);
        }
        private void acceptAccount(LauncherSettings selected,Account result) throws IOException {
            if(cancelled.get() || Thread.currentThread().isInterrupted())throw new CancellationException();
            tokens.save(selected.clientId(),result.refreshToken());
            account=result;window.closeDeviceLogin();window.closeBrowserLogin();window.setAccount(result.name(),true);
            window.setStatus("Inloggad som "+result.name()+".",false);
            log("Minecraft-kontot är verifierat: "+result.name());
        }
        private boolean requireClientId() {
            if(!settings.clientId().isBlank())return true;
            window.showPage("settings");
            window.setStatus("Ditt konto och launcherns app-ID är olika saker. Ange ett Microsoft-app-ID för Aurora.",true);
            JOptionPane.showMessageDialog(window,
                "Aurora öppnar Microsofts inloggning i din webbläsare och kontrollerar sedan Minecraft-licensen.\n"
                +"Microsoft kräver att utgivaren registrerar launcher-appen en gång och anger dess offentliga app-ID.\n"
                +"Spelare använder därefter sina vanliga Minecraft-konton.\n\n"
                +"Registrering och inställningar beskrivs i filen MICROSOFT_LOGIN.md.\n"
                +"Ingen klienthemlighet eller något Microsoft-lösenord ska skrivas här.",
                "Konfigurera Microsoft-inloggning",JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        @Override public void chooseInstance() {
            if(busy.get())return;
            JFileChooser chooser=new JFileChooser(settings.instancePath().toFile());
            chooser.setDialogTitle("Välj mapp för Aurora-instansen");chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showOpenDialog(window)==JFileChooser.APPROVE_OPTION)
                update(new LauncherSettings(chooser.getSelectedFile().toPath(),settings.javaPath(),settings.clientId(),settings.memoryMb(),settings.closeOnLaunch()));
        }
        @Override public void chooseJava() {
            if(busy.get())return;
            JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Välj java.exe eller java – Java 25 eller senare");
            if(chooser.showOpenDialog(window)==JFileChooser.APPROVE_OPTION)
                update(new LauncherSettings(settings.instancePath(),chooser.getSelectedFile().toPath(),settings.clientId(),settings.memoryMb(),settings.closeOnLaunch()));
        }
        @Override public void openGameFolder() {
            try {Files.createDirectories(settings.instancePath());Desktop.getDesktop().open(settings.instancePath().toFile());}
            catch(Exception error) {window.setStatus("Mappen kunde inte öppnas: "+settings.instancePath(),true);}
        }
        @Override public void saveSettings(AuroraWindow.Settings value) {
            if(busy.get())return;
            try {update(new LauncherSettings(Path.of(value.instancePath()),value.javaPath().isBlank()?null:Path.of(value.javaPath()),value.clientId(),value.memoryMb(),value.closeOnLaunch()));}
            catch(RuntimeException error) {window.setStatus("Kontrollera sökvägar, app-ID och minne.",true);}
        }
        private void update(LauncherSettings changed) {
            try {
                changed.save(configFile);
                if(!changed.clientId().equals(settings.clientId())) {tokens.clear();account=null;window.setAccount("",false);}
                settings=changed;window.setSettings(toUi(changed));window.setStatus("Inställningarna är sparade.",false);
            }catch(IOException error) {window.setStatus("Inställningarna kunde inte sparas.",true);}
        }
        @Override public void openLoginPage(URI uri) {
            try {browseMicrosoft(uri);}
            catch(Exception error) {window.setStatus("Microsofts inloggningssida kunde inte öppnas i din webbläsare.",true);}
        }
        private void browseMicrosoft(URI uri) throws IOException {
            String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || !(host.equals("microsoft.com") || host.endsWith(".microsoft.com") || host.equals("login.microsoftonline.com") || host.equals("login.live.com"))) {
                throw new IOException("Microsofts inloggningsadress kunde inte verifieras.");
            }
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new IOException("Ingen webbläsare kunde öppnas för Microsoft-inloggningen.");
            Desktop.getDesktop().browse(uri);
        }
        private void submit(String title,CheckedTask task) {
            if(!busy.compareAndSet(false,true))return;
            cancelled.set(false);window.setBusy(true);window.setProgress(-1,title);window.setStatus(title,false);
            worker.submit(()->{
                try {task.run();}
                catch(CancellationException | InterruptedException error) {
                    if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                    window.setStatus("Åtgärden avbröts.",false);window.closeDeviceLogin();window.closeBrowserLogin();
                }catch(AuthException error) {
                    boolean aborted=error.code().equals("CANCELLED") || error.code().equals("ACCESS_DENIED");
                    window.setStatus(error.getMessage(),!aborted);window.closeDeviceLogin();window.closeBrowserLogin();log(error.getMessage());
                    if(error.code().equals("CLIENT_ID_REQUIRED") || error.code().equals("CLIENT_CONFIGURATION"))window.showPage("settings");
                }catch(Exception error) {
                    String message=error.getMessage();
                    if(message==null || message.isBlank())message="Åtgärden kunde inte slutföras.";
                    message=redact(message);window.setStatus(message,true);window.closeDeviceLogin();window.closeBrowserLogin();log(message);
                }finally {authenticatingSecret="";busy.set(false);window.setBusy(false);}
            });
        }
        private void log(String message) {window.appendLog(redact(message));}
        private String redact(String value) {
            String result=value==null?"":value;
            Account current=account;
            if(current!=null) {
                if(!current.accessToken().isEmpty())result=result.replace(current.accessToken(),"[dolt]");
                if(!current.refreshToken().isEmpty())result=result.replace(current.refreshToken(),"[dolt]");
            }
            if(!authenticatingSecret.isEmpty())result=result.replace(authenticatingSecret,"[dolt]");
            return result.replaceAll("(?i)(access_token|refresh_token|id_token|identityToken|authorization)([\\\"' :=]+)[^\\s\\\",}]+","$1$2[dolt]");
        }
        private static AuroraWindow.Settings toUi(LauncherSettings value) {
            return new AuroraWindow.Settings(value.instancePath().toString(),value.javaPath()==null?"":value.javaPath().toString(),value.clientId(),value.memoryMb(),value.closeOnLaunch());
        }
        @FunctionalInterface private interface CheckedTask {void run() throws Exception;}
    }
}
