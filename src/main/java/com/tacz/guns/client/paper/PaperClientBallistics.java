package com.tacz.guns.client.paper;

import com.google.gson.JsonObject;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.bridge.BallisticsBatch;
import com.tacz.guns.client.event.RenderCrosshairEvent;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.particles.BulletHoleOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import com.tacz.guns.api.LogicalSide;
import net.neoforged.fml.common.EventBusSubscriber;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.tacz.guns.client.paper.GunResolver.bool;
import static com.tacz.guns.client.paper.GunResolver.string;

/** Paper owns impacts and damage. These untracked local entities only animate and render. */
@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public final class PaperClientBallistics {
    private static final int MAX_VISUALS = 2048;
    private static final int MAX_REMEMBERED = 8192;
    private static final int MAX_EFFECTS_PER_TICK = 256;
    private static final Map<Long, Visual> VISUALS = new LinkedHashMap<>();
    private static final LinkedHashSet<Long> RETIRED = new LinkedHashSet<>();
    private static final LinkedHashSet<String> IMPACTS = new LinkedHashSet<>();
    private static ClientLevel world;
    private static long ticks;
    private static int effects;
    private static int nextLocalId = -1;
    private static long lastMalformedLog;

    private PaperClientBallistics() { }

    private static final class Visual {
        final EntityKineticBullet bullet;
        final int life;
        long expiresAt;
        int serverAge;
        long terminalAt = -1;
        boolean renderedTerminal;
        boolean blocked;
        boolean renderedBlocked;

        Visual(EntityKineticBullet bullet, int life, int age) {
            this.bullet = bullet;
            this.life = life;
            this.serverAge = age;
            this.expiresAt = ticks + life - age + 2L;
        }
    }

    public static void receive(JsonObject batch) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !PaperClientBridge.active()) return;
        ensureWorld(level);
        if (!level.dimension().location().toString().equals(string(batch, "dimension", ""))) return;
        if (!batch.has("records") || !batch.get("records").isJsonArray()) return;
        var records = batch.getAsJsonArray("records");
        if (records.size() > BallisticsBatch.MAX_RECORDS) return;
        for (var entry : records) {
            try {
                if (!entry.isJsonObject()) continue;
                JsonObject record = entry.getAsJsonObject();
                long id = record.get("bullet").getAsLong();
                if (id < 0) continue;
                switch (string(record, "op", "")) {
                    case "spawn" -> spawn(level, id, record);
                    case "update" -> update(id, record);
                    case "end" -> end(id, record);
                    case "block_hit" -> blockHit(level, id, record);
                    case "entity_hit" -> entityHit(level, id, record);
                    default -> { }
                }
            } catch (RuntimeException malformed) {
                // A malformed cosmetic record must never disable the gameplay bridge.
                long now = System.currentTimeMillis();
                if (now - lastMalformedLog > 10_000) {
                    lastMalformedLog = now;
                    GunMod.LOGGER.warn("Ignoring malformed Paper ballistic visual: {}", malformed.toString());
                }
            }
        }
    }

    private static void spawn(ClientLevel level, long id, JsonObject record) {
        if (VISUALS.containsKey(id) || RETIRED.contains(id)) return;
        Vec3 position = position(record);
        Vec3 velocity = velocity(record);
        int life = boundedInt(record, "life", 1, 1200);
        int age = boundedInt(record, "age", 0, life);
        if (age >= life) return;
        float gravity = (float) bounded(record, "gravity", 0, 100);
        float friction = (float) bounded(record, "friction", 0, 1);
        ResourceLocation gun = resource(record, "id", null);
        ResourceLocation ammo = resource(record, "ammo", null);
        ResourceLocation display = resource(record, "display", DefaultAssets.DEFAULT_GUN_DISPLAY_ID);
        Entity owner = trackedEntity(level, record, "entity", "ownerUuid");
        EntityKineticBullet bullet = EntityKineticBullet.createClientVisual(level, owner, ammo, gun, display,
                position, velocity, gravity, friction, life, age, bool(record, "tracer", false));
        // The entity is deliberately NOT inserted into ClientLevel. Its ID cannot replace a
        // real network entity, even if another mod or the server uses the same negative ID.
        bullet.setId(nextLocalId--);
        if (nextLocalId == Integer.MIN_VALUE) nextLocalId = -1;
        if (VISUALS.size() >= MAX_VISUALS) {
            Iterator<Map.Entry<Long, Visual>> oldest = VISUALS.entrySet().iterator();
            var removed = oldest.next();
            removed.getValue().bullet.discard();
            oldest.remove();
        }
        VISUALS.put(id, new Visual(bullet, life, age));
    }

    private static void update(long id, JsonObject record) {
        Visual visual = VISUALS.get(id);
        if (visual == null || visual.terminalAt >= 0) return;
        Vec3 position = position(record);
        Vec3 velocity = velocity(record);
        int age = boundedInt(record, "age", 0, visual.life);
        if (age < visual.serverAge) return;
        visual.serverAge = age;
        visual.expiresAt = ticks + visual.life - age + 2L;
        visual.blocked = false;
        visual.renderedBlocked = false;
        EntityKineticBullet bullet = visual.bullet;
        bullet.setPos(position);
        bullet.setDeltaMovement(velocity);
        bullet.tickCount = age;
        // Do not interpolate a large correction through intervening blocks.
        if (new Vec3(bullet.xo, bullet.yo, bullet.zo).distanceToSqr(position) > 16) copyPrevious(bullet);
    }

    private static void end(long id, JsonObject record) {
        String reason = string(record, "reason", "hit");
        // Interest loss is reversible: a later spawn can carry the same live bullet ID.
        if (!reason.equals("hidden")) remember(RETIRED, id);
        Visual visual = VISUALS.get(id);
        if (visual == null || visual.terminalAt >= 0) return;
        if (!reason.equals("hit") && !reason.equals("expired")) {
            visual.bullet.discard();
            VISUALS.remove(id);
            return;
        }
        Vec3 end = position(record);
        EntityKineticBullet bullet = visual.bullet;
        // A spawn and impact can arrive in the same batch. Preserve their segment for one
        // rendered frame instead of deleting the projectile before it can ever be seen.
        Vec3 prior = new Vec3(bullet.xo, bullet.yo, bullet.zo);
        Vec3 direction = bullet.getDeltaMovement();
        Vec3 segment = end.subtract(prior);
        if (segment.dot(direction) < 0) {
            // Prediction has passed the impact: clamp both interpolation endpoints to the
            // authoritative impact side, never draw an interpolated trail through the wall.
            Vec3 start = direction.lengthSqr() > 1.0e-10 ? end.subtract(direction.normalize().scale(Math.min(.2, direction.length()))) : end;
            bullet.xo = start.x; bullet.yo = start.y; bullet.zo = start.z;
            bullet.xOld = start.x; bullet.yOld = start.y; bullet.zOld = start.z;
        }
        bullet.setPos(end);
        bullet.tickCount = Math.max(5, bullet.tickCount); // Keep the native near-muzzle filter from hiding an entire short shot.
        visual.blocked = false;
        visual.terminalAt = ticks;
    }

    private static void blockHit(ClientLevel level, long id, JsonObject record) {
        Vec3 point = position(record);
        BlockPos block = new BlockPos(boundedInt(record, "bx", -30_000_000, 30_000_000),
                boundedInt(record, "by", -2048, 2048), boundedInt(record, "bz", -30_000_000, 30_000_000));
        Direction face = Direction.valueOf(string(record, "face", "").toUpperCase(Locale.ROOT));
        String key = id + ":block:" + block.asLong() + ":" + face;
        if (!acceptEffect(key) || !level.hasChunkAt(block)) return;
        ResourceLocation ammo = resource(record, "ammo", null);
        ResourceLocation gun = resource(record, "id", null);
        ResourceLocation display = resource(record, "display", DefaultAssets.DEFAULT_GUN_DISPLAY_ID);
        var state = level.getBlockState(block);
        if (state.isAir()) return;
        level.addParticle(new BulletHoleOption(face, block, ammo.toString(), gun.toString(), display.toString()), point.x, point.y, point.z, 0, 0, 0);
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 outside = point.add(normal.scale(.01));
        for (int i = 0; i < 5; i++) {
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), outside.x, outside.y, outside.z,
                    normal.x * .05 + level.random.nextGaussian() * .025,
                    normal.y * .05 + level.random.nextGaussian() * .025,
                    normal.z * .05 + level.random.nextGaussian() * .025);
        }
        var sound = state.getSoundType();
        level.playLocalSound(point.x, point.y, point.z, sound.getHitSound(), SoundSource.BLOCKS,
                Math.min(1f, (sound.getVolume() + 1) / 8), sound.getPitch() * .8f, false);
        if (bool(record, "ignite", false)) level.addParticle(ParticleTypes.LAVA, point.x, point.y, point.z, 0, 0, 0);
    }

    private static void entityHit(ClientLevel level, long id, JsonObject record) {
        int targetId = boundedInt(record, "target", 0, Integer.MAX_VALUE);
        if (!acceptEffect(id + ":entity:" + targetId)) return;
        Entity target = trackedEntity(level, record, "target", "targetUuid");
        Entity shooter = trackedEntity(level, record, "entity", "ownerUuid");
        LivingEntity attacker = shooter instanceof LivingEntity living ? living : null;
        ResourceLocation gun = resource(record, "id", null);
        ResourceLocation display = resource(record, "display", DefaultAssets.DEFAULT_GUN_DISPLAY_ID);
        float amount = (float) bounded(record, "damage", 0, 1_000_000_000);
        boolean headshot = bool(record, "headshot", false);
        float multiplier = (float) bounded(record, "headshotMultiplier", 0, 1000);
        float base = headshot && multiplier > 0 ? amount / multiplier : amount;
        float appliedMultiplier = headshot ? multiplier : 1;
        if (bool(record, "ignite", false)) {
            Vec3 hitPoint = position(record);
            Vec3 flame = target == null ? hitPoint : target.getEyePosition();
            level.addParticle(ParticleTypes.LAVA, flame.x, flame.y, flame.z, 0, 0, 0);
        }
        Visual visual = VISUALS.get(id);
        Entity bullet = visual == null ? null : visual.bullet;
        if (bool(record, "kill", false)) {
            NeoForge.EVENT_BUS.post(new EntityKillByGunEvent(bullet, target instanceof LivingEntity living ? living : null,
                    attacker, gun, display, base, null, headshot, appliedMultiplier, LogicalSide.CLIENT));
        } else {
            NeoForge.EVENT_BUS.post(new EntityHurtByGunEvent.Post(bullet, target, attacker, gun, display,
                    base, null, headshot, appliedMultiplier, LogicalSide.CLIENT));
            // The server can confirm a distant hit whose victim is outside entity tracking.
            // Preserve the local shooter's feedback without inventing a target entity.
            if (target == null && attacker != null && attacker == Minecraft.getInstance().player) {
                RenderCrosshairEvent.markHitTimestamp();
                if (headshot) RenderCrosshairEvent.markHeadShotTimestamp();
                TimelessAPI.getGunDisplay(display, gun).ifPresent(index -> {
                    if (headshot) SoundPlayManager.playHeadHitSound(attacker, index);
                    else SoundPlayManager.playFleshHitSound(attacker, index);
                });
            }
        }
    }

    private static boolean acceptEffect(String key) {
        if (IMPACTS.contains(key)) return false;
        remember(IMPACTS, key);
        return effects++ < MAX_EFFECTS_PER_TICK;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != world || !PaperClientBridge.active()) { reset(); world = level; }
        if (level == null) return;
        ticks++;
        effects = 0;
        Iterator<Map.Entry<Long, Visual>> iterator = VISUALS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Visual visual = entry.getValue();
            EntityKineticBullet bullet = visual.bullet;
            boolean terminalExpired = visual.terminalAt >= 0 && (visual.renderedTerminal || ticks - visual.terminalAt > 4);
            if (terminalExpired || bullet.isRemoved() || ticks > visual.expiresAt) {
                bullet.discard(); iterator.remove();
            } else if (visual.terminalAt < 0) {
                // The manager owns visual lifetime so a late valid server correction can
                // rewind predicted age during the small expiry grace window.
                if (visual.blocked || bullet.tickCount >= visual.life) continue;
                copyPrevious(bullet);
                Vec3 start = bullet.position();
                Vec3 end = start.add(bullet.getDeltaMovement());
                var hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bullet));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    // Cosmetic prediction only: never damage a block/entity or emit an impact.
                    // Wait for Paper's terminal record (or correction) at the near side of the wall.
                    bullet.setPos(hit.getLocation());
                    bullet.tickCount = Math.max(5, bullet.tickCount);
                    visual.blocked = true;
                    continue;
                }
                bullet.tickCount++;
                bullet.tick();
            }
        }
    }

    @SubscribeEvent
    public static void render(ExtractLevelRenderStateEvent event) {
        if (VISUALS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world || !PaperClientBridge.active()) { reset(); return; }
        var dispatcher = mc.getEntityRenderDispatcher();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Visual visual : VISUALS.values()) {
            EntityKineticBullet bullet = visual.bullet;
            if (bullet.isRemoved() || visual.blocked && visual.renderedBlocked
                    || !dispatcher.shouldRender(bullet, event.getFrustum(), camera.x, camera.y, camera.z)) continue;
            event.getRenderState().entityRenderStates.add(dispatcher.extractEntity(bullet, partial));
            if (visual.terminalAt >= 0) visual.renderedTerminal = true;
            if (visual.blocked) visual.renderedBlocked = true;
        }
    }

    private static void copyPrevious(EntityKineticBullet bullet) {
        bullet.xo = bullet.getX(); bullet.yo = bullet.getY(); bullet.zo = bullet.getZ();
        bullet.xOld = bullet.getX(); bullet.yOld = bullet.getY(); bullet.zOld = bullet.getZ();
        bullet.xRotO = bullet.getXRot(); bullet.yRotO = bullet.getYRot();
    }

    private static void ensureWorld(ClientLevel level) {
        if (world != level) { reset(); world = level; }
    }

    public static void reset() {
        VISUALS.values().forEach(visual -> visual.bullet.discard());
        VISUALS.clear(); RETIRED.clear(); IMPACTS.clear();
        world = null; ticks = 0; effects = 0; nextLocalId = -1;
    }

    private static <T> void remember(LinkedHashSet<T> set, T value) {
        set.add(value);
        if (set.size() > MAX_REMEMBERED) { var first = set.iterator(); first.next(); first.remove(); }
    }

    @Nullable
    private static Entity trackedEntity(ClientLevel level, JsonObject record, String idField, String uuidField) {
        int id = boundedInt(record, idField, 0, Integer.MAX_VALUE);
        Entity entity = level.getEntity(id);
        String uuid = string(record, uuidField, "");
        if (entity != null && !uuid.isEmpty() && !entity.getUUID().equals(UUID.fromString(uuid))) return null;
        return entity;
    }

    private static Vec3 position(JsonObject record) {
        return new Vec3(bounded(record, "x", -30_000_000, 30_000_000), bounded(record, "y", -2048, 2048), bounded(record, "z", -30_000_000, 30_000_000));
    }

    private static Vec3 velocity(JsonObject record) {
        Vec3 value = new Vec3(bounded(record, "vx", -1024, 1024), bounded(record, "vy", -1024, 1024), bounded(record, "vz", -1024, 1024));
        if (value.lengthSqr() > 1024 * 1024) throw new IllegalArgumentException("Invalid projectile speed");
        return value;
    }

    private static int boundedInt(JsonObject record, String key, int min, int max) {
        double value = bounded(record, key, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException("Expected integer " + key);
        return (int) value;
    }

    private static double bounded(JsonObject record, String key, double min, double max) {
        double value = record.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private static ResourceLocation resource(JsonObject record, String key, @Nullable ResourceLocation fallback) {
        String value = string(record, key, fallback == null ? "" : fallback.toString());
        if (value.length() > 256) throw new IllegalArgumentException("Invalid resource " + key);
        ResourceLocation result = ResourceLocation.tryParse(value);
        if (result == null) throw new IllegalArgumentException("Invalid resource " + key);
        return result;
    }
}
