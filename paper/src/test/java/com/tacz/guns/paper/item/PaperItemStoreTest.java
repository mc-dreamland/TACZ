package com.tacz.guns.paper.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real item store against controllable Bukkit item metadata, without a server. */
@SuppressWarnings("deprecation")
class PaperItemStoreTest {
    private static final String GUN = "test:rifle";
    private static final String AMMO = "test:round";
    private static final String SCOPE = "test:scope";
    private static final String MAGAZINE = "test:magazine";
    private static final String INSTANCE = "00000000-0000-0000-0000-000000000123";
    private final Map<Material, Object> originalBlockTypes = new EnumMap<>(Material.class);
    private Field blockTypeField;

    @BeforeEach
    void isolateMaterialRegistryLookup() throws ReflectiveOperationException {
        // Paper 1.21.11 routes even STICK.isAir() through the server block registry.
        // All carriers exercised here are non-air; stub only that external lookup,
        // retaining the real enum identities/keys used by the store's material checks.
        // Restore the suppliers after every test so no other suite sees this fixture.
        blockTypeField = Material.class.getDeclaredField("blockType");
        blockTypeField.setAccessible(true);
        for (Material material : List.of(Material.STICK, Material.PAPER, Material.FLINT, Material.CHEST)) {
            originalBlockTypes.put(material, blockTypeField.get(material));
            blockTypeField.set(material, (Supplier<?>) () -> null);
        }
    }

    @AfterEach
    void restoreMaterialRegistryLookup() throws IllegalAccessException {
        for (Map.Entry<Material, Object> entry : originalBlockTypes.entrySet())
            blockTypeField.set(entry.getKey(), entry.getValue());
        originalBlockTypes.clear();
    }

    @Test
    void readsGunWithValidPdcAndNoCustomModelData() {
        Fixture f = new Fixture();
        JsonObject state = gun();
        Carrier carrier = new Carrier(Material.STICK, 1, null).state(state);

        assertEquals(state, f.store.read(carrier.stack));
        assertFalse(carrier.meta.hasCustomModelData());
        verify(carrier.meta, never()).getCustomModelData();
    }

    @Test
    void arbitraryExternalAndLegacyCustomModelDataDoNotDetermineIdentity() {
        Fixture f = new Fixture();
        JsonObject state = gun();
        state.addProperty("cmd", 987654);
        Carrier carrier = new Carrier(Material.STICK, 1, 42).state(state);

        assertEquals(state, f.store.read(carrier.stack));
        state.addProperty("cmd", "obsolete-value");
        carrier.state(state);
        assertEquals(state, f.store.read(carrier.stack));
        verify(carrier.meta, never()).getCustomModelData();
    }

    @Test
    void rejectsAbsentPdcUnknownIdsWrongCarriersAndInvalidInstances() {
        Fixture f = new Fixture();
        Carrier carrier = new Carrier(Material.STICK, 1, 123);
        assertNull(f.store.read(carrier.stack), "A custom model alone is not a TACZ item");
        assertNull(f.store.read(null));
        assertNull(f.store.read(new Carrier(Material.PAPER, 1, null).state(gun()).stack));

        JsonObject state = gun();
        state.addProperty("id", "test:missing");
        assertNull(f.store.read(carrier.state(state).stack));
        state = gun();
        state.remove("instance");
        assertNull(f.store.read(carrier.state(state).stack));
        state.addProperty("instance", "not-a-uuid");
        assertNull(f.store.read(carrier.state(state).stack));
        assertNull(f.store.read(new Carrier(Material.STICK, 2, null).state(gun()).stack));
    }

    @Test
    void readAndWritePreserveInstalledAttachmentsAmmoAndChamber() {
        Fixture f = new Fixture();
        JsonObject state = equippedGun();
        Carrier carrier = new Carrier(Material.STICK, 1, null).state(state);

        assertEquals(40, f.store.capacity(state));
        JsonObject read = f.store.read(carrier.stack);
        assertEquals(state, read);
        f.store.write(carrier.stack, read);
        JsonObject after = f.store.read(carrier.stack);
        assertEquals(state, after);
        assertEquals(37, after.get("ammo").getAsInt());
        assertTrue(after.get("chamber").getAsBoolean());
        assertEquals(state.get("attachments"), after.get("attachments"));
    }

