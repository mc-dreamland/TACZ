package com.tacz.guns.client.paper;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import com.tacz.guns.compat.oculus.OculusCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public final class PaperRenderEvents {
    private PaperRenderEvents() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void renderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !GunResolver.isPaperGun(mc.player.getMainHandItem())) return;
        if (event.getHand() == InteractionHand.OFF_HAND) {
            event.setCanceled(true);
            return;
        }
        ItemStack stack = GunResolver.renderStack(mc.player.getMainHandItem());
        if (IClientItemExtensions.of(stack).getCustomRenderer() instanceof AnimateGeoItemRenderer<?, ?> renderer && renderer.getModel(stack) != null) {
            if (renderer.needReInit(stack)) renderer.tryInit(stack, mc.player, event.getPartialTick());
            OculusCompat.endBatch(mc.renderBuffers().bufferSource());
            ItemDisplayContext context = mc.player.getMainArm() == HumanoidArm.LEFT ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
            renderer.renderFirstPerson(mc.player, stack, context, event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), event.getPartialTick());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = GunResolver.renderStack(event.getItemStack());
        if (stack == event.getItemStack() || stack.isEmpty()) return;
        if (stack.hasTag() && stack.getTag().contains("PaperBoxCapacity")) {
            event.getToolTip().add(Component.literal(stack.getTag().getInt("AmmoCount") + " / " + stack.getTag().getInt("PaperBoxCapacity")).withStyle(ChatFormatting.GOLD));
            event.getToolTip().add(Component.literal("右键打开弹药箱").withStyle(ChatFormatting.GRAY));
            return;
        }
        // The real vanilla item's hover name and all server lore remain authoritative.
        stack.getItem().appendHoverText(stack, event.getEntity() == null ? null : event.getEntity().level(), event.getToolTip(), event.getFlags());
    }
}
