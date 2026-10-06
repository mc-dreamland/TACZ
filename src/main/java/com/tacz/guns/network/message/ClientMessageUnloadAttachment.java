package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ClientMessageUnloadAttachment implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientMessageUnloadAttachment> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "client_unload_attachment")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientMessageUnloadAttachment> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, message -> message.gunSlotIndex,
        ByteBufCodecs.fromCodec(AttachmentType.CODEC), message -> message.attachmentType,
        ClientMessageUnloadAttachment::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int gunSlotIndex;
    private final AttachmentType attachmentType;

    public ClientMessageUnloadAttachment(int gunSlotIndex, AttachmentType attachmentType) {
        this.gunSlotIndex = gunSlotIndex;
        this.attachmentType = attachmentType;
    }

    public static void handle(ClientMessageUnloadAttachment message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Inventory inventory = player.getInventory();
            if (message.gunSlotIndex < 0 || message.gunSlotIndex >= inventory.getContainerSize()) {
                return;
            }

            ItemStack gunItem = inventory.getItem(message.gunSlotIndex);
            IGun gun = IGun.getIGunOrNull(gunItem);
            if (gun == null || gun.hasAttachmentLock(gunItem)
                    || message.attachmentType == AttachmentType.NONE) {
                return;
            }

            ItemStack attachmentItem = gun.getAttachment(gunItem, message.attachmentType);
            if (attachmentItem.isEmpty()) {
                return;
            }
            // Remove first, then give the item back. This avoids duplication if the component
            // write fails; a full inventory falls back to dropping the attachment.
            gun.unloadAttachment(gunItem, message.attachmentType);
            if (!gun.getAttachment(gunItem, message.attachmentType).isEmpty()) {
                return;
            }
            if (!inventory.add(attachmentItem)) {
                player.drop(attachmentItem, false);
            }
            AttachmentPropertyManager.postChangeEvent(player, gunItem);
            if (message.attachmentType == AttachmentType.EXTENDED_MAG) {
                gun.dropAllAmmo(player, gunItem);
            }
            player.inventoryMenu.broadcastChanges();
            NetworkHandler.sendToClientPlayer(ServerMessageRefreshRefitScreen.INSTANCE, player);
            GunMod.LOGGER.debug("C2S unload attachment gunSlot={} type={}",
                    message.gunSlotIndex, message.attachmentType);
        });
    }
}