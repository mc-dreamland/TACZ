package com.tacz.guns.mixin.client;

import com.tacz.guns.bridge.BridgeProtocol;
import com.tacz.guns.client.paper.PaperClientBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PaperPayloadMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void tacz$paperPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!BridgeProtocol.CHANNEL.equals(packet.getIdentifier().toString())) return;
        ci.cancel();
        FriendlyByteBuf buffer = packet.getData();
        try {
            if (buffer.readableBytes() > BridgeProtocol.MAX_PACKET_BYTES) return;
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), data);
            Minecraft minecraft = Minecraft.getInstance();
            ClientPacketListener source = (ClientPacketListener) (Object) this;
            minecraft.execute(() -> {
                // A payload queued on the old connection must not mutate a newly joined world.
                if (minecraft.getConnection() == source) PaperClientBridge.receive(data);
            });
        } finally {
            // Forge's getData() returns a copy; packet.handle() releases only its own buffer.
            buffer.release();
        }
    }
}
