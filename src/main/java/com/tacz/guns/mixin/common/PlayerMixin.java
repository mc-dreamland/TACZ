package com.tacz.guns.mixin.common;

import com.tacz.guns.api.entity.ForcePose;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Player.class)
public abstract class PlayerMixin implements ForcePose {
    /**
     * NeoForge 26.1.2 already adds Player#setForcedPose/getForcedPose and applies the
     * forced pose from Player#updatePlayerPose. Keep TaCZ's small compatibility interface,
     * but delegate to that native implementation instead of storing an unused parallel
     * field that vanilla never reads.
     */
    @Override
    public Pose tacz$getForcedPose() {
        return ((Player) (Object) this).getForcedPose();
    }

    @Override
    public void tacz$setForcedPose(Pose pose) {
        ((Player) (Object) this).setForcedPose(pose);
    }
}
