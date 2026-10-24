package com.tacz.guns.client.render.scope;

import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.GunMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared geometry/texture filter for reticle submissions.
 *
 * <p>Thin lines use the geometry threshold below. Large transparent reticle quads are identified
 * by the alpha pattern in their mapped texture region; opaque blackout panels remain rejected.
 * Decisions are cached on the attachment model and frozen into snapshots before submission.</p>
 *
 * <h2>为什么蚀刻与发光两类准星都必须过这个筛子</h2>
 * {@code division} 系节点里混着<b>遮光板</b>（挡住镜外视野的大面），上游靠 stencil 把它们裁在
 * 目镜圆外；本移植用屏幕空间 mask 裁剪，并提前去掉纯色遮光板以节省提交与光栅化。
 * 完整贴图式准星仍依赖 mask；mask 无效时由绘制入口跳过该帧，避免越出镜口。
 *
 * <p>此前只有 {@link EtchedReticleRenderer} 过滤；实测 {@code scope_vudu} 把遮光板放进了
 * <b>发光</b>节点：{@code division_illuminated} 共 6 个 cube，其中 5 块是
 * {@code [50,50,0] / [50,100,0]×2 / [100,250,0]×2}，被 {@code IlluminatedReticleRenderer}
 * 整树提交成大块亮面 —— 因此过滤抽到这里共用。</p>
 *
 * <h2>阈值的出处（默认枪包全量 73 个准星 cube 的实测分布）</h2>
 * 原版细线几何按「AABB 第二长轴」排序后存在一个分界（不适用于整张贴图式准星）：
 * <pre>
 *   最大的合法标线 middle = 5.804   （scope_qmk152 的圆环弧段 [3.40021, 4.80769, 0]）
 *   最小的遮光板   middle = 8.800   （scope_98k 的 [9.218, 8.8, 0.044]）
 * </pre>
 * 阈值取两者之间的 {@value #THIN_MARK_MAX_EXTENT}：既能保住 QKM 的整圈弧段
 * （旧阈值 4.0 会误杀 middle 4.67~5.80 的弧段，圆环出现缺口），
 * 又能拦下全部遮光板（98k / 1873 / aug / qmk / vudu 的最小面板都在 8.8 以上）。
 *
 * <p>坐标单位是模型 json 单位（1 单位 = 1/16 格），与 {@code BedrockCube} 顶点存储一致。</p>
 */
public final class ReticleMarkFilter {
    /** 准星标线允许的 AABB 第二长轴上限（模型单位）。 */
    static final float THIN_MARK_MAX_EXTENT = 6.0F;

    private ReticleMarkFilter() {
    }

    /** Resolve once per model/texture, so PNG decoding and pixel scans never run every frame. */
    public static Predicate<BedrockCube> forTexture(@Nullable Identifier texture, ScopeNodeSet nodes) {
        if (texture != null) {
            try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(texture).open()) {
                return create(nodes, ImageIO.read(stream));
            } catch (IOException e) {
                GunMod.LOGGER.warn("Unable to inspect scope reticle texture {}; retaining thin geometry only", texture, e);
            }
        }
        return create(nodes, null);
    }

    static Predicate<BedrockCube> create(ScopeNodeSet nodes, @Nullable BufferedImage texture) {
        Set<BedrockCube> cubes = Collections.newSetFromMap(new IdentityHashMap<>());
        nodes.etchedReticle().forEach(part -> collectCubes(part, cubes));
        nodes.illuminatedReticle().forEach(part -> collectCubes(part, cubes));
        // A complete crosshair may be a large transparent quad (BJD), rather than thin geometry.
        // Solid blackout panels sample only opaque pixels and must still be rejected.
        cubes.removeIf(cube -> !isThinMark(cube) && !hasCutoutPattern(cube, texture));
        return cubes::contains;
    }

    private static void collectCubes(BedrockPart part, Set<BedrockCube> cubes) {
        cubes.addAll(part.cubes);
        part.children.forEach(child -> collectCubes(child, cubes));
    }

    private static boolean hasCutoutPattern(BedrockCube cube, @Nullable BufferedImage texture) {
        if (texture == null) {
            return false;
        }
        for (var polygon : cube.getPolygons()) {
            if (polygon == null || isDegenerateEmptyFace(polygon)) {
                continue;
            }
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            for (var vertex : polygon.vertices) {
                minU = Math.min(minU, vertex.u);
                minV = Math.min(minV, vertex.v);
                maxU = Math.max(maxU, vertex.u);
                maxV = Math.max(maxV, vertex.v);
            }
            if (!Float.isFinite(minU) || !Float.isFinite(minV)
                    || !Float.isFinite(maxU) || !Float.isFinite(maxV)) {
                continue;
            }
            int x0 = Math.clamp((int) Math.floor(minU * texture.getWidth()), 0, texture.getWidth());
            int y0 = Math.clamp((int) Math.floor(minV * texture.getHeight()), 0, texture.getHeight());
            int x1 = Math.clamp((int) Math.ceil(maxU * texture.getWidth()), 0, texture.getWidth());
            int y1 = Math.clamp((int) Math.ceil(maxV * texture.getHeight()), 0, texture.getHeight());
            boolean clear = false, visible = false;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    // Match the entity cutout pipeline's 0.1 alpha threshold.
                    int alpha = texture.getRGB(x, y) >>> 24;
                    clear |= alpha < 26;
                    visible |= alpha >= 26;
                    if (clear && visible) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Division 树把细的准星标线与巨大的遮光板混在一起。只保留「三条 AABB 轴中第二长轴
     * 不超过 {@value #THIN_MARK_MAX_EXTENT}」的线/环类 cube；更大的面交由贴图透明度判定。
     *
     * <p>退化占位面不参与测量：只声明了部分 UV 面（例如只有 {@code south}）的 cube 会给
     * 未声明的面生成四个顶点全部钉在 {@code (0,0,0)} 的零面积 polygon。对未旋转的 cube 来说，
     * 该点等于<b>骨骼 pivot</b>，可能离几何很远（scope_1873_6x 的 division pivot 在模型原点，
     * 而十字线在 z=-111）——把它计入包围盒会把盒子撑到 32×10.97×111，
     * 从而误杀横线（当年「竖线在、横线没」的直接原因）。</p>
     */
    static boolean isThinMark(BedrockCube cube) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        boolean foundVertex = false;

        for (var polygon : cube.getPolygons()) {
            if (polygon == null || isDegenerateEmptyFace(polygon)) {
                continue;
            }
            for (var vertex : polygon.vertices) {
                if (vertex == null) {
                    continue;
                }
                foundVertex = true;
                minX = Math.min(minX, vertex.pos.x());
                minY = Math.min(minY, vertex.pos.y());
                minZ = Math.min(minZ, vertex.pos.z());
                maxX = Math.max(maxX, vertex.pos.x());
                maxY = Math.max(maxY, vertex.pos.y());
                maxZ = Math.max(maxZ, vertex.pos.z());
            }
        }
        if (!foundVertex) {
            return false;
        }

        float x = maxX - minX;
        float y = maxY - minY;
        float z = maxZ - minZ;
        float largest = Math.max(x, Math.max(y, z));
        float smallest = Math.min(x, Math.min(y, z));
        float middle = x + y + z - largest - smallest;
        return middle <= THIN_MARK_MAX_EXTENT;
    }

    /**
     * 真实面可能横跨数个单位，但未映射 UV 的占位面四个顶点全部钉在精确的
     * {@code (0,0,0)}。这种 polygon 面积为零、永远不会光栅化，因此不得参与包围盒测量。
     */
    private static boolean isDegenerateEmptyFace(com.tacz.guns.client.model.bedrock.BedrockPolygon polygon) {
        if (polygon.vertices == null || polygon.vertices.length == 0) {
            return true;
        }
        for (var vertex : polygon.vertices) {
            if (vertex == null) {
                continue;
            }
            if (vertex.pos.x() != 0.0F || vertex.pos.y() != 0.0F || vertex.pos.z() != 0.0F) {
                return false;
            }
        }
        return true;
    }
}
