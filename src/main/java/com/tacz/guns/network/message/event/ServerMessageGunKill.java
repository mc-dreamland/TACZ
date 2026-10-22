package com.tacz.guns.network.message.event;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
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

public class ServerMessageGunKill implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageGunKill> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_gun_kill")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageGunKill> STREAM_CODEC = StreamCodec.of(
        ServerMessageGunKill::encode,
        ServerMessageGunKill::decode
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int bulletId;
    public final int killEntityId;
    public final int attackerId;
    private final Identifier gunId;
    private final Identifier gunDisplayId;
    private final boolean isHeadShot;
    private final float baseDamage;
    private final float headshotMultiplier;

    public ServerMessageGunKill(int bulletId, int killEntityId, int attackerId, Identifier gunId, Identifier gunDisplayId, float baseDamage, boolean isHeadShot, float headshotMultiplier) {
        this.bulletId = bulletId;
        this.killEntityId = killEntityId;
        this.attackerId = attackerId;
        this.gunId = gunId;
        this.gunDisplayId = gunDisplayId;
        this.baseDamage = baseDamage;
        this.isHeadShot = isHeadShot;
        this.headshotMultiplier = headshotMultiplier;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ServerMessageGunKill message) {
        buf.writeInt(message.bulletId);
        buf.writeInt(message.killEntityId);
        buf.writeInt(message.attackerId);
        buf.writeIdentifier(message.gunId);
        buf.writeIdentifier(message.gunDisplayId);
        buf.writeFloat(message.baseDamage);
        buf.writeBoolean(message.isHeadShot);
        buf.writeFloat(message.headshotMultiplier);
    }

    public static ServerMessageGunKill decode(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readInt();
        int killEntityId = buf.readInt();
        int attackerId = buf.readInt();
        Identifier gunId = buf.readIdentifier();
        Identifier gunDisplayId = buf.readIdentifier();
        float baseDamage = buf.readFloat();
        boolean isHeadShot = buf.readBoolean();
        float headshotMultiplier = buf.readFloat();
        return new ServerMessageGunKill(bulletId, killEntityId, attackerId, gunId, gunDisplayId, baseDamage, isHeadShot, headshotMultiplier);
    }

    public static void handle(ServerMessageGunKill message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onGunKill", new Class[]{com.tacz.guns.network.message.event.ServerMessageGunKill.class}, message));
    }

    public Identifier getGunId() {
        return gunId;
    }

    public Identifier getGunDisplayId() {
        return gunDisplayId;
    }

    public boolean isHeadShot() {
        return isHeadShot;
    }

    public float getBaseDamage() {
        return baseDamage;
    }

    public float getHeadshotMultiplier() {
        return headshotMultiplier;
    }

}
