package dev.aurora.minecraft.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.minecraft.AuroraHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    protected ChatScreenMixin() { super(Component.empty()); }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void aurora$grab(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && AuroraHud.beginDrag(event.x(), event.y())) {
            setDragging(true);
            cir.setReturnValue(true);
        }
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && AuroraHud.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && AuroraHud.finishDrag()) {
            setDragging(false);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void aurora$saveOnClose(CallbackInfo ci) { AuroraHud.finishDrag(); }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void aurora$hint(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        AuroraHud.editorHint(graphics);
    }
}
