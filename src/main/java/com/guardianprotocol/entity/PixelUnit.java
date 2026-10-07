package com.guardianprotocol.entity;

import com.guardianprotocol.GuardianConfig;
import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.PawnFacingMode;
import com.guardianprotocol.combat.Skills;
import com.guardianprotocol.data.AttackRange;
import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.BranchRegistry;
import com.guardianprotocol.data.BuiltinBranch;
import com.guardianprotocol.data.PieceFacing;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.data.UnitClass;
import com.guardianprotocol.menu.PawnFacingMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2D 像素小人棋子。
 *
 * <p>行为对齐原版<b>盔甲架</b>的「摆件」形态：放下之后完全不动、不游荡、不被推动。
 * 它是塔防里的可部署单位，数值来自两张参考表，见 {@link UnitBranch}。</p>
 *
 * <h3>为什么还继承 PathfinderMob</h3>
 * <p>不直接继承 {@code Entity} 是图省事：这样能白拿生命值 / 伤害 / 命名牌 / 阴影 /
 * 死亡结算等一整套原版逻辑。而「完全不动」是靠下面这些手段实现的：</p>
 * <ul>
 *     <li>构造函数里<b>清空全部 AI goal</b>，不留任何移动行为；</li>
 *     <li>{@code setNoAi(true)} 彻底关掉 AI 心跳；</li>
 *     <li>{@code setNoGravity(true)} 不受重力；</li>
 *     <li>重写 {@link #push(Entity)} / {@link #push(double, double, double)} /
 *         {@link #knockback(double, double, double)} / {@link #setDeltaMovement(Vec3)}
 *         四个入口并<b>全部拦掉</b> —— 活塞推、爆炸冲、生物挤、水流冲因此统统无效；</li>
 *     <li>重写 {@link #setYRot(float)} / {@link #setYHeadRot(float)} / {@link #setXRot(float)}
 *         锁死<b>渲染用</b>的旋转（贴图是公告板，转了只会让碰撞箱莫名倾斜）。</li>
 * </ul>
 *
 * <h3>「朝向」是独立于旋转的字段</h3>
 * <p>棋子的朝向（东南西北）存在 {@link #getFacing()} 里，<b>不是</b> {@code getYRot()}。
 * 原因：{@code getYRot()} 被上面那条锁死为 0，用不了。朝向决定两件事，两者都随它旋转：</p>
 * <ul>
 *     <li>攻击范围格子 —— {@link #worldCells()}；</li>
 *     <li>阻挡时敌人被按在哪一侧 —— {@code PawnCombatManager#blockAnchor}。</li>
 * </ul>
 *
 * <p>注意：它仍然是实体，所以 {@code /kill}、虚空伤害、{@code /tp} 依然有效 ——
 * 这是刻意留的调试与回收口子。</p>
 */
public class PixelUnit extends PathfinderMob implements MenuProvider, OwnableEntity {

    /** 同步：职业序号（决定贴图）。 */
    private static final EntityDataAccessor<Integer> DATA_UNIT_CLASS =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 同步：分支 id 字符串。
     *
     * <p><b>为什么是字符串而不是序号</b>：分支可以由数据包新增/覆盖，
     * 序号会随「表里多一行」而漂移；id（{@code guardian_protocol:sniper_marksman}）
     * 才是稳定身份。老存档里存的是枚举名（{@code SNIPER_MARKSMAN}），
     * 读取时由 {@link BranchRegistry#byKey(String)} 统一兼容。</p>
     */
    private static final EntityDataAccessor<String> DATA_BRANCH =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.STRING);

    /**
     * 同步：棋子朝向（{@link PieceFacing} 的序号）。
     *
     * <p>必须<b>同步</b>而不是只存服务端：攻击范围预览是客户端画的，
     * 客户端要按同一个朝向算格子，否则预览与实际打击范围会错开 90°。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_FACING =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 存档里朝向的 NBT 键。
     *
     * <p>刻意与实体自己的 {@code Rotation} 分开：那个字段被本类锁死为 0
     * （贴图是公告板，转了也看不出来），朝向是给攻击范围与阻挡用的独立语义。</p>
     */
    public static final String TAG_FACING = "Facing";

    /**
     * 同步：棋子阶位（1~6）。
     *
     * <p>「阶位」是自走棋那一层的概念：商店按阶位出货、同分支同阶位三个可以三合一。
     * 它<b>不影响</b>本 mod 的战斗数值（数值只由分支决定）—— 这一层刻意只做数据与表现，
     * 让后续的商店/羁绊项目直接读，而不必再改实体。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 同步：是否已精炼（三合一的终点）。
     *
     * <p>表现层据此叠一层金色光效（见 {@code PixelUnitRenderer}）；数据层由商店项目使用。</p>
     */
    private static final EntityDataAccessor<Boolean> DATA_REFINED =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.BOOLEAN);

    /**
     * 同步：技能槽位（{@link #NO_SKILL}=0 表示没有技能，1~{@link #MAX_SKILL_SLOT} 对应三个技能槽）。
     *
     * <p><b>为什么进同步数据而不是只存服务端</b>：技能 HUD 与「这个棋子有没有技能」的判断
     * 是客户端也要读的；将来做客户端预测（按槽位预告技力条）同样要读它。
     * 与 {@link #DATA_TIER} 同一个口径：<b>数据层先放好，技能内容留给后续项目填</b>。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_SKILL_SLOT =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 同步：**技力**（Skill Points，{@code >= 0}）。
     *
     * <p>口径（2026-10 第四轮，PRTS「技能」页）：上限 = 该技能的消耗技力、
     * 自动回复每 20 tick +1、攻击回复每次出手 +1、受击回复每次被攻击 +1。
     * <b>上限的判据在 {@code combat.Skills} 一处</b> —— 实体这边只保证「不为负」
     * （它不知道槽位对应的技能是什么，也不该知道）。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_SKILL_POINTS =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 同步：**激活剩余 tick**（{@link #SKILL_NOT_ACTIVE} = 未激活；
     * {@code >= 0} = 还剩多少 tick；{@link #SKILL_FOREVER_TICKS} = 永续激活）。
     *
     * <p>为什么用「剩余」而不是「开始时刻」：自验台**不推进游戏刻**
     * （{@code getGameTime()} 冻住，踩坑记录），按绝对时刻算的技能在自验里永远不结束。
     * 剩余 tick 由 {@code combat.Skills} 每服务端 tick 减 1，与本 mod 自己的节拍一致。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_SKILL_ACTIVE_TICKS =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 同步：**剩余弹药**（{@link #SKILL_NO_AMMO} = 不适用；{@code >= 0} = 还剩几发）。
     *
     * <p>只有「持续时间类型 = 有限持续(弹药耗尽结束)」的技能用它（表里 18 条）。
     * 每击消耗与「弹量 ≤ 0 之后退出技能」的判据都在 {@code combat.Skills}。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_SKILL_AMMO =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /**
     * 同步：出手形式覆盖（{@link UnitBranch.AttackMethod} 的序号，{@code -1} = 没有覆盖）。
     *
     * <p><b>为什么用 int 而不是枚举</b>：{@code SynchedEntityData} 只认原版注册过的序列化器，
     * 枚举没有对应的 {@code EntityDataSerializer}；序号 + 越界兜底（见
     * {@link #getAttackMethodOverride()}）是本工程对枚举同步的一贯做法（同 {@code DATA_FACING}）。</p>
     *
     * <p>哨兵值刻意用 {@code -1} 而不是 {@code 0}：{@code 0} 是 {@code MELEE} 的合法序号，
     * 用它当「没有覆盖」会让「手动改成近战」与「不覆盖」两件事无法区分。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_ATTACK_METHOD_OVERRIDE =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /** 技能槽位：0 = 没有技能。 */
    public static final int NO_SKILL = 0;

    /** 技能槽位上限（1..3 是三个技能槽）。 */
    public static final int MAX_SKILL_SLOT = 3;

    /** 「技能未激活」的哨兵值（{@link #DATA_SKILL_ACTIVE_TICKS}）。 */
    public static final int SKILL_NOT_ACTIVE = -1;

    /** 「永续激活」的哨兵值：激活剩余走这个值时**不按时间递减**。 */
    public static final int SKILL_FOREVER_TICKS = Integer.MAX_VALUE;

    /** 「弹药不适用」的哨兵值（{@link #DATA_SKILL_AMMO}）。 */
    public static final int SKILL_NO_AMMO = -1;

    /** 「出手形式没有覆盖」的哨兵值（见 {@link #DATA_ATTACK_METHOD_OVERRIDE} 的注释）。 */
    private static final int NO_ATTACK_METHOD_OVERRIDE = -1;

    /**
     * 解放者 ramp：<b>技能未激活期间</b>累积的 tick 数（0 ~ {@link #LIBERATOR_RAMP_MAX_TICKS}）。
     *
     * <h3>★ 为什么它必须**同步**（2026-10-05 实测反馈后改的）</h3>
     * <p>设计原话：「解放者的效果从面板上看不到啊，能让面板显示吗？」—— 朝向界面是**客户端**画的，
     * 而这个字段原来是个普通的服务端字段（不同步）⇒ 客户端读到的永远是 0，面板上只能显示 ×1.00。
     * 所以它改成 {@code entityData}（与技能那几个字段同一套做法）。</p>
     *
     * <h3>为什么归到「攻击力」而不是「伤害乘区」</h3>
     * <p>设计口径（原话）：「解放者是**攻击力**逐渐提升至 200%，它也没说是伤害啊」——
     * 原文写的是「技能未开启时 40 秒内<b>攻击力</b>逐渐提升至最高 +200%」，
     * 所以它是**攻击力倍率**（面板上看得见、将来也会影响一切按攻击力算的东西），
     * 与教官/领主那种「造成**伤害**」的乘区**不是一伙**。分类在
     * {@code combat/DamageMods#isAttackSide}。</p>
     *
     * <h3>什么时候加、什么时候归零</h3>
     * <p>由 {@code PawnCombatManager#tickUnit} 每 tick 维护：<b>技能激活时直接归零</b>，
     * 否则 +1（夹到上限）。归零发生在技能**期间**，所以技能一结束它天然就是 0
     * —— 这正是精二特性原文的「技能结束时重置攻击力」。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_RAMP_TICKS =
            SynchedEntityData.defineId(PixelUnit.class, EntityDataSerializers.INT);

    /** 解放者 ramp 的 NBT 键（「技能未开启时累积了多少 tick」）。 */
    public static final String TAG_RAMP_TICKS = "RampTicks";

    /** 解放者 ramp 的最大 tick 数（40 秒；数值出处见 {@code combat/DamageMods}）。 */
    public static final int LIBERATOR_RAMP_MAX_TICKS =
            com.guardianprotocol.combat.DamageMods.liberatorRampMaxTicks();

    /** 解放者 ramp 的当前 tick 数（**同步字段**：客户端要拿它画面板）。 */
    public int getRampTicks() {
        return this.entityData.get(DATA_RAMP_TICKS);
    }

    /** 设 ramp tick 数（自动夹到 0 ~ {@link #LIBERATOR_RAMP_MAX_TICKS}）。 */
    public void setRampTicks(int ticks) {
        this.entityData.set(DATA_RAMP_TICKS,
                Math.max(0, Math.min(LIBERATOR_RAMP_MAX_TICKS, ticks)));
    }

    // ------------------------------------------------------------------
    // 攻击力倍率（★ 2026-10-05：解放者的 ramp 归到「攻击力」这一侧）
    // ------------------------------------------------------------------

    /**
     * 当前**攻击力倍率**（默认 1.0；目前只有解放者的 ramp 会 ≠ 1）。
     *
     * <p>判据只有一处：{@link com.guardianprotocol.combat.DamageMods#attackMultiplier}。
     * 谁读它：① {@code PawnCombatManager#attack} 算这一击的伤害时；
     * ② 朝向界面的「攻击力」那一行 —— 所以面板上能**看着它爬**。</p>
     */
    public double attackMultiplier() {
        return com.guardianprotocol.combat.DamageMods.attackMultiplier(
                getBranch().damageMod(), getRampTicks());
    }

    /**
     * 当前**实际攻击力** = 面板攻击力 × {@link #attackMultiplier()}。
     *
     * <p>★ 这是「攻击力」这个词在运行时的唯一口径：伤害结算与界面显示都读它，
     * 不各算一遍（否则迟早出现「面板显示 ×3、打出来还是 ×1」这种对不上的状态）。</p>
     *
     * <p><b>数据包分支</b>：它们的 {@code damageMod()} 恒为空 ⇒ 倍率恒 1.0 ⇒ 退回面板值。</p>
     */
    public double effectiveAttackDamage() {
        return getBranch().attackDamage() * attackMultiplier();
    }

    /** 阶位取值范围与默认值（默认 1 阶 = 商店最初级的货）。 */
    public static final int MIN_TIER = 1;
    public static final int MAX_TIER = 6;
    public static final int DEFAULT_TIER = 1;

    /** 存档里阶位的 NBT 键。 */
    public static final String TAG_TIER = "Tier";

    /** 存档里「是否精炼」的 NBT 键。 */
    public static final String TAG_REFINED = "Refined";

    /** 存档里技能槽位的 NBT 键（int；老存档没有这个键 → 用默认值 {@link #NO_SKILL}）。 */
    public static final String TAG_SKILL_SLOT = "SkillSlot";

    /** 存档里技力的 NBT 键（int，{@code >= 0}）。 */
    public static final String TAG_SKILL_POINTS = "SkillPoints";

    /** 存档里技能激活剩余的 NBT 键（int，{@code -1} = 未激活）。 */
    public static final String TAG_SKILL_ACTIVE_TICKS = "SkillActiveTicks";

    /** 存档里剩余弹药的 NBT 键（int，{@code <0} = 不适用）。 */
    public static final String TAG_SKILL_AMMO = "SkillAmmo";

    /**
     * 存档里「出手形式覆盖」的 NBT 键（string，<b>空串 = 没有覆盖</b>）。
     *
     * <p>存枚举名而不是序号：序号会随「枚举里插一个常量」而漂移，
     * 而名字（{@code MELEE} / {@code PROJECTILE}）是稳定身份 ——
     * 与 {@code Branch} 存 id 字符串同一个理由（见 {@link #DATA_BRANCH} 的注释）。</p>
     */
    public static final String TAG_ATTACK_METHOD_OVERRIDE = "AttackMethodOverride";

    /**
     * 归属（摆放者）的 NBT 键：**存 UUID 字符串**，另存一个显示名便于报告。
     *
     * <p>与其它字段同一个理由：UUID 是稳定身份，显示名只是给人看的（玩家改名不影响归属）。
     * 老存档没有这两个键 ⇒ 归属为空 = 无主棋子（无主棋子之间仍算同队，见 PawnTeams）。</p>
     */
    public static final String TAG_OWNER = "Owner";

    /** 归属者的显示名（只为报告/排查，不参与任何判定）。 */
    public static final String TAG_OWNER_NAME = "OwnerName";

    /**
     * 命名牌是否**临时**显示（由准星是否对准决定）。
     *
     * <p>设计要求：头顶的「职业名 + 血量/上限」平时不要显示，太挡视线；
     * 只有**准星对准某个棋子**时才显示它自己的那一块。
     * 由 {@code AttackRangePreview} 在客户端驱动（它本来就知道准星对着谁）。</p>
     *
     * <p>刻意做成**纯客户端字段**、不走 {@code SynchedEntityData}：
     * 这是「我看着谁」的表现层状态，与服务端无关，也不需要同步给别的玩家
     * （每个玩家的准星各看各的）。也不必用 {@code setCustomNameVisible()} 那套
     * 同步字段 —— 那个会被服务端下发的值覆盖掉，白白多一层时序问题。</p>
     */
    private boolean nameRevealed;

    /** 设置命名牌是否临时显示（客户端调用）。 */
    public void setNameRevealed(boolean revealed) {
        this.nameRevealed = revealed;
    }

    /**
     * 职业是否被「显式指定」过。
     *
     * <p>只用于 {@code finalizeSpawn} 判断要不要随机，属服务端临时状态：
     * 不参与同步、也不存档（跨存档由 {@link #readAdditionalSaveData} 重新置位）。</p>
     */
    private boolean classAssigned;

    /**
     * 所有已加载的棋子的活跃表。
     *
     * <p>与保护目标同样的思路：原版没有「按实体类型查所有实例」的现成 API，
     * 让实体自己在构造时登记、{@link #remove(RemovalReason)} 时注销，管理器直接读这张表。</p>
     */
    private static final List<PixelUnit> ACTIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 当前活跃棋子（只读视图）。 */
    public static List<PixelUnit> activeUnits() {
        return java.util.Collections.unmodifiableList(ACTIVE);
    }

    public PixelUnit(EntityType<? extends PixelUnit> type, Level level) {
        super(type, level);

        // ① 清空 AI：这是「不游荡」的第一道闸。
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);

        // ② 彻底关掉 AI 心跳与重力
        this.setNoAi(true);
        this.setNoGravity(true);

        // ③ 棋子是战术资产，不因距离被清除
        this.setPersistenceRequired();

        // ④ 锁死渲染用的旋转（公告板贴图看不出旋转，转了只会让碰撞箱莫名倾斜）。
        //    棋子真正的「朝向」是独立的 Facing 字段，见下面 getFacing()。
        super.setYRot(0.0F);
        super.setXRot(0.0F);
        super.setYHeadRot(0.0F);

        // ⑤ 登记进活跃表，供 PawnCombatManager 遍历
        ACTIVE.add(this);
    }

    /**
     * 被移除（/kill、区块卸载、世界关闭）时注销，避免静态表持有失效引用。
     *
     * <p><b>踩过的坑</b>：1.20.1 的 {@code Entity} <b>没有无参的 {@code setRemoved()}</b>
     * （那是 {@code BlockEntity} 的方法）。实体的移除钩子是
     * {@code remove(Entity.RemovalReason)}。写错会得到两条互相矛盾的报错：
     * 「方法不会覆盖或实现超类型的方法」+「无法将 Entity 中的方法 setRemoved 应用到给定类型」。</p>
     */
    @Override
    public void remove(RemovalReason reason) {
        ACTIVE.remove(this);
        super.remove(reason);
    }

    // ------------------------------------------------------------------
    // 注册与属性
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_UNIT_CLASS, UnitClass.VANGUARD.ordinal());
        this.entityData.define(DATA_BRANCH, BuiltinBranch.idOf(DEFAULT_BRANCH).toString());
        // 默认朝正东：方案1 的初始朝向就是它，方案2 会在放下时被玩家朝向覆写。
        this.entityData.define(DATA_FACING, PieceFacing.EAST.ordinal());
        // 阶位默认 1 阶、未精炼：商店还没做，先让所有棋子都是「最初级的货」。
        this.entityData.define(DATA_TIER, DEFAULT_TIER);
        this.entityData.define(DATA_REFINED, false);
        // 技能位默认「没有技能 / 技力 0 / 未激活 / 弹药不适用」：
        // 于是「技能系统还没接上」时所有棋子的行为与加这些字段之前**完全一致**，
        // 不会因为多了一个默认槽位就凭空开始放技能。
        this.entityData.define(DATA_SKILL_SLOT, NO_SKILL);
        this.entityData.define(DATA_SKILL_POINTS, 0);
        this.entityData.define(DATA_SKILL_ACTIVE_TICKS, SKILL_NOT_ACTIVE);
        this.entityData.define(DATA_SKILL_AMMO, SKILL_NO_AMMO);
        // 解放者 ramp（同步：朝向界面的「攻击力」那一行要拿它算实时倍率）
        this.entityData.define(DATA_RAMP_TICKS, 0);
        this.entityData.define(DATA_ATTACK_METHOD_OVERRIDE, NO_ATTACK_METHOD_OVERRIDE);
    }

    /**
     * 基础属性。
     *
     * <p>这里给的是**默认值**，实际每个棋子会由 {@link #applyBranchAttributes()}
     * 按分支覆写成 {@code CombatStats} 的数值（职业基础模板 + 分支增减）。
     * 之所以保留一个非零默认，是为了让「还没指定分支」的棋子在那一瞬间也不会是 0 血。</p>
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)        // 不动，速度给 0
                .add(Attributes.ATTACK_DAMAGE, 5.0D)
                .add(Attributes.ARMOR, 3.0D)                 // 会被 applyBranchAttributes 覆写
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 0.0D);
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    public UnitClass getUnitClass() {
        UnitClass v = UnitClass.byOrdinal(this.entityData.get(DATA_UNIT_CLASS));
        return v == null ? UnitClass.VANGUARD : v;
    }

    public void setUnitClass(UnitClass unitClass) {
        this.entityData.set(DATA_UNIT_CLASS, unitClass.ordinal());
    }

    /** 默认分支（还没被指定时用它兜底）。 */
    private static final UnitBranch DEFAULT_BRANCH = UnitBranch.VANGUARD_CHARGER;

    // 解析缓存：getBranch() 在战斗里是「每 tick 每棋子」都要调的，
    // 而 byKey 要做字符串解析/遍历枚举（老存档存的是枚举名）。缓存命中时只比两个字符串。
    private BranchDef cachedBranch;
    private String cachedBranchKey;
    private int cachedRevision = -1;

    public BranchDef getBranch() {
        String raw = this.entityData.get(DATA_BRANCH);
        int revision = BranchRegistry.revision();
        if (this.cachedBranch != null && this.cachedRevision == revision
                && raw.equals(this.cachedBranchKey)) {
            return this.cachedBranch;
        }
        BranchDef def = BranchRegistry.byKey(raw);
        if (def == null) {
            // 兜底一：客户端没装同一个数据包时认不出数据包分支 —— 用**同职业**的内置分支顶上。
            // （贴图与血量不会错：职业是单独同步的字段；只有攻击范围预览会退化。）
            def = BranchRegistry.firstOfClass(this.getUnitClass());
        }
        if (def == null) {
            // 兜底二：连职业都不可信（存档写坏）——退回默认分支，绝不抛异常。
            def = BranchRegistry.require(BuiltinBranch.idOf(DEFAULT_BRANCH));
        }
        this.cachedBranch = def;
        this.cachedBranchKey = raw;
        this.cachedRevision = revision;
        return def;
    }

    /** 按内置枚举设置分支（刷怪蛋、自验台走这条）。 */
    public void setBranch(UnitBranch branch) {
        this.setBranch(new BuiltinBranch(branch));
    }

    /** 设置分支：id 进同步数据，职业跟着走，数值与名字一起刷新。 */
    public void setBranch(BranchDef branch) {
        this.entityData.set(DATA_BRANCH, branch.id().toString());
        // 职业跟着分支走，避免出现「重装贴图 + 狙击数值」这种不一致
        this.entityData.set(DATA_UNIT_CLASS, branch.unitClass().ordinal());
        this.applyBranchAttributes();
        // ★ 分支定了才有名字：写进实体自定义名，Jade / 死亡消息 / 指令反馈才认得出它是谁。
        //   放在最后是因为 getDisplayName() 要用到刚写好的血量。
        this.refreshNameTag();
    }

    /**
     * 按分支把生命/攻击力/护甲写进属性。
     *
     * <p>数值来自 {@link BranchDef}：内置分支 = 职业基础模板 + 分支增减（总点数上限 20），
     * 数据包分支 = JSON 里写的绝对值。</p>
     *
     * <p>注意「先改 MAX_HEALTH 再回满血」的顺序：只加 MAX_HEALTH 不改当前血量的话，
     * 实体会留着一个「残血比例」，看起来像是受伤状态。</p>
     */
    private void applyBranchAttributes() {
        BranchDef branch = this.getBranch();
        var maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(branch.maxHealth());
            this.setHealth(this.getMaxHealth());
        }
        var attack = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(branch.attackDamage());
        }
        // 护甲：原版按点数减伤（约 1 点 = 4%，上限 80%）
        var armor = this.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.setBaseValue(branch.armor());
        }
    }

    /**
     * 一次性指定分支（刷怪蛋走这条路）。
     *
     * <p>必须用本方法而不是只调 {@link #setBranch}：它会把 {@link #classAssigned}
     * 置位，从而阻止 {@code finalizeSpawn} 把分支随机掉。</p>
     */
    public void assign(UnitBranch branch) {
        this.assign(new BuiltinBranch(branch));
    }

    /** 指定任意分支（含数据包分支）。 */
    public void assign(BranchDef branch) {
        this.setBranch(branch);
        this.classAssigned = true;
        // ★ 部署初始化（设计口径「棋子放下自动装技能 1」+ PRTS「携带者入场时，技能会预先
        //   充入初始技力」）——
        //   **判据只在这一处**：刷怪蛋（{@code ModItems.PawnSpawnEggItem} 的 NBT 路径会走
        //   readAdditionalSaveData 里的 assign）、{@code api/Pawns.spawn}、自验台的生成路径
        //   全部汇到本方法，所以不必在每个调用点各写一份「摆完要初始化技能位」。
        //   （同一件事写两份必然漂移：本项目「一条判据一处实现」的纪律。）
        //
        //   ★ 只在服务端做：技能位/技力/激活/弹药四个字段都是**同步数据**，客户端那份由同步包
        //   下发；在客户端本地写会被服务端权威值覆盖，反而多一层时序问题（同朝向/阶位的注释）。
        if (!this.level().isClientSide) {
            Skills.onDeploy(this);
        }
    }

    public boolean isClassAssigned() {
        return this.classAssigned;
    }

    /** 该职业的贴图。 */
    public ResourceLocation getSpriteTexture() {
        return this.getUnitClass().spriteTexture();
    }

    /** 该棋子的攻击范围格子（相对自身所在格的 {x, z} 偏移，见 {@link AttackRange}）。 */
    public List<AttackRange.Cell> getAttackCells() {
        // 兜底逻辑（分支范围键取不到 → 退回职业默认范围）在数据层，
        // 这样数据包分支也享受同一条兜底，见 BuiltinBranch#attackCells
        return this.getBranch().attackCells();
    }

    // ------------------------------------------------------------------
    // 阶位 / 精炼（自走棋那一层的数据，本 mod 只存与显示）
    // ------------------------------------------------------------------

    /** 当前阶位（1~6）。 */
    public int getTier() {
        return this.entityData.get(DATA_TIER);
    }

    /** 设置阶位；越界一律夹到 {@link #MIN_TIER}~{@link #MAX_TIER}（不抛异常，避免商店算错就崩）。 */
    public void setTier(int tier) {
        this.entityData.set(DATA_TIER, Math.max(MIN_TIER, Math.min(MAX_TIER, tier)));
    }

    /** 是否已精炼（三合一的终点）。 */
    public boolean isRefined() {
        return this.entityData.get(DATA_REFINED);
    }

    public void setRefined(boolean refined) {
        this.entityData.set(DATA_REFINED, refined);
    }

    // ------------------------------------------------------------------
    // 技能位 / 技力 / 激活 / 弹药 / 出手形式覆盖
    //
    // 本类**只负责存、同步、夹取**：技能数值（上限 = 消耗技力）、触发时机、结束原因、
    // 涨技力的规则全部在 {@code combat.Skills} 一处（那里的类注释写了「本类不负责什么」）。
    //
    // ★ 老存档里的 {@code SkillCooldown} 键：2026-10 第四轮把**占位冷却**换成技力机制时，
    //   那个字段与它的 NBT 键一起删掉了（现在写档也不再产生它）。老存档里带着这个键的棋子
    //   必须**照常加载**：读档时**忽略**该键即可 —— 不迁移、不报错、不 WARN（存档是设计的，
    //   一个已经不存在的字段不值得在日志里刷一行）。所以本类里**刻意没有**任何常量或分支
    //   提到它；需要核对这条行为的地方（自验场景 skills）直接用字符串字面量 "SkillCooldown"。
    //   「SkillCooldown 去哪了」的答案是：被 {@link #TAG_SKILL_POINTS} 取代。
    // ------------------------------------------------------------------

    /**
     * 当前技能槽位（0 = 没有技能，1~{@link #MAX_SKILL_SLOT} = 三个技能槽）。
     *
     * <p>槽位就是「预设」的选择：棋子放下时装技能 1，玩家可以切到 2/3
     * （切槽命令见 {@code command/DebugCommands}，界面留给后续项目）。</p>
     */
    public int getSkillSlot() {
        return this.entityData.get(DATA_SKILL_SLOT);
    }

    /**
     * 设置技能槽位；越界一律夹到 [{@link #NO_SKILL}, {@link #MAX_SKILL_SLOT}]。
     *
     * <p>刻意<b>不抛异常</b>：调用方可能是数据包 / 指令 / 后续项目的商店逻辑，
     * 参数写错只该「退化成没有技能或最高槽位」，不该把服务器带崩 ——
     * 与 {@link #setTier(int)} 同一个口径（{@link BranchDef} 的契约也是这么要求的）。</p>
     */
    public void setSkillSlot(int slot) {
        this.entityData.set(DATA_SKILL_SLOT, clampSkillSlot(slot));
    }

    /** 把技能槽位夹到合法区间（同步数据与存档读取共用这一条判据，避免两处各写一份）。 */
    public static int clampSkillSlot(int slot) {
        return Math.max(NO_SKILL, Math.min(MAX_SKILL_SLOT, slot));
    }

    /**
     * 当前技力（{@code >= 0}）。
     *
     * <p>本类只负责存与同步；<b>上限（= 该技能的消耗技力）与涨落由
     * {@code combat.Skills} 一处判定</b> —— 实体不知道槽位对应的技能是什么。</p>
     */
    public int getSkillPoints() {
        return this.entityData.get(DATA_SKILL_POINTS);
    }

    /** 设置技力；负数夹到 0（负数没有含义，只可能是调用方算错）。 */
    public void setSkillPoints(int points) {
        this.entityData.set(DATA_SKILL_POINTS, clampSkillPoints(points));
    }

    /** 把技力夹到 {@code >= 0}（上限的判据在 {@code combat.Skills}，本类不越权）。 */
    public static int clampSkillPoints(int points) {
        return Math.max(0, points);
    }

    /**
     * 技能激活剩余 tick：{@link #SKILL_NOT_ACTIVE} = 未激活；
     * {@code >= 0} = 还剩多少 tick；{@link #SKILL_FOREVER_TICKS} = 永续激活。
     */
    public int getSkillActiveTicks() {
        return this.entityData.get(DATA_SKILL_ACTIVE_TICKS);
    }

    /** 设置激活剩余；小于 -1 的一律夹到 {@link #SKILL_NOT_ACTIVE}（不抛异常）。 */
    public void setSkillActiveTicks(int ticks) {
        this.entityData.set(DATA_SKILL_ACTIVE_TICKS, clampSkillActiveTicks(ticks));
    }

    /** 把激活剩余夹到合法区间（只看下界；上界的哨兵 {@link #SKILL_FOREVER_TICKS} 是合法值）。 */
    public static int clampSkillActiveTicks(int ticks) {
        return Math.max(SKILL_NOT_ACTIVE, ticks);
    }

    /** 剩余弹药：{@link #SKILL_NO_AMMO} = 不适用；{@code >= 0} = 还剩几发。 */
    public int getSkillAmmo() {
        return this.entityData.get(DATA_SKILL_AMMO);
    }

    /** 设置剩余弹药；负数一律夹到 {@link #SKILL_NO_AMMO}（不抛异常）。 */
    public void setSkillAmmo(int ammo) {
        this.entityData.set(DATA_SKILL_AMMO, clampSkillAmmo(ammo));
    }

    /**
     * 把弹药夹到合法区间：负数统一归到哨兵 {@link #SKILL_NO_AMMO}。
     *
     * <p>不保留 -5 / -100 这类「更负」的值：口径里负数只有「不适用」一个含义，
     * 归一到哨兵之后 {@code == SKILL_NO_AMMO} 这种判定才不会因为存档写坏而失效。</p>
     */
    public static int clampSkillAmmo(int ammo) {
        return Math.max(SKILL_NO_AMMO, ammo);
    }

    /** 技能当前**是否处于激活期**（含永续）。 */
    public boolean isSkillActive() {
        return getSkillActiveTicks() >= 0;
    }

    /**
     * 出手形式覆盖；{@code null} = 用分支模板的默认值。
     *
     * <p>序号越界（存档写坏、未来枚举被改过）时<b>当作没有覆盖</b>而不是抛异常：
     * 兜底成「按分支默认出手」永远是对的，见 {@link #effectiveAttackMethod()}。</p>
     */
    @Nullable
    public UnitBranch.AttackMethod getAttackMethodOverride() {
        int raw = this.entityData.get(DATA_ATTACK_METHOD_OVERRIDE);
        UnitBranch.AttackMethod[] values = UnitBranch.AttackMethod.values();
        return raw < 0 || raw >= values.length ? null : values[raw];
    }

    /**
     * 设置出手形式覆盖（{@code null} = 清除覆盖）。
     *
     * <p>给技能系统用：技能期间「近战变远程」「远程变近战」只改这一个字段，
     * 战斗侧不必知道是哪条技能改的。</p>
     */
    public void setAttackMethodOverride(@Nullable UnitBranch.AttackMethod method) {
        this.entityData.set(DATA_ATTACK_METHOD_OVERRIDE,
                method == null ? NO_ATTACK_METHOD_OVERRIDE : method.ordinal());
    }

    /**
     * 实际出手方式：有覆盖用覆盖，否则取 {@link #getBranch()}{@code .attackMethod()}。
     *
     * <p>这是战斗侧（将来以及现在的 {@code PawnCombatManager}）<b>唯一</b>该读的出手口径：
     * 直接读 {@code getBranch().attackMethod()} 会让技能改出来的出手形式被无声忽略。</p>
     */
    public UnitBranch.AttackMethod effectiveAttackMethod() {
        UnitBranch.AttackMethod override = this.getAttackMethodOverride();
        return override != null ? override : this.getBranch().attackMethod();
    }

    /**
     * <b>受伤路径</b>：这里是**受击回复**（每次被攻击 +1 点技力）的<b>唯一入口</b>。
     *
     * <p>为什么必须接在实体自己的 {@code hurt} 上，而不是接在某个伤害结算里：棋子的血只会因为
     * 原版伤害路径掉（敌人近战、投掷物、爆炸、{@code /damage}、虚空…），而它们**全部**会走到
     * 这个方法。接在 {@code PawnCombatManager.applyHit} 上就会漏掉「敌人打棋子」这一大类
     * （那是原版 AI 直接调 {@code hurt}），而表里那 2 条受击回复的技能正是靠挨打涨技力的。</p>
     *
     * <p>判据只用 {@code super.hurt(...)} 的返回值：原版在「无敌帧内、且这一下不比上次疼」时
     * 会直接返回 {@code false}（一点血都不掉）—— 那种情况不算「被攻击」，不加技力。
     * 涨技力本身（受击回复才 +1、激活期间阻回、夹到上限）全部在 {@code combat/Skills.onHurt}
     * 一处实现，这里只是把事件转过去。</p>
     */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean damaged = super.hurt(source, amount);
        // 只在服务端加技力：技力是同步数据，客户端本地加会被服务端权威值覆盖
        if (damaged && !this.level().isClientSide) {
            Skills.onHurt(this);
        }
        return damaged;
    }

    // ------------------------------------------------------------------
    // 朝向
    // ------------------------------------------------------------------

    /** 棋子当前朝向；数据异常时兜底为正东（= 方案1 的初始朝向）。 */
    public PieceFacing getFacing() {
        PieceFacing f = PieceFacing.byOrdinal(this.entityData.get(DATA_FACING));
        return f == null ? PieceFacing.EAST : f;
    }

    /**
     * 直接设置朝向。
     *
     * <p>服务端权威：写进同步数据后客户端会收到，攻击范围预览才不会和服务端判定错开。</p>
     */
    public void setFacing(PieceFacing facing) {
        this.entityData.set(DATA_FACING, facing.ordinal());
    }

    /** 顺时针旋转 90°（界面上的 {@code +}）；返回旋转后的朝向。 */
    public PieceFacing rotateClockwise() {
        PieceFacing next = this.getFacing().clockwise();
        this.setFacing(next);
        return next;
    }

    /** 逆时针旋转 90°（界面上的 {@code -}）；返回旋转后的朝向。 */
    public PieceFacing rotateCounterClockwise() {
        PieceFacing next = this.getFacing().counterClockwise();
        this.setFacing(next);
        return next;
    }

    /**
     * 把攻击范围格子换算成<b>世界方块坐标偏移</b>（已按当前朝向旋转）。
     *
     * <p>这是索敌与预览<b>唯一</b>该用的入口。直接用 {@link #getAttackCells()}
     * 拿到的还是「相对棋子」的坐标，只适用于面朝正南（+Z）的情况。</p>
     */
    public List<AttackRange.Cell> worldCells() {
        List<AttackRange.Cell> local = this.getAttackCells();
        if (local.isEmpty()) {
            return local;
        }
        PieceFacing facing = this.getFacing();
        List<AttackRange.Cell> out = new ArrayList<>(local.size());
        for (AttackRange.Cell c : local) {
            int[] off = facing.toWorldOffset(c.x(), c.z());
            out.add(new AttackRange.Cell(off[0], off[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 实体障碍：让棋子真的是「一堵墙」
    // ------------------------------------------------------------------

    /**
     * ★ 棋子是<b>实体障碍</b>（实心）。<b>没有这一步，阻挡在物理上根本不存在</b> ——
     * 敌人会直接从棋子身上穿过去（设计口径 的反馈：「碰撞箱没了，根本挡不了实体」）。
     *
     * <h3>依据（1.20.1 反编译源码，逐行读过；路径见 踩坑记录）</h3>
     * <p>一个实体要能挡住别的实体，必须进得了对方的碰撞列表，链条是三环：</p>
     * <ol>
     *     <li>{@code EntityGetter#getEntityCollisions(Entity, AABB)}（第 53-71 行）：
     *         判据是 {@code NO_SPECTATORS.and(移动者::canCollideWith)}，命中后把候选实体的
     *         {@code getBoundingBox()} 当成 {@code VoxelShape} 交出去；</li>
     *     <li>{@code Entity#collide(Vec3)}（第 888-891 行）把这些形状交给
     *         {@code collideBoundingBox} —— <b>这一环才是「走不过去」</b>；</li>
     *     <li>{@code Entity#canCollideWith(Entity)}（第 1856 行）直接返回
     *         {@code other.canBeCollidedWith()}；而 {@code Entity#canBeCollidedWith()}
     *         （第 1860 行）<b>默认 false</b>。</li>
     * </ol>
     *
     * <p>于是有个很反直觉的结论：<b>原版生物之间没有碰撞箱</b>。一群僵尸挤在一起靠的是
     * {@code pushEntities} 的<b>推挤</b>（每 tick 互相弹开 0.05 格），不是硬碰撞 ——
     * 推挤拦不住「一直往前走」的 AI，所以没有硬碰撞就必然穿模。</p>
     *
     * <p>全部 5454 个源文件里，<b>活着的实体只有潜影贝重写了它</b>：
     * {@code Shulker#canBeCollidedWith() { return this.isAlive(); }}（第 439-441 行）——
     * 「潜影贝是实心的、推不动」正是这一行。本方法照抄这个口径。</p>
     *
     * <h3>副作用（都为真，且都可接受，故不藏）</h3>
     * <ul>
     *     <li>棋子对<b>玩家</b>同样是实心的：碰撞是双向的，做不到「只挡怪物」。
     *         棋子是部署在地面上的单位，站不进去才对（也是设计口径「要有碰撞箱」的字面结果）；</li>
     *     <li>它<b>不会</b>让棋子被推动：{@link #push(Entity)} 等仍是空实现、速度仍是 0，
     *         棋子依旧钉死不动 —— 这正是「实心且不可推」该有的样子（同潜影贝）；</li>
     *     <li>原版寻路<b>看不见实体</b>：{@code PathNavigationRegion#getEntityCollisions}
     *         （第 89-91 行）直接 {@code return List.of();}，所以敌人会被棋子挡住，
     *         但<b>不会自己绕开</b>。绕行由 {@code PawnCombatManager} 补一条侧步路点
     *         （见那里的注释：被挡住的 N 个本来就该顶在那儿，第 N+1 个必须能过去）；</li>
     *     <li>「已经和棋子重叠」的实体（{@code /summon} 到棋子脚下、被爆炸塞进来）
     *         不会被弹出去：原版只拦「进不来」，不负责「推出去」——潜影贝同样如此。</li>
     * </ul>
     *
     * <h3>★ 2026-10 设计口径：<b>常态阻挡为 0 的棋子没有碰撞箱</b></h3>
     * <p>设计原话：「<b>常态阻挡为 0 的棋子，去掉它的碰撞箱</b>」（怪物应当能从它身上走过去）。
     * 所以这里的判据是 {@code isAlive() && getBranch().normalBlockCount() > 0}。</p>
     *
     * <p><b>为什么不能只看 {@link BranchDef#blockCount()}</b>：表里「阻挡数」那一列填的是
     * <b>技能期 / 显示值</b>，不是常态。最典型的例子是<b>近卫 · 解放者</b>：PRTS 的特性原文
     * 写着「<b>通常不攻击且阻挡数为0</b>」（4 位解放者的特性全是这句），而表里那格是 <b>3</b>。
     * 若照 {@code blockCount() > 0} 判，解放者就会拿到碰撞箱、把怪物挡在外面 ——
     * 与它的特性（平时不挡人）正好相反；<b>伏击客</b>（表里本来就是 0）也有过同样的现象：
     * 设计实机看到「伏击客会阻挡怪物」才暴露出这个口径问题。</p>
     *
     * <p>「常态阻挡」这个量与它的覆盖表<b>只有一处实现</b>（生成链 的
     * {@code normal_block} → 生成物 {@link UnitBranch#normalBlockCount()}），
     * 本方法与 {@code PawnCombatManager#updateBlocking} 的容量读的是<b>同一个量</b> ——
     * 否则会出现「不挡路却还去把怪拽到锚点」这种自相矛盾。</p>
     */
    @Override
    public boolean canBeCollidedWith() {
        // 与潜影贝同一个口径：活着才是障碍。死亡动画期间不再挡路，
        // 免得「尸体」继续把敌人拦在外面（那时棋子已经不再阻挡、也不再攻击）。
        // ★ 2026-10 再加一条：**常态阻挡为 0 的棋子根本不该有碰撞箱**（设计口径）——
        //   判据用 normalBlockCount()（常态），不是 blockCount()（技能期/显示值，见上面的长注释）。
        return this.isAlive() && this.getBranch().normalBlockCount() > 0;
    }

    // ------------------------------------------------------------------
    // 「钉死」：拦掉一切位移与旋转
    // ------------------------------------------------------------------

    @Override
    public void push(Entity entity) {
        // 生物挤 / 实体碰撞推动：无效
    }

    @Override
    public void push(double x, double y, double z) {
        // 方块推动（含爆炸、活塞）：无效
    }

    @Override
    public void knockback(double strength, double x, double z) {
        // 击退：无效
    }

    @Override
    public void setDeltaMovement(Vec3 movement) {
        // ★ 最后一道闸：任何来源的速度都归零。
        //   调用 super 传零向量（而不是直接 return），保证内部速度字段确实是 0，
        //   不会残留上一 tick 的数值导致「抖一下」。
        super.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public void setDeltaMovement(double x, double y, double z) {
        super.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public void setYRot(float yaw) {
        super.setYRot(0.0F);
    }

    @Override
    public void setXRot(float pitch) {
        super.setXRot(0.0F);
    }

    @Override
    public void setYHeadRot(float rotation) {
        super.setYHeadRot(0.0F);
    }

    @Override
    public void setYBodyRot(float rotation) {
        super.setYBodyRot(0.0F);
    }

    /** 摔落伤害：棋子不会掉下去，但保险起见关掉。 */
    @Override
    public boolean causeFallDamage(float distance, float multiplier,
                                   net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    /** 棋子是战术资产，不因距离过远被清除。 */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // ------------------------------------------------------------------
    // 存档

    // ------------------------------------------------------------------
    // 归属（有主）—— 2026-10 设计口径
    // ------------------------------------------------------------------
    //
    // 设计原话：「将玩家摆放的棋子统一挂到玩家所属下视为同一队……不同玩家放的棋子在不同玩家
    // 所属下，但不为同一队只算同一阵营」，补充「棋子类似于被驯服的生物，也是『有主』的」。
    // 所以这里就是**原版驯服生物那一套**：实现 {@link OwnableEntity}，存主人 UUID，
    // {@code getOwner()} 由接口的默认实现按 UUID 去世界里查。
    //
    // ★ 只存服务端 + 存档，**不同步给客户端**：治疗与队伍判定都在服务端做，客户端不需要它；
    //   将来若要做「按主人染色的棋子外观」，再补一个同步字段（那时的口径与 踩坑记录同）。
    @Nullable
    private String ownerId;

    /** 主人显示名（报告用）。 */
    @Nullable
    private String ownerName;

    /** 记下归属（{@code null}/空 = 无主）。同时进/出记分板队伍，见 {@code PawnTeams}。 */
    public void assignOwner(@Nullable String uuid, @Nullable String name) {
        this.ownerId = (uuid == null || uuid.isBlank()) ? null : uuid;
        this.ownerName = (name == null || name.isBlank()) ? null : name;
    }

    /** 主人 UUID 字符串（无主 = null）。 */
    @Nullable
    public String ownerId() {
        return this.ownerId;
    }

    /** 主人显示名（无主 = null；只有报告用）。 */
    @Nullable
    public String ownerName() {
        return this.ownerName;
    }

    /** {@link OwnableEntity}：主人 UUID（无主 = null）。 */
    @Override
    @Nullable
    public java.util.UUID getOwnerUUID() {
        if (this.ownerId == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(this.ownerId);
        } catch (IllegalArgumentException ex) {
            return null;    // 存档/物品 NBT 是外部输入：写坏了降级成「无主」，不抛
        }
    }
    // ------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Branch", this.getBranch().id().toString());
        tag.putString("UnitClass", this.getUnitClass().spriteId());
        // 朝向单独存：实体自己的 Rotation 被锁成 0，不能用它还原朝向
        tag.putString(TAG_FACING, this.getFacing().name());
        // 阶位 / 精炼：商店项目会用到的两个字段，先存好（老存档没有这两个键 → 用默认值）
        tag.putInt(TAG_TIER, this.getTier());
        tag.putBoolean(TAG_REFINED, this.isRefined());
        // 技能位数据：同样先存好（老存档没有这些键 → 默认值：无技能 / 技力 0 / 未激活 / 无弹药）
        tag.putInt(TAG_SKILL_SLOT, this.getSkillSlot());
        tag.putInt(TAG_SKILL_POINTS, this.getSkillPoints());
        tag.putInt(TAG_SKILL_ACTIVE_TICKS, this.getSkillActiveTicks());
        tag.putInt(TAG_SKILL_AMMO, this.getSkillAmmo());
        // 解放者 ramp（老存档没有这个键 → 0 = 从零开始爬）
        tag.putInt(TAG_RAMP_TICKS, this.getRampTicks());
        // 覆盖存**枚举名**、没有覆盖存空串（理由见 TAG_ATTACK_METHOD_OVERRIDE 的注释）
        UnitBranch.AttackMethod override = this.getAttackMethodOverride();
        tag.putString(TAG_ATTACK_METHOD_OVERRIDE, override == null ? "" : override.name());
        // 归属（有主）：UUID 字符串 + 显示名
        if (this.ownerId != null) {
            tag.putString(TAG_OWNER, this.ownerId);
        }
        if (this.ownerName != null) {
            tag.putString(TAG_OWNER_NAME, this.ownerName);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 优先按分支还原（分支自带职业）；老存档只有 UnitClass 时退回按职业还原。
        // 归属（有主）：与朝向同一理由，只在服务端写（客户端不参与任何判定）。
        // ★ 读进来之后立刻重新入队：队伍成员表虽然存在 level.dat 里，但**队伍本身可能被清理过**
        //   （例如有人把 gp_p* 删了），那时棋子会变成「有主但无队」，治疗就找不到它的同队了。
        if (!this.level().isClientSide) {
            if (tag.contains(TAG_OWNER) || tag.contains(TAG_OWNER_NAME)) {
                this.assignOwner(tag.getString(TAG_OWNER), tag.getString(TAG_OWNER_NAME));
            }
            if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.guardianprotocol.combat.PawnTeams.rejoin(sl, this);
            }
        }
        if (tag.contains("Branch")) {
            // BranchRegistry.byKey 同时认三种写法：新存档的完整 id、老存档的枚举名、光 path
            BranchDef def = BranchRegistry.byKey(tag.getString("Branch"));
            if (def != null) {
                this.assign(def);
            } else {
                GuardianProtocol.LOGGER.warn("[{}] 存档里的分支无法识别：{}（退回默认分支）",
                        GuardianProtocol.MODID, tag.getString("Branch"));
            }
        } else if (tag.contains("UnitClass")) {
            UnitClass c = UnitClass.byName(tag.getString("UnitClass"));
            if (c != null) {
                this.setUnitClass(c);
                this.classAssigned = true;
            }
        }
        // 朝向：老存档没有这个键，保持 defineSynchedData 给的默认（正东）。
        // ★ 只在服务端写：生成时服务端会把朝向放进生成包，客户端读到的可能还是旧值，
        //   若在这里也写，客户端就会把服务端的权威朝向覆盖掉（同步数据还没到的时候）。
        if (!this.level().isClientSide && tag.contains(TAG_FACING)) {
            PieceFacing f = PieceFacing.byName(tag.getString(TAG_FACING));
            if (f != null) {
                this.setFacing(f);
            } else {
                GuardianProtocol.LOGGER.warn("[{}] 存档里的朝向无法识别：{}",
                        GuardianProtocol.MODID, tag.getString(TAG_FACING));
            }
        }
        // 阶位 / 精炼：与朝向同样的理由，只在服务端写（客户端以同步数据为准）。
        // 老存档两个键都没有 → 保持默认（1 阶、未精炼）。
        if (!this.level().isClientSide) {
            if (tag.contains(TAG_TIER)) {
                this.setTier(tag.getInt(TAG_TIER));
            }
            if (tag.contains(TAG_REFINED)) {
                this.setRefined(tag.getBoolean(TAG_REFINED));
            }
        }
        // 技能位数据：与朝向/阶位**同一条理由**（踩坑记录）—— 本方法在客户端也会跑，
        // 而无条件写同步数据会覆盖掉服务端刚下发的权威值（生成包与同步包的到达顺序不可假设）。
        // 老存档没有这些键 → 保持 defineSynchedData 给的默认（无技能 / 技力 0 / 未激活 / 无弹药）。
        //
        // ★ 老存档里可能还有 `SkillCooldown`（占位冷却时代的键）：**这里刻意不读它**，
        //   也不迁移 —— 读一个已经不存在的字段只会让日志多一行噪声，而它对技力没有任何意义。
        //   三个新键各自独立判定：缺哪个就用那个的默认值，互不牵连。
        if (!this.level().isClientSide) {
            if (tag.contains(TAG_SKILL_SLOT)) {
                this.setSkillSlot(tag.getInt(TAG_SKILL_SLOT));
            }
            if (tag.contains(TAG_SKILL_POINTS)) {
                this.setSkillPoints(tag.getInt(TAG_SKILL_POINTS));
            }
            if (tag.contains(TAG_SKILL_ACTIVE_TICKS)) {
                this.setSkillActiveTicks(tag.getInt(TAG_SKILL_ACTIVE_TICKS));
            }
            if (tag.contains(TAG_SKILL_AMMO)) {
                this.setSkillAmmo(tag.getInt(TAG_SKILL_AMMO));
            }
            // 解放者 ramp：缺这个键的老存档 → 保持 0（= 从零开始爬，与「技能结束重置」同一个状态）
            if (tag.contains(TAG_RAMP_TICKS)) {
                this.setRampTicks(tag.getInt(TAG_RAMP_TICKS));
            }
            if (tag.contains(TAG_ATTACK_METHOD_OVERRIDE)) {
                String raw = tag.getString(TAG_ATTACK_METHOD_OVERRIDE).trim();
                if (raw.isEmpty()) {
                    // 空串 = 明确表示「没有覆盖」（写入端就是这么写的）
                    this.setAttackMethodOverride(null);
                } else {
                    UnitBranch.AttackMethod method = parseAttackMethod(raw);
                    if (method == null) {
                        // 认不出来就退回「按分支默认出手」，不抛异常、也不清掉存档里的原文
                        GuardianProtocol.LOGGER.warn("[{}] 存档里的出手形式覆盖无法识别：{}（当作没有覆盖）",
                                GuardianProtocol.MODID, raw);
                    } else {
                        this.setAttackMethodOverride(method);
                    }
                }
            }
        }
    }

    /**
     * 按名字解析出手形式覆盖（大小写不敏感）。
     *
     * <p>容错写法（{@code equalsIgnoreCase} + trim）是刻意留的：存档里的这一列是**外部输入**，
     * 手改过、或用小写写过都还可能读回来。认不出来返回 {@code null}，由调用方降级 ——
     * 与 {@code BranchRegistry.byKey} 对老写法的宽容同一个思路。</p>
     */
    @Nullable
    private static UnitBranch.AttackMethod parseAttackMethod(String raw) {
        for (UnitBranch.AttackMethod method : UnitBranch.AttackMethod.values()) {
            if (method.name().equalsIgnoreCase(raw)) {
                return method;
            }
        }
        return null;
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        // 普通刷怪蛋刷出来的棋子随机一个分支，方便对比 8 套贴图；
        // 指定分支的蛋会先把数据写好并把 classAssigned 置位，这里必须尊重它。
        if (reason == MobSpawnType.SPAWN_EGG && !this.classAssigned) {
            UnitBranch[] values = UnitBranch.values();
            this.setBranch(values[this.random.nextInt(values.length)]);
        }
        return super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
    }

    // ------------------------------------------------------------------
    // 右键界面（朝向设置）
    // ------------------------------------------------------------------

    /**
     * 右键棋子打开朝向界面。
     *
     * <p><b>只在方案1（{@link PawnFacingMode#FIXED_EAST}）下开界面</b>：
     * 方案2 的语义是「按放置时我的面向布置」，如果还允许事后旋转，
     * 就与那个语义打架了。所以方案2 直接返回 {@code PASS}，右键没有任何反应。</p>
     *
     * <p>打开通道用 {@code NetworkHooks.openScreen} 的<b>实体版</b>：
     * 它没有现成的 {@code pos} 重载，要自己把实体 id 写进额外数据，
     * 客户端再据此在自己的世界里找回这个实体（见 {@code ModMenus}）。</p>
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!GuardianConfig.pawnFacingMode().allowsManualRotation()) {
            return InteractionResult.PASS;
        }
        if (this.level().isClientSide) {
            // 这里返回 SUCCESS 是刻意的：客户端预先「假定」这次交互成功了，
            // 免得本地预测把玩家的这一下判定成失败而出现回弹。
            // 真正的开界面由服务端那边的 NetworkHooks.openScreen 发起，客户端不用自己做。
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this, buf -> buf.writeVarInt(this.getId()));
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** 界面标题 / 头顶命名牌：分支名 + 当前血量，与 {@link #getName()} 口径一致。 */
    @Override
    public Component getDisplayName() {
        // 自定义名（服务端写的分支名）优先，保证「界面标题 / 命名牌 / Jade / 死亡消息」
        // 四处显示的是同一个名字。
        Component name = this.getCustomName() != null ? this.getCustomName() : this.branchName();
        return Component.empty()
                .append(name)
                .append(Component.literal(String.format(" %.0f/%.0f",
                        this.getHealth(), this.getMaxHealth())));
    }

    /**
     * 分支名（内置分支走语言键；数据包分支直接用 JSON 里写的名字）。
     *
     * <p><b>public</b>（2026-10-07）：朝向界面要显示「分支职业：职业 · 分支」，
     * 而「内置走语言键、数据包用字面量」这条规则只能有一处 —— 所以界面直接调这里，
     * 不另抄一份（同项目一贯口径：同一条判据写两遍迟早漂移）。</p>
     */
    public Component branchName() {
        BranchDef branch = this.getBranch();
        if (branch.isBuiltin()) {
            return Component.translatable(
                    "branch.guardian_protocol." + branch.key().toLowerCase(Locale.ROOT));
        }
        // 数据包分支没有语言键：用字面量，免得头顶显示成 branch.guardian_protocol.xxx
        return Component.literal(branch.branchName());
    }

    /**
     * 把分支名写成<b>实体的自定义名</b>。
     *
     * <p><b>为什么非写不可</b>：刷怪蛋的物品名是「每个分支一个」（陷阱师棋子 / 医师棋子…），
     * 但实体只有<b>一个类型</b>（{@code guardian_protocol:pixel_unit}）。
     * 不写自定义名的话，{@link #getName()} 只能退回实体类型名 ——
     * 于是任何读它的地方（<b>Jade</b>、死亡消息、指令反馈）都显示成通用的
     * 「像素棋子」，玩家会觉得「刷怪蛋有名字、放出来的棋子却没名字」。</p>
     *
     * <p>只显示分支名、不带血量：血量由 Jade 的血条与头顶命名牌（{@link #getDisplayName()}）
     * 负责，同一个数字出现两遍反而啰嗦。</p>
     *
     * <p>只在服务端写：客户端那份由同步包下发，本地写会被覆盖掉。</p>
     */
    private void refreshNameTag() {
        if (this.level().isClientSide) {
            return;
        }
        this.setCustomName(this.branchName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PawnFacingMenu(containerId, inventory, this.getId());
    }

    /**
     * 命名牌只在**准星对准本棋子**时显示。
     *
     * <p>★ 早先这里直接 {@code return true}，于是 7 个棋子的「职业名 + 血量」
     * 一直顶在头上，几个棋子摆一起就糊成一片、连攻击范围预览都看不清。
     * 现在返回 {@link #nameRevealed}，由预览那边（它才知道准星对着谁）驱动。</p>
     *
     * <p>注意这是**纯客户端表现**：服务端即使返回 false 也不影响任何判定逻辑。</p>
     */
    @Override
    public boolean isCustomNameVisible() {
        return this.nameRevealed;
    }
}
