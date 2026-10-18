package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;

import java.util.function.BooleanSupplier;

import static com.tacz.guns.paper.gameplay.GunMath.*;

/** The BUJIDAO SG-01's Lua reload phases, including the chamber in its total capacity. */
final class Sg01Reload {
    static final String SCRIPT = "bujidao:sg_01_gun_logic";
    private enum Phase { INTRO, LOOP, ENDING }

    private final boolean tactical;
    private final long intro, loop, ending, introFeed, loopFeed;
    private Phase phase = Phase.INTRO;
    private long start;
    private boolean fed, failed;

    Sg01Reload(JsonObject gun, boolean chamber) {
        tactical = chamber;
        JsonObject params = object(gun, "script_param");
        intro = timing(params, chamber ? "intro" : "intro_empty", chamber ? .72 : 2);
        loop = timing(params, "loop", .86);
        ending = timing(params, "ending", .74);
        introFeed = Math.min(intro, timing(params, "intro_empty_feed", 1.07));
        loopFeed = Math.min(loop, timing(params, "loop_feed", .46));
    }

    static boolean matches(JsonObject gun) { return SCRIPT.equals(text(gun, "script", "")); }

    static int needed(JsonObject state, int capacity) {
        return Math.max(0, capacity - integer(state, "ammo", 0) - (flag(state, "chamber", false) ? 1 : 0));
    }

    /** Lua treats nonpositive timing values as absent, rather than zero-length phases. */
    static long timing(JsonObject params, String key, double fallback) {
        double value = number(params, key, fallback);
        return Math.max(1, (long) (Math.min(value > 0 ? value : fallback, 120) * 1000));
    }

    void advance(long elapsed, JsonObject state, int capacity, BooleanSupplier hasAmmo, BooleanSupplier takeOne) {
        // Keep the original phase boundaries when catching up. Every feed rechecks inventory.
        // Bound unusual edited packs without changing the time line; a later tick can continue.
        for (int steps = 0; phase != Phase.ENDING && steps < 2048; steps++) {
            boolean introduction = phase == Phase.INTRO;
            long length = introduction ? intro : loop;
            long feed = introduction ? introFeed : loopFeed;
            if (!fed && (!introduction || !tactical) && elapsed >= start + feed) {
                if (needed(state, capacity) > 0 && (!introduction || !flag(state, "chamber", false)) && takeOne.getAsBoolean()) {
                    if (introduction) state.addProperty("chamber", true);
                    else state.addProperty("ammo", integer(state, "ammo", 0) + 1);
                } else failed = true;
                fed = true;
            }
            if (elapsed < start + length) break;
            start += length;
            if (failed || needed(state, capacity) == 0 || !hasAmmo.getAsBoolean()) phase = Phase.ENDING;
            else { phase = Phase.LOOP; fed = false; }
        }
    }

    boolean empty() { return !tactical; }
    boolean feeding() { return phase != Phase.ENDING; }
    boolean finished(long elapsed) { return phase == Phase.ENDING && elapsed >= start + ending; }

    long remaining(long elapsed, JsonObject state, int capacity) {
        if (phase == Phase.ENDING) return Math.max(0, start + ending - elapsed);
        int reserved = !fed && (phase == Phase.LOOP || !tactical) ? 1 : 0;
        return Math.max(0, start + (phase == Phase.INTRO ? intro : loop) - elapsed)
                + Math.max(0, needed(state, capacity) - reserved) * loop;
    }
}
