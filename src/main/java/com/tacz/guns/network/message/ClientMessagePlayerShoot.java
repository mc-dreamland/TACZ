package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ClientMessagePlayerShoot implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientMessagePlayerShoot> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "client_player_shoot")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientMessagePlayerShoot> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_LONG, message -> message.timestamp,
        ByteBufCodecs.FLOAT, message -> message.chargeProgress,
        ClientMessagePlayerShoot::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 这里的 timestamp 应该是基于 base timestamp 的相对值
     */
    private final long timestamp;
    private final float chargeProgress;

    public ClientMessagePlayerShoot(long timestamp) {
        this(timestamp, 0f);
    }

    public ClientMessagePlayerShoot(long timestamp, float chargeProgress) {
        this.timestamp = timestamp;
        this.chargeProgress = chargeProgress;
    }

    public static void handle(ClientMessagePlayerShoot message, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer entity = (ServerPlayer) context.player();
            ShootResult result = IGunOperator.fromLivingEntity(entity)
                    .shoot(entity::getXRot, entity::getYRot, message.timestamp, message.chargeProgress);
            // Successful server shooting already emits ServerMessageGunShoot from
            // LivingEntityShoot and ServerMessageGunFire from the gun's actual fire cycle.
            // Do not emit either event unconditionally here: a NOT_DRAW/NO_AMMO/etc.
            // result would otherwise look like a real shot on the client.
            GunMod.LOGGER.debug("C2S shoot entity={} ts={} result={}", entity.getId(), message.timestamp, result);
        });
    }
}