package com.tacz.guns.resource;

import com.tacz.guns.resource.filter.RecipeFilter;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.resource.index.CommonBlockIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.attachment.AttachmentData;
import com.tacz.guns.resource.pojo.data.block.BlockData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.recipe.TableRecipe;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaTable;

import java.util.Map;
import java.util.Set;

public interface ICommonResourceProvider {
    @Nullable GunData getGunData(ResourceLocation id);

    @Nullable AttachmentData getAttachmentData(ResourceLocation attachmentId);

    @Nullable BlockData getBlockData(ResourceLocation id);

    @Nullable RecipeFilter getRecipeFilter(ResourceLocation id);

    @Nullable CommonGunIndex getGunIndex(ResourceLocation gunId);

    @Nullable CommonAmmoIndex getAmmoIndex(ResourceLocation ammoId);

    @Nullable CommonAttachmentIndex getAttachmentIndex(ResourceLocation attachmentId);

    @Nullable CommonBlockIndex getBlockIndex(ResourceLocation blockId);

    @Nullable
    public LuaTable getScript(ResourceLocation scriptId);

    Set<Map.Entry<ResourceLocation, CommonGunIndex>> getAllGuns();

    Set<Map.Entry<ResourceLocation, CommonAmmoIndex>> getAllAmmos();

    Set<Map.Entry<ResourceLocation, CommonAttachmentIndex>> getAllAttachments();

    Set<Map.Entry<ResourceLocation, CommonBlockIndex>> getAllBlocks();

    Set<String> getAttachmentTags(ResourceLocation registryName);

    Set<String> getAllowAttachmentTags(ResourceLocation registryName);

    /**
     * 枪械工作台配方。
     *
     * <p><b>为什么需要它（第 12 轮）</b>：26.2 的客户端<b>没有</b>完整配方表 ——
     * {@code ClientLevel#recipeAccess()} 返回的 {@code RecipeAccess} 只有
     * {@code propertySet(...)} 与 {@code stonecutterRecipes()}，
     * 原版只下发配方书需要的那部分。上游 1.21.1 用的
     * {@code recipeManager.getAllRecipesFor(...)} 在 26.2 客户端已不可用。</p>
     *
     * <p>因此工作台界面所需的配方必须由 mod 自己同步过来，
     * 走既有的 {@code DataType.RECIPES} 通道（该枚举此前被声明但从未接线）。</p>
     */
    @Nullable TableRecipe getTableRecipe(ResourceLocation recipeId);

    Set<Map.Entry<ResourceLocation, TableRecipe>> getAllTableRecipes();
}
