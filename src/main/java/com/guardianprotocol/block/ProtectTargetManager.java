package com.guardianprotocol.block;

import com.guardianprotocol.GuardianConfig;
import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.ai.MoveToProtectTargetGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 保护目标的运行时逻辑：嘲讽扫描 + 接触抹杀 + 血量归零摧毁。
 *
 * <h3>为什么要单独一个 manager</h3>
 * <p>方块本身只在玩家放下时才「知道」自己存在；而嘲讽需要<b>每间隔若干 tick
 * 主动扫描附近实体</b>。这类「世界上所有同类方块一起做的周期性工作」放在
 * 一个 manager 里统一调度，比给每个方块实体挂 ticker 更可控
 * （可以统一限流，也更容易在关卡开始/结束时整体开关）。</p>
 *
 * <h3>性能</h3>
 * <p>每 {@code scanIntervalTicks}（默认 20 tick = 1 秒）才扫一次，
 * 每次扫描的实体范围是 {@code tauntRadius}（默认 32 格）的立方体。
 * 若后续同屏保护目标很多，应把扫描改成「按区块/按玩家附近」索引，
 * 目前先按「数量少、半径大」的塔防场景做最简实现。</p>
 */
public final class ProtectTargetManager {

    /** 生物 -> 已挂上的嘲讽 goal。用于摘除与复用，避免每 tick 重复 addGoal。 */
    private static final Map<PathfinderMob, MoveToProtectTargetGoal> TAUNTED = new HashMap<>();

    /** 保护目标自己的 tick 计数：用于限流扫描。 */
    private static final Map<BlockPos, Integer> TICK_COUNTER = new HashMap<>();

