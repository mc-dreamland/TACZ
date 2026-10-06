package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;
import com.tacz.guns.paper.network.BridgePeer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import java.util.*;
import static com.tacz.guns.paper.gameplay.GunMath.*;

/** Server-side swept-segment projectiles. Visuals are sent separately to modded clients. */
final class PaperBallistics implements Listener {
    private static final double VISUAL_RANGE_SQUARED = 128 * 128;
    private final JavaPlugin plugin;
    private final BridgePeer peer;
    private final Set<Bullet> bullets = new LinkedHashSet<>();
    private final Map<World, List<Player>> worldPlayers = new HashMap<>();
    private HitboxProfiles hitboxProfiles;
    private DamageContext activeDamage;

    PaperBallistics(JavaPlugin plugin, BridgePeer peer) {
        this.plugin = plugin;
        this.peer = peer;
        hitboxProfiles = HitboxProfiles.load(plugin.getConfig());
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void hitboxProfiles(HitboxProfiles profiles) { this.hitboxProfiles = Objects.requireNonNull(profiles); }

    void fire(Player shooter, JsonObject state, JsonObject gun, double aimingProgress, boolean crawling) {
        JsonObject data = object(gun, "bullet").deepCopy();
        int pellets = (int) clamp(integer(data, "bullet_amount", 1), 1, 128);
        double speed = clamp(number(data, "speed", 5) / 20, .01, 100);
        JsonObject inaccuracies = object(gun, "inaccuracy");
        String posture = crawling ? "lie" : shooter.isSneaking() ? "sneak" : shooter.getVelocity().lengthSquared() > .003 ? "move" : "stand";
        double spread = clamp(number(inaccuracies, posture, 1), 0, 90);
        spread += (number(inaccuracies, "aim", spread) - spread) * clamp(aimingProgress, 0, 1);
        JsonObject heat = object(gun, "heat");
        double ratio = clamp(number(state, "heat", 0) / Math.max(1, number(heat, "max", 100)), 0, 1);
        spread *= number(heat, "min_inaccuracy", 1) + ratio * (number(heat, "max_inaccuracy", 1) - number(heat, "min_inaccuracy", 1));
        Location eye = shooter.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < .0001) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        Random random = new Random();
        for (int i = 0; i < pellets; i++) {
            if (bullets.size() >= plugin.getConfig().getInt("gameplay.max-active-bullets", 8192)) break;
            Vector direction;
            if (text(gun, "script", "").equals("tacz:sp_spread_logic")) {
                double angle = i / 10.0 * Math.PI * 2;
                direction = forward.clone().multiply(8).add(right.clone().multiply(Math.cos(angle))).add(up.clone().multiply(Math.sin(angle))).normalize();
            } else {
                double radius = Math.tan(Math.toRadians(spread)) * Math.sqrt(random.nextDouble());
                double angle = random.nextDouble() * Math.PI * 2;
                direction = forward.clone().add(right.clone().multiply(Math.cos(angle) * radius)).add(up.clone().multiply(Math.sin(angle) * radius)).normalize();
            }
            Vector inherited = shooter.getVelocity().clone();
            if (shooter.isOnGround()) inherited.setY(0);
            Bullet bullet = new Bullet(shooter, state, text(gun, "ammo", ""), data, eye, direction.multiply(speed).add(inherited), pellets);
            bullets.add(bullet);
            refreshObservers(bullet, shooter);
        }
    }

    void tick() {
        // Damage can synchronously dispatch death/quit hooks which reset another shooter's bullets.
        worldPlayers.clear();
        try {
            for (Bullet bullet : List.copyOf(bullets)) if (bullets.contains(bullet)) advance(bullet);
        } finally {
            worldPlayers.clear();
        }
    }
    void reset(Player player) {
        for (Bullet bullet : List.copyOf(bullets)) if (bullet.owner.equals(player.getUniqueId())) finish(bullet, "reset");
    }
    void shutdown() {
        for (Bullet bullet : List.copyOf(bullets)) finish(bullet, "shutdown");
        HandlerList.unregisterAll(this);
    }

    private void advance(Bullet b) {
        Player shooter = Bukkit.getPlayer(b.owner);
        if (shooter == null || !shooter.isOnline() || shooter.isDead() || !shooter.getWorld().equals(b.world)) { finish(b, "owner_unavailable"); return; }
        if (b.age >= b.life) { finish(b, "expired"); return; }
        if (!b.world.isChunkLoaded(b.position.getBlockX() >> 4, b.position.getBlockZ() >> 4)) { finish(b, "unloaded"); return; }
        ++b.age;
        JsonObject explosion = object(b.data, "explosion");
        boolean explosive = flag(explosion, "explode", false);
        if (explosive && b.age >= number(explosion, "delay", 30) * 20) { explode(b, shooter, b.position); finish(b, "hit"); return; }
        double distance = b.velocity.length();
        if (distance < 1.0e-6) { finish(b, "stopped"); return; }
        Vector direction = b.velocity.clone().normalize();
        Location start = b.position.clone();
        Location end = start.clone().add(b.velocity);
        // Never load terrain in response to a client action.
        if (!b.world.isChunkLoaded(end.getBlockX() >> 4, end.getBlockZ() >> 4)) { finish(b, "unloaded"); return; }
        for (double step = 8; step < distance; step += 8) {
            Location crossed = start.clone().add(direction.clone().multiply(step));
            if (!b.world.isChunkLoaded(crossed.getBlockX() >> 4, crossed.getBlockZ() >> 4)) { finish(b, "unloaded"); return; }
        }
        RayTraceResult blockHit = b.world.rayTraceBlocks(start, direction, distance, FluidCollisionMode.NEVER, true);
        double available = blockHit == null ? distance : blockHit.getHitPosition().distance(start.toVector());
        double advanced = 0;
        while (advanced < available + 1.0e-5) {
            EntityHit hit = closestHit(b, start.toVector().add(direction.clone().multiply(advanced)), direction, Math.max(0, available - advanced));
            if (hit == null) break;
            LivingEntity target = hit.target;
            b.hit.add(target.getUniqueId());
            Location point = hit.zone.point().toLocation(b.world);
            b.position = point;
            JsonObject extra = object(b.data, "extra_damage");
            double regionMultiplier = HitRegionDamage.multiplier(b.data, hit.zone.region());
            double amount = damageAt(b.data, point.distance(b.origin), b.pellets) * regionMultiplier;
            boolean damaged = damage(shooter, target, amount, number(extra, "armor_ignore", 0), point, number(b.data, "knockback", 0), direction);
            boolean ignited = damaged && igniteEntity(shooter, target, b.data);
            // Resolve direct-hit explosions before deciding whether the confirmation is a kill,
            // matching the original mod's impact feedback ordering.
            if (explosive && !b.ended) explode(b, shooter, point);
            if (damaged) entityHit(b, shooter, target, point, amount, hit.zone.region(), regionMultiplier, ignited);
            // A damage callback can synchronously reset this shooter's projectiles.
            if (b.ended) return;
            if (explosive) { finish(b, "hit"); return; }
            if (--b.pierce <= 0) { b.position = point; finish(b, "hit"); return; }
            advanced = hit.zone.point().distance(start.toVector()) + .001;
        }
        if (blockHit != null) {
            Location point = blockHit.getHitPosition().toLocation(b.world);
            b.position = point;
            if (explosive) explode(b, shooter, point);
            else {
                JsonObject ignite = object(b.data, "ignite");
                JsonObject hit = record(b, "block_hit"); coordinates(hit, "", point);
                Block block = blockHit.getHitBlock();
                hit.addProperty("bx", block.getX()); hit.addProperty("by", block.getY()); hit.addProperty("bz", block.getZ());
                hit.addProperty("face", blockHit.getHitBlockFace().name()); hit.addProperty("ignite", flag(ignite, "block", false));
                sendImpact(b, shooter, point, hit, null);
                if (flag(ignite, "block", false) && plugin.getConfig().getBoolean("gameplay.ignite-blocks", false)) {
                    Block fire = blockHit.getHitBlock().getRelative(blockHit.getHitBlockFace());
                    if (fire.getType().isAir() && !fire.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isAir()) {
                        BlockIgniteEvent igniteEvent = new BlockIgniteEvent(fire, BlockIgniteEvent.IgniteCause.ARROW, shooter);
                        Bukkit.getPluginManager().callEvent(igniteEvent);
                        if (!igniteEvent.isCancelled()) fire.setType(Material.FIRE);
                    }
                }
            }
            finish(b, "hit"); return;
        }
        b.position = end;
        boolean water = isWater(end);
        b.velocity.multiply(1 - (water ? .4 : b.friction));
        b.velocity.setY(b.velocity.getY() - b.gravity * (water ? .6 : 1));
        boolean mediumChanged = b.water != water;
        b.water = water;
        if (b.age >= b.life) { finish(b, "expired"); return; }
        refreshObservers(b, shooter);
        // Clients integrate the same motion locally. Only media transitions and long-lived
        // projectiles need an authoritative correction, never one trace per server tick.
        if (mediumChanged || b.age % 20 == 0) sendObservers(b, snapshot(b, "update"));
    }

