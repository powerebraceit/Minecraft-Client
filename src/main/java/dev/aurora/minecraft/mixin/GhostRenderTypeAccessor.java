package dev.aurora.minecraft.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.client.renderer.rendertype.RenderType")
public interface GhostRenderTypeAccessor {
    @Accessor("state") net.minecraft.client.renderer.rendertype.RenderSetup aurora$state();
}
