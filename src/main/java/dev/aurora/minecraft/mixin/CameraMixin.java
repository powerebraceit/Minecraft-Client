package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void aurora$zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
        AuroraClient client = AuroraClient.instance();
        if (client != null) cir.setReturnValue(client.zoom(cir.getReturnValue()));
    }
}
