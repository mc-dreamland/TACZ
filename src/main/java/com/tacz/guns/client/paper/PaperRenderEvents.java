package com.tacz.guns.client.paper;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import com.tacz.guns.compat.shader.ShaderCompat;
import com.tacz.guns.util.ItemNbtUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.Item;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import com.tacz.guns.client.renderer.item.BuiltinItemRendererRegistry;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
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
        if (!ShaderCompat.shouldRenderInCurrentHandPhase(stack)) {
            event.setCanceled(true);
            return;
        }
        if (BuiltinItemRendererRegistry.INSTANCE.get(stack.getItem()) instanceof AnimateGeoItemRenderer<?, ?> renderer && renderer.getModel(stack) != null) {
            if (renderer.needReInit(stack)) renderer.tryInit(stack, mc.player, event.getPartialTick());
            ItemDisplayContext context = mc.player.getMainArm() == HumanoidArm.LEFT ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
            renderer.renderFirstPerson(mc.player, stack, context, event.getPoseStack(), event.getSubmitNodeCollector(), event.getPackedLight(), event.getPartialTick());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = GunResolver.renderStack(event.getItemStack());
        if (stack == event.getItemStack() || stack.isEmpty()) return;
        if (ItemNbtUtils.getTag(stack).contains("PaperBoxCapacity")) {
            event.getToolTip().add(Component.literal(ItemNbtUtils.getTag(stack).getIntOr("AmmoCount", 0) + " / " + ItemNbtUtils.getTag(stack).getIntOr("PaperBoxCapacity", 0)).withStyle(ChatFormatting.GOLD));
            event.getToolTip().add(Component.literal("右键打开弹药箱").withStyle(ChatFormatting.GRAY));
            return;
        }
        // The real vanilla item's hover name and all server lore remain authoritative.
        stack.getItem().appendHoverText(stack, Item.TooltipContext.of(event.getEntity() == null ? null : event.getEntity().level()), stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT), event.getToolTip()::add, event.getFlags());
    }
}
