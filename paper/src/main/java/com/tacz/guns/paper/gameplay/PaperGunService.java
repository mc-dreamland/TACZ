package com.tacz.guns.paper.gameplay;

import com.google.gson.*;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.BridgePeer;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.function.LongSupplier;
import static com.tacz.guns.paper.gameplay.GunMath.*;

/** Authoritative actions/state machine for bridged clients. All entry points run on the server thread. */
public final class PaperGunService implements Listener {
    private static final long SHOT_EARLY_WINDOW = 50;
    private static final Set<String> CANCEL_QUEUED_SHOT = Set.of("reload", "bolt", "melee", "fire_select", "inspect", "draw");
    private static final Set<String> SCRIPTS = Set.of("", "tacz:xmag_reload_logic", "tacz:db_short_gun_logic", "tacz:devotion_lmg_logic", "tacz:hk_mk23_logic",
            "tacz:kar98_gun_logic", "tacz:m1014_gun_logic", "tacz:m870_gun_logic", "tacz:spas_12_gun_logic", "tacz:sp_heat", "tacz:sp_spread_logic");
    private final JavaPlugin plugin;
    private final DefaultGunPack pack;
    private final PaperItemStore items;
    private final BridgePeer peer;
    private final PaperBallistics ballistics;
    private final LongSupplier clock;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final NamespacedKey movementKey;
    private long ticks;
    private static long eventSequence;
    static long nextEventSequence() { return ++eventSequence; }

    public PaperGunService(JavaPlugin plugin, DefaultGunPack pack, PaperItemStore items, BridgePeer peer) {
        this(plugin, pack, items, peer, () -> System.nanoTime() / 1_000_000);
    }

    public void hitboxProfiles(HitboxProfiles profiles) { ballistics.hitboxProfiles(profiles); }

    PaperGunService(JavaPlugin plugin, DefaultGunPack pack, PaperItemStore items, BridgePeer peer, LongSupplier clock) {
        this.plugin = plugin; this.pack = pack; this.items = items; this.peer = peer;
        this.clock = clock;
        movementKey = new NamespacedKey(plugin, "gun_movement");
        ballistics = new PaperBallistics(plugin, peer);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void handle(Player player, JsonObject action) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Gun actions must execute on the server thread");
        if (!allowed(player)) return;
        long now = now();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        if (now - session.windowStart >= 1000) { session.windowStart = now; session.requests = 0; }
        if (++session.requests > 100) return;
        long sequence;
        try { sequence = action.get("seq").getAsLong(); } catch (RuntimeException ex) { return; }
        if (sequence < 0 || sequence <= session.sequence) return;
        session.sequence = sequence;
        String op = text(action, "op", "");
        if (CANCEL_QUEUED_SHOT.contains(op)) cancelPendingShot(player, session);
        Equipped equipped = equip(player, session, now);
        if (equipped == null || !text(action, "instance", "").equals(text(equipped.state, "instance", ""))) {
            rejectShot(player, action, sequence); sync(player); return;
        }
        // Requests can arrive between ticks. Settle due feeds before an action can interrupt them.
        tickReload(player, session, equipped, now);
        persist(equipped);
        if (op.equals("shoot") && executePendingShot(player, session, now)) {
            equipped = equip(player, session, now);
            if (equipped == null || !text(action, "instance", "").equals(text(equipped.state, "instance", ""))) {
                rejectShot(player, action, sequence); sync(player); return;
            }
        }
        switch (op) {
            case "shoot" -> shoot(player, session, equipped, action, sequence, now);
            case "reload" -> reload(player, session, equipped, now);
            case "cancel_reload" -> cancelReload(player, session, equipped, now);
            case "bolt" -> bolt(player, session, equipped, now);
            case "draw" -> { /* equip() already started the draw once for this instance. */ }
            case "aim" -> {
                session.aiming = flag(action, "value", false);
                if (session.aiming) player.setSprinting(false);
                broadcastValue(player, equipped.state, op, session.aiming);
            }
            case "crawl" -> {
                session.crawling = flag(equipped.gun, "can_crawl", true) && flag(action, "value", false)
                        && player.isOnGround() && !player.isFlying() && !player.isInsideVehicle() && !player.isInWater();
                player.setPose(session.crawling ? Pose.SWIMMING : player.getPose(), session.crawling);
                broadcastValue(player, equipped.state, op, session.crawling);
            }
            case "fire_select" -> {
                if (!busy(session, now) && equipped.gun.has("fire_mode")) {
                    JsonArray modes = equipped.gun.getAsJsonArray("fire_mode");
                    if (!modes.isEmpty()) {
                        int index = -1;
                        for (int i = 0; i < modes.size(); i++) if (modes.get(i).getAsString().equalsIgnoreCase(text(equipped.state, "fireMode", "semi"))) index = i;
                        equipped.state.addProperty("fireMode", modes.get((index + 1) % modes.size()).getAsString().toLowerCase(Locale.ROOT));
                        session.charge = 0; session.lastShot = now;
                        session.shotPhase = false; session.acceleration = 0; session.shotInterval = 0;
                        persist(equipped);
                        peer.broadcast(player, "event", event(player, equipped.state, op));
                    }
                }
            }
            case "zoom" -> {
                String scopeId = text(object(equipped.state, "attachments"), "scope", "");
                int count = pack.scopeZoomCount(scopeId);
                if (count > 1) {
                    int zoom = Math.floorMod(integer(equipped.state, "zoom", 0) + 1, count);
                    equipped.state.addProperty("zoom", zoom); persist(equipped);
                    JsonObject event = event(player, equipped.state, op); event.addProperty("zoom", zoom); peer.send(player, "event", event);
                }
            }
            case "melee" -> melee(player, session, equipped, now);
            case "inspect" -> { if (!busy(session, now)) peer.broadcast(player, "event", event(player, equipped.state, op)); }
            default -> { }
        }
        sync(player);
    }

