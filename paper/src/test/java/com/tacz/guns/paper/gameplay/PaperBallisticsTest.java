package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.paper.network.BridgePeer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperBallisticsTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void nonTracerStillSpawnsAndOrdinaryFlightHasOnlyOneCorrectionPerSecond() {
        try (Fixture f = new Fixture()) {
            f.fire();
            JsonObject spawn = f.only(f.shooter, "spawn");
            assertFalse(spawn.get("tracer").getAsBoolean());
            assertEquals("tacz:default", spawn.get("display").getAsString());
            assertEquals("tacz:762x39", spawn.get("ammo").getAsString());
            assertEquals(f.shooter.getUniqueId().toString(), spawn.get("ownerUuid").getAsString());
            assertEquals(200, spawn.get("life").getAsInt()); assertEquals(0, spawn.get("age").getAsInt());
            f.ticks(19);
            assertEquals(List.of("spawn"), f.ops(f.shooter));
            f.ticks(1);
            JsonObject update = f.only(f.shooter, "update");
            assertEquals(20, update.get("age").getAsInt()); assertEquals(20, update.get("z").getAsDouble(), 1.0e-9);
            assertEquals(1, update.get("vz").getAsDouble(), 1.0e-9);
            verify(f.peer, never()).send(any(), anyString(), any());
            verify(f.peer, never()).broadcast(any(), anyString(), any());
        }
    }

    @Test void mediumTransitionsCorrectVelocityButDoNotStreamEveryWaterTick() {
        try (Fixture f = new Fixture()) {
            f.gun.getAsJsonObject("bullet").addProperty("gravity", .1);
            f.fire(); when(f.block.getType()).thenReturn(Material.WATER);
            f.ticks(1);
            JsonObject wet = f.only(f.shooter, "update");
            assertTrue(wet.get("water").getAsBoolean());
            assertEquals(.6, wet.get("vz").getAsDouble(), 1.0e-9);
            assertEquals(-.06, wet.get("vy").getAsDouble(), 1.0e-9);
            f.ticks(2); assertEquals(1, f.records(f.shooter, "update").size());
            when(f.block.getType()).thenReturn(Material.AIR); f.ticks(1);
            assertEquals(2, f.records(f.shooter, "update").size());
            assertFalse(f.records(f.shooter, "update").getLast().get("water").getAsBoolean());
        }
    }

    @Test void lavaKeepsAirPhysicsWhileBubbleColumnsAndWaterloggedBlocksUseWaterPhysics() {
        try (Fixture f = new Fixture()) {
            f.fire(); when(f.block.getType()).thenReturn(Material.LAVA); f.ticks(20);
            JsonObject lava = f.only(f.shooter, "update");
            assertFalse(lava.get("water").getAsBoolean());
            assertEquals(1, lava.get("vz").getAsDouble(), 1.0e-9);
            assertEquals(20, lava.get("z").getAsDouble(), 1.0e-9);
            when(f.block.getType()).thenReturn(Material.BUBBLE_COLUMN); f.ticks(1);
            JsonObject bubbles = f.records(f.shooter, "update").getLast();
            assertTrue(bubbles.get("water").getAsBoolean()); assertEquals(.6, bubbles.get("vz").getAsDouble(), 1.0e-9);
        }
        try (Fixture f = new Fixture()) {
            Waterlogged data = mock(Waterlogged.class); when(data.isWaterlogged()).thenReturn(true);
            f.fire(); when(f.block.getType()).thenReturn(Material.OAK_STAIRS); when(f.block.getBlockData()).thenReturn(data); f.ticks(1);
            assertTrue(f.only(f.shooter, "update").get("water").getAsBoolean());
            when(data.isWaterlogged()).thenReturn(false); f.ticks(1);
            assertFalse(f.records(f.shooter, "update").getLast().get("water").getAsBoolean());
        }
    }

    @Test void audienceTracksProjectileAndReturningObserverGetsCurrentSnapshotOfSameBullet() {
        try (Fixture f = new Fixture()) {
            Player nearby = f.player(0), entering = f.player(200), hidden = f.player(0), unready = f.player(0), otherWorld = f.player(0);
            when(hidden.canSee(f.shooter)).thenReturn(false); when(f.peer.ready(unready)).thenReturn(false);
            f.positions.get(otherWorld.getUniqueId()).setWorld(mock(World.class));
            f.fire();
            assertEquals(1, f.records(nearby, "spawn").size());
            assertTrue(f.ops(entering).isEmpty()); assertTrue(f.ops(hidden).isEmpty());
            assertTrue(f.ops(unready).isEmpty()); assertTrue(f.ops(otherWorld).isEmpty());
            long bullet = f.only(nearby, "spawn").get("bullet").getAsLong();
            f.positions.get(nearby.getUniqueId()).setZ(300);
            f.positions.get(entering.getUniqueId()).setZ(2);
            f.ticks(1);
            assertEquals("hidden", f.only(nearby, "end").get("reason").getAsString());
            assertEquals(1, f.only(entering, "spawn").get("age").getAsInt());
            assertEquals(1, f.only(entering, "spawn").get("z").getAsDouble(), 1.0e-9);
            f.positions.get(nearby.getUniqueId()).setZ(2); f.ticks(1);
            assertEquals(List.of("spawn", "end", "spawn"), f.ops(nearby));
            JsonObject respawn = f.records(nearby, "spawn").getLast();
            assertEquals(bullet, respawn.get("bullet").getAsLong()); assertEquals(2, respawn.get("age").getAsInt());
            assertEquals(2, respawn.get("z").getAsDouble(), 1.0e-9);
        }
    }

    @Test void expirationResetAndShutdownEndExactlyOnceAndIdsSurviveReset() {
        try (Fixture f = new Fixture()) {
            f.gun.getAsJsonObject("bullet").addProperty("life", .1);
            f.fire(); f.ticks(3);
            assertEquals(List.of("spawn", "end"), f.ops(f.shooter));
            assertEquals("expired", f.only(f.shooter, "end").get("reason").getAsString());
            assertEquals(2, f.only(f.shooter, "end").get("z").getAsDouble(), 1.0e-9);
            long first = f.only(f.shooter, "spawn").get("bullet").getAsLong();
            f.events.clear(); f.fire();
            long second = f.only(f.shooter, "spawn").get("bullet").getAsLong(); assertTrue(second > first);
            f.service.reset(f.shooter); f.service.reset(f.shooter); f.ticks(1);
            assertEquals(List.of("spawn", "end"), f.ops(f.shooter));
            assertEquals("reset", f.only(f.shooter, "end").get("reason").getAsString());
            f.events.clear(); f.fire(); f.service.shutdown(); f.service.shutdown();
            assertEquals(List.of("spawn", "end"), f.ops(f.shooter));
            assertEquals("shutdown", f.only(f.shooter, "end").get("reason").getAsString());
        }
    }

    @Test void terminationNotifiesPreviousObserversEvenAfterTheyLeaveTheRadius() {
        try (Fixture f = new Fixture()) {
            Player observer = f.player(0); f.fire();
            f.positions.get(observer.getUniqueId()).setZ(1000);
            f.service.reset(f.shooter);
            assertEquals("reset", f.only(observer, "end").get("reason").getAsString());
        }
    }

    @Test void missingOwnerAndUnloadedTerrainStopSimulationWithoutLoadingChunks() {
        try (Fixture f = new Fixture()) {
            Player observer = f.player(0); f.fire(); f.online.remove(f.shooter.getUniqueId()); f.ticks(1);
            assertEquals("owner_unavailable", f.only(observer, "end").get("reason").getAsString());
            f.online.put(f.shooter.getUniqueId(), f.shooter); f.events.clear();
            f.fire(); when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); f.ticks(1);
            assertEquals("unloaded", f.only(observer, "end").get("reason").getAsString());
            verify(f.world, never()).getChunkAt(anyInt(), anyInt());
            verify(f.world, never()).rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean());
        }
    }

    @Test void blockImpactPreservesExactBlockCoordinatesAndOrdersSpawnImpactEnd() {
        try (Fixture f = new Fixture()) {
            Block struck = mock(Block.class);
            when(struck.getX()).thenReturn(0); when(struck.getY()).thenReturn(65); when(struck.getZ()).thenReturn(-1);
            // NORTH-side boundary is z=0, but the actual struck block is z=-1.
            RayTraceResult result = new RayTraceResult(new Vector(0, 65.62, 0), struck, BlockFace.SOUTH);
            when(f.world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean())).thenReturn(result);
            f.gun.getAsJsonObject("bullet").add("ignite", json("{block:true}"));
            f.fire(); f.ticks(1);
            assertEquals(List.of("spawn", "block_hit", "end"), f.ops(f.shooter));
            JsonObject hit = f.only(f.shooter, "block_hit");
            assertEquals(-1, hit.get("bz").getAsInt()); assertEquals(0, hit.get("z").getAsDouble(), 1.0e-9);
            assertEquals("SOUTH", hit.get("face").getAsString()); assertTrue(hit.get("ignite").getAsBoolean());
            assertEquals("hit", f.only(f.shooter, "end").get("reason").getAsString());
            verify(struck, never()).getBlockData();
            verify(f.peer, never()).broadcast(any(), anyString(), any());
        }
    }

    @Test void acceptedHeadshotReachesDistantShooterVictimAndNearbyObserversExactlyOnce() {
        try (Fixture f = new Fixture()) {
            Player victim = f.target(190), observer = f.player(180);
            when(victim.canSee(f.shooter)).thenReturn(false);
            f.gun.getAsJsonObject("bullet").addProperty("speed", 1000);
            f.fire(); f.ticks(4);
            assertEquals(2, f.only(observer, "spawn").get("age").getAsInt(), "Interest follows the bullet even when the shooter is over 128 blocks away");
            for (Player recipient : List.of(f.shooter, victim, observer)) {
                JsonObject hit = f.only(recipient, "entity_hit");
                assertTrue(hit.get("headshot").getAsBoolean()); assertFalse(hit.get("kill").getAsBoolean());
                assertEquals(16, hit.get("damage").getAsDouble(), 1.0e-9);
                assertEquals(2, hit.get("headshotMultiplier").getAsDouble(), 1.0e-9);
                assertEquals("head", hit.get("hitRegion").getAsString());
                assertEquals(2, hit.get("regionMultiplier").getAsDouble(), 1.0e-9);
                assertEquals(victim.getUniqueId().toString(), hit.get("targetUuid").getAsString());
                assertEquals(f.shooter.getUniqueId().toString(), hit.get("ownerUuid").getAsString());
            }
            assertEquals("hidden", f.only(f.shooter, "end").get("reason").getAsString());
            assertTrue(f.records(victim, "spawn").isEmpty(), "Victim hurt feedback does not expose a hidden shooter visual");
            verify(f.peer, never()).send(any(), anyString(), any());
        }
    }

    @Test void cancelledDamageHasNoEntityHitOrIgnitionButStillEndsTheCollidingBullet() {
        try (Fixture f = new Fixture()) {
            Player victim = f.target(.5);
            doReturn(false).when(f.service).damage(any(Player.class), any(LivingEntity.class), anyDouble(), anyDouble(), any(Location.class), anyDouble(), any(Vector.class));
            f.gun.getAsJsonObject("bullet").add("ignite", json("{entity:true}"));
            f.fire(); f.ticks(1);
            assertEquals(List.of("spawn", "end"), f.ops(f.shooter));
            assertTrue(f.records(victim, "entity_hit").isEmpty());
            verify(victim, never()).setFireTicks(anyInt());
        }
    }

    @Test void synchronousDamageResetCannotResurrectBulletOrSendDuplicateEnd() {
        try (Fixture f = new Fixture()) {
            f.target(.5);
            doAnswer(call -> { f.service.reset(f.shooter); return true; }).when(f.service).damage(any(Player.class), any(LivingEntity.class), anyDouble(), anyDouble(), any(Location.class), anyDouble(), any(Vector.class));
            f.fire(); f.ticks(3);
            assertEquals(1, f.records(f.shooter, "end").size()); assertEquals(1, f.records(f.shooter, "entity_hit").size());
            assertTrue(f.records(f.shooter, "update").isEmpty());
        }
    }

    @Test void geometricHeadTorsoAndLegHitsApplyTheirOwnDamageMultipliersAndReportRegion() {
        String[] regions = {"head", "torso", "legs"};
        double[] heights = {1.62, 1.1, .4}, multipliers = {2, .75, .5};
        for (int i = 0; i < regions.length; i++) {
            try (Fixture f = new Fixture()) {
                Player victim = f.target(5);
                f.gun.getAsJsonObject("bullet").getAsJsonObject("extra_damage")
                        .add("body_part_multipliers", json("{\"torso\":0.75,\"legs\":0.5}"));
                f.aim(0, 64 + heights[i], 0, new Vector(0, 0, 1));
                f.fire(); f.ticks(1);
                JsonObject hit = f.only(f.shooter, "entity_hit");
                assertEquals(regions[i], hit.get("hitRegion").getAsString());
                assertEquals(multipliers[i], hit.get("regionMultiplier").getAsDouble(), 1.0e-9);
                assertEquals(8 * multipliers[i], hit.get("damage").getAsDouble(), 1.0e-9);
                assertEquals(i == 0, hit.get("headshot").getAsBoolean());
                assertEquals(2, hit.get("headshotMultiplier").getAsDouble(), 1.0e-9,
                        "Legacy feedback retains the configured head multiplier for every region");
                verify(f.service).damage(eq(f.shooter), eq(victim), eq(8 * multipliers[i]), eq(0.0), any(Location.class), eq(0.0), any(Vector.class));
            }
        }
    }

    @Test void omittedBodyMultipliersPreserveExistingTorsoAndLegDamage() {
        for (double height : new double[]{1.1, .4}) {
            try (Fixture f = new Fixture()) {
                f.target(5);
                f.aim(0, 64 + height, 0, new Vector(0, 0, 1));
                f.fire(); f.ticks(1);
                JsonObject hit = f.only(f.shooter, "entity_hit");
                assertFalse(hit.get("headshot").getAsBoolean());
                assertEquals(1, hit.get("regionMultiplier").getAsDouble(), 1.0e-9);
                assertEquals(8, hit.get("damage").getAsDouble(), 1.0e-9);
            }
        }
    }

    @Test void sideAndRearHeadshotsUseTheSameGeometryAsFrontShots() {
        for (boolean side : new boolean[]{true, false}) {
            try (Fixture f = new Fixture()) {
                Player victim = f.target(5);
                when(victim.getBodyYaw()).thenReturn(0f); // Facing +Z: -X is the side and -Z is behind.
                f.aim(side ? -3 : 0, 65.62, side ? 5 : 2, side ? new Vector(1, 0, 0) : new Vector(0, 0, 1));
                f.fire(); f.ticks(1);
                JsonObject hit = f.only(f.shooter, "entity_hit");
                assertEquals("head", hit.get("hitRegion").getAsString());
                assertEquals(16, hit.get("damage").getAsDouble(), 1.0e-9);
                assertEquals(victim.getUniqueId().toString(), hit.get("targetUuid").getAsString());
            }
        }
    }

    @Test void wallClipsTheCandidateTraceBeforeAnyBodyRegionCanBeDamaged() {
        try (Fixture f = new Fixture()) {
            f.target(5);
            Block wall = mock(Block.class);
            when(wall.getX()).thenReturn(0); when(wall.getY()).thenReturn(65); when(wall.getZ()).thenReturn(3);
            RayTraceResult wallHit = new RayTraceResult(new Vector(0, 65.1, 3), wall, BlockFace.NORTH);
            when(f.world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean())).thenReturn(wallHit);
            f.aim(0, 65.1, 0, new Vector(0, 0, 1));
            f.fire(); f.ticks(1);
            assertEquals(List.of("spawn", "block_hit", "end"), f.ops(f.shooter));
            assertTrue(f.records(f.shooter, "entity_hit").isEmpty());
            verify(f.service, never()).damage(any(Player.class), any(LivingEntity.class), anyDouble(), anyDouble(), any(Location.class), anyDouble(), any(Vector.class));
        }
    }

    @Test void firstPhysicalTargetWinsEvenWhenTheWorldReturnsTheFarTargetFirst() {
        try (Fixture f = new Fixture()) {
            Player far = f.target(8), near = f.target(3);
            f.aim(0, 65.1, 0, new Vector(0, 0, 1));
            f.fire(); f.ticks(1);
            JsonObject hit = f.only(f.shooter, "entity_hit");
            assertEquals(near.getUniqueId().toString(), hit.get("targetUuid").getAsString());
            assertEquals("torso", hit.get("hitRegion").getAsString());
            verify(f.service, never()).damage(any(Player.class), eq(far), anyDouble(), anyDouble(), any(Location.class), anyDouble(), any(Vector.class));
            verify(f.service).damage(any(Player.class), eq(near), anyDouble(), anyDouble(), any(Location.class), anyDouble(), any(Vector.class));
        }
    }

    @Test void proneLegOutsideTheVanillaBoxIsStillFoundByThePlayerCandidatePass() {
        try (Fixture f = new Fixture()) {
            Player victim = f.target(5);
            when(victim.getPose()).thenReturn(Pose.SWIMMING);
            when(victim.isInWater()).thenReturn(false);
            when(victim.getBodyYaw()).thenReturn(0f);
            BoundingBox vanilla = new BoundingBox(-.3, 64, 4.7, .3, 64.6, 5.3);
            when(victim.getBoundingBox()).thenReturn(vanilla);
            f.aim(-2, 64.3, 4.3, new Vector(1, 0, 0));
            f.fire(); f.ticks(1);
            assertFalse(f.queries.isEmpty());
            assertTrue(f.queries.stream().noneMatch(query -> query.overlaps(vanilla)),
                    "This target must be absent from the native bounding-box candidate query");
            JsonObject hit = f.only(f.shooter, "entity_hit");
            assertEquals(victim.getUniqueId().toString(), hit.get("targetUuid").getAsString());
            assertEquals("legs", hit.get("hitRegion").getAsString());
            assertFalse(hit.get("headshot").getAsBoolean());
            assertEquals(8, hit.get("damage").getAsDouble(), 1.0e-9);
        }
    }

    private record Packet(UUID recipient, JsonObject record) {}

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final BridgePeer peer = mock(BridgePeer.class);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Map<UUID, Player> online = new HashMap<>();
        final Map<UUID, Location> positions = new HashMap<>();
        final List<Player> players = new ArrayList<>();
        final List<BoundingBox> queries = new ArrayList<>();
        final List<Packet> events = new ArrayList<>();
        final JsonObject gun = json("{ammo:'tacz:762x39',inaccuracy:{stand:0,aim:0},bullet:{speed:20,life:10,friction:0,gravity:0,damage:8,extra_damage:{head_shot_multiplier:2}}}");
        final Player shooter;
        final PaperBallistics service;

        Fixture() {
            try {
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
                when(plugin.getConfig()).thenReturn(new YamlConfiguration());
                when(world.getPlayers()).thenReturn(players);
                when(world.getNearbyEntities(any(BoundingBox.class))).thenAnswer(call -> {
                    BoundingBox query = call.getArgument(0);
                    queries.add(query.clone());
                    return players.stream().filter(player -> player.getWorld() == world && query.overlaps(player.getBoundingBox())).toList();
                });
                when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
                when(world.getBlockAt(any(Location.class))).thenReturn(block);
                when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
                when(block.getType()).thenReturn(Material.AIR);
                doAnswer(call -> {
                    Player player = call.getArgument(0); JsonObject data = call.getArgument(1);
                    events.add(new Packet(player.getUniqueId(), data.deepCopy())); return null;
                }).when(peer).ballistics(any(Player.class), any(JsonObject.class));
                shooter = player(0);
                positions.get(shooter.getUniqueId()).setX(0);
                service = spy(new PaperBallistics(plugin, peer));
                // Preserve the real collision and accepted/cancelled feedback paths while replacing
                // the external Bukkit damage pipeline, which needs live server registries.
                doReturn(true).when(service).damage(any(Player.class), any(LivingEntity.class), anyDouble(), anyDouble(), any(Location.class), anyDouble(), any(Vector.class));
            } catch (RuntimeException | Error failure) {
                bukkit.close(); throw failure;
            }
        }

        Player player(double z) {
            Player player = mock(Player.class); UUID id = UUID.randomUUID();
            // Observers stand beside the firing lane. Tests opt targets into the lane explicitly.
            positions.put(id, new Location(world, 8, 64, z)); online.put(id, player); players.add(player);
            when(player.getUniqueId()).thenReturn(id); when(player.getEntityId()).thenReturn(players.size());
            when(player.isOnline()).thenReturn(true); when(player.isOnGround()).thenReturn(true);
            when(player.isValid()).thenReturn(true); when(player.getType()).thenReturn(EntityType.PLAYER);
            when(player.getPose()).thenReturn(Pose.STANDING);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getWorld()).thenAnswer(call -> positions.get(id).getWorld());
            when(player.getLocation()).thenAnswer(call -> positions.get(id).clone());
            when(player.getEyeLocation()).thenAnswer(call -> positions.get(id).clone().add(0, 1.62, 0));
            when(player.getBoundingBox()).thenAnswer(call -> {
                Location location = positions.get(id);
                return new BoundingBox(location.getX() - .3, location.getY(), location.getZ() - .3,
                        location.getX() + .3, location.getY() + 1.8, location.getZ() + .3);
            });
            when(player.getVelocity()).thenAnswer(call -> new Vector());
            when(player.canSee(any(Player.class))).thenReturn(true); when(peer.ready(player)).thenReturn(true);
            return player;
        }
        Player target(double z) { Player player = player(z); positions.get(player.getUniqueId()).setX(0); return player; }
        void aim(double x, double y, double z, Vector direction) {
            Location eye = new Location(world, x, y, z).setDirection(direction);
            when(shooter.getEyeLocation()).thenAnswer(call -> eye.clone());
            gun.getAsJsonObject("bullet").addProperty("speed", 200);
        }
        void fire() { service.fire(shooter, json("{id:'tacz:ak47',instance:'gun-instance'}"), gun, 0, false); }
        void ticks(int count) { for (int i = 0; i < count; i++) service.tick(); }
        List<String> ops(Player player) { return events.stream().filter(p -> p.recipient.equals(player.getUniqueId())).map(p -> p.record.get("op").getAsString()).toList(); }
        List<JsonObject> records(Player player, String op) { return events.stream().filter(p -> p.recipient.equals(player.getUniqueId()) && p.record.get("op").getAsString().equals(op)).map(Packet::record).toList(); }
        JsonObject only(Player player, String op) { List<JsonObject> records = records(player, op); assertEquals(1, records.size(), op); return records.getFirst(); }
        @Override public void close() { bukkit.close(); }
    }
}