    @Test
    void invalidGunStateStillFailsWithoutCustomModelData() {
        Fixture f = new Fixture();
        Carrier carrier = new Carrier(Material.STICK, 1, null);
        JsonObject state = equippedGun();
        state.addProperty("ammo", 41);
        assertNull(f.store.read(carrier.state(state).stack), "Expanded magazine capacity remains enforced");
        state.addProperty("ammo", -1);
        assertNull(f.store.read(carrier.state(state).stack));
        state.addProperty("ammo", 0.5);
        assertNull(f.store.read(carrier.state(state).stack));
        state = gun();
        state.addProperty("chamber", "true");
        assertNull(f.store.read(carrier.state(state).stack));
        state = gun();
        state.addProperty("fireMode", "burst");
        assertNull(f.store.read(carrier.state(state).stack));
        state = gun();
        state.addProperty("heat", -1);
        assertNull(f.store.read(carrier.state(state).stack));
    }

    @Test
    void installedAttachmentsMustExistMatchTheirSlotAndBeAllowed() {
        Fixture f = new Fixture();
        Carrier carrier = new Carrier(Material.STICK, 1, null);
        JsonObject state = equippedGun();
        state.getAsJsonObject("attachments").addProperty("scope", "test:missing");
        assertNull(f.store.read(carrier.state(state).stack));
        state = equippedGun();
        state.getAsJsonObject("attachments").addProperty("scope", MAGAZINE);
        assertNull(f.store.read(carrier.state(state).stack));
        state = equippedGun();
        when(f.pack.allowedAttachment(GUN, SCOPE)).thenReturn(false);
        assertNull(f.store.read(carrier.state(state).stack));
        when(f.pack.allowedAttachment(GUN, SCOPE)).thenReturn(true);
        f.gunData.add("allow_attachment_types", JsonParser.parseString("[\"extended_mag\"]"));
        assertNull(f.store.read(carrier.state(state).stack));
    }

    @Test
    void ammunitionAndLooseAttachmentsStackWithoutInstances() {
        Fixture f = new Fixture();
        JsonObject ammo = item("ammo", AMMO), attachment = item("attachment", SCOPE);
        Carrier ammoCarrier = new Carrier(Material.PAPER, 48, null).state(ammo);
        Carrier attachmentCarrier = new Carrier(Material.FLINT, 64, null).state(attachment);

        assertEquals(ammo, f.store.read(ammoCarrier.stack));
        assertEquals(attachment, f.store.read(attachmentCarrier.stack));
        f.store.write(ammoCarrier.stack, ammo);
        f.store.write(attachmentCarrier.stack, attachment);
        assertEquals(48, ammoCarrier.stack.getMaxStackSize());
        assertEquals(64, attachmentCarrier.stack.getMaxStackSize());
        assertEquals(ammo, f.store.read(ammoCarrier.stack));
        assertFalse(ammoCarrier.persisted().has("instance"));
        assertFalse(attachmentCarrier.persisted().has("instance"));
        ammoCarrier.stack.setAmount(49);
        assertNull(f.store.read(ammoCarrier.stack));
    }

    @Test
    void ammunitionBoxIdentityAndContentsRemainValidated() {
        Fixture f = new Fixture();
        JsonObject state = item("box", DefaultGunPack.BOX_IDS.getFirst());
        state.addProperty("instance", INSTANCE);
        state.addProperty("boxLevel", 0);
        state.addProperty("boxAmmo", 64);
        state.addProperty("boxAmmoId", AMMO);
        state.addProperty("boxCapacity", 192);
        Carrier carrier = new Carrier(Material.CHEST, 1, null).state(state);
        assertEquals(state, f.store.read(carrier.stack));
        state.addProperty("boxLevel", 1);
        assertNull(f.store.read(carrier.state(state).stack));
        state.addProperty("boxLevel", 0);
        state.addProperty("boxAmmoId", "test:missing");
        assertNull(f.store.read(carrier.state(state).stack));
        state.addProperty("boxAmmoId", AMMO);
        state.addProperty("boxAmmo", 193);
        assertNull(f.store.read(carrier.state(state).stack));
    }

