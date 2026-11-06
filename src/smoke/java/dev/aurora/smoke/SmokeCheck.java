package dev.aurora.smoke;

import dev.aurora.core.ClientConfig;
import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.minecraft.AuroraClient;
import dev.aurora.minecraft.AuroraScreen;
import dev.aurora.minecraft.ModuleSettingsScreen;
import dev.aurora.minecraft.AuroraHud;
import dev.aurora.smoke.mixin.ChatAccessor;
import dev.aurora.smoke.mixin.ChatInputAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Screenshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Development-only integration runner. Never included in the distributed client JAR. */
public final class SmokeCheck {
    private static int stage, ticks, stageTick;
    private static boolean finished;
    private static int originalGuiScale;
    private static final Path RESULT = Path.of("aurora-smoke-result.txt");

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        AuroraClient.LOG.info("SMOKE PASS: {}", message);
    }

    public static void tick() {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            if (ticks > 2400) throw new AssertionError("Timed out in stage " + stage);
            if (mc.gui.screen() instanceof AccessibilityOnboardingScreen screen) { screen.onClose(); return; }
            AuroraClient client = AuroraClient.instance();
            if (stage == 0 && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null) {
                check(client != null && client.modules().all().stream().allMatch(m -> m.available()), "All ten modules have game implementations");
                mc.options.renderDistance().set(3);
                mc.options.simulationDistance().set(3);
                mc.createWorldOpenFlows().createFreshLevel("aurora-smoke-" + System.currentTimeMillis(),
                    new LevelSettings("Aurora smoke test", GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42, false, false), WorldPresets::createTestWorldDimensions, new TitleScreen());
                stage = 1;
            } else if (stage == 1 && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
                check(mc.level != null, "Loaded real singleplayer world");
                check(ClientConfig.RIGHT_SHIFT == InputConstants.KEY_RSHIFT, "Default binding matches Minecraft 26.3 Right Shift scancode");
                for (var module : client.modules().all()) {
                    client.config().resetHudPosition(module.id());
                    client.config().setting(module.id(), "background", "true");
                    client.config().setting(module.id(), "key", "0");
                }
                equip(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET), 0.1);
                equip(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE), 0.6);
                equip(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS), 0.9);
                equip(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS), 0.2);
                mc.player.setXRot(90);
                client.config().menuKey(ClientConfig.RIGHT_SHIFT);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(229, 0, 0));
                check(mc.gui.screen() instanceof AuroraScreen, "Right Shift opens Aurora through the keyboard hook");
                for (var module : client.modules().all()) { module.setEnabled(true); client.config().remember(module); }
                client.save();
                check(ClientConfig.load(Path.of("config/aurora.properties")).enabledModules().size() == 10, "Enabled modules persisted");
                stage = 2; stageTick = ticks;
            } else if (stage == 2 && ticks - stageTick > 30) {
                capture("aurora-menu.png");
                check(mc.gui.screen() instanceof AuroraScreen, "Animated menu rendered for 30 ticks without errors");
                AuroraScreen menu = (AuroraScreen) mc.gui.screen();
                clickPanel(menu, 200, 82);
                for (char ch : "zoom".toCharArray()) menu.charTyped(new CharacterEvent(ch));
                clickPanel(menu, 200, 135);
                check(!client.enabled("zoom"), "Native left-click and global search toggle Zoom from Combat category");
                clickPanel(menu, 200, 135);
                check(client.enabled("zoom"), "Native left-click re-enables Zoom");
                clickPanel(menu, 40, 110);
                float menuScale = Math.max(0.1f, Math.min(1, Math.min(menu.width / 720f, menu.height / 460f)));
                double menuX = (Math.round(menu.width / menuScale) - 680) / 2f;
                double menuY = (Math.round(menu.height / menuScale) - 420) / 2f;
                menu.mouseClicked(new MouseButtonEvent((menuX + 200) * menuScale, (menuY + 188) * menuScale,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false);
                check(mc.gui.screen() instanceof ModuleSettingsScreen, "Right click opens module settings in Minecraft");
                var settings = (ModuleSettingsScreen) mc.gui.screen();
                int settingsX = (settings.width - Math.min(440, settings.width - 24)) / 2;
                int settingsY = (settings.height - Math.min(360, settings.height - 24)) / 2;
                settings.mouseClicked(mouse(settingsX + 30, settingsY + 64 + 2 * 34 + 10), false);
                check(!client.config().flag("armor_status", "background", true), "HUD background can be disabled from module settings");
                check(!ClientConfig.load(Path.of("config/aurora.properties")).flag("armor_status", "background", true), "Module setting survives config reload");
                check(client.enabled("armor_status"), "Right click settings preserve module enabled state");
                settings.onClose();
                clickPanel(menu, 200, 375);
                menu.keyPressed(new KeyEvent(InputConstants.KEY_R, 0, 0));
                check(client.config().menuKey() == InputConstants.KEY_R, "Changed menu binding through native GUI input");
                mc.gui.screen().onClose();
                check(mc.gui.screen() == null, "Menu closes back into world");
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora smoke: local timestamp check"));
                String received = ((ChatAccessor) mc.gui.hud.getChat()).aurora$messages().getFirst().content().getString();
                check(received.matches("\\[\\d{2}:\\d{2}\\] Aurora smoke: local timestamp check"), "Chat timestamp hook modifies actual chat messages");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(229, 0, 0));
                check(mc.gui.screen() == null, "Old binding no longer opens menu");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(21, 0, 0));
                check(mc.gui.screen() instanceof AuroraScreen, "Changed menu binding opens menu");
                mc.gui.screen().onClose();
                client.config().menuKey(229); client.save();
                stage = 3; stageTick = ticks;
            } else if (stage == 3 && ticks - stageTick > 30) {
                client.config().setting("fps_counter", "key", Integer.toString(InputConstants.KEY_H));
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_H, 0, 0));
                check(!client.enabled("fps_counter"), "Module hotkey toggles module through keyboard hook");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_H, 0, 0));
                check(client.enabled("fps_counter"), "Module hotkey toggles module back on");
                client.config().setting("fps_counter", "key", "0");
                check(mc.player.input.keyPresses.shift(), "Toggle Sneak applied to real player input");
                check(mc.player.input.keyPresses.sprint(), "Toggle Sprint applied to real player input");
                check(mc.gameRenderer.mainCamera().getFov() < 35, "Zoom changes real camera FOV");
                capture("aurora-hud.png");
                var armor = AuroraHud.panels().get("armor_status");
                check(armor != null && armor.height() == 104, "Armor HUD contains four equipped armor rows");
                check(armor.x() == mc.getWindow().getGuiScaledWidth() - armor.width() - 8
                    && armor.y() == mc.getWindow().getGuiScaledHeight() - armor.height() - 44, "Armor HUD defaults to bottom right");
                check(!AuroraHud.beginDrag(armor.x() + 10, armor.y() + 10), "HUD cannot be dragged outside chat");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_T, 0, 0));
                stage = 4; stageTick = ticks;
            } else if (stage == 4 && ticks - stageTick > 20) {
                check(mc.gui.screen() instanceof ChatScreen, "T opens real chat screen");
                ChatScreen chat = (ChatScreen) mc.gui.screen();
                var armor = AuroraHud.panels().get("armor_status");
                check(chat.mouseClicked(mouse(armor.x() + 10, armor.y() + 10), false), "Chat mouse press grabs armor panel");
                check(chat.mouseDragged(mouse(100, 80), 0, 0), "Chat mouse drag moves armor panel");
                check(chat.mouseReleased(mouse(100, 80)), "Chat mouse release finishes drag");
                var movedArmor = AuroraHud.panels().get("armor_status");
                check(movedArmor.x() == 90 && movedArmor.y() == 70, "Dragging preserves cursor offset");
                check(ClientConfig.load(Path.of("config/aurora.properties")).hudPosition("armor_status") != null, "Armor position saved on mouse release");
                var fps = AuroraHud.panels().get("fps_counter");
                chat.mouseClicked(mouse(fps.x() + 5, fps.y() + 5), false);
                chat.mouseDragged(mouse(240, 25), 0, 0);
                chat.onClose();
                check(ClientConfig.load(Path.of("config/aurora.properties")).hudPosition("fps_counter") != null, "Closing chat during drag saves other HUD panels");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_T, 0, 0));
                stage = 5; stageTick = ticks;
            } else if (stage == 5 && ticks - stageTick > 20) {
                ChatScreen chat = (ChatScreen) mc.gui.screen();
                var input = ((ChatInputAccessor) chat).aurora$input();
                String before = input.getValue();
                chat.charTyped(new CharacterEvent('h'));
                check(input.getValue().equals(before + "h"), "Normal chat typing works after HUD dragging");
                capture("aurora-hud-editor.png");
                chat.onClose();
                check(!AuroraHud.drag(10, 10), "Chat close ends dragging");
                originalGuiScale = mc.options.guiScale().get();
                mc.options.guiScale().set(1);
                mc.resizeGui();
                stage = 6; stageTick = ticks;
            } else if (stage == 6 && ticks - stageTick > 20) {
                int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
                check(AuroraHud.panels().values().stream().allMatch(b -> b.x() >= 0 && b.y() >= 0
                    && b.x() + b.width() <= width && b.y() + b.height() <= height - 24), "Saved HUD positions remain visible after GUI scale change");
                mc.options.guiScale().set(originalGuiScale); mc.resizeGui();
                for (var module : client.modules().all()) { module.setEnabled(false); client.config().remember(module); }
                client.save();
                stage = 7; stageTick = ticks;
            } else if (stage == 7 && ticks - stageTick > 30) {
                check(!mc.player.input.keyPresses.shift(), "Disabling Sneak restores player input");
                check(!mc.player.input.keyPresses.sprint(), "Disabling Sprint restores player input");
                check(mc.gameRenderer.mainCamera().getFov() > 50, "Disabling Zoom restores camera FOV");
                check(ClientConfig.load(Path.of("config/aurora.properties")).menuKey() == 229, "Default binding restored and saved");
                check(Files.exists(Path.of("screenshots/aurora-menu.png")) && Files.exists(Path.of("screenshots/aurora-hud.png")), "Game screenshots captured");
                check(Files.exists(Path.of("screenshots/aurora-hud-editor.png")), "HUD editor screenshot captured");
                Files.writeString(RESULT, "PASS: Minecraft 26.3 client, world, menu, binding, modules, four armor icons, chat HUD dragging, saved positions and GUI scaling verified.\n");
                finished = true;
                AuroraClient.LOG.info("AURORA SMOKE COMPLETE: PASS");
                mc.stop();
            }
        } catch (Throwable error) {
            finished = true;
            AuroraClient.LOG.error("AURORA SMOKE FAILED", error);
            try { Files.writeString(RESULT, "FAIL: " + error + "\n"); } catch (Exception ignored) { }
            mc.stop();
        }
    }

    private static void equip(EquipmentSlot slot, ItemStack stack, double damageFraction) {
        stack.setDamageValue((int) (stack.getMaxDamage() * damageFraction));
        Minecraft.getInstance().player.setItemSlot(slot, stack);
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    private static void capture(String name) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
            message -> AuroraClient.LOG.info("Smoke screenshot: {}", message.getString()));
    }

    private static void clickPanel(AuroraScreen screen, double x, double y) {
        float scale = Math.max(0.1f, Math.min(1, Math.min(screen.width / 720f, screen.height / 460f)));
        double panelX = (Math.round(screen.width / scale) - 680) / 2f;
        double panelY = (Math.round(screen.height / scale) - 420) / 2f;
        screen.mouseClicked(new MouseButtonEvent((panelX + x) * scale, (panelY + y) * scale,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }
}
