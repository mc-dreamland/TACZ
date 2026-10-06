package com.tacz.guns.entity.sync.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DataHolder implements ValueIOSerializable {
    public Map<SyncedDataKey<?, ?>, DataEntry<?, ?>> dataMap = new HashMap<>();
    private boolean dirty = false;

    @SuppressWarnings("unchecked")
    public <E extends Entity, T> boolean set(E entity, SyncedDataKey<?, ?> key, T value) {
        DataEntry<E, T> entry = (DataEntry<E, T>) this.dataMap.computeIfAbsent(key, DataEntry::new);
        if (!entry.getValue().equals(value)) {
            boolean dirty = !entity.level().isClientSide() && entry.getKey().syncMode() != SyncedDataKey.SyncMode.NONE;
            entry.setValue(value, dirty);
            this.dirty |= dirty;
            return true;
        }
        return false;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <E extends Entity, T> T get(SyncedDataKey<E, T> key) {
        return (T) this.dataMap.computeIfAbsent(key, DataEntry::new).getValue();
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void clean() {
        this.dirty = false;
        this.dataMap.forEach((key, entry) -> entry.clean());
    }

    public List<DataEntry<?, ?>> gatherDirty() {
        return this.dataMap.values().stream().filter(DataEntry::isDirty).filter(entry -> entry.getKey().syncMode() != SyncedDataKey.SyncMode.NONE).collect(Collectors.toList());
    }

    public List<DataEntry<?, ?>> gatherAll() {
        return this.dataMap.values().stream().filter(entry -> entry.getKey().syncMode() != SyncedDataKey.SyncMode.NONE).collect(Collectors.toList());
    }

    /** Respawns also copy transient keys; only resetOnDeath keys are filtered on death. */
    public void copyFrom(DataHolder original, boolean wasDeath) {
        this.dataMap.clear();
        this.dirty = false;
        original.dataMap.forEach((key, value) -> {
            if (wasDeath && !key.persistent()) return;
            DataEntry<?, ?> copy = new DataEntry<>(key);
            copy.readValue(value.writeValue().copy());
            this.dataMap.put(key, copy);
        });
    }

    @Override
    public void serialize(ValueOutput output) {
        var entries = output.list("Entries", CompoundTag.CODEC);
        this.dataMap.forEach((key, entry) -> {
            if (!key.save()) return;
            CompoundTag data = new CompoundTag();
            data.putString("ClassKey", key.classKey().id().toString());
            data.putString("DataKey", key.id().toString());
            data.put("Value", entry.writeValue());
            entries.add(data);
        });
    }

    @Override
    public void deserialize(ValueInput input) {
        this.dataMap.clear();
        this.dirty = false;
        for (CompoundTag data : input.listOrEmpty("Entries", CompoundTag.CODEC)) {
            ResourceLocation classId = ResourceLocation.tryParse(data.getStringOr("ClassKey", ""));
            ResourceLocation keyId = ResourceLocation.tryParse(data.getStringOr("DataKey", ""));
            Tag value = data.get("Value");
            if (classId == null || keyId == null || value == null) continue;
            SyncedClassKey<?> classKey = SyncedEntityData.instance().getClassKey(classId);
            if (classKey == null) continue;
            SyncedDataKey<?, ?> key = SyncedEntityData.instance().getKey(classKey, keyId);
            if (key == null || !key.save()) continue;
            DataEntry<?, ?> entry = new DataEntry<>(key);
            entry.readValue(value);
            this.dataMap.put(key, entry);
        }
    }
}
