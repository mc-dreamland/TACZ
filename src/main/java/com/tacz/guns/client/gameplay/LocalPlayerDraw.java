package com.tacz.guns.client.gameplay;

import com.tacz.guns.api.LogicalSide;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.other.KeepingItemRenderer;
import com.tacz.guns.api.item.IAnimationItem;
import com.tacz.guns.api.item.ItemBehavior;
import com.tacz.guns.api.event.common.GunDrawEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.network.message.ClientMessagePlayerDrawGun;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.client.renderer.item.BuiltinItemRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.TimeUnit;

public class LocalPlayerDraw {
    private final LocalPlayerDataHolder data;
    private final LocalPlayer player;
    public boolean readyToDraw = false;
    private long drawSequence;

    public LocalPlayerDraw(LocalPlayerDataHolder data, LocalPlayer player) {
        this.data = data;
        this.player = player;
    }

    public void draw(ItemStack lastItem) {
        if (!NetworkHandler.canSendToServer(ClientMessagePlayerDrawGun.TYPE)) {
            return;
        }
        // 重置各种参数
        this.resetData();
        long sequence = ++drawSequence;
        if (data.drawFuture != null) {
            data.drawFuture.cancel(false);
            data.drawFuture = null;
        }

        // 获取各种数据
        ItemStack currentItem = player.getMainHandItem();
        long drawTime = System.currentTimeMillis() - data.clientDrawTimestamp;
        IGun currentGun = IGun.getIGunOrNull(currentItem);
        IGun lastGun = IGun.getIGunOrNull(lastItem);

        // 计算 draw 时长和 putAway 时长
        if (drawTime >= 0) {
            drawTime = getDrawTime(lastItem, lastGun, drawTime);
        }
        long putAwayTime = Math.abs(drawTime);

        // 发包通知服务器
        if (Minecraft.getInstance().gameMode != null) {
            Minecraft.getInstance().gameMode.ensureHasSentCarriedItem();
        }
        NetworkHandler.sendToServer(ClientMessagePlayerDrawGun.INSTANCE);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new GunDrawEvent(player, lastItem, currentItem, LogicalSide.CLIENT));

        // 不处于收枪状态时才能收枪
        boolean startOutgoing = KeepingItemRenderer.getRenderer().beginSwitch(lastItem, putAwayTime);
        if (drawTime >= 0 && startOutgoing) {
            doPutAway(lastItem, putAwayTime);
        }

        // 异步放映抬枪动画
        if (currentGun != null) {
            doDraw(currentItem, putAwayTime, sequence);
            // 刷新配件数据
            AttachmentPropertyManager.postChangeEvent(player, currentItem);
        }
    }

    private void doDraw(ItemStack currentItem, long putAwayTime, long sequence) {
        TimelessAPI.getGunDisplay(currentItem).ifPresent(display -> {
            int selectedSlot = player.getInventory().getSelectedSlot();
            ItemStack requested = currentItem.copy();
            // 根据 put away time 预定 draw 行为（仅播放音效，状态机的初始化为了保证一致性已经移动）
            data.drawFuture = LocalPlayerDataHolder.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
                Minecraft.getInstance().submit(() -> {
                    if (sequence != drawSequence || Minecraft.getInstance().player != player
                            || player.getInventory().getSelectedSlot() != selectedSlot) return;
                    if (!(ItemBehavior.of(player.getMainHandItem()) instanceof IAnimationItem item)
                            || !item.isSame(requested, player.getMainHandItem())) return;
                    SoundPlayManager.stopPlayGunSound();
                    SoundPlayManager.playDrawSound(player, display);
                });
            }, putAwayTime, TimeUnit.MILLISECONDS);
        });
    }

    private void doPutAway(ItemStack lastItem, long putAwayTime) {
        if (BuiltinItemRendererRegistry.INSTANCE.get(com.tacz.guns.client.paper.GunResolver.renderStack(lastItem).getItem()) instanceof AnimateGeoItemRenderer<?, ?> renderer) {
            renderer.tryExit(lastItem, putAwayTime);
        }
        TimelessAPI.getGunDisplay(lastItem).ifPresent(display -> {
            Minecraft.getInstance().submit(() -> {
                // 播放收枪音效
                SoundPlayManager.stopPlayGunSound();
                SoundPlayManager.playPutAwaySound(player, display);
            });
        });
    }

    private long getDrawTime(ItemStack lastItem, IGun lastGun, long drawTime) {
        if (BuiltinItemRendererRegistry.INSTANCE.get(com.tacz.guns.client.paper.GunResolver.renderStack(lastItem).getItem()) instanceof AnimateGeoItemRenderer<?, ?> renderer) {
            long putAwayTime = renderer.getPutAwayTime(lastItem);
            if (drawTime > putAwayTime) {
                drawTime = putAwayTime;
            }
            data.clientDrawTimestamp = System.currentTimeMillis() + drawTime;
        } else {
            drawTime = 0;
            data.clientDrawTimestamp = System.currentTimeMillis();
        }
        return drawTime;
    }

    private void resetData() {
        // 锁上状态锁
        data.lockState(operator -> operator.getSynDrawCoolDown() > 0);
        // 重置客户端的 shoot 时间戳
        data.isShootRecorded = true;
        data.clientShootTimestamp = -1;
        data.chargeProgress = 0;
        // 重置客户端瞄准状态
        data.clientIsAiming = false;
        data.clientAimingProgress = 0;
        LocalPlayerDataHolder.oldAimingProgress = 0;
        // 重置拉栓状态
        data.isBolting = false;
        // 更新切枪时间戳
        if (data.clientDrawTimestamp == -1) {
            data.clientDrawTimestamp = System.currentTimeMillis();
        }
    }
}
