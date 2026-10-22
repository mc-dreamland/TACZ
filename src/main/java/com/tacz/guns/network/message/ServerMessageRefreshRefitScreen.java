package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ServerMessageRefreshRefitScreen implements CustomPacketPayload {
    public static final ServerMessageRefreshRefitScreen INSTANCE = new ServerMessageRefreshRefitScreen();
    public static final CustomPacketPayload.Type<ServerMessageRefreshRefitScreen> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_refresh_refit_screen")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageRefreshRefitScreen> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private ServerMessageRefreshRefitScreen() { }

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerMessageRefreshRefitScreen message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onRefreshRefit", new Class[]{com.tacz.guns.network.message.ServerMessageRefreshRefitScreen.class}, message));
    }

}
