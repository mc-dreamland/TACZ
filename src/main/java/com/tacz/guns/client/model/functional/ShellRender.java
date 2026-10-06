package com.tacz.guns.client.model.functional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.BedrockAmmoModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.IFunctionalSubmitter;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.client.resource.pojo.display.gun.ShellEjection;
import com.tacz.guns.compat.iris.IrisCompat;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.concurrent.ConcurrentLinkedDeque;

public class ShellRender implements IFunctionalSubmitter {
    // 抛壳队列
    private final ConcurrentLinkedDeque<Data> SHELL_QUEUE = new ConcurrentLinkedDeque<>();
    public static boolean isSelf = false;

    private final BedrockGunModel bedrockGunModel;

    public ShellRender(BedrockGunModel bedrockGunModel) {
        this.bedrockGunModel = bedrockGunModel;
    }

    private static boolean shellContextMatchesCamera(ItemDisplayContext displayContext) {
        boolean cameraFirstPerson = Minecraft.getInstance().options.getCameraType().isFirstPerson();
        return cameraFirstPerson == displayContext.firstPerson();
    }

    private static RenderType shellRenderType(ItemDisplayContext displayContext, ResourceLocation texture) {
        // 26.1.2 第一人称走 RenderType.itemCutout（26.1 才拆出的 ITEM_CUTOUT 管线），
        // 1.21.11 没有该管线，手持物品与实体共用 ENTITY_CUTOUT，因此两分支合一。
        // 保留 displayContext 形参与调用点不变，方便后续需要区分时再拆开。
        return RenderType.entityCutout(texture);
    }

    public void addShell(Vector3f randomVelocity) {
        if (SHELL_QUEUE.size() > 128) {
            SHELL_QUEUE.pollFirst();
        }
        double xRandom = Math.random() * randomVelocity.x();
        double yRandom = Math.random() * randomVelocity.y();
        double zRandom = Math.random() * randomVelocity.z();
        Vector3f vector3f = new Vector3f((float) xRandom, (float) yRandom, (float) zRandom);
        SHELL_QUEUE.offerLast(new Data(System.currentTimeMillis(), vector3f));
    }

    private void checkShellQueue(long lifeTime) {
        if (!SHELL_QUEUE.isEmpty()) {
            Data data = SHELL_QUEUE.peekFirst();
            if ((System.currentTimeMillis() - data.timeStamp) > lifeTime) {
                SHELL_QUEUE.pollFirst();
                checkShellQueue(lifeTime);
            }
        }
    }

    @Override
    public void extract(ExtractionContext context) {
        ItemDisplayContext displayContext = context.displayContext();
        if (IrisCompat.isRenderShadow() || !isSelf || !shellContextMatchesCamera(displayContext)) {
            return;
        }
        ItemStack currentGunItem = bedrockGunModel.getCurrentGunItem();
        IGun iGun = IGun.getIGunOrNull(currentGunItem);
        if (iGun == null) {
            return;
        }
        GunData gunData = TimelessAPI.getClientGunIndex(iGun.getGunId(currentGunItem))
                .map(ClientGunIndex::getGunData).orElse(null);
        GunDisplayInstance display = TimelessAPI.getGunDisplay(currentGunItem).orElse(null);
        if (gunData == null || display == null || display.getShellEjection() == null) {
            return;
        }

        ShellEjection shellEjection = display.getShellEjection();
        var ammoIndex = TimelessAPI.getClientAmmoIndex(gunData.getAmmoId()).orElse(null);
        if (ammoIndex == null || ammoIndex.getShellModel() == null || ammoIndex.getShellTextureLocation() == null) {
            return;
        }
        BedrockAmmoModel model = ammoIndex.getShellModel();
        ResourceLocation texture = ammoIndex.getShellTextureLocation();
        long lifeTime = (long) (shellEjection.getLivingTime() * 1000);
        checkShellQueue(lifeTime);

        Vector3f initialVelocity = shellEjection.getInitialVelocity();
        Vector3f acceleration = shellEjection.getAcceleration();
        Vector3f angularVelocity = shellEjection.getAngularVelocity();
        PoseStack origin = context.poseStack();
        int light = context.light();
        int overlay = context.overlay();

        for (Data data : SHELL_QUEUE) {
            if (data.normal == null || data.pose == null) {
                data.normal = new Matrix3f(origin.last().normal());
                data.pose = new Matrix4f(origin.last().pose());
            }
            long ageMs = System.currentTimeMillis() - data.timeStamp;
            double time = ageMs / 1000.0;
            Vector3f randomOffset = data.randomOffset;

            PoseStack frozenShellPose = new PoseStack();
            frozenShellPose.last().normal().set(data.normal);
            frozenShellPose.last().pose().set(data.pose);
            double x = (initialVelocity.x() + randomOffset.x()) * time + 0.5 * acceleration.x() * time * time;
            double y = (initialVelocity.y() + randomOffset.y()) * time + 0.5 * acceleration.y() * time * time;
            double z = (initialVelocity.z() + randomOffset.z()) * time + 0.5 * acceleration.z() * time * time;
            frozenShellPose.translate(-x, -y, z);
            frozenShellPose.mulPose(Axis.XN.rotationDegrees((float) (time * angularVelocity.x())));
            frozenShellPose.mulPose(Axis.YN.rotationDegrees((float) (time * angularVelocity.y())));
            frozenShellPose.mulPose(Axis.ZP.rotationDegrees((float) (time * angularVelocity.z())));
            frozenShellPose.translate(0, -1.5, 0);

            context.add(collector -> {
                PoseStack taskPose = new PoseStack();
                taskPose.last().pose().set(frozenShellPose.last().pose());
                taskPose.last().normal().set(frozenShellPose.last().normal());
                model.submit(taskPose, displayContext, collector, shellRenderType(displayContext, texture), light, overlay);
            });
        }
    }

    public static class Data {
        public final long timeStamp;
        public final Vector3f randomOffset;

        public Matrix3f normal = null;
        public Matrix4f pose = null;

        public Data(long timeStamp, Vector3f randomOffset) {
            this.timeStamp = timeStamp;
            this.randomOffset = randomOffset;
        }
    }
}
