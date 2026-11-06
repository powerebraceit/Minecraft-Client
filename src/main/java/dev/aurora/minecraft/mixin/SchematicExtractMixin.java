package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class SchematicExtractMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Inject(method="extract",at=@At("TAIL"))
    private void aurora$extract(DeltaTracker delta,Camera camera,float partialTick,CallbackInfo ci) {
        var client=AuroraClient.instance();
        if(client!=null)client.builder().preview.extract(levelRenderState);
    }
}
