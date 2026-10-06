package com.tacz.guns.paper.network;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

public interface BridgePeer {
    boolean ready(Player player);
    void send(Player player, String type, JsonObject data);
    void broadcast(Player source, String type, JsonObject data);
    void ballistics(Player observer, JsonObject record);
}
