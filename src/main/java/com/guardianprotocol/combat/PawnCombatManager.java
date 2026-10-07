package com.guardianprotocol.combat;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.data.AttackRange;
import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.entity.PawnProjectile;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 棋子的索敌 + 攻击 + 阻挡。
 *
 * <h3>为什么单独一个 manager，而不是给实体加 AI goal</h3>
 * <p>棋子是<b>钉死</b>的（不能用寻路、不会移动），原版的近战/远程 goal 都依赖寻路与
 * 目标追踪，套不上。而「按格子判断攻击范围」这件事本来就是纯几何计算，
 * 放在一个周期性的 manager 里最直观，也方便统一限流。</p>
 *
 * <h3>攻击范围判定</h3>
 * <p>范围来自 {@link AttackRange}：一组相对 {@code (x, z)} 偏移。
 * 判定时把棋子所在方块坐标加上偏移，得到若干<b>方块坐标</b>，
 * 然后在这些方块内找敌人。这与「格子制塔防」的手感一致 ——
 * 打的是格子，不是圆形半径。</p>
 *
 * <h3>阻挡</h3>
 * <h3>阻挡（★ 2026-10 重做：只改寻路目标，不碰坐标）</h3>
 * <p>满足「{@code blockCount > 0}」的分支会挡住离它最近的 <b>N = 阻挡数</b> 个敌人，
 * 第 N+1 个起放行。对被挡住的敌人<b>只做两件事</b>：</p>
 * <ol>
 *     <li>给它下发一个<b>寻路目标</b>（锚点由 {@link BlockAnchorStrategy} 给，占位实现见
 *         {@link FlatFrontAnchor}），让它自己走过去、走到了就停；</li>
 *     <li>把它的<b>攻击目标</b>锁成这颗棋子（中立生物还要设愤怒目标+愤怒时间），
 *         免得它转身去追玩家。</li>
 * </ol>
 * <p>★ <b>全程不调 {@code setPos} / {@code setDeltaMovement}</b>：上一版是「每 tick 把敌人
 * 拽到锚点」，于是被击退/被挤开之后下一 tick 又被拽回来，看起来像被橡皮筋拉着；
 * 现在敌人被击退后会<b>自己重新寻路走回锚点</b>，观感是「撞到墙被弹开后又走回去」。
 * 放行时把它被我们改过的寻路与攻击目标一起清掉，交还原版 AI。</p>
 *
 * <p>棋子本身<b>不产生任何常态攻击击退</b>（设计口径）：{@link #applyHit} 会把目标
 * 挨打前的速度原样复位，等于取消原版那次击退 —— 所以也不再需要上一版那个
 * 「挨打后把被挡住的目标位置复位」的补丁。</p>
 *
 * <h3>攻击方式（★ 2026-10 新增）</h3>
 * <p>出手前先看 {@link BranchDef#attackMethod()}：</p>
 * <ul>
 *     <li>{@link UnitBranch.AttackMethod#PROJECTILE}：发射投掷物（{@link PawnProjectile}）——
 *         无视重力、带粒子轨迹、<b>命中那一刻</b>才结算伤害。远程位分支与
 *         「近战位但有远程攻击」的 6 个分支（情报官 / 领主 / 哨戒铁卫 / 要塞 / 伏击客 / 钩索师）走这条；</li>
 *     <li>{@link UnitBranch.AttackMethod#MELEE}：近战武器攻击 —— 挥臂动作包 + 刀光粒子 + 劈砍音效，
 *         伤害即时结算。其余 29 个近战位分支走这条。</li>
 * </ul>
 * <p>两条路的伤害结算<b>只有一处实现</b>（{@link #applyHit}），投掷物命中也回调它 ——
 * 「护甲/附魔照常生效」这条口径因此不会各写一份；而<b>常态攻击不产生击退</b>
 * （设计口径）也在这一个方法里统一取消。</p>
 */
public final class PawnCombatManager {

    /** 每个棋子距上次攻击还有多少 tick。key 是棋子，value 是冷却。 */
    private static final Map<PixelUnit, Integer> COOLDOWN = new HashMap<>();

    /**
     * 每个棋子当前按住了哪些敌人（用于名额统计与释放）。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code HashSet}：多目标攻击要按确定的顺序出手，
     * {@code HashSet} 的迭代顺序既不稳定也不可复现，会让「同一批敌人谁先挨打」每 tick 变。</p>
     */
    private static final Map<PixelUnit, Set<PathfinderMob>> BLOCKED = new HashMap<>();

    /**
     * 每个棋子最近一次选中的目标（只读快照）。
     *
     * <p>只为<b>可观测性</b>存在：没有它，「这个棋子到底选中了谁」在服务端完全看不见，
     * 自验/排查只能靠看伤害数字 —— 而棋盘上别的东西也会打伤害，很容易误判。
     * 自验命令 {@code /guardianprotocol selftest} 读的就是它。</p>
     */
    private static final Map<PixelUnit, List<LivingEntity>> LAST_TARGETS = new HashMap<>();

    /**
     * 被挡住的敌人的<b>粘滞半径</b>（格）：离棋子超过这个距离就松手。
     *
     * <p>为什么需要它：敌人被挡住之后是<b>自己走过去</b>的，中途可能被别的实体挤开、
     * 被爆炸顶飞、或者干脆寻路失败跑去别处。这时候名额如果一直占着，
     * 它的同伴就永远进不来（容量语义失效）。8 格是个宽松的兜底值 ——
     * 正常被击退不会超过 2~3 格，能走到 8 格外的说明它已经不在「这个棋子的战场」上了。</p>
     */
    private static final double BLOCK_KEEP_RADIUS = 8.0D;

    /**
     * 下发寻路时用的速度倍率（1.0 = 生物自己的寻路速度）。
     *
     * <p>用生物自身速度而不是写死一个绝对值：不同生物快慢不同，观感才自然
     * （同 {@code MoveToProtectTargetGoal} 的做法）。</p>
     */
    private static final double BLOCK_NAV_SPEED = 1.0D;

    /**
     * 中立生物被锁成「愤怒」状态时的剩余愤怒时间（tick）。
     *
     * <p>中立生物（僵尸猪灵、蜘蛛…）<b>光设 target 不会真的打</b>：它们的攻击 goal 看的是
     * 「愤怒目标 + 剩余愤怒时间」。每 tick 重设，所以取一个够大的值即可（200 tick = 10 秒）。</p>
     */
    private static final int NEUTRAL_ANGER_TICKS = 200;

    /**
     * 锚点策略（<b>可替换</b>，设计要求留的接口）。
     *
     * <p>占位实现走「正前方 1.3 格 + 名次横向 0.3 格」；地形适配、可达性检查、
     * 多波次行为留给塔防循环项目，换策略只需要调
     * {@link #setAnchorStrategy}（下游走 {@code api/Blocking}）。</p>
     */
    private static volatile BlockAnchorStrategy anchorStrategy = FlatFrontAnchor.INSTANCE;

    /** 当前锚点策略。 */
    public static BlockAnchorStrategy anchorStrategy() {
        return anchorStrategy;
    }

    /**
     * 换锚点策略（返回旧值便于还原）。
     *
     * <p>{@code null} 视为非法：那会让 {@link #driveToAnchor} 每 tick 抛 NPE，
     * 而异常被上层 catch 之后的表现是「棋子不阻挡了」，极难查。</p>
     */
    public static BlockAnchorStrategy setAnchorStrategy(BlockAnchorStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("锚点策略不能为 null");
        }
        BlockAnchorStrategy old = anchorStrategy;
        anchorStrategy = strategy;
        return old;
    }

    /**
     * <b>攻击间隔倍率（全局，可调）</b>。
     *
     * <p>★ 值、口径与「为什么不改表格那一列」的完整说明在
     * {@link BlockGeometry#ATTACK_INTERVAL_MULTIPLIER}（纯数据类，可脱离 Minecraft 验证）。
     * <p>默认值取自 {@link BlockGeometry#ATTACK_INTERVAL_MULTIPLIER}。自验命令
     * {@code /guardianprotocol selftest} 会在隔离场景里临时改成 1.0 以便在有限 tick 内
     * 观察到多次出手（见 {@link #setAttackIntervalMultiplier}），跑完自动还原。</p>
     */
    private static double attackIntervalMultiplier = BlockGeometry.ATTACK_INTERVAL_MULTIPLIER;

    /**
     * 当前生效的攻击间隔倍率（调试/自验用）。
     */
    public static double attackIntervalMultiplier() {
        return attackIntervalMultiplier;
    }

    /**
     * 临时改攻击间隔倍率（自验/调试用，返回旧值便于还原）。
     *
     * <p><b>为什么要有这个口子</b>：倍率是 2.0 时，一个 1.2s 的分支要 48 tick 才出一次手，
     * 想在自验里看到「打了几下」得跑上百 tick。自验场景把它临时设成 1.0，
     * 跑完立刻还原 —— 这样自验**不需要改常量、也不会污染正常游戏**。</p>
     *
     * <p>{@code <= 0} 视为非法：那会让间隔变成 0 tick，而上层把「间隔 0」解释成
     * 「不参与自动攻击」（踩坑记录那个耦合），于是自验会得出「棋子不攻击」的假结论。</p>
     */
    public static double setAttackIntervalMultiplier(double value) {
        if (!(value > 0.0D)) {
            throw new IllegalArgumentException("攻击间隔倍率必须 > 0，收到 " + value);
        }
        double old = attackIntervalMultiplier;
        attackIntervalMultiplier = value;
        return old;
    }

    /**
     * 多目标攻击的伤害系数（先按 1.0 = 每个目标都吃满额伤害）。
     *
     * <h3>为什么要显式留这个系数</h3>
     * <p>表里只说「同时攻击阻挡的所有敌人」，<b>没说</b>同时打多个时单个目标的伤害要不要打折。
     * 原作里这类分支的平衡靠的是「面板偏低 + 攻击间隔偏长」，而本工程的面板来自
     * 给定的职业模板（未按分支的攻击目标数再平衡）。所以这里：
     * <b>保持 1.0（满额）</b>，先把「机制」跑对，强弱留给实机试玩后再调这一个数 ——
     * 而不是现在偷偷塞一个除法（那样数值会变成「说不清怎么来的」，
     * 踩坑记录「表缺数据变成玩法规则」就是这一类）。</p>
     *
     * <p>举例：强攻手（阻挡 3、基础间隔 1.2s，乘上 {@link BlockGeometry#ATTACK_INTERVAL_MULTIPLIER}
     * 后 1.8s）挡满 3 个敌人时 DPS 约为单体的 3 倍。若实机觉得过强，把这个系数调到 0.6~0.7 即可，
     * 无需改机制。</p>
     */
    private static final float MULTI_ATTACK_DAMAGE_SCALE = 1.0F;

    private PawnCombatManager() {
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用。 */
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        tickCounter++;
        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level);
        }
        // ★ prune 必须**每个服务端 tick 只调一次**，见 prune() 的注释：
        //   放进 tickLevel 里会让「按维度清理」变成「把别的维度的状态清掉」——
        //   服务端有 3 个维度（主世界/下界/末地），于是主世界棋子的冷却每 tick 被清一次，
        //   表现是**棋子每个 tick 都出手**、攻击间隔完全失效（2026-10 实测抓到的真 bug）。
        prune();
    }

    /** 世界卸载 / 服务器停止时清缓存（把被挡住的敌人一起放行，别留下「盯着不存在目标」的怪）。 */
    public static void clear() {
        for (Map.Entry<PixelUnit, Set<PathfinderMob>> e : BLOCKED.entrySet()) {
            ServerLevel level = e.getKey().level() instanceof ServerLevel sl ? sl : null;
            for (PathfinderMob mob : e.getValue()) {
                release(level, e.getKey(), mob);
            }
        }
        COOLDOWN.clear();
        BLOCKED.clear();
        LAST_TARGETS.clear();
        DETOUR_UNTIL.clear();
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private static void tickLevel(ServerLevel level) {
        for (PixelUnit unit : PixelUnit.activeUnits()) {
            // 注意：Entity 上没有 getLevel()，只有 level()（1.20.1 官方名）
            if (unit.isRemoved() || unit.level() != level || !unit.isAlive()) {
                continue;
            }
            try {
                tickUnit(level, unit);
            } catch (Exception ex) {
                // 单个棋子出错不应该拖垮整个 tick（否则一个坏数据会让全场停止攻击）
                GuardianProtocol.LOGGER.error("[{}] 棋子战斗逻辑异常，已跳过 {}",
                        GuardianProtocol.MODID, unit.getUUID(), ex);
            }
        }
    }

    private static void tickUnit(ServerLevel level, PixelUnit unit) {
        BranchDef branch = unit.getBranch();

        // 0) ★ 被动回血（吟游者）：**不经过攻击路径与出手冷却** —— 它本来就不攻击。
        //    用本 mod 自己的 tick 计数，不用 level.getGameTime()（自验台把游戏刻冻住了，见踩坑记录）。
        UnitBranch.Heal heal = branch.heal();
        if (heal.kind() == UnitBranch.Heal.Kind.REGEN && heal.regenInterval() > 0
                && tickCounter % heal.regenInterval() == 0) {
            regenPulse(level, unit, branch, heal);
        }

        // 1) 攻击冷却
        int cd = COOLDOWN.getOrDefault(unit, 0);
        if (cd > 0) {
            COOLDOWN.put(unit, cd - 1);
        }

        // 1.5) ★ 解放者的 ramp（2026-10 第五轮）：**技能激活时归零、否则 +1**。
        //      归零发生在技能期间，所以「技能一结束它天然是 0」= 精二特性原文的
        //      「技能结束时重置攻击力」；「只在技能未开启时累积」也由同一个判断给出。
        //      （不额外挂「技能结束」的回调：状态归零比事件回调更不容易漏。）
        if (branch.damageMod().kind() == UnitBranch.DamageMod.Kind.LIBERATOR_RAMP) {
            if (unit.isSkillActive()) {
                unit.setRampTicks(0);
            } else {
                int next = unit.getRampTicks() + 1;
                unit.setRampTicks(Math.min(next, PixelUnit.LIBERATOR_RAMP_MAX_TICKS));
            }
        }

        // 2) ★★ 拉怪（2026-10 重做）：把**棋子的攻击范围内**的敌人锁成 target = 棋子。
        //
        //    ★ 为什么必须排在 updateBlocking **之前**：拉怪是「给目标」，阻挡是「要求它站到锚点上」。
        //      怪物只有先有了目标才会朝棋子走，走到了才进得了「被挡名单」——
        //      顺序颠倒就变成「先要求它已经站在棋子面前」，而缺的正是这条起点：
        //      **原版没有任何 goal 会主动把棋子选成目标**（僵尸的 targetSelector 只有
        //      HurtByTargetGoal + NearestAttackableTargetGoal<Player>/<AbstractVillager>/<IronGolem>/<Turtle>，
        //      1.20.1 Zombie#addBehaviourGoals），而本 mod 唯一给怪赋目标的地方
        //      （updateBlocking → driveToAnchor → lockAttackTarget）只对**已经进了被挡名单**的怪生效。
        //
        //    ★ 门只认 branch.canAttack()：解放者 / 阵法术师 / 吟游者三个分支关着
        //      （表里写「通常不攻击」），它们不拉怪 —— 否则「无脑拉全场」把非战斗分支也变成引怪器。
        //
        //    ★ 范围口径：**拉怪范围 = 棋子的攻击范围**，复用索敌那一份
        //      candidatesInRange（攻击范围格子 + 垂直区间 + Targeting.isEnemy），
        //      不另写第二份范围判据，也不改 pickTargets 的语义。
        if (branch.canAttack()) {
            lureIntoRange(level, unit, branch);
        }

        // 3) 阻挡：把正前方的敌人按住。
        //    先做这一步，本 tick 的索敌就一定能看到被按住的敌人（它们被钉在攻击范围里）。
        //
        //    ★ 这一步对「不攻击」的分支（解放者/阵法术师/吟游者）**照常执行**：
        //      表里说的是它们不出手，不是不站场。解放者的阻挡数本来就是 0，
        //      所以它只走个空流程；将来技能系统上线后它会先恢复阻挡再恢复攻击。
        Set<PathfinderMob> held = updateBlocking(level, unit);

        // 3b) 绕行：棋子是**实心**的（见 PixelUnit#canBeCollidedWith），而原版寻路看不见实体
        //     —— 被我们挡住的那些本来就该顶在锚点上，名额之外的第 N+1 个必须能过去。
        routeAround(level, unit, held);

        // 4) 索敌：按分支的机制（打谁 + 一次打几个）挑出本 tick 的目标
        List<LivingEntity> targets = pickTargets(level, unit, branch);
        if (targets.isEmpty()) {
            return;
        }

        // 5) 攻击（受攻击间隔限制）
        if (COOLDOWN.getOrDefault(unit, 0) > 0) {
            return;
        }
        int interval = attackIntervalTicks(branch);
        if (interval <= 0) {
            // 表格没给攻击间隔，不参与自动攻击（当前 72/72 都有值，这是兜底路径）
            return;
        }
        // ★★ 出手挂钩（技能位）：**整场只有这一处**调 {@code Skills.onAttack}。
        //    走到这里就意味着「本 tick 确实要出手了」（有目标 + 冷却已过 + 有间隔），
        //    而下面这一轮把三种出手形式都走完：近战（applyHit）/ 投掷物（弹体命中回调 applyHit）/
        //    **医疗分支的治疗出手**（applyHeal）—— 所以攻击回复类的医疗分支不会漏涨技力
        //    （设计文档「医疗分支的治疗出手也算一次出手」）。
        //
        //    为什么放在**循环外**、而不是放进 attack()（那个方法一次只对一个目标出手）：
        //    本 mod 的多目标分支（散射手 MULTI_ALL、强攻手/收割者/重剑手/推击手 MULTI_BLOCKED）
        //    会在一个 tick 内对 N 个目标各调一次 attack()。PRTS 的口径是「每次攻击回复 1 点技力」、
        //    「每次攻击消耗 N 发弹药」—— 都是**按出手计**。放在 attack() 里会让群攻分支一次出手
        //    涨 N 点技力、消耗 N×M 发弹药（32 发 / 每击 2 发的散射手打 3 个目标就掉 6 发），
        //    那是错的。放在这里 = 一次出手一次回调，与表里「每次攻击消耗 2 发」逐字对得上。
        //    （副作用写出来：目标在循环里被前一手打死时，这一次出手仍已计入 —— 与原作
        //     「出手就算一次攻击」一致。）
        //
        //    技力 / 激活 / 弹药 / 结束原因的全部判据在 combat/Skills 一处，这里不复制任何一条。
        Skills.onAttack(unit);
        for (LivingEntity target : targets) {
            // 多目标时，前一个目标可能把后面的挤掉/打死，逐个复核再出手
            if (target.isRemoved() || target.isDeadOrDying()) {
                continue;
            }
            attack(level, unit, branch, target);
        }
        COOLDOWN.put(unit, interval);
    }

    // ------------------------------------------------------------------
    // 攻击节拍
    // ------------------------------------------------------------------

    /**
     * 这个分支实际出手的间隔（tick）＝ 表格基础间隔 × 20 × {@link #ATTACK_INTERVAL_MULTIPLIER}。
     *
     * <p>分成两层是刻意的：</p>
     * <ul>
     *     <li>{@code UnitBranch.attackIntervalTicks()} 是**数据**（表格原值，PRTS 口径），
     *         有 `verify_branch_fields.py` 逐字核对；</li>
     *     <li>本方法是**手感**（试玩调参），只改 {@code ATTACK_INTERVAL_MULTIPLIER} 一个数。</li>
     * </ul>
     *
     * <p>返回 0 只有一种情况：表格没给间隔（该分支不参与自动攻击）。
     * 只要表里有值，结果**至少 1 tick** —— 否则 {@code Math.round} 在极端参数下
     * 可能把它算成 0，而 0 在上层被解释成「不攻击」，
     * 于是「调节奏」反而把分支改成不攻击了。这是一个很隐蔽的耦合，故显式兜住
     * （同族问题见 踩坑记录：「表缺数据被解释成玩法规则」）。</p>
     */
    private static int attackIntervalTicks(BranchDef branch) {
        return Math.max(1, (int) Math.round(
                branch.attackIntervalSeconds() * BlockGeometry.TICKS_PER_SECOND
                        * attackIntervalMultiplier));
    }

    // ------------------------------------------------------------------
    // 拉怪（★ 2026-10 重做）：先给它目标 —— 缺少的正是这条链条的起点
    // ------------------------------------------------------------------

    /**
     * <b>拉怪</b>：把棋子<b>攻击范围内</b>的敌人锁成 {@code target = 棋子}（只下目标，不碰坐标）。
     *
     * <h3>为什么必须有这一步（2026-10 查证，不是猜的）</h3>
     * <p><b>原版没有任何 goal 会主动把棋子选成目标。</b>僵尸的 {@code targetSelector} 只有
     * {@code HurtByTargetGoal} + {@code NearestAttackableTargetGoal<Player>} +
     * {@code <AbstractVillager>} + {@code <IronGolem>} + {@code <Turtle>}
     * （1.20.1 {@code Zombie#addBehaviourGoals}，源码在 {@code mapped_official} sources jar 里逐行读过），
     * 棋子（{@code PixelUnit}）不在其中任何一条里；<b>监守者（Warden）是例外</b> ——
     * 它走 {@code Brain}/Activity 那套，不经过 {@code GoalSelector}。</p>
     *
     * <p>而本 mod 唯一给怪赋目标的地方是 {@link #updateBlocking} →
     * {@link #driveToAnchor} → {@link #lockAttackTarget}，它<b>只对已经进了「被挡名单」的怪生效</b>
     * —— 也就是要求它<b>先自己走进棋子那一格</b>。链条缺的就是起点：怪不去找棋子 ⇒
     * 走不到棋子那一格 ⇒ 永远进不了被挡名单 ⇒ 永远不还手。实机 {@code /guardianprotocol block}
     * 抓到过这一幕：附近 5 只尸壳中心距 5.46~7.72 格、{@code 在射程内=false}、{@code target=无}、
     * {@code 被挡名单=否}。</p>
     *
     * <h3>三条口径（都不许各写第二份）</h3>
     * <ul>
     *     <li><b>范围 = 棋子的攻击范围</b>：直接用 {@link #candidatesInRange}
     *         —— 它就是索敌用的那一份（攻击范围格子 + 垂直区间 + {@link Targeting#isEnemy}）；
     *         这里<b>不</b>新写范围判据，也<b>不</b>改 {@code pickTargets} 的语义；</li>
     *     <li><b>门 = {@link BranchDef#canAttack()}</b>（调用点判的）：解放者 / 阵法术师 / 吟游者
     *         三个分支关着；</li>
     *     <li><b>只对 {@link PathfinderMob} 下目标</b>：史莱姆/岩浆怪/幻翼这类不吃寻路的给了也没用
     *         （同 {@link #blockCandidates} 的理由）；中立生物连愤怒目标一起写，
     *         判据全在 {@link #lockAttackTarget} 一处。</li>
     * </ul>
     *
     * <h3>语义影响（写进文档，别当 bug 修）</h3>
     * <p>拉怪<b>不看阻挡白名单、也不看锚点名额</b>：放行（第 N+1 个）只放行「锚点与寻路」，
     * 只要它还在棋子的攻击范围内，下一 tick 就会被重新锁成 {@code target = 棋子}
     * —— 所以「放行者一定 {@code target=null}」这个假设<b>只在攻击范围之外成立</b>。
     * 拉怪会覆盖原有的 target（含正在追玩家的怪），与本 mod「优先打棋子而不是追玩家」的既有口径一致。</p>
     *
     * <p>另外它与 {@link #routeAround} 的护栏「不含把这个棋子当目标的」是同一个口径的两面：
     * 被拉住的怪是<b>主动来打棋子的</b>，不给它侧步（详见 设计说明）。</p>
     */
    private static void lureIntoRange(ServerLevel level, PixelUnit unit, BranchDef branch) {
        for (LivingEntity candidate : lureCandidatesIn(level, unit, branch)) {
            // 候选已经被过滤成「活着、未被移除的 PathfinderMob」（见 lureCandidatesIn），
            // 这里只负责下目标 —— 判据（含中立生物的愤怒）一律走 lockAttackTarget。
            lockAttackTarget((PathfinderMob) candidate, unit);
        }
    }

    /**
     * 「拉怪范围」里的候选：<b>复用索敌那一份</b> {@link #candidatesInRange}，
     * 只保留能被下目标的 {@link PathfinderMob}（活着、未被移除）。
     *
     * <p>唯一实现：{@link #lureIntoRange} 与只读口子 {@link #debugLureCandidates}
     * （自验 G 组与 {@code /guardianprotocol block} 共用）都只调它 —— 两处各写一份过滤，
     * 迟早会出现「诊断说在范围内、实际没拉」这种说不清的状态。</p>
     */
    private static List<LivingEntity> lureCandidatesIn(ServerLevel level, PixelUnit unit, BranchDef branch) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : candidatesInRange(level, unit, branch)) {
            // ★ isRemoved/isDeadOrDying 必须在这里再判一次：被挡名单里的怪是直接塞进
            //   candidatesInRange 的，而拉怪跑在 updateBlocking **之前** —— 那时名单里
            //   可能还留着上一 tick 刚被打死的怪（死亡动画期 isAlive() 仍为 true，见踩坑记录）。
            if (e instanceof PathfinderMob mob && !mob.isRemoved() && !mob.isDeadOrDying()) {
                out.add(mob);
            }
        }
        return out;
    }

    /**
     * <b>只读</b>：这颗棋子此刻的「拉怪范围」里有哪些 {@link PathfinderMob}。
     *
     * <p>自验 G 组与只读诊断 {@code /guardianprotocol block} 都调这里 ——
     * <b>诊断里不许重算距离/范围</b>，否则「报告说在范围内、拉怪说不在」会变成两套口径各说各话
     * （本项目纪律：一条判据只留一处实现，见 {@link #meleeAttackReachSqr} 的同款理由）。</p>
     *
     * <p>只读：不设 target、不动导航、不碰坐标、不播粒子。返回的是实体的直接引用。</p>
     */
    public static List<LivingEntity> debugLureCandidates(@Nullable ServerLevel level, @Nullable PixelUnit unit) {
        if (level == null || unit == null) {
            return List.of();
        }
        return lureCandidatesIn(level, unit, unit.getBranch());
    }

    /**
     * 让棋子按住正前方的敌人。
     *
     * @return 本 tick 被这个棋子按住的敌人集合
     */
    /**
     * 让棋子按住正前方的敌人。
     *
     * <h3>★ 为什么要排除 isDeadOrDying()（这是「击杀时被拖拽」的真正原因）</h3>
     * <p>原版生物死亡后有约 20 tick 的死亡动画，这期间 {@code isAlive()} <b>仍然是 true</b>
     * （只有在动画结束时才会变为 false / 被移除）。</p>
     *
     * <p>如果只用 {@code isAlive()} 过滤，那么：</p>
     * <ol>
     *     <li>被击杀的敌人依然留在「被按住」集合里；</li>
     *     <li>它被击杀时受到的击退/死亡位移会把它推离锚点；</li>
     *     <li>下一 tick 的 {@code setPos(anchor)} 又把它<b>拽回棋子正前方</b>；</li>
     *     <li>如此重复 20 tick —— 表现就是「怪物死的时候被拖回去」。</li>
     * </ol>
     * <p>所以凡是有位移纠正的地方，都必须用 {@code isDeadOrDying()} 而不是 {@code isAlive()}。</p>
     *
     * @return 本 tick 被这个棋子按住的敌人集合
     */
    private static Set<PathfinderMob> updateBlocking(ServerLevel level, PixelUnit unit) {
        BranchDef branch = unit.getBranch();
        // ★ 容量 = **常态阻挡**（设计口径），不是表里「阻挡数」那一列 ——
        //   表里那列是技能期/显示值：解放者表里 3，而 PRTS 特性原文写的是「通常不攻击且阻挡数为0」。
        //   ★ 这与 PixelUnit#canBeCollidedWith() 读的是**同一个量**（判定唯一一处：
        //   生成链 的 normal_block → UnitBranch#normalBlockCount()），
        //   两处必须一致 —— 否则会出现「不挡路（没有碰撞箱）却还去把怪拽到锚点」的自相矛盾。
        int capacity = branch.normalBlockCount();
        Set<PathfinderMob> held = BLOCKED.computeIfAbsent(unit, k -> new LinkedHashSet<>());

        // ① 先清掉已死/正在死/已移除/换维度/被挤得太远的。
        //    注意用 isDeadOrDying()：死亡动画期间 isAlive() 仍为 true（踩坑记录）。
        for (Iterator<PathfinderMob> it = held.iterator(); it.hasNext(); ) {
            PathfinderMob mob = it.next();
            if (isGone(level, unit, mob)) {
                release(level, unit, mob);
                it.remove();
            }
        }

        if (capacity <= 0) {
            // **常态阻挡**为 0（例如伏击客 / 解放者）：它不是「墙」，只负责把之前挡住的放掉
            // —— 与 PixelUnit#canBeCollidedWith() 的「没有碰撞箱」是同一个口径的两个面。
            return held;
        }

        // ② 候选：按分支的**阻挡搜索范围**（默认脚下那一格 / 守望者九宫格）找，按距离排序
        List<PathfinderMob> candidates = blockCandidates(level, unit, branch);
        candidates.sort(Comparator.comparingDouble(unit::distanceToSqr));

        // ③ 容量判定：**已经在挡的优先保留**，再按距离补足到 N
        //
        //  ★ 为什么「保留」不能靠「它还在候选集里」：现在敌人是**自己走到**锚点的，
        //    走到之后往往已经不在「棋子脚下那一格」了（锚点在正前方 1.3 格）。
        //    若每 tick 都按位置重新选人，它会被放行 → 走出候选 → 又被抓回来，
        //    表现就是原地抖动、名额乱跳。所以保留是**粘滞**的（只受上面 isGone 的约束）。
        List<PathfinderMob> keep = new ArrayList<>(held);
        for (PathfinderMob mob : candidates) {
            if (keep.size() >= capacity) {
                break;
            }
            if (!keep.contains(mob)) {
                keep.add(mob);
            }
        }

        // ④ 释放超出名额的（含被挤掉的）：交还原版 AI
        for (PathfinderMob mob : held) {
            if (!keep.contains(mob)) {
                release(level, unit, mob);
            }
        }
        held.clear();
        held.addAll(keep);

        // ⑤ 被挡住的：下发锚点导航 + 锁攻击目标（**从头到尾不碰坐标**）
        int slot = 0;
        int total = held.size();
        for (PathfinderMob mob : held) {
            if (mob.isDeadOrDying() || mob.isRemoved()) {
                continue;       // 双保险：这一 tick 里刚被打死就跳过（下一 tick 会被 isGone 清掉）
            }
            driveToAnchor(level, unit, mob, slot++, total);
        }
        return held;
    }

    /** 这个被挡住的敌人是不是「已经不该继续占名额了」。 */
    private static boolean isGone(ServerLevel level, PixelUnit unit, PathfinderMob mob) {
        return mob.isRemoved()
                || mob.isDeadOrDying()
                || mob.level() != level
                || mob.distanceToSqr(unit) > BLOCK_KEEP_RADIUS * BLOCK_KEEP_RADIUS;
    }

    /**
     * 按分支的<b>阻挡搜索范围</b>收集候选敌人。
     *
     * <ul>
     *     <li>{@link UnitBranch.BlockSearch#FOOT}（默认）：只有棋子<b>脚下那一格</b>；</li>
     *     <li>{@link UnitBranch.BlockSearch#SURROUNDING}：棋子所在格 + 周围一圈，共九格
     *         （目前只有医疗·守望者）。</li>
     * </ul>
     *
     * <p>筛选规则（<b>唯一实现</b>在 {@link BlockCandidates}）：排除玩家/棋子自己/已驯服/有主人的，
     * 其余必须命中配置白名单 —— 默认白名单是空的，也就是<b>默认谁都不挡</b>。
     * 再叠一条「与棋子同一地面高度」：阻挡只管地面上的敌人，飞在天上的不该被算作被挡住。</p>
     *
     * <p>只收 {@link PathfinderMob}：不是这个类型的（史莱姆/岩浆怪/幻翼）根本不吃寻路，
     * 给它们下发导航是空操作（设计说明的实测结论）。</p>
     */
    private static List<PathfinderMob> blockCandidates(ServerLevel level, PixelUnit unit, BranchDef branch) {
        AABB search = switch (branch.blockSearch()) {
            case FOOT -> new AABB(unit.blockPosition());
            case SURROUNDING -> new AABB(unit.blockPosition()).inflate(1.0D);
        };
        return new ArrayList<>(level.getEntitiesOfClass(
                PathfinderMob.class, search,
                mob -> !mob.isRemoved() && !mob.isDeadOrDying()
                        && BlockCandidates.isBlockable(mob)
                        && isOnSameGroundLevel(unit, mob)));
    }

    /**
     * 把一个被挡住的敌人<b>驱动</b>到锚点，并把它的攻击目标锁成棋子。
     *
     * <p>两件事都不碰坐标：</p>
     * <ol>
     *     <li>{@link #lockAttackTarget}：让它优先打棋子；</li>
     *     <li>{@code getNavigation().moveTo(...)}：让它自己走过去；走到
     *         {@link BlockGeometry#ANCHOR_ARRIVE_DISTANCE} 之内就 {@code stop()}，
     *         免得它继续顶着棋子往锚点里挤 —— 棋子现在是<b>实心</b>的
     *         （见 {@link PixelUnit#canBeCollidedWith()}），顶上去只会把碰撞箱压在棋子身上，
     *         看着像贴脸抖。</li>
     * </ol>
     *
     * <p>⚠️ <b>「到了就停，它的攻击 goal 自然会负责」这半句不成立</b>（2026-10 实读反编译源，
     * 见 踩坑记录②）：{@code stop()} 让 {@code nav.isDone()} 恒真，而
     * {@code MeleeAttackGoal#canContinueToUse()} 在 {@code followingTargetEvenIfNotSeen=false}
     * 那一支就是 {@code !nav.isDone()}（僵尸的 {@code ZombieAttackGoal(this, 1.0, false)} 正是这一支）
     * ⇒ <b>攻击 goal 每 tick 被掐掉</b>，只在它自己的 {@code canUse()} 20 tick 门放行时才有机会出手。
     * 这是「怪被挡住却不还手」的候选根因之一 —— 实机请用只读诊断
     * {@code /guardianprotocol block} 看现场（它会把 nav.isDone / target / 够不够得着都打出来）。</p>
     *
     * <p><b>为什么每 tick 都下发</b>：生物自己的攻击 goal（{@code MeleeAttackGoal} 之类）
     * 也会调寻路走向攻击目标 —— 两条指令抢同一个寻路器。我们的调用在服务端 tick 的
     * <b>END 阶段</b>（见 {@code ModEvents}），也就是生物自己的 AI 跑完之后，所以稳定生效；
     * 一旦某一 tick 不下发，敌人就会被它自己的 AI 带去撞棋子身上。</p>
     */
    private static void driveToAnchor(ServerLevel level, PixelUnit unit, PathfinderMob mob,
                                      int slot, int total) {
        lockAttackTarget(mob, unit);
        // 进「被挡住」队伍：**队内成员之间不再互相推挤**（挤在一起不会把彼此推得东倒西歪），
        // 对队外实体的推挤行为不变。
        // ★ 注意 1.20.1 的 CollisionRule#PUSH_OWN_TEAM 实际语义与枚举名**相反**：
        //   实测（EntitySelector#pushableBy + MC-87984，1.20.1 在影响版本里）
        //   同队成员之间**不推**、对队外**照常推**。设计要的「避免叠成一团」正是这条；
        //   若哪天要「队外也推不动它们」，把 BlockTeams.BLOCKED_RULE 换成 NEVER 即可（逻辑不动）。
        BlockTeams.tag(level, mob);

        Vec3 anchor = anchorStrategy.anchor(level, unit, mob, slot, total);
        if (anchor == null) {
            // 本 tick 这个敌人没有合适的锚点 → 不下发导航，交还原版 AI
            // （占位实现从不返回 null；这是给以后的「可达性检查」策略留的口子）
            return;
        }
        double arrive = BlockGeometry.ANCHOR_ARRIVE_DISTANCE;
        if (mob.position().distanceToSqr(anchor) <= arrive * arrive) {
            mob.getNavigation().stop();
            return;
        }
        mob.getNavigation().moveTo(anchor.x, anchor.y, anchor.z, BLOCK_NAV_SPEED);
    }

    /**
     * 把敌人的攻击目标锁成棋子。
     *
     * <p>普通敌对生物设 {@code setTarget} 就够了；<b>中立生物光设它不会打</b>：
     * 僵尸猪灵/蜘蛛这类走的是 {@link NeutralMob} 的「愤怒」机制，攻击 goal 看的是
     * 愤怒目标 + 剩余愤怒时间（0 就等于不生气）。所以这两项要一起写。</p>
     *
     * <p>已知的竞争：原版的 {@code NearestAttackableTargetGoal} 等 goal 每 tick 也可能改
     * {@code target}（第 8.3 记过这件事）。我们是**每 tick 重设**，而且排在生物 AI
     * 之后，所以目标绝大多数时间都是棋子 —— 代价是它对玩家「视而不见」，
     * 这正是设计要的「优先打棋子而不是追玩家」。</p>
     */
    private static void lockAttackTarget(PathfinderMob mob, PixelUnit unit) {
        if (mob.getTarget() != unit) {
            mob.setTarget(unit);
        }
        if (mob instanceof NeutralMob neutral) {
            if (!unit.getUUID().equals(neutral.getPersistentAngerTarget())) {
                neutral.setPersistentAngerTarget(unit.getUUID());
            }
            neutral.setRemainingPersistentAngerTime(NEUTRAL_ANGER_TICKS);
        }
    }

    /**
     * 放行：把「我们改过的」东西全部还回去 —— 停下我们下发的寻路、清掉指向棋子的攻击目标
     * （含中立生物的愤怒）、退出「被挡住」队伍。
     *
     * <p>只清<b>指向棋子</b>的那一份：如果这只生物本来就在追玩家（愤怒目标是玩家），
     * 那不是我们动的，别替它清掉。</p>
     */
    private static void release(@Nullable ServerLevel level, @Nullable PixelUnit unit, PathfinderMob mob) {
        mob.getNavigation().stop();
        if (mob.getTarget() instanceof PixelUnit) {
            mob.setTarget(null);
        }
        if (mob instanceof NeutralMob neutral && unit != null
                && unit.getUUID().equals(neutral.getPersistentAngerTarget())) {
            neutral.stopBeingAngry();
        }
        if (level != null) {
            BlockTeams.untag(level, mob);
        }
    }

    /**
     * 判定敌人是否与棋子在<b>同一地面高度</b>。
     *
     * <p>用途：<b>阻挡候选</b>只收地面的敌人。飞在天上的敌人被算作「被挡住」没有意义 ——
     * 它不会为了一个地面锚点降落（给它的导航要么失败、要么让它绕圈）。</p>
     *
     * <p>容差 1.0 格：容纳台阶与半砖，排除明显高一层或悬空的。判据用「脚底高度差」
     * 而<b>不是</b> {@code onGround()}：正在跳跃、或刚被击退的敌人 {@code onGround()}
     * 会瞬间为 false，用它会漏挡并让阻挡名额反复抖动。</p>
     *
     * <p>注意这与<b>攻击</b>的垂直判定不是一回事：攻击走 {@link VerticalRange}
     * （远程上下各 4 格、对地只上 1 下 2），两者各有各的口径。</p>
     */
    private static boolean isOnSameGroundLevel(PixelUnit unit, PathfinderMob mob) {
        return Math.abs(mob.getY() - unit.getY()) <= BLOCK_SAME_LEVEL_TOLERANCE;
    }

    /** 阻挡候选的「同一层」容差（格）；见 {@link #isOnSameGroundLevel}。 */
    private static final double BLOCK_SAME_LEVEL_TOLERANCE = 1.0D;

    // ------------------------------------------------------------------
    // 绕行：棋子是实心的，而原版寻路看不见实体
    // ------------------------------------------------------------------

    /** 同一只敌人两次「侧步」之间的最短间隔（tick）。 */
    private static final int DETOUR_COOLDOWN_TICKS = 30;

    /** 判定「贴住棋子」的额外容差（格）：碰撞箱相距小于它就算贴上。 */
    private static final double DETOUR_TOUCH_GAP = 0.12D;

    /** 侧步路点相对棋子中心的横向余量（格），还要再加上双方碰撞箱的半宽。 */
    private static final double DETOUR_LATERAL_MARGIN = 0.45D;

    /** 侧步路点朝「敌人想去的那一侧」多给的一点前量（格），免得它只是横着挪一下。 */
    private static final double DETOUR_FORWARD = 0.6D;

    /** 每只敌人下次可以再被侧步的游戏刻（key = 实体 UUID）。 */
    private static final Map<UUID, Long> DETOUR_UNTIL = new HashMap<>();

    /** 累计下发过多少次侧步（自验/排查用：它是「实心 + 绕行」这套真的在干活的唯一证据）。 */
    private static int detourCount;

    /**
     * 自己的 tick 计数：{@link #onServerTick} 每被调一次加一。
     *
     * <p><b>为什么不直接用 {@code level.getGameTime()}</b>：无头自验台<b>不推进游戏刻</b>
     * （只 {@code mob.tick()} + 手动调本方法），{@code getGameTime()} 在整段自验里是冻结的 ——
     * 用它当冷却计时会让「同一只敌人 30 tick 内只补一次侧步」在自验台上变成
     * <b>整个场景只补一次</b>，于是测试会因为测试台的时间不走而失败（假失败）。
     * 计数器的语义正是「本方法被调用了几次」，两条路径（真实 tick / 自验台）下都成立。</p>
     */
    private static long tickCounter;

    /**
     * 帮「<b>没被这个棋子挡住</b>」的敌人绕过它。
     *
     * <h3>为什么非要有这一步</h3>
     * <p>{@link PixelUnit#canBeCollidedWith()} 让棋子成为<b>硬障碍</b>（没有那一步，
     * 阻挡在物理上根本不存在：敌人直接从棋子身上穿过去）。但原版寻路<b>只认方块</b> ——
     * {@code PathNavigationRegion#getEntityCollisions}（1.20.1 第 89-91 行）
     * 直接 {@code return List.of();}。于是敌人<b>会</b>被棋子挡住，却<b>不会</b>绕开它，
     * 只会顶着它原地踏步。</p>
     *
     * <p>被我们按住的那 N 个本来就该顶在锚点上（那是「挡住」），
     * 但名额之外的第 N+1 个必须过得去（设计口径：「挡最近 N 个，第 N+1 个自然过去」）。
     * 这里就是给它们补一条侧步路点。</p>
     *
     * <h3>为什么是「侧步」而不是「推开」</h3>
     * <p>推 = 改坐标 / 改速度，那正是上一版被设计否掉的「每 tick 拽敌人」；而这里只给它一个
     * <b>可达的目标点</b>，怎么走过去仍旧是它自己的寻路。所以「第 N+1 个」是
     * <b>自己走过去</b>的，不是被搬过去的 —— 与口径的字面意思一致。</p>
     *
     * <h3>三个只会多不会少的护栏</h3>
     * <ul>
     *     <li><b>不含正被挡住的</b>（本棋子 + 别的棋子都算）：它们本来就该顶在那儿，
     *         给它们侧步等于把「挡住」自己拆掉；</li>
     *     <li><b>不含把这个棋子当目标的</b>：那只生物是主动来打棋子的（原版 AI 自己选的），
     *         把它支开等于替玩家躲怪；</li>
     *     <li><b>同一次贴住只补一次</b>（{@link #DETOUR_COOLDOWN_TICKS}）：不然它还没走开时
     *         每 tick 都被重下一遍指令，等于把它<b>钉在侧步点上</b>，
     *         看上去反而不是「它自己绕过去的」。</li>
     * </ul>
     *
     * <p>侧步点取「它自己那一侧」（离得近的一侧），少绕半圈；高度取棋子脚底，
     * 所以水平绕行不会被写成爬坡（上坡寻路失败会变成「卡住」，那比不绕更难查）。</p>
     */
    private static void routeAround(ServerLevel level, PixelUnit unit, Set<PathfinderMob> held) {
        long now = tickCounter;
        AABB touch = unit.getBoundingBox().inflate(DETOUR_TOUCH_GAP);
        List<PathfinderMob> pressed = level.getEntitiesOfClass(PathfinderMob.class, touch,
                mob -> !mob.isRemoved() && !mob.isDeadOrDying()
                        && !held.contains(mob)
                        && !isHeldByAnyPawn(mob)
                        && mob.getTarget() != unit);
        for (PathfinderMob mob : pressed) {
            Long until = DETOUR_UNTIL.get(mob.getUUID());
            if (until != null && now < until) {
                continue;
            }
            Vec3 point = detourPoint(unit, mob);
            if (point == null) {
                continue;
            }
            mob.getNavigation().moveTo(point.x, point.y, point.z, BLOCK_NAV_SPEED);
            DETOUR_UNTIL.put(mob.getUUID(), now + DETOUR_COOLDOWN_TICKS);
            detourCount++;
        }
        // 冷却过期的条目顺手清掉：静态表不许无界增长（同 prune() 的理由）
        DETOUR_UNTIL.values().removeIf(v -> v <= now);
    }

    /** 这只敌人现在被任何一个棋子挡着吗（绕行必须让开它们）。 */
    private static boolean isHeldByAnyPawn(PathfinderMob mob) {
        for (Set<PathfinderMob> held : BLOCKED.values()) {
            if (held.contains(mob)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 算一个「绕过这个棋子」的侧步点；算不出来（敌人就在棋子正中心、没有方向可言）返回 null。
     *
     * <p>方向取「棋子 → 敌人想去的那个地方」，横向取垂直于它的方向、并挑敌人自己所在的那一侧。</p>
     */
    @Nullable
    private static Vec3 detourPoint(PixelUnit unit, PathfinderMob mob) {
        Vec3 from = unit.position();
        Vec3 goal = goalOf(unit, mob);
        Vec3 d = goal == null ? mob.position().subtract(from) : goal.subtract(from);
        d = new Vec3(d.x, 0.0D, d.z);
        if (d.lengthSqr() < 1.0E-4D) {
            return null;
        }
        d = d.normalize();
        Vec3 perp = new Vec3(-d.z, 0.0D, d.x);
        if (mob.position().subtract(from).dot(perp) < 0.0D) {
            perp = perp.scale(-1.0D);
        }
        double lateral = (unit.getBbWidth() + mob.getBbWidth()) * 0.5D + DETOUR_LATERAL_MARGIN;
        return from.add(perp.scale(lateral)).add(d.scale(DETOUR_FORWARD));
    }

    /**
     * 这只敌人「想去哪儿」的近似：优先取它当前攻击目标的位置，其次是它此刻的移动方向。
     *
     * <p>两者都拿不到（没有目标、且正被挡住所以速度为 0 —— 完全静止的「贴住」就是这个状态）
     * 时返回 null，由 {@link #detourPoint} 退回「从棋子指向它自己」的方向：
     * 那等于让它先<b>离开</b>棋子，是没有任何信息时最不会出错的一步。</p>
     */
    @Nullable
    private static Vec3 goalOf(PixelUnit unit, PathfinderMob mob) {
        LivingEntity target = mob.getTarget();
        if (target != null && target != unit) {
            return target.position();
        }
        Vec3 motion = mob.getDeltaMovement();
        return motion.horizontalDistanceSqr() > 1.0E-4D ? mob.position().add(motion) : null;
    }

    /** 累计下发过多少次绕行侧步（自验用；也是「这套真的在干活」的可观测证据）。 */
    public static int debugDetourCount() {
        return detourCount;
    }

    /**
     * 自验/排查用：这只敌人与棋子算不算「同一地面高度」。
     *
     * <p>判据的唯一实现是 {@link #isOnSameGroundLevel}；这里只是把它开一个只读口子，
     * 免得自验台自己再写一份容差（两份判据迟早会不一致，而且错的那份没人会去查）。</p>
     *
     * <p>形参收 {@link LivingEntity} 而不是 {@code PathfinderMob}：调用方（自验台）手里
     * 常常是 {@code Mob} 类型的返回值，而**不是**寻路生物的实体在阻挡这里根本不会成为候选**
     * （{@link #blockCandidates} 只收 {@code PathfinderMob}），所以那种情况直接算「不同层」——
     * 语义与「它不可能被挡住」一致，也省得每个调用点各写一次强制转换。</p>
     */
    public static boolean debugSameGroundLevel(PixelUnit unit, @Nullable LivingEntity mob) {
        return mob instanceof PathfinderMob pathfinder && isOnSameGroundLevel(unit, pathfinder);
    }

    /** 把绕行计数归零（自验每个场景重开时用，免得读到上一个场景的累计值）。 */
    public static void resetDetourCount() {
        detourCount = 0;
    }

    // ------------------------------------------------------------------
    // 索敌
    // ------------------------------------------------------------------

    /**
     * 这个棋子能不能打到这个目标 —— <b>只看垂直关系</b>，口径来自 {@link VerticalRange}。
     *
     * <h3>★ 2026-10 重做：从「碰撞盒的隐含限制」改成「显式区间」</h3>
     * <p>攻击范围是二维的（相对格子的 x/z），格子判定用的是<b>一个整格高</b>的 AABB ——
     * 它<b>隐含地</b>把「高出/低于一格以上」的目标全挡掉了。于是模板里写的
     * 「远程单位没有垂直盲区」实际从未生效：远程棋子只能打到上下各一格以内，
     * 打不到站在 3 格高台上的敌人。</p>
     *
     * <p>现在改成两条显式规则（数字全是 {@link VerticalRange} 里的常量，试玩只调常量）：</p>
     * <ul>
     *     <li><b>远程 / 对空</b>：上下各 <b>4</b> 格，真正做到没有盲区；</li>
     *     <li><b>只打地面</b>：上方 <b>1</b> 格、下方 <b>2</b> 格 —— 打不到明显比自己高的，
     *         但能打到脚下的。</li>
     * </ul>
     * <p>配套改动：{@link #candidatesInRange} 的候选盒也按这个区间张开，
     * 否则「筛选条件放宽了、扫描盒还是 1 格高」会继续把人漏掉。</p>
     */
    private static boolean canHitVertically(PixelUnit unit, LivingEntity target) {
        return VerticalRange.covers(verticalMode(unit.getBranch()), unit.getY(), target.getY());
    }

    /** 分支的对空口径 → {@link VerticalRange} 的模式（一处映射，别处不许再判）。 */
    private static VerticalRange.Mode verticalMode(BranchDef branch) {
        return switch (branch.verticalTargeting()) {
            case RANGED, AIR_AND_GROUND -> VerticalRange.Mode.RANGED;
            case GROUND_ONLY -> VerticalRange.Mode.GROUND_ONLY;
        };
    }

    /**
     * 在攻击范围的格子里挑出本 tick 要打的敌人。
     *
     * <h3>三条轴（全部来自《…对照表》的「分支机制（干员特性原文）」一列）</h3>
     * <ol>
     *     <li><b>会不会攻击</b>（{@link UnitBranch#canAttack()}）：表里写「通常不攻击」的
     *         三个分支（解放者 / 阵法术师 / 吟游者）直接返回空。
     *         注意它们仍会**站场 + 阻挡** —— 「不攻击」不等于「不参战」；
     *         补上攻击力要靠技能系统（还没做），这点已在 {@code UnitBranch.canAttack()} 注明。</li>
     *     <li><b>一次打几个</b>（{@link UnitBranch#targetCount()}）：
     *         <ul>
     *             <li>{@code SINGLE}：只打优先级最高的那一个；</li>
     *             <li>{@code MULTI_BLOCKED}：打**自己挡住的全部**敌人。列出全部候选后再过滤成
     *                 「正被自己挡住的」，而不是直接拿「攻击范围内所有敌人」——
     *                 表里写的是「同时攻击<b>阻挡的</b>所有敌人」，
     *                 所以漏过去的敌人不能被它顺手清掉（否则阻挡就没意义了）；</li>
     *             <li>{@code MULTI_ALL}：打范围内的全部敌人（伏击客 / 散射手）。</li>
     *         </ul>
     *     </li>
     *     <li><b>打谁</b>（{@link UnitBranch#targetPriority()}）：默认最近；
     *         神射手取防御最低、攻城手取最重、速射手空中优先、裂空炮手只打空中。
     *         判据的实现与近似见 {@link Targeting}。</li>
     * </ol>
     *
     * <h3>为什么把「候选收集」和「排序」分开</h3>
     * <p>范围格子可能有十几个，而同一个敌人可能同时落在两个格子的 AABB 里
     * （碰撞箱跨界）。用 {@link LinkedHashSet} 去重后再排序，比在格子循环里边比边选
     * 更不容易出现「同一个敌人被打两次」——多目标时那是实打实的双倍伤害。</p>
     *
     * <p><b>2026-10 变化</b>：{@code MULTI_BLOCKED} 仍走这条统一路径，但
     * <b>「墙」分支（{@link BranchDef#attacksBlockedOnly()}）的候选集合整体换成「被挡住的那批」</b>
     * —— 近战位 + 会阻挡 + 近战武器攻击的分支只打自己挡住的敌人，不再按攻击范围格子找人。
     * 其余分支（含所有投掷物分支）照旧按格子索敌。</p>
     */
    private static List<LivingEntity> pickTargets(ServerLevel level, PixelUnit unit, BranchDef branch) {
        List<LivingEntity> picked = selectTargets(level, unit, branch);
        // 记下这次选择，供调试 / 自验读取（见 LAST_TARGETS 的注释）
        LAST_TARGETS.put(unit, picked);
        return picked;
    }

    /** {@link #pickTargets} 的实现体：算完由调用方统一记录，避免两处各写一份记录逻辑。 */
    private static List<LivingEntity> selectTargets(ServerLevel level, PixelUnit unit, BranchDef branch) {
        if (!branch.canAttack()) {
            DEBUG_STEPS = "canAttack=false → 直接返回空";
            return List.of();
        }
        // ★ 医疗职业（2026-10 第三轮）：候选集合是**我方干员**，不是敌人。
        //   咒愈师（POST_HIT）不走这里 —— 它照旧打敌人，命中之后再治一个友方（见 applyHit）。
        UnitBranch.Heal heal = branch.heal();
        if (heal.kind() == UnitBranch.Heal.Kind.SINGLE
                || heal.kind() == UnitBranch.Heal.Kind.ALL
                || heal.kind() == UnitBranch.Heal.Kind.CHAIN) {
            return selectHealTargets(level, unit, branch, heal);
        }
        // 候选集合：分档取（见方法注释）
        List<LivingEntity> candidates;
        String scope;
        if (branch.attacksBlockedOnly(unit.effectiveAttackMethod())) {
            boolean airOnly = Targeting.requiresAir(branch.targetPriority());
            List<LivingEntity> blocked = new ArrayList<>(heldEnemies(unit));
            blocked.removeIf(e -> !canHitVertically(unit, e)
                    || (airOnly && !Targeting.isFlying(e)));
            candidates = blocked;
            scope = "只打被挡住的";
        } else {
            candidates = candidatesInRange(level, unit, branch);
            scope = "按攻击范围格子";
        }
        if (candidates.isEmpty()) {
            DEBUG_STEPS = "候选为空（" + scope + "）";
            return List.of();
        }
        candidates.sort(Targeting.comparator(branch.targetPriority(), unit.position(),
                // ★ N2：`BLOCKED_FIRST` 要问「这只怪是不是正被我挡住」—— 那是本类的状态，
                //   所以判据由这里传进去（其余优先级忽略这个参数）。
                e -> isHeldBy(unit, e)));
        StringBuilder dbg = new StringBuilder();
        dbg.append("候选 ").append(candidates.size()).append(" 个（").append(scope).append("，已排序）");
        for (LivingEntity e : candidates) {
            dbg.append(" [").append(e.getType().getDescription().getString())
                    .append(" 被挡住=").append(isHeldBy(unit, e))
                    .append(" 距=").append(String.format(java.util.Locale.ROOT, "%.2f",
                            Math.sqrt(e.distanceToSqr(unit))))
                    .append(']');
        }

        if (branch.targetCount() == UnitBranch.TargetCount.MULTI_ALL) {
            DEBUG_STEPS = dbg.toString();
            return List.copyOf(candidates);
        }
        if (branch.targetCount() == UnitBranch.TargetCount.MULTI_BLOCKED) {
            // 只要「挡住的」那些；名额上限 = 阻挡数（表里收割者写的是「最大生效数等于阻挡数」）
            List<LivingEntity> blocked = new ArrayList<>();
            // ★ 这里**故意**读表里的 blockCount()（技能期/显示值）：原文那句「最大生效数等于阻挡数」
            //   说的就是这个数。它与「常态阻挡」（能不能挡路 / 有没有碰撞箱）是两件事 ——
            //   常态那个量见 updateBlocking 的容量与 PixelUnit#canBeCollidedWith()。
            int cap = Math.max(0, branch.blockCount());
            for (LivingEntity e : candidates) {
                if (blocked.size() >= cap) {
                    break;
                }
                if (isHeldBy(unit, e)) {
                    blocked.add(e);
                }
            }
            dbg.append(" → MULTI_BLOCKED 取到 ").append(blocked.size())
                    .append(" 个（上限 ").append(cap).append("）");
            DEBUG_STEPS = dbg.toString();
            return List.copyOf(blocked);
        }
        DEBUG_STEPS = dbg.append(" → SINGLE 取最近那个").toString();
        return List.of(candidates.get(0));
    }

    /** 最近一次 {@link #selectTargets} 走到哪一步（排查用，只为报告服务）。 */
    private static volatile String DEBUG_STEPS = "(尚未执行)";

    // ------------------------------------------------------------------
    // 群伤（落点溅射 / 连锁）—— 2026-10 第二轮
    // ------------------------------------------------------------------

    /**
     * 停顿的减速等级（6 ⇒ 速度约 −90%，观感上就是「顿住了」）。
     *
     * <h3>★ 时长不在这里，按分支走 {@code UnitBranch#pause()}（2026-10 第四轮改）</h3>
     * <p>旧版这里有个 {@code CHAIN_PAUSE_TICKS = 10} 的常数，注释自己写着「暂定值：
     * PRTS 只写『造成短暂停顿』、没给时长」。**现在 PRTS 给了数**：</p>
     * <ul>
     *     <li><b>链术师</b> 精二特性「…每次跳跃伤害降低 15% 并造成短暂停顿(0.5s)」⇒ 0.5 秒 =
     *         <b>10 tick</b>（值恰好与旧暂定值相同，但现在是**有出处**的）；</li>
     *     <li><b>凝滞师</b> 《分支特性信息》「特性停顿时间为 <b>0.8 秒</b>」⇒ <b>16 tick</b>。</li>
     * </ul>
     * <p>所以时长挪进数据（生成物 {@code UnitBranch#pause()}，规则表在
     * 生成链的 PAUSE_OVERRIDES 覆盖表），这里只留「顿住的程度」这一个手感常数 ——
     * 与 {@code VerticalRange} 只留四个区间常量是同一个思路：**数值归数据、手感归代码**。</p>
     */
    public static final int PAUSE_AMPLIFIER = 6;

    /** 最近一次攻击的溅射命中了几个（自验/排查用）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SPLASH_HIT_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 最近一次连锁一共打中几个（含第一个；-1 = 还没发生过）。 */
    private static final java.util.concurrent.atomic.AtomicInteger CHAIN_HIT_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 读「最近一次溅射打中几个」（没发生过 = -1）。 */
    public static int debugLastSplashHits() {
        return SPLASH_HIT_COUNT.get();
    }

    /** 读「最近一次连锁打中几个」（没发生过 = -1）。 */
    public static int debugLastChainHits() {
        return CHAIN_HIT_COUNT.get();
    }

    /** 清零这两个观测值（自验每个用例前调一次，免得读到上一段的残留）。 */
    public static void debugResetAoeCounters() {
        SPLASH_HIT_COUNT.set(-1);
        CHAIN_HIT_COUNT.set(-1);
        CHAIN_LINK_COUNT.set(-1);
    }

    /** 最近一次连锁治疗一共治到几个（含第一个；-1 = 还没发生过）。 */
    private static final java.util.concurrent.atomic.AtomicInteger CHAIN_HEAL_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /**
     * 最近一次连锁画了几段「连线」（伤害链与治疗链共用这一个计数；-1 = 还没发生过）。
     *
     * <p>为什么要有它：连线是**表现**，粒子发出去就不进世界了，自验台没法像断言伤害那样断言它 ——
     * 只能让画连线的那一处**自己记一笔**，断言才不是「看日志猜」。</p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger CHAIN_LINK_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 读「最近一次连锁治疗治到几个」。 */
    public static int debugLastChainHeals() {
        return CHAIN_HEAL_COUNT.get();
    }

    /** 读「最近一次连锁画了几段连线」。 */
    public static int debugLastChainLinks() {
        return CHAIN_LINK_COUNT.get();
    }

    /** 清零连锁治疗的观测值。 */
    public static void debugResetHealCounters() {
        CHAIN_HEAL_COUNT.set(-1);
        POST_HIT_HEAL_COUNT.set(-1);
        CHAIN_LINK_COUNT.set(-1);
    }

    /** 最近一次咒愈师出手有没有顺手治到友方（1 = 治了，0 = 范围内没人需要治，-1 = 没发生过）。 */
    private static final java.util.concurrent.atomic.AtomicInteger POST_HIT_HEAL_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 读「最近一次咒愈师出手有没有顺手治疗」。 */
    public static int debugLastPostHitHeal() {
        return POST_HIT_HEAL_COUNT.get();
    }

    /** 最近一次被动回血脉冲治到几个（吟游者；-1 = 还没发生过）。 */
    private static final java.util.concurrent.atomic.AtomicInteger REGEN_HEAL_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /** 读「最近一次被动回血脉冲治到几个」。 */
    public static int debugLastRegenHeals() {
        return REGEN_HEAL_COUNT.get();
    }

    /**
     * 自验/排查用：这个棋子的「友方范围扫描」现在看得见几个友方。
     *
     * <p>为什么要暴露它：治疗与被动回血都靠 {@link #alliesInRange}，而它是「逐格建 AABB 再捞实体」——
     * 位置、垂直窗口、实体是否还在，任何一处不对都会表现为「功能只对一部分友方生效」，
     * 光看血条分不清是「没看见」还是「看见了但没治」。</p>
     */
    public static int debugAlliesInRange(PixelUnit unit) {
        if (unit == null || !(unit.level() instanceof ServerLevel level)) {
            return -1;
        }
        return alliesInRange(level, unit, unit.getBranch()).size();
    }

    /** 读取上一步的判据追踪（配合 {@link #debugTrace}）。 */
    public static String debugSteps() {
        return DEBUG_STEPS;
    }

    /**
     * 扫攻击范围格子，收集范围内的全部敌人（已去重、已过垂直判定）。
     *
     * <p>★ 用 {@link PixelUnit#worldCells()}：它已按棋子朝向把「相对格子」旋转到世界偏移。</p>
     *
     * <p>★ <b>2026-10：扫描盒按 {@link VerticalRange} 的区间张开</b>。以前这里的盒子是
     * {@code new AABB(cellPos)}（整格高 1 格），于是「垂直判定放宽到上下 4 格」这条改动
     * 会被扫描盒原样卡回去 —— 筛选条件与扫描范围必须同一套口径，否则等于没改。</p>
     */
    private static List<LivingEntity> candidatesInRange(ServerLevel level, PixelUnit unit, BranchDef branch) {
        List<AttackRange.Cell> cells = unit.worldCells();
        if (cells.isEmpty()) {
            return new ArrayList<>();
        }
        BlockPos origin = unit.blockPosition();
        boolean airOnly = Targeting.requiresAir(branch.targetPriority());
        double minY = rangeBoxMinY(unit, branch);
        double maxY = rangeBoxMaxY(unit, branch);
        // ★ 被自己挡住的敌人算「站在自身格里」，所以哪怕 0-1 这种只有自身格的范围也要收进来。
        //   先塞进候选集合，AABB 那一轮只负责把「挡不住的、路过的」敌人捞进来。
        Set<LivingEntity> found = new LinkedHashSet<>();
        for (LivingEntity held : heldEnemies(unit)) {
            if (canHitVertically(unit, held) && (!airOnly || Targeting.isFlying(held))) {
                found.add(held);
            }
        }
        for (AttackRange.Cell cell : cells) {
            BlockPos cellPos = origin.offset(cell.x(), 0, cell.z());
            AABB box = new AABB(cellPos.getX(), minY, cellPos.getZ(),
                    cellPos.getX() + 1.0D, maxY, cellPos.getZ() + 1.0D);
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> !e.isRemoved() && !e.isDeadOrDying() && Targeting.isEnemy(e)
                            && canHitVertically(unit, e)
                            // 裂空炮手「起飞后只攻击空中敌人」：地面目标直接过滤掉，
                            // 不是「排在后面」—— 否则它就退化成普通的「空中优先」了。
                            && (!airOnly || Targeting.isFlying(e)))) {
                found.add(candidate);
            }
        }
        return new ArrayList<>(found);
    }

    /** 攻击范围扫描盒的纵向下界（**只有一处判据**：索敌与治疗都用它）。 */
    private static double rangeBoxMinY(PixelUnit unit, BranchDef branch) {
        return unit.getY() - VerticalRange.down(verticalMode(branch));
    }

    /** 攻击范围扫描盒的纵向上界（+1 = 格子自己的高度）。 */
    private static double rangeBoxMaxY(PixelUnit unit, BranchDef branch) {
        return unit.getY() + VerticalRange.up(verticalMode(branch)) + 1.0D;
    }

    /**
     * 把上面这两个下界/上界算成方块格子的整数范围（治疗那边要按格子扫）。
     */
    private static int rangeBoxMinBlockY(PixelUnit unit, BranchDef branch) {
        return net.minecraft.util.Mth.floor(rangeBoxMinY(unit, branch));
    }

    private static int rangeBoxMaxBlockY(PixelUnit unit, BranchDef branch) {
        return net.minecraft.util.Mth.ceil(rangeBoxMaxY(unit, branch));
    }

    // ------------------------------------------------------------------
    // 治疗（医疗职业的出手）—— 2026-10 第三轮
    // ------------------------------------------------------------------
    //
    // 口径（全部写进 branch_meta.HEAL_OVERRIDES 的 source，逐分支带对照表原文）：
    //   · 目标 = **我方干员**（本 mod 的棋子 PixelUnit 就是干员）；
    //   · ★ **候选含自身**（2026-10 补，实测提问「医疗怎么不治疗自身」）：原作默认能治自己，
    //     只有技能/特性明确排除自身时才不能；判据与出处只在 woundedCandidates / selectHealTargets 一处；
    //   · 选谁 = **同一检测时段生命值最低的**（设计口径：按当前生命值，不是百分比）；
    //   · 治疗量 = **攻击值**（不另立「治疗力」，否则两份数值迟早漂移）；
    //   · 群愈师 = 范围内**全体**各治一次（设计口径；对照表写的是「三个」）；
    //   · 链愈师 = 从最低血量那名起跳，最多 3 个目标、每跳 ×0.75（对照表原文）；
    //     ★ 跳法按原作（2026-10 修正）：跳到**上一跳目标周围 8 格**内的另一名友方、**优先最低血量**、
    //       **满血也能跳** ⇒ 因此能治到**攻击范围之外**（出处与逐字原文见 chainHealJump 的注释）；
    //   · 疗养师 = 内圈之外 ×0.8（内圈 = 医师那一档的标准范围）；
    //   · 咒愈师 = 照旧打敌人，命中后按**实际造成的伤害** ×0.5 治一名友方。

    /**
     * 治疗分支的目标选择：**我方干员（含自身）**里「需要治疗」的那些，按当前生命值升序。
     *
     * <p>四条都是判据，写在注释里免得下次被当成 bug：</p>
     * <ul>
     *   <li><b>★ 自己也是候选</b>（2026-10 实测提问「医疗怎么不治疗自身」后补的口径）：
     *       原作里「能治疗的单位通常也能治疗自己」，只有技能/特性**明确写着排除自身**时才不能 ——
     *       出处 arknights.wiki.gg 的 {@code Attribute/HP} → Healing 逐字：
     *       「Units capable of healing can usually heal themselves, unless their healing ability
     *       explicitly states that it excludes the user themselves, such as Blemishine's Divine
     *       Avatar.」。本 mod 七个医疗分支的特性原文（PRTS：恢复友方单位生命 / 同时恢复三个友方
     *       单位的生命 / …）**都没有**排除自己；凯尔希的天赋原文更是直接写着「优先治疗**自身**和
     *       Mon3tr」。⇒ 旧实现里 {@code alliesInRange} 那句 {@code e != unit} 是一条**没有出处**的
     *       排除，把原作默认行为改掉了（见 踩坑记录）。现在自己与友方一起参与挑选。</li>
     *   <li><b>只收「不满血」的</b>：全都在满血时返回空 ⇒ 本次**不出手**（省一次冷却）。
     *       ★ 这是本 mod 的口径（原作会照样治满血目标）—— 塔防里把冷却浪费在满血单位上更糟；</li>
     *   <li><b>按当前生命值升序</b>，相同血量比距离（保证确定性，别让「谁先谁后」看运气）；
     *       自己的距离恒为 0 ⇒ 同血量时自己优先 —— 与凯尔希天赋「优先治疗自身」同向；</li>
     *   <li><b>群愈师返回全体</b>、单体与连锁只返回最低那位（连锁的后续跳在 {@link #applyHeal} 里走）。</li>
     * </ul>
     */
    private static List<LivingEntity> selectHealTargets(ServerLevel level, PixelUnit unit,
                                                        BranchDef branch, UnitBranch.Heal heal) {
        List<LivingEntity> wounded = woundedCandidates(level, unit, branch);
        if (wounded.isEmpty()) {
            DEBUG_STEPS = "治疗：候选里没有需要治疗的（自身与友方都满血）→ 本次不出手";
            return List.of();
        }
        wounded.sort(Comparator.comparingDouble(LivingEntity::getHealth)
                .thenComparingDouble(e -> e.distanceToSqr(unit)));
        LivingEntity lowest = wounded.get(0);
        DEBUG_STEPS = String.format(java.util.Locale.ROOT,
                "治疗（%s）：候选 %d 名需要治疗（其中自身=%s），最低血量 %s/%s",
                heal.kind(), wounded.size(), wounded.contains(unit) ? "是" : "否",
                fmt1(lowest.getHealth()), fmt1(lowest.getMaxHealth()));
        if (heal.kind() == UnitBranch.Heal.Kind.ALL) {
            return List.copyOf(wounded);        // 群愈师：范围内全体（设计口径）
        }
        return List.of(lowest);                 // 单体 / 连锁的第一跳
    }

    /**
     * 「该治谁」的候选集合（**唯一一处**）：自己（不满血时）+ 范围内同队棋子（不满血时）。
     *
     * <p>★ 抽出来的理由同本项目一贯规矩：治疗的出口不止一个 —— 单体 / 群愈 / 链愈走
     * {@link #selectHealTargets}，咒愈师走 {@link #lowestWoundedAlly}。「自己算不算候选」
     * 这条口径要是各写一份，迟早出现「医师能治自己、咒愈师不能」这种说不清的状态。</p>
     *
     * <p>只判「不满血」：满血的自己不进候选（否则每 tick 都会治满血目标，白烧冷却）。</p>
     */
    private static List<LivingEntity> woundedCandidates(ServerLevel level, PixelUnit unit, BranchDef branch) {
        List<LivingEntity> out = new ArrayList<>();
        // ★ 自己也过「禁疗」这一关：虽然目前只有医疗分支会走治疗路径（它们都可被治疗），
        //   但把判据写全，将来「某分支自己给自己回血」时才不会绕过它。
        if (unit.getBranch().healable() && unit.getHealth() < unit.getMaxHealth() - 0.01F) {
            out.add(unit);                      // ★ 自己（口径与出处见 selectHealTargets 的注释）
        }
        for (LivingEntity ally : alliesInRange(level, unit, branch)) {
            if (ally.getHealth() < ally.getMaxHealth() - 0.01F) {
                out.add(ally);
            }
        }
        return out;
    }

    /**
     * 攻击范围内的**自己人**（棋子）。
     *
     * <p>扫描盒与索敌**同一套**（{@link #rangeBoxMinY}/{@link #rangeBoxMaxY}）：
     * 「能不能打到」和「能不能治到」必须是同一个范围，否则会出现
     * 「医疗治得到一个自己根本够不着的目标」这种没法解释的现象。</p>
     *
     * <h3>★ 为什么改成「一个大盒 + 格子成员判定」</h3>
     * <p>原来是对每个格子各建一个 AABB 逐格捞实体。实机上它是**漏人**的：自验里两只友方
     * 分别站在范围格的 (1,0) 与 (0,-1) 上，第二次扫描只看见一只 —— 于是吟游者的被动
     * 只给一只回血，另一只永远是 5 血（现象是「功能只对一部分友方生效」）。
     * 现在改成：先按所有格子算出一个**大包围盒**捞一次，再用「它的方块坐标是不是正好落在
     * 某个范围格里」精确判定。判定口径与本工程其它地方一致（攻击范围是**格子**，不是圆），
     * 而且「看见了谁」不再依赖坐标轴的浮点边界。</p>
     *
     * <p><b>只收自己人</b>：见 {@link #isFriendly}（设计口径：只治疗同队，别把怪也治了）。</p>
     */
    private static List<LivingEntity> alliesInRange(ServerLevel level, PixelUnit unit, BranchDef branch) {
        List<AttackRange.Cell> cells = unit.worldCells();
        if (cells.isEmpty()) {
            return List.of();
        }
        BlockPos origin = unit.blockPosition();
        int minDx = Integer.MAX_VALUE;
        int maxDx = Integer.MIN_VALUE;
        int minDz = Integer.MAX_VALUE;
        int maxDz = Integer.MIN_VALUE;
        for (AttackRange.Cell c : cells) {
            minDx = Math.min(minDx, c.x());
            maxDx = Math.max(maxDx, c.x());
            minDz = Math.min(minDz, c.z());
            maxDz = Math.max(maxDz, c.z());
        }
        AABB box = new AABB(
                origin.getX() + minDx, rangeBoxMinY(unit, branch), origin.getZ() + minDz,
                origin.getX() + maxDx + 1.0D, rangeBoxMaxY(unit, branch), origin.getZ() + maxDz + 1.0D);
        List<LivingEntity> out = new ArrayList<>();
        for (PixelUnit ally : level.getEntitiesOfClass(PixelUnit.class, box,
                e -> e != unit && !e.isRemoved() && !e.isDeadOrDying() && isFriendly(unit, e))) {
            BlockPos at = ally.blockPosition();
            if (insideCells(cells, at.getX() - origin.getX(), at.getZ() - origin.getZ())) {
                out.add(ally);
            }
        }
        return out;
    }

    /** 相对格子 (dx,dz) 是否在范围里（范围是**格子集合**，不是圆）。 */
    private static boolean insideCells(List<AttackRange.Cell> cells, int dx, int dz) {
        for (AttackRange.Cell c : cells) {
            if (c.x() == dx && c.z() == dz) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「自己人」判据 —— 治疗只对**同队**生效（设计口径：只治疗同队，别把怪也治了）。
     *
     * <ol>
     *   <li><b>只治棋子</b>：不是 {@link PixelUnit} 的一律不治 —— 这一条就把「怪」挡在门外，
     *       它不依赖队伍配置（哪怕有人把僵尸拉进玩家队也不会被治）；</li>
     *   <li><b>★ 目标不能是「禁疗」分支</b>（2026-10 第四轮补）：PRTS 精二特性原文写着
     *       武者「不成为其他角色的治疗目标」、收割者/不屈者「无法被友方角色治疗」——
     *       在此之前本工程**没有这个标志**，医疗会把它们当普通友军治（审计报告 N1）。
     *       判据落在 **{@code target} 的分支**上（不是治疗者），见
     *       {@code UnitBranch#healable()}。</li>
     *   <li><b>同队才算</b>：队伍 = 「谁摆的棋子」（见 {@link PawnTeams}，设计口径：
     *       玩家 A 摆的棋子属于 A 队、B 摆的属于 B 队）。A 的医疗<b>不会</b>去治 B 的棋子 ——
     *       它们只是**同一阵营**（互相不攻击），不是同一队；</li>
     *   <li><b>都没队伍算同队</b>：命令/数据包生成的「无主」棋子之间可以互相治疗
     *       （有主的与无主的互不治疗，因为队名不同）。</li>
     * </ol>
     *
     * <p><b>为什么闸门放在这里</b>：挑目标的 {@code alliesInRange}、连锁的
     * {@code chainHealJump}、结算的 {@code applyHeal} 全都过这一个方法 —— 禁疗只要写一次，
     * 三条路径就都受约束（「同一条规则只写一处」是本项目的老规矩，见 踩坑记录）。</p>
     */
    private static boolean isFriendly(PixelUnit healer, LivingEntity target) {
        if (!(target instanceof PixelUnit ally)) {
            return false;
        }
        if (!ally.getBranch().healable()) {
            return false;       // ★ 禁疗（武者 / 收割者 / 不屈者）
        }
        return PawnTeams.isSameTeam(healer, ally);
    }

    /**
     * 这一次治疗对**这个目标**的数值：攻击值 × 追加系数 ×（疗养师的内圈外折扣）。
     *
     * @param extra 连锁跳数的递减等额外系数（单体传 1.0）
     */
    private static float healAmount(PixelUnit unit, BranchDef branch, LivingEntity target, double extra) {
        double amount = branch.attackDamage() * extra;
        UnitBranch.Heal heal = branch.heal();
        if (heal.hasInnerRange() && !insideInnerRange(unit, target, heal.innerRangeKey())) {
            amount *= heal.farFactor();
        }
        return (float) amount;
    }

    /**
     * 目标在不在「内圈」里（疗养师的 80% 判据）。
     *
     * <p>★ 内圈**不是某个凭空的格数**：EN 官方 wiki 的 Medic/Therapist 原文写的是
     * 「reduced to 80% on those outside a range of 3×2 with a 1×1 extension up front」——
     * 也就是**医师那一档的标准范围**。所以范围键由生成链取「医师」的实际值传进来，
     * 范围文档改了它会跟着走（不写死）。</p>
     */
    private static boolean insideInnerRange(PixelUnit unit, LivingEntity target, String key) {
        List<AttackRange.Cell> local = AttackRange.resolve(key);
        if (local.isEmpty()) {
            return true;        // 取不到内圈形状时按「不算远」处理：宁可治满，也不要凭空打折
        }
        BlockPos origin = unit.blockPosition();
        BlockPos at = target.blockPosition();
        for (AttackRange.Cell c : local) {
            int[] off = unit.getFacing().toWorldOffset(c.x(), c.z());
            if (origin.getX() + off[0] == at.getX() && origin.getZ() + off[1] == at.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结算一次治疗 —— <b>治疗弹与近战治疗共用的唯一入口</b>。
     *
     * <p>链愈师的多跳在这里继续：从刚治过的目标出发，在它**周围 8 格**里找**血量最低的、
     * 还没治过的**友方（**满血也算**），每跳治疗量 ×{@code chainDecay}，直到打满 {@code targets} 个
     * （含第一个）或找不到下一个为止 —— 规则与出处见 {@link #chainHealJump}。</p>
     *
     * @param amount 第一个目标的治疗量（调用方已按内圈/外圈算好）
     * @return 实际治到的目标数（含第一个；0 = 没治成）
     */
    public static int applyHeal(ServerLevel level, PixelUnit unit, LivingEntity target, float amount) {
        if (level == null || unit == null || target == null || amount <= 0.0F) {
            return 0;
        }
        if (target.isRemoved() || target.isDeadOrDying()) {
            return 0;
        }
        // ★ 判据落在**结算点**上，而不是只落在选目标那一步：治疗弹在飞的过程中目标可能换了队，
        //   而连锁/被动回血是直接调这里的。放这里一次，三条路径全都受同一条规则约束 ——
        //   「只治同队的棋子，怪一律不治」。
        if (!isFriendly(unit, target)) {
            return 0;
        }
        healOne(level, unit, target, amount);
        int healed = 1;
        BranchDef branch = unit.getBranch();
        UnitBranch.Heal heal = branch.heal();
        if (heal.kind() != UnitBranch.Heal.Kind.CHAIN) {
            return healed;
        }
        Set<LivingEntity> done = new LinkedHashSet<>();
        done.add(target);
        // ★ 后续跳的规则**按原作**（2026-10 修正；出处 wiki.gg Medic/Chain_Medic 逐字）：
        //   「their healing "jumps" to another friendly unit **in the surrounding 8 tiles of the
        //     target**, prioritizing those with **the lowest HP** ... and **can jump to those with
        //     full HP**」 ⇒ ① 只认「上一跳目标周围 8 格」（含斜角；也**因此能治到攻击范围之外**，
        //   原作 Strategies 段明说 "can heal operators outside their usual range"）；
        //   ② 优先**血量最低**（并列按距离，确定性 tie-break）；③ **满血目标也能跳**。
        //   旧实现是「在整片攻击范围里按**距离**找、且只跳不满血的」—— 三条都对不上，已删。
        // ★ 不让连锁弹回治疗者本人：把它先塞进 done（原作写「在 3 个友方单位间跳跃」，
        //   弹回自己观感像卡住）—— 这是本 mod 的读法。
        done.add(unit);
        LivingEntity current = target;
        int targets = Math.max(2, heal.targets());
        for (int hop = 1; hop < targets; hop++) {
            LivingEntity next = chainHealJump(level, unit, current, done);
            if (next == null) {
                break;      // 周围 8 格里没有可跳的目标：如实停下，不硬凑跳数
            }
            healOne(level, unit, next, healAmount(unit, branch, next, Math.pow(heal.chainDecay(), hop)));
            // ★ 链的连线（2026-10 补）：治疗链用绿色治愈光点画线，斜角也连（见 showChainLink）
            showChainLink(level, current, next, ParticleTypes.HAPPY_VILLAGER);
            done.add(next);
            current = next;
            healed++;
        }
        CHAIN_HEAL_COUNT.set(healed);
        return healed;
    }

    /** 治疗一名友方并播表现，返回实际回复量（用于自验与报告）。 */
    private static float healOne(ServerLevel level, PixelUnit unit, LivingEntity target, float amount) {
        float before = target.getHealth();
        target.heal(amount);
        float gained = target.getHealth() - before;
        Vec3 at = impactPoint(target);
        level.sendParticles(ParticleTypes.HEART, at.x, at.y, at.z, 3, 0.25D, 0.25D, 0.25D, 0.0D);
        level.playSound(null, unit.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.NEUTRAL, 0.3F, 1.4F);
        return gained;
    }

    /**
     * 连锁治疗的**下一跳**：原作口径「跳到目标**周围 8 格**内的另一名友方，
     * 优先生命值最低的，**可以跳到满血的目标**」。
     *
     * <p>出处（逐字）：wiki.gg {@code Medic/Chain_Medic} ——
     * "their healing "jumps" to another friendly unit **in the surrounding 8 tiles of the target**,
     * prioritizing those with **the lowest HP** (or the most recently deployed if there are
     * multiple) and **can jump to those with full HP**, up to twice with the healing in each jump
     * reduced by 25% of the previous"。</p>
     *
     * <p>★ 三处因此与旧实现不同（2026-10 修正）：</p>
     * <ol>
     *   <li><b>范围</b>：只认「上一跳目标周围 8 格」（3×3 去掉自己那格）—— 旧实现是
     *       {@code alliesInRange}（**整片攻击范围**）；</li>
     *   <li><b>挑法</b>：优先**血量最低** —— 旧实现按**距离**最近（连方法名都写着 nearest）；</li>
     *   <li><b>满血也能跳</b>：原作明说可以 —— 旧实现只跳「不满血」的，因此也**做不到**原作
     *       Strategies 段写的那件事：「Chain Medics can heal operators **outside their usual
     *       range**」（跳跃只看上一跳周围的 8 格，最后几跳可以落到攻击范围之外）。</li>
     * </ol>
     *
     * <p>并列时：原作是「最近部署的那名」，本 mod 没有部署顺序 ⇒ 用**距离**当确定性 tie-break
     * （写在这里备查）。纵向口径与本文件其它地方一致：用棋子自己的范围上下界（{@link #rangeBoxMinY}
     * / {@link #rangeBoxMaxY}）。</p>
     *
     * @param done 已经治过的目标（含第一跳与治疗者本人）—— 不重复跳
     */
    @Nullable
    private static PixelUnit chainHealJump(ServerLevel level, PixelUnit unit, LivingEntity from,
                                          Set<LivingEntity> done) {
        BlockPos at = from.blockPosition();
        AABB box = new AABB(at.getX() - 1, rangeBoxMinY(unit, unit.getBranch()), at.getZ() - 1,
                at.getX() + 2.0D, rangeBoxMaxY(unit, unit.getBranch()), at.getZ() + 2.0D);
        PixelUnit best = null;
        double bestHealth = Double.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (PixelUnit ally : level.getEntitiesOfClass(PixelUnit.class, box,
                e -> !done.contains(e) && !e.isRemoved() && !e.isDeadOrDying() && isFriendly(unit, e))) {
            BlockPos p = ally.blockPosition();
            int dx = p.getX() - at.getX();
            int dz = p.getZ() - at.getZ();
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
                continue;                       // 只认「周围 8 格」
            }
            double health = ally.getHealth();
            double dist = ally.distanceToSqr(from);
            if (health < bestHealth - 1.0E-6D
                    || (Math.abs(health - bestHealth) <= 1.0E-6D && dist < bestDist)) {
                bestHealth = health;
                bestDist = dist;
                best = ally;
            }
        }
        return best;
    }

    /**
     * 咒愈师用：候选里**血量最低**的、需要治疗的目标（★ 含自身，见 {@link #woundedCandidates}）。
     *
     * <p>★ 2026-10 顺手修掉一处名不符实：旧实现委托给 {@link #nearestWoundedAlly}，那是按
     * <b>距离</b>挑的，与方法名/注释写的「血量最低」不是一回事（也正是它让「自己」永远抢不到
     * 候选名额 —— 自己距离恒为 0）。现在直接按血量取最小，与单体治疗的挑选口径一致。</p>
     */
    @Nullable
    private static LivingEntity lowestWoundedAlly(ServerLevel level, PixelUnit unit, BranchDef branch) {
        LivingEntity best = null;
        for (LivingEntity candidate : woundedCandidates(level, unit, branch)) {
            if (best == null || candidate.getHealth() < best.getHealth()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 被动回血（辅助 · 吟游者）的一次脉冲：给范围内**所有**需要治疗的友军各回一次血。
     *
     * <p>对照表原文：「不攻击，持续恢复范围内所有友军生命（每秒恢复相当于自身攻击力10%的生命）」。
     * 三条实现口径：</p>
     * <ul>
     *   <li><b>每秒 = 20 tick</b>，由 {@code regenInterval} 给出；节拍用本 mod 自己的
     *       {@code tickCounter}（游戏刻在自验台里是冻住的，见踩坑记录）；</li>
     *   <li><b>只回不满血的</b>：满血友军跳过（被动每次 tick 都跑，不筛就会一直刷粒子/音效）；</li>
     *   <li><b>范围含自身那一格</b>：原文说「范围内所有友军」，没有把自己排除 ——
     *       吟游者的范围本来就覆盖自身格，所以它给自己回血是**照原文**的行为（本 mod 读法，备查）。</li>
     * </ul>
     *
     * @return 这一脉冲治到几个
     */
    private static int regenPulse(ServerLevel level, PixelUnit unit, BranchDef branch,
                                  UnitBranch.Heal heal) {
        float amount = (float) (branch.attackDamage() * heal.regenFactor());
        if (amount <= 0.0F) {
            return 0;
        }
        int healed = 0;
        if (unit.getHealth() < unit.getMaxHealth() - 0.01F) {
            healOne(level, unit, unit, amount);
            healed++;
        }
        for (LivingEntity ally : alliesInRange(level, unit, branch)) {
            if (ally.getHealth() < ally.getMaxHealth() - 0.01F) {
                healOne(level, unit, ally, amount);
                healed++;
            }
        }
        if (healed > 0) {
            REGEN_HEAL_COUNT.set(healed);
        }
        return healed;
    }

    /** 一位小数（报告里读起来清楚，别打印 12.333333）。 */
    private static String fmt1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 本 tick 被这个棋子挡住的、仍然活着的敌人（顺序 = 抓取顺序）。 */
    private static List<LivingEntity> heldEnemies(PixelUnit unit) {
        Set<PathfinderMob> held = BLOCKED.get(unit);
        if (held == null || held.isEmpty()) {
            return List.of();
        }
        List<LivingEntity> out = new ArrayList<>(held.size());
        for (PathfinderMob mob : held) {
            if (!mob.isRemoved() && !mob.isDeadOrDying()) {
                out.add(mob);
            }
        }
        return out;
    }

    // 说明：本类不再自带「什么算敌人」的薄封装 —— 唯一实现是
    // Targeting.isEnemy → GuardianConfig.isHostile（不要在别处再抄一遍 instanceof Enemy）。
    // ★ 注意两个口径**刻意不同**：
    //   · 「谁算敌人（可打）」= Targeting.isEnemy（Enemy 接口 / MONSTER 类别 + 配置白黑名单）；
    //   · 「谁该被挡住」    = combat/BlockCandidates（配置里的阻挡白名单，默认空 = 谁都不挡）。

    /** 这个目标当前是否正被该棋子阻挡按住。 */
    private static boolean isHeldBy(PixelUnit unit, LivingEntity target) {
        Set<PathfinderMob> held = BLOCKED.get(unit);
        return held != null && target instanceof PathfinderMob mob && held.contains(mob);
    }

    // ------------------------------------------------------------------
    // 伤害乘区（2026-10 第五轮）
    // ------------------------------------------------------------------

    /**
     * 这一击对**这个目标**的伤害倍率（1.0 = 不乘）。
     *
     * <p>把关系信息采齐后交给纯判据 {@link DamageMods#factor}：</p>
     * <ul>
     *     <li><b>targetHeld</b>：目标是不是正被**这个棋子**挡住（走「被挡名单」）；</li>
     *     <li><b>切比雪夫格距</b>：{@link Targeting#chebyshevTiles}（设计口径：领主那个「1 格」
     *         按格距量）；</li>
     *     <li><b>是否空中</b>：{@link Targeting#isFlying}（与索敌/群伤同一个判据）；</li>
     *     <li><b>重量等级</b>：{@link Targeting#approxWeightLevel}（近似，见那里的注释）；</li>
     *     <li><b>ramp</b>：解放者的 {@link DamageMods#liberatorRamp}（其它分支恒 1.0）。</li>
     * </ul>
     *
     * <p>★ 设计口径：「造成伤害 = 进行伤害计算时的**最终伤害**，不是攻击力」——
     * 所以这里算出来的倍率只乘在 {@code hurt()} 的**入参**上，绝不去改
     * {@code branch.attackDamage()}（面板/治疗量/界面显示都不受影响）。</p>
     */
    private static double damageFactor(PixelUnit unit, LivingEntity target) {
        UnitBranch.DamageMod mod = unit.getBranch().damageMod();
        if (mod == null || !mod.present() || DamageMods.isAttackSide(mod)) {
            // ★ 解放者的 ramp 是**攻击力**倍率（口径修正），走
            //   PixelUnit#effectiveAttackDamage，**不**在这里再乘一次（否则乘两遍）。
            return 1.0D;
        }
        return DamageMods.factor(mod,
                isHeldBy(unit, target),
                Targeting.chebyshevTiles(unit, target),
                Targeting.isFlying(target),
                Targeting.approxWeightLevel(target),
                // 散射手「前方一横排」：与索敌/阻挡共用同一套朝向旋转（Targeting#isInFrontRow）
                Targeting.isInFrontRow(unit, target));
    }

    /**
     * 结算一次伤害 —— <b>带伤害乘区</b>的那条路（主段 / 余震 / 溅射 / 连锁全都走它）。
     *
     * <p>★ 为什么单独包一层而不是直接改 {@link #hurtOnce}：{@code hurtOnce} 的调用方里
     * 还有「无来源伤害」这类不该乘的东西吗？—— 目前没有（全部来自棋子出手），
     * 但把「乘区」与「结算」分开写有两个好处：① 倍率只算一次、读起来一眼看到它乘在哪；
     * ② 自验台读得到「这一击乘了多少」（{@link #DEBUG_LAST_DAMAGE_FACTOR}）。</p>
     */
    private static float hurtWithMods(ServerLevel level, PixelUnit unit, LivingEntity victim,
                                      float damage) {
        double factor = damageFactor(unit, victim);
        DEBUG_LAST_DAMAGE_FACTOR = factor;
        return hurtOnce(level, unit, victim, (float) (damage * factor));
    }

    /** 最近一次结算用的伤害倍率（自验/排查用；-1 = 还没发生过）。 */
    private static volatile double DEBUG_LAST_DAMAGE_FACTOR = -1.0D;

    /** 最近一次出手用的**攻击力倍率**（自验/排查用；-1 = 还没发生过）。 */
    private static volatile double DEBUG_LAST_ATTACK_MULTIPLIER = -1.0D;

    /** 读「最近一次出手的攻击力倍率」（解放者 ramp 用；其余分支恒 1.0）。 */
    public static double debugLastAttackMultiplier() {
        return DEBUG_LAST_ATTACK_MULTIPLIER;
    }

    /** 读「最近一次结算的伤害倍率」。 */
    public static double debugLastDamageFactor() {
        return DEBUG_LAST_DAMAGE_FACTOR;
    }

    /** 清零这个观测值（自验每个用例前调一次，免得读到上一段的残留）。 */
    public static void debugResetDamageFactor() {
        DEBUG_LAST_DAMAGE_FACTOR = -1.0D;
        DEBUG_LAST_ATTACK_MULTIPLIER = -1.0D;
    }

    // ------------------------------------------------------------------
    // 攻击
    // ------------------------------------------------------------------

    /**
     * 出手：按分支的<b>攻击方式</b>分成两条路（{@code PROJECTILE} 发射投掷物 /
     * {@code MELEE} 近战武器攻击）。
     *
     * <p><b>为什么伤害不在这里结算</b>：投掷物要飞一段路，伤害发生在<b>命中那一刻</b>
     * （由 {@link PawnProjectile} 回调 {@link #applyHit}）。所以这里只负责「把这一手打出去」，
     * 结算入口只有一个（{@link #applyHit}），近战与投掷物共用 ——
     * 避免「近战一套结算、远程另一套结算」两份实现各自漂移
     * （同 设计说明那条「被挡住算不算在范围内只能有一条判据」的思路）。</p>
     */
    private static void attack(ServerLevel level, PixelUnit unit, BranchDef branch, LivingEntity target) {
        // ★ 攻击力取**运行时**的有效值：面板值 × 攻击力倍率（目前只有解放者的 ramp ≠ 1）。
        //   设计口径（2026-10-05）：「解放者是**攻击力**逐渐提升至 200%」⇒ 它改的是
        //   「这一击用的攻击力」，所以在这里乘一次，**不是**伤害乘区（那条在 hurtWithMods 里）。
        //   同一个数也被朝向界面的「攻击力」那一行读 —— 面板上能看着它爬。
        float damage = (float) unit.effectiveAttackDamage() * MULTI_ATTACK_DAMAGE_SCALE;
        DEBUG_LAST_ATTACK_MULTIPLIER = unit.attackMultiplier();
        // ★ 医疗职业：出手是**治疗**，不是伤害（设计口径 第三轮）。
        //   治疗量 = 攻击值（口径写在 branch_meta.HEAL_OVERRIDES 的 source 里），
        //   治疗同样走投掷物（远程位分支），命中那一刻才结算 —— 与伤害弹共用一个弹体类。
        UnitBranch.Heal heal = branch.heal();
        if (heal.kind() == UnitBranch.Heal.Kind.SINGLE
                || heal.kind() == UnitBranch.Heal.Kind.ALL
                || heal.kind() == UnitBranch.Heal.Kind.CHAIN) {
            float amount = healAmount(unit, branch, target, 1.0D);
            // ★ 出手形式取**运行时**的（技能可以覆盖分支模板，见 PixelUnit#effectiveAttackMethod）
            if (unit.effectiveAttackMethod() == UnitBranch.AttackMethod.PROJECTILE) {
                fireProjectile(level, unit, target, amount, true);
            } else {
                applyHeal(level, unit, target, amount);
            }
            return;
        }
        // ★ 出手形式取**运行时**的（技能可以覆盖分支模板，见 PixelUnit#effectiveAttackMethod）
        switch (unit.effectiveAttackMethod()) {
            case PROJECTILE -> fireProjectile(level, unit, target, damage);
            case MELEE -> meleeAttack(level, unit, target, damage);
            default -> meleeAttack(level, unit, target, damage);
        }
    }

    /**
     * 近战武器攻击：<b>即时结算</b>（设计确定：不加挥砍前摇）。
     *
     * <p>{@code swing(...)} 是原版的挥臂动作包（{@code ClientboundAnimatePacket}）：
     * 目前棋子没有骨骼模型，所以只有粒子与音效看得见，但等以后换成有手臂的模型
     * （设计说明·10「正式美术」）它就是现成的挥砍动画，不用再补。</p>
     */
    private static void meleeAttack(ServerLevel level, PixelUnit unit, LivingEntity target, float damage) {
        unit.swing(InteractionHand.MAIN_HAND, true);
        applyHit(level, unit, target, damage, HitKind.MELEE);
    }

    /**
     * 发射投掷物：造一个 {@link PawnProjectile} 出来，伤害在命中那一刻由弹体结算。
     *
     * <p>开火的瞬间也会出手间隔冷却（见 {@link #tickUnit}），与近战同一套节拍 ——
     * 所以「弹体还在飞」不影响下一次出手的时间点，这也是原作里远程单位的行为。</p>
     */
    private static void fireProjectile(ServerLevel level, PixelUnit unit, LivingEntity target, float damage) {
        fireProjectile(level, unit, target, damage, false);
    }

    /** 同上，但可以指定这是**治疗弹**（医疗职业）。 */
    private static void fireProjectile(ServerLevel level, PixelUnit unit, LivingEntity target,
                                       float damage, boolean healing) {
        // fire() 恒定返回一颗已经加入世界的弹体（没有「失败返回 null」这条路）：
        // 主人/目标在开火那一刻之后失效的话，弹体自己在下一 tick 消散（见 PawnProjectile.tick）。
        // 所以这里不需要判空分支 —— 有的话就是一段永远不会走到的死代码。
        PawnProjectile shot = PawnProjectile.fire(level, unit, target, damage, healing);
        Vec3 at = shot.position();
        // 枪口闪光：一小撮粒子 + 一声箭响（服务端发，客户端自动同步）
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 3, 0.05D, 0.05D, 0.05D, 0.0D);
        level.playSound(null, unit.blockPosition(), SoundEvents.ARROW_SHOOT,
                SoundSource.NEUTRAL, 0.35F, 1.4F);
    }

    /** 命中类型：决定「打中了」的粒子与音效（两者的伤害结算是同一条路）。 */
    public enum HitKind {
        /** 近战武器：刀光 + 劈砍音。 */
        MELEE,
        /** 投掷物：附魔命中粒子 + 箭矢命中音。 */
        PROJECTILE
    }

    /**
     * 结算一次命中 —— <b>近战与投掷物唯一的共同入口</b>。
     *
     * <p>它做的三件事：</p>
     * <ol>
     *     <li>按原版伤害源结算（护甲/附魔照常生效，来源仍是棋子本体）；</li>
     *     <li><b>被自己挡住的敌人</b>挨打后把位置与速度复位 —— 原版击退会把它推离锚点，
     *         而阻挡又每 tick 把它拉回来，不抵消就是肉眼可见的「拖拽」（见踩坑记录）；</li>
     *     <li>播命中表现（粒子 + 音效），按 {@link HitKind} 区分近战与投掷物。</li>
     * </ol>
     *
     * <p>{@code public} 是给 {@link PawnProjectile} 用的：弹体命中时回调这里，
     * 而不是自己再写一份伤害结算。</p>
     */
    public static void applyHit(ServerLevel level, PixelUnit unit, LivingEntity target, float damage,
                                HitKind kind) {
        BranchDef branch = unit.getBranch();
        UnitBranch.Aoe aoe = branch.aoe();
        UnitBranch.Heal heal = branch.heal();
        int hits = Math.max(1, branch.attackCount());
        float last = 0.0F;
        float dealtFirst = 0.0F;
        for (int hit = 0; hit < hits; hit++) {
            // 第 0 段全额，之后按 tailFactor 递减（投掷手：第二段余震 = 攻击力的一半）
            float dmg = hit <= 0 ? damage
                    : (float) (damage * Math.pow(branch.tailFactor(), hit));
            last = dmg;
            if (!target.isRemoved() && !target.isDeadOrDying()) {
                float dealt = hurtWithMods(level, unit, target, dmg);
                if (hit == 0) {
                    dealtFirst = dealt;
                }
            }
            // 表现只在第一段播：余震与主段同一 tick，再播一次只是噪声
            if (hit == 0) {
                showHitFx(level, unit, target, kind);
                // ★ 停顿（2026-10 第四轮）：`HIT` 档 = **每次普攻命中的主目标**（凝滞师）。
                //   依据：PRTS 精二特性「攻击造成法术伤害，并对敌人造成短暂的停顿」；
                //   时长 0.8 秒 = 16 tick，出处与判据见 UnitBranch#pause()。
                //   `CHAIN` 档（链术师）**不在这里**做 —— 它只作用于跳跃到的那几个目标，
                //   由 applyChain 处理；两档互斥，写在一个地方就不会双份。
                UnitBranch.Pause pause = branch.pause();
                if (pause.present() && pause.kind() == UnitBranch.Pause.Kind.HIT) {
                    pauseBriefly(target, pause.ticks());
                }
            }
            // 落点溅射：以**命中点**为中心，半径内的其他敌人也吃伤害（主目标不算，见 applySplash）
            if (aoe.kind() == UnitBranch.Aoe.Kind.SPLASH) {
                applySplash(level, unit, target, (float) (dmg * aoe.factor()), aoe);
            }
        }
        // 连锁：命中之后逐跳找下一个敌人（与「几段」无关，一次攻击只连锁一次）
        if (aoe.kind() == UnitBranch.Aoe.Kind.CHAIN) {
            applyChain(level, unit, target, last, aoe);
        }
        // ★ 咒愈师（POST_HIT）：**先打敌人，再把这次伤害的一部分治给一名友方**
        //   （口径确认口径；对照表原文：为攻击范围内一名友方干员治疗相当于 50% 伤害的生命值）。
        //   治疗量用「**实际造成的伤害**」算（扣过护甲/吸收之后再乘比例）——
        //   用面板伤害会让「打了高防目标却治了一大口」这种事发生。
        if (heal.kind() == UnitBranch.Heal.Kind.POST_HIT && dealtFirst > 0.0F) {
            LivingEntity ally = lowestWoundedAlly(level, unit, branch);
            if (ally != null) {
                applyHeal(level, unit, ally, (float) (dealtFirst * heal.postHitFactor()));
                POST_HIT_HEAL_COUNT.set(1);
            } else {
                POST_HIT_HEAL_COUNT.set(0);
            }
        }
        // 说明：被阻挡的敌人<b>不会</b>因为挨打而解除阻挡 ——
        // 阻挡的意义就是把它钉在原地挨打，直到它被击杀或棋子被拆。
    }

    /**
     * 结算**一次**伤害实例 —— 本 mod 的伤害全部从这里落地（主段 / 余震 / 溅射 / 连锁）。
     *
     * <h4>① 击退一律抹掉（2026-10 设计口径）</h4>
     * <p>原版 {@code hurt} 扣血之后会给目标一个击退冲量，这里把冲量<b>原样复位</b>：
     * 伤害、护甲、附魔、无敌帧、死亡结算全都照原版走，只有「被推开」这件事被抹掉。
     * 由此上一版那个「挨打后把被挡住的目标 setPos 回锚点」的补丁整个删掉 ——
     * 它存在的唯一理由就是抵消这次击退。</p>
     *
     * <h4>② ★ 为什么必须清零 {@code invulnerableTime}</h4>
     * <p>1.20.1 的 {@code LivingEntity#hurt} 开头的判据是
     * 「{@code invulnerableTime > 10} 且伤害源不绕过冷却」→ 此时<b>只有超过「上次那次伤害」的部分</b>
     * 才生效，甚至直接 {@code return false}（一点伤害都不掉）。而 {@code hurt} 成功之后会把
     * {@code invulnerableTime} 置成 20 —— 也就是说，**同一 tick 内的第二次结算会被原版静静吞掉**。</p>
     * <p>这对本 mod 是致命的：投掷手的余震（0.5×）恒小于主段，会被判成「不如上次疼」而完全无效；
     * 溅射/连锁的目标若在最近 10 tick 内挨过打，也会部分或全部无效。玩家看到的是
     * 「群体伤害时好时坏」，而日志里什么都没有。所以这里在每次结算前清零。</p>
     * <p>代价（写出来，别装作没有）：原版的无敌帧本来会吞掉「同一瞬间来自不同来源的重复伤害」。
     * 清零之后，<b>多个棋子集火同一个目标时伤害会全额叠加</b>。塔防里这正是想要的
     * （「我摆了三个棋子，三个都在打」），而原版行为表现为「有的棋子明明出手了却不掉血」。
     * 单棋子自己不可能因此变快 —— 它的出手间隔（40 tick 起）本来就远大于无敌帧（20 tick）。</p>
     */
    private static float hurtOnce(ServerLevel level, PixelUnit unit, LivingEntity victim, float damage) {
        Vec3 motionBefore = victim.getDeltaMovement();
        float healthBefore = victim.getHealth();
        victim.invulnerableTime = 0;
        victim.hurt(level.damageSources().mobAttack(unit), damage);
        if (!victim.isRemoved() && !victim.isDeadOrDying()) {
            // 只在目标还活着时复位：死亡那一刻的位移（死亡动画的抛飞）不该被抹掉，
            // 否则「击杀的打击感」也会一起消失。
            victim.setDeltaMovement(motionBefore);
        }
        // 返回**实际掉的血**（扣过护甲/吸收之后）：咒愈师的「治疗 50% 伤害」要用这个数，
        // 用面板伤害会得到「打了高防目标却治了一大口」这种解释不通的结果。
        return Math.max(0.0F, healthBefore - victim.getHealth());
    }

    /**
     * 落点溅射：以**命中点**为中心、半径 {@code aoe.radius()} 格内的其他敌人，各吃
     * {@code damage}（= 主段伤害 × 溅射系数，调用方已经乘好）。
     *
     * <p>三条口径：</p>
     * <ul>
     *   <li><b>主目标不算在内</b>：它已经在主段里结算过一次，再吃一次溅射就是「一枪两倍伤害」，
     *       原作里溅射的措辞一律是「目标<b>周围</b>的其他敌人」；</li>
     *   <li><b>能不能打空中</b>按分支数值（{@code aoe.canHitAir()}）—— 要塞/投掷手的来源备注
     *       明写「不可对空」，用 {@link Targeting#isFlying} 判，和索敌那边同一个判据；</li>
     *   <li><b>垂直窗口与索敌一致</b>（{@link #canHitVertically}）：否则「贴着地面打的棋子」
     *       会隔着几格高度溅到天上的敌人，和本工程「显式高度区间」那条规矩打架。</li>
     * </ul>
     *
     * @return 被溅射到的敌人数（报告/自验用）
     */
    private static int applySplash(ServerLevel level, PixelUnit unit, LivingEntity primary,
                                   float damage, UnitBranch.Aoe aoe) {
        if (damage <= 0.0F || aoe.radius() <= 0.0D) {
            return 0;
        }
        Vec3 at = impactPoint(primary);
        double r2 = aoe.radius() * aoe.radius();
        AABB box = new AABB(at, at).inflate(aoe.radius() + 0.5D);
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != primary && !e.isRemoved() && !e.isDeadOrDying()
                        && Targeting.isEnemy(e)
                        && (aoe.canHitAir() || !Targeting.isFlying(e))
                        && canHitVertically(unit, e))) {
            if (impactPoint(e).distanceToSqr(at) > r2) {
                continue;   // AABB 是方的，这里按球判（半径语义必须与数值口径一致）
            }
            hurtWithMods(level, unit, e, damage);
            level.sendParticles(ParticleTypes.ENCHANTED_HIT,
                    at.x, at.y, at.z, 2, 0.1D, 0.1D, 0.1D, 0.01D);
            n++;
        }
        if (n > 0) {
            SPLASH_HIT_COUNT.set(n);      // 排查用：最近一次溅射打到了几个
        }
        return n;
    }

    /**
     * 连锁：从命中目标开始，在 {@code aoe.radius()} 格内找**最近的、还没打过的**敌人跳过去，
     * 每跳伤害 ×{@code aoe.chainDecay()}，直到打满 {@code aoe.chainTargets()} 个目标
     * （<b>含第一个</b>）或找不到下一个为止。
     *
     * <p>数值依据：PRTS《溅射半径一览》——「会在4个敌人间跳跃，每次跳跃伤害降低15%」
     * （4 是精二口径；对照表那列写「3（精二后 4）」）。跳跃搜索半径 1.7 也取自同一张表。</p>
     *
     * <p><b>「短暂停顿」现在是数据</b>（2026-10 第四轮改）：时长按分支走
     * {@link BranchDef#pause()}（链术师 0.5 秒 = 10 tick，出处见 {@link #PAUSE_AMPLIFIER}），
     * 且**只对连锁跳到的目标**生效 —— PRTS 原文写的是「每次**跳跃**…造成短暂停顿」，
     * 主目标不算「跳跃」。实现是**一次短时强减速**，不是眩晕：本工程的硬规矩是
     * 「不碰敌人的坐标」（见 6.3 阻挡那一节的教训）。</p>
     *
     * @return 连锁额外打中的敌人数（不含第一个）
     */
    private static int applyChain(ServerLevel level, PixelUnit unit, LivingEntity primary,
                                  float damage, UnitBranch.Aoe aoe) {
        java.util.Set<LivingEntity> hit = new java.util.LinkedHashSet<>();
        hit.add(primary);
        LivingEntity current = primary;
        float dmg = damage;
        int targets = Math.max(1, aoe.chainTargets());
        // ★ 停顿只在「连锁跳」这一档上生效；HIT 档（凝滞师）管的是普攻主目标，在 applyHit 里做。
        UnitBranch.Pause pause = unit.getBranch().pause();
        boolean pauseOnJump = pause.present() && pause.kind() == UnitBranch.Pause.Kind.CHAIN;
        for (int jump = 1; jump < targets; jump++) {
            dmg = (float) (dmg * aoe.chainDecay());
            LivingEntity next = nearestChainTarget(level, unit, current, hit, aoe);
            if (next == null) {
                break;      // 附近没有可跳的目标：如实停下，不硬凑跳数
            }
            hurtWithMods(level, unit, next, dmg);
            if (pauseOnJump) {
                pauseBriefly(next, pause.ticks());
            }
            Vec3 at = impactPoint(next);
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 3, 0.12D, 0.12D, 0.12D, 0.02D);
            // ★ 链的连线（2026-10 补）：从上一跳到这一跳画一条看得见的线（斜角也连，见 showChainLink）
            showChainLink(level, current, next, ParticleTypes.ELECTRIC_SPARK);
            hit.add(next);
            current = next;
        }
        CHAIN_HIT_COUNT.set(hit.size());
        return hit.size() - 1;
    }

    /** 连锁的下一跳：在搜索半径内、还没打过的最近敌人（判定口径与溅射一致）。 */
    @Nullable
    private static LivingEntity nearestChainTarget(ServerLevel level, PixelUnit unit,
                                                   LivingEntity from, java.util.Set<LivingEntity> hit,
                                                   UnitBranch.Aoe aoe) {
        Vec3 at = impactPoint(from);
        AABB box = new AABB(at, at).inflate(aoe.radius() + 0.5D);
        double r2 = aoe.radius() * aoe.radius();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> !hit.contains(e) && !e.isRemoved() && !e.isDeadOrDying()
                        && Targeting.isEnemy(e)
                        && (aoe.canHitAir() || !Targeting.isFlying(e))
                        && canHitVertically(unit, e))) {
            double d = impactPoint(e).distanceToSqr(at);
            if (d <= r2 && d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * 「短暂停顿」的落地：给目标挂一次**短时强减速**（不碰坐标）。
     *
     * @param ticks 停顿时长（tick）—— 来自 {@code branch.pause().ticks()}，**不是常数**：
     *              凝滞师 16（0.8 秒）、链术师 10（0.5 秒）。调用方保证 {@code ticks > 0}。
     */
    private static void pauseBriefly(LivingEntity victim, int ticks) {
        if (ticks <= 0) {
            return;
        }
        victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                ticks, PAUSE_AMPLIFIER, false, true));
    }

    /** 命中点：目标身体中心（粒子、溅射圆心、连锁起点都用它，只算一次）。 */
    private static Vec3 impactPoint(LivingEntity e) {
        return e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
    }

    /** 链的连线每隔多少格撒一颗粒子。0.25 格 ⇒ 连得起来，也不会密到像一团光。 */
    private static final double CHAIN_LINK_STEP = 0.25D;

    /**
     * 链的「连线」表现：从上一跳目标沿**真实线段**撒一串粒子到这一跳目标。
     *
     * <p>★ 2026-10 设计口径（原话：「表现，不能斜角链接治疗」→「加可见连线，斜角也要连」→
     * 「连线不要搞烟花粒子效果」→「我的意思是换其他样式的粒子」）：</p>
     * <ul>
     *   <li><b>必须有连线</b>：之前两条链都只在**每个目标**撒一簇粒子，跳与跳之间没有任何连接，
     *       观感像「各打各的」；</li>
     *   <li><b>斜角也要连</b>：这里沿两点之间的三维线段等距采样，**正交与斜角走同一条路径** ——
     *       不存在「只连正交」的分支；</li>
     *   <li><b>不要烟花那种</b>：每颗 `count = 1`、扩散 0、速度 0（烟花是十几颗带扩散与速度的爆开），
     *       并且按链的种类挑**贴题**的样式 —— 伤害链用 {@code ELECTRIC_SPARK}（电弧），
     *       治疗链用 {@code HAPPY_VILLAGER}（绿色治愈光点），**不用** {@code FIREWORK}。</li>
     * </ul>
     */
    private static void showChainLink(ServerLevel level, LivingEntity from, LivingEntity to,
                                      ParticleOptions particle) {
        Vec3 a = impactPoint(from);
        Vec3 b = impactPoint(to);
        int steps = Math.max(2, (int) Math.round(a.distanceTo(b) / CHAIN_LINK_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            level.sendParticles(particle,
                    a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        CHAIN_LINK_COUNT.set(CHAIN_LINK_COUNT.get() < 0 ? 1 : CHAIN_LINK_COUNT.get() + 1);
    }

    /** 命中表现（粒子 + 音效），近战与投掷物各一套。 */
    private static void showHitFx(ServerLevel level, PixelUnit unit, LivingEntity target, HitKind kind) {
        Vec3 at = impactPoint(target);
        boolean melee = kind == HitKind.MELEE;
        level.sendParticles(
                melee ? ParticleTypes.CRIT : ParticleTypes.ENCHANTED_HIT,
                at.x, at.y, at.z, 4, 0.15D, 0.15D, 0.15D, 0.02D);
        if (melee) {
            // 刀光：原版「横扫之刃」用的就是这个粒子，一张斜向的白色弧线
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            level.playSound(null, unit.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP,
                    SoundSource.NEUTRAL, 0.35F, 0.9F);
        } else {
            level.playSound(null, unit.blockPosition(), SoundEvents.ARROW_HIT,
                    SoundSource.NEUTRAL, 0.35F, 1.2F);
        }
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /**
     * 清掉「已经不在世界上的棋子」的缓存，避免静态 map 泄漏。
     *
     * <h3>★★ 判据只能是「棋子没了」，不能是「不是这个维度」（2026-10 修，实打实的 bug）</h3>
     * <p>原实现是 {@code prune(ServerLevel level)}，判据 {@code u.level() != level} 就删 ——
     * 而它当时**在每个维度各调一次**（{@code tickLevel} 的末尾）。服务端同时有
     * <b>三个</b>维度（主世界 / 下界 / 末地），于是：主世界棋子的冷却/阻挡/选中记录
     * 会在<b>同一次 tick 里</b>被下界那次 prune 清掉。后果不是「偶尔漏一拍」，而是：</p>
     * <ul>
     *     <li>出手冷却每 tick 归零 ⇒ <b>棋子每个 tick 都出手</b>（20 次/秒），
     *         攻击间隔与倍率**完全失效**；</li>
     *     <li>阻挡集合每 tick 清空 ⇒ 被按住的敌人每 tick 松一次手（位置还留在锚点上，
     *         所以只在「被挡住=」这类观测里看得出来）；</li>
     *     <li>「本 tick 选中了谁」每 tick 归零 ⇒ 自验与排查读到的永远是空。</li>
     * </ul>
     * <p><b>怎么发现的</b>：本轮给自验加「投掷物真的被造出来了吗」这条断言时，
     * 报告出现自相矛盾的一行 —— 场上明明有弹体（棋子出过手），而冷却读出来是 0、
     * 选中读出来是 0。再推一次心跳，弹体从 1 颗变 2 颗，实锤「冷却没生效」。</p>
     *
     * <p>★ 教训：**「按维度清理」的判据必须问「这个对象还在不在」，而不是「它属不属于我这一份」**
     * —— 后者在「同一份数据被多个维度共享」时会把还活着的数据删掉，而且一声不响。
     * 这也解释了两次实测反馈「攻击速度过快 / 间隔还要再长」：调倍率在一个**没生效**的
     * 冷却上，当然看不出变化。</p>
     */
    private static void prune() {
        COOLDOWN.keySet().removeIf(PixelUnit::isRemoved);
        LAST_TARGETS.keySet().removeIf(PixelUnit::isRemoved);
        Iterator<Map.Entry<PixelUnit, Set<PathfinderMob>>> it = BLOCKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PixelUnit, Set<PathfinderMob>> e = it.next();
            PixelUnit unit = e.getKey();
            if (unit.isRemoved()) {
                // 棋子没了：把它挡住的敌人**完整放行**（停寻路 + 清目标/愤怒 + 退出队伍），
                // 不能只 stop() —— 否则它们会一直盯着一个不存在的攻击目标。
                for (PathfinderMob mob : e.getValue()) {
                    release(unit.level() instanceof ServerLevel sl ? sl : null, unit, mob);
                }
                e.getValue().clear();
                it.remove();
            } else {
                // 同样用 isDeadOrDying()：正在播死亡动画的敌人不该继续占着阻挡名额
                e.getValue().removeIf(m -> m.isRemoved() || m.isDeadOrDying());
            }
        }
    }

    /** 便于调试：当前被阻挡的敌人总数。 */
    public static int blockedCount() {
        int n = 0;
        for (Set<PathfinderMob> s : BLOCKED.values()) {
            n += s.size();
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 自验 / 调试用的只读口子
    // ------------------------------------------------------------------

    /**
     * 这个棋子本 tick 选中的目标（自验与调试用；没目标时为 null）。
     *
     * <p>返回值是<b>只读快照</b>：{@link List#copyOf} 复制出来的不可变列表，
     * 调用方改不动内部状态。口子存在的理由很实际 —— 没有它，
     * 「到底选中了谁」在服务端完全不可观测，只能靠看伤害来猜，
     * 而棋盘上别的东西也会打伤害，很容易误判。</p>
     */
    public static List<LivingEntity> debugLastTargets(PixelUnit unit) {
        List<LivingEntity> t = LAST_TARGETS.get(unit);
        return t == null ? List.of() : t;
    }

    /** 这个棋子当前挡住了哪些敌人（只读快照，自验/调试用）。 */
    public static List<PathfinderMob> debugBlocked(PixelUnit unit) {
        Set<PathfinderMob> held = BLOCKED.get(unit);
        return held == null ? List.of() : List.copyOf(held);
    }

    // ------------------------------------------------------------------
    // 近战出手距离（原版判据）—— ★ 唯一实现，诊断命令与自验都只调这里
    // ------------------------------------------------------------------

    /**
     * 原版近战「出手距离」判据（<b>平方</b>形式，逐字照抄 1.20.1
     * {@code MeleeAttackGoal#getAttackReachSqr}）：
     *
     * <pre>{@code
     * (double)(mob.getBbWidth() * 2.0F * mob.getBbWidth() * 2.0F + target.getBbWidth())
     * }</pre>
     *
     * <p><b>★ 为什么收进 combat 包、而且只此一处</b>：{@code /guardianprotocol block} 诊断命令要打
     * 「这只怪的攻击距离」，若把公式内联在命令里就出现了第二份口径 —— 哪天原版换写法、
     * 或者我们改了棋子尺寸，两处会各说各话，而「怪够不够得着棋子」正是拿这个数判断的。
     * 命令只调这里，不复制算术（本项目纪律：一条判据只留一处实现）。</p>
     *
     * <p>注意它是<b>距离的平方</b>：被攻击方的<b>宽度</b>直接加在里面 ⇒ 棋子越宽、怪越够得着。
     * 僵尸（宽 0.6）打 0.6 宽的棋子：{@code 1.44 + 0.6 = 2.04} ⇒ <b>1.4283</b> 格。</p>
     */
    public static double meleeAttackReachSqr(LivingEntity attacker, LivingEntity target) {
        return (double) (attacker.getBbWidth() * 2.0F * attacker.getBbWidth() * 2.0F
                + target.getBbWidth());
    }

    /** {@link #meleeAttackReachSqr} 的开方 = 到底几格够得着（算术只有上面那一处）。 */
    public static double meleeAttackReach(LivingEntity attacker, LivingEntity target) {
        return Math.sqrt(meleeAttackReachSqr(attacker, target));
    }

    /**
     * 两个实体的<b>中心距</b>（格，三维 —— 与 {@code Entity#distanceToSqr} 同一口径）。
     *
     * <p>原版出手判定比的就是这个量：{@code mob.distanceToSqr(target) <= getAttackReachSqr(target)}，
     * 而 {@code Entity#distanceToSqr(Entity)} 走 {@code (x, y, z)} 三轴 —— 所以「中心距」也不能
     * 只算水平面，否则诊断报告里会出现「距离 1.2 却说够不着」这种自相矛盾的行。</p>
     */
    public static double centerDistance(LivingEntity a, LivingEntity b) {
        return Math.sqrt(a.distanceToSqr(b));
    }

    /** 原版判据本体：这只怪现在<b>够得着</b>这个目标吗（唯一实现，命令与自验都调它）。 */
    public static boolean isWithinMeleeAttackRange(LivingEntity attacker, LivingEntity target) {
        return attacker.distanceToSqr(target) <= meleeAttackReachSqr(attacker, target);
    }

    /** 这个棋子本 tick 的出手间隔（tick，含当前倍率）。自验用。 */
    public static int debugIntervalTicks(PixelUnit unit) {
        return attackIntervalTicks(unit.getBranch());
    }

    /** 出手冷却剩余（tick）。自验用来观察「确实按间隔出过手」。 */
    public static int debugCooldown(PixelUnit unit) {
        return COOLDOWN.getOrDefault(unit, 0);
    }

    /**
     * 把「这个棋子为什么选中/没选中某个目标」的判据逐条打出来（排查用）。
     *
     * <p>存在的理由：{@code 选中=0} 这个结果有很多条互不相干的原因（不在格子里、
     * 垂直判定挡掉、被挡住但没名额、水平距离不够、实体根本没在同一个 level…），
     * 光看结果猜不出来。这里把每一步的中间量都列出来，一次就能定位。
     * 自验命令的 {@code debug} 场景会把这些写进报告。</p>
     */
    public static String debugTrace(PixelUnit unit) {
        StringBuilder sb = new StringBuilder();
        BranchDef branch = unit.getBranch();
        sb.append("棋子 ").append(branch.branchName()).append("（").append(branch.key()).append("）\n");
        sb.append("  位置=").append(String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)",
                unit.getX(), unit.getY(), unit.getZ()))
                .append(" 方块格=").append(unit.blockPosition())
                .append(" 朝向=").append(unit.getFacing().displayName())
                .append(" 攻击范围=").append(branch.attackRangeKey())
                .append(" 优先级=").append(branch.targetPriority())
                .append(" 目标数=").append(branch.targetCount())
                .append(" 会攻击=").append(branch.canAttack()).append('\n');
        sb.append("  垂直模式=").append(branch.verticalTargeting())
                .append(" 攻击间隔=").append(attackIntervalTicks(branch)).append(" tick\n");
        sb.append("  上一步选目标走到：").append(DEBUG_STEPS).append('\n');
        sb.append("  打击格=");
        for (AttackRange.Cell c : unit.worldCells()) {
            sb.append('(').append(c.x()).append(',').append(c.z()).append(") ");
        }
        sb.append('\n');

        // 附近实体逐个体检
        AABB around = new AABB(unit.blockPosition()).inflate(12.0D);
        List<LivingEntity> near = unit.level() instanceof ServerLevel sl
                ? sl.getEntitiesOfClass(LivingEntity.class, around, e -> !(e instanceof PixelUnit))
                : List.of();
        sb.append("  12 格内实体 ").append(near.size()).append(" 个：\n");
        for (LivingEntity e : near) {
            boolean enemy = Targeting.isEnemy(e);
            boolean vert = canHitVertically(unit, e);
            boolean inCell = false;
            AABB box = e.getBoundingBox();
            for (AttackRange.Cell c : unit.worldCells()) {
                BlockPos cp = unit.blockPosition().offset(c.x(), 0, c.z());
                if (new AABB(cp).intersects(box)) {
                    inCell = true;
                    break;
                }
            }
            sb.append("    ").append(e.getType().getDescription().getString())
                    .append(" 位置=").append(String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)",
                            e.getX(), e.getY(), e.getZ()))
                    .append(" 存活=").append(!e.isDeadOrDying())
                    .append(" 算敌人=").append(enemy)
                    .append(" 过垂直判定=").append(vert)
                    .append(" 落在打击格=").append(inCell)
                    .append(" 被挡住=").append(isHeldBy(unit, e))
                    .append(" 距棋子=").append(String.format(java.util.Locale.ROOT, "%.2f",
                            Math.sqrt(e.distanceToSqr(unit))))
                    .append(" 脚底差=").append(String.format(java.util.Locale.ROOT, "%.2f",
                            e.getY() - unit.getY()))
                    .append('\n');
        }
        return sb.toString();
    }
}
