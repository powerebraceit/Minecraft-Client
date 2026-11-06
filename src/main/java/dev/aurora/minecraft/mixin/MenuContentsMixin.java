package dev.aurora.minecraft.mixin;
import dev.aurora.minecraft.AuroraClient;
import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AbstractContainerMenu.class)
public abstract class MenuContentsMixin {
    @Inject(method="initializeContents",at=@At("TAIL"))
    private void aurora$contents(int stateId,List<ItemStack> items,ItemStack carried,CallbackInfo ci) {
        var client=AuroraClient.instance();
        if(client!=null && client.builder()!=null)client.builder().restock.contents((AbstractContainerMenu)(Object)this);
    }
}
