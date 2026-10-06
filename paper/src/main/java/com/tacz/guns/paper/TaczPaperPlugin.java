package com.tacz.guns.paper;

import com.tacz.guns.paper.command.TaczCommand;
import com.tacz.guns.paper.gameplay.PaperGunService;
import com.tacz.guns.paper.gameplay.HitboxProfiles;
import com.tacz.guns.paper.inventory.PaperInventoryService;
import com.tacz.guns.paper.inventory.PaperItemGuard;
import com.tacz.guns.paper.inventory.PaperSupplyMenu;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.PaperNetwork;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Objects;
import java.util.logging.Level;

/** Paper server implementation for server-configured TACZ gun catalogs. */
public final class TaczPaperPlugin extends JavaPlugin implements Listener {
    private DefaultGunPack pack;
    private PaperNetwork network;
    private PaperGunService guns;
    private PaperInventoryService inventory;
    private PaperSupplyMenu supplies;

    @Override
    public void onEnable() {
        if (!Bukkit.getMinecraftVersion().equals("1.21.11")) {
            getLogger().severe("TACZ Paper requires Paper 1.21.11; found " + Bukkit.getMinecraftVersion());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        try {
            pack = new DefaultGunPack(this);
            pack.load();
            PaperItemStore items = new PaperItemStore(this, pack);
            network = new PaperNetwork(this, pack);
            guns = new PaperGunService(this, pack, items, network);
            inventory = new PaperInventoryService(this, pack, items, network);
            supplies = new PaperSupplyMenu(this, pack, items);
            new PaperItemGuard(this, items, inventory, network);
            network.handlers(guns::handle, (player, request) -> {
                // Discarding a late menu response must not interrupt the player's current gun.
                if (!PaperItemStore.string(request, "op", "").equals("close_refit")) guns.reset(player);
                inventory.handle(player, request);
            }, guns::sync, guns::sync, player -> {
                guns.resetConnection(player);
                inventory.close(player);
            });
            TaczCommand commands = new TaczCommand(this, pack, items, inventory, supplies, network, this::reloadTacz);
            Objects.requireNonNull(getCommand("tacz")).setExecutor(commands);
            Objects.requireNonNull(getCommand("tacz")).setTabCompleter(commands);
            Bukkit.getPluginManager().registerEvents(this, this);
            Bukkit.getScheduler().runTaskTimer(this, () -> { network.tick(); guns.tick(); network.flushBallistics(); }, 1, 1);
            getLogger().info("Loaded " + pack.gunIndexes().size() + " guns from " + getDataFolder() + ". Client: matching TACZ bridge + ViaForge on 1.20.1.");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Unable to start TACZ Paper", exception);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void reloadTacz() {
        HitboxProfiles profiles;
        try {
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.load(new File(getDataFolder(), "config.yml"));
            profiles = HitboxProfiles.load(configuration);
            // Loading is staged, including transfer-size validation. A bad file must not
            // clear menus, interrupt shots or replace any currently active catalog data.
            pack.load();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "TACZ catalog reload failed; retaining the previous catalog", exception);
            throw new IllegalArgumentException("枪械配置加载失败，已保留原配置：" + exception.getMessage(), exception);
        }
        try {
            reloadConfig();
            guns.hitboxProfiles(profiles);
            inventory.clear();
            supplies.clear();
            for (var player : Bukkit.getOnlinePlayers()) guns.reset(player);
            network.reload();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "TACZ reload failed", exception);
            throw new IllegalStateException("TACZ reload failed; check server log", exception);
        }
    }

    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (inventory != null) inventory.clear();
        if (supplies != null) supplies.clear();
        if (guns != null) guns.shutdown();
        if (network != null) network.close();
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        if (inventory != null) inventory.close(event.getPlayer());
    }
}
