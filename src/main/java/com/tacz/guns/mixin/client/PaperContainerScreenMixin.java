package com.tacz.guns.mixin.client;

import com.tacz.guns.client.gui.GunSmithTableScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class PaperContainerScreenMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tacz$tickPaperWorkbench(CallbackInfo ci) {
        if ((Object) this instanceof GunSmithTableScreen screen && screen.isPaperWorkbench()) {
            // The final vanilla tick closes a real server container on death. This view has none.
            screen.tickPaperWorkbench();
            ci.cancel();
        }
    }
}
