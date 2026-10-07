package com.guardianprotocol.menu;

import com.guardianprotocol.GuardianProtocol;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 容器（界面）注册表。
 *
 * <p>用 Forge 的 {@link IForgeMenuType#create} 变体：它允许客户端在创建容器时
 * 额外带一份数据（这里带方块坐标），从而在客户端也能找到对应的方块实体。</p>
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, GuardianProtocol.MODID);

    /** 保护目标设置界面。 */
    public static final RegistryObject<MenuType<ProtectTargetMenu>> PROTECT_TARGET =
            MENUS.register("protect_target",
                    () -> IForgeMenuType.create((containerId, inventory, data) ->
                            new ProtectTargetMenu(containerId, inventory, data.readBlockPos())));

    /** 出怪点设置界面。 */
    public static final RegistryObject<MenuType<SpawnPointMenu>> SPAWN_POINT =
            MENUS.register("spawn_point",
                    () -> IForgeMenuType.create((containerId, inventory, data) ->
                            new SpawnPointMenu(containerId, inventory, data.readBlockPos())));

    /**
     * 棋子朝向界面。
     *
     * <p>额外数据是<b>实体 id</b>（varint）而不是方块坐标 —— 棋子是实体，
     * 由 {@code PixelUnit.mobInteract} 写出，客户端据此在自己的世界里找回它。</p>
     */
    public static final RegistryObject<MenuType<PawnFacingMenu>> PAWN_FACING =
            MENUS.register("pawn_facing",
                    () -> IForgeMenuType.create((containerId, inventory, data) ->
                            new PawnFacingMenu(containerId, inventory, data.readVarInt())));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