    public void tick() {
        ticks++;
        long now = now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!allowed(player)) { clearHeld(player, sessions.get(player.getUniqueId())); continue; }
            Session s = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
            Equipped e = equip(player, s, now);
            if (e == null) {
                if (s.lastSyncedGun) sync(player); // Clear observers' ADS/reload state after switching to an ordinary item.
                continue;
            }
            tickReload(player, s, e, now);
            // Feeding blocks sprinting, but reaching the finishing phase allows it again.
            if (s.aiming || feeding(s)) player.setSprinting(false);
            if (player.isSprinting()) { s.sprintUntil = now + millis(e.gun, "sprint_time", .2); s.aiming = false; }
            if (s.crawling && (!player.isOnGround() || player.isFlying() || player.isInsideVehicle() || player.isInWater())) {
                s.crawling = false;
                player.setPose(player.getPose(), false);
            }
            double aimStep = clamp(now - s.aimTick, 0, 100) / (double) Math.max(1, millis(e.gun, "aim_time", .2));
            s.aimingProgress = clamp(s.aimingProgress + (s.aiming ? aimStep : -aimStep), 0, 1);
            s.aimTick = now;
            tickBolt(player, s, e, now);
            coolHeat(s, e, now);
            // A queued trigger owns no item snapshot. Commit feeds/cooling before reading the
            // real held item again, and never let the old snapshot overwrite its ammunition.
            if (s.pendingShot != null && now >= s.pendingShot.due) {
                persist(e);
                executePendingShot(player, s, now);
                e = equip(player, s, now);
                if (e == null) continue;
            }
            boolean firedBurst = false;
            if (s.burstRemaining > 0 && now >= s.burstAt) {
                if (player.isSprinting() || s.reload != null || s.boltUntil > now || flag(e.state, "overheated", false)) finishBurst(player, s, "cancelled");
                else {
                    // Catch up only the bounded, server-created burst; timestamps never come from clients.
                    for (int i = 0; i < 16 && s.burstRemaining > 0 && now >= s.burstAt; i++) {
                        if (!fireOne(player, s, e, now, s.burstTrigger, true)) { finishBurst(player, s, "cancelled"); break; }
                        firedBurst = true;
                        s.burstRemaining--; s.burstAt += s.burstInterval;
                    }
                    if (s.burstRemaining == 0) finishBurst(player, s, "complete");
                }
            }
            if (s.meleeAt > 0 && now >= s.meleeAt) { s.meleeAt = 0; performMelee(player, s, e); }
            movement(player, s, e);
            persist(e);
            // Match the persisted ammo snapshot with its firing watermark in the same tick.
            if (ticks % 2 == 0 || firedBurst) sync(player);
        }
        ballistics.tick();
    }

    private void shoot(Player player, Session s, Equipped e, JsonObject action, long sequence, long now) {
        if (s.pendingShot != null || !canShoot(player, s, e, now)) { rejectShot(player, action, sequence); return; }
        String mode = text(e.state, "fireMode", "semi");
        double claimed;
        try { claimed = action.has("charge") ? action.get("charge").getAsDouble() : 0; }
        catch (RuntimeException ex) { rejectShot(player, action, sequence); return; }
        if (!validShotCharge(s, e, claimed, now)) { rejectShot(player, action, sequence); return; }
        if (now < s.nextShot) {
            if (s.nextShot - now <= SHOT_EARLY_WINDOW) {
                s.pendingShot = new PendingShot(s.instance, mode, claimed, s.nextShot, sequence);
                shotResult(player, s.instance, sequence, "queued", 0);
            } else rejectShot(player, action, sequence);
            return;
        }
        fireTrigger(player, s, e, claimed, now, s.nextShot, sequence);
    }

    private boolean canShoot(Player player, Session s, Equipped e, long now) {
        return !busy(s, now) && now >= s.sprintUntil && !player.isSprinting() && !flag(e.state, "overheated", false);
    }

    private boolean validShotCharge(Session s, Equipped e, double claimed, long now) {
        String mode = text(e.state, "fireMode", "semi");
        JsonObject charge = object(object(e.gun, "charging"), mode);
        double previous = text(charge, "type", "hold").equals("delay") ? 0 : Math.max(0, s.charge - number(charge, "decrease_on_fire", 0));
        return validCharge(charge, claimed, now - Math.max(s.lastShot, s.drawStarted), previous);
    }

    /** Returns true when a due slot was consumed, whether it fired or became invalid. */
    private boolean executePendingShot(Player player, Session s, long now) {
        PendingShot pending = s.pendingShot;
        if (pending == null || now < pending.due) return false;
        s.pendingShot = null;
        if (!allowed(player)) {
            shotResult(player, pending.instance, pending.sequence, "cancelled", 0); return true;
        }
        Equipped current = equip(player, s, now);
        if (current == null || !pending.instance.equals(s.instance) || !pending.fireMode.equals(text(current.state, "fireMode", "semi"))) {
            shotResult(player, pending.instance, pending.sequence, "cancelled", 0); return true;
        }
        if (canShoot(player, s, current, now) && validShotCharge(s, current, pending.charge, now))
            fireTrigger(player, s, current, pending.charge, now, pending.due, pending.sequence);
        else shotResult(player, pending.instance, pending.sequence, "cancelled", 0);
        // Sequence validation happened when the intent entered the slot. Aim/zoom messages may
        // legitimately have advanced the session sequence while this trigger was waiting.
        sync(player);
        return true;
    }

    private void fireTrigger(Player player, Session s, Equipped e, double claimed, long now, long due, long sequence) {
        ShotTrigger trigger = new ShotTrigger(sequence, s.instance);
        if (!fireOne(player, s, e, now, trigger, true)) { finishTrigger(player, trigger, "rejected"); return; }
        s.charge = claimed;
        String mode = text(e.state, "fireMode", "semi");
        JsonObject burst = object(e.gun, "burst_data");
        boolean burstMode = mode.equals("burst");
        int count = burstMode ? (int) clamp(integer(burst, "count", 1), 1, 16) : 1;
        if (text(e.gun, "script", "").equals("tacz:db_short_gun_logic") && burstMode) {
            // The bundled client plays one double-barrel animation/sound for the two physical discharges.
            fireOne(player, s, e, now, trigger, false);
        }
        s.burstRemaining = count - 1;
        s.burstTrigger = s.burstRemaining > 0 ? trigger : null;
        s.burstInterval = (long) Math.max(25, 60000 / Math.max(1, number(burst, "bpm", 600)));
        double rpm = Math.max(1, number(e.gun, "rpm", 300));
        JsonObject heat = object(e.gun, "heat");
        double fraction = clamp(number(e.state, "heat", 0) / Math.max(1, number(heat, "max", 100)), 0, 1);
        rpm *= number(heat, "min_rpm_mod", 1) + fraction * (number(heat, "max_rpm_mod", 1) - number(heat, "min_rpm_mod", 1));
        long interval = (long) Math.max(25, 60000 / Math.max(1, rpm));
        if (text(e.gun, "script", "").equals("tacz:devotion_lmg_logic")) {
            s.acceleration = s.shotPhase && now - s.lastShot < interval + 100 ? Math.min(7, s.acceleration + 1) : 0;
            interval = (long) (interval * (1 - s.acceleration * .08));
        }
        s.lastShot = now;
        // TACZ's cooldown compares group start timestamps; the scheduled rounds run inside that window.
        s.shotInterval = burstMode ? Math.max(s.burstRemaining * s.burstInterval, millis(burst, "min_interval", .3)) : interval;
        // Carry only an existing phase's one-tick scheduling delay. A first shot or long pause
        // cannot create credit, and a long stall cannot release a backlog in a single tick.
        long phase = s.shotPhase && due > 0 && now >= due && now - due <= SHOT_EARLY_WINDOW ? due : now;
        if (phase + s.shotInterval <= now) phase = now;
        s.nextShot = phase + s.shotInterval;
        s.burstAt = phase + s.burstInterval;
        s.shotPhase = true;
        persist(e);
        if (s.burstRemaining == 0) finishTrigger(player, trigger, "complete");
    }

    private boolean fireOne(Player player, Session s, Equipped e, long now, ShotTrigger trigger, boolean visual) {
        if (flag(e.state, "overheated", false)) return false;
        String bolt = text(e.gun, "bolt", "open_bolt");
        boolean inventory = text(object(e.gun, "reload"), "type", "magazine").equals("inventory");
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        int ammo = integer(e.state, "ammo", 0);
        boolean chamber = flag(e.state, "chamber", false);
        if (bolt.equals("manual_action") && !chamber) return false;
        if (!inventory && ammo <= 0 && (!chamber || bolt.equals("open_bolt"))) return false;
        if (!bolt.equals("manual_action") && inventory) {
            if (!creative) {
                String ammoId = text(e.gun, "ammo", "");
                if (bolt.equals("closed_bolt") && !chamber) {
                    if (items.takeAmmo(player, ammoId, 1) != 1) return false;
                    chamber = true;
                }
                if (items.takeAmmo(player, ammoId, 1) != 1) {
                    if (!chamber || bolt.equals("open_bolt")) return false;
                    chamber = false;
                }
            }
        } else if (!creative) {
            if (bolt.equals("manual_action")) chamber = false;
            else if (ammo > 0) ammo--;
            else if (!bolt.equals("open_bolt") && chamber) chamber = false;
            else return false;
        }
        if (bolt.equals("closed_bolt") && ammo > 0 && !chamber && !inventory) { ammo--; chamber = true; }
        if (text(e.gun, "script", "").equals("tacz:spas_12_gun_logic") && text(e.state, "fireMode", "semi").equals("burst") && ammo > 0) { ammo--; chamber = true; }
        e.state.addProperty("ammo", Math.max(0, ammo)); e.state.addProperty("chamber", chamber);
        ballistics.fire(player, e.state, e.gun, s.aimingProgress, s.crawling);
        JsonObject heat = object(e.gun, "heat");
        if (!heat.isEmpty()) {
            double max = Math.max(1, number(heat, "max", 100));
            double amount = Math.min(max, number(e.state, "heat", 0) + Math.max(0, number(heat, "per_shot", 3)));
            e.state.addProperty("heat", amount); e.state.addProperty("overheated", amount >= max); s.heatAt = now;
        }
        JsonObject event = event(player, e.state, "shoot");
        int shotIndex = trigger.fired++;
        event.addProperty("actionSeq", trigger.sequence); event.addProperty("shotIndex", shotIndex);
        event.addProperty("visual", visual);
        s.lastFiredActionSeq = trigger.sequence; s.lastFiredShotIndex = shotIndex;
        event.addProperty("fireMode", text(e.state, "fireMode", "semi")); event.addProperty("ammo", integer(e.state, "ammo", 0));
        JsonObject silence = object(e.gun, "silence");
        event.addProperty("silenced", flag(silence, "use_silence_sound", false));
        event.addProperty("soundDistance", number(silence, "distance", 128));
        peer.broadcast(player, "event", event);
        player.setExhaustion(player.getExhaustion() + .02f);
        return true;
    }

    private void rejectShot(Player player, JsonObject action, long sequence) {
        if (text(action, "op", "").equals("shoot")) shotResult(player, text(action, "instance", ""), sequence, "rejected", 0);
    }

    private void cancelPendingShot(Player player, Session s) {
        PendingShot pending = s.pendingShot;
        s.pendingShot = null;
        if (pending != null) shotResult(player, pending.instance, pending.sequence, "cancelled", 0);
    }

    private void finishBurst(Player player, Session s, String status) {
        ShotTrigger trigger = s.burstTrigger;
        s.burstRemaining = 0; s.burstTrigger = null;
        if (trigger != null) finishTrigger(player, trigger, status);
    }

    private void finishTrigger(Player player, ShotTrigger trigger, String status) {
        if (trigger.finished) return;
        trigger.finished = true;
        shotResult(player, trigger.instance, trigger.sequence, status, trigger.fired);
    }

    /** A result closes only this trigger; the action-sequence watermark is not a firing acknowledgement. */
    private void shotResult(Player player, String instance, long sequence, String status, int fired) {
        if (!peer.ready(player)) return;
        JsonObject result = new JsonObject();
        result.addProperty("entity", player.getEntityId()); result.addProperty("instance", instance);
        result.addProperty("op", "shot_result"); result.addProperty("seq", nextEventSequence());
        result.addProperty("actionSeq", sequence); result.addProperty("status", status); result.addProperty("fired", fired);
        peer.send(player, "event", result);
    }

    private void reload(Player player, Session s, Equipped e, long now) {
        if (busy(s, now) || now < s.nextShot || text(object(e.gun, "reload"), "type", "magazine").equals("inventory")) return;
        int capacity = items.capacity(e.state);
        if (integer(e.state, "ammo", 0) >= capacity && (text(e.gun, "bolt", "open_bolt").equals("open_bolt") || flag(e.state, "chamber", false))) return;
        if (!freeAmmo(player, e) && items.countAmmo(player, text(e.gun, "ammo", "")) == 0) return;
        int level = 0;
        for (var entry : object(e.state, "attachments").entrySet()) {
            if (entry.getKey().equalsIgnoreCase("extended_mag")) {
                JsonObject attachment = pack.attachment(entry.getValue().getAsString());
                if (attachment != null) level = integer(attachment, "extended_mag_level", 0);
            }
        }
        s.reload = ReloadPlan.create(e.gun, e.state, capacity, level);
        s.reloadStart = now; s.feedIndex = 0; s.reloadEnd = now + s.reload.duration();
        finishBurst(player, s, "cancelled"); s.aiming = false;
        player.setSprinting(false);
        sync(player); // Animation transitions must see the accepted reload phase before the event.
        peer.broadcast(player, "event", event(player, e.state, "reload"));
    }

    private void tickReload(Player player, Session s, Equipped e, long now) {
        if (s.reload == null) return;
        while (s.feedIndex < s.reload.feeds().size() && now >= s.reloadStart + s.reload.feeds().get(s.feedIndex).time()) {
            ReloadPlan.Feed feed = s.reload.feeds().get(s.feedIndex++);
            int availableSpace = feed.chamber() ? (flag(e.state, "chamber", false) ? 0 : 1) : Math.max(0, items.capacity(e.state) - integer(e.state, "ammo", 0));
            int wanted = Math.min(feed.count(), availableSpace);
            int taken;
            if (freeAmmo(player, e)) taken = wanted;
            else if (text(object(e.gun, "reload"), "type", "magazine").equals("fuel")) taken = wanted > 0 && items.takeAmmo(player, text(e.gun, "ammo", ""), 1) == 1 ? wanted : 0;
            else taken = items.takeAmmo(player, text(e.gun, "ammo", ""), wanted);
            if (feed.chamber()) { if (taken > 0) e.state.addProperty("chamber", true); }
            else e.state.addProperty("ammo", integer(e.state, "ammo", 0) + taken);
            if (!s.reload.progressive() && s.reload.empty() && !text(e.gun, "bolt", "open_bolt").equals("open_bolt") && !flag(e.state, "chamber", false) && integer(e.state, "ammo", 0) > 0) chamber(e.state);
            if (taken < wanted && s.reload.progressive()) { finishProgressive(s, now); break; }
        }
        if (now >= s.reloadEnd) { s.reload = null; s.feedIndex = 0; }
    }

    private void cancelReload(Player player, Session s, Equipped e, long now) {
        // The default magazine/xmag scripts have no interrupt_reload hook. Cancelling their
        // server timer while their animation continues would make a completed reload load nothing.
        if (!interruptible(s)) return;
        finishProgressive(s, now);
        sync(player);
        peer.broadcast(player, "event", event(player, e.state, "cancel_reload"));
    }

    private void finishProgressive(Session s, long now) { s.feedIndex = s.reload.feeds().size(); s.reloadEnd = Math.min(s.reloadEnd, now + s.reload.ending()); }

    private void bolt(Player player, Session s, Equipped e, long now) {
        if (busy(s, now) || now < s.nextShot || flag(e.state, "chamber", false) || !text(e.gun, "bolt", "open_bolt").equals("manual_action")) return;
        if (integer(e.state, "ammo", 0) <= 0 && !text(object(e.gun, "reload"), "type", "magazine").equals("inventory")) return;
        JsonObject params = object(e.gun, "script_param");
        long duration = millis(params, "bolt_time", number(e.gun, "bolt_action_time", .5));
        double feedDefault = number(e.gun, "bolt_feed_time", duration / 1000.0);
        s.boltFeed = now + millis(params, "bolt_feed_time", feedDefault < 0 ? duration / 1000.0 : feedDefault);
        s.boltUntil = now + Math.max(duration, s.boltFeed - now); s.boltFed = false;
        peer.broadcast(player, "event", event(player, e.state, "bolt"));
    }

    private void tickBolt(Player player, Session s, Equipped e, long now) {
        if (s.boltUntil == 0) return;
        if (!s.boltFed && now >= s.boltFeed) {
            s.boltFed = true;
            if (text(object(e.gun, "reload"), "type", "magazine").equals("inventory")) {
                if (freeAmmo(player, e) || items.takeAmmo(player, text(e.gun, "ammo", ""), 1) == 1) e.state.addProperty("chamber", true);
            } else if (integer(e.state, "ammo", 0) > 0) chamber(e.state);
        }
        if (now >= s.boltUntil) s.boltUntil = 0;
    }

    private static void chamber(JsonObject state) { state.addProperty("ammo", integer(state, "ammo", 0) - 1); state.addProperty("chamber", true); }
    private boolean freeAmmo(Player player, Equipped e) { return player.getGameMode() == GameMode.CREATIVE || flag(object(e.gun, "reload"), "infinite", false); }

    private void coolHeat(Session s, Equipped e, long now) {
        JsonObject heat = object(e.gun, "heat");
        double amount = number(e.state, "heat", 0);
        if (heat.isEmpty() || amount <= 0) return;
        boolean locked = flag(e.state, "overheated", false);
        long elapsed = now - s.heatAt;
        if (elapsed >= number(heat, locked ? "over_heat_time" : "cooling_delay", locked ? 3000 : 1000)) {
            // Original script subtracts elapsed / 10000 * multiplier once per game tick.
            amount = Math.max(0, amount - elapsed / 10000.0 * number(heat, "cooling_multiplier", 1));
            e.state.addProperty("heat", amount); if (amount <= 0) e.state.addProperty("overheated", false);
        }
    }

    private void melee(Player player, Session s, Equipped e, long now) {
        if (busy(s, now) || now < s.nextShot || now < s.meleeUntil) return;
        JsonObject melee = object(e.gun, "melee"), data = meleeData(e);
        s.meleeAt = now + Math.max(1, millis(data, "prep", .1));
        s.meleeUntil = now + millis(melee, "cooldown", .7) + millis(data, "cooldown", 0);
        s.nextShot = s.meleeUntil;
        s.shotPhase = false;
        peer.broadcast(player, "event", event(player, e.state, "melee"));
    }

    private JsonObject meleeData(Equipped e) {
        for (var entry : object(e.state, "attachments").entrySet()) {
            JsonObject attachment = pack.attachment(entry.getValue().getAsString());
            if (attachment != null && attachment.has("melee")) return object(attachment, "melee");
        }
        return object(object(e.gun, "melee"), "default");
    }

    private void performMelee(Player player, Session s, Equipped e) {
        JsonObject data = meleeData(e);
        double reach = clamp(number(object(e.gun, "melee"), "distance", 1) + number(data, "distance", 1), 0, 8);
        double angle = Math.toRadians(clamp(number(data, "range_angle", 40) / 2, 0, 90));
        Location eye = player.getEyeLocation(); Vector look = eye.getDirection();
        for (Entity target : player.getNearbyEntities(reach, reach, reach)) {
            if (!(target instanceof LivingEntity living) || living == player || !player.hasLineOfSight(living)) continue;
            Vector offset = living.getEyeLocation().toVector().subtract(eye.toVector());
            if (offset.lengthSquared() > reach * reach || offset.lengthSquared() < .0001 || look.angle(offset) > angle) continue;
            ballistics.damage(player, living, Math.max(0, number(data, "damage", 1)), 0, eye, DamageType.PLAYER_ATTACK, number(data, "knockback", .5), offset);
        }
        player.setExhaustion(player.getExhaustion() + .1f);
    }

    private Equipped equip(Player player, Session s, long now) {
        ItemStack item = player.getInventory().getItemInMainHand();
        JsonObject state = items.read(item);
        if (state == null || !text(state, "kind", "").equals("gun")) { clearHeld(player, s); return null; }
        JsonObject gun = items.effectiveGun(state);
        if (gun == null || !SCRIPTS.contains(text(gun, "script", ""))) { clearHeld(player, s); return null; }
        String instance = text(state, "instance", "");
        if (instance.isBlank()) return null;
        if (!instance.equals(s.instance)) {
            long putAway = s.putAway;
            clearHeld(player, s);
            s.instance = instance; s.drawStarted = now; s.heatAt = now; s.aimTick = now;
            s.drawUntil = now + putAway + millis(gun, "draw_time", .4);
            s.putAway = millis(gun, "put_away_time", .4);
            peer.broadcast(player, "event", event(player, state, "draw"));
        }
        return new Equipped(item, state, gun, state.deepCopy());
    }

    public void sync(Player player) {
        if (!peer.ready(player)) return;
        Session s = sessions.get(player.getUniqueId());
        JsonObject state = items.read(player.getInventory().getItemInMainHand());
        JsonObject packet = state != null && text(state, "kind", "").equals("gun") ? state.deepCopy() : new JsonObject();
        packet.addProperty("entity", player.getEntityId());
        packet.addProperty("instance", text(packet, "instance", "")); packet.addProperty("id", text(packet, "id", ""));
        packet.addProperty("ammo", integer(packet, "ammo", 0)); packet.addProperty("chamber", flag(packet, "chamber", false));
        packet.addProperty("fireMode", text(packet, "fireMode", "semi")); packet.addProperty("heat", number(packet, "heat", 0));
        packet.add("attachments", object(packet, "attachments").deepCopy());
        long now = now();
        boolean feeding = feeding(s);
        packet.addProperty("reloadState", s == null || s.reload == null ? "NOT_RELOADING" : (s.reload.empty() ? "EMPTY_RELOAD_" : "TACTICAL_RELOAD_") + (feeding ? "FEEDING" : "FINISHING"));
        packet.addProperty("reloadInterruptible", interruptible(s));
        long reloadDeadline = s == null || s.reload == null ? 0 : feeding ? s.reloadStart + s.reload.feeds().get(s.feedIndex).time() : s.reloadEnd;
        packet.addProperty("reloadRemaining", Math.max(0, reloadDeadline - now));
        packet.addProperty("drawRemaining", s == null ? 0 : Math.max(0, s.drawUntil - now));
        packet.addProperty("shootRemaining", s == null ? 0 : Math.max(0, s.nextShot - now));
        packet.addProperty("shotInterval", s == null ? 0 : s.shotInterval);
        packet.addProperty("bolting", s != null && s.boltUntil > now);
        packet.addProperty("aiming", s != null && s.aiming); packet.addProperty("crawling", s != null && s.crawling);
        packet.addProperty("aimingProgress", s == null ? 0 : s.aimingProgress);
        packet.addProperty("charge", s == null ? 0 : s.charge);
        packet.addProperty("lastFiredActionSeq", s == null ? -1 : s.lastFiredActionSeq);
        packet.addProperty("lastFiredShotIndex", s == null ? -1 : s.lastFiredShotIndex);
        packet.addProperty("sprinting", s == null ? 0 : Math.max(0, s.sprintUntil - now) / 1000.0);
        packet.addProperty("seq", s == null ? -1 : s.sequence);
        if (s != null) s.lastSyncedGun = !text(packet, "instance", "").isEmpty();
        peer.broadcast(player, "state", packet);
    }

    private void movement(Player player, Session s, Equipped e) {
        JsonObject movement = object(e.gun, "movement_speed");
        String type = s.reload != null ? "reload" : s.aiming ? "aim" : "base";
        double amount = clamp(number(movement, type, 0) - Math.max(0, number(e.gun, "weight", 0)) * .015, -.95, 2);
        if (Double.compare(s.movement, amount) == 0) return;
        if (applyMovement(player, amount)) s.movement = amount;
    }

    /** Isolate the Bukkit attribute boundary from the authoritative action state machine. */
    boolean applyMovement(Player player, double amount) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute == null) return false;
        attribute.removeModifier(movementKey);
        attribute.addTransientModifier(new AttributeModifier(movementKey, amount, AttributeModifier.Operation.ADD_SCALAR));
        return true;
    }

    void clearMovement(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(movementKey);
    }

    public void reset(Player player) {
        Session session = sessions.get(player.getUniqueId());
        clearHeld(player, session); ballistics.reset(player);
        sync(player);
    }

    /** Rehandshake starts a new request-sequence epoch; inventory transactions use reset() instead. */
    public void resetConnection(Player player) {
        Session old = sessions.remove(player.getUniqueId());
        clearHeld(player, old);
        ballistics.reset(player);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) clearHeld(player, sessions.get(player.getUniqueId()));
        sessions.clear(); ballistics.shutdown(); HandlerList.unregisterAll(this);
    }

    private void clearHeld(Player player, Session s) {
        if (s == null) return;
        cancelPendingShot(player, s);
        finishBurst(player, s, "cancelled");
        if (s.crawling) player.setPose(player.getPose(), false);
        if (!s.instance.isEmpty()) clearMovement(player);
        s.instance = ""; s.aiming = false; s.aimingProgress = 0; s.crawling = false; s.reload = null; s.boltUntil = 0;
        s.burstRemaining = 0; s.meleeAt = 0; s.charge = 0; s.movement = Double.NaN; s.drawUntil = 0; s.shotInterval = 0;
        s.pendingShot = null; s.shotPhase = false; s.acceleration = 0;
        s.lastFiredActionSeq = -1; s.lastFiredShotIndex = -1;
        // nextShot/sequence are session-wide; switching guns cannot bypass anti-replay or a firing cooldown.
    }
    private boolean allowed(Player player) { return peer.ready(player) && player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && player.hasPermission("tacz.use"); }
    private static boolean busy(Session s, long now) { return s.reload != null || s.boltUntil > now || s.drawUntil > now || s.burstRemaining > 0 || s.meleeAt > 0; }
    private static boolean feeding(Session s) { return s != null && s.reload != null && s.feedIndex < s.reload.feeds().size(); }
    private static boolean interruptible(Session s) { return feeding(s) && s.reload.progressive(); }
    private long now() { return clock.getAsLong(); }
    private static JsonObject event(Player player, JsonObject state, String op) {
        JsonObject event = new JsonObject(); event.addProperty("entity", player.getEntityId()); event.addProperty("instance", text(state, "instance", "")); event.addProperty("id", text(state, "id", "")); event.addProperty("op", op); event.addProperty("seq", nextEventSequence()); event.addProperty("fireMode", text(state, "fireMode", "semi")); return event;
    }
    private void broadcastValue(Player player, JsonObject state, String op, boolean value) { JsonObject event = event(player, state, op); event.addProperty("value", value); peer.broadcast(player, "event", event); }

    @EventHandler public void quit(PlayerQuitEvent event) { reset(event.getPlayer()); sessions.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void death(PlayerDeathEvent event) { reset(event.getEntity()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) { reset(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void slot(PlayerItemHeldEvent event) { reset(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent event) { reset(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void drop(PlayerDropItemEvent event) { reset(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void sprint(PlayerToggleSprintEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (event.isSprinting() && s != null && (s.aiming || feeding(s))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sprintStarted(PlayerToggleSprintEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (event.isSprinting() && s != null) cancelPendingShot(event.getPlayer(), s);
    }

    /** Carrier sticks must not provide a second attack path around gun action/state validation. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void vanillaAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!(event.getDamager() instanceof Player player) || ballistics.isManagedDamage(event)) return;
        JsonObject state = items.read(player.getInventory().getItemInMainHand());
        if (state != null && text(state, "kind", "").equals("gun")) event.setCancelled(true);
    }

    private void persist(Equipped equipped) {
        if (!equipped.state.equals(equipped.original)) items.write(equipped.item, equipped.state);
    }
    private record Equipped(ItemStack item, JsonObject state, JsonObject gun, JsonObject original) {}
    private record PendingShot(String instance, String fireMode, double charge, long due, long sequence) {}
    private static final class ShotTrigger {
        final long sequence;
        final String instance;
        int fired;
        boolean finished;
        ShotTrigger(long sequence, String instance) { this.sequence = sequence; this.instance = instance; }
    }
    private static final class Session {
        String instance = "";
        long sequence = -1, windowStart, drawStarted, drawUntil, putAway, nextShot, lastShot, sprintUntil, boltUntil, boltFeed, heatAt, aimTick;
        long reloadStart, reloadEnd, burstAt, burstInterval, meleeAt, meleeUntil, shotInterval;
        long lastFiredActionSeq = -1;
        int lastFiredShotIndex = -1;
        int requests, feedIndex, burstRemaining, acceleration;
        boolean aiming, crawling, boltFed, lastSyncedGun, shotPhase;
        double charge, aimingProgress, movement = Double.NaN;
        ReloadPlan reload;
        PendingShot pendingShot;
        ShotTrigger burstTrigger;
    }
}
