package com.guardianprotocol.combat;

import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 「被挡住的敌人该走向哪里」的<b>可替换策略</b>。
 *
 * <h3>为什么要有这一层</h3>
 * <p>本轮把阻挡从「每 tick 把敌人拽到固定点」改成「<b>只下发寻路目标，让敌人自己走过去</b>」
 * （设计口径）。主循环（容量判定 → 下发导航 → 锁攻击目标 → 放行）已经跑通，
 * 但「锚点怎么选」这件事要结合地形、场景、关卡布局来设计 ——
 * 台阶怎么处理、坡道怎么走、锚点被方块挡住怎么办、多波次切换时锚点要不要重置、
 * 锚点与保护目标要保持什么距离，这些都属于<b>塔防循环项目</b>的调优工作。</p>
 *
 * <p>所以本阶段只给一个<b>平坦地形可用的占位实现</b>（{@link FlatFrontAnchor}），
 * 并把接口留在这里：塔防循环项目换锚点策略时<b>只替换实现</b>，主循环一行都不用动
 * （下游通过 {@code api/Blocking#setAnchorStrategy(...)} 换）。</p>
 *
 * <h3>实现约定</h3>
 * <ul>
 *     <li>返回 {@code null} = <b>本 tick 不给这个敌人下发导航</b>（交还原版 AI）。
 *         占位实现从不返回 null，但未来的策略可以用它表达「这个敌人现在没有合适的锚点」
 *         （例如完全够不到、地形不连续）。</li>
 *     <li>实现里**不要**碰敌人的坐标与速度 —— 那是被本轮明确废弃的做法。</li>
 *     <li>同一个 {@code (slot, total)} 组合在连续几 tick 里应当稳定（否则敌人会在
 *         两个锚点之间来回走），但接口不强制：多波次/动态锚点将来可能需要它变化。</li>
 * </ul>
 */
public interface BlockAnchorStrategy {

    /**
     * 第 {@code slot} 个（共 {@code total} 个）被挡住的敌人应该走向哪里。
     *
     * @param level  所在服务端世界
     * @param unit   挡人的棋子
     * @param target 被挡住的敌人
     * @param slot   名次，0 起（按离棋子的距离排序后的次序）
     * @param total  本 tick 被挡住的总数（= 分支阻挡数，未满员时是实际个数）
     * @return 锚点世界坐标；{@code null} 表示本 tick 不下发导航
     */
    @Nullable
    Vec3 anchor(ServerLevel level, PixelUnit unit, LivingEntity target, int slot, int total);

    /** 策略名（报告与调试用，一句话说清「锚点是怎么选的」）。 */
    String name();
}
