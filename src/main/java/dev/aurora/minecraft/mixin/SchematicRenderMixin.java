package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.builder.SchematicPreview;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class SchematicRenderMixin {
    @Inject(method="submitFeatures",at=@At("TAIL"))
    private void aurora$submit(LevelRenderState state,SubmitNodeCollector output,boolean outline,CallbackInfo ci) {
        SchematicPreview.submit(state,output);
    }
}
