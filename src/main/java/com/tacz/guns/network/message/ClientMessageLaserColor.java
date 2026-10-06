package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.nbt.AttachmentItemDataAccessor;
import net.minecraft.nbt.CompoundTag;
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

import java.util.HashMap;
import java.util.Map;

public class ClientMessageLaserColor implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientMessageLaserColor> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "client_laser_color")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientMessageLaserColor> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.idMapper(AttachmentType::fromId, AttachmentType::ordinal), ByteBufCodecs.INT), message -> message.colorMap,
        ByteBufCodecs.BOOL, message -> message.applyGunColor,
        ByteBufCodecs.INT, message -> message.gunColor,
        ByteBufCodecs.INT, message -> message.gunSlotIndex,
        (colorMap, applyGunColor, gunColor, gunSlotIndex) -> {
            ClientMessageLaserColor message = new ClientMessageLaserColor();
            message.colorMap.putAll(colorMap);
            message.applyGunColor = applyGunColor;
            message.gunColor = gunColor;
            message.gunSlotIndex = gunSlotIndex;
            return message;
        }
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final Map<AttachmentType, Integer> colorMap = new HashMap<>();
    private boolean applyGunColor = false;
    private int gunColor = 0;
    private int gunSlotIndex = -1;

    private ClientMessageLaserColor() {
    }

    /**
     * Collect the colors from the client-side preview stack when the refit screen closes.
     * Installed attachments are serialized inside the gun stack, so the message must copy
     * their custom data values rather than send the temporary ItemStack returned by
     * {@link IGun#getAttachment(ItemStack, AttachmentType)}.
     */
    public ClientMessageLaserColor(@NotNull ItemStack gun, int gunSlotIndex) {
        if (gun.getItem() instanceof IGun iGun) {
            for (AttachmentType type : AttachmentType.values()) {
                ItemStack attachment = iGun.getAttachment(gun, type);
                if (attachment.getItem() instanceof IAttachment iAttachment
                        && iAttachment.hasCustomLaserColor(attachment)) {
                    colorMap.put(type, iAttachment.getLaserColor(attachment));
                }
            }
            if (iGun.hasCustomLaserColor(gun)) {
                this.gunColor = iGun.getLaserColor(gun);
                this.applyGunColor = true;
            }
            this.gunSlotIndex = gunSlotIndex;
        }
    }

    /**
     * Apply the submitted colors to the authoritative server-side gun stack.
     * A payload cannot trust an ItemStack supplied by the client; it only carries the
     * inventory slot and color values, and the server resolves the current gun itself.
     */
    public static void handle(ClientMessageLaserColor message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Inventory inventory = player.getInventory();
            if (message.gunSlotIndex < 0 || message.gunSlotIndex >= inventory.getContainerSize()) {
                return;
            }

            ItemStack gunItem = inventory.getItem(message.gunSlotIndex);
            IGun iGun = IGun.getIGunOrNull(gunItem);
            if (iGun == null) {
                return;
            }

            for (Map.Entry<AttachmentType, Integer> entry : message.colorMap.entrySet()) {
                AttachmentType type = entry.getKey();
                if (type == null || type == AttachmentType.NONE) {
                    continue;
                }
                // getAttachmentTag/setAttachmentTag operate on the installed attachment's
                // components.minecraft:custom_data in the authoritative gun NBT. This also
                // rejects a slot that is not allowed or has no installed attachment.
                CompoundTag tag = iGun.getAttachmentTag(gunItem, type);
                if (tag != null) {
                    AttachmentItemDataAccessor.setLaserColorToTag(tag, entry.getValue());
                    iGun.setAttachmentTag(gunItem, type, tag);
                }
            }
            if (message.applyGunColor) {
                iGun.setLaserColor(gunItem, message.gunColor);
            }

            // ItemStack custom-data mutation does not replace the Inventory slot object.
            // Broadcast the changed stack so the next client inventory update cannot restore
            // the pre-edit color when the gun is fired or the screen is reopened.
            player.inventoryMenu.broadcastChanges();
            GunMod.LOGGER.debug("C2S laser color slot={} attachments={} gunColor={}",
                    message.gunSlotIndex, message.colorMap.size(), message.applyGunColor);
        });
    }

}
