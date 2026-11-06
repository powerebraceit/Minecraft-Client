package dev.aurora.minecraft.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface GhostTextureAccessor {
    @Accessor("location") net.minecraft.resources.Identifier aurora$location();
}
