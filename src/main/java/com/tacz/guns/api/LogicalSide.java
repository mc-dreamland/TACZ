package com.tacz.guns.api;

/** 26.1 NeoForge no longer ships {@code net.neoforged.fml.LogicalSide}. Keep TACZ's side flag. */
public enum LogicalSide {
    CLIENT,
    SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER;
    }
}
