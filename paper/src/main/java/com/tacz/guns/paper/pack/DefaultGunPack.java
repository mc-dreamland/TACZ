package com.tacz.guns.paper.pack;

import com.google.gson.*;
import com.tacz.guns.bridge.BridgeItemIdentity;
import com.tacz.guns.bridge.PackTransfer;
import com.tacz.guns.paper.item.AttachmentModifiers;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Server-owned catalogs. Directory edits are validated before replacing the active pack. */
public final class DefaultGunPack {
    public static final List<String> BOX_IDS = BridgeItemIdentity.BOX_IDS;
    private final JavaPlugin plugin;
    private final Map<String, Map<String, JsonObject>> objects = new LinkedHashMap<>();
    private final Map<String, JsonArray> tags = new TreeMap<>();
    private final Map<String, String> scripts = new TreeMap<>();
    private final Map<String, JsonObject> attachmentDisplays = new TreeMap<>();
    private final Map<String, JsonObject> gunDisplays = new TreeMap<>();
    private JsonObject network = new JsonObject();

    public DefaultGunPack(JavaPlugin plugin) { this.plugin = plugin; }

    public void load() throws IOException {
        InputStream input = plugin.getResource("default-pack.zip");
        if (input == null) throw new FileNotFoundException("Bundled default-pack.zip is missing");
        load(input, plugin.getDataFolder().toPath());
    }

    public void load(InputStream input) throws IOException {
        load(input, null);
    }

    /** The stream supplies first-run defaults; subsequent loads use the recursive server directories. */
    public void load(InputStream input, Path directory) throws IOException {
        DefaultGunPack candidate = new DefaultGunPack(plugin);
        candidate.readBundled(input);
        if (directory != null) DirectoryPackFiles.apply(directory, candidate.objects, candidate.tags);
        PackCatalogValidator.validate(candidate.objects, candidate.tags);
        candidate.buildNetwork();
        // A valid server configuration must also fit the client's bounded pack transfer.
        JsonObject bundle = new JsonObject();
        bundle.add("data", candidate.networkData());
        PackTransfer.encode(bundle);
        objects.clear(); objects.putAll(candidate.objects);
        tags.clear(); tags.putAll(candidate.tags);
        scripts.clear(); scripts.putAll(candidate.scripts);
        attachmentDisplays.clear(); attachmentDisplays.putAll(candidate.attachmentDisplays);
        gunDisplays.clear(); gunDisplays.putAll(candidate.gunDisplays);
        network = candidate.network;
    }

