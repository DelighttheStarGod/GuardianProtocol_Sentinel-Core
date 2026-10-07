package com.guardianprotocol.blockentity;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.block.ModBlocks;
import com.guardianprotocol.block.ProtectTargetBlockEntity;
import com.guardianprotocol.block.SpawnPointBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块实体注册表。
 *
 * <p>注意注册顺序：{@code BlockEntityType.Builder.of(...).build(null)} 里的
 * 「合法方块」是在 {@code build} 时用 {@code Supplier} 求值的，但这里传的是
 * 已经注册好的 {@link ModBlocks#PROTECT_TARGET}。因为 DeferredRegister 保证了
 * 方块先于本类被填充，所以直接取 {@code .get()} 是安全的
 * ——但为了绝对稳妥，用 {@code ModBlocks.PROTECT_TARGET::get} 延迟求值，
 * 避免任何类加载顺序上的意外。</p>
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GuardianProtocol.MODID);

    /** 保护目标的方块实体。 */
    public static final RegistryObject<BlockEntityType<ProtectTargetBlockEntity>> PROTECT_TARGET =
            BLOCK_ENTITIES.register("protect_target",
                    () -> BlockEntityType.Builder
                            .of(ProtectTargetBlockEntity::new, ModBlocks.PROTECT_TARGET.get())
                            .build(null));

    /** 出怪点的方块实体（存怪物类型 / 数量 / 间隔 / 延迟 / 启用）。 */
    public static final RegistryObject<BlockEntityType<SpawnPointBlockEntity>> SPAWN_POINT =
            BLOCK_ENTITIES.register("spawn_point",
                    () -> BlockEntityType.Builder
                            .of(SpawnPointBlockEntity::new, ModBlocks.SPAWN_POINT.get())
                            .build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
