package com.tacz.guns.network;

import com.tacz.guns.bridge.BridgeProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativePayloadGuardTest {
    private record Payload(Type<Payload> type) implements CustomPacketPayload {}

    private static final Payload DRAW = new Payload(new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("tacz", "client_player_draw_gun")));
    private static final Payload SHOOT = new Payload(new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("tacz", "client_player_shoot")));

    @Test
    void paperBridgeChannelDoesNotAuthorizeNativeDraw() {
        var channels = Set.of(Identifier.parse(BridgeProtocol.CHANNEL));
        assertFalse(NetworkHandler.sendIfSupported(DRAW, channels::contains,
                payload -> fail("Paper must never receive an unnegotiated native payload")));
    }

    @Test
    void nativeServerReceivesTheOriginalPayload() {
        var sent = new ArrayList<CustomPacketPayload>();
        assertTrue(NetworkHandler.sendIfSupported(DRAW, DRAW.type().id()::equals, sent::add));
        assertEquals(1, sent.size());
        assertSame(DRAW, sent.getFirst());
    }

    @Test
    void paperHandshakeWaitsForTheServerChannelAnnouncement() {
        var channels = new java.util.HashSet<Identifier>();
        var hello = new Payload(new CustomPacketPayload.Type<>(Identifier.parse(BridgeProtocol.CHANNEL)));
        var sent = new ArrayList<CustomPacketPayload>();
        assertFalse(NetworkHandler.sendIfSupported(hello, channels::contains, sent::add));
        assertTrue(sent.isEmpty());
        channels.add(hello.type().id());
        assertTrue(NetworkHandler.sendIfSupported(hello, channels::contains, sent::add));
        assertEquals(java.util.List.of(hello), sent);
    }

    @Test
    void supportIsCheckedForEachPayloadRatherThanTheNamespace() {
        var channels = Set.of(DRAW.type().id());
        assertFalse(NetworkHandler.sendIfSupported(SHOOT, channels::contains,
                payload -> fail("A draw channel does not authorize a shoot payload")));
    }
}
