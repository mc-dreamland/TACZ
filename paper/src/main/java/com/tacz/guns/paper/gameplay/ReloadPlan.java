package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import static com.tacz.guns.paper.gameplay.GunMath.*;

/** Java equivalents of the bundled reload Lua scripts. Times are relative milliseconds. */
public record ReloadPlan(List<Feed> feeds, long duration, long ending, boolean progressive, boolean empty) {
    public record Feed(long time, int count, boolean chamber) {}

    public static ReloadPlan create(JsonObject gun, JsonObject state, int capacity, int extensionLevel) {
        String script = text(gun, "script", "");
        String mode = text(state, "fireMode", "semi");
        JsonObject param = object(gun, "script_param");
        boolean empty = integer(state, "ammo", 0) == 0 && !flag(state, "chamber", false);
        int missing = Math.max(0, capacity - integer(state, "ammo", 0));
        boolean kar = script.equals("tacz:kar98_gun_logic");
        boolean tube = script.equals("tacz:m870_gun_logic") || script.equals("tacz:m1014_gun_logic") || script.equals("tacz:spas_12_gun_logic");
        List<Feed> feeds = new ArrayList<>();
        boolean scope = object(state, "attachments").entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("scope") && !e.getValue().getAsString().equals("tacz:empty"));
        if (kar && empty && !scope) {
            long feed = millis(param, "clip_load_feed", 2.78);
            feeds.add(new Feed(feed, 1, true));
            feeds.add(new Feed(feed, missing, false));
            return new ReloadPlan(List.copyOf(feeds), Math.max(feed, millis(param, "clip_load", 3)), 0, false, true);
        }
        if (tube || kar) {
            boolean spasSemi = script.equals("tacz:spas_12_gun_logic") && mode.equals("burst");
            long intro = millis(param, empty ? (spasSemi ? "intro_empty_semi" : "intro_empty") : "intro", .5);
            long loop = Math.max(1, millis(param, "loop", .5));
            long loopFeed = millis(param, "loop_feed", .3);
            long ending = millis(param, "ending", .5);
            long cursor = intro;
            boolean needsChamber = !flag(state, "chamber", false);
            if (empty && !kar) {
                feeds.add(new Feed(millis(param, spasSemi ? "intro_empty_feed_semi" : "intro_empty_feed", .4), 1, true));
            }
            if (kar && needsChamber) {
                feeds.add(new Feed(cursor + loopFeed, 1, true));
                cursor += loop;
            }
            boolean pairs = script.equals("tacz:m1014_gun_logic");
            for (int left = missing; left > 0;) {
                int count = pairs && left >= 2 ? 2 : 1;
                long step = count == 2 ? Math.max(1, millis(param, "loop_2", loop / 1000.0)) : loop;
                // The bundled M1014 animation's first pair feeds at loop_feed.
                long feedTime = count == 2 ? millis(param, "loop_feed", loopFeed / 1000.0) : loopFeed;
                feeds.add(new Feed(cursor + feedTime, count, false));
                cursor += step;
                left -= count;
            }
            feeds.sort(java.util.Comparator.comparingLong(Feed::time));
            long lastFeed = feeds.stream().mapToLong(Feed::time).max().orElse(0);
            return new ReloadPlan(List.copyOf(feeds), Math.max(cursor, lastFeed) + ending, ending, true, empty);
        }
        JsonObject reload = object(gun, "reload");
        String style = empty ? "empty" : "tactical";
        long feed = millis(object(reload, "feed"), style, 1.5);
        long duration = millis(object(reload, "cooldown"), style, 2);
        if (script.equals("tacz:xmag_reload_logic")) {
            String prefix = empty ? "empty" : "reload";
            String suffix = extensionLevel == 0 ? "" : "_xmag_" + Math.min(extensionLevel, 3);
            feed = millis(param, prefix + suffix + "_feed", feed / 1000.0);
            duration = millis(param, prefix + suffix + "_cooldown", duration / 1000.0);
        } else if (script.equals("tacz:hk_mk23_logic")) {
            String prefix = !empty ? "tactical" : mode.equals("semi") ? "empty_pump" : "empty";
            String suffix = extensionLevel == 0 ? "" : "_xmag_" + Math.min(extensionLevel, 2);
            feed = millis(param, prefix + suffix + "_feed", feed / 1000.0);
            duration = millis(param, prefix + suffix + "_cooldown", duration / 1000.0);
        }
        feeds.add(new Feed(feed, missing, false));
        return new ReloadPlan(List.copyOf(feeds), Math.max(duration, feed), 0, false, empty);
    }
}
