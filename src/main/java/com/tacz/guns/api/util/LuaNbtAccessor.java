package com.tacz.guns.api.util;

import com.tacz.guns.util.ItemNbtUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lua view whose mutations are committed back to an item's custom-data component. */
@SuppressWarnings("unused")
public final class LuaNbtAccessor {
    private final Supplier<CompoundTag> reader;
    private final Consumer<Consumer<CompoundTag>> writer;

    public LuaNbtAccessor(CompoundTag tag) {
        CompoundTag value = tag == null ? new CompoundTag() : tag;
        this.reader = () -> value;
        this.writer = edit -> edit.accept(value);
    }

    private LuaNbtAccessor(Supplier<CompoundTag> reader, Consumer<Consumer<CompoundTag>> writer) {
        this.reader = reader;
        this.writer = writer;
    }

    public static LuaNbtAccessor from(ItemStack stack) {
        return new LuaNbtAccessor(() -> ItemNbtUtils.getTag(stack), edit -> ItemNbtUtils.updateTag(stack, edit));
    }

    public static LuaNbtAccessor from(CompoundTag tag) { return new LuaNbtAccessor(tag); }
    public boolean contains(String key) { return nbt().contains(key); }
    public boolean contains(String key, int type) {
        Tag value = nbt().get(key);
        return value != null && (value.getId() == type || type == 99 && value instanceof NumericTag);
    }
    public LuaNbtAccessor newCompoundTag() { return new LuaNbtAccessor(new CompoundTag()); }
    public int getInt(String key) { return nbt().getIntOr(key, 0); }
    public double getDouble(String key) { return nbt().getDoubleOr(key, 0); }
    public float getFloat(String key) { return nbt().getFloatOr(key, 0); }
    public long getLong(String key) { return nbt().getLongOr(key, 0); }
    public String getString(String key) { return nbt().getStringOr(key, ""); }
    public boolean getBoolean(String key) { return nbt().getBooleanOr(key, false); }
    public boolean getBoolean(CompoundTag tag, String key) { return tag.getBooleanOr(key, false); }

    public LuaNbtAccessor getCompound(String key) {
        if (!contains(key, Tag.TAG_COMPOUND)) return null;
        return new LuaNbtAccessor(() -> nbt().getCompoundOrEmpty(key), edit -> writer.accept(parent -> {
            CompoundTag child = parent.getCompoundOrEmpty(key);
            edit.accept(child);
            parent.put(key, child);
        }));
    }

    public void putInt(String key, int value) { writer.accept(tag -> tag.putInt(key, value)); }
    public void putDouble(String key, double value) { writer.accept(tag -> tag.putDouble(key, value)); }
    public void putFloat(String key, float value) { writer.accept(tag -> tag.putFloat(key, value)); }
    public void putLong(String key, long value) { writer.accept(tag -> tag.putLong(key, value)); }
    public void putString(String key, String value) { writer.accept(tag -> tag.putString(key, value)); }
    public void putBoolean(String key, boolean value) { writer.accept(tag -> tag.putBoolean(key, value)); }
    public void putCompound(String key, LuaNbtAccessor value) {
        if (value != null) writer.accept(tag -> tag.put(key, value.nbt().copy()));
    }

    @ApiStatus.Internal
    public CompoundTag nbt() { return reader.get(); }
}
