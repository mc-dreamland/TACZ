package com.tacz.guns.client.paper;

import com.google.gson.JsonObject;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.bridge.PresentationAmmo;
import com.tacz.guns.bridge.ShotPresentationTracker;
import com.tacz.guns.client.animation.statemachine.GunAnimationConstant;
import com.tacz.guns.client.renderer.item.GunItemRendererWrapper;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.resource.modifier.custom.SilenceModifier;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.sound.SoundManager;
import com.tacz.guns.util.InputExtraCheck;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.tacz.guns.client.renderer.item.BuiltinItemRendererRegistry;
import net.neoforged.neoforge.common.NeoForge;
import com.tacz.guns.api.LogicalSide;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.tacz.guns.client.paper.GunResolver.*;

/** Predicts only local presentation. Damage, ammunition and other players remain authoritative. */
public final class PaperShotPresentation {
    private static final ShotPresentationTracker TRACKER = new ShotPresentationTracker();
    private static final Map<Long, Context> CONTEXTS = new LinkedHashMap<>();

    private record Context(ClientLevel level, ClientPacketListener connection, LocalPlayer player, int slot,
                           String instance, ResourceLocation id, FireMode mode, GunData data, boolean silenced, boolean consumesAmmo) {
        boolean valid() {
            Minecraft mc = Minecraft.getInstance();
            if (!PaperClientBridge.active() || mc.level != level || mc.getConnection() != connection || mc.player != player
                    || !player.isAlive() || player.isSpectator() || player.getInventory().getSelectedSlot() != slot || !InputExtraCheck.isInGame()) return false;
            var gun = resolve(player.getMainHandItem());
            IGunOperator operator = IGunOperator.fromLivingEntity(player);
            if (operator.getSynReloadState().getStateType().isReloading() || operator.getSynIsBolting()) return false;
            return gun != null && gun.paper() && instance.equals(gun.instance()) && id.equals(gun.data().getGunId(gun.realStack()))
                    && mode == gun.data().getFireMode(gun.realStack());
        }
    }

    private PaperShotPresentation() { }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private static long sequence(JsonObject value, String key) {
        try { return value.has(key) ? value.get(key).getAsLong() : -1; } catch (RuntimeException ignored) { return -1; }
    }

