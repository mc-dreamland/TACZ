package com.tacz.guns.network.message.event;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.event.common.GunFireEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import com.tacz.guns.api.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ServerMessageGunFire implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageGunFire> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_gun_fire")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageGunFire> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, message -> message.shooterId,
        ItemStack.STREAM_CODEC, message -> message.gunItemStack,
        ServerMessageGunFire::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int shooterId;
    public final ItemStack gunItemStack;

    public ServerMessageGunFire(int shooterId, ItemStack gunItemStack) {
        this.shooterId = shooterId;
        this.gunItemStack = gunItemStack;
    }

    public static void handle(ServerMessageGunFire message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onGunFire", new Class[]{com.tacz.guns.network.message.event.ServerMessageGunFire.class}, message));
    }

}
