package com.tacz.guns.paper.item;

import com.google.gson.*;
import java.util.*;

/** Default-pack modifier arithmetic, with its three trusted inline formulas compiled to Java. */
public final class AttachmentModifiers {
    private AttachmentModifiers() {}
    public static double number(JsonObject object, String key, double fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : fallback;
    }
    public static JsonObject object(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) parent.add(key, new JsonObject());
        return parent.getAsJsonObject(key);
    }
    private static double formula(String formula, double x) {
        return switch (formula.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)) {
            case "y=1" -> 1;
            case "if(x>2)theny=x+2elsey=xend" -> x > 2 ? x + 2 : x;
            case "if(x>0.5)theny=x*1.5elsey=x*1.75end" -> x > .5 ? x * 1.5 : x * 1.75;
            default -> throw new IllegalArgumentException("Unsupported default-pack attachment formula: " + formula);
        };
    }
    public static void validate(JsonElement element) {
        if (element.isJsonArray()) { for (JsonElement child : element.getAsJsonArray()) validate(child); }
        if (element.isJsonObject()) for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("function")) formula(entry.getValue().getAsString(), 1);
            else validate(entry.getValue());
        }
    }
    public static double evaluate(List<JsonObject> modifiers, double base) {
        double add = base, percent = 1, multiply = 1;
        for (JsonObject modifier : modifiers) {
            add += number(modifier, "addend", 0); percent += number(modifier, "percent", 0); multiply *= Math.max(0, number(modifier, "multiplier", 1));
        }
        double value = add * Math.max(0, percent) * multiply;
        for (JsonObject modifier : modifiers) if (modifier.has("function")) value = formula(modifier.get("function").getAsString(), value);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite attachment modifier result");
        return value;
    }
    private static List<JsonObject> collect(List<JsonObject> attachments, String key, String legacy) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject attachment : attachments) {
            if (attachment.has(key) && attachment.get(key).isJsonObject()) result.add(attachment.getAsJsonObject(key));
            else if (legacy != null && attachment.has(legacy)) { JsonObject modifier = new JsonObject(); modifier.add("addend", attachment.get(legacy)); result.add(modifier); }
        } return result;
    }
    private static void modify(JsonObject output, String field, double fallback, List<JsonObject> attachments, String key, String legacy) {
        List<JsonObject> modifiers = collect(attachments, key, legacy);
        if (!modifiers.isEmpty()) output.addProperty(field, evaluate(modifiers, number(output, field, fallback)));
    }
    public static JsonObject apply(JsonObject raw, String fireMode, List<JsonObject> attachments) {
        JsonObject gun = raw.deepCopy(); JsonObject bullet = object(gun, "bullet"), extra = object(bullet, "extra_damage");
        JsonObject adjust = raw.has("fire_mode_adjust") && raw.getAsJsonObject("fire_mode_adjust").has(fireMode)
                ? raw.getAsJsonObject("fire_mode_adjust").getAsJsonObject(fireMode) : new JsonObject();
        for (String key : List.of("damage", "speed", "knockback")) if (adjust.has(key)) bullet.addProperty(key, number(bullet, key, 0) + number(adjust, key, 0));
        for (String key : List.of("armor_ignore", "head_shot_multiplier")) if (adjust.has(key)) extra.addProperty(key, number(extra, key, key.equals("head_shot_multiplier") ? 1 : 0) + number(adjust, key, 0));
        gun.addProperty("rpm", number(gun, "rpm", 600) + number(adjust, "rpm", 0)); gun.remove("fire_mode_adjust");
        modify(gun, "rpm", 600, attachments, "rpm", null);
        modify(gun, "aim_time", .2, attachments, "ads", "ads_addend");
        modify(bullet, "damage", 0, attachments, "damage", null);
        modify(bullet, "speed", 200, attachments, "ammo_speed", null);
        modify(bullet, "knockback", 0, attachments, "knockback", null);
        modify(bullet, "pierce", 1, attachments, "pierce", null);
        modify(extra, "armor_ignore", 0, attachments, "armor_ignore", null);
        modify(extra, "head_shot_multiplier", 1.5, attachments, "head_shot", null);
        if (extra.has("damage_adjust")) for (JsonElement element : extra.getAsJsonArray("damage_adjust")) {
            JsonObject pair = element.getAsJsonObject(); pair.addProperty("damage", number(pair, "damage", 0) + number(adjust, "damage", 0));
            modify(pair, "damage", 0, attachments, "damage", null);
        }
        if (extra.has("damage_adjust") && !extra.getAsJsonArray("damage_adjust").isEmpty()) {
            JsonObject first = extra.getAsJsonArray("damage_adjust").get(0).getAsJsonObject();
            if (first.has("distance") && first.get("distance").getAsJsonPrimitive().isNumber()) modify(first, "distance", 0, attachments, "effective_range", null);
        }
        JsonObject accuracy = object(gun, "inaccuracy");
        for (String stance : List.of("stand", "move", "sneak", "lie", "aim")) {
            if (accuracy.has(stance)) accuracy.addProperty(stance, number(accuracy, stance, 0) + number(adjust, stance.equals("aim") ? "aim_inaccuracy" : "other_inaccuracy", 0));
            String key = switch (stance) { case "aim", "sneak", "lie" -> stance + "_inaccuracy"; default -> "inaccuracy"; };
            modify(accuracy, stance, 0, attachments, key, key.equals("inaccuracy") ? "inaccuracy_addend" : null);
        }
        for (String axis : List.of("pitch", "yaw")) {
            List<JsonObject> modifiers = new ArrayList<>();
            for (JsonObject attachment : attachments) {
                if (attachment.has("recoil") && attachment.getAsJsonObject("recoil").has(axis)) modifiers.add(attachment.getAsJsonObject("recoil").getAsJsonObject(axis));
                else if (attachment.has("recoil_modifier") && attachment.getAsJsonObject("recoil_modifier").has(axis)) { JsonObject m = new JsonObject(); m.add("percent", attachment.getAsJsonObject("recoil_modifier").get(axis)); modifiers.add(m); }
            }
            if (!modifiers.isEmpty() && gun.has("recoil") && gun.getAsJsonObject("recoil").has(axis)) for (JsonElement point : gun.getAsJsonObject("recoil").getAsJsonArray(axis)) {
                JsonArray values = point.getAsJsonObject().getAsJsonArray("value"); for (int i = 0; i < values.size(); i++) values.set(i, new JsonPrimitive(evaluate(modifiers, values.get(i).getAsDouble())));
            }
        }
        double weight = number(gun, "weight", 0);
        for (JsonObject attachment : attachments) {
            weight += number(attachment, "weight", 0);
            if (attachment.has("melee")) object(gun, "melee").add("default", attachment.get("melee").deepCopy());
            if (attachment.has("movement_speed")) for (Map.Entry<String, JsonElement> entry : attachment.getAsJsonObject("movement_speed").entrySet()) {
                JsonObject movement = object(gun, "movement_speed"); movement.addProperty(entry.getKey(), number(movement, entry.getKey(), 0) + entry.getValue().getAsDouble());
            }
        }
        gun.addProperty("weight", weight);
        for (String section : List.of("explosion", "ignite")) {
            List<JsonObject> modifiers = collect(attachments, section, null); if (modifiers.isEmpty()) continue;
            JsonObject output = object(bullet, section);
            if (section.equals("explosion")) {
                for (String key : List.of("radius", "damage", "delay")) {
                    List<JsonObject> fields = collect(modifiers, key, null);
                    output.addProperty(key, evaluate(fields, number(output, key, switch (key) { case "radius" -> .5; case "damage" -> 2; default -> 30; })));
                }
            }
            for (String key : section.equals("ignite") ? List.of("entity", "block") : List.of("explode", "knockback", "destroy_block")) {
                boolean enabled = output.has(key) && output.get(key).getAsBoolean();
                for (JsonObject modifier : modifiers) enabled |= modifier.has(key) && modifier.get(key).getAsBoolean(); output.addProperty(key, enabled);
            }
        }
        List<JsonObject> silencers = collect(attachments, "silence", null);
        JsonObject silence = new JsonObject(); double distance = evaluate(collect(silencers, "distance", "distance_addend"), 128);
        silence.addProperty("distance", distance); silence.addProperty("use_silence_sound", silencers.stream().anyMatch(s -> s.has("use_silence_sound") && s.get("use_silence_sound").getAsBoolean())); gun.add("silence", silence);
        return gun;
    }
}
