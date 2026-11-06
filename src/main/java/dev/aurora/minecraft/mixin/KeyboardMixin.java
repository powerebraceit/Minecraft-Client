package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void aurora$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        AuroraClient client = AuroraClient.instance();
        Minecraft mc = Minecraft.getInstance();
        if (client != null && action == 1 && window == mc.getWindow().handle()
                && mc.gui.screen() == null && mc.gui.overlay() == null
                && event.key() == client.config().menuKey()) {
            client.openMenu(); ci.cancel();
        }
    }
}
