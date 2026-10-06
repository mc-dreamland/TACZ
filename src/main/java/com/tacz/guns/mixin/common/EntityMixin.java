package com.tacz.guns.mixin.common;

import com.tacz.guns.api.entity.IMoveDistTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin implements IMoveDistTracker {
    @Unique
    private float tacz$moveDistO;
    @Unique
    private boolean tacz$moveDistInit;

    @Override
    public float tacz$getMoveDistO() {
        return this.tacz$moveDistInit ? this.tacz$moveDistO : ((Entity) (Object) this).moveDist;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tacz$captureMoveDistO(CallbackInfo ci) {
        this.tacz$moveDistO = ((Entity) (Object) this).moveDist;
        this.tacz$moveDistInit = true;
    }
}
