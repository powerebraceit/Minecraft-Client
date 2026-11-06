package dev.aurora.minecraft.mixin;
import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class BuilderTickMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void aurora$builder(CallbackInfo ci) {
        var client=AuroraClient.instance();
        if(client!=null)client.builder().tick();
    }
}
