package dev.aurora.smoke.mixin;

import dev.aurora.smoke.SmokeCheck;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class SmokeMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void aurora$smoke(CallbackInfo ci) {
        if (Boolean.getBoolean("aurora.smoke")) SmokeCheck.tick();
    }
}
