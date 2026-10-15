package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryMenuSessionTest {
    @Test void onlyOneTransactionCanBeInFlightAndTokensRotateOnAcknowledgement() {
        InventoryMenuSession session = new InventoryMenuSession(menu("workbench", "first", -1, ""), 0);
        JsonObject request = session.begin("craft", new JsonObject(), 10);
        assertEquals("first", request.get("token").getAsString());
        assertNull(session.begin("craft", new JsonObject(), 11));
        assertTrue(session.accept(refresh("workbench", "first", "second", -1, ""), 12));
        assertFalse(session.pending());
        assertEquals("second", session.begin("craft", new JsonObject(), 13).get("token").getAsString());
    }

    @Test void lateResponseCannotResurrectClosedMenu() {
        InventoryMenuSession session = new InventoryMenuSession(menu("workbench", "first", -1, ""), 0);
        session.begin("craft", new JsonObject(), 1);
        assertEquals("close_workbench", session.close().get("op").getAsString());
        assertFalse(session.accept(refresh("workbench", "first", "second", -1, ""), 2));
        assertNull(session.begin("craft", new JsonObject(), 3));
        assertNull(session.close());
    }

    @Test void unrelatedOrDuplicateRepliesCannotOverwriteActiveSession() {
        InventoryMenuSession session = new InventoryMenuSession(menu("workbench", "first", -1, ""), 0);
        session.begin("craft", new JsonObject(), 1);
        assertFalse(session.accept(refresh("workbench", "other", "second", -1, ""), 2));
        assertFalse(session.accept(refresh("box", "first", "second", -1, ""), 2));
        assertFalse(session.accept(refresh("workbench", "first", "first", -1, ""), 2));
        assertFalse(session.accept(menu("workbench", "second", -1, ""), 2));
        assertTrue(session.pending());
        JsonObject response = refresh("workbench", "first", "second", -1, "");
        assertTrue(session.accept(response, 3));
        assertFalse(session.accept(response, 4));
        assertEquals("second", session.data().get("token").getAsString());
    }

    @Test void boxRefreshMustKeepItsSlotAndInstance() {
        InventoryMenuSession session = new InventoryMenuSession(menu("box", "first", 3, "box-a"), 0);
        JsonObject request = session.begin("box_store", new JsonObject(), 1);
        assertEquals("box-a", request.get("instance").getAsString());
        assertEquals(3, request.get("slot").getAsInt());
        assertFalse(session.accept(refresh("box", "first", "second", 4, "box-a"), 2));
        assertFalse(session.accept(refresh("box", "first", "second", 3, "box-b"), 2));
        assertTrue(session.accept(refresh("box", "first", "second", 3, "box-a"), 3));
        assertThrows(IllegalArgumentException.class, () -> session.begin("craft", new JsonObject(), 4));
    }

    @Test void onlyMatchingErrorsUnlockThePendingTransaction() {
        InventoryMenuSession session = new InventoryMenuSession(menu("workbench", "first", -1, ""), 0);
        session.begin("craft", new JsonObject(), 1);
        JsonObject error = new JsonObject();
        error.addProperty("op", "craft"); error.addProperty("token", "old");
        assertFalse(session.acceptError(error));
        error.addProperty("op", "box_take"); error.addProperty("token", "first");
        assertFalse(session.acceptError(error));
        assertTrue(session.pending());
        error.addProperty("op", "craft");
        assertTrue(session.acceptError(error));
        assertFalse(session.pending());
        assertFalse(session.acceptError(error));
    }

    @Test void refreshAndTimeoutUseDeadlinesWithoutRetryingCraft() {
        InventoryMenuSession session = new InventoryMenuSession(menu("workbench", "first", -1, ""), 1_000);
        assertFalse(session.refreshDue(90_999));
        assertTrue(session.refreshDue(91_000));
        session.begin("refresh_workbench", new JsonObject(), 91_000);
        assertFalse(session.refreshDue(100_000));
        assertFalse(session.timedOut(100_999));
        assertTrue(session.timedOut(101_000));
        assertTrue(session.accept(refresh("workbench", "first", "second", -1, ""), 101_001));
        assertFalse(session.timedOut(101_002));
        assertFalse(session.refreshDue(150_000));
    }

    @Test void displaySnapshotsAndRequestArgumentsCannotMutateIdentity() {
        JsonObject original = menu("workbench", "first", -1, "");
        InventoryMenuSession session = new InventoryMenuSession(original, 0);
        original.addProperty("token", "changed");
        session.data().addProperty("token", "also-changed");
        JsonObject arguments = new JsonObject(); arguments.addProperty("token", "fake");
        JsonObject request = session.begin("craft", arguments, 1);
        assertEquals("first", request.get("token").getAsString());
        assertEquals("fake", arguments.get("token").getAsString());
    }

    private static JsonObject menu(String kind, String token, int slot, String instance) {
        JsonObject menu = new JsonObject();
        menu.addProperty("menu", kind); menu.addProperty("token", token); menu.addProperty("slot", slot);
        if (!instance.isEmpty()) { JsonObject state = new JsonObject(); state.addProperty("instance", instance); menu.add("state", state); }
        return menu;
    }

    private static JsonObject refresh(String kind, String oldToken, String newToken, int slot, String instance) {
        JsonObject menu = menu(kind, newToken, slot, instance);
        menu.addProperty("refresh", true); menu.addProperty("previousToken", oldToken);
        return menu;
    }
}