    /** Broad-phase uses native boxes; horizontal players are added because their model extends beyond that box. */
    private EntityHit closestHit(Bullet bullet, Vector origin, Vector direction, double distance) {
        Vector end = origin.clone().add(direction.clone().multiply(distance));
        BoundingBox query = BoundingBox.of(origin, end).expand(.015);
        Set<Entity> candidates = new LinkedHashSet<>(bullet.world.getNearbyEntities(query));
        for (Player player : playersInWorld(bullet.world)) if (HitZones.horizontal(player)) candidates.add(player);
        EntityHit closest = null;
        for (Entity entity : candidates) {
            if (!(entity instanceof LivingEntity target) || entity.getUniqueId().equals(bullet.owner)
                    || bullet.hit.contains(entity.getUniqueId()) || !bullet.world.equals(entity.getWorld()) || !entity.isValid() || entity.isDead()
                    || entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) continue;
            HitZones.Hit zone = HitZones.trace(target, origin, direction, distance, hitboxProfiles.forEntity(target));
            if (zone != null && (closest == null || zone.distance() < closest.zone.distance()
                    || zone.distance() == closest.zone.distance() && target.getUniqueId().compareTo(closest.target.getUniqueId()) < 0))
                closest = new EntityHit(target, zone);
        }
        return closest;
    }

    private record EntityHit(LivingEntity target, HitZones.Hit zone) { }

    private void refreshObservers(Bullet b, Player shooter) {
        Iterator<UUID> existing = b.observers.iterator();
        while (existing.hasNext()) {
            Player observer = Bukkit.getPlayer(existing.next());
            if (visible(b, shooter, observer, b.position)) continue;
            if (canReceive(b, observer)) peer.ballistics(observer, endRecord(b, "hidden"));
            existing.remove();
        }
        for (Player observer : playersInWorld(b.world)) {
            if (!b.observers.contains(observer.getUniqueId()) && visible(b, shooter, observer, b.position)) {
                peer.ballistics(observer, snapshot(b, "spawn"));
                b.observers.add(observer.getUniqueId());
            }
        }
    }

    private boolean canReceive(Bullet b, Player player) {
        return player != null && player.isOnline() && b.world.equals(player.getWorld()) && peer.ready(player);
    }

    private List<Player> playersInWorld(World world) {
        return worldPlayers.computeIfAbsent(world, key -> List.copyOf(key.getPlayers()));
    }

    private boolean visible(Bullet b, Player shooter, Player observer, Location point) {
        return canReceive(b, observer) && (observer.equals(shooter) || observer.canSee(shooter))
                && observer.getLocation().distanceSquared(point) <= VISUAL_RANGE_SQUARED;
    }

    private void sendObservers(Bullet b, JsonObject record) {
        for (UUID id : b.observers) {
            Player observer = Bukkit.getPlayer(id);
            if (canReceive(b, observer)) peer.ballistics(observer, record);
        }
    }

    private void entityHit(Bullet b, Player shooter, LivingEntity target, Location point, double amount, HitZones.Region region, double multiplier, boolean ignited) {
        JsonObject hit = record(b, "entity_hit"); coordinates(hit, "", point);
        hit.addProperty("target", target.getEntityId()); hit.addProperty("targetUuid", target.getUniqueId().toString());
        hit.addProperty("headshot", region == HitZones.Region.HEAD);
        hit.addProperty("headshotMultiplier", HitRegionDamage.multiplier(b.data, HitZones.Region.HEAD));
        hit.addProperty("hitRegion", region.id()); hit.addProperty("regionMultiplier", multiplier);
        hit.addProperty("kill", target.isDead()); hit.addProperty("damage", amount);
        hit.addProperty("ignite", ignited);
        sendImpact(b, shooter, point, hit, target instanceof Player victim ? victim : null);
    }

    private void sendImpact(Bullet b, Player shooter, Location point, JsonObject record, Player victim) {
        Set<UUID> sent = new HashSet<>();
        for (Player observer : playersInWorld(b.world)) if (visible(b, shooter, observer, point)) {
            peer.ballistics(observer, record); sent.add(observer.getUniqueId());
        }
        // A distant sniper still needs hit markers, and the victim needs the original hurt event.
        // Block effects are spatial only; entity feedback additionally reaches these two players.
        if (record.get("op").getAsString().equals("entity_hit")) {
            for (Player recipient : new Player[]{shooter, victim}) if (canReceive(b, recipient) && sent.add(recipient.getUniqueId())) peer.ballistics(recipient, record);
        }
    }

