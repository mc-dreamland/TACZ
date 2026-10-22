package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ServerMessageSound implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageSound> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_sound")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageSound> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeInt(msg.getEntityId());
            Identifier.STREAM_CODEC.encode(buf, msg.getGunId());
            Identifier.STREAM_CODEC.encode(buf, msg.getGunDisplayId());
            buf.writeUtf(msg.getSoundName());
            buf.writeFloat(msg.getVolume());
            buf.writeFloat(msg.getPitch());
            buf.writeInt(msg.getDistance());
        },
        buf -> new ServerMessageSound(
            buf.readInt(),
            Identifier.STREAM_CODEC.decode(buf),
            Identifier.STREAM_CODEC.decode(buf),
            buf.readUtf(),
            buf.readFloat(),
            buf.readFloat(),
            buf.readInt()
        )
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int entityId;
    private final Identifier gunId;
    private final Identifier gunDisplayId;
    private final String soundName;
    private final float volume;
    private final float pitch;
    public final int distance;

    public ServerMessageSound(int entityId, Identifier gunId, Identifier gunDisplayId, String soundName, float volume, float pitch, int distance) {
        this.entityId = entityId;
        this.gunId = gunId;
        this.gunDisplayId = gunDisplayId;
        this.soundName = soundName;
        this.volume = volume;
        this.pitch = pitch;
        this.distance = distance;
    }

    public static void handle(ServerMessageSound message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onSound", new Class[]{com.tacz.guns.network.message.ServerMessageSound.class}, message));
    }

    public int getEntityId() {
        return entityId;
    }

    public Identifier getGunId() {
        return gunId;
    }

    public Identifier getGunDisplayId() {
        return gunDisplayId;
    }

    public String getSoundName() {
        return soundName;
    }

    public float getVolume() {
        return volume;
    }

    public float getPitch() {
        return pitch;
    }

    public int getDistance() {
        return distance;
    }
}
