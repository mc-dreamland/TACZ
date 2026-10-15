package com.tacz.guns.bridge;

import com.google.gson.JsonObject;

import java.util.Set;

/** Client transaction bookkeeping, independent of Minecraft screens and registries. */
public final class InventoryMenuSession {
    private JsonObject menu;
    private final String kind;
    private final int slot;
    private final String instance;
    private long receivedAt;
    private long pendingAt;
    private String operation = "";
    private boolean closed;

    public InventoryMenuSession(JsonObject menu, long now) {
        kind = string(menu, "menu");
        if (!Set.of("workbench", "box").contains(kind) || string(menu, "token").isEmpty())
            throw new IllegalArgumentException("Invalid inventory menu");
        slot = menu.get("slot").getAsInt();
        instance = instance(menu);
        this.menu = menu.deepCopy();
        receivedAt = now;
    }

    public JsonObject data() { return menu.deepCopy(); }
    public String kind() { return kind; }
    public String operation() { return operation; }
    public boolean pending() { return !operation.isEmpty(); }
    public boolean closed() { return closed; }
    public boolean refreshDue(long now) { return !closed && !pending() && now - receivedAt >= 90_000; }
    public boolean timedOut(long now) { return !closed && pending() && now - pendingAt >= 10_000; }

    public JsonObject begin(String op, JsonObject arguments, long now) {
        if (closed || pending()) return null;
        boolean allowed = op.equals("refresh_" + kind) || (kind.equals("workbench") ? op.equals("craft")
                : Set.of("box_store", "box_take", "box_fill").contains(op));
        if (!allowed) throw new IllegalArgumentException("Operation does not belong to this menu");
        JsonObject request = arguments.deepCopy();
        request.addProperty("op", op);
        request.addProperty("token", string(menu, "token"));
        request.addProperty("slot", slot);
        if (!instance.isEmpty()) request.addProperty("instance", instance);
        operation = op;
        pendingAt = now;
        return request;
    }

    public boolean accept(JsonObject response, long now) {
        if (closed || !kind.equals(string(response, "menu")) || !response.has("refresh")
                || !response.get("refresh").getAsBoolean()
                || !string(menu, "token").equals(string(response, "previousToken"))
                || !response.has("slot") || response.get("slot").getAsInt() != slot
                || !instance.equals(instance(response)) || string(response, "token").isEmpty()
                || string(menu, "token").equals(string(response, "token"))) return false;
        menu = response.deepCopy();
        operation = "";
        receivedAt = now;
        return true;
    }

    public boolean acceptError(JsonObject error) {
        if (closed || !pending() || !operation.equals(string(error, "op"))
                || !string(menu, "token").equals(string(error, "token"))) return false;
        operation = "";
        return true;
    }

    public JsonObject close() {
        if (closed) return null;
        closed = true;
        operation = "";
        JsonObject request = new JsonObject();
        request.addProperty("op", "close_" + kind);
        request.addProperty("token", string(menu, "token"));
        return request;
    }

    private static String instance(JsonObject menu) {
        return menu.has("state") && menu.get("state").isJsonObject() ? string(menu.getAsJsonObject("state"), "instance") : "";
    }

    private static String string(JsonObject data, String key) {
        return data.has(key) && data.get(key).isJsonPrimitive() && data.getAsJsonPrimitive(key).isString()
                ? data.get(key).getAsString() : "";
    }
}
