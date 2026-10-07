package com.guardianprotocol.combat;

import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.data.UnitClass;

/**
 * 棋子的战斗数值（<b>由 生成链 生成，不要手改</b>）。
 *
 * <h3>数值口径（给定值）</h3>
 * <p>两张参考表只有阻挡数/费用/攻击间隔，没有面板；面板按给定的基础模板来：</p>
 *
 * <table border="1">
 *   <caption>职业基础模板</caption>
 *   <tr><th>职业</th><th>HP</th><th>攻击</th><th>护甲</th></tr>
 *   <tr><td>狙击</td><td>20</td><td>10</td><td>3</td></tr>
 *   <tr><td>术师</td><td>20</td><td>15</td><td>3</td></tr>
 *   <tr><td>先锋</td><td>25</td><td>5</td><td>3</td></tr>
 *   <tr><td>近卫</td><td>20</td><td>15</td><td>4</td></tr>
 *   <tr><td>重装（盾卫）</td><td>30</td><td>5</td><td>5</td></tr>
 *   <tr><td>辅助</td><td>25</td><td>7</td><td>3</td></tr>
 *   <tr><td>特种</td><td>8</td><td>5</td><td>1</td></tr>
 *   <tr><td>医疗</td><td>10</td><td>5</td><td>4</td></tr>
 * </table>
 *
 * <h3>分支增减：总点数上限 20</h3>
 * <p>权重体现「各属性 1 点不等值」：1 HP = 1 点、1 攻击 = 1 点、
 * 1 护甲 = <b>2 点</b>。预算按部署费用分档：≤11 → 18，12~15 → 19，≥16 → 20。</p>
 *
 * <p><b>⚠️ 哪些行是准数、哪些是机械推导</b>：每行末尾都标了。给定值的是
 * 参考表的 10 行；其余 62 行是按 生成链 的
 * {@code derive_delta()} 推的（用预算的 60%，按干员标签分攻击/血量/护甲权重），
 * <b>等设计给数值表后替换</b>。</p>
 */
public final class CombatStats {

    /** 职业基础模板：{HP, 攻击, 护甲}。 */
    private static final int[][] BASE = new int[UnitClass.values().length][3];

    /** 分支相对基础模板的增减：{HP 增量, 攻击增量, 护甲增量}，索引 = UnitBranch 的 ordinal。 */
    private static final int[][] DELTA = new int[UnitBranch.values().length][3];

