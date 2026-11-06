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
    @Inject(method="handleSetPlayerInventory",at=@At("TAIL"))
    private void aurora$inventory(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet,CallbackInfo ci) {
        var client=AuroraClient.instance();if(client!=null && client.builder()!=null)client.builder().buckets.confirmedInventory(packet.slot(),packet.contents());
    }
    @Inject(method="handleContainerSetSlot",at=@At("TAIL"))
    private void aurora$inventorySlot(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket packet,CallbackInfo ci) {
        var client=AuroraClient.instance();if(client==null || client.builder()==null)return;
        int slot=packet.getSlot();
        if(packet.getContainerId()==0 && slot>=36 && slot<=44)client.builder().buckets.confirmedInventory(slot-36,packet.getItem());
        else if(packet.getContainerId()==0 && slot>=9 && slot<=35 || packet.getContainerId()==-2)client.builder().buckets.confirmedInventory(slot,packet.getItem());
    }

}
