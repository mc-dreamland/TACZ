package com.tacz.guns.network.message.event;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import com.tacz.guns.api.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class ServerMessageGunHurt implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageGunHurt> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_gun_hurt")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageGunHurt> STREAM_CODEC = StreamCodec.of(
        ServerMessageGunHurt::encode,
        ServerMessageGunHurt::decode
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int bulletId;
    public final int hurtEntityId;
    public final int attackerId;
    private final Identifier gunId;
    private final Identifier gunDisplayId;
    private final float amount;
    private final boolean isHeadShot;
    private final float headshotMultiplier;

    public ServerMessageGunHurt(int bulletId, int hurtEntityId, int attackerId, Identifier gunId, Identifier gunDisplayId,
                                float amount, boolean isHeadShot, float headshotMultiplier) {
        this.bulletId = bulletId;
        this.hurtEntityId = hurtEntityId;
        this.attackerId = attackerId;
        this.gunId = gunId;
        this.gunDisplayId = gunDisplayId;
        this.amount = amount;
        this.isHeadShot = isHeadShot;
        this.headshotMultiplier = headshotMultiplier;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ServerMessageGunHurt message) {
        buf.writeInt(message.bulletId);
        buf.writeInt(message.hurtEntityId);
        buf.writeInt(message.attackerId);
        buf.writeIdentifier(message.gunId);
        buf.writeIdentifier(message.gunDisplayId);
        buf.writeFloat(message.amount);
        buf.writeBoolean(message.isHeadShot);
        buf.writeFloat(message.headshotMultiplier);
    }

    public static ServerMessageGunHurt decode(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readInt();
        int hurtEntityId = buf.readInt();
        int attackerId = buf.readInt();
        Identifier gunId = buf.readIdentifier();
        Identifier gunDisplayId = buf.readIdentifier();
        float amount = buf.readFloat();
        boolean isHeadShot = buf.readBoolean();
        float headshotMultiplier = buf.readFloat();
        return new ServerMessageGunHurt(bulletId, hurtEntityId, attackerId, gunId, gunDisplayId, amount, isHeadShot, headshotMultiplier);
    }

    public static void handle(ServerMessageGunHurt message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onGunHurt", new Class[]{com.tacz.guns.network.message.event.ServerMessageGunHurt.class}, message));
    }

    public Identifier getGunId() {
        return gunId;
    }

    public Identifier getGunDisplayId() {
        return gunDisplayId;
    }

    public float getAmount() {
        return amount;
    }

    public boolean isHeadShot() {
        return isHeadShot;
    }

    public float getHeadshotMultiplier() {
        return headshotMultiplier;
    }

}
