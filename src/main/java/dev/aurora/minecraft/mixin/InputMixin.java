package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class InputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void aurora$input(CallbackInfo ci) {
        AuroraClient client = AuroraClient.instance();
        Minecraft mc = Minecraft.getInstance();
        if (client == null || mc.player == null || mc.gui.screen() != null) return;
        if (client.builder().controlsInput()) {
            var nav = client.builder().navigator;
            keyPresses = new Input(nav.forward(), false, false, false, nav.jump(), client.builder().requiresCrouch(), false);
            moveVector = new net.minecraft.world.phys.Vec2(0, nav.forward() ? 1 : 0);
            return;
        }
        Input p = keyPresses;
        keyPresses = new Input(p.forward(), p.backward(), p.left(), p.right(), p.jump(),
            p.shift() || client.enabled("toggle_sneak"), p.sprint() || client.enabled("toggle_sprint"));
    }
}
