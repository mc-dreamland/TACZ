package com.tacz.guns.client.paper;

import com.google.gson.JsonObject;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.event.common.GunDrawEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunFireSelectEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.bridge.ClientShotSchedule;
import com.tacz.guns.client.animation.statemachine.GunAnimationConstant;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.config.common.GunConfig;
import com.tacz.guns.sound.SoundManager;
import com.tacz.guns.entity.sync.ModSyncedEntityData;
import com.tacz.guns.entity.sync.core.SyncedEntityData;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.ChargeType;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import com.tacz.guns.client.renderer.item.BuiltinItemRendererRegistry;
import net.neoforged.neoforge.common.NeoForge;
import com.tacz.guns.api.LogicalSide;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.tacz.guns.client.paper.GunResolver.*;

/** Client prediction is limited to presentation; Paper acknowledges every authoritative action. */
public final class PaperClientGameplay {
    private static final Map<Integer, ReceivedState> STATES = new HashMap<>();
    private static final Map<String, Long> LAST_EVENTS = new HashMap<>();
    private static final Map<Integer, ItemStack> LAST_GUNS = new HashMap<>();
    private static int selected = -1;
    private static ItemStack previous = ItemStack.EMPTY;
    private static final ClientShotSchedule SHOT_SCHEDULE = new ClientShotSchedule();
    private static PendingShot pendingShot;
    private static long serverShotInterval;
    private static long lastConfirmedShotAt;
    private static FireMode lastConfirmedFireMode;
    private static String serverShotInstance = "";
    private static long nextReload;
    private static long nextBolt;
    private static long nextCancel;
    private static boolean crawling;
    private static PendingRefitOpen pendingRefitOpen;

    private PaperClientGameplay() { }

    private record ReceivedState(JsonObject value, long receivedAt) { }
    private record PendingShot(ClientLevel level, ClientPacketListener connection, LocalPlayer player, int slot, String instance,
                               ResourceLocation gunId, FireMode fireMode, float charge) { }

    private record PendingRefitOpen(String requestId, int slot, String instance, ClientLevel level,
                                    GunRefitScreen screen, long expiresAt) {
        boolean valid(Minecraft mc) {
            var held = mc.player == null ? null : resolve(mc.player.getMainHandItem());
            return mc.level == level && mc.screen == screen && held != null && held.paper()
                    && mc.player.getInventory().getSelectedSlot() == slot && instance.equals(held.instance())
                    && System.currentTimeMillis() < expiresAt;
        }
    }

