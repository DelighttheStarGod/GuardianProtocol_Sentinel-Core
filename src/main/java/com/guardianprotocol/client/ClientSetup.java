package com.guardianprotocol.client;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.blockentity.ModBlockEntities;
import com.guardianprotocol.entity.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端专用注册。
 *
 * <p>整个类用 {@code value = Dist.CLIENT} 限定，Forge 在专用服务器上根本不会加载它——
 * 这一点很关键：{@link PixelUnitRenderer} / {@link ProtectTargetRenderer} 引用了大量
 * 客户端类（PoseStack、RenderType、Camera 等），如果这些引用出现在服务端会加载的类里，
 * 专用服务器启动时就会 {@code NoClassDefFoundError}。</p>
 */
@Mod.EventBusSubscriber(modid = GuardianProtocol.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    /**
     * 由主类构造器调用。
     *
     * <p>因为本类已经带 {@code @Mod.EventBusSubscriber}，理论上 Forge 会自动扫描静态
     * {@code @SubscribeEvent} 方法；但这里保留显式入口，方便主类一眼看出客户端挂了什么，
     * 也避免「事件没触发但不知道去哪找」的排查困难。</p>
     *
     * <p><b>★ 本方法会被专用服务器调用</b>：它是从主类构造器（服务端也跑）进来的，
     * 只是本类自身带 {@code Dist.CLIENT}，所以<b>方法体里绝不能直接引用客户端类</b>，
     * 否则会在专用服务器上加载它们 —— 实测症状是启动直接失败：</p>
     * <pre>
     * Attempted to load class com/guardianprotocol/client/AttackRangePreview
     * for invalid dist DEDICATED_SERVER
     * </pre>
     * <p>所以下面那行用 {@link DistExecutor#unsafeRunWhenOn} 包起来：
     * 该 lambda 只在物理客户端执行，类加载被推迟到那一刻。</p>
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterRenderers);
        // 界面注册走原版 MenuScreens，而不是 Forge 事件：
        // 本版本的 Forge 并没有 RegisterMenuScreensEvent（只有 ScreenEvent / ContainerScreenEvent
        // 这类"已经要显示时"的事件），所以用 FMLClientSetupEvent + MenuScreens.register 最稳。
        modEventBus.addListener(ClientSetup::onClientSetup);

        // 攻击范围预览：两个处理器都挂游戏事件总线。
        //  - RenderHighlightEvent.Entity：准星命中棋子的那一帧，顺便把范围画出来
        //  - ClientTickEvent：维护「准星移开后仍保留 1 秒」的计时
        //
        // ★ 必须包在 DistExecutor 里（原因见方法注释）：这里早先是裸的
        //   `EVENT_BUS.register(AttackRangePreview.class)`，于是**专用服务器根本起不来**。
        //   注意「方法引用」才安全（ClientSetup::onRegisterRenderers 那种），
        //   一旦写成 `SomeClientClass.class` 或直接 new，就等于当场加载那个类。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.minecraftforge.common.MinecraftForge.EVENT_BUS
                        .register(AttackRangePreview.class));
    }

    /** 客户端初始化：注册容器 -> 界面的映射。没有这一步，右键保护目标/出怪点/棋子会打开空白界面。 */
    private static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.guardianprotocol.menu.ModMenus.PROTECT_TARGET.get(),
                    ProtectTargetScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.guardianprotocol.menu.ModMenus.SPAWN_POINT.get(),
                    SpawnPointScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.guardianprotocol.menu.ModMenus.PAWN_FACING.get(),
                    PawnFacingScreen::new);
        });
    }

    /** 注册渲染器：棋子 + 投掷物（实体）+ 保护目标（方块实体）。 */
    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PIXEL_UNIT.get(), PixelUnitRenderer::new);
        // 投掷物：公告板面片。这里写方法引用（PawnProjectileRenderer::new）而不是先 new 一个，
        // 类加载因此被推迟到本事件真正执行时 —— 本事件只在客户端触发（见类注释的铁律）。
        event.registerEntityRenderer(ModEntities.PAWN_PROJECTILE.get(), PawnProjectileRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.PROTECT_TARGET.get(), ProtectTargetRenderer::new);
        GuardianProtocol.LOGGER.info("[{}] 已注册棋子、投掷物与保护目标的渲染器。", GuardianProtocol.MODID);
    }
}
