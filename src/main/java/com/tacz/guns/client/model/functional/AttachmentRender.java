package com.tacz.guns.client.model.functional;

import com.tacz.guns.api.item.ItemBehavior;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.IFunctionalSubmitter;
import com.tacz.guns.client.render.scope.ScopeRenderTypes;
import com.tacz.guns.client.renderer.item.AttachmentItemRenderer;
import com.tacz.guns.util.RenderDistance;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;


public class AttachmentRender implements IFunctionalSubmitter {
    private final BedrockGunModel bedrockGunModel;
    private final AttachmentType type;

    public AttachmentRender(BedrockGunModel bedrockGunModel, AttachmentType type) {
        this.bedrockGunModel = bedrockGunModel;
        this.type = type;
    }



    public static void submitAttachment(ItemStack attachmentItem,
                                        ItemStack gunItem,
                                        PoseStack poseStack,
                                        ItemDisplayContext transformType,
                                        SubmitNodeCollector collector,
                                        int light,
                                        int overlay) {
        poseStack.translate(0, -1.5, 0);
        if (!(ItemBehavior.of(attachmentItem) instanceof IAttachment iAttachment)) {
            return;
        }
        ResourceLocation attachmentId = iAttachment.getAttachmentId(attachmentItem);
        TimelessAPI.getClientAttachmentIndex(attachmentId).ifPresentOrElse(attachmentIndex -> {
            BedrockAttachmentModel model = attachmentIndex.getAttachmentModel();
            ResourceLocation texture = attachmentIndex.getModelTexture();
            if (model != null && texture != null) {
                Pair<BedrockAttachmentModel, ResourceLocation> lodModel = attachmentIndex.getLodModel();
                if (lodModel != null && !RenderDistance.inRenderHighPolyModelDistance(poseStack) && !transformType.firstPerson()) {
                    model = lodModel.getLeft();
                    texture = lodModel.getRight();
                }
                RenderType renderType = RenderType.entityCutout(texture);
                // The scope itself reaches this call before it marks the aperture, so it keeps its
                // dedicated depth-body sequence. Non-scope attachments are traversed afterwards and
                // use the same screen-space outside mask as the gun body when the aperture is active.
                renderType = ScopeRenderTypes.clipForViewmodel(renderType, texture,
                        transformType != null && transformType.firstPerson());
                model.submit(attachmentItem, gunItem, poseStack, transformType, collector,
                        renderType, texture, light, overlay);
            }
        }, () -> collector.submitCustomGeometry(
                poseStack,
                RenderType.entityTranslucent(MissingTextureAtlasSprite.getLocation()),
                (pose, buffer) -> {
                    PoseStack frozen = new PoseStack();
                    frozen.last().pose().set(pose.pose());
                    frozen.last().normal().set(pose.normal());
                    AttachmentItemRenderer.SLOT_ATTACHMENT_MODEL.renderToBuffer(
                            frozen, buffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
                }
        ));
    }

    @Override
    public void extract(ExtractionContext context) {
        ItemStack attachmentItem = bedrockGunModel.getCurrentAttachmentItem().get(type);
        if (attachmentItem == null || attachmentItem.isEmpty()) {
            return;
        }
        ItemStack frozenAttachment = attachmentItem.copy();
        ItemStack frozenGun = bedrockGunModel.getCurrentGunItem().copy();
        PoseStack frozenPose = context.poseStack();
        ItemDisplayContext displayContext = context.displayContext();
        int light = context.light();
        int overlay = context.overlay();
        context.add(collector -> {
            PoseStack taskPose = new PoseStack();
            taskPose.last().pose().set(frozenPose.last().pose());
            taskPose.last().normal().set(frozenPose.last().normal());
            submitAttachment(frozenAttachment, frozenGun, taskPose, displayContext, collector, light, overlay);
        });
    }

}
