package com.guardianprotocol;

import com.guardianprotocol.block.ModBlocks;
import com.guardianprotocol.blockentity.ModBlockEntities;
import com.guardianprotocol.menu.ModMenus;
import com.guardianprotocol.client.ClientSetup;
import com.guardianprotocol.entity.ModEntities;
import com.guardianprotocol.item.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * 卫戍协议（Guardian Protocol）主类。
 *
 * <p>玩法原型来自千星构建环境「卫戍协议」：1-4 人合作、自动塔防 + 自走棋融合。
 * 本 mod 是其 Minecraft Forge 1.20.1 移植，角色以「2D 像素小人」呈现。</p>
 *
 * <p>注意：MODID 必须与 META-INF/mods.toml 中的 modId、以及 gradle.properties 的 mod_id 完全一致。</p>
 */
@Mod(GuardianProtocol.MODID)
public class GuardianProtocol {

    /** 本 mod 的命名空间。资源路径一律是 assets/guardian_protocol/... 与 data/guardian_protocol/... */
    public static final String MODID = "guardian_protocol";

    /** 日志器：用 LogUtils 拿到的 logger 会自动带上 modid 前缀。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 47.4.23 的 @Mod 主类构造器。
     *
     * <p><b>只能取这两种参数之一</b>（见 {@code FMLModContainer.constructMod()} 的实现：
     * 它先找 {@code <init>(FMLJavaModLoadingContext)}，找不到才退回无参构造器）：</p>
     * <ul>
     *     <li>{@link FMLJavaModLoadingContext}（本类采用）</li>
     *     <li>无参构造器</li>
     * </ul>
     *
     * <p><b>坑</b>：写成 {@code <init>(IEventBus)} 能编译通过，但运行时会直接
     * {@code NoSuchMethodException: <init>()} 加载失败——因为 Forge 根本不去找
     * 这个签名。网上的新版本示例（1.21 / NeoForge）常用 IEventBus，1.20.1 不行。</p>
     */
    public GuardianProtocol(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 让各注册表把自己的 DeferredRegister 挂到 mod 事件总线上，注册才会真正发生。
        // 顺序有讲究：方块必须先于方块实体注册，因为方块实体的合法方块表要引用方块。
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModMenus.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // 网络通道：本 mod 只有一个自定义包（出怪点的「怪物类型」是字符串，
        // 原版 clickMenuButton 的 int 载荷装不下）。在构造器里注册是安全的 ——
        // Forge 的通道锁在加载阶段 NETWORK_LOCK(COMPLETE) 才置上，远在这之后。
        com.guardianprotocol.net.ModNetwork.register();

        // 客户端专用注册（实体渲染器 / 模型层）只在物理客户端加载，必须放在 ClientSetup 里。
        ClientSetup.register(modEventBus);

        // 实体属性挂载走的是 mod 事件总线（EntityAttributeCreationEvent 是 mod 总线事件，
        // 不是游戏总线事件——这一点很容易写错，写错的表现是实体创建时抛
        // "Entity has no attributes" 崩溃）。
        modEventBus.addListener(ModEvents::onEntityAttributeCreation);

        // 运行时事件（保护目标的嘲讽扫描、抹杀、缓存清理）走游戏事件总线。
        ModEvents.registerGameEvents();

        // 通用初始化阶段：此时所有注册项已可用，适合做跨注册项的校验。
        modEventBus.addListener(this::commonSetup);

        // 读取 config/guardian_protocol-common.toml。
        // ★ 1.20.1 必须走 ModLoadingContext.get()，FMLJavaModLoadingContext 上没有
        //   registerConfig 方法（MDK 模板里的 context.registerConfig(...) 是新版写法）。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GuardianConfig.SPEC);

        // 世界加载完成时，把配置里的默认嘲讽半径套用到「仍是硬编码默认档」的保护目标上。
        // 为什么要等到这里才读配置：方块对象是在 RegisterEvent 期间构造的，
        // 那时配置还没注册；在方块构造器里读配置会把方块注册搞成 null（已踩过）。
        MinecraftForge.EVENT_BUS.addListener(GuardianProtocol::onLevelLoad);

        // 自验命令 /guardianprotocol selftest：把「索敌选了谁 / 一次打几个 / 阻挡落点 /
        // 出手间隔」写进 <运行目录>/guardianprotocol-selftest.log，便于无头核对。
        // 与上面那条一样走游戏事件总线，方法上带 @SubscribeEvent。
        MinecraftForge.EVENT_BUS.addListener(
                com.guardianprotocol.command.DebugCommands::onRegisterCommands);

        // 数据包里的职业分支定义（data/<ns>/guardian_branches/*.json）随资源重载一起读。
        // 走游戏事件总线：AddReloadListenerEvent 是服务器资源重载事件，不是 mod 生命周期事件。
        MinecraftForge.EVENT_BUS.addListener(
                com.guardianprotocol.data.BranchReloadListener::onAddReloadListeners);

        LOGGER.info("[{}] 卫戍协议加载中……", MODID);
    }

    /**
     * 世界加载：把配置里的 {@code tauntRadius} 应用到「没被单独调过」的保护目标。
     *
     * <p>判定条件是「当前档位 == 硬编码默认档」，所以：</p>
     * <ul>
     *     <li>玩家用右键界面改过的方块（值不等于硬编码默认）<b>不会被覆盖</b>；</li>
     *     <li>改配置文件后重进世界，默认档会按配置生效（含旧存档里没动过的方块）。</li>
     * </ul>
     */
    private static void onLevelLoad(net.minecraftforge.event.level.LevelEvent.Load event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        var configured = com.guardianprotocol.block.TauntRadius.fromConfigRadius(
                GuardianConfig.tauntRadius());
        if (configured == com.guardianprotocol.block.TauntRadius.HARDCODED_DEFAULT) {
            return;   // 配置与硬编码默认一致，什么都不用做
        }
        int changed = 0;
        for (com.guardianprotocol.block.ProtectTargetBlockEntity target
                : com.guardianprotocol.block.ProtectTargetBlockEntity.activeTargets()) {
            if (target.getLevel() != level) {
                continue;
            }
            if (target.getTauntRadius() == com.guardianprotocol.block.TauntRadius.HARDCODED_DEFAULT) {
                target.setTauntRadius(configured);
                changed++;
            }
        }
        if (changed > 0) {
            LOGGER.info("[{}] 已按配置把 {} 个保护目标的嘲讽半径设为 {}。",
                    MODID, changed, configured.display());
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 用 enqueueWork 保证代码运行在同步阶段，避免并发注册导致注册表竞态。
        event.enqueueWork(() -> LOGGER.info("[{}] 已注册 {} 种单位类型；保护目标血量 {}，默认嘲讽半径 {}。",
                MODID, ModEntities.UNIT_TYPES.size(), GuardianConfig.targetHealth(),
                com.guardianprotocol.block.TauntRadius.fromConfigRadius(
                        GuardianConfig.tauntRadius()).display()));
    }
}