    static {
        base(UnitClass.SNIPER, 20, 10, 3);
        base(UnitClass.CASTER, 20, 15, 3);
        base(UnitClass.VANGUARD, 25, 5, 3);
        base(UnitClass.GUARD, 20, 15, 4);
        base(UnitClass.DEFENDER, 30, 5, 5);
        base(UnitClass.SUPPORTER, 25, 7, 3);
        base(UnitClass.SPECIALIST, 8, 5, 1);
        base(UnitClass.MEDIC, 10, 5, 4);

        // ---- 分支增减（括号内为加权后总点数；权重 1/1/2）----
        delta(UnitBranch.VANGUARD_TACTICIAN, +7, +2, +1);   // 7+2+2 = 11  机械推导，待确认
        delta(UnitBranch.VANGUARD_STRATEGIST, +7, +2, +1);   // 7+2+2 = 11  机械推导，待确认
        delta(UnitBranch.VANGUARD_PIONEER, +5, +4, +1);   // 5+4+2 = 11  机械推导，待确认
        delta(UnitBranch.VANGUARD_AGENT, +7, +2, +1);   // 7+2+2 = 11  机械推导，待确认
        delta(UnitBranch.VANGUARD_STANDARD_BEARER, +7, +2, +1);   // 7+2+2 = 11  机械推导，待确认
        delta(UnitBranch.VANGUARD_CHARGER, -4, +4, +1);   // 4+4+2 = 10  给定值
        delta(UnitBranch.GUARD_MERCENARY, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.GUARD_EARTHSHAKER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_FIGHTER, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.GUARD_ARTS_FIGHTER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_LORD, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_LIBERATOR, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.GUARD_PRIMAL, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_REAPER, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_CRUSHER, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_SOLOBLADE, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_SWORDMASTER, -3, +10, -1);   // 3+10+2 = 15  给定值
        delta(UnitBranch.GUARD_DREADNOUGHT, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_CENTURION, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.GUARD_INSTRUCTOR, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SNIPER_SKYBREAKER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_HEAVYSHOOTER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_DEADEYE, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_LOOPSHOOTER, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SNIPER_FLINGER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_HUNTER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_BESIEGER, -2, +12, -1);   // 2+12+2 = 16  给定值（推算，待确认）
        delta(UnitBranch.SNIPER_ARTILLERYMAN, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_SPREADSHOOTER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SNIPER_MARKSMAN, -3, +10, -1);   // 3+10+2 = 15  给定值
        delta(UnitBranch.DEFENDER_PRIMAL_PROTECTOR, +6, +2, +2);   // 6+2+4 = 12  机械推导，待确认
        delta(UnitBranch.DEFENDER_SENTRY_PROTECTOR, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.DEFENDER_ARTS_PROTECTOR, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.DEFENDER_GUARDIAN, +6, +2, +2);   // 6+2+4 = 12  机械推导，待确认
        delta(UnitBranch.DEFENDER_JUGGERNAUT, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.DEFENDER_FORTRESS, +2, +5, +2);   // 2+5+4 = 11  给定值（推算，待确认）
        delta(UnitBranch.DEFENDER_DUELIST, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.DEFENDER_PROTECTOR, +6, -1, +1);   // 6+1+2 = 9  给定值
        delta(UnitBranch.CASTER_BLAST, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_MYSTIC, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_PHALANX, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_PRIMAL, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_SHAPER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_MECH_ACCORD, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_SPLASH, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.CASTER_CORE, -2, +9, -1);   // 2+9+2 = 13  给定值
        delta(UnitBranch.CASTER_CHAIN, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.MEDIC_WATCHMAN, +8, +2, +1);   // 8+2+2 = 12  机械推导，待确认
        delta(UnitBranch.MEDIC_INCANTATION, +5, +2, +2);   // 5+2+4 = 11  机械推导，待确认
        delta(UnitBranch.MEDIC_CHAIN, +5, +5, +1);   // 5+5+2 = 12  机械推导，待确认
        delta(UnitBranch.MEDIC_WANDERING, +7, +2, +1);   // 7+2+2 = 11  机械推导，待确认
        delta(UnitBranch.MEDIC_THERAPIST, +8, +2, +1);   // 8+2+2 = 12  机械推导，待确认
        delta(UnitBranch.MEDIC_MEDIC, +8, -3, +1);   // 8+3+2 = 13  给定值
        delta(UnitBranch.MEDIC_MULTI_TARGET, +8, +2, +1);   // 8+2+2 = 12  机械推导，待确认
        delta(UnitBranch.SUPPORTER_SUPPORTIVE_RANGER, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SUPPORTER_ARTIFICER, +5, +2, +2);   // 5+2+4 = 11  机械推导，待确认
        delta(UnitBranch.SUPPORTER_DECEL_BINDER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SUPPORTER_ABJURER, +4, +2, +0);   // 4+2+0 = 6  给定值
        delta(UnitBranch.SUPPORTER_SUMMONER, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SUPPORTER_RITUALIST, +3, +4, +2);   // 3+4+4 = 11  机械推导，待确认
        delta(UnitBranch.SUPPORTER_BARD, +5, +4, +1);   // 5+4+2 = 11  机械推导，待确认
        delta(UnitBranch.SUPPORTER_HEXER, +3, +4, +2);   // 3+4+4 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_DOLLKEEPER, +5, +4, +1);   // 5+4+2 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_SKYRANGER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SPECIALIST_TRAPMASTER, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_GEEK, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_ALCHEMIST, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_EXECUTOR, +10, +2, +1);   // 10+2+2 = 14  给定值
        delta(UnitBranch.SPECIALIST_AMBUSHER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
        delta(UnitBranch.SPECIALIST_MERCHANT, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_HOOKMASTER, +2, +7, +1);   // 2+7+2 = 11  机械推导，待确认
        delta(UnitBranch.SPECIALIST_PUSH_STROKER, +2, +8, +1);   // 2+8+2 = 12  机械推导，待确认
    }

    private CombatStats() {
    }

    private static void base(UnitClass unitClass, int hp, int attack, int armor) {
        BASE[unitClass.ordinal()] = new int[]{hp, attack, armor};
    }

    private static void delta(UnitBranch branch, int dHp, int dAttack, int dArmor) {
        DELTA[branch.ordinal()] = new int[]{dHp, dAttack, dArmor};
    }

    /** 最大生命值。 */
    public static double maxHealth(UnitBranch branch) {
        int[] b = BASE[branch.unitClass().ordinal()];
        int d = DELTA[branch.ordinal()][0];
        // 下限 1：不允许被减成 0 或负数，否则实体一放出来就死
        return Math.max(1, b[0] + d);
    }

    /** 攻击力。 */
    public static double attackDamage(UnitBranch branch) {
        int[] b = BASE[branch.unitClass().ordinal()];
        int d = DELTA[branch.ordinal()][1];
        return Math.max(0, b[1] + d);
    }

    /** 护甲（对应原版 {@code Attributes.ARMOR}，约 1 点抵消 4% 伤害，上限 80%）。 */
    public static double armor(UnitBranch branch) {
        int[] b = BASE[branch.unitClass().ordinal()];
        int d = DELTA[branch.ordinal()][2];
        return Math.max(0, b[2] + d);
    }

    /** 当前应造成的单次伤害（将来接羁绊乘区时改这里）。 */
    public static float damage(UnitBranch branch) {
        return (float) attackDamage(branch);
    }

    // ==================================================================
    //  BranchDef 重载：数据包分支（数据包 里的 JSON）也要能算数值。
    //
    //  ★ 这四个重载曾经只存在于生成物里（有人手加过），而本脚本不产出它们，
    //    于是**每次跑 regen_branches.py 都会被悄悄删掉**，表现是
    //    combat/PawnCombatManager 编译失败（BranchDef 无法转换为 UnitBranch），
    //    而 check 那一步照样全绿 —— 因为核对脚本只看生成物，不看能不能编译。
    //    现在把它们收进模板，生成物才是完整的。
    //
    //  为什么不能让调用方先转成 UnitBranch：{@link BranchDef} 是「内置枚举 +
    //  数据包 JSON」两条来源的共同接口，JSON 分支没有对应的枚举常量。
    // ==================================================================

    /** 最大生命值（数据包分支走它自己的数值）。 */
    public static double maxHealth(BranchDef branch) {
        return branch.maxHealth();
    }

    /** 攻击力（数据包分支走它自己的数值）。 */
    public static double attackDamage(BranchDef branch) {
        return branch.attackDamage();
    }

    /** 护甲（数据包分支走它自己的数值）。 */
    public static double armor(BranchDef branch) {
        return branch.armor();
    }

    /** 当前应造成的单次伤害（数据包分支走它自己的数值）。 */
    public static float damage(BranchDef branch) {
        return (float) branch.attackDamage();
    }

    /** 加权点数：1 HP = 1、1 攻击 = 1、1 护甲 = 2。 */
    private static int weightedPoints(int dHp, int dAttack, int dArmor) {
        return Math.abs(dHp) + Math.abs(dAttack) + Math.abs(dArmor) * 2;
    }

    /** 该分支的预算（按费用分档）。 */
    public static int budget(UnitBranch branch) {
        int cost = branch.deployCost();
        if (cost <= 11) {
            return 18;
        }
        return cost <= 15 ? 19 : 20;
    }

    static {
        // 自检：任何分支的加权点数都不得超过 20（给定的硬约束），也不得超过其预算。
        // 放在静态块里，一旦有人改表改过头，启动时立刻抛错而不是悄悄失衡。
        for (UnitBranch branch : UnitBranch.values()) {
            int[] d = DELTA[branch.ordinal()];
            int points = weightedPoints(d[0], d[1], d[2]);
            if (points > 20) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的属性增减超出上限：加权 %d 点 > 20 点", branch.branchName(), points));
            }
            int budget = budget(branch);
            if (points > budget) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的属性增减超出其预算：%d > %d", branch.branchName(), points, budget));
            }
        }
    }
}
