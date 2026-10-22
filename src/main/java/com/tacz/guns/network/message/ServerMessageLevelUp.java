package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ServerMessageLevelUp implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageLevelUp> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_level_up")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageLevelUp> STREAM_CODEC = StreamCodec.composite(
        ItemStack.STREAM_CODEC, ServerMessageLevelUp::getGun,
        ByteBufCodecs.INT, ServerMessageLevelUp::getLevel,
        ServerMessageLevelUp::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final ItemStack gun;
    public final int level;

    public ServerMessageLevelUp(ItemStack gun, int level) {
        this.gun = gun;
        this.level = level;
    }

    public static void handle(ServerMessageLevelUp message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onLevelUp", new Class[]{com.tacz.guns.network.message.ServerMessageLevelUp.class}, message));
    }


    public ItemStack getGun() {
        return this.gun;
    }

    public int getLevel() {
        return this.level;
    }
}
