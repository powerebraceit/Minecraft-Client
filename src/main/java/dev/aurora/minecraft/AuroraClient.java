package dev.aurora.minecraft;

import dev.aurora.core.ClientConfig;
import dev.aurora.core.ClientModule;
import dev.aurora.core.ModuleRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuroraClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Aurora");
    private static AuroraClient instance;
    private final ModuleRegistry modules = ModuleRegistry.catalog();
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("aurora.properties");
    private ClientConfig config;
    private double zoomFactor = 1;
    private long lastZoomNanos;

    public static AuroraClient instance() { return instance; }
    public ModuleRegistry modules() { return modules; }
    public ClientConfig config() { return config; }

    @Override public void onInitializeClient() {
        instance = this;
        try { config = ClientConfig.load(configPath); }
        catch (IOException | IllegalArgumentException error) {
            LOG.error("Could not read Aurora settings; using defaults (original file retained)", error);
            config = new ClientConfig();
        }
        for (ClientModule module : modules.all()) {
            module.bind(new ClientModule.Effect() {
                @Override public void enable() { }
                @Override public void disable() {
                    if (module.id().equals("toggle_sprint") && Minecraft.getInstance().player != null)
                        Minecraft.getInstance().player.setSprinting(false);
                }
            });
            // Effects are applied by the input, camera, HUD and chat hooks only while enabled.
            if (config.enabledModules().contains(module.id())) module.setEnabled(true);
        }
        LOG.info("Aurora {} initialized for Minecraft 26.3: {} modules",
            FabricLoader.getInstance().getModContainer("aurora").orElseThrow().getMetadata().getVersion(), modules.all().size());
    }

    public boolean enabled(String id) {
        ClientModule module = modules.get(id);
        return module != null && module.enabled();
    }

    public void save() {
        try { config.save(configPath); }
        catch (IOException error) {
            LOG.error("Could not save Aurora settings", error);
            throw new IllegalStateException("Could not save Aurora settings", error);
        }
    }

    public void openMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null && mc.gui.overlay() == null)
            mc.gui.setScreen(new AuroraScreen(this));
    }

    public float zoom(float originalFov) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastZoomNanos == 0 ? 0 : Math.min((now - lastZoomNanos) / 1e9, 0.1);
        lastZoomNanos = now;
        double target = enabled("zoom") && mc.player != null ? 0.25 : 1;
        zoomFactor = config.reducedMotion() ? target : zoomFactor + (target - zoomFactor) * -Math.expm1(-12 * dt);
        return (float) (originalFov * zoomFactor);
    }

    public Component timestamp(Component original) {
        if (!enabled("chat_timestamps")) return original;
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        return Component.empty().append(Component.literal("[" + time + "] ").withColor(0x9b8aff)).append(original);
    }
}
