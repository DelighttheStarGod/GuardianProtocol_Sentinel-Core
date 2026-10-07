package com.guardianprotocol;

import com.guardianprotocol.block.ProtectTargetManager;
import com.guardianprotocol.entity.ModEntities;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 事件处理。
 *
 * <p>注意区分两条总线：</p>
 * <ul>
 *     <li><b>mod 事件总线</b>（modEventBus）：注册类事件、生命周期事件、
 *         {@link EntityAttributeCreationEvent}。在 {@code GuardianProtocol} 构造器里
 *         用 {@code addListener} 挂。</li>
 *     <li><b>游戏事件总线</b>（{@link MinecraftForge#EVENT_BUS}）：世界/玩家/实体运行时事件，
 *         例如本类的 {@link TickEvent.ServerTickEvent} 与 {@link LevelEvent.Unload}。</li>
 * </ul>
 */
public final class ModEvents {

    private ModEvents() {
    }

    /** 把运行时事件挂到游戏事件总线上（由主类构造器调用）。 */
    public static void registerGameEvents() {
        MinecraftForge.EVENT_BUS.register(ModEvents.class);
    }

    // ------------------------------------------------------------------
    // mod 事件总线
    // ------------------------------------------------------------------

    /**
     * 给已注册的实体类型挂属性。
     *
     * <p>必须等到实体注册完成之后才能调用，所以只能挂在事件上，不能写在静态初始化里。</p>
     */
    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        ModEntities.onEntityAttributeCreation(event);
        GuardianProtocol.LOGGER.info("[{}] 已挂载单位属性。", GuardianProtocol.MODID);
    }

    // ------------------------------------------------------------------
    // 游戏事件总线
    // ------------------------------------------------------------------

    /** 服务端每 tick：驱动保护目标的嘲讽扫描 + 棋子的索敌/攻击/阻挡 + 出怪点的排期出怪 + 技能位。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        ProtectTargetManager.onServerTick(event);
        if (event.phase == TickEvent.Phase.END) {
            com.guardianprotocol.combat.PawnCombatManager.onServerTick(event.getServer());
            // 技能位：推进每个棋子的技力与激活窗口，满技力就放技能（见 combat/Skills）。
            com.guardianprotocol.combat.Skills.onServerTick(event.getServer());
            // 出怪点里「延迟 / 批次内间隔」不为 0 的批次在这里按 tick 放出。
            // 没有排期时是空操作，所以平时没有任何代价。
            com.guardianprotocol.spawn.SpawnPointService.tickPending(event.getServer());
            // 实机自验的观察推进（没在跑时是空操作）
            com.guardianprotocol.command.DebugCommands.tickLiveObservation();
        }
    }

    /**
     * 玩家登录：把玩家挂进「不与任何实体碰撞」的队伍。
     *
     * <p>为什么要在登录时挂：队伍成员写的是 UUID，玩家换了一个存档/第一次进服时并不在队里。
     * 与 {@code BlockTeams.apply}（开服建队）合起来，保证「在线玩家都在玩家队」。
     * 玩家退出时**不**摘 —— 摘了会让「离线再上线」多一条路径，而停服时统一清理
     * （见 {@link #onServerStopped}），失效窗口极小。</p>
     */
    @SubscribeEvent
    public static void onPlayerLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level) {
            com.guardianprotocol.combat.BlockTeams.tagPlayer(level, event.getEntity());
        }
    }

    /** 世界卸载：清掉嘲讽绑定、扫描计数器、棋子战斗缓存与出怪排期，避免跨世界串数据。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        ProtectTargetManager.onLevelUnload(event);
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            com.guardianprotocol.combat.PawnCombatManager.clear();
            // ★ 出怪排期必须跟着世界一起丢：它持有 ServerLevel 引用，
            //   留着就会在下一个世界里继续出怪（坐标还是旧世界的那一份）。
            com.guardianprotocol.spawn.SpawnPointService.clear(level);
        } else {
            // 客户端那份「出怪点配置快照」也要清：坐标相同但世界不同的话会张冠李戴。
            // DistExecutor 保证这段只在客户端执行（ClientSpawnPointConfig 是 @OnlyIn(CLIENT)）。
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> com.guardianprotocol.client.ClientSpawnPointConfig::clear);
        }
    }

    /** 服务器停止：兜底清理静态缓存（顺序有讲究：先把被挡住的敌人放行，再拆队伍）。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ProtectTargetManager.onServerStopped(event);
        // ① 放行所有被挡住的敌人：release() 会顺手把它们从队伍里摘出来
        com.guardianprotocol.combat.PawnCombatManager.clear();
        com.guardianprotocol.spawn.SpawnPointService.clearAll();
        // ② 再把两队拆掉（不在存档里留东西，见 BlockTeams 的注释）
        com.guardianprotocol.combat.BlockTeams.shutdown(event.getServer());
    }

    /**
     * 服务端启动完成：**无头自验**的入口。
     *
     * <p>只有加了 {@code -Dguardianprotocol.selftest=1} 才会跑（见
     * {@link com.guardianprotocol.command.DebugCommands#enabledByProperty}）。
     * 这条通道是为「把真实存档拖进来、无头跑一遍、把结论交回」准备的，
     * 因为开发环境<b>没法往服务端控制台敲命令</b>（stdin 管道不通，
     * Forge 47.4.23 的 conditional-file {@code runFunction} 也不触发）。</p>
     *
     * <p>跑完由自验自己 {@code halt}，所以这条命令会「启动 → 自验 → 关服」。
     * 正常游戏（没加参数）完全不受影响。</p>
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        // 建两支记分板队伍（被挡住的敌人 / 玩家），并把在线玩家挂进玩家队。
        // 与自验无关，正常开服也要做 —— 它是「阻挡系统的一部分」，不是测试设施。
        com.guardianprotocol.combat.BlockTeams.apply(event.getServer());
        if (com.guardianprotocol.command.DebugCommands.enabledByProperty()) {
            com.guardianprotocol.command.DebugCommands.runAllAndHalt(event.getServer());
        }
    }
}
