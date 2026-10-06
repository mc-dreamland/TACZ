package com.tacz.guns.paper.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperLaserColorTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    @Test void rgbAcceptsOnlyNumericUnsignedTwentyFourBitIntegers() {
        assertEquals(0, PaperItemStore.rgb(JsonParser.parseString("0")));
        assertEquals(0xFFFFFF, PaperItemStore.rgb(JsonParser.parseString("16777215")));
        for (String invalid : List.of("-1", "16777216", "1.5", "'255'", "true", "null", "[]", "{}"))
            assertThrows(IllegalArgumentException.class, () -> PaperItemStore.rgb(JsonParser.parseString(invalid)), invalid);
    }

    @Test void storedColorsMustBelongToAnEditableGunOrInstalledAttachment() {
        DefaultGunPack pack = mock(DefaultGunPack.class); PaperItemStore store = new PaperItemStore(null, pack);
        when(pack.laserEditable("gun", "tacz:minigun")).thenReturn(true);
        when(pack.laserEditable("attachment", "tacz:laser_peq15")).thenReturn(true);
        assertDoesNotThrow(() -> store.validateLaserColors(json("{kind:'gun',id:'tacz:minigun',laserColor:65280,attachments:{laser:'tacz:laser_peq15'},attachmentColors:{laser:16711680}}")));
        assertDoesNotThrow(() -> store.validateLaserColors(json("{kind:'attachment',id:'tacz:laser_peq15',laserColor:255}")));
        assertDoesNotThrow(() -> store.validateLaserColors(json("{kind:'gun',id:'tacz:ak47',attachments:{}}")), "Existing items without color fields remain valid");
        for (String invalid : List.of(
                "{kind:'gun',id:'tacz:ak47',laserColor:255,attachments:{}}",
                "{kind:'gun',id:'tacz:minigun',attachments:{},attachmentColors:{laser:255}}",
                "{kind:'gun',id:'tacz:minigun',attachments:{grip:'tacz:grip_vertical_ranger'},attachmentColors:{grip:255}}",
                "{kind:'gun',id:'tacz:minigun',attachments:{laser:'tacz:laser_peq15'},attachmentColors:{laser:-1}}",
                "{kind:'attachment',id:'tacz:laser_peq15',attachmentColors:{laser:255}}",
                "{kind:'ammo',id:'tacz:9mm',laserColor:255}"))
            assertThrows(IllegalArgumentException.class, () -> store.validateLaserColors(json(invalid)), invalid);
    }
}
