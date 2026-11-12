package com.tacz.guns.resource.pojo.data.gun;

import com.google.common.collect.Maps;
import com.google.gson.annotations.SerializedName;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.util.HitboxHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

public enum InaccuracyType {
    /**
     * 站立不动
     */
    @SerializedName("stand")
    STAND,
    /**
     * 移动
     */
    @SerializedName("move")
    MOVE,
    /**
     * 潜行，认为是其他 FPS 游戏中的半蹲
     */
    @SerializedName("sneak")
    SNEAK,
    /**
     * 趴下，原版确实可以趴下
     */
    @SerializedName("lie")
    LIE,
    /**
     * 瞄准状态
     */
    @SerializedName("aim")
    AIM,

    /**
     * 离地腰射，无水平移动
     */
    @SerializedName("airborne")
    AIRBORNE,
    /**
     * 离地腰射，有水平移动
     */
    @SerializedName("airborne_move")
    AIRBORNE_MOVE,
    /**
     * 离地瞄准
     */
    @SerializedName("airborne_aim")
    AIRBORNE_AIM;

    /**
     * 获取当前的不准确度状态
     *
     * @param livingEntity 射手
     * @return 不准度情况
     */
    public static InaccuracyType getInaccuracyType(LivingEntity livingEntity) {
        float aimingProgress = IGunOperator.fromLivingEntity(livingEntity).getSynAimingProgress();
        // 离地优先于地面姿态；竖直起跳和下落不计入水平移动。
        if (!livingEntity.onGround()) {
            return getAirborneType(livingEntity.getKnownMovement(), aimingProgress);
        }
        // 地面瞄准优先级最高
        if (aimingProgress == 1.0f) {
            return InaccuracyType.AIM;
        }
        // MOJANG 的奇妙设计，趴下的姿势名称是 SWIMMING
        if (!livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING) {
            return InaccuracyType.LIE;
        }
        if (livingEntity.getPose() == Pose.CROUCHING) {
            return InaccuracyType.SNEAK;
        }
        if (isMove(livingEntity)) {
            return InaccuracyType.MOVE;
        }
        return InaccuracyType.STAND;
    }

    static InaccuracyType getAirborneType(Vec3 movement, float aimingProgress) {
        if (aimingProgress == 1.0f) return AIRBORNE_AIM;
        return movement.horizontalDistance() > 0.05f ? AIRBORNE_MOVE : AIRBORNE;
    }

    /** Only airborne ADS interpolates here; ground selection retains its existing priority. */
    public float resolveInaccuracy(Map<InaccuracyType, Float> values, float aimingProgress) {
        float base = values.get(this);
        if (this == AIRBORNE || this == AIRBORNE_MOVE) {
            float progress = Math.max(0, Math.min(1, aimingProgress));
            return base + (values.get(AIRBORNE_AIM) - base) * progress;
        }
        return base;
    }

    public boolean isAirborne() {
        return this == AIRBORNE || this == AIRBORNE_MOVE || this == AIRBORNE_AIM;
    }

    public static Map<InaccuracyType, Float> getDefaultInaccuracy() {
        Map<InaccuracyType, Float> inaccuracy = Maps.newHashMap();
        inaccuracy.put(InaccuracyType.STAND, 5f);
        inaccuracy.put(InaccuracyType.MOVE, 5.75f);
        inaccuracy.put(InaccuracyType.SNEAK, 3.5f);
        inaccuracy.put(InaccuracyType.LIE, 2.5f);
        inaccuracy.put(InaccuracyType.AIM, 0.15f);
        inaccuracy.put(InaccuracyType.AIRBORNE, inaccuracy.get(InaccuracyType.STAND));
        inaccuracy.put(InaccuracyType.AIRBORNE_MOVE, inaccuracy.get(InaccuracyType.MOVE));
        inaccuracy.put(InaccuracyType.AIRBORNE_AIM, inaccuracy.get(InaccuracyType.AIM));
        return inaccuracy;
    }

    private static boolean isMove(LivingEntity livingEntity) {
        // 26.2 对齐：上游 1.21.1 用的是 Math.abs(walkDist - walkDistO)，即“本 tick 的水平位移 * 0.6”。
        // 移植时换成了 walkAnimation.speed()，两者量纲不同：
        //   walkDist 增量        = 位移 * 0.6
        //   walkAnimation.speed  = min(位移 * 4.0, 1.0)   （见 LivingEntity#updateWalkAnimation）
        // 后者约为前者的 6.7 倍，会让 0.05 阈值被显著放大 —— 极慢速移动也判定为“移动中”。
        //
        // 26.2 中 walkDist 已更名 moveDist，但<b>没有</b>保留 moveDistO（javap 确认），
        // 无法直接算增量。改用与“本 tick 水平位移”等价的速度量并乘回 0.6 还原量纲。
        // （玩家分支下面会用实际速度覆盖，所以本行主要影响非玩家实体。）
        double distance = livingEntity.getDeltaMovement().horizontalDistance() * 0.6;
        if (livingEntity instanceof Player player) {
            distance = HitboxHelper.getPlayerVelocity(player).length();
        }
        return distance > 0.05f;
    }

    public boolean isAim() {
        return this == AIM || this == AIRBORNE_AIM;
    }
}
