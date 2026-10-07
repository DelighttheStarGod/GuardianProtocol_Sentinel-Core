package com.guardianprotocol.combat;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 棋子技能位的<b>状态机</b>：技力（SP）→ 触发 → 激活 / 结束。
 *
 * <h3>本类现在负责什么</h3>
 * <ol>
 *     <li><b>涨技力</b>：自动回复每 {@value #AUTO_RECOVERY_INTERVAL_TICKS} tick +1；
 *         攻击回复 / 受击回复由 {@link #onAttack} / {@link #onHurt} 两个挂钩点 +1；</li>
 *     <li><b>满即放</b>：未激活时 {@code SP >= 消耗} ⇒ 扣满消耗并触发（<b>没有任何手动入口</b>，
 *         设计口径：卫戍协议活动里没有手动释放，我们也不做）；</li>
 *     <li><b>激活窗口</b>：有限持续按 tick 递减、永续不递减、弹药类由出手驱动退出；
 *         <b>激活期间阻回</b>（不回技力）；</li>
 *     <li><b>占位表现</b>：一声轻响 + 少量粒子 + 一行日志（日志带技能位 / 技能名 / 结束原因）；</li>
 *     <li><b>调试 / 自验口子</b>：{@link #setSlot}、{@link #debugCastCount}、
 *         {@link #resetDebugCounters}、{@link #describe}。</li>
 * </ol>
 *
 * <h3>本类<b>不</b>负责什么</h3>
 * <ul>
 *     <li><b>技能效果一律不做</b>（攻击力 / 攻击间隔 / 目标数 / 防御 / 生命 / 治疗 / 范围 / 减益）：
 *         设计确定「本轮只做技力 → 触发这一条链」，效果文本照读照存、不驱动行为；</li>
 *     <li><b>伤害与治疗的结算</b>：技能要造成伤害时不许另开一套，必须回调
 *         {@code PawnCombatManager.applyHit} / {@code applyHeal}（同队判据也走 {@code isFriendly}）；</li>
 *     <li><b>持有棋子的状态</b>：技力 / 激活剩余 / 弹药三个字段存在 {@link PixelUnit} 的同步数据里
 *         （存档要存、客户端要显示、多人要同步），本类只读写它们；</li>
 *     <li><b>手动释放 / 手动关闭</b>：不做，也不要再问（设计要求）。</li>
 * </ul>
 *
 * <h3>判据的出处（每个数字都有来源）</h3>
 * <p>数值口径见生成物 {@code UnitBranch.Skill} 的注释（《明日方舟模板干员技能表.xlsx》最高等级列）；
 * 机制口径出自 PRTS「技能」页 <a href="https://prts.wiki/w/技能">https://prts.wiki/w/技能</a>：
 * 技力上限 = 该技能的消耗技力、触发后扣除全部消耗、激活期间阻回、弹药打完后结束。</p>
 *
 * <h3>计时为什么用本类自己的 tick 计数</h3>
 * <p>自验台（无头服务端）<b>不推进游戏刻</b>（{@code getGameTime()} 冻住，踩坑记录），
 * 实体自己的 {@code tickCount} 同样不一定在动。所以「自动回复每 20 tick +1」由
 * {@link #tickUnit} 被调用的次数驱动（{@code TICK_COUNTER}）—— 手动心跳也能验，
 * 而且与真实运行的节拍一致（谁调 {@link #tickUnit}，谁就是在推进它）。</p>
 */
public final class Skills {

    /** 自动回复的间隔（tick，20 tick = 1 秒；PRTS：「通常每秒回复1点技力」）。 */
    public static final int AUTO_RECOVERY_INTERVAL_TICKS = 20;

    /**
     * 每颗棋子放过几次技能（自验用）。
     *
     * <p>与 {@code PawnCombatManager.COOLDOWN} 同一个写法：{@code Entity} 不重写
     * {@code equals/hashCode}，所以这里就是按<b>实例身份</b>计数，不会把两颗棋子混在一起。</p>
     */
    private static final Map<PixelUnit, Integer> CAST_COUNT = new HashMap<>();

    /**
     * 每颗棋子被 {@link #tickUnit} 推进了多少次（自动回复的节拍源）。
     *
     * <p>不用 {@code Entity#tickCount}、更不用 {@code level.getGameTime()}：
     * 自验台里那两个都可能是冻住的（踩坑记录），而这里数的是「本类被调了几次」，
     * 手动心跳与真实运行得到同一个结果。</p>
     */
    private static final Map<PixelUnit, Integer> TICK_COUNTER = new HashMap<>();

    /**
     * 每颗棋子<b>最近一次</b>技能结束的原因（只给 {@link #describe} 与自验看）。
     *
     * <p>不进同步数据也不进存档：它是「发生过什么」的痕迹，不是棋子的状态 ——
     * 存进存档只会让读档的棋子带着上一局的结束原因。</p>
     */
    private static final Map<PixelUnit, EndReason> LAST_END = new HashMap<>();

    private Skills() {
    }

    /** 技能为什么结束（激活开始不算结束，所以没有「开始」这一类）。 */
    public enum EndReason {
        /** 还没结束过（或正在激活中）。 */
        NONE("未结束"),
        /** 瞬发：放完即结束，不进激活期。 */
        INSTANT("瞬发（放完即结束）"),
        /** 有限持续：剩余 tick 归 0。 */
        EXPIRED("时间到"),
        /** 弹药类：出手之后弹量 ≤ 0（允许扣成负数，PRTS 原话「可能出现负数弹药」）。 */
        AMMO_OUT("弹药耗尽");

        private final String display;

        EndReason(String display) {
            this.display = display;
        }

        /** 中文说法（日志与 {@link #describe} 用）。 */
        public String displayName() {
            return display;
        }
    }

    // ------------------------------------------------------------------
    // 服务端每 tick
    // ------------------------------------------------------------------

    /**
     * 服务端每 tick 调用：推进所有棋子的技力与激活窗口，满技力就放技能。
     *
     * <p><b>为什么先判 {@code level.getServer() != server}</b>：本方法可能被
     * 「手动心跳」（自验台 / 指令里直接调）触发，也可能一个进程里存在不止一个服务端对象
     * （单机世界切换、集成服务器与独立服务器并存）。加上这一条，
     * 「推进哪个服务端的棋子」就不会张冠李戴 —— 与 {@code PawnCombatManager}
     * 只处理「本维度」的棋子同一个思路。</p>
     *
     * <p>单个棋子出错一律 catch 住打日志：一颗棋子的坏数据不该让全场棋子的技能
     * 全部停转（照 {@code PawnCombatManager.tickLevel} 的写法）。</p>
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (PixelUnit unit : PixelUnit.activeUnits()) {
            if (unit.isRemoved() || !unit.isAlive()) {
                continue;
            }
            // 只在服务端推进：level() 不是 ServerLevel（客户端那份）直接跳过
            if (!(unit.level() instanceof ServerLevel level) || level.getServer() != server) {
                continue;
            }
            try {
                tickUnit(unit);
            } catch (Exception ex) {
                GuardianProtocol.LOGGER.error("[{}] 棋子技能逻辑异常，已跳过 {}",
                        GuardianProtocol.MODID, unit.getUUID(), ex);
            }
        }
        // ★ prune 必须**每个服务端 tick 只调一次**：放进上面的循环里会变成
        //   「处理一个棋子就清一遍别的棋子的计数」。同类问题的完整教训见
        //   PawnCombatManager.prune() 的注释（那里的判据按维度写错过一次）。
        prune();
    }

    /**
     * 推进一颗棋子的技能位（技力 / 激活 / 触发）——<b>状态机的全部判断都在这里</b>。
     *
     * <p>顺序（与 设计文档 的状态机一一对应）：</p>
     * <ol>
     *     <li>槽位 0、槽位超过本分支技能数、被动(常驻生效) ⇒ <b>什么都不做</b>（也不刷日志）；</li>
     *     <li>激活中 ⇒ 只有有限持续递减，归 0 结束；永续与弹药类不递减；
     *         <b>一律不回技力（阻回）</b>；</li>
     *     <li>未激活 ⇒ 按回复类型涨技力（自动回复在这里；攻击/受击回复由挂钩点加）、
     *         夹到上限，然后 {@code SP >= 消耗} 就扣满消耗并触发。</li>
     * </ol>
     *
     * <p>槽位为 0 时什么都不做、<b>不刷日志</b>：否则每个没技能的棋子每 tick 一行，
     * 日志会被淹没，这也是「没有技能」这个默认状态该有的安静。</p>
     */
    static void tickUnit(PixelUnit unit) {
        int slot = unit.getSkillSlot();
        UnitBranch.Skill skill = skillOf(unit);
        if (slot <= 0 || skill == null) {
            return;
        }
        // 被动(常驻生效)：不进技力循环、永不触发（本轮它连效果都没有，见 设计文档）。
        if (skill.durationKind() == UnitBranch.DurationKind.PASSIVE) {
            return;
        }

        int ticks = TICK_COUNTER.merge(unit, 1, Integer::sum);

        if (unit.isSkillActive()) {
            tickActive(unit, skill);
            return;                      // ★ 阻回：激活期间连涨技力那一步都不走
        }

        int max = Math.max(0, skill.cost());
        int points = clamp(unit.getSkillPoints(), 0, max);
        if (skill.recoveryKind() == UnitBranch.RecoveryKind.AUTO
                && ticks % AUTO_RECOVERY_INTERVAL_TICKS == 0) {
            points += 1;
        }
        points = clamp(points, 0, max);

        if (max > 0 && points >= max) {
            // 触发条件：技力 ≥ 消耗 ⇒ 扣**满**消耗（PRTS「触发技能后…消耗相应的技力」）。
            //
            // ★ `max > 0` 这一半是**必须的**，不是防御性写法：表里「消耗技力 = 0」的有 8 条
            //   （正好是「初始触发」那 8 条被动技能）。按字面判 `SP >= cost` 就是 `0 >= 0`，
            //   于是**每个 tick 都会触发一次**；而它们的回复类型是 PASSIVE（技力根本不涨），
            //   这个条件会一直成立。反例断言「未激活且 SP < 消耗 ⇒ 不触发」要守住它。
            points = 0;
            writePoints(unit, points);
            cast(unit, slot, skill);
            return;
        }
        writePoints(unit, points);
    }

    /**
     * 激活窗口的推进（只有有限持续会递减）。
     *
     * <p>瞬发不会进激活期；永续与弹药类走 {@link PixelUnit#SKILL_FOREVER_TICKS} 哨兵
     * —— 弹药类的退出由 {@link #onAttack} 驱动（PRTS：「当下次普通攻击时弹药不高于0，
     * 则会退出技能状态」）。</p>
     */
    private static void tickActive(PixelUnit unit, UnitBranch.Skill skill) {
        int left = unit.getSkillActiveTicks();
        if (left == PixelUnit.SKILL_FOREVER_TICKS) {
            return;                      // 永续 / 弹药类：不按时间递减
        }
        if (left <= 0) {
            endSkill(unit, skill, EndReason.EXPIRED);
            return;
        }
        int next = left - 1;
        unit.setSkillActiveTicks(Math.max(0, next));
        if (next <= 0) {
            endSkill(unit, skill, EndReason.EXPIRED);
        }
    }

    // ------------------------------------------------------------------
    // 挂钩点（出手 / 受击 / 部署）
    //
    // ★ 只有这三个入口能改技力：攻击回复与受击回复各只有一条路径，不许在别处 +1。
    // ------------------------------------------------------------------

    /**
     * <b>出手</b>挂钩（攻击回复 +1；弹药类消耗弹药）——由 {@code PawnCombatManager.tickUnit}
     * 的「出手」那一步调用（**整场只有那一处**，它一次出手只回调一次；医疗分支的治疗出手走同一个点），
     * <b>医疗分支的「治疗出手」也算一次出手</b>（攻击回复类医疗靠它回技力）。
     *
     * <p>弹药类的顺序是「先消耗、再判退出」：PRTS 说「当下次普通攻击时弹药不高于0，
     * 则会退出技能状态」，也就是<b>这一击照样打出去</b>，打完才退出；
     * 弹量允许扣成负数（PRTS：「必定消耗相应数额…可能出现负数弹药」）。</p>
     *
     * <p><b>★ 判定的时间点（两种读法都写出来，别让它静默）</b>：本方法实现的是
     * 「<b>先扣弹药，扣完 ≤ 0 ⇒ 这次出手之后退出</b>」—— 于是「32 发 / 每击 2 发」的散射手
     * 在第 16 次出手（32→0）之后退出；不足一次消耗时照扣（1 发 − 2 发 = −1）也退出，
     * 退出时弹药被清成哨兵 {@link PixelUnit#SKILL_NO_AMMO}（所以「负弹药」这个中间值
     * 在外部观察不到）。另一种读法是 PRTS 那句话的字面：「下一次出手时若 ≤ 0 才退出」
     * （即第 17 次出手之后退出）—— 本轮取前者，因为 设计文档 与 A 段口径都写成
     * 「出手时弹量 ≤ 0 ⇒ 该次出手后退出技能」，且它不需要为「弹药已经打光」再留一次哑火出手。
     * 自验场景 skills 会把两个数都打出来。</p>
     */
    public static void onAttack(PixelUnit unit) {
        UnitBranch.Skill skill = skillOf(unit);
        if (skill == null) {
            return;
        }
        if (unit.isSkillActive()) {
            if (skill.durationKind() == UnitBranch.DurationKind.AMMO
                    && unit.getSkillAmmo() != PixelUnit.SKILL_NO_AMMO) {
                int cost = Math.max(1, skill.ammoPerAttack());
                int left = unit.getSkillAmmo() - cost;
                unit.setSkillAmmo(left);
                if (left <= 0) {
                    endSkill(unit, skill, EndReason.AMMO_OUT);
                }
            }
            return;                      // 阻回：激活期间的出手不回技力
        }
        if (skill.recoveryKind() == UnitBranch.RecoveryKind.ON_ATTACK) {
            addPoints(unit, skill, 1);
        }
    }

    /**
     * <b>受击</b>挂钩（受击回复 +1）——由 {@link PixelUnit} 的受伤路径
     * （{@code PixelUnit#hurt}，**唯一一处**）调用，只在 {@code super.hurt(...)} 真的扣到血时转过来。
     * 表里只有 2 条受击回复（驭法铁卫技能1 / 不屈者技能3），别漏；同 设计文档/9.4。
     */
    public static void onHurt(PixelUnit unit) {
        UnitBranch.Skill skill = skillOf(unit);
        if (skill == null || unit.isSkillActive()) {
            return;                      // 阻回
        }
        if (skill.recoveryKind() == UnitBranch.RecoveryKind.ON_HURT) {
            addPoints(unit, skill, 1);
        }
    }

    /**
     * <b>部署</b>初始化：技力 = {@code clamp(初始技力, 0, 消耗)}，并清掉激活与弹药。
     *
     * <p>PRTS：「携带者入场时，技能会预先充入初始技力」。表里那 12 条「初动 ≥ 消耗」的分支
     * （尖兵技能1、情报官技能1…）因此<b>落地第一 tick 就会放一次</b>。</p>
     *
     * <p>槽位为 0（没装技能）时按设计口径装**技能 1**（「棋子放下自动装技能 1」）。
     *
     * <p>★ 接线：由 {@code PixelUnit#assign(BranchDef)} 调用 —— <b>整场只有那一处</b>，
     * 因为棋子的三条生成路径（刷怪蛋的物品 NBT → 读档时走 assign、{@code api/Pawns.spawn}、
     * 自验台的 {@code spawnPawn}）全部汇到 {@code assign}。在调用点各写一份「摆完初始化技能位」
     * 迟早会漂移（本项目「一条判据一处实现」的纪律）。</p>
     */
    public static void onDeploy(PixelUnit unit) {
        if (unit.getSkillSlot() == PixelUnit.NO_SKILL && skillCountOf(unit.getBranch()) > 0) {
            unit.setSkillSlot(1);
        }
        UnitBranch.Skill skill = skillOf(unit);
        unit.setSkillActiveTicks(PixelUnit.SKILL_NOT_ACTIVE);
        unit.setSkillAmmo(PixelUnit.SKILL_NO_AMMO);
        if (skill == null) {
            unit.setSkillPoints(0);
            return;
        }
        unit.setSkillPoints(clamp(skill.initialSp(), 0, Math.max(0, skill.cost())));
    }

    /** 涨技力并夹到上限（攻击 / 受击回复共用这一处）。 */
    private static void addPoints(PixelUnit unit, UnitBranch.Skill skill, int delta) {
        int max = Math.max(0, skill.cost());
        writePoints(unit, clamp(unit.getSkillPoints() + delta, 0, max));
    }

    /**
     * 写回技力。
     *
     * <p>★ 「值没变就不写」不是抠性能：默认状态下（槽位 0 或技力恒 0）这一行一次同步数据
     * 都不会写 —— 写 0 到已经是 0 的字段上，最轻也要多一次比较，最坏会白白把实体标脏。</p>
     */
    private static void writePoints(PixelUnit unit, int points) {
        if (unit.getSkillPoints() != points) {
            unit.setSkillPoints(points);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // 触发 / 结束（占位表现 + 一行日志）
    // ------------------------------------------------------------------

    /**
     * 触发一次技能：按 {@code DurationKind} 进激活期（或瞬发结束），再放占位表现。
     *
     * <p><b>技能效果本轮不做</b>（设计文档）：这里只改「技能自己的状态」，
     * 不碰攻击力/间隔/目标数/防御/治疗。将来要改出手形式就调
     * {@code unit.setAttackMethodOverride(...)}，技能结束再 {@code setOverride(null)} 还原。</p>
     */
    private static void cast(PixelUnit unit, int slot, UnitBranch.Skill skill) {
        CAST_COUNT.merge(unit, 1, Integer::sum);
        EndReason reason = EndReason.NONE;
        switch (skill.durationKind()) {
            case INSTANT -> reason = EndReason.INSTANT;      // 不进激活期
            case TIMED -> unit.setSkillActiveTicks(skill.durationTicks());
            case INFINITE, AMMO -> unit.setSkillActiveTicks(PixelUnit.SKILL_FOREVER_TICKS);
            case PASSIVE -> {
                return;                                     // 走不到（tickUnit 已挡掉）
            }
        }
        if (skill.durationKind() == UnitBranch.DurationKind.AMMO) {
            unit.setSkillAmmo(Math.max(0, skill.ammo()));
        } else {
            unit.setSkillAmmo(PixelUnit.SKILL_NO_AMMO);
        }
        playPlaceholderEffect(unit, slot);
        LAST_END.put(unit, reason);
        GuardianProtocol.LOGGER.info("[{}] 技能触发：{} 技能位={} 技能名={}｜持续={}｜结束原因={}",
                GuardianProtocol.MODID, unit.getUUID(), slot, skill.name(),
                skill.durationKind(), reason.displayName());
    }

    /**
     * 结束激活（时间到 / 弹药耗尽）。
     *
     * <p>它只清「激活剩余」与「弹药」，<b>不动技力</b>：技力在触发那一刻已经扣满消耗了。</p>
     */
    private static void endSkill(PixelUnit unit, UnitBranch.Skill skill, EndReason reason) {
        unit.setSkillActiveTicks(PixelUnit.SKILL_NOT_ACTIVE);
        unit.setSkillAmmo(PixelUnit.SKILL_NO_AMMO);
        LAST_END.put(unit, reason);
        GuardianProtocol.LOGGER.info("[{}] 技能结束：{} 技能位={} 技能名={}｜结束原因={}",
                GuardianProtocol.MODID, unit.getUUID(), unit.getSkillSlot(), skill.name(),
                reason.displayName());
    }

    /**
     * 占位表现：一声轻响 + 少量粒子（第三个槽位换一套音效/粒子，便于一眼看出放的是哪个槽）。
     *
     * <p><b>为什么只有音效与粒子</b>：技能真正的效果本轮不做（设计口径）。
     * 这里刻意只保留「看得见 / 听得见 / 日志里有」三件事，让自验与实机都能确认技能确实放了。</p>
     *
     * <p>音效只用<b>确认是裸 {@code SoundEvent}</b> 的三个（本类用到的三个 {@code SoundEvents}
     * 字段都当场核对过类型）：1.20.1 里 {@code SoundEvents.NOTE_BLOCK_*} 是
     * {@code Holder.Reference<SoundEvent>}（要 {@code .value()} 才是本体），而
     * {@code Level#playSound} 收的是裸 {@code SoundEvent}。</p>
     */
    private static void playPlaceholderEffect(PixelUnit unit, int slot) {
        if (!(unit.level() instanceof ServerLevel level)) {
            // 客户端不该走到这里（onServerTick 已经挡掉了）；挡第二道只是防御性写法
            return;
        }
        if (slot == 3) {
            level.playSound(null, unit.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.NEUTRAL, 0.6F, 0.8F);
            level.sendParticles(ParticleTypes.END_ROD,
                    unit.getX(), unit.getY() + 1.0D, unit.getZ(), 10, 0.4D, 0.5D, 0.4D, 0.0D);
        } else if (slot == 2) {
            level.playSound(null, unit.blockPosition(), SoundEvents.ARROW_HIT,
                    SoundSource.NEUTRAL, 0.5F, 1.2F);
            level.sendParticles(ParticleTypes.ENCHANTED_HIT,
                    unit.getX(), unit.getY() + 1.0D, unit.getZ(), 8, 0.35D, 0.45D, 0.35D, 0.05D);
        } else {
            level.playSound(null, unit.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.NEUTRAL, 0.5F, 1.5F);
            level.sendParticles(ParticleTypes.CRIT,
                    unit.getX(), unit.getY() + 1.0D, unit.getZ(), 6, 0.3D, 0.4D, 0.3D, 0.02D);
        }
    }

    // ------------------------------------------------------------------
    // 数据查询（槽位 → 技能）
    // ------------------------------------------------------------------

    /**
     * 某分支第 {@code slot} 个技能（1 起）；没有/占位条目返回 {@code null}。
     *
     * <p>数据包分支（{@code BranchRegistry} 里那些）<b>没有技能表</b> —— 返回 null，
     * 不是「给个默认技能」：静默给默认值会让数据包分支凭空获得技能。</p>
     */
    @Nullable
    public static UnitBranch.Skill skillOf(BranchDef branch, int slot) {
        if (branch == null || slot <= 0) {
            return null;
        }
        Optional<UnitBranch> builtin = branch.builtin();
        if (builtin.isEmpty()) {
            return null;
        }
        UnitBranch.Skill skill = builtin.get().skill(slot - 1);
        return skill != null && skill.present() ? skill : null;
    }

    /** 这颗棋子当前槽位对应的技能（没有则 null）。 */
    @Nullable
    public static UnitBranch.Skill skillOf(PixelUnit unit) {
        return unit == null ? null : skillOf(unit.getBranch(), unit.getSkillSlot());
    }

    /**
     * 这个分支**实际有几个**技能（2 或 3；数据包分支与查不到的分支是 0）。
     *
     * <p>表里有 4 个分支只有 2 条技能（近卫·佣兵 / 近卫·本源近卫 / 狙击·裂空炮手 / 辅助·游击手），
     * 生成器会把第 3 条补成空条目 —— {@code Skill.present()} 就是「表里真有这条」的判据。</p>
     */
    public static int skillCountOf(BranchDef branch) {
        if (branch == null) {
            return 0;
        }
        Optional<UnitBranch> builtin = branch.builtin();
        if (builtin.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UnitBranch.Skill skill : builtin.get().skills()) {
            if (skill.present()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 槽位对应技能的<b>技力上限</b>（= 该技能的消耗技力；没有这条技能返回 0）。
     *
     * <p>PRTS：「技力的最大储存量等同于该技能的技力需求」。</p>
     */
    public static int maxPointsOf(BranchDef branch, int slot) {
        UnitBranch.Skill skill = skillOf(branch, slot);
        return skill == null ? 0 : Math.max(0, skill.cost());
    }

    /**
     * 槽位能不能用：{@code null} = 可以；否则返回一句**中文的拒绝理由**。
     *
     * <p>这是「越界 / 超过本分支实际技能数」的<b>唯一一处判据</b> —— 切槽命令与
     * {@code api.Skills} 门面都读它，不许各自再写一份「夹取」：
     * 静默夹取会把「这个分支只有 2 个技能」这件事藏起来（设计要求明确拒绝并说理由）。</p>
     */
    @Nullable
    public static String slotRejectReason(BranchDef branch, int slot) {
        if (branch == null) {
            return "棋子没有可识别的分支（分支 id 认不出来）";
        }
        if (slot < PixelUnit.NO_SKILL || slot > PixelUnit.MAX_SKILL_SLOT) {
            return "技能位只能是 " + PixelUnit.NO_SKILL + "（卸下）或 1~"
                    + PixelUnit.MAX_SKILL_SLOT + "，传进来的是 " + slot;
        }
        if (slot == PixelUnit.NO_SKILL) {
            return null;                 // 0 = 卸下技能，合法
        }
        int count = skillCountOf(branch);
        if (count == 0) {
            return "分支「" + branch.branchName() + "」没有技能数据（数据包分支没有技能表）";
        }
        if (slot > count) {
            return "分支「" + branch.branchName() + "」只有 " + count + " 个技能，装不了 " + slot + " 号";
        }
        return null;
    }

    /** 这颗棋子是不是处于激活期（含永续与弹药类）。 */
    public static boolean isActive(PixelUnit unit) {
        return unit != null && unit.isSkillActive();
    }

    /** 最近一次技能结束的原因（没结束过就是 {@link EndReason#NONE}）。 */
    public static EndReason endReasonOf(PixelUnit unit) {
        return LAST_END.getOrDefault(unit, EndReason.NONE);
    }

    // ------------------------------------------------------------------
    // 调试 / 自验口子
    // ------------------------------------------------------------------

    /**
     * 装技能（调试 / 自验用）：设槽位并直接把技力写成 {@code points}（激活与弹药一并清掉）。
     *
     * <p>槽位非法时<b>什么都不改</b>，返回拒绝理由（调用方负责说出来）——
     * 不静默夹取，理由见 {@link #slotRejectReason}。</p>
     *
     * <p>典型用法：{@code setSlot(unit, 1, maxPointsOf(branch, 1))} = 「装 1 号技能且技力已满」，
     * 于是下一个 tick 就能观察到一次触发。</p>
     *
     * @return 空串 = 成功；否则是拒绝理由
     */
    public static String setSlot(PixelUnit unit, int slot, int points) {
        String why = slotRejectReason(unit.getBranch(), slot);
        if (why != null) {
            GuardianProtocol.LOGGER.warn("[{}] 拒绝设置技能位：{}（棋子 {}）",
                    GuardianProtocol.MODID, why, unit.getUUID());
            return why;
        }
        unit.setSkillSlot(slot);
        int max = maxPointsOf(unit.getBranch(), slot);
        unit.setSkillPoints(max <= 0 ? PixelUnit.clampSkillPoints(points)
                : clamp(points, 0, max));
        unit.setSkillActiveTicks(PixelUnit.SKILL_NOT_ACTIVE);
        unit.setSkillAmmo(PixelUnit.SKILL_NO_AMMO);
        LAST_END.remove(unit);
        return "";
    }

    /**
     * 这颗棋子放过几次技能（自验用，只读）。
     *
     * <p><b>计数会随棋子的移除被清掉</b>（见 {@link #prune()}）：这是为了不让静态表
     * 长期持有已经不在世界上的实体。所以自验里要读这个数，请在棋子<b>还在场上时</b>读；
     * 场景之间要互不干扰就调 {@link #resetDebugCounters()}。</p>
     */
    public static int debugCastCount(PixelUnit unit) {
        return CAST_COUNT.getOrDefault(unit, 0);
    }

    /** 清空计数器与节拍表（自验场景之间互不干扰）。 */
    public static void resetDebugCounters() {
        CAST_COUNT.clear();
        TICK_COUNTER.clear();
        LAST_END.clear();
    }

    /**
     * 一行描述，排查 / 报告用。
     *
     * <p>存在的理由与 {@code PawnCombatManager.debugTrace} 一样：「技能没放出来」可能是
     * 「槽位是 0」「技力还没满」「这个分支只有 2 个技能」，光看结果猜不出来。
     * 这里把槽位、技能名、技力/上限、激活剩余、弹药、最近结束原因、实际出手形式、
     * 放过几次一次列全。</p>
     *
     * <p>注意它<b>不发任何包、不改任何状态</b>，可以在客户端读
     * （槽位/技力/激活/弹药/覆盖都在同步数据里）。</p>
     */
    public static String describe(PixelUnit unit) {
        BranchDef branch = unit.getBranch();
        int slot = unit.getSkillSlot();
        UnitBranch.Skill skill = skillOf(unit);
        StringBuilder sb = new StringBuilder();
        sb.append(branch.branchName())
                .append(" 技能位=").append(slot);
        if (slot > PixelUnit.NO_SKILL) {
            sb.append("（").append(skill == null ? "本分支没有这个技能位" : skill.name()).append("）");
        }
        sb.append(" 技力=").append(unit.getSkillPoints())
                .append('/').append(maxPointsOf(branch, slot));
        int active = unit.getSkillActiveTicks();
        sb.append(" 激活=");
        if (active == PixelUnit.SKILL_FOREVER_TICKS) {
            sb.append("永续/弹药类");
        } else if (active < 0) {
            sb.append("未激活");
        } else {
            sb.append("剩余 ").append(active).append(" tick");
        }
        sb.append(" 弹药=").append(unit.getSkillAmmo() == PixelUnit.SKILL_NO_AMMO
                ? "不适用" : unit.getSkillAmmo() + " 发");
        sb.append(" 最近结束=").append(endReasonOf(unit).displayName());
        sb.append(" 实际出手=").append(unit.effectiveAttackMethod());
        if (unit.getAttackMethodOverride() != null) {
            sb.append("（技能覆盖；分支默认 ").append(branch.attackMethod()).append("）");
        }
        sb.append(" 技能数=").append(skillCountOf(branch));
        sb.append(" 已放技能=").append(debugCastCount(unit)).append(" 次");
        return sb.toString();
    }

    /**
     * 清掉「已经不在世界上的棋子」的计数/节拍/结束原因，避免静态 map 长期持有失效引用。
     *
     * <p>判据只能是<b>「棋子还在不在」</b>，不能是「属不属于我这一份」——
     * {@code PawnCombatManager.prune()} 那里有一次实打实的教训（按维度清理把别的维度
     * 的状态清掉了）。所以这里用 {@code isRemoved()}，且每个服务端 tick 只调一次。</p>
     */
    private static void prune() {
        CAST_COUNT.keySet().removeIf(PixelUnit::isRemoved);
        TICK_COUNTER.keySet().removeIf(PixelUnit::isRemoved);
        LAST_END.keySet().removeIf(PixelUnit::isRemoved);
    }
}
