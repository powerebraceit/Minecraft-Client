package dev.aurora.smoke.mixin;

import dev.aurora.smoke.SmokeCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Verify special-model geometry actually reaches the translucent collector, not just an empty terrain part. */
@Mixin(targets="dev.aurora.minecraft.builder.GhostModelCollector")
public abstract class GhostSubmitCheckMixin {
    @Inject(method="submitModel",at=@At("HEAD"))
    private void aurora$specialGeometry(CallbackInfo ci) {SmokeCheck.SPECIAL_MODELS.incrementAndGet();}
}
