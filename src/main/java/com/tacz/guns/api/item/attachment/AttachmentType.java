package com.tacz.guns.api.item.attachment;

import com.google.gson.annotations.SerializedName;
import com.mojang.serialization.Codec;

public enum AttachmentType {
    @SerializedName("scope")
    SCOPE,
    @SerializedName("muzzle")
    MUZZLE,
    @SerializedName("stock")
    STOCK,
    @SerializedName("grip")
    GRIP,
    @SerializedName("laser")
    LASER,
    @SerializedName("extended_mag")
    EXTENDED_MAG,
    NONE;

    public static final Codec<AttachmentType> CODEC = Codec.STRING.xmap(
            s -> AttachmentType.valueOf(s.toUpperCase(java.util.Locale.ROOT)),
            t -> t.name().toLowerCase(java.util.Locale.ROOT)
    );

    public static AttachmentType fromId(int id) {
        AttachmentType[] values = values();
        if (id < 0 || id >= values.length) {
            return NONE;
        }
        return values[id];
    }
}