    private void readBundled(InputStream input) throws IOException {
        objects.clear(); tags.clear(); scripts.clear(); attachmentDisplays.clear(); gunDisplays.clear(); network = new JsonObject();
        String[][] categories = {{"data/guns/", "GUN_DATA"}, {"data/attachments/", "ATTACHMENT_DATA"},
                {"index/ammo/", "AMMO_INDEX"}, {"index/guns/", "GUN_INDEX"},
                {"index/attachments/", "ATTACHMENT_INDEX"}, {"recipes/", "RECIPES"},
                {"recipe_filters/", "RECIPE_FILTER"}, {"data/blocks/", "BLOCK_DATA"}, {"index/blocks/", "BLOCK_INDEX"}};
        for (String[] category : categories) objects.put(category[1], new TreeMap<>());
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                String displayPrefix = path.contains("assets/tacz/display/attachments/") ? "assets/tacz/display/attachments/" : "assets/tacz/display/guns/";
                int displayRoot = path.indexOf(displayPrefix);
                if (displayRoot >= 0 && (displayRoot == 0 || path.charAt(displayRoot - 1) == '/') && path.endsWith(".json")) {
                    byte[] bytes = zip.readNBytes(2_000_001);
                    if (bytes.length > 2_000_000) throw new IOException("Oversized item display: " + entry.getName());
                    try { (displayPrefix.contains("attachments/") ? attachmentDisplays : gunDisplays).put(id(path.substring(displayRoot), displayPrefix, ".json"), JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()); }
                    catch (RuntimeException e) { throw new IOException("Invalid item display: " + entry.getName(), e); }
                    continue;
                }
                int root = path.indexOf("data/tacz/");
                if (root < 0 || (root > 0 && path.charAt(root - 1) != '/')) continue;
                path = path.substring(root + "data/tacz/".length());
                if (!path.endsWith(".json") && !path.endsWith(".lua")) continue;
                byte[] bytes = zip.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IOException("Oversized default pack entry: " + entry.getName());
                String raw = new String(bytes, StandardCharsets.UTF_8);
                if (path.startsWith("scripts/") && path.endsWith(".lua")) {
                    scripts.put(id(path, "scripts/", ".lua"), raw); continue;
                }
                try {
                    if (path.startsWith("tacz_tags/attachments/")) {
                        tags.put(id(path, "tacz_tags/attachments/", ".json"), JsonParser.parseString(raw).getAsJsonArray());
                    } else {
                        for (String[] category : categories) {
                            if (path.startsWith(category[0]) && path.endsWith(".json")) {
                                objects.get(category[1]).put(id(path, category[0], ".json"), JsonParser.parseString(raw).getAsJsonObject());
                                break;
                            }
                        }
                    }
                } catch (RuntimeException e) { throw new IOException("Invalid bundled pack entry: " + entry.getName(), e); }
            }
        }
        if (gunIndexes().isEmpty() || ammoIndexes().isEmpty()) throw new IOException("Default pack has no guns or ammunition");
        for (String gunId : gunIndexes().keySet()) if (gun(gunId) == null) throw new IOException("Missing gun data: " + gunId);
        for (String attachmentId : attachmentIndexes().keySet()) if (attachment(attachmentId) == null) throw new IOException("Missing attachment data: " + attachmentId);
        try { attachments().values().forEach(AttachmentModifiers::validate); }
        catch (IllegalArgumentException e) { throw new IOException("Default pack modifier validation failed", e); }
    }

    private void buildNetwork() {
        objects.forEach((type, values) -> {
            JsonObject map = new JsonObject(); values.forEach((key, value) -> map.addProperty(key, value.toString())); network.add(type, map);
        });
        JsonObject tagData = new JsonObject(), allowData = new JsonObject();
        tags.forEach((key, value) -> {
            // The Forge tag matcher recursively expands tags without cycle detection. Send
            // their equivalent leaf sets so directory aliases cannot recurse on the client.
            JsonArray leaves = new JsonArray();
            Set<String> attachments = new TreeSet<>();
            collectTag(key, new HashSet<>(), attachments);
            attachments.forEach(leaves::add);
            tagData.addProperty(key, leaves.toString());
            int separator = key.indexOf(':');
            String path = key.substring(separator + 1);
            if (path.startsWith("allow_attachments/"))
                allowData.addProperty(key.substring(0, separator + 1) + path.substring("allow_attachments/".length()), leaves.toString());
        });
        network.add("ATTACHMENT_TAGS", tagData); network.add("ALLOW_ATTACHMENT_TAGS", allowData);
    }

    private void collectTag(String id, Set<String> visited, Set<String> attachments) {
        Deque<String> pending = new ArrayDeque<>();
        pending.add(id);
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (!visited.add(next)) continue;
            JsonArray entries = tags.get(next);
            if (entries == null) continue;
            for (JsonElement entry : entries) {
                String value = entry.getAsString();
                if (value.startsWith("#")) pending.addLast(value.substring(1));
                else attachments.add(value);
            }
        }
    }

    private static String id(String path, String prefix, String suffix) { return "tacz:" + path.substring(prefix.length(), path.length() - suffix.length()); }
    private Map<String, JsonObject> map(String type) { return Collections.unmodifiableMap(objects.getOrDefault(type, Map.of())); }
    public Map<String, JsonObject> guns() { return map("GUN_DATA"); }
    public Map<String, JsonObject> gunIndexes() { return map("GUN_INDEX"); }
    public Map<String, JsonObject> ammoIndexes() { return map("AMMO_INDEX"); }
    public Map<String, JsonObject> attachmentIndexes() { return map("ATTACHMENT_INDEX"); }
    public Map<String, JsonObject> attachments() { return map("ATTACHMENT_DATA"); }
    public Map<String, JsonObject> recipes() { return map("RECIPES"); }
    public Map<String, String> scripts() { return Collections.unmodifiableMap(scripts); }
    public int scopeZoomCount(String attachmentId) {
        JsonObject index = attachmentIndexes().get(attachmentId); if (index == null || !index.has("display")) return 0;
        JsonObject display = attachmentDisplays.get(index.get("display").getAsString());
        return display != null && display.has("zoom") && display.get("zoom").isJsonArray() ? display.getAsJsonArray("zoom").size() : 0;
    }
    public boolean laserEditable(String kind, String id) {
        JsonObject index = (kind.equals("gun") ? gunIndexes() : kind.equals("attachment") ? attachmentIndexes() : Map.<String, JsonObject>of()).get(id);
        if (index == null || !index.has("display")) return false;
        JsonObject display = (kind.equals("gun") ? gunDisplays : attachmentDisplays).get(index.get("display").getAsString());
        if (display == null || !display.has("laser") || !display.get("laser").isJsonObject()) return false;
        JsonObject laser = display.getAsJsonObject("laser");
        return !laser.has("can_edit") || laser.get("can_edit").getAsBoolean();
    }
    public JsonObject gun(String id) { JsonObject index = gunIndexes().get(id); return index == null ? null : guns().get(index.get("data").getAsString()); }
    public JsonObject attachment(String id) { JsonObject index = attachmentIndexes().get(id); return index == null ? null : attachments().get(index.get("data").getAsString()); }
    public boolean hasItem(String kind, String id) {
        if (kind == null || id == null) return false;
        return switch (kind) {
            case "gun" -> gunIndexes().containsKey(id);
            case "ammo" -> ammoIndexes().containsKey(id);
            case "attachment" -> attachmentIndexes().containsKey(id);
            case "box" -> BOX_IDS.contains(id);
            default -> false;
        };
    }
    public JsonObject networkData() { return network.deepCopy(); }
    public boolean allowedAttachment(String gunId, String attachmentId) {
        int separator = gunId.indexOf(':');
        return matches(tags.get(gunId.substring(0, separator + 1) + "allow_attachments/" + gunId.substring(separator + 1)), attachmentId, new HashSet<>());
    }
    public boolean attachmentHasTag(String attachmentId, String tagId) { return matches(tags.get(tagId), attachmentId, new HashSet<>()); }
    private boolean matches(JsonArray entries, String target, Set<String> visited) {
        if (entries == null) return false;
        Deque<JsonArray> pending = new ArrayDeque<>();
        pending.add(entries);
        while (!pending.isEmpty()) for (JsonElement entry : pending.removeFirst()) {
            String value = entry.getAsString();
            if (target.equals(value)) return true;
            if (value.startsWith("#") && visited.add(value)) {
                JsonArray nested = tags.get(value.substring(1));
                if (nested != null) pending.addLast(nested);
            }
        }
        return false;
    }
}
