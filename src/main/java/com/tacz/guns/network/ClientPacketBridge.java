package com.tacz.guns.network;

/**
 * Dedicated-safe reflection bridge. Class.forName keeps client types out of
 * this class's constant pool so Dist cleaner / dedicated classpath cannot
 * throw NoClassDefFoundError: LocalPlayer at payload registration.
 */
public final class ClientPacketBridge {
    private ClientPacketBridge() {
    }

    public static void invoke(String method, Class<?>[] types, Object... args) {
        if (net.neoforged.fml.loading.FMLEnvironment.getDist() != net.neoforged.api.distmarker.Dist.CLIENT) {
            return;
        }
        try {
            Class<?> handlers = Class.forName("com.tacz.guns.client.network.ClientPacketHandlers");
            handlers.getMethod(method, types).invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            // A missing client dependency must surface instead of silently dropping gameplay packets.
            throw new IllegalStateException("Could not dispatch TACZ client payload to " + method, e);
        }
    }
}
