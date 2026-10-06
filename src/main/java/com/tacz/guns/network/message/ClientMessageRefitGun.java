package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.item.IAttachment;
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

public class ClientMessageRefitGun implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientMessageRefitGun> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "client_refit_gun")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientMessageRefitGun> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, message -> message.attachmentSlotIndex,
        ByteBufCodecs.INT, message -> message.gunSlotIndex,
        ByteBufCodecs.fromCodec(AttachmentType.CODEC), message -> message.attachmentType,
        ClientMessageRefitGun::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int attachmentSlotIndex;
    public final int gunSlotIndex;
    private final AttachmentType attachmentType;

    public ClientMessageRefitGun(int attachmentSlotIndex, int gunSlotIndex, AttachmentType attachmentType) {
        this.attachmentSlotIndex = attachmentSlotIndex;
        this.gunSlotIndex = gunSlotIndex;
        this.attachmentType = attachmentType;
    }

    public static void handle(ClientMessageRefitGun message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Inventory inventory = player.getInventory();
            if (!validSlot(inventory, message.attachmentSlotIndex)
                    || !validSlot(inventory, message.gunSlotIndex)) {
                return;
            }

            ItemStack attachmentItem = inventory.getItem(message.attachmentSlotIndex);
            ItemStack gunItem = inventory.getItem(message.gunSlotIndex);
            IGun gun = IGun.getIGunOrNull(gunItem);
            IAttachment attachment = IAttachment.getIAttachmentOrNull(attachmentItem);
            if (gun == null || attachment == null || gun.hasAttachmentLock(gunItem)
                    || !gun.allowAttachment(gunItem, attachmentItem)) {
                return;
            }

            // The server derives the slot type from the actual item; never trust the client
            // supplied enum when deciding which attachment slot to replace.
            AttachmentType realType = attachment.getType(attachmentItem);
            ItemStack oldAttachment = gun.getAttachment(gunItem, realType);
            gun.installAttachment(gunItem, attachmentItem);
            AttachmentPropertyManager.postChangeEvent(player, gunItem);
            inventory.setItem(message.attachmentSlotIndex, oldAttachment);
            if (realType == AttachmentType.EXTENDED_MAG) {
                gun.dropAllAmmo(player, gunItem);
            }
            player.inventoryMenu.broadcastChanges();
            NetworkHandler.sendToClientPlayer(ServerMessageRefreshRefitScreen.INSTANCE, player);
            GunMod.LOGGER.debug("C2S refit gunSlot={} attachmentSlot={} type={}",
                    message.gunSlotIndex, message.attachmentSlotIndex, realType);
        });
    }

    private static boolean validSlot(Inventory inventory, int slot) {
        return slot >= 0 && slot < inventory.getContainerSize();
    }

}