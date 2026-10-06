package com.tacz.guns.paper.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tacz.guns.bridge.BridgeItemIdentity;
import com.tacz.guns.paper.item.AttachmentModifiers;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the common catalog before it replaces the active pack; no Bukkit registry is needed. */
public final class PackCatalogValidator {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Set<String> ATTACHMENT_TYPES = Set.of("scope", "muzzle", "stock", "grip", "laser", "extended_mag");
    private static final Set<String> FIRE_MODES = Set.of("auto", "semi", "burst");
    private static final Set<String> SCRIPTS = Set.of("", "tacz:xmag_reload_logic", "tacz:db_short_gun_logic",
            "tacz:devotion_lmg_logic", "tacz:hk_mk23_logic", "tacz:kar98_gun_logic", "tacz:m1014_gun_logic",
            "tacz:m870_gun_logic", "tacz:spas_12_gun_logic", "tacz:sp_heat", "tacz:sp_spread_logic");
    private static final Set<String> BOXES = Set.copyOf(BridgeItemIdentity.BOX_IDS);
    private static final Set<String> NUMERIC_MODIFIERS = Set.of("rpm", "damage", "ammo_speed", "knockback", "pierce",
            "armor_ignore", "head_shot", "effective_range", "ads", "inaccuracy", "aim_inaccuracy", "sneak_inaccuracy", "lie_inaccuracy");

    private PackCatalogValidator() { }

    public static void validate(Map<String, Map<String, JsonObject>> objects, Map<String, JsonArray> tags) throws IOException {
        for (var category : objects.entrySet()) {
            for (var entry : category.getValue().entrySet()) {
                String type = category.getKey(), id = entry.getKey();
                try {
                    resourceId(id, "catalog id");
                    JsonObject data = entry.getValue();
                    require(data != null, "object must not be null");
                    finiteNumbers(data, "$", 0);
                    switch (type) {
                        case "GUN_INDEX" -> {
                            index(data);
                            text(data, "type");
                            reference(objects, "GUN_DATA", text(data, "data"), "data");
                            if (data.has("item_type")) require(text(data, "item_type").equals("modern_kinetic"), "unsupported item_type");
                        }
                        case "ATTACHMENT_INDEX" -> {
                            index(data);
                            require(ATTACHMENT_TYPES.contains(text(data, "type")), "unsupported attachment type");
                            reference(objects, "ATTACHMENT_DATA", text(data, "data"), "data");
                        }
                        case "AMMO_INDEX" -> {
                            index(data);
                            optionalInteger(data, "stack_size", 1, 64);
                        }
                        case "GUN_DATA" -> gun(data, objects);
                        case "ATTACHMENT_DATA" -> attachment(data);
                        case "RECIPES" -> recipe(data, objects);
                        default -> { /* Other common data still receives resource-id and finite-number checks. */ }
                    }
                } catch (RuntimeException invalid) {
                    throw new IOException("Invalid " + type + " " + id + ": " + invalid.getMessage(), invalid);
                }
            }
        }
        for (var entry : tags.entrySet()) {
            try {
                resourceId(entry.getKey(), "tag id");
                require(entry.getValue() != null, "tag must be an array");
                for (JsonElement element : entry.getValue()) {
                    String value = string(element, "tag entry");
                    if (value.startsWith("#")) {
                        String target = value.substring(1);
                        resourceId(target, "tag reference");
                        require(tags.containsKey(target), "missing tag reference: " + target);
                    } else reference(objects, "ATTACHMENT_INDEX", value, "attachment");
                }
                // Cycles are legal sets. Consumers must resolve them with a visited set.
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid ATTACHMENT_TAGS " + entry.getKey() + ": " + invalid.getMessage(), invalid);
            }
        }
    }

    private static void index(JsonObject data) {
        text(data, "name");
        resourceId(text(data, "display"), "display");
    }

    private static void gun(JsonObject data, Map<String, Map<String, JsonObject>> objects) {
        reference(objects, "AMMO_INDEX", text(data, "ammo"), "ammo");
        integer(data.get("rpm"), "rpm", 1, 2400);
        String reloadType = "magazine";
        if (data.has("reload")) {
            JsonObject reload = object(data.get("reload"), "reload");
            if (reload.has("type")) reloadType = text(reload, "type");
            require(!reloadType.equals("fuel"), "unsupported fuel reload.type for Paper");
            require(!reloadType.equals("manual"), "unsupported manual reload.type for Paper; use a supported progressive-reload script with magazine");
            require(Set.of("magazine", "inventory").contains(reloadType), "unsupported reload.type");
            if (reload.has("infinite")) require(reload.get("infinite").isJsonPrimitive()
                    && reload.getAsJsonPrimitive("infinite").isBoolean(), "reload.infinite must be a boolean");
            for (String field : Set.of("feed", "cooldown")) if (reload.has(field)) {
                JsonObject timing = object(reload.get(field), "reload." + field);
                for (String mode : Set.of("empty", "tactical")) optionalNumber(timing, mode, 0, Double.MAX_VALUE);
            }
        }
        if (data.has("ammo_amount")) integer(data.get("ammo_amount"), "ammo_amount", 1, 1_000_000);
        else require(reloadType.equals("inventory"), "non-inventory gun is missing ammo_amount");
        if (data.has("extended_mag_ammo_amount")) {
            JsonArray capacities = array(data.get("extended_mag_ammo_amount"), "extended_mag_ammo_amount");
            require(capacities.size() == 3, "extended_mag_ammo_amount must contain exactly 3 capacities");
            for (JsonElement value : capacities) integer(value, "extended_mag_ammo_amount", 1, 1_000_000);
        }
        if (data.has("bolt")) require(Set.of("open_bolt", "closed_bolt", "manual_action").contains(text(data, "bolt")), "unsupported bolt");
        JsonArray modes = array(data.get("fire_mode"), "fire_mode");
        require(!modes.isEmpty(), "fire_mode must not be empty");
        Set<String> uniqueModes = new HashSet<>();
        for (JsonElement entry : modes) {
            String mode = string(entry, "fire_mode");
            require(FIRE_MODES.contains(mode) && uniqueModes.add(mode), "invalid or duplicate fire_mode: " + mode);
        }
        if (data.has("script")) require(SCRIPTS.contains(string(data.get("script"), "script")), "unsupported Paper gun script");
        if (data.has("allow_attachment_types")) for (JsonElement type : array(data.get("allow_attachment_types"), "allow_attachment_types"))
            require(ATTACHMENT_TYPES.contains(string(type, "allow_attachment_types")), "unsupported allow_attachment_types entry");
        if (data.has("builtin_attachments")) for (var entry : object(data.get("builtin_attachments"), "builtin_attachments").entrySet()) {
            require(ATTACHMENT_TYPES.contains(entry.getKey()), "unsupported builtin_attachments slot");
            reference(objects, "ATTACHMENT_INDEX", string(entry.getValue(), "builtin attachment"), "builtin attachment");
        }
        if (data.has("burst_data")) {
            JsonObject burst = object(data.get("burst_data"), "burst_data");
            optionalInteger(burst, "count", 1, 16);
            optionalInteger(burst, "bpm", 1, 2400);
            optionalNumber(burst, "min_interval", 0, Double.MAX_VALUE);
        }
        for (String timing : Set.of("draw_time", "put_away_time", "aim_time", "sprint_time", "bolt_action_time"))
            optionalNumber(data, timing, 0, Double.MAX_VALUE);
        if (data.has("bullet")) bullet(object(data.get("bullet"), "bullet"));
        if (data.has("recoil")) {
            JsonObject recoil = object(data.get("recoil"), "recoil");
            for (String axis : Set.of("pitch", "yaw")) if (recoil.has(axis)) for (JsonElement frame : array(recoil.get(axis), "recoil." + axis)) {
                JsonObject point = object(frame, "recoil frame");
                number(point.get("time"), "recoil.time", 0, Double.MAX_VALUE);
                JsonArray values = array(point.get("value"), "recoil.value");
                require(values.size() == 2, "recoil.value must have two numbers");
                require(number(values.get(0), "recoil.value", -Double.MAX_VALUE, Double.MAX_VALUE)
                        <= number(values.get(1), "recoil.value", -Double.MAX_VALUE, Double.MAX_VALUE), "recoil.value must be ascending");
            }
        }
    }

    private static void bullet(JsonObject bullet) {
        optionalNumber(bullet, "damage", 0, Double.MAX_VALUE);
        optionalNumber(bullet, "speed", Double.MIN_VALUE, Double.MAX_VALUE);
        optionalNumber(bullet, "life", Double.MIN_VALUE, Double.MAX_VALUE);
        optionalInteger(bullet, "bullet_amount", 1, 128);
        optionalInteger(bullet, "pierce", 0, 64); // Default launcher assets deliberately use zero.
        if (bullet.has("extra_damage")) {
            JsonObject extra = object(bullet.get("extra_damage"), "bullet.extra_damage");
            optionalNumber(extra, "head_shot_multiplier", 0, 100);
            if (extra.has("body_part_multipliers")) {
                JsonObject parts = object(extra.get("body_part_multipliers"), "bullet.extra_damage.body_part_multipliers");
                for (var part : parts.entrySet()) {
                    require(Set.of("torso", "legs").contains(part.getKey()), "unknown body_part_multipliers region: " + part.getKey());
                    number(part.getValue(), "body_part_multipliers." + part.getKey(), 0, 100);
                }
            }
            if (extra.has("damage_adjust")) for (JsonElement entry : array(extra.get("damage_adjust"), "damage_adjust")) {
                JsonObject adjustment = object(entry, "damage_adjust entry");
                JsonElement distance = adjustment.get("distance");
                if (distance != null && distance.isJsonPrimitive() && distance.getAsJsonPrimitive().isString())
                    require(distance.getAsString().equals("infinite"), "damage_adjust.distance must be numeric or 'infinite'");
                else number(distance, "damage_adjust.distance", 0, Double.MAX_VALUE);
                number(adjustment.get("damage"), "damage_adjust.damage", 0, Double.MAX_VALUE);
            }
        }
    }

    private static void attachment(JsonObject data) {
        optionalInteger(data, "extended_mag_level", 0, 3);
        for (String field : NUMERIC_MODIFIERS) if (data.has(field)) {
            JsonObject modifier = object(data.get(field), field);
            for (String operand : Set.of("addend", "percent", "multiplier"))
                optionalNumber(modifier, operand, -Double.MAX_VALUE, Double.MAX_VALUE);
        }
        AttachmentModifiers.validate(data);
    }

    private static void recipe(JsonObject data, Map<String, Map<String, JsonObject>> objects) {
        require(text(data, "type").equals("tacz:gun_smith_table_crafting"), "unsupported recipe type");
        JsonArray materials = array(data.get("materials"), "materials");
        require(!materials.isEmpty(), "materials must not be empty");
        for (JsonElement entry : materials) {
            JsonObject material = object(entry, "material");
            optionalInteger(material, "count", 1, Integer.MAX_VALUE);
            ingredient(material.get("item"), 0);
        }
        JsonObject result = object(data.get("result"), "result");
        String kind = text(result, "type"), id = text(result, "id");
        optionalInteger(result, "count", 1, 4096);
        switch (kind) {
            case "gun" -> reference(objects, "GUN_INDEX", id, "result.id");
            case "ammo" -> reference(objects, "AMMO_INDEX", id, "result.id");
            case "attachment" -> reference(objects, "ATTACHMENT_INDEX", id, "result.id");
            case "box" -> require(BOXES.contains(id), "unknown result ammunition box: " + id);
            default -> throw new IllegalArgumentException("unsupported result.type: " + kind);
        }
    }

    private static void ingredient(JsonElement value, int depth) {
        require(depth <= 32, "ingredient nesting is too deep");
        if (value != null && value.isJsonArray()) {
            require(!value.getAsJsonArray().isEmpty(), "ingredient alternatives must not be empty");
            for (JsonElement alternative : value.getAsJsonArray()) ingredient(alternative, depth + 1);
        } else {
            JsonObject item = object(value, "ingredient");
            require(item.has("item") != item.has("tag"), "ingredient must contain exactly one item or tag");
            String key = item.has("item") ? "item" : "tag";
            resourceId(text(item, key), "ingredient." + key);
        }
    }

    private static void finiteNumbers(JsonElement value, String path, int depth) {
        require(depth <= 64, "JSON nesting is too deep at " + path);
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            require(Double.isFinite(value.getAsDouble()), "non-finite number at " + path);
        else if (value.isJsonArray()) {
            int index = 0;
            for (JsonElement child : value.getAsJsonArray()) finiteNumbers(child, path + "[" + index++ + "]", depth + 1);
        } else if (value.isJsonObject()) for (var child : value.getAsJsonObject().entrySet())
            finiteNumbers(child.getValue(), path + "." + child.getKey(), depth + 1);
    }

    private static void reference(Map<String, Map<String, JsonObject>> objects, String type, String id, String field) {
        resourceId(id, field);
        require(objects.getOrDefault(type, Map.of()).containsKey(id), "missing " + field + " reference: " + id + " (" + type + ")");
    }
    private static void resourceId(String value, String field) { require(RESOURCE_ID.matcher(value).matches(), "invalid " + field + " resource id: " + value); }
    private static String text(JsonObject object, String field) {
        String result = string(object.get(field), field);
        require(!result.isBlank(), field + " must not be blank");
        return result;
    }
    private static String string(JsonElement value, String field) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), field + " must be a string");
        return value.getAsString();
    }
    private static JsonObject object(JsonElement value, String field) { require(value != null && value.isJsonObject(), field + " must be an object"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value, String field) { require(value != null && value.isJsonArray(), field + " must be an array"); return value.getAsJsonArray(); }
    private static void optionalInteger(JsonObject data, String field, int min, int max) { if (data.has(field)) integer(data.get(field), field, min, max); }
    private static int integer(JsonElement value, String field, int min, int max) {
        number(value, field, min, max);
        int result;
        try { result = value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(field + " must be an integer", invalid); }
        require(result >= min && result <= max, field + " must be within " + min + ".." + max);
        return result;
    }
    private static void optionalNumber(JsonObject data, String field, double min, double max) { if (data.has(field)) number(data.get(field), field, min, max); }
    private static double number(JsonElement value, String field, double min, double max) {
        require(value instanceof JsonPrimitive primitive && primitive.isNumber(), field + " must be a number");
        double result = value.getAsDouble();
        require(Double.isFinite(result) && result >= min && result <= max, field + " must be within " + min + ".." + max);
        return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
