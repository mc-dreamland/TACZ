package com.tacz.guns.client.render.scope;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.List;
import java.util.function.UnaryOperator;

/** Retains vanilla arm submission and hooks while masking skin fragments inside the ocular. */
public final class ScopeHandCollector implements SubmitNodeCollector {
    private final SubmitNodeCollector root;
    private final OrderedSubmitNodeCollector delegate;
    private final UnaryOperator<RenderType> renderTypes;

    ScopeHandCollector(SubmitNodeCollector delegate, UnaryOperator<RenderType> renderTypes) {
        this(delegate, delegate, renderTypes);
    }

    private ScopeHandCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector delegate,
                               UnaryOperator<RenderType> renderTypes) {
        this.root = root;
        this.delegate = delegate;
        this.renderTypes = renderTypes;
    }

    public static SubmitNodeCollector forSkin(SubmitNodeCollector collector, Identifier skin) {
        if (!ScopeRenderTypes.hasScheduledViewmodelAperture()) {
            return collector;
        }
        RenderType translucentSkin = RenderTypes.entityTranslucent(skin);
        RenderType cutoutSkin = RenderTypes.entityCutout(skin);
        return new ScopeHandCollector(collector, original -> {
            // A mod's custom arm hook may also submit unrelated textures or effects.
            // Only replace the known vanilla skin materials, using the actual player skin.
            if (original == translucentSkin || original == cutoutSkin) {
                return ScopeRenderTypes.clipForViewmodel(original, skin, true);
            }
            return original;
        });
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new ScopeHandCollector(root, root.order(order), renderTypes);
    }

    @Override
    public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        delegate.submitShadow(pose, radius, pieces);
    }

    @Override
    public void submitNameTag(PoseStack pose, @Nullable Vec3 offset, int distance, Component text,
                              boolean discrete, int light, double cameraDistance, CameraRenderState camera) {
        delegate.submitNameTag(pose, offset, distance, text, discrete, light, cameraDistance, camera);
    }

    @Override
    public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow,
                           Font.DisplayMode mode, int light, int color, int background, int outline) {
        delegate.submitText(pose, x, y, text, shadow, mode, light, color, background, outline);
    }

    @Override
    public void submitFlame(PoseStack pose, EntityRenderState entity, Quaternionf rotation) {
        delegate.submitFlame(pose, entity, rotation);
    }

    @Override
    public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash) {
        delegate.submitLeash(pose, leash);
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type,
                                int light, int overlay, int color, @Nullable TextureAtlasSprite sprite,
                                int outline, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
        delegate.submitModel(model, state, pose, renderTypes.apply(type), light, overlay, color, sprite, outline, crumbling);
    }

    @Override
    public void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay,
                                @Nullable TextureAtlasSprite sprite, boolean foil, boolean glint,
                                int color, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling, int outline) {
        delegate.submitModelPart(part, pose, renderTypes.apply(type), light, overlay, sprite,
                foil, glint, color, crumbling, outline);
    }

    @Override
    public void submitBlock(PoseStack pose, BlockState state, int light, int overlay, int outline) {
        delegate.submitBlock(pose, state, light, overlay, outline);
    }

    @Override
    public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state) {
        delegate.submitMovingBlock(pose, state);
    }

    @Override
    public void submitBlockModel(PoseStack pose, RenderType type, BlockStateModel model,
                                 float red, float green, float blue, int light, int overlay, int outline) {
        delegate.submitBlockModel(pose, type, model, red, green, blue, light, overlay, outline);
    }

    @Override
    public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline,
                           int[] tint, List<BakedQuad> quads, RenderType type, ItemStackRenderState.FoilType foil) {
        delegate.submitItem(pose, context, light, overlay, outline, tint, quads, type, foil);
    }

    @Override
    public void submitCustomGeometry(PoseStack pose, RenderType type, CustomGeometryRenderer geometry) {
        delegate.submitCustomGeometry(pose, renderTypes.apply(type), geometry);
    }

    @Override
    public void submitParticleGroup(ParticleGroupRenderer particles) {
        delegate.submitParticleGroup(particles);
    }
}