    public static void onState(JsonObject state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || integer(state, "entity", -1) != mc.player.getId()) return;
        TRACKER.snapshot(string(state, "instance", ""), sequence(state, "lastFiredActionSeq"), integer(state, "lastFiredShotIndex", -1));
    }

    public static void result(JsonObject event) {
        TRACKER.result(sequence(event, "actionSeq"), string(event, "instance", ""), string(event, "status", ""), integer(event, "fired", 0));
    }

    public static boolean shouldPlayConfirmed(JsonObject event) {
        return TRACKER.confirm(sequence(event, "actionSeq"), string(event, "instance", ""), integer(event, "shotIndex", 0), bool(event, "visual", true));
    }

    public static boolean hasUnconfirmedShots(String instance) { return !TRACKER.outstanding(instance, true).isEmpty(); }

    /** Returns the finite clip budget without ever decrementing the vanilla carrier. */
    public static int available(GunResolver.ResolvedGun gun, GunData data) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return 0;
        ItemStack authoritative = GunResolver.authoritativeStack(gun.realStack()).copy();
        IGun accessor = (IGun) authoritative.getItem();
        boolean inventory = accessor.useInventoryAmmo(authoritative);
        boolean chamber = accessor.hasBulletInBarrel(authoritative);
        int ammo = accessor.getCurrentAmmoCount(authoritative);
        if (data.getBolt() == Bolt.MANUAL_ACTION && !chamber) return 0;
        if (!inventory && ammo <= 0 && (!chamber || data.getBolt() == Bolt.OPEN_BOLT)) return 0;
        if (player.getAbilities().instabuild || inventory) return Integer.MAX_VALUE;
        int total = ammo + (data.getBolt() != Bolt.OPEN_BOLT && chamber ? 1 : 0);
        return Math.max(0, total - TRACKER.reservedAmmo(gun.instance()));
    }

    public static void sent(long actionSeq, GunResolver.ResolvedGun gun, GunData data, FireMode mode) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        int physical = mode == FireMode.BURST && data.getBurstData() != null ? Math.max(1, data.getBurstData().getCount()) : 1;
        boolean doubleBarrel = mode == FireMode.BURST && ResourceLocation.parse("tacz:db_short_gun_logic").equals(data.getScript());
        if (doubleBarrel) physical = 2;
        physical = Math.min(16, Math.min(physical, available(gun, data)));
        ItemStack authoritative = GunResolver.authoritativeStack(gun.realStack()).copy();
        IGun accessor = (IGun) authoritative.getItem();
        boolean predicted = !accessor.useInventoryAmmo(authoritative);
        boolean consumes = !player.getAbilities().instabuild;
        boolean automaticManualFeed = mode == FireMode.BURST && ResourceLocation.parse("tacz:spas_12_gun_logic").equals(data.getScript());
        if (consumes && data.getBolt() == Bolt.MANUAL_ACTION && !automaticManualFeed) physical = Math.min(physical, 1);
        if (data.hasHeatData() && data.getHeatData().getHeatPerShot() > 0) {
            ItemStack predictedView = GunResolver.renderStack(gun.realStack()).copy();
            float heat = ((IGun) predictedView.getItem()).getHeatAmount(predictedView);
            physical = Math.min(physical, Math.max(0, (int) Math.ceil((data.getHeatData().getHeatMax() - heat) / data.getHeatData().getHeatPerShot())));
        }
        if (physical <= 0) return;
        boolean silenced = false;
        var cache = IGunOperator.fromLivingEntity(player).getCacheProperty();
        if (cache != null) { Pair<Integer, Boolean> silence = cache.getCache(SilenceModifier.ID); silenced = silence != null && silence.right(); }
        Context context = new Context(mc.level, mc.getConnection(), player, player.getInventory().getSelectedSlot(),
                gun.instance(), accessor.getGunId(authoritative), mode, data, silenced, consumes);
        long interval = mode == FireMode.BURST ? Math.max(1, data.getBurstShootInterval()) : 0;
        TRACKER.begin(actionSeq, gun.instance(), physical, doubleBarrel ? 1 : physical, interval, now(), predicted, consumes);
        CONTEXTS.keySet().removeIf(sequence -> !TRACKER.contains(sequence));
        CONTEXTS.put(actionSeq, context);
        pump();
    }

    public static void pump() {
        Minecraft mc = Minecraft.getInstance();
        if (!PaperClientBridge.active() || mc.player == null || !mc.player.isAlive() || !InputExtraCheck.isInGame()) {
            cancelScene();
            return;
        }
        var shot = TRACKER.poll(now());
        if (shot == null) return;
        Context context = CONTEXTS.get(shot.actionSeq());
        if (context == null || !context.valid()) { cancelScene(); return; }
        ItemStack render = GunResolver.renderStack(context.player().getMainHandItem()).copy();
        present(context.player(), render, context.data(), context.silenced(), shot.shotIndex() == 0);
    }

    public static void present(LocalPlayer player, ItemStack render, GunData data, boolean silenced, boolean first) {
        var holder = IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder();
        if (first) {
            holder.clientLastShootTimestamp = holder.clientShootTimestamp;
            holder.clientShootTimestamp = System.currentTimeMillis();
        }
        // A cancelled visual is still recorded by the tracker and will not return through its ACK.
        if (NeoForge.EVENT_BUS.post(new GunFireEvent(player, render, LogicalSide.CLIENT)).isCanceled()) return;
        TimelessAPI.getGunDisplay(render).ifPresent(display -> {
            SoundPlayManager.stopPlayGunSound(display, SoundManager.INSPECT_SOUND);
            if (BuiltinItemRendererRegistry.INSTANCE.get(render.getItem()) instanceof GunItemRendererWrapper renderer) {
                Minecraft mc = Minecraft.getInstance();
                if (renderer.needReInit(render)) renderer.tryInit(render, player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
                var machine = renderer.getStateMachine(render);
                if (machine != null && machine.getContext() != null) renderer.updateContext(machine.getContext(), render, player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
                renderer.triggerAnimation(render, GunAnimationConstant.INPUT_SHOOT);
            }
            if (silenced) SoundPlayManager.playSilenceSound(player, display, data);
            else SoundPlayManager.playShootSound(player, display, data);
        });
    }

    /** Applied to a freshly built TACZ view; the real PDC/NBT and synced state stay untouched. */
    public static void overlay(String instance, CompoundTag tag) {
        PresentationAmmo ammo = new PresentationAmmo(tag.getIntOr("GunCurrentAmmoCount", 0), tag.getBooleanOr("HasBulletInBarrel", false));
        float heat = tag.getFloatOr("HeatAmount", 0);
        boolean overheated = tag.getBooleanOr("OverHeated", false);
        for (var shot : TRACKER.outstanding(instance, false)) {
            Context context = CONTEXTS.get(shot.actionSeq());
            if (context == null) continue;
            GunData data = context.data();
            boolean spas = context.mode() == FireMode.BURST && ResourceLocation.parse("tacz:spas_12_gun_logic").equals(data.getScript());
            if (context.consumesAmmo()) ammo = ammo.consume(data.getBolt().name().toLowerCase(Locale.ROOT), spas);
            if (data.hasHeatData()) {
                heat = Math.min(data.getHeatData().getHeatMax(), heat + Math.max(0, data.getHeatData().getHeatPerShot()));
                overheated |= heat >= data.getHeatData().getHeatMax();
            }
        }
        tag.putInt("GunCurrentAmmoCount", ammo.ammo());
        tag.putBoolean("HasBulletInBarrel", ammo.chamber());
        tag.putFloat("HeatAmount", heat);
        tag.putBoolean("OverHeated", overheated);
    }

    public static void cancelScene() { TRACKER.cancelFuture(); }
    public static void reset() { TRACKER.reset(); CONTEXTS.clear(); }
}
