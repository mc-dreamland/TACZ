package com.tacz.guns.client.gui.components;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.text.DecimalFormat;

/** Range slider used by the laser HSV controls. Vanilla AbstractSliderButton stores 0..1. */
public class ForgeSlider extends AbstractSliderButton {
    protected Component prefix;
    protected Component suffix;
    protected double minValue;
    protected double maxValue;
    protected double stepSize;
    protected boolean drawString;
    private final DecimalFormat format;

    public ForgeSlider(int x, int y, int width, int height, Component prefix, Component suffix,
                       double minValue, double maxValue, double currentValue, double stepSize,
                       int precision, boolean drawString) {
        super(x, y, width, height, Component.empty(), 0D);
        this.prefix = prefix;
        this.suffix = suffix;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.stepSize = Math.abs(stepSize);
        this.value = snapToNearest((currentValue - minValue) / (maxValue - minValue));
        this.drawString = drawString;
        if (stepSize == 0D) {
            precision = Math.min(precision, 4);
            StringBuilder builder = new StringBuilder("0");
            if (precision > 0) {
                builder.append('.');
                builder.append("0".repeat(precision));
            }
            this.format = new DecimalFormat(builder.toString());
        } else if (Mth.equal(this.stepSize, Math.floor(this.stepSize))) {
            this.format = new DecimalFormat("0");
        } else {
            this.format = new DecimalFormat(Double.toString(this.stepSize).replaceAll("\\d", "0"));
        }
        this.updateMessage();
    }

    public double getValue() {
        return this.value * (maxValue - minValue) + minValue;
    }

    public long getValueLong() {
        return Math.round(getValue());
    }

    public int getValueInt() {
        return (int) getValueLong();
    }

    /**
     * Set the slider's real value in the configured min/max range.
     *
     * <p>This must not be named {@code setValue}: NeoForge 26.1's
     * {@code AbstractSliderButton#setValue(double)} receives a 0..1 fractional value and
     * invokes {@code applyValue()}. Overriding it with real-value semantics breaks the
     * vanilla drag path through {@code AbstractSliderButton#onDrag}.</p>
     */
    public void setValueReal(double value) {
        double oldValue = this.value;
        this.value = snapToNearest((value - this.minValue) / (this.maxValue - this.minValue));
        if (!Mth.equal(oldValue, this.value)) {
            this.applyValue();
        }
        this.updateMessage();
    }

    public String getValueString() {
        return format.format(getValue());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // NeoForge 26.1's AbstractSliderButton only calls onDrag while its protected
        // dragging flag is set. ExtendedSlider in the 26.1 NeoForge sources sets this
        // flag here; without it a track click works, but holding the mouse button and
        // moving the handle never produces a drag update.
        this.dragging = this.active;
        this.setValueFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        // Do not call super: AbstractSliderButton#onDrag uses its fractional
        // setValue(double), while this class also supports step snapping and real values.
        // Going directly through setValueFromMouse keeps one coherent update path and
        // invokes applyValue exactly once when the snapped value changes.
        this.setValueFromMouse(event.x());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean flag = event.key() == GLFW.GLFW_KEY_LEFT;
        if (flag || event.key() == GLFW.GLFW_KEY_RIGHT) {
            if (this.minValue > this.maxValue) {
                flag = !flag;
            }
            float f = flag ? -1F : 1F;
            if (stepSize <= 0D) {
                this.setSliderValue(this.value + (f / (this.width - 8)));
            } else {
                this.setValueReal(getValue() + f * this.stepSize);
            }
        }
        return false;
    }

    private void setValueFromMouse(double mouseX) {
        this.setSliderValue((mouseX - (this.getX() + 4)) / (this.width - 8));
    }

    private void setSliderValue(double value) {
        double oldValue = this.value;
        this.value = this.snapToNearest(value);
        if (!Mth.equal(oldValue, this.value)) {
            this.applyValue();
        }
        this.updateMessage();
    }

    private double snapToNearest(double value) {
        if (stepSize <= 0D) {
            return Mth.clamp(value, 0D, 1D);
        }
        value = Mth.lerp(Mth.clamp(value, 0D, 1D), this.minValue, this.maxValue);
        value = (stepSize * Math.round(value / stepSize));
        if (this.minValue > this.maxValue) {
            value = Mth.clamp(value, this.maxValue, this.minValue);
        } else {
            value = Mth.clamp(value, this.minValue, this.maxValue);
        }
        return Mth.map(value, this.minValue, this.maxValue, 0D, 1D);
    }

    @Override
    protected void updateMessage() {
        if (this.drawString) {
            this.setMessage(Component.literal("").append(prefix).append(this.getValueString()).append(suffix));
        } else {
            this.setMessage(Component.empty());
        }
    }

    @Override
    protected void applyValue() {
    }
}
