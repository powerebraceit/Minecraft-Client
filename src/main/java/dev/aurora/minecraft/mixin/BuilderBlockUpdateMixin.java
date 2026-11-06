package dev.aurora.minecraft.mixin;
import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
public abstract class BuilderBlockUpdateMixin {
    @Inject(method="handleBlockUpdate",at=@At("TAIL"))
    private void aurora$block(ClientboundBlockUpdatePacket packet,CallbackInfo ci) {
        var client=AuroraClient.instance();
        if(client!=null)client.builder().confirmed(packet.getPos(),packet.getBlockState());
    }
    @Inject(method="handleChunkBlocksUpdate",at=@At("TAIL"))
    private void aurora$section(ClientboundSectionBlocksUpdatePacket packet,CallbackInfo ci) {
        var client=AuroraClient.instance();
        if(client!=null)packet.runUpdates(client.builder()::confirmed);
    }
}
