package com.guardianprotocol.block;

import com.guardianprotocol.GuardianProtocol;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块注册表。
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, GuardianProtocol.MODID);

    /** 保护目标：塔防的失守条件核心，自带嘲讽 + 接触抹杀。 */
    public static final RegistryObject<Block> PROTECT_TARGET =
            BLOCKS.register("protect_target", ProtectTargetBlock::new);

    /** 出怪点：敌人从哪出来的标记，只存配置，出怪动作在 spawn/SpawnPointService。 */
    public static final RegistryObject<Block> SPAWN_POINT =
            BLOCKS.register("spawn_point", SpawnPointBlock::new);

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
