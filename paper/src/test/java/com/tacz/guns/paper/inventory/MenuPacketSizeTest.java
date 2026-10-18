package com.tacz.guns.paper.inventory;

import com.google.gson.*;
import com.tacz.guns.bridge.BridgeProtocol;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MenuPacketSizeTest {
    private static JsonObject menu(String type, JsonArray entries) {
        JsonObject menu = new JsonObject(); menu.addProperty("menu", type); menu.addProperty("title", "TACZ 工作台/枪械改装"); menu.addProperty("token", UUID.randomUUID().toString());
        menu.addProperty("slot", 40); menu.addProperty("page", 5); menu.addProperty("pages", 100); menu.add("entries", entries); return menu;
    }
    @Test void everyDefaultRecipePageFitsTheActualPluginMessageLimit() throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        bundledPack().recipes().forEach((id, recipe) -> {
            JsonObject row = MenuEntries.recipe(id, recipe);
            assertEquals(recipe.getAsJsonObject("result").get("id"), row.get("itemId"));
            assertFalse(row.get("description").getAsString().contains("{"), "Material descriptions must not expose raw JSON"); rows.add(row);
        });
        assertFalse(rows.isEmpty());
        int maximum = 0; for (int start = 0; start < rows.size(); start += 40) {
            JsonArray entries = new JsonArray(); for (int index = start; index < Math.min(rows.size(), start + 40); index++) entries.add(rows.get(index));
            maximum = Math.max(maximum, BridgeProtocol.encode("menu", menu("workbench", entries)).length);
        }
        assertTrue(maximum < BridgeProtocol.MAX_PACKET_BYTES - 1000); System.out.println("Largest default workbench page: " + maximum + " bytes");
    }
    @Test void fullInventoryOfLongestAttachmentRowsAndGunStateFits() throws Exception {
        String longest = bundledPack().attachmentIndexes().keySet().stream().max(Comparator.comparingInt(String::length)).orElseThrow();
        JsonArray rows = new JsonArray(); for (int slot = 0; slot < 44; slot++) {
            JsonObject row = MenuEntries.entry(longest, longest, slot < 37 ? "attachment" : "installed", "extended_mag"); row.addProperty("slot", slot); row.addProperty("type", "extended_mag"); rows.add(row);
        }
        JsonObject payload = menu("refit", rows); JsonObject state = new JsonObject(); state.addProperty("instance", UUID.randomUUID().toString()); state.addProperty("id", "tacz:gun_id");
        JsonObject attachments = new JsonObject(); for (String type : List.of("scope", "muzzle", "stock", "grip", "laser", "extended_mag")) attachments.addProperty(type, longest); state.add("attachments", attachments); payload.add("state", state);
        int length = BridgeProtocol.encode("menu", payload).length; assertTrue(length < BridgeProtocol.MAX_PACKET_BYTES - 1000); System.out.println("Worst-case refit menu: " + length + " bytes");
    }
    private static DefaultGunPack bundledPack() throws Exception {
        DefaultGunPack pack = new DefaultGunPack(null);
        try (InputStream input = DefaultGunPack.class.getResourceAsStream("/default-pack.zip")) {
            assertNotNull(input); pack.load(input);
        }
        return pack;
    }
}
