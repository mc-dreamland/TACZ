package com.tacz.guns.client.render.scope;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.renderer.snapshot.BedrockRenderSnapshot;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.pojo.model.CubesItem;
import com.tacz.guns.client.resource.pojo.model.FaceUVsItem;
import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReticleMarkFilterTest {
    private static final String ASSETS = "/assets/tacz/custom/tacz_default_gun/assets/bujidao/";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(CubesItem.class, new CubesItem.Deserializer()).create();

    @ParameterizedTest
    @ValueSource(strings = {"ar_02jing", "sr02", "sr03", "sr04"})
    void shippedTexturedReticleSurvivesAndEveryBlackoutPanelIsRejected(String scope) throws Exception {
        BedrockModelPOJO pojo;
        try (var reader = new InputStreamReader(resource("geo_models/attachment/" + scope + "_geo.json"), StandardCharsets.UTF_8)) {
            pojo = GSON.fromJson(reader, BedrockModelPOJO.class);
        }
        BedrockPart division = new BedrockModel(pojo, BedrockVersion.NEW).getNode("division");
        assertNotNull(division);
        ScopeNodeSet nodes = new ScopeNodeSet(List.of(division), List.of());
        BufferedImage texture;
        try (var stream = resource("textures/attachment/uv/" + scope + ".png")) {
            texture = ImageIO.read(stream);
        }
        var filter = ReticleMarkFilter.create(nodes, texture);

        assertFalse(ReticleMarkFilter.isThinMark(division.cubes.getFirst()), "Reproduce the old size-filter failure");
        assertTrue(filter.test(division.cubes.getFirst()), "Keep the complete transparent reticle plane");
        for (int i = 1; i < division.cubes.size(); i++) {
            assertFalse(filter.test(division.cubes.get(i)), "Reject blackout panel " + i);
        }

        // The one filtered snapshot is shared by ordinary, late-hand and final-overlay paths.
        BedrockRenderSnapshot original = BedrockRenderSnapshot.captureSubtree(division, new PoseStack(),
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, 15728880, 0, 1, 1, 1, 1);
        BedrockRenderSnapshot filtered = original.filtered(filter);
        assertEquals(24, vertexCount(filtered));
        assertEquals(24 * division.cubes.size(), vertexCount(original), "Filtering must not mutate the original snapshot/model");

        var fallback = ReticleMarkFilter.create(nodes, null);
        assertTrue(division.cubes.stream().noneMatch(fallback), "Unreadable texture keeps the conservative geometry fallback");
    }

    @Test
    void thinLinesInChildNodesSurviveEvenWithOpaqueOrMissingTexture() {
        BedrockPart parent = new BedrockPart("division");
        BedrockPart child = new BedrockPart("line");
        parent.children.add(child);
        FaceUVsItem faces = GSON.fromJson("""
                {"south":{"uv":[0,0],"uv_size":[1,1]}}
                """, FaceUVsItem.class);
        // The zero-area placeholder faces at the origin must not expand this thin line's bounds.
        var line = new BedrockCubePerFace(-16, 10, -111, 32, 0.2f, 0, 0, 16, 16, faces);
        child.cubes.add(line);
        var nodes = new ScopeNodeSet(List.of(parent), List.of());
        assertTrue(ReticleMarkFilter.create(nodes, null).test(line));
        BufferedImage opaque = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        assertTrue(ReticleMarkFilter.create(nodes, opaque).test(line));
    }

    private static int vertexCount(BedrockRenderSnapshot snapshot) {
        AtomicInteger count = new AtomicInteger();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex")) {
                        count.incrementAndGet();
                    }
                    return proxy;
                });
        snapshot.write(consumer);
        return count.get();
    }

    private static InputStream resource(String path) {
        InputStream stream = ReticleMarkFilterTest.class.getResourceAsStream(ASSETS + path);
        assertNotNull(stream, path);
        return stream;
    }
}
