package dev.aurora.minecraft;

import dev.aurora.core.HudLayout;
import dev.aurora.core.HudLayout.Bounds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class AuroraHud {
    private static final Map<String, Bounds> PANELS = new LinkedHashMap<>();
    private static String dragging;
    private static double offsetX, offsetY;
    private static boolean moved;
    private AuroraHud() { }

    public static Map<String, Bounds> panels() { return Map.copyOf(PANELS); }

    public static void render(GuiGraphicsExtractor graphics) {
        PANELS.clear();
        AuroraClient client = AuroraClient.instance();
        Minecraft mc = Minecraft.getInstance();
        if (client == null || mc.player == null || mc.level == null || mc.gui.hud.isHidden()) return;
        boolean editing = mc.gui.screen() instanceof ChatScreen;
        List<TextPanel> lines = new ArrayList<>();
        if (client.enabled("fps_counter")) lines.add(new TextPanel("fps_counter", mc.getFps() + " FPS"));
        if (client.enabled("coordinates")) lines.add(new TextPanel("coordinates", String.format(Locale.ROOT, "XYZ  %.1f / %.1f / %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ())));
        if (client.enabled("biome_info")) lines.add(new TextPanel("biome_info", "Biome  " + mc.level.getBiome(mc.player.blockPosition()).unwrapKey().map(key -> key.identifier().getPath()).orElse("Unknown")));
        if (client.enabled("block_info")) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                var pos = hit.getBlockPos();
                lines.add(new TextPanel("block_info", mc.level.getBlockState(pos).getBlock().getName().getString() + "  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
            } else if (editing) lines.add(new TextPanel("block_info", "Block Info"));
        }
        if (client.enabled("attack_cooldown")) lines.add(new TextPanel("attack_cooldown", "Attack ready  " + Math.round(mc.player.getAttackStrengthScale(0) * 100) + "%"));
        int y = 10;
        for (TextPanel panel : lines) {
            int width = Math.min(graphics.guiWidth(), mc.font.width(panel.text) + 16);
            Bounds bounds = place(panel.id, width, 20, graphics, 8, y);
            background(graphics, panel.id, bounds, editing);
            graphics.text(mc.font, mc.font.plainSubstrByWidth(panel.text, Math.max(0, width - 16)), bounds.x() + 8, bounds.y() + 6, 0xffedf1ff, AuroraClient.instance().config().flag(panel.id, "text_shadow", false));
            y += 24;
        }
        if (client.enabled("armor_status")) renderArmor(graphics, editing);
    }

    private static void renderArmor(GuiGraphicsExtractor graphics, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        List<ItemStack> armor = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (!stack.isEmpty()) armor.add(stack);
        }
        if (armor.isEmpty() && !editing) return;
        int textWidth = armor.stream().mapToInt(stack -> mc.font.width(durability(stack))).max().orElse(mc.font.width("Armor"));
        int width = textWidth + 40, height = Math.max(1, armor.size()) * 24 + 8;
        Bounds bounds = place("armor_status", width, height, graphics, graphics.guiWidth() - width - 8, graphics.guiHeight() - height - 44);
        background(graphics, "armor_status", bounds, editing);
        if (armor.isEmpty()) graphics.text(mc.font, "Armor", bounds.x() + 8, bounds.y() + 12, 0xff8a93ad, false);
        for (int i = 0; i < armor.size(); i++) {
            ItemStack stack = armor.get(i);
            int rowY = bounds.y() + 4 + i * 24;
            graphics.item(stack, bounds.x() + 8, rowY + 4);
            graphics.itemDecorations(mc.font, stack, bounds.x() + 8, rowY + 4);
            graphics.text(mc.font, durability(stack), bounds.x() + 30, rowY + 8, durabilityColor(stack), AuroraClient.instance().config().flag("armor_status", "text_shadow", false));
        }
    }

    private static String durability(ItemStack stack) {
        return stack.isDamageableItem() ? (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage() : "-";
    }

    private static int durabilityColor(ItemStack stack) {
        if (!stack.isDamageableItem()) return 0xffedf1ff;
        double remaining = (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
        return remaining > 0.5 ? 0xff8cdea4 : remaining > 0.2 ? 0xffffce7a : 0xffff7f8c;
    }

    private static Bounds place(String id, int width, int height, GuiGraphicsExtractor graphics, int defaultX, int defaultY) {
        Bounds bounds = HudLayout.place(AuroraClient.instance().config().hudPosition(id), width, height,
            graphics.guiWidth(), graphics.guiHeight(), defaultX, defaultY);
        PANELS.put(id, bounds);
        return bounds;
    }

    private static void background(GuiGraphicsExtractor graphics, String id, Bounds bounds, boolean editing) {
        MinecraftCanvas canvas = new MinecraftCanvas(graphics, Minecraft.getInstance().font);
        if (AuroraClient.instance().config().flag(id, "background", true)) {
            canvas.roundedRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 5, 0xd9111522);
            canvas.roundedRect(bounds.x(), bounds.y() + 5, 2, bounds.height() - 10, 1,
                AuroraClient.instance().config().number(id, "accent", 0xff9b8aff));
        }
        if (editing) graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), id.equals(dragging) ? 0xffedf1ff : 0xaa9b8aff);
    }

    public static void editorHint(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || PANELS.isEmpty()) return;
        String hint = "Aurora HUD: drag panels to move";
        int x = Math.max(4, (graphics.guiWidth() - mc.font.width(hint)) / 2);
        graphics.text(mc.font, hint, x, graphics.guiHeight() - 38, 0xffc4baff, true);
    }

    public static boolean beginDrag(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        AuroraClient client = AuroraClient.instance();
        if (!(mc.gui.screen() instanceof ChatScreen) || client == null || mc.player == null || mc.gui.hud.isHidden()
                || mouseY >= mc.getWindow().getGuiScaledHeight() - 24) return false;
        for (var panel : new ArrayList<>(PANELS.entrySet()).reversed()) {
            if (client.enabled(panel.getKey()) && panel.getValue().contains(mouseX, mouseY)) {
                dragging = panel.getKey();
                offsetX = mouseX - panel.getValue().x(); offsetY = mouseY - panel.getValue().y();
                moved = false;
                return true;
            }
        }
        return false;
    }

    public static boolean drag(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Bounds bounds = PANELS.get(dragging);
        if (dragging == null || bounds == null || !(mc.gui.screen() instanceof ChatScreen)) return false;
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        Bounds next = HudLayout.move(bounds.width(), bounds.height(), mouseX - offsetX, mouseY - offsetY, width, height);
        if (next.x() != bounds.x() || next.y() != bounds.y()) {
            AuroraClient.instance().config().hudPosition(dragging, HudLayout.normalize(next, width, height));
            PANELS.put(dragging, next);
            moved = true;
        }
        return true;
    }

    public static boolean finishDrag() {
        if (dragging == null) return false;
        dragging = null;
        if (moved) {
            moved = false;
            try { AuroraClient.instance().save(); }
            catch (IllegalStateException error) {
                Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora: Could not save HUD positions."));
            }
        }
        return true;
    }

    private record TextPanel(String id, String text) { }
}
