package com.tacz.guns.client.particle;

import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulletHoleParticleTest {
    @ParameterizedTest
    @EnumSource(Direction.class)
    void decalLiesFlatOnEveryHitFaceAndFacesOutward(Direction face) {
        Quaternionf rotation = BulletHoleParticle.surfaceRotation(face);
        Vector3f normal = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());

        // Vertex order and base plane used by Minecraft's QuadParticleRenderState.
        Vector3f[] corners = {
                new Vector3f(1, -1, 0), new Vector3f(1, 1, 0),
                new Vector3f(-1, 1, 0), new Vector3f(-1, -1, 0)
        };
        for (Vector3f corner : corners) {
            corner.rotate(rotation);
            assertEquals(0, corner.dot(normal), 1.0e-6,
                    "Every corner must stay in the hit face's plane: " + face);
        }

        Vector3f edge1 = new Vector3f(corners[1]).sub(corners[0]);
        Vector3f edge2 = new Vector3f(corners[2]).sub(corners[0]);
        Vector3f renderedNormal = edge1.cross(edge2).normalize();
        assertEquals(1, renderedNormal.dot(normal), 1.0e-6,
                "The visible side must face out of the block: " + face);
    }
}
