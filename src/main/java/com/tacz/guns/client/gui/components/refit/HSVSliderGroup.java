package com.tacz.guns.client.gui.components.refit;

import com.tacz.guns.api.item.ItemBehavior;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.util.LaserColorUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.widget.ForgeSlider;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.function.IntConsumer;

public class HSVSliderGroup {
    private final Inventory inventory;
    private final int gunItemIndex;

    private final AttachmentType type;
    private final IntConsumer previewColor;

    private final LaserColorSlider hueSlider;
    private final LaserColorSlider saturationSlider;

    public HSVSliderGroup(int x, int y, int width, int height, Inventory inventory, int gunItemIndex, @NotNull AttachmentType type) {
        this.inventory = inventory;
        this.gunItemIndex = gunItemIndex;
        this.type = type;
        this.previewColor = null;

        int color = getColor(type);
        float[] hsb = Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);

        hueSlider = new LaserColorSlider(x, y, width, height, this, hsb[0]);
        saturationSlider = new LaserColorSlider(x, y + 2 + height, width, height, this, hsb[1]);
    }

    /** Paper refit keeps the color draft outside the authoritative inventory stack. */
    public HSVSliderGroup(int x, int y, int width, int height, int color, IntConsumer previewColor) {
        this.inventory = null;
        this.gunItemIndex = -1;
        this.type = AttachmentType.NONE;
        this.previewColor = previewColor;
        float[] hsb = Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);
        hueSlider = new LaserColorSlider(x, y, width, height, this, hsb[0]);
        saturationSlider = new LaserColorSlider(x, y + 2 + height, width, height, this, hsb[1]);
    }

    public LaserColorSlider getHueSlider() {
        return hueSlider;
    }

    public LaserColorSlider getSaturationSlider() {
        return saturationSlider;
    }


    public void apply() {
        if (previewColor != null) {
            if (hueSlider != null && saturationSlider != null) {
                previewColor.accept(Color.HSBtoRGB((float) hueSlider.getValue(), (float) saturationSlider.getValue(), 1f) & 0xFFFFFF);
            }
            return;
        }
        // 需要检查的实现
        // 这里写往客户端写nbt其实是脏写，只为了确保能实时预览染色效果
        // 需要在合适的时机向服务器发包通知改动
        // 不在此直接向服务器发包是因为这个组件在滑动时会被非常频繁的调用，不希望频繁向服务器发包
        ItemStack gun = inventory.getItem(gunItemIndex);
        if (ItemBehavior.of(gun) instanceof IGun iGun) {
            int rgb_new = Color.HSBtoRGB((float) hueSlider.getValue(), (float) saturationSlider.getValue(), 1f);

            if (type == AttachmentType.NONE) {
                iGun.setLaserColor(gun, rgb_new);
                return;
            }

            ItemStack laser = iGun.getAttachment(gun, type);
            if (ItemBehavior.of(laser) instanceof IAttachment iAttachment) {
                iAttachment.setLaserColor(laser, rgb_new);
            }
        }
    }



    private int getColor(AttachmentType type) {
        if (inventory == null) {
            return 0XFF0000;
        }
        ItemStack gun = inventory.getItem(gunItemIndex);

        if (ItemBehavior.of(gun) instanceof IGun iGun) {
            if (type == AttachmentType.NONE) {
                return LaserColorUtil.getLaserColor(gun);
            } else {
                ItemStack attachment = iGun.getAttachment(gun, type);
                return LaserColorUtil.getLaserColor(attachment);
            }
        }

        return 0XFF0000;
    }

    public static class LaserColorSlider extends ForgeSlider {
        private final HSVSliderGroup parent;

        public LaserColorSlider(int x, int y, int width, int height, HSVSliderGroup parent, double current) {
            super(x, y, width, height, Component.empty(), Component.empty(), 0, 1, current, 0.01, 0, true);
            this.parent = parent;
        }

        @Override
        protected void applyValue() {
            parent.apply();
        }
    }
}
