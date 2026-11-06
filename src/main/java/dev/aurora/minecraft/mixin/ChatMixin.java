package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.AuroraClient;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public abstract class ChatMixin {
    @ModifyVariable(method = {"addClientSystemMessage", "addServerSystemMessage", "addPlayerMessage"},
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component aurora$timestamp(Component message) {
        AuroraClient client = AuroraClient.instance();
        return client == null ? message : client.timestamp(message);
    }
}
