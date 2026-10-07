package com.guardianprotocol.ai;

import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 「嘲讽」AI：强制把一个生物的移动目标改成保护目标所在位置。
 *
 * <p>为什么不用 {@code Mob.setTarget()}：那条路是给「攻击型生物锁定攻击对象」用的，
 * 而且会被原版的 {@code NearestAttackableTargetGoal} / {@code HurtByTargetGoal}
 * 每 tick 重新覆盖（例如僵尸会立刻把目标改回最近的玩家）。
 * 这里直接做<b>移动目标重定向</b>：每 tick 让寻路器走向保护目标，
 * 于是生物会无视玩家、一路冲向保护目标——这才是「自带嘲讽」的观感。</p>
 *
 * <p>注意：本类<b>不</b>负责往生物身上挂/摘 goal，
 * 增删由 {@link ProtectTargetManager} 通过 {@code goalSelector} 统一管理，
 * 这样避免在 goal 内部改自己的 goal 列表（会引发 ConcurrentModification）。</p>
 *
 * <h3>★ 它会让位（2026-10 实机 A/B 定位）</h3>
 * <p>本 goal 是 priority 0 且独占 {@code MOVE} 旗标 —— 这既是它能压过随机游走的原因，
 * 也是「被嘲讽的怪被棋子挡住后打不还手」的成因：原版近战 goal 要同一面旗标，
 * 而原版 {@code GoalSelector} 只允许 priority 不高于当前占用者的 goal 顶掉它
 * （逐字判据见 {@link #canUse()} 的注释）。所以 {@code canUse()} 在
 * 「攻击目标是我方棋子」时直接返回 false，把旗标让给它的近战 goal；
 * 目标没了（棋子被放行 / 死亡）自动恢复行军。</p>
 */
public class MoveToProtectTargetGoal extends Goal {

    /** 每次重新下发路径的间隔（tick）。太频繁会明显掉帧。 */
    private static final int REPATH_INTERVAL = 10;

    /**
     * 认为「已到达」的距离（格，平方值）。
     *
     * <p>★ 这个值必须**明显小于**抹杀判定半径，否则会出现「敌人走到一半就停下、
     * 永远碰不到保护目标」的僵局：goal 认为到了、不再驱动移动，
     * 而抹杀判定还没命中。这里取 0.25（即 0.5 格），配合默认 killRadius=1.0，
     * 保证敌人会一路走进抹杀区。</p>
     */
    private static final double ARRIVE_DISTANCE_SQR = 0.25D;

    private final PathfinderMob mob;
    /** 目标点；为 null 表示当前没有嘲讽目标，goal 自动失活。 */
    @Nullable
    private Vec3 targetPos;
    private int repathCooldown;

    public MoveToProtectTargetGoal(PathfinderMob mob) {
        this.mob = mob;
        // MOVE 是「移动类」goal 的互斥标记：同一时刻只有一个 MOVE goal 生效，
        // 这样嘲讽会自然抢占随机游走等其它移动行为。
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /** 由 manager 调用：设置/清除嘲讽目标点。 */
    public void setTargetPos(@Nullable Vec3 pos) {
        this.targetPos = pos;
        this.repathCooldown = 0;
    }

    @Nullable
    public Vec3 getTargetPos() {
        return this.targetPos;
    }

    @Override
    public boolean canUse() {
        if (this.targetPos == null) {
            return false;
        }
        // ★★ 2026-10（实机 A/B 定位的**第三个断点**）：盯上我方棋子时**让出 MOVE 旗标**。
        //
        //   本 goal 挂在 priority 0 且占 {@code Flag.MOVE}，而原版近战 goal（僵尸的
        //   {@code ZombieAttackGoal} 挂在 priority 2、要 {@code MOVE + LOOK}）能不能起跑
        //   由原版 {@code GoalSelector} 决定，判据逐字如下（1.20.1 反编译源）：
        //     · {@code GoalSelector#goalCanBeReplacedForAllFlags}（:70）遍历候选 goal 要的
        //       每一面旗标，要求该旗标上锁着的那个 goal 必须 {@code canBeReplacedBy(候选)}；
        //     · {@code GoalSelector#tick}（:103）用它 + {@code canUse()} 决定起跑；
        //     · {@code WrappedGoal#canBeReplacedBy} = {@code this.isInterruptable()
        //       && this.getPriority() >= other.getPriority()}。
        //   ⇒ 锁着 MOVE 的是 priority **0**、候选近战 goal 是 **2**，{@code 0 >= 2} 为假
        //     ⇒ **近战 goal 永远起不来** ⇒ 它举着手不挥拳。
        //
        //   实机 A/B 与此完全对上（设计口径 实机校验原话）：
        //     「当敌方被保护目标嘲讽后被棋子阻挡会出现打不还手，但如果没被保护目标
        //      嘲讽被阻挡时则会正常攻击棋子」；
        //     「除了监守者是一直能攻击棋子外，其他敌人都是打不还手」——
        //      监守者走 {@code Brain}/Activity，**根本不经过 GoalSelector**，所以它是例外，
        //      这条例外反过来印证了「旗标竞争」这个机理。
        //   （同轮**证伪**了「队伍同盟 {@code isAlliedTo} 拦住原版伤害」那条猜想：
        //   原版 28 处 {@code isAlliedTo} 引用里没有一处在伤害路径上 —— 只有选目标的
        //   {@code TargetingConditions}、玩家互伤的 {@code Player#canHarmPlayer}、
        //   推挤与渲染；{@code LivingEntity#hurt} / {@code Mob#doHurtTarget} 都不查它。）
        //
        //   所以：只要它的攻击目标是我方棋子（拉怪 / 锁目标锁上的那种），这一份嘲讽就先让位，
        //   MOVE 旗标空出来给它的近战 goal。棋子死了或目标被清掉 ⇒ 回到下面那条距离判据，
        //   嘲讽自动恢复。
        LivingEntity target = this.mob.getTarget();
        if (target instanceof PixelUnit pawn && pawn.isAlive()) {
            return false;
        }
        // 已经贴到目标附近就不必再驱动移动了（抹杀逻辑会负责）。
        return this.mob.distanceToSqr(this.targetPos) > ARRIVE_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.moveTowards();
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.targetPos == null) {
            return;
        }
        if (this.repathCooldown-- <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.moveTowards();
        }
    }

    private void moveTowards() {
        if (this.targetPos == null) {
            return;
        }
        PathNavigation nav = this.mob.getNavigation();
        // 移速取生物自身的「跟随速度」；这样不同生物快慢不同，观感自然。
        nav.moveTo(this.targetPos.x, this.targetPos.y, this.targetPos.z, 1.0D);
    }
}