    private void finish(Bullet b, String reason) {
        if (b.ended) return;
        b.ended = true;
        // Notify the original recipients even when they have already moved out of range.
        sendObservers(b, endRecord(b, reason));
        b.observers.clear();
        bullets.remove(b);
    }

    private JsonObject endRecord(Bullet b, String reason) {
        JsonObject end = record(b, "end"); coordinates(end, "", b.position); end.addProperty("reason", reason); return end;
    }

    private JsonObject snapshot(Bullet b, String op) {
        JsonObject snapshot = record(b, op); coordinates(snapshot, "", b.position);
        snapshot.addProperty("vx", b.velocity.getX()); snapshot.addProperty("vy", b.velocity.getY()); snapshot.addProperty("vz", b.velocity.getZ());
        snapshot.addProperty("age", b.age); snapshot.addProperty("water", b.water);
        if (op.equals("spawn")) {
            snapshot.addProperty("life", b.life); snapshot.addProperty("gravity", b.gravity); snapshot.addProperty("friction", b.friction);
            snapshot.addProperty("tracer", b.tracer);
        }
        return snapshot;
    }

    boolean damage(Player shooter, LivingEntity target, double amount, double armorIgnore, Location point, double knockback, Vector direction) {
        return damage(shooter, target, amount, armorIgnore, point, DamageType.ARROW, knockback, direction);
    }

    boolean damage(Player shooter, LivingEntity target, double amount, double armorIgnore, Location point, DamageType type, double knockback, Vector direction) {
        if (amount <= 0 || target.isInvulnerable() || !canHurt(shooter, target)) return false;
        DamageContext previous = activeDamage;
        DamageContext context = new DamageContext(target, clamp(armorIgnore, 0, 1));
        activeDamage = context;
        int invulnerability = target.getNoDamageTicks();
        Vector oldVelocity = target.getVelocity();
        try {
            target.setNoDamageTicks(0);
            target.damage(amount, DamageSource.builder(type).withCausingEntity(shooter).withDirectEntity(shooter).withDamageLocation(point).build());
        } finally {
            activeDamage = previous;
            if (!context.accepted) target.setNoDamageTicks(invulnerability);
        }
        if (context.accepted && target.isValid()) {
            // Gun knockback replaces the vanilla hurt impulse, as in the mod.
            Vector velocity = oldVelocity.clone().add(direction.clone().normalize().multiply(Math.max(0, knockback)));
            target.setVelocity(velocity);
        }
        return context.accepted;
    }

    private boolean canHurt(Player shooter, LivingEntity target) {
        if (!(target instanceof Player victim)) return true;
        if (victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) return false;
        if (victim.equals(shooter)) return true;
        if (!victim.getWorld().getPVP()) return false;
        Team team = shooter.getScoreboard().getEntryTeam(shooter.getName());
        return team == null || team.allowFriendlyFire() || !team.hasEntry(victim.getName());
    }

