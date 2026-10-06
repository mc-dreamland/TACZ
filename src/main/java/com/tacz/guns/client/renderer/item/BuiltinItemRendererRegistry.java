package com.tacz.guns.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 26.1.2 item-model bridge registry. Replaces Fabric BuiltinItemRendererRegistry.
 * TaczDynamicItemModel looks up the renderer for tacz:dynamic_item.
 */
public final class BuiltinItemRendererRegistry {
    public static final BuiltinItemRendererRegistry INSTANCE = new BuiltinItemRendererRegistry();

    public interface DynamicItemRenderer {
        void render(ItemStack stack, ItemDisplayContext mode, PoseStack poseStack,
                    SubmitNodeCollector collector, int light, int overlay);
    }

    private final Map<Item, DynamicItemRenderer> renderers = new IdentityHashMap<>();

    private BuiltinItemRendererRegistry() {
    }

    public void register(Item item, DynamicItemRenderer renderer) {
        if (item != null && renderer != null) {
            renderers.put(item, renderer);
        }
    }

    @Nullable
    public DynamicItemRenderer get(Item item) {
        return renderers.get(item);
    }
}
