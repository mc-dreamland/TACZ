package com.tacz.guns.client.event;

import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import com.tacz.guns.client.resource.ClientIndexManager;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.network.CommonNetworkCache;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Clears client-side synchronized pack state whenever the client leaves a world. */
public final class CommonNetworkCacheEvent {
    private CommonNetworkCacheEvent() {
    }

    public static void onClientPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {

        // An integrated server shares the static CommonAssetsManager instance with the
        // client, so do not clear that server-owned instance while it is still shutting
        // down. The client network cache must nevertheless be cleared on memory
        // connections; otherwise the next world in the same JVM can reuse the previous
        // world's gun state and recipe/index data.
        if (event.getConnection() != null && !event.getConnection().isMemoryConnection()) {
            CommonAssetsManager.clearInstance();
        }
        CommonNetworkCache.INSTANCE.clear();
        ClientIndexManager.clear();
        // Reset the static base timestamp so a stale value from a previous world does
        // not corrupt the first shoot packet sent after joining the next world.
        LocalPlayerDataHolder.clientBaseTimestamp = -1L;
    }
}
