package com.tacz.guns.paper.item;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentModifiersTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    @Test void modifierAggregationMatchesOriginalArithmetic() {
        assertEquals(22.5, AttachmentModifiers.evaluate(List.of(json("{addend:5,percent:0.5,multiplier:2}"), json("{percent:-0.25,multiplier:0.5}")), 13));
        assertEquals(0, AttachmentModifiers.evaluate(List.of(json("{percent:-2}")), 10));
    }
    @Test void defaultInlineFunctionsAndUnsupportedFormulasAreExplicit() {
        assertEquals(5, AttachmentModifiers.evaluate(List.of(json("{function:'if (x > 2) then y = x + 2 else y = x end'}")), 3));
        assertEquals(.4375, AttachmentModifiers.evaluate(List.of(json("{function:'if (x > 0.5) then y = x*1.5 else y = x*1.75 end'}")), .25));
        assertEquals(1, AttachmentModifiers.evaluate(List.of(json("{function:'y = 1'}")), 99));
        assertThrows(IllegalArgumentException.class, () -> AttachmentModifiers.validate(json("{damage:{function:'y = 500'}}")));
    }
    @Test void damageCurveAndModeAdjustmentsApplyBeforeAttachmentMultiplier() {
        JsonObject raw = json("{rpm:600,aim_time:0.2,bullet:{damage:9,speed:250,extra_damage:{damage_adjust:[{distance:30,damage:9},{distance:'infinite',damage:6}]}},fire_mode_adjust:{semi:{damage:2,rpm:-100}},inaccuracy:{stand:4,aim:0.2}}");
        JsonObject result = AttachmentModifiers.apply(raw, "semi", List.of(json("{damage:{multiplier:0.9},rpm:{multiplier:0.8},inaccuracy:{multiplier:0.5},aim_inaccuracy:{multiplier:0.25}}")));
        assertEquals(400, result.get("rpm").getAsDouble());
        assertEquals(9.9, result.getAsJsonObject("bullet").get("damage").getAsDouble(), .00001);
        assertEquals(7.2, result.getAsJsonObject("bullet").getAsJsonObject("extra_damage").getAsJsonArray("damage_adjust").get(1).getAsJsonObject().get("damage").getAsDouble(), .00001);
        assertEquals(2, result.getAsJsonObject("inaccuracy").get("stand").getAsDouble());
        assertEquals(.05, result.getAsJsonObject("inaccuracy").get("aim").getAsDouble());
        assertFalse(result.has("fire_mode_adjust"));
        assertEquals(9, raw.getAsJsonObject("bullet").get("damage").getAsInt(), "Catalog data must remain immutable");
    }
    @Test void everyBundledAttachmentFormulaIsSupported() throws Exception {
        Path directory = Path.of("../src/main/resources/assets/tacz/custom/tacz_default_gun/data/tacz/data/attachments");
        try (var files = Files.walk(directory)) {
            List<Path> paths = files.filter(path -> path.toString().endsWith(".json")).toList(); assertFalse(paths.isEmpty());
            for (Path path : paths) assertDoesNotThrow(() -> AttachmentModifiers.validate(JsonParser.parseString(Files.readString(path))), path.toString());
        }
    }
    @Test void everyBundledGunAcceptsItsAllowedDefaultAttachments() throws Exception {
        Path root = Path.of("../src/main/resources/assets/tacz/custom/tacz_default_gun/data/tacz");
        List<JsonObject> attachments;
        try (var paths = Files.walk(root.resolve("data/attachments"))) { attachments = paths.filter(p -> p.toString().endsWith(".json")).map(p -> { try { return json(Files.readString(p)); } catch (Exception e) { throw new RuntimeException(e); } }).toList(); }
        try (var paths = Files.walk(root.resolve("data/guns"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject gun = json(Files.readString(path));
                for (JsonObject attachment : attachments) assertDoesNotThrow(() -> AttachmentModifiers.apply(gun, "semi", List.of(attachment)), path + " / " + attachment);
            }
        }
    }
}
