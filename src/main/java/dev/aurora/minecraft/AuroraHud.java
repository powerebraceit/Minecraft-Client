package dev.aurora.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class AuroraHud {
    private AuroraHud() { }

    public static void render(GuiGraphicsExtractor graphics) {
        AuroraClient client = AuroraClient.instance();
        Minecraft mc = Minecraft.getInstance();
        if (client == null || mc.player == null || mc.level == null || mc.gui.hud.isHidden()) return;
        List<String> lines = new ArrayList<>();
        if (client.enabled("fps_counter")) lines.add(mc.getFps() + " FPS");
        if (client.enabled("coordinates")) lines.add(String.format(Locale.ROOT, "XYZ  %.1f / %.1f / %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        if (client.enabled("biome_info")) lines.add("Biome  " + mc.level.getBiome(mc.player.blockPosition()).unwrapKey().map(key -> key.identifier().getPath()).orElse("Unknown"));
        if (client.enabled("block_info") && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            lines.add(mc.level.getBlockState(pos).getBlock().getName().getString() + "  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        }
        if (client.enabled("attack_cooldown")) lines.add("Attack ready  " + Math.round(mc.player.getAttackStrengthScale(0) * 100) + "%");
        if (client.enabled("armor_status")) {
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                ItemStack stack = mc.player.getItemBySlot(slot);
                if (!stack.isEmpty()) lines.add(stack.getHoverName().getString() + (stack.isDamageableItem() ? "  " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage() : ""));
            }
        }
        int y = 10;
        MinecraftCanvas canvas = new MinecraftCanvas(graphics, mc.font);
        for (String line : lines) {
            canvas.roundedRect(8, y, mc.font.width(line) + 16, 20, 5, 0xd9111522);
            canvas.roundedRect(8, y + 5, 2, 10, 1, 0xff9b8aff);
            graphics.text(mc.font, line, 16, y + 6, 0xffedf1ff, false);
            y += 24;
        }
    }
}