    private ProtectTargetManager() {
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用（由 ModEvents 挂到 TickEvent.ServerTickEvent）。 */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            tickLevel(level);
        }
    }

    /** 世界卸载时清缓存，避免跨世界串数据或内存泄漏。 */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel) {
            TAUNTED.clear();
            TICK_COUNTER.clear();
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        TAUNTED.clear();
        TICK_COUNTER.clear();
    }

    // ------------------------------------------------------------------
    // 每世界逻辑
    // ------------------------------------------------------------------

    private static void tickLevel(ServerLevel level) {
        int interval = Math.max(1, GuardianConfig.scanIntervalTicks());

        // 1) 取本世界里所有保护目标，并做「限流」：只有倒计时到 0 的才参与本轮扫描。
        //    这样即使有多个保护目标，也不会在同一 tick 全部开工。
        List<ProtectTargetBlockEntity> due = new ArrayList<>();
        for (ProtectTargetBlockEntity target : ProtectTargetBlockEntity.activeTargets()) {
            // 跨世界的实例跳过；已移除的实例由 setRemoved() 注销，这里再兜一层。
            if (target.isRemoved() || target.getLevel() != level) {
                continue;
            }
            BlockPos pos = target.getBlockPos();
            int c = TICK_COUNTER.merge(pos, -1, Integer::sum);
            if (c <= 0) {
                TICK_COUNTER.put(pos, interval);
                due.add(target);
            }
        }
        // 清理已经消失的目标对应的计数器
        TICK_COUNTER.keySet().removeIf(
                pos -> !(level.getBlockEntity(pos) instanceof ProtectTargetBlockEntity));

        if (due.isEmpty()) {
            // 即使没有到期的目标，也要清理一遍失效的嘲讽绑定（生物死了/走远了）
            pruneTaunted(level);
            return;
        }

        double killRadius = GuardianConfig.killRadius();
        boolean doTaunt = GuardianConfig.tauntEnabled();
        boolean doKill = GuardianConfig.killOnContact();

        for (ProtectTargetBlockEntity target : due) {
            BlockPos pos = target.getBlockPos();
            // ★ 嘲讽半径以「每个方块自己的档位」为准（右键界面可改），
            //   配置里的 tauntRadius 只作为新放置方块的默认档位。
            double tauntRadius = target.getTauntRadius().radius();
            AABB tauntBox = new AABB(pos).inflate(tauntRadius);

            // 2) 嘲讽：强制范围内「能嘲讽的生物」把移动目标改为保护目标。
            //
            //    ★ 口径：**嘲讽范围与抹杀范围完全一致** —— 两者都用
            //      GuardianConfig.shouldErase(...) 判定（默认只针对敌对生物，
            //      受白名单/黑名单控制，玩家永远豁免）。
            //      也就是说：能被抹杀的，就一定会被嘲讽；不会被抹杀的，也不会被嘲讽。
            //
            //    唯一的物理限制：嘲讽靠的是「给生物挂一个移动目标 goal」，
            //    所以只有 PathfinderMob（会用寻路器走路的生物）能响应。
            //    像恶魂这类飞行生物虽然会被抹杀，但没法被嘲讽 —— 它们本来也不走地面路径。
            if (doTaunt) {
                for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, tauntBox)) {
                    if (!GuardianConfig.shouldErase(mob)) {
                        continue;   // 不在抹杀范围内的（例如玩家宠物）：也不嘲讽
                    }
                    taunt(mob, pos);
                }
            }

            // 3) 抹杀：接触判定。用「方块中心 + killRadius」构成的一个小立方体。
            if (doKill) {
                AABB killBox = new AABB(
                        pos.getX() + 0.5D - killRadius, pos.getY() + 0.5D - killRadius,
                        pos.getZ() + 0.5D - killRadius,
                        pos.getX() + 0.5D + killRadius, pos.getY() + 0.5D + killRadius,
                        pos.getZ() + 0.5D + killRadius);
                for (Entity entity : level.getEntitiesOfClass(Entity.class, killBox)) {
                    erase(level, entity, pos);
                }
            }
        }

        pruneTaunted(level);
    }

    // ------------------------------------------------------------------
    // 嘲讽
    // ------------------------------------------------------------------

    /** 给一个生物挂上（或更新）嘲讽目标。 */
    private static void taunt(PathfinderMob mob, BlockPos pos) {
        MoveToProtectTargetGoal goal = TAUNTED.get(mob);
        if (goal == null || goalFor(mob) != goal) {
            goal = new MoveToProtectTargetGoal(mob);
            // priority 0 = 最高优先级，压过随机游走等 MOVE goal。
            mob.goalSelector.addGoal(0, goal);
            TAUNTED.put(mob, goal);
        }
        Vec3 center = Vec3.atCenterOf(pos);
        goal.setTargetPos(center);

        // 同时把「攻击目标」也指向保护目标（凡是 LivingEntity 就可以被锁定），
        // 这样敌人会停下来「攻击」保护目标而不是继续追玩家。
        // 注意：保护目标不是 LivingEntity，所以这里只是占位式地清空攻击目标，
        // 阻止它继续追玩家；真正的伤害由抹杀逻辑负责。
        mob.setTarget(null);
    }

    /** 取生物身上已注册的嘲讽 goal（若已丢失则返回 null）。 */
    private static MoveToProtectTargetGoal goalFor(PathfinderMob mob) {
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wg : mob.goalSelector.getAvailableGoals()) {
            if (wg.getGoal() instanceof MoveToProtectTargetGoal g) {
                return g;
            }
        }
        return null;
    }

    /**
     * 清理失效的嘲讽绑定：生物已死/被移除、或已经不在任何保护目标的嘲讽半径内。
     */
    private static void pruneTaunted(ServerLevel level) {
        Iterator<Map.Entry<PathfinderMob, MoveToProtectTargetGoal>> it = TAUNTED.entrySet().iterator();
        double radius = GuardianConfig.tauntRadius() + 8.0D;    // 留一点迟滞，避免边界抖动
        while (it.hasNext()) {
            Map.Entry<PathfinderMob, MoveToProtectTargetGoal> e = it.next();
            PathfinderMob mob = e.getKey();
            // 用 isDeadOrDying()：死亡动画期间 isAlive() 仍为 true，
            // 若在这里继续操作它（停寻路、清目标），会干扰死亡表现。
            if (mob.isRemoved() || mob.isDeadOrDying() || mob.level() != level) {
                it.remove();
                continue;
            }
            Vec3 tp = e.getValue().getTargetPos();
            if (tp == null || mob.distanceToSqr(tp) > radius * radius) {
                // 走远了：清目标并停止寻路，避免它一直朝旧坐标跑
                e.getValue().setTargetPos(null);
                mob.getNavigation().stop();
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    // 抹杀与摧毁
    // ------------------------------------------------------------------

    /**
     * 抹杀一个实体。
     *
     * <p>用 {@code discard()} 而不是 {@code kill()}：需求是「抹杀」——瞬间消失、
     * 不留尸体、不掉落、不触发死亡动画。玩家永远豁免（{@link GuardianConfig#shouldErase}）。</p>
     */
    private static void erase(ServerLevel level, Entity entity, BlockPos pos) {
        if (entity instanceof Player) {
            return;
        }
        if (!GuardianConfig.shouldErase(entity)) {
            return;
        }
        // 先解绑嘲讽，避免 map 里留下悬空引用
        if (entity instanceof PathfinderMob mob) {
            TAUNTED.remove(mob);
        }
        entity.discard();
        GuardianProtocol.LOGGER.debug("[{}] 保护目标 {} 抹杀了 {}", GuardianProtocol.MODID, pos, entity.getType());
    }

    /**
     * 对保护目标造成伤害；血量归零则摧毁方块。
     *
     * <p>目前还没有敌人能对它造成伤害（敌人一接触就被抹杀了），
     * 这个方法先留好接口：等「远程敌人 / 攻城怪」阶段直接调用即可。</p>
     */
    public static void damageTarget(Level level, BlockPos pos, int amount) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ProtectTargetBlockEntity target)) {
            return;
        }
        if (target.hurt(amount)) {
            // 失守：摧毁方块（不触发掉落，因为保护目标不是靠破坏获得的资源）
            level.removeBlock(pos, false);
            GuardianProtocol.LOGGER.info("[{}] 保护目标在 {} 被摧毁（失守）", GuardianProtocol.MODID, pos);
        }
    }

    /** 判断某坐标是否是保护目标方块。 */
    public static boolean isProtectTarget(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof ProtectTargetBlock;
    }

    /** 供外部（命令 / 调试）查询某个保护目标的血量文本。 */
    public static String describe(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ProtectTargetBlockEntity t)) {
            return "不是保护目标";
        }
        if (t.isIndestructible()) {
            return "保护目标（不可摧毁）";
        }
        return String.format("保护目标 %d/%d", t.getHealth(), t.getMaxHealth());
    }

    /** 便于调试：当前被嘲讽的生物数量。 */
    public static int tauntedCount() {
        return TAUNTED.size();
    }
}