    boolean isManagedDamage(EntityDamageByEntityEvent event) {
        return activeDamage != null && activeDamage.target == event.getEntity();
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void armor(EntityDamageByEntityEvent event) {
        if (activeDamage == null || activeDamage.target != event.getEntity()) return;
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) {
            event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, event.getDamage(EntityDamageEvent.DamageModifier.ARMOR) * (1 - activeDamage.armorIgnore));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void damageResult(EntityDamageByEntityEvent event) {
        if (activeDamage != null && activeDamage.target == event.getEntity()) activeDamage.accepted = !event.isCancelled() && event.getFinalDamage() > 0;
    }

    private boolean igniteEntity(Player shooter, LivingEntity target, JsonObject data) {
        boolean enabled = data.has("ignite") && (data.get("ignite").isJsonPrimitive() ? flag(data, "ignite", false) : flag(object(data, "ignite"), "entity", false));
        if (!enabled || !plugin.getConfig().getBoolean("gameplay.ignite-entities", true)) return false;
        EntityCombustByEntityEvent event = new EntityCombustByEntityEvent(shooter, target, (float) number(data, "ignite_entity_time", 2));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        target.setFireTicks(Math.max(target.getFireTicks(), (int) (event.getDuration() * 20)));
        return true;
    }

    private void explode(Bullet b, Player shooter, Location point) {
        JsonObject explosion = object(b.data, "explosion");
        double radius = clamp(number(explosion, "radius", 3), .1, 16);
        double amount = Math.max(0, number(explosion, "damage", 3));
        boolean destroy = flag(explosion, "destroy_block", false) && plugin.getConfig().getBoolean("gameplay.explosion-block-damage", false);
        List<Block> blocks = new ArrayList<>();
        if (destroy) {
            int r = (int) Math.ceil(radius);
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                if (x*x + y*y + z*z > radius*radius) continue;
                Location position = point.clone().add(x, y, z);
                if (position.getY() < b.world.getMinHeight() || position.getY() >= b.world.getMaxHeight()
                        || !b.world.isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) continue;
                Block block = position.getBlock();
                if (!block.getType().isAir() && block.getType().getBlastResistance() < 100 && block.getType().getHardness() >= 0) blocks.add(block);
            }
        }
        EntityExplodeEvent event = new EntityExplodeEvent(shooter, point, blocks, .3f, destroy ? ExplosionResult.DESTROY : ExplosionResult.KEEP);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        for (Entity entity : b.world.getNearbyEntities(point, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target)) continue;
            Vector offset = target.getEyeLocation().toVector().subtract(point.toVector());
            double length = offset.length();
            if (length > radius) continue;
            if (length > .01 && b.world.rayTraceBlocks(point, offset.clone().normalize(), length, FluidCollisionMode.NEVER, true) != null) continue;
            if (length < .01) offset = new Vector(0, 1, 0);
            double exposure = 1 - length / radius;
            damage(shooter, target, amount * exposure, 0, point, DamageType.PLAYER_EXPLOSION,
                    flag(explosion, "knockback", false) && plugin.getConfig().getBoolean("gameplay.explosion-knockback", true) ? exposure : 0, offset);
        }
        if (destroy) for (Block block : event.blockList()) {
            if (!blocks.contains(block) || block.getType().getHardness() < 0) continue;
            if (Math.random() < event.getYield()) block.breakNaturally(); else block.setType(Material.AIR);
        }
        b.world.spawnParticle(Particle.EXPLOSION, point, 1);
        b.world.playSound(point, Sound.ENTITY_GENERIC_EXPLODE, 1, 1);
    }

    private static void coordinates(JsonObject o, String prefix, Location p) {
        o.addProperty(prefix + "x", p.getX()); o.addProperty(prefix + "y", p.getY()); o.addProperty(prefix + "z", p.getZ());
    }
    private static boolean isWater(Location location) {
        Block block = location.getBlock();
        return block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN
                || block.getBlockData() instanceof Waterlogged data && data.isWaterlogged();
    }
    private JsonObject record(Bullet b, String op) {
        JsonObject o = new JsonObject(); o.addProperty("op", op); o.addProperty("bullet", b.id);
        o.addProperty("entity", b.shooterEntity); o.addProperty("ownerUuid", b.owner.toString());
        o.addProperty("id", b.gunId); o.addProperty("display", "tacz:default"); o.addProperty("ammo", b.ammoId); return o;
    }
    private static final class DamageContext {
        final LivingEntity target; final double armorIgnore; boolean accepted;
        DamageContext(LivingEntity target, double armorIgnore) { this.target = target; this.armorIgnore = armorIgnore; }
    }
    // Main-thread sequence deliberately survives command reloads and service reconstruction.
    private static long nextBullet;
    private static final class Bullet {
        final long id = ++nextBullet;
        final UUID owner; final World world; final Location origin; final JsonObject data; final String gunId, ammoId;
        final int shooterEntity, pellets, life; final double gravity, friction; final boolean tracer;
        final Set<UUID> hit = new HashSet<>(), observers = new LinkedHashSet<>();
        Location position; Vector velocity; int age, pierce; boolean water, ended;
        Bullet(Player shooter, JsonObject state, String ammoId, JsonObject data, Location origin, Vector velocity, int pellets) {
            owner = shooter.getUniqueId(); world = shooter.getWorld(); this.origin = origin.clone(); position = origin.clone(); this.velocity = velocity;
            this.data = data; this.pellets = pellets; life = (int) clamp(number(data, "life", 10) * 20, 1, 1200); pierce = (int) clamp(integer(data, "pierce", 1), 1, 64);
            gunId = text(state, "id", ""); this.ammoId = ammoId; shooterEntity = shooter.getEntityId();
            gravity = number(data, "gravity", 0); friction = clamp(number(data, "friction", .01), 0, 1);
            int interval = integer(data, "tracer_count_interval", -1);
            tracer = interval >= 0 && (id - 1) % ((long) interval + 1) == 0;
            water = world.isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4) && isWater(position);
        }
    }
}
