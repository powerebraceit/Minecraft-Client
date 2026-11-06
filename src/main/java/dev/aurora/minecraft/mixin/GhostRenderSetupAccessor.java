package dev.aurora.minecraft.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.client.renderer.rendertype.RenderSetup")
public interface GhostRenderSetupAccessor {
    @Accessor("textures") java.util.Map<String,?> aurora$textures();
}