    @Test
    void writeDropsLegacyCmdFromPdcButPreservesOptionalExternalModelData() {
        Fixture f = new Fixture();
        JsonObject state = equippedGun();
        state.addProperty("cmd", 90001);
        Carrier carrier = new Carrier(Material.STICK, 1, 4567).state(state);

        f.store.write(carrier.stack, state);

        assertFalse(state.has("cmd"));
        assertEquals(state, carrier.persisted());
        assertEquals(state, f.store.read(carrier.stack));
        assertEquals(4567, carrier.meta.getCustomModelData());
        assertEquals(1, carrier.stack.getMaxStackSize());
        verify(carrier.meta, never()).setCustomModelData(nullable(Integer.class));
        verify(carrier.stack).setItemMeta(carrier.meta);
    }

    @Test
    void createWritesRecognizableItemsWithoutAnyCustomModelDataSetter() {
        Fixture f = new Fixture();
        List<Carrier> carriers = new ArrayList<>();
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class, (stack, context) ->
                carriers.add(new Carrier(stack, (Material) context.arguments().getFirst(), 1, null)))) {
            ItemStack gun = f.store.create("gun", GUN, 1);
            ItemStack ammo = f.store.create("ammo", AMMO, 48);
            ItemStack attachment = f.store.create("attachment", SCOPE, 64);

            assertEquals(3, construction.constructed().size());
            JsonObject gunState = f.store.read(gun);
            assertNotNull(gunState);
            assertEquals(GUN, gunState.get("id").getAsString());
            assertEquals(0, gunState.get("ammo").getAsInt());
            assertFalse(gunState.get("chamber").getAsBoolean());
            assertEquals(item("ammo", AMMO), f.store.read(ammo));
            assertEquals(item("attachment", SCOPE), f.store.read(attachment));
            assertEquals(48, ammo.getAmount());
            assertEquals(64, attachment.getAmount());
            for (Carrier carrier : carriers) {
                assertFalse(carrier.persisted().has("cmd"));
                assertFalse(carrier.meta.hasCustomModelData());
                verify(carrier.meta, never()).setCustomModelData(nullable(Integer.class));
            }
        }
    }

    @Test
    void writeStillRejectsUnknownItemsAndWrongCarrierMaterial() {
        Fixture f = new Fixture();
        JsonObject unknown = item("ammo", "test:missing");
        Carrier carrier = new Carrier(Material.PAPER, 1, null);
        assertThrows(IllegalArgumentException.class, () -> f.store.write(carrier.stack, unknown));
        assertThrows(IllegalArgumentException.class, () -> f.store.write(carrier.stack, gun()));
        assertNull(carrier.raw());
        assertThrows(IllegalArgumentException.class, () -> f.store.create("gun", GUN, 2));
        assertThrows(IllegalArgumentException.class, () -> f.store.create("ammo", "test:missing", 1));
    }

    private static JsonObject item(String kind, String id) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind);
        result.addProperty("id", id);
        return result;
    }

    private static JsonObject gun() {
        JsonObject result = item("gun", GUN);
        result.addProperty("instance", INSTANCE);
        result.addProperty("ammo", 17);
        result.addProperty("chamber", true);
        result.addProperty("fireMode", "auto");
        result.addProperty("heat", 0);
        result.add("attachments", new JsonObject());
        return result;
    }

    private static JsonObject equippedGun() {
        JsonObject result = gun();
        result.addProperty("ammo", 37);
        result.getAsJsonObject("attachments").addProperty("scope", SCOPE);
        result.getAsJsonObject("attachments").addProperty("extended_mag", MAGAZINE);
        return result;
    }

    private static final class Fixture {
        final DefaultGunPack pack = mock(DefaultGunPack.class);
        final PaperItemStore store = new PaperItemStore(mock(JavaPlugin.class), pack);
        final JsonObject gunData = JsonParser.parseString("""
                {"ammo":"test:round","ammo_amount":30,"extended_mag_ammo_amount":[40,50,60],
                 "fire_mode":["auto","semi"],"allow_attachment_types":["scope","extended_mag"]}
                """).getAsJsonObject();

        Fixture() {
            Map<String, JsonObject> guns = Map.of(GUN, new JsonObject());
            Map<String, JsonObject> ammunition = Map.of(AMMO, JsonParser.parseString("{\"stack_size\":48}").getAsJsonObject());
            Map<String, JsonObject> attachments = Map.of(
                    SCOPE, JsonParser.parseString("{\"type\":\"scope\"}").getAsJsonObject(),
                    MAGAZINE, JsonParser.parseString("{\"type\":\"extended_mag\"}").getAsJsonObject());
            when(pack.gunIndexes()).thenReturn(guns);
            when(pack.ammoIndexes()).thenReturn(ammunition);
            when(pack.attachmentIndexes()).thenReturn(attachments);
            when(pack.gun(GUN)).thenReturn(gunData);
            when(pack.attachment(MAGAZINE)).thenReturn(JsonParser.parseString("{\"extended_mag_level\":1}").getAsJsonObject());
            when(pack.allowedAttachment(eq(GUN), anyString())).thenAnswer(call -> attachments.containsKey(call.getArgument(1)));
            when(pack.hasItem(anyString(), anyString())).thenAnswer(call -> {
                String kind = call.getArgument(0), id = call.getArgument(1);
                return switch (kind) {
                    case "gun" -> guns.containsKey(id);
                    case "ammo" -> ammunition.containsKey(id);
                    case "attachment" -> attachments.containsKey(id);
                    case "box" -> DefaultGunPack.BOX_IDS.contains(id);
                    default -> false;
                };
            });
        }
    }

    private static final class Carrier {
        final ItemStack stack;
        final ItemMeta meta = mock(ItemMeta.class);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, String> data = new HashMap<>();

        Carrier(Material material, int count, Integer customModelData) {
            this(mock(ItemStack.class), material, count, customModelData);
        }

        Carrier(ItemStack stack, Material material, int count, Integer customModelData) {
            this.stack = stack;
            AtomicInteger amount = new AtomicInteger(count), maximum = new AtomicInteger(64);
            when(stack.getType()).thenReturn(material);
            when(stack.hasItemMeta()).thenReturn(true);
            when(stack.getItemMeta()).thenReturn(meta);
            when(stack.setItemMeta(meta)).thenReturn(true);
            when(stack.getAmount()).thenAnswer(call -> amount.get());
            when(stack.getMaxStackSize()).thenAnswer(call -> maximum.get());
            doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(meta.hasCustomModelData()).thenReturn(customModelData != null);
            if (customModelData != null) when(meta.getCustomModelData()).thenReturn(customModelData);
            doAnswer(call -> { maximum.set(call.getArgument(0)); return null; }).when(meta).setMaxStackSize(anyInt());
            when(pdc.get(PaperItemStore.BRIDGE_KEY, PersistentDataType.STRING)).thenAnswer(call -> raw());
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(pdc).set(eq(PaperItemStore.BRIDGE_KEY), eq(PersistentDataType.STRING), anyString());
        }

        Carrier state(JsonObject state) { data.put(PaperItemStore.BRIDGE_KEY, state.toString()); return this; }
        String raw() { return data.get(PaperItemStore.BRIDGE_KEY); }
        JsonObject persisted() { return JsonParser.parseString(raw()).getAsJsonObject(); }
    }
}
