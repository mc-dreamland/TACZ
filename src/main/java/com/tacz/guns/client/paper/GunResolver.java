package com.tacz.guns.client.paper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.*;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.init.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps wire/inventory stacks vanilla; native TACZ stacks exist only as client views. */
@Mod.EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GunResolver {
    private static final Map<String, ItemStack> RENDER_STACKS = new LinkedHashMap<>(128, .75f, true);
    private static final Map<String, StateView> STATES = new LinkedHashMap<>();
    private static final Map<Class<?>, Object> ACCESSORS = new LinkedHashMap<>();
    private static final Map<ItemStack, Metadata> METADATA = new WeakHashMap<>();

    private GunResolver() { }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        ItemBehavior.setClientResolver(GunResolver::behavior);
    }

    public record ResolvedGun(ItemStack realStack, ItemStack renderStack, IGun data, String instance, boolean paper) { }
    private record Metadata(Item material, int cmd, String wireData, JsonObject parsed) { }
    private record StateView(JsonObject data, int owner, String baseline) { }

    @Nullable
    private static JsonObject metadata(ItemStack stack) {
        if (!PaperClientBridge.active() || stack == null || stack.isEmpty() || !stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        String wireData = tag.getCompound("PublicBukkitValues").getString("tacz:bridge");
        if (wireData.isEmpty()) return null;
        int cmd = tag.getInt("CustomModelData");
        Metadata cached = METADATA.get(stack);
        if (cached != null && cached.material() == stack.getItem() && cached.cmd() == cmd && cached.wireData().equals(wireData)) return cached.parsed();
        JsonObject parsed = PaperClientBridge.itemData(stack);
        METADATA.put(stack, new Metadata(stack.getItem(), cmd, wireData, parsed));
        return parsed;
    }

    @Nullable
    public static ResolvedGun resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (stack.getItem() instanceof IGun nativeGun) return new ResolvedGun(stack, stack, nativeGun, "", false);
        JsonObject item = metadata(stack);
        if (item == null || !"gun".equals(string(item, "kind", ""))) return null;
        ItemStack render = renderStack(stack);
        if (!(render.getItem() instanceof IGun)) return null;
        return new ResolvedGun(stack, render, (IGun) accessor(IGun.class), string(item, "instance", ""), true);
    }

    public static boolean isPaperGun(ItemStack stack) {
        ResolvedGun gun = resolve(stack);
        return gun != null && gun.paper();
    }

    public static Object behavior(ItemStack stack) {
        if (stack.isEmpty()) return stack.getItem();
        if (stack.getItem() instanceof IGun || stack.getItem() instanceof IAmmo || stack.getItem() instanceof IAttachment
                || stack.getItem() instanceof IAmmoBox) return stack.getItem();
        JsonObject data = metadata(stack);
        if (data == null) return stack.getItem();
        return switch (string(data, "kind", "")) {
            case "gun" -> accessor(IGun.class);
            case "ammo" -> accessor(IAmmo.class);
            case "attachment" -> accessor(IAttachment.class);
            case "box", "ammo_box" -> accessor(IAmmoBox.class);
            default -> stack.getItem();
        };
    }

    /* IGun is a data API, not an Item. All of its stack arguments are isolated views. */
    private static Object accessor(Class<?> type) {
        return ACCESSORS.computeIfAbsent(type, key -> Proxy.newProxyInstance(key.getClassLoader(), new Class<?>[]{key}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Paper " + key.getSimpleName() + " data view";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (method.getReturnType() == Void.TYPE) return null;
            Object[] converted = args == null ? new Object[0] : args.clone();
            Item target = null;
            for (int i = 0; i < converted.length; i++) {
                if (converted[i] instanceof ItemStack original) {
                    // Even a getter can expose a mutable CompoundTag. Never expose either original.
                    ItemStack view = renderStack(original).copy();
                    converted[i] = view;
                    if (target == null && key.isInstance(view.getItem())) target = view.getItem();
                }
            }
            if (target == null) {
                if (key == IGun.class) target = ModItems.MODERN_KINETIC_GUN.get();
                else if (key == IAmmo.class) target = ModItems.AMMO.get();
                else if (key == IAttachment.class) target = ModItems.ATTACHMENT.get();
                else if (key == IAmmoBox.class) target = ModItems.AMMO_BOX.get();
            }
            if (target == null) throw new IllegalStateException("Missing Paper item view for " + method.getName());
            try {
                return method.invoke(target, converted);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }));
    }

    public static ItemStack renderStack(ItemStack real) {
        return renderStack(real, true);
    }

    static ItemStack authoritativeStack(ItemStack real) { return renderStack(real, false); }

    private static ItemStack renderStack(ItemStack real, boolean prediction) {
        if (real == null || real.isEmpty()) return ItemStack.EMPTY;
        JsonObject item = metadata(real);
        if (item == null) return real;
        String kind = string(item, "kind", "");
        Item nativeItem = switch (kind) {
            case "gun" -> ModItems.MODERN_KINETIC_GUN.get();
            case "ammo" -> ModItems.AMMO.get();
            case "attachment" -> ModItems.ATTACHMENT.get();
            case "box", "ammo_box" -> ModItems.AMMO_BOX.get();
            default -> null;
        };
        if (nativeItem == null) return real;
        String instance = string(item, "instance", "");
        String id = string(item, "id", "");
        String key = kind + ":" + id + ":" + instance;
        JsonObject current = item.deepCopy();
        StateView snapshot = STATES.get(instance);
        if (snapshot != null) {
            Minecraft mc = Minecraft.getInstance();
            JsonObject equipped = mc.level != null && mc.level.getEntity(snapshot.owner()) instanceof LivingEntity owner
                    ? metadata(owner.getMainHandItem()) : null;
            // Inventory updates supersede the transient snapshot. Never overlay an old held-gun
            // snapshot on that gun after it has moved to another slot or changed on the server.
            boolean currentSnapshot = equipped != null && instance.equals(string(equipped, "instance", ""))
                    && snapshot.baseline().equals(real.getTag().getCompound("PublicBukkitValues").getString("tacz:bridge"));
            if (currentSnapshot && id.equals(string(snapshot.data(), "id", ""))) {
                for (var entry : snapshot.data().entrySet()) current.add(entry.getKey(), entry.getValue());
            } else {
                STATES.remove(instance);
            }
        }
        ItemStack render = RENDER_STACKS.computeIfAbsent(key, ignored -> new ItemStack(nativeItem));
        CompoundTag tag = new CompoundTag();
        if (real.hasCustomHoverName()) tag.put("display", real.getTag().getCompound("display").copy());
        if (kind.equals("gun")) {
            tag.putString("GunId", id);
            tag.putString("GunDisplayId", string(current, "displayId", "tacz:default"));
            tag.putInt("GunCurrentAmmoCount", Math.max(0, integer(current, "ammo", 0)));
            tag.putBoolean("HasBulletInBarrel", bool(current, "chamber", false));
            String fireMode = string(current, "fireMode", "SEMI").toUpperCase(Locale.ROOT);
            try { FireMode.valueOf(fireMode); } catch (IllegalArgumentException ignored) { fireMode = "SEMI"; }
            tag.putString("GunFireMode", fireMode);
            tag.putFloat("HeatAmount", number(current, "heat", 0));
            tag.putBoolean("OverHeated", bool(current, "overheated", false));
            tag.putString("PaperInstance", instance);
            copyLaserColor(current, "laserColor", tag);
            if (current.has("attachments") && current.get("attachments").isJsonObject()) {
                for (var entry : current.getAsJsonObject("attachments").entrySet()) {
                    try {
                        AttachmentType type = AttachmentType.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                        String attachment = entry.getValue().getAsString();
                        if (ResourceLocation.tryParse(attachment) == null) continue;
                        CompoundTag attachmentTag = new CompoundTag();
                        attachmentTag.putString("AttachmentId", attachment);
                        attachmentTag.putInt("ZoomNumber", integer(current, "zoom", 0));
                        if (current.has("attachmentColors") && current.get("attachmentColors").isJsonObject()) {
                            copyLaserColor(current.getAsJsonObject("attachmentColors"), type.name().toLowerCase(Locale.ROOT), attachmentTag);
                        }
                        ItemStack attachmentStack = new ItemStack(ModItems.ATTACHMENT.get());
                        attachmentStack.setTag(attachmentTag);
                        tag.put("Attachment" + type.name(), attachmentStack.save(new CompoundTag()));
                    } catch (RuntimeException ignored) { }
                }
            }
            if (Minecraft.getInstance().screen instanceof GunRefitScreen screen) screen.applyPaperPreview(instance, tag);
            if (prediction) PaperShotPresentation.overlay(instance, tag);
        } else if (kind.equals("attachment")) {
            tag.putString("AttachmentId", id);
            copyLaserColor(current, "laserColor", tag);
        } else if (kind.equals("ammo")) {
            tag.putString("AmmoId", id);
        } else {
            String ammoId = string(current, "boxAmmoId", "tacz:empty");
            tag.putString("AmmoId", ammoId.isEmpty() ? DefaultAssets.EMPTY_AMMO_ID.toString() : ammoId);
            tag.putInt("AmmoCount", Math.max(0, integer(current, "boxAmmo", 0)));
            tag.putInt("Level", Math.max(0, integer(current, "boxLevel", 0)));
            tag.putInt("PaperBoxCapacity", Math.max(0, integer(current, "boxCapacity", 0)));
        }
        render.setCount(real.getCount());
        if (!tag.equals(render.getTag())) render.setTag(tag);
        if (RENDER_STACKS.size() > 512) RENDER_STACKS.remove(RENDER_STACKS.keySet().iterator().next());
        return render;
    }

    private static void copyLaserColor(JsonObject data, String key, CompoundTag tag) {
        int rgb = integer(data, key, -1);
        if (rgb >= 0 && rgb <= 0xFFFFFF) tag.putInt("LaserColor", rgb);
    }

    public static void onState(JsonObject state) {
        String instance = string(state, "instance", "");
        Minecraft mc = Minecraft.getInstance();
        int entityId = integer(state, "entity", -1);
        if (!instance.isEmpty() && mc.level != null && mc.level.getEntity(entityId) instanceof LivingEntity owner) {
            ItemStack held = owner.getMainHandItem();
            JsonObject item = metadata(held);
            if (item == null || !instance.equals(string(item, "instance", ""))) return;
            STATES.put(instance, new StateView(state.deepCopy(), entityId, held.getTag().getCompound("PublicBukkitValues").getString("tacz:bridge")));
            if (STATES.size() > 512) STATES.remove(STATES.keySet().iterator().next());
        }
    }

    public static boolean sameIdentity(ItemStack left, ItemStack right) {
        JsonObject a = metadata(left);
        JsonObject b = metadata(right);
        if (a == null || b == null) return a == null && b == null && ItemStack.matches(left, right);
        return string(a, "instance", "").equals(string(b, "instance", ""))
                && string(a, "id", "").equals(string(b, "id", ""));
    }

    public static void reset() { RENDER_STACKS.clear(); STATES.clear(); METADATA.clear(); }

    public static String string(JsonObject object, String key, String fallback) {
        try { JsonElement value = object.get(key); return value == null || value.isJsonNull() ? fallback : value.getAsString(); }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static int integer(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; } catch (RuntimeException ignored) { return fallback; }
    }
    public static float number(JsonObject object, String key, float fallback) {
        try { float value = object.has(key) ? object.get(key).getAsFloat() : fallback; return Float.isFinite(value) ? value : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; } catch (RuntimeException ignored) { return fallback; }
    }
}
