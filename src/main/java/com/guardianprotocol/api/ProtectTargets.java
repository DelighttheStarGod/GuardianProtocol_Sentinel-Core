package com.guardianprotocol.api;

import com.guardianprotocol.block.ProtectTargetBlockEntity;
import com.guardianprotocol.block.ProtectTargetManager;
import com.guardianprotocol.block.TauntRadius;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「保护目标」的公开接口：查、伤、读血量/嘲讽半径。
 *
 * <p>塔防的**失守条件**就是保护目标被摧毁，所以后续的塔防循环项目需要这条通道：
 * 让远程/攻城怪打到它（{@link #damage}），并读它还剩多少血。</p>
 *
 * <p>目前敌人一律「接触即被抹杀」，所以实际上挨不到打 —— 这条通道是为
 * 「有远程敌人」的那一天准备的（见 设计说明第 5 条）。</p>
 */
public final class ProtectTargets {

    private ProtectTargets() {
    }

    /** 当前已加载的全部保护目标（只读快照）。 */
    public static List<ProtectTargetBlockEntity> active() {
        return ProtectTargetBlockEntity.activeTargets();
    }

    /** 这个坐标上是不是保护目标方块。 */
    public static boolean isProtectTarget(Level level, BlockPos pos) {
        return ProtectTargetManager.isProtectTarget(level, pos);
    }

    /**
     * 对保护目标造成伤害。
     *
     * <p>返回 {@code true} 表示**这一下把它打没了**（失守）：内部先记下「它还在不在」，
     * 打完再查一次 —— 这样下游不必自己去猜「刚才那下是不是最后一击」。</p>
     *
     * <p>不可摧毁的保护目标（配置里血量设成 ≤0）永远返回 false。</p>
     */
    public static boolean damage(Level level, BlockPos pos, int amount) {
        if (amount <= 0 || !isProtectTarget(level, pos)) {
            return false;
        }
        ProtectTargetManager.damageTarget(level, pos, amount);
        return !isProtectTarget(level, pos);
    }

    /** 当前血量；不是保护目标时返回 -1。 */
    public static int healthOf(Level level, BlockPos pos) {
        ProtectTargetBlockEntity target = at(level, pos);
        return target == null ? -1 : target.getHealth();
    }

    /** 血量上限；不是保护目标时返回 -1。 */
    public static int maxHealthOf(Level level, BlockPos pos) {
        ProtectTargetBlockEntity target = at(level, pos);
        return target == null ? -1 : target.getMaxHealth();
    }

    /** 是不是不可摧毁（配置里血量 ≤0）。 */
    public static boolean isIndestructible(Level level, BlockPos pos) {
        ProtectTargetBlockEntity target = at(level, pos);
        return target != null && target.isIndestructible();
    }

    /** 当前嘲讽半径（格）；不是保护目标时返回 -1。 */
    public static double tauntRadiusOf(Level level, BlockPos pos) {
        ProtectTargetBlockEntity target = at(level, pos);
        return target == null ? -1.0D : target.getTauntRadius().radius();
    }

    /**
     * 调整某个保护目标的嘲讽半径（逐方块独立，会被量化到最近的 8 格档位）。
     *
     * @return 是否真的改到了（不是保护目标 / 坐标没方块时为 false）
     */
    public static boolean setTauntRadius(Level level, BlockPos pos, double radius) {
        ProtectTargetBlockEntity target = at(level, pos);
        if (target == null) {
            return false;
        }
        target.setTauntRadius(TauntRadius.fromConfigRadius(radius));
        return true;
    }

    /** 供日志/指令用的一行描述。 */
    public static String describe(Level level, BlockPos pos) {
        return ProtectTargetManager.describe(level, pos);
    }

    /** 当前被嘲讽的生物数量（排查用）。 */
    public static int tauntedCount() {
        return ProtectTargetManager.tauntedCount();
    }

    @Nullable
    private static ProtectTargetBlockEntity at(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ProtectTargetBlockEntity target ? target : null;
    }
}
