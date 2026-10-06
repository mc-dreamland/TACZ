package com.tacz.guns.resource.network;

import com.mojang.serialization.Codec;

public enum DataType {
    /**
     * 需要同步到客户端的数据类型
     */
    GUN_DATA,
    ATTACHMENT_DATA,
    AMMO_INDEX,
    GUN_INDEX,
    ATTACHMENT_INDEX,
    RECIPES,
    RECIPE_FILTER,
    ATTACHMENT_TAGS,
    ALLOW_ATTACHMENT_TAGS,
    BLOCK_DATA,
    BLOCK_INDEX;

    public static final Codec<DataType> CODEC = Codec.STRING.xmap(DataType::valueOf, DataType::name);
}