    public static void onState(JsonObject state) {
        int entityId = integer(state, "entity", -1);
        if (entityId < 0) return;
        ReceivedState old = STATES.get(entityId);
        long sequence = sequence(state);
        if (old != null && sequence >= 0 && sequence < sequence(old.value())) return;
        PaperShotPresentation.onState(state);
        long now = System.currentTimeMillis();
        STATES.put(entityId, new ReceivedState(state.deepCopy(), now));
        GunResolver.onState(state);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(entityId) instanceof LivingEntity entity) {
            var equipped = resolve(entity.getMainHandItem());
            if (equipped == null || !equipped.paper()) {
                syncEntity(entity, new JsonObject(), 0);
                LAST_GUNS.remove(entityId);
                return;
            }
            if (!equipped.instance().equals(string(state, "instance", ""))) return;
            syncEntity(entity, state, 0);
            if (entity == mc.player) {
                serverShotInterval = Math.max(0, integer(state, "shotInterval", 0));
                serverShotInstance = equipped.instance();
                // A snapshot describes the server at send time. Re-anchoring the local deadline
                // to its arrival adds network delay to every shot; only the server validates it.
                LocalPlayerDataHolder data = IClientPlayerGunOperator.fromLocalPlayer(mc.player).getDataHolder();
                data.clientIsAiming = bool(state, "aiming", false);
                data.isBolting = bool(state, "bolting", false);
                data.clientStateLock = false;
                crawling = bool(state, "crawling", crawling);
                AttachmentPropertyManager.postChangeEvent(entity, entity.getMainHandItem());
            }
        }
    }

    private static long sequence(JsonObject value) {
        try { return value.has("seq") ? value.get("seq").getAsLong() : -1; }
        catch (RuntimeException ignored) { return -1; }
    }

    private static void syncEntity(LivingEntity entity, JsonObject state, long elapsed) {
        SyncedEntityData synced = SyncedEntityData.instance();
        synced.set(entity, ModSyncedEntityData.SHOOT_COOL_DOWN_KEY, remaining(state, "shootRemaining", elapsed));
        synced.set(entity, ModSyncedEntityData.DRAW_COOL_DOWN_KEY, remaining(state, "drawRemaining", elapsed));
        synced.set(entity, ModSyncedEntityData.IS_BOLTING_KEY, bool(state, "bolting", false));
        synced.set(entity, ModSyncedEntityData.IS_AIMING_KEY, bool(state, "aiming", false));
        synced.set(entity, ModSyncedEntityData.AIMING_PROGRESS_KEY, Mth.clamp(number(state, "aimingProgress", bool(state, "aiming", false) ? 1f : 0f), 0, 1));
        synced.set(entity, ModSyncedEntityData.SPRINT_TIME_KEY, Math.max(0f, number(state, "sprinting", 0) - elapsed / 1000f));
        ReloadState reload = new ReloadState();
        String reloadName = string(state, "reloadState", "NOT_RELOADING").toUpperCase(Locale.ROOT);
        try { reload.setStateType(ReloadState.StateType.valueOf(reloadName)); }
        catch (IllegalArgumentException ignored) { }
        reload.setCountDown(remaining(state, "reloadRemaining", elapsed));
        // Keep reloading until the server confirms completion, even if its countdown reaches zero.
        synced.set(entity, ModSyncedEntityData.RELOAD_STATE_KEY, reload);
    }

    private static long remaining(JsonObject state, String field, long elapsed) {
        return Math.max(0L, (long) integer(state, field, 0) - elapsed);
    }

    public static void onEvent(JsonObject message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int entityId = integer(message, "entity", -1);
        long seq = sequence(message);
        String op = string(message, "op", "");
        String eventKey = entityId + ":" + string(message, "instance", "") + ":" + op;
        if (seq >= 0 && seq <= LAST_EVENTS.getOrDefault(eventKey, -1L)) return;
        if (seq >= 0) {
            LAST_EVENTS.put(eventKey, seq);
            if (LAST_EVENTS.size() > 4096) LAST_EVENTS.clear();
        }
        if (op.equals("shot_result")) { PaperShotPresentation.result(message); return; }
        if (!(mc.level.getEntity(entityId) instanceof LivingEntity entity)) return;
        var gun = resolve(entity.getMainHandItem());
        if (gun == null || !gun.paper()) return;
        if (!string(message, "instance", gun.instance()).equals(gun.instance())) return;
        ItemStack render = gun.renderStack();
        if (op.equals("shoot") && entity == mc.player) {
            lastConfirmedShotAt = shotClock();
            try { lastConfirmedFireMode = FireMode.valueOf(string(message, "fireMode", "semi").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { lastConfirmedFireMode = null; }
            if (!PaperShotPresentation.shouldPlayConfirmed(message)) return;
        }
        if (op.equals("shoot") && !bool(message, "visual", true)) return;
        TimelessAPI.getGunDisplay(render).ifPresent(display -> {
            IGun data = (IGun) render.getItem();
            boolean empty = !data.hasBulletInBarrel(render) && data.getCurrentAmmoCount(render) == 0;
            boolean local = entity == mc.player;
            if (local && BuiltinItemRendererRegistry.INSTANCE.get(render.getItem()) instanceof AnimateGeoItemRenderer<?, ?> renderer) {
                if (renderer.needReInit(render)) renderer.tryInit(render, mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
                String input = switch (op) {
                    case "shoot" -> null; // Local shot presentation handles its own context and event ordering.
                    case "reload" -> GunAnimationConstant.INPUT_RELOAD;
                    case "cancel_reload" -> GunAnimationConstant.INPUT_CANCEL_RELOAD;
                    case "bolt" -> GunAnimationConstant.INPUT_BOLT;
                    case "draw" -> GunAnimationConstant.INPUT_DRAW;
                    case "fire_select" -> GunAnimationConstant.INPUT_FIRE_SELECT;
                    case "melee" -> GunAnimationConstant.INPUT_BAYONET_PUSH;
                    case "inspect" -> GunAnimationConstant.INPUT_INSPECT;
                    default -> null;
                };
                if (input != null) renderer.triggerAnimation(render, input);
            }
            switch (op) {
                case "shoot" -> {
                    boolean silenced = bool(message, "silenced", false);
                    if (local) {
                        TimelessAPI.getCommonGunIndex(data.getGunId(render)).ifPresent(index -> {
                            PaperShotPresentation.present(mc.player, render.copy(), index.getGunData(), silenced, integer(message, "shotIndex", 0) == 0);
                        });
                    } else {
                        int distance = Math.max(1, integer(message, "soundDistance", silenced ? GunConfig.DEFAULT_GUN_SILENCE_SOUND_DISTANCE.get() : GunConfig.DEFAULT_GUN_FIRE_SOUND_DISTANCE.get()));
                        SoundPlayManager.playRemoteShootSound(entity, display, silenced, distance);
                        if (integer(message, "shotIndex", 0) == 0) NeoForge.EVENT_BUS.post(new GunShootEvent(entity, render, LogicalSide.CLIENT));
                        NeoForge.EVENT_BUS.post(new GunFireEvent(entity, render, LogicalSide.CLIENT));
                    }
                }
                case "reload" -> {
                    NeoForge.EVENT_BUS.post(new GunReloadEvent(entity, render, LogicalSide.CLIENT));
                    if (local) SoundPlayManager.playReloadSound(entity, display, empty);
                    else SoundPlayManager.playClientSound(entity, display.getSounds(empty ? SoundManager.RELOAD_EMPTY_SOUND : SoundManager.RELOAD_TACTICAL_SOUND), 1, 1, GunConfig.DEFAULT_GUN_OTHER_SOUND_DISTANCE.get());
                }
                case "bolt" -> {
                    if (local) SoundPlayManager.playBoltSound(entity, display);
                    else SoundPlayManager.playClientSound(entity, display.getSounds(SoundManager.BOLT_SOUND), 1, 1, GunConfig.DEFAULT_GUN_OTHER_SOUND_DISTANCE.get());
                }
                case "draw" -> {
                    NeoForge.EVENT_BUS.post(new GunDrawEvent(entity, LAST_GUNS.getOrDefault(entityId, ItemStack.EMPTY), render, LogicalSide.CLIENT));
                    if (local) SoundPlayManager.playDrawSound(entity, display);
                    else SoundPlayManager.playClientSound(entity, display.getSounds(SoundManager.DRAW_SOUND), 1, 1, GunConfig.DEFAULT_GUN_OTHER_SOUND_DISTANCE.get());
                }
                case "fire_select" -> {
                    NeoForge.EVENT_BUS.post(new GunFireSelectEvent(entity, render, LogicalSide.CLIENT));
                    SoundPlayManager.playFireSelectSound(entity, display);
                }
                case "inspect" -> { if (local) SoundPlayManager.playInspectSound(entity, display, empty); }
                case "melee" -> {
                    NeoForge.EVENT_BUS.post(new GunMeleeEvent(entity, render, LogicalSide.CLIENT));
                    SoundPlayManager.playMeleePushSound(entity, display);
                }
                case "cancel_reload" -> { if (local) SoundPlayManager.stopPlayGunSound(); }
                default -> { }
            }
            LAST_GUNS.put(entityId, render.copy());
        });
    }

    public static void onMenu(JsonObject menu) {
        Minecraft mc = Minecraft.getInstance();
        if (!string(menu, "menu", "").equals("refit")) {
            cancelScheduledShot();
            PaperShotPresentation.cancelScene();
            pendingRefitOpen = null;
            mc.setScreen(new PaperMenuScreen(menu));
            return;
        }
        // A transaction may complete after Z/Escape, a hotbar change, or a different menu.
        // Only initial opens may create a screen; refreshed tokens belong to the existing one.
        if (bool(menu, "refresh", false)) {
            if (pendingRefitOpen == null && mc.screen instanceof GunRefitScreen screen && screen.acceptsPaperMenu(menu)) {
                screen.updatePaperMenu(menu);
            } else {
                discardRefitMenu(menu);
            }
            return;
        }
        String requestId = string(menu, "requestId", "");
        if (!requestId.isEmpty()) {
            PendingRefitOpen pending = pendingRefitOpen;
            if (pending == null || !pending.requestId().equals(requestId)) {
                discardRefitMenu(menu);
                return;
            }
            pendingRefitOpen = null;
            if (!pending.valid(mc)) {
                discardRefitMenu(menu);
                return;
            }
        }
        JsonObject state = menu.has("state") && menu.get("state").isJsonObject()
                ? menu.getAsJsonObject("state") : new JsonObject();
        var held = mc.player == null ? null : resolve(mc.player.getMainHandItem());
        if (held == null || !held.paper() || mc.player.getInventory().getSelectedSlot() != integer(menu, "slot", -1)
                || !held.instance().equals(string(state, "instance", ""))) {
            discardRefitMenu(menu);
            return;
        }
        if (mc.screen instanceof GunRefitScreen screen && screen.acceptsPaperMenu(menu)) {
            screen.updatePaperMenu(menu);
        } else {
            cancelScheduledShot();
            PaperShotPresentation.cancelScene();
            mc.setScreen(new GunRefitScreen(menu));
        }
    }

    private static void discardRefitMenu(JsonObject menu) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "close_refit");
        request.addProperty("token", string(menu, "token", ""));
        PaperClientBridge.sendMenuAction(request);
    }

    public static void onError(JsonObject error) {
        Minecraft mc = Minecraft.getInstance();
        PendingRefitOpen pending = pendingRefitOpen;
        if (pending != null) {
            if (pending.requestId().equals(string(error, "requestId", ""))) {
                pendingRefitOpen = null;
                // A failed token refresh must not turn into an endless open/error retry cycle.
                if (pending.screen() != null && mc.screen == pending.screen()) mc.setScreen(null);
            }
        } else if (mc.screen instanceof GunRefitScreen screen && screen.acceptsPaperError(error)) {
            screen.clearPaperPending();
        } else if (mc.screen instanceof PaperMenuScreen screen) {
            screen.clearPending();
        }
    }

    public static void openMenu(String menu) {
        cancelScheduledShot();
        PaperShotPresentation.cancelScene();
        if (menu.equals("refit")) {
            if (pendingRefitOpen != null) {
                // Pressing Z again while the server is responding cancels the pending open.
                pendingRefitOpen = null;
                return;
            }
            requestRefitMenu(null);
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("op", "open_" + menu);
        PaperClientBridge.sendMenuAction(request);
    }

    public static void reopenRefitMenu(GunRefitScreen screen) {
        if (Minecraft.getInstance().screen == screen && screen.isPaperRefit()) requestRefitMenu(screen);
    }

    private static void requestRefitMenu(GunRefitScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        var held = mc.player == null ? null : resolve(mc.player.getMainHandItem());
        if (!PaperClientBridge.active() || held == null || !held.paper() || mc.screen != screen) return;
        if (mc.gameMode != null) mc.gameMode.ensureHasSentCarriedItem();
        PendingRefitOpen pending = new PendingRefitOpen(UUID.randomUUID().toString(),
                mc.player.getInventory().getSelectedSlot(), held.instance(), mc.level, screen, System.currentTimeMillis() + 10_000);
        pendingRefitOpen = pending;
        JsonObject request = new JsonObject();
        request.addProperty("op", "open_refit");
        request.addProperty("requestId", pending.requestId());
        request.addProperty("slot", pending.slot());
        request.addProperty("instance", pending.instance());
        PaperClientBridge.sendMenuAction(request);
    }

    public static void action(String op) { action(op, new JsonObject()); }

    public static void action(String op, boolean value) {
        JsonObject args = new JsonObject();
        args.addProperty("value", value);
        if (action(op, args) < 0) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && op.equals("aim")) IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder().clientIsAiming = value;
        if (op.equals("crawl")) crawling = value;
    }

    private static long action(String op, JsonObject args) {
        if (op.equals("reload") || op.equals("cancel_reload") || op.equals("bolt") || op.equals("draw")
                || op.equals("fire_select") || op.equals("melee") || op.equals("inspect")) cancelScheduledShot();
        if (op.equals("fire_select")) {
            serverShotInterval = 0;
            lastConfirmedFireMode = null;
            lastConfirmedShotAt = 0;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isSpectator() || !isPaperGun(player.getMainHandItem())) return -1;
        long now = System.currentTimeMillis();
        if (op.equals("reload")) {
            if (IGunOperator.fromLivingEntity(player).getSynReloadState().getStateType().isReloading()) return -1;
            if (now < nextReload) return -1;
            nextReload = now + 250;
        }
        if (op.equals("bolt")) {
            if (IGunOperator.fromLivingEntity(player).getSynIsBolting()) return -1;
            if (now < nextBolt) return -1;
            nextBolt = now + 250;
        }
        if (op.equals("cancel_reload")) {
            ReloadState.StateType reloadState = IGunOperator.fromLivingEntity(player).getSynReloadState().getStateType();
            if (!reloadState.isReloading() || reloadState.isReloadFinishing() || now < nextCancel) return -1;
            ReceivedState snapshot = STATES.get(player.getId());
            var held = resolve(player.getMainHandItem());
            if (snapshot == null || held == null || !held.instance().equals(string(snapshot.value(), "instance", ""))
                    || !bool(snapshot.value(), "reloadInterruptible", false)) return -1;
            nextCancel = now + 250;
        }
        if (Minecraft.getInstance().gameMode != null) Minecraft.getInstance().gameMode.ensureHasSentCarriedItem();
        return PaperClientBridge.sendAction(op, args);
    }

    public static ShootResult shoot() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!PaperClientBridge.active() || player == null || !player.isAlive() || player.isSpectator()
                || !InputExtraCheck.isInGame()) return ShootResult.NOT_GUN;
        var gun = resolve(player.getMainHandItem());
        if (gun == null || !gun.paper()) return ShootResult.NOT_GUN;
        if (SHOT_SCHEDULE.pending() || cooldown() >= ClientShotSchedule.PREPARE_WINDOW_MS) return ShootResult.COOL_DOWN;
        IGunOperator operator = IGunOperator.fromLivingEntity(player);
        if (operator.getSynReloadState().getStateType().isReloading()) {
            action("cancel_reload");
            return ShootResult.IS_RELOADING;
        }
        if (operator.getSynDrawCoolDown() > 0) return ShootResult.IS_DRAWING;
        if (operator.getSynIsBolting()) return ShootResult.IS_BOLTING;
        if (operator.getSynMeleeCoolDown() > 0) return ShootResult.IS_MELEE;
        if (operator.getSynSprintTime() > 0 || player.isSprinting()) return ShootResult.IS_SPRINTING;
        if (gun.data().isOverheatLocked(gun.realStack())) return ShootResult.OVERHEATED;
        var index = TimelessAPI.getCommonGunIndex(gun.data().getGunId(gun.realStack()));
        if (index.isEmpty()) return ShootResult.ID_NOT_EXIST;
        if (!gun.data().hasBulletInBarrel(gun.realStack()) && gun.data().getCurrentAmmoCount(gun.realStack()) <= 0
                && !gun.data().useInventoryAmmo(gun.realStack()) && !player.getAbilities().instabuild) {
            TimelessAPI.getGunDisplay(gun.renderStack()).ifPresent(display -> SoundPlayManager.playDryFireSound(player, display));
            return ShootResult.NO_AMMO;
        }
        if (index.get().getGunData().getBolt() == Bolt.MANUAL_ACTION && !gun.data().hasBulletInBarrel(gun.realStack())) {
            if (shouldRequestBolt(gun)) action("bolt");
            return ShootResult.NEED_BOLT;
        }
        var gunData = index.get().getGunData();
        if (PaperShotPresentation.available(gun, gunData) <= 0) return ShootResult.NO_AMMO;
        FireMode fireMode = gun.data().getFireMode(gun.realStack());
        if (NeoForge.EVENT_BUS.post(new GunShootEvent(player, gun.renderStack().copy(), LogicalSide.CLIENT)).isCanceled()) return ShootResult.FORGE_EVENT_CANCEL;
        if (!SHOT_SCHEDULE.prepare(shotClock())) return ShootResult.COOL_DOWN;
        pendingShot = new PendingShot(mc.level, mc.getConnection(), player, player.getInventory().getSelectedSlot(), gun.instance(),
                gun.data().getGunId(gun.realStack()), fireMode, IClientPlayerGunOperator.fromLocalPlayer(player).getChargeProgress());
        var charge = gunData.getChargeData(fireMode);
        if (charge != null) {
            var holder = IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder();
            holder.chargeProgress = charge.getChargeType() == ChargeType.DELAY ? 0 : Math.max(0, holder.chargeProgress - charge.getDecreaseOnFire());
        }
        // SUCCESS commits this input, including semi-auto clicks and charge-on-release shots.
        // Never charge again in the render pump or require the trigger to remain held there.
        pumpShot();
        return ShootResult.SUCCESS;
    }

    public static void pumpShot() {
        PendingShot shot = pendingShot;
        if (shot == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!PaperClientBridge.active() || player != shot.player() || mc.level != shot.level() || mc.getConnection() != shot.connection()
                || player == null || !player.isAlive() || player.isSpectator() || !InputExtraCheck.isInGame()
                || player.getInventory().getSelectedSlot() != shot.slot()) { cancelScheduledShot(); return; }
        var gun = resolve(player.getMainHandItem());
        if (gun == null || !gun.paper() || !shot.instance().equals(gun.instance())
                || !shot.gunId().equals(gun.data().getGunId(gun.realStack()))
                || shot.fireMode() != gun.data().getFireMode(gun.realStack())) { cancelScheduledShot(); return; }
        IGunOperator operator = IGunOperator.fromLivingEntity(player);
        if (operator.getSynReloadState().getStateType().isReloading() || operator.getSynDrawCoolDown() > 0
                || operator.getSynIsBolting() || operator.getSynMeleeCoolDown() > 0
                || operator.getSynSprintTime() > 0 || player.isSprinting() || gun.data().isOverheatLocked(gun.realStack())) {
            cancelScheduledShot();
            return;
        }
        long now = shotClock();
        if (!SHOT_SCHEDULE.due(now)) return;
        var index = TimelessAPI.getCommonGunIndex(shot.gunId());
        if (index.isEmpty()) { cancelScheduledShot(); return; }
        var gunData = index.get().getGunData();
        if (PaperShotPresentation.available(gun, gunData) <= 0) { cancelScheduledShot(); return; }
        FireMode fireMode = shot.fireMode();
        long interval = Math.max(1, gunData.getShootInterval(player, fireMode, gun.realStack()));
        if (fireMode == FireMode.BURST && gunData.getBurstData() != null) {
            var burst = gunData.getBurstData();
            interval = Math.max((long) (burst.getMinInterval() * 1000), Math.max(0, burst.getCount() - 1) * gunData.getBurstShootInterval());
        }
        // Reuse server-side script adjustments only during the current firing sequence.
        // A long pause must let acceleration restart at the native attachment/heat interval.
        if (serverShotInterval > 0 && lastConfirmedFireMode == fireMode && gun.instance().equals(serverShotInstance)
                && now - lastConfirmedShotAt < interval + 100) interval = serverShotInterval;
        JsonObject args = new JsonObject();
        args.addProperty("charge", shot.charge());
        pendingShot = null;
        long actionSeq = action("shoot", args);
        if (actionSeq < 0) { cancelScheduledShot(); return; }
        SHOT_SCHEDULE.sent(now, Math.max(1, interval));
        PaperShotPresentation.sent(actionSeq, gun, gunData, fireMode);
    }

    private static long shotClock() { return System.nanoTime() / 1_000_000; }

    private static void cancelScheduledShot() {
        pendingShot = null;
        SHOT_SCHEDULE.cancel();
    }

    public static long cooldown() { return SHOT_SCHEDULE.remaining(shotClock()); }
    public static boolean crawling() { return crawling; }

    private static boolean shouldRequestBolt(GunResolver.ResolvedGun gun) {
        if (SHOT_SCHEDULE.pending() || cooldown() > 0 || PaperShotPresentation.hasUnconfirmedShots(gun.instance())) return false;
        // The cosmetic shot clears the displayed chamber before Paper may have fired its
        // queued trigger. A bolt intent based on that overlay would cancel the real shot.
        ItemStack authority = GunResolver.authoritativeStack(gun.realStack()).copy();
        if (!(authority.getItem() instanceof IGun data)) return false;
        return !data.hasBulletInBarrel(authority) && (data.getCurrentAmmoCount(authority) > 0 || data.useInventoryAmmo(authority));
    }

    public static boolean charge(boolean pressed, LocalPlayerDataHolder data) {
        LocalPlayer player = Minecraft.getInstance().player;
        var gun = player == null ? null : resolve(player.getMainHandItem());
        if (gun == null || !gun.paper()) { data.isCharging = false; data.chargeProgress = 0; return false; }
        var charge = TimelessAPI.getCommonGunIndex(gun.data().getGunId(gun.realStack()))
                .map(index -> index.getGunData().getChargeData(gun.data().getFireMode(gun.realStack()))).orElse(null);
        if (charge == null) return pressed;
        IGunOperator operator = IGunOperator.fromLivingEntity(player);
        boolean cooldownReady = charge.isChargeDuringCooldown() || cooldown() < 50;
        boolean ready = cooldownReady && !operator.getSynReloadState().getStateType().isReloading()
                && operator.getSynDrawCoolDown() <= 0 && !operator.getSynIsBolting()
                && operator.getSynSprintTime() <= 0 && !player.isSprinting();
        if ((pressed || charge.getChargeType() == ChargeType.DELAY && data.chargeProgress > 0) && ready) {
            data.isCharging = true;
            data.chargeProgress = Math.min(charge.getMaxCharge(), data.chargeProgress + charge.getIncreasePerTick());
            return charge.getChargeType() != ChargeType.HOLD && data.chargeProgress >= charge.getMaxCharge();
        }
        if (charge.getChargeType() == ChargeType.HOLD && cooldownReady && !pressed && data.chargeProgress >= charge.getFireThreshold()) {
            data.isCharging = false;
            return true;
        }
        data.isCharging = false;
        data.chargeProgress = Math.max(0, data.chargeProgress - charge.getDecreasePerTick());
        return false;
    }

    public static void draw(ItemStack lastItem) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        cancelScheduledShot();
        PaperShotPresentation.cancelScene();
        SHOT_SCHEDULE.reset();
        serverShotInterval = 0;
        lastConfirmedShotAt = 0;
        lastConfirmedFireMode = null;
        serverShotInstance = "";
        nextReload = 0;
        nextBolt = 0;
        nextCancel = 0;
        var data = IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder();
        data.reset();
        data.clientDrawTimestamp = System.currentTimeMillis();
        ItemStack last = renderStack(lastItem);
        if (BuiltinItemRendererRegistry.INSTANCE.get(last.getItem()) instanceof AnimateGeoItemRenderer<?, ?> renderer) renderer.tryExit(last, 0);
        action("draw");
        if (isPaperGun(player.getMainHandItem())) AttachmentPropertyManager.postChangeEvent(player, player.getMainHandItem());
    }

    public static void inventoryTick(LocalPlayer player) {
        if (pendingRefitOpen != null && !pendingRefitOpen.valid(Minecraft.getInstance())) {
            GunRefitScreen waiting = pendingRefitOpen.screen();
            pendingRefitOpen = null;
            if (waiting != null && Minecraft.getInstance().screen == waiting) Minecraft.getInstance().setScreen(null);
        }
        ItemStack held = player.getMainHandItem();
        if (selected != player.getInventory().getSelectedSlot() || !sameIdentity(previous, held)) {
            draw(previous);
            selected = player.getInventory().getSelectedSlot();
        }
        previous = held.copy();
    }

    public static void tickPlayer(LocalPlayer player, LocalPlayerDataHolder data) {
        long now = System.currentTimeMillis();
        if (player.level() != null) {
            STATES.entrySet().removeIf(entry -> player.level().getEntity(entry.getKey()) == null);
            for (var entry : STATES.entrySet()) {
                if (player.level().getEntity(entry.getKey()) instanceof LivingEntity entity) {
                    var equipped = resolve(entity.getMainHandItem());
                    if (equipped != null && equipped.paper() && equipped.instance().equals(string(entry.getValue().value(), "instance", ""))) {
                        syncEntity(entity, entry.getValue().value(), now - entry.getValue().receivedAt());
                    } else if (equipped == null || !equipped.paper()) {
                        syncEntity(entity, new JsonObject(), 0);
                        LAST_GUNS.remove(entry.getKey());
                    }
                }
            }
        }
        LocalPlayerDataHolder.oldAimingProgress = data.clientAimingProgress;
        var gun = resolve(player.getMainHandItem());
        float aimStep = gun == null ? .2f : TimelessAPI.getCommonGunIndex(gun.data().getGunId(gun.realStack()))
                .map(index -> Math.min(1f, .05f / Math.max(.001f, index.getGunData().getAimTime()))).orElse(.2f);
        data.clientAimingProgress = Mth.clamp(data.clientAimingProgress + (data.clientIsAiming ? aimStep : -aimStep), 0, 1);
        data.clientAimingTimestamp = now;
        if (!isPaperGun(player.getMainHandItem())) {
            data.clientIsAiming = false;
            data.isCharging = false;
            data.chargeProgress = 0;
            crawling = false;
        } else if (gun != null) {
            IGunOperator operator = IGunOperator.fromLivingEntity(player);
            if (!operator.getSynReloadState().getStateType().isReloading() && !operator.getSynIsBolting()
                    && operator.getSynDrawCoolDown() <= 0 && shouldRequestBolt(gun)) {
                TimelessAPI.getCommonGunIndex(gun.data().getGunId(gun.realStack())).ifPresent(index -> {
                    if (index.getGunData().getBolt() == Bolt.MANUAL_ACTION) action("bolt");
                });
            }
        }
        if (crawling && (player.jumping || player.isSpectator() || player.isPassenger() || player.getAbilities().flying)) action("crawl", false);
        player.setForcedPose(crawling ? Pose.SWIMMING : null);
    }

    public static void reset() {
        STATES.clear(); LAST_EVENTS.clear(); LAST_GUNS.clear(); GunResolver.reset();
        PaperClientBallistics.reset();
        PaperShotPresentation.reset();
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.setForcedPose(null);
        selected = -1; previous = ItemStack.EMPTY; nextReload = 0; nextBolt = 0; nextCancel = 0; crawling = false;
        cancelScheduledShot(); SHOT_SCHEDULE.reset();
        serverShotInterval = 0; lastConfirmedShotAt = 0; lastConfirmedFireMode = null; serverShotInstance = "";
        pendingRefitOpen = null;
    }
}
