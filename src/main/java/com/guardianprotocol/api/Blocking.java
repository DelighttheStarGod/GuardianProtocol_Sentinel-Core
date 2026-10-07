package com.guardianprotocol.api;

import com.guardianprotocol.combat.BlockAnchorStrategy;
import com.guardianprotocol.combat.BlockCandidates;
import com.guardianprotocol.combat.BlockTeams;
import com.guardianprotocol.combat.FlatFrontAnchor;
import com.guardianprotocol.combat.PawnCombatManager;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「阻挡」的公开入口（下游只碰 {@code api} 包，见 设计说明）。
 *
 * <h3>本阶段（2026-10）阻挡是什么</h3>
 * <p>棋子把最近的 N 个敌人（<b>N = 分支的「常态阻挡」</b> —— 不是对照表那一列「阻挡数」，
 * 那一列是<b>技能期 / 显示值</b>：解放者表里 3、常态 0，见 踩坑记录）挡在身前，
 * 做法是<b>只下发寻路目标 +
 * 锁攻击目标</b>，<b>不碰敌人的坐标</b> —— 敌人自己走到锚点，被击退后自己走回来。
 * 第 N+1 个起放行（把它被改过的寻路与攻击目标清掉，交还原版 AI）。
 * <b>常态阻挡为 0 的分支（伏击客 / 解放者）一个名额都不占</b>，也不会成为实体障碍。</p>
 *
 * <h3>★ 2026-10 补的洞：棋子必须是<b>实体障碍</b></h3>
 * <p>上面那一整套只有在「棋子撞得过去」的前提下才成立。棋子原本**不是**障碍
 * （1.20.1 里活着的实体只有潜影贝 `canBeCollidedWith`，见 踩坑记录），
 * 敌人会直接从它身上穿过去。现在 {@code PixelUnit#canBeCollidedWith()} 让它成为实心障碍
 * （判据 = {@code isAlive() && 常态阻挡 > 0}：常态阻挡为 0 的棋子<b>仍然不是</b>障碍，
 * 那是设计口径 的口径），
 * 并按下面的口径处理随之而来的「绕行」：</p>
 * <ul>
 *     <li><b>实心与白名单无关</b>：白名单管「谁被<b>按住</b>」，实心管「谁<b>过不去</b>」——
 *         所以即使白名单是空的（默认），棋子照样挡得住实体；</li>
 *     <li><b>放行者的「绕行」是补出来的</b>：原版寻路看不见实体
 *         （{@code PathNavigationRegion#getEntityCollisions} 返回空），所以被挡住之后敌人
 *         <b>不会自己绕开</b>。名额之外的第 N+1 个由主循环补一条侧步路点绕过去
 *         （{@link #detourCount()} 可以看它到底下了几次）。</li>
 * </ul>
 *
 * <h3>★ 2026-10 重做：<b>拉怪</b> —— 语义影响写在下面（别当 bug 修）</h3>
 * <p>棋子每 tick 还会把<b>自己攻击范围内</b>的敌人锁成 {@code target = 棋子}
 * （{@code PawnCombatManager.lureIntoRange}：范围与索敌共用同一份 {@code candidatesInRange}，
 * <b>不</b>另写一份；门 = 分支的 {@code canAttack()} —— 解放者 / 阵法术师 / 吟游者<b>不拉怪</b>）。
 * 它比上面那套阻挡<b>先跑</b>（怪得先有目标才会朝棋子走，走到了才进得了被挡名单）。两点影响：</p>
 * <ul>
 *     <li><b>拉怪不看白名单、也不看锚点名额</b>：放行只放行「锚点与寻路」（外加放行那一刻清掉目标/队），
 *         放行者<b>只要还在棋子的攻击范围内，下一 tick 就会被重新锁成 {@code target=棋子}</b>
 *         ⇒ <b>「放行者一定 {@code target=null}」这个假设只在攻击范围之外成立</b>；</li>
 *     <li><b>拉怪会覆盖原有的 target</b>（含正在追玩家的怪）—— 与本 mod「优先打棋子而不是追玩家」
 *         的既有口径一致。可观测的副作用：被拉住的怪不再吃「绕行侧步」
 *         （{@code routeAround} 的护栏本来就写着「不碰把这个棋子当目标的怪」）。</li>
 * </ul>
 * <p><b>与嘲讽让位是同一条链的两端</b>：被拉成 {@code target=棋子} 之后，身上挂着嘲讽 goal
 * 的怪会让出 {@code MOVE} 旗标（见 设计说明），它自己的原版近战 goal 才起得来
 * ⇒ 才会边走边挥拳。让位判据一个字都没改。</p>
 *
 * <h3>★ 交给塔防循环项目的东西</h3>
 * <ul>
 *     <li><b>可替换的锚点策略</b>：{@link #setAnchorStrategy} / {@link #anchorStrategy}
 *         —— 换锚点只需要实现 {@link BlockAnchorStrategy}，主循环一行都不用动；</li>
 *     <li><b>一份占位实现</b>：{@link FlatFrontAnchor}（正前方 1.3 格 + 名次横向 0.3 格），
 *         只保证平坦地形观感正确；</li>
 *     <li>已跑通的<b>容量判定 + 导航停止</b>主循环（就在 {@code combat/PawnCombatManager} 里）；</li>
 *     <li>「谁会被挡」的判据 {@link #rejectReason}（白名单 + 硬性排除，配置在
 *         {@code config/guardian_protocol-common.toml} 的 {@code [blocking] whitelist}）。</li>
 * </ul>
 *
 * <p>后续项目要补的是：地形适配（台阶/坡道/锚点被方块挡住）、可达性检查、
 * 多波次时锚点的重置、锚点与保护目标的距离关系 —— 都在这层之下，不需要改主循环。</p>
 */
public final class Blocking {

    private Blocking() {
    }

    /** 当前锚点策略。 */
    public static BlockAnchorStrategy anchorStrategy() {
        return PawnCombatManager.anchorStrategy();
    }

    /**
     * 换锚点策略（返回旧值便于还原）。
     *
     * <p>调用时机随意（服务端运行时即可），生效于下一个 tick —— 策略字段是 volatile 的。</p>
     */
    public static BlockAnchorStrategy setAnchorStrategy(BlockAnchorStrategy strategy) {
        return PawnCombatManager.setAnchorStrategy(strategy);
    }

    /** 这个棋子当前挡住了哪些敌人（只读快照）。 */
    public static List<LivingEntity> blockedOf(PixelUnit pawn) {
        return List.copyOf(PawnCombatManager.debugBlocked(pawn));
    }

    /**
     * 只读：这颗棋子此刻的<b>拉怪范围</b>（= 它自己的攻击范围）里有哪些可被下目标的生物。
     *
     * <p>口径与主循环里真正拉怪的那一步<b>共用同一份判据</b>（{@code candidatesInRange} +
     * 只留 {@code PathfinderMob}），所以它既不会多报也不会少报 —— 自验 G 组与
     * {@code /guardianprotocol block} 都读它。<b>只读</b>：不设 target、不动导航、不碰坐标。</p>
     */
    public static List<LivingEntity> lureCandidates(ServerLevel level, PixelUnit pawn) {
        return PawnCombatManager.debugLureCandidates(level, pawn);
    }

    /** 当前全场被挡住的敌人总数。 */
    public static int blockedCount() {
        return PawnCombatManager.blockedCount();
    }

    /**
     * 累计下发过多少次「绕行侧步」（进程内计数，只增）。
     *
     * <p>为什么值得开放：**「棋子实心 + 放行者还能过去」这件事只有一个可观测证据**，
     * 就是它。敌人在世界里的位移看不出是谁下的指令；而侧步次数从 0 变成 1，
     * 说明确实是本 mod 把它从棋子前面引开的（自验场景 {@code solid} 就断言了这一点）。</p>
     */
    public static int detourCount() {
        return PawnCombatManager.debugDetourCount();
    }

    /**
     * 这个实体能不能被挡住；不能则返回原因（一句话，可为 null = 能）。
     *
     * <p>排查「为什么它没被挡住」就用它 —— 「没挡住」有很多互不相干的原因
     * （玩家/棋子/已驯服/不在白名单/白名单为空…），看结果猜不出来。</p>
     */
    @Nullable
    public static String rejectReason(@Nullable Entity entity) {
        return BlockCandidates.rejectReason(entity);
    }

    /** 当前阻挡白名单（只读；改它请改配置文件）。 */
    public static List<String> whitelist() {
        return BlockCandidates.whitelistInEffect();
    }

    /** 把实体挂进「被挡住」队伍（一般不用手调，主循环会做）。 */
    public static boolean tagBlocked(ServerLevel level, Entity entity) {
        return BlockTeams.tag(level, entity);
    }

    /** 队伍状态一行描述（排查用）。 */
    public static String describeTeams(ServerLevel level) {
        return BlockTeams.describe(level);
    }
}
