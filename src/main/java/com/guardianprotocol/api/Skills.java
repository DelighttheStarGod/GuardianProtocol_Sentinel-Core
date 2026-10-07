package com.guardianprotocol.api;

import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.entity.PixelUnit;

/**
 * 「棋子技能位」的公开接口：读写槽位 / 技力 / 激活 / 弹药 / 技能数、读实际出手形式、打一行描述。
 *
 * <p>下游（技能系统项目 / 商店 / 角色导入）需要的就是这一层：给某颗棋子装上几号技能槽、
 * 看它现在攒了多少技力、还剩多少激活时间与弹药、以及「这颗棋子现在到底怎么出手」。
 * 技能<b>怎么放</b>不在这里 —— 那是 {@code combat.Skills} 的状态机，下游只需要读它的结果。</p>
 *
 * <p><b>破坏性改动（发生在 0.1.0 期间，按设计口径 的口径<b>不抬版本号</b>）</b>：
 * 2026-10 第四轮「预设技能」把占位冷却换成技力机制 ——
 * {@code cooldownOf} / {@code setCooldown} 已删除，换成
 * {@link #pointsOf} / {@link #setPoints} / {@link #activeTicksOf} / {@link #ammoOf}
 * / {@link #skillCountOf}。数值与边界口径见 {@link PixelUnit} 与
 * {@code combat.Skills} 的类注释（PRTS「技能」页：上限 = 消耗技力、激活期间阻回、弹药打完后结束）。</p>
 */
public final class Skills {

    private Skills() {
    }

    // ------------------------------------------------------------------
    // 槽位与技能数
    // ------------------------------------------------------------------

    /**
     * 棋子的技能槽位（0 = 没有技能，1~3 = 三个技能槽）。
     *
     * <p>读的是同步数据，所以<b>客户端也能调</b>（技能 HUD 要的就是这个）。</p>
     */
    public static int slotOf(PixelUnit unit) {
        return unit.getSkillSlot();
    }

    /**
     * 装技能（设槽位 + 直接写技力）。返回<b>空串 = 成功</b>，否则是一句中文的拒绝理由。
     *
     * <p>★ 与旧版不同：非法槽位<b>不再静默夹取</b>。分支只有 2 个技能时槽位 3 会被明确拒绝
     * （理由里带分支名与实际技能数）—— 静默夹取会把「这个分支只有 2 个技能」藏起来。</p>
     *
     * <p>想「立刻就能放」就传 {@code points = maxPointsOf(unit)}：下一个服务端 tick
     * 就会观察到一次触发（见 {@code combat.Skills#onServerTick}）。</p>
     */
    public static String setSlot(PixelUnit unit, int slot, int points) {
        // 注意：本类与 combat.Skills 同名，不能用 import（会撞名），只能用全限定名。
        // 转发而不是复制逻辑：「能不能装这个槽位」的判据只有一处（combat.Skills.slotRejectReason）。
        return com.guardianprotocol.combat.Skills.setSlot(unit, slot, points);
    }

    /**
     * 这个分支实际有几个技能（2 或 3）。数据包分支（没有技能表）返回 0。
     *
     * <p>它同时是「槽位 3 合不合法」的判据：表里有 4 个分支只有 2 条技能
     * （近卫·佣兵 / 近卫·本源近卫 / 狙击·裂空炮手 / 辅助·游击手）。</p>
     */
    public static int skillCountOf(PixelUnit unit) {
        return com.guardianprotocol.combat.Skills.skillCountOf(unit.getBranch());
    }

    /** 当前槽位对应技能的技力上限（= 该技能的消耗技力；没有这条技能返回 0）。 */
    public static int maxPointsOf(PixelUnit unit) {
        return com.guardianprotocol.combat.Skills.maxPointsOf(unit.getBranch(), unit.getSkillSlot());
    }

    // ------------------------------------------------------------------
    // 技力 / 激活 / 弹药（同步数据，客户端可读）
    // ------------------------------------------------------------------

    /** 当前技力（{@code >= 0}，上限见 {@link #maxPointsOf}）。 */
    public static int pointsOf(PixelUnit unit) {
        return unit.getSkillPoints();
    }

    /**
     * 直接设技力；负数夹到 0，超过上限的会被夹到上限。
     *
     * <p>上限的判据在 {@code combat.Skills}（实体不知道槽位对应的技能是什么），
     * 这里只是把「越界输入不抛异常」这条契约兑现。</p>
     */
    public static void setPoints(PixelUnit unit, int points) {
        int max = maxPointsOf(unit);
        unit.setSkillPoints(max <= 0 ? PixelUnit.clampSkillPoints(points)
                : Math.max(0, Math.min(max, points)));
    }

    /**
     * 技能激活剩余 tick：{@code -1} = 未激活；{@code >= 0} = 剩余 tick；
     * {@code Integer.MAX_VALUE} = 永续（含弹药类）激活。
     */
    public static int activeTicksOf(PixelUnit unit) {
        return unit.getSkillActiveTicks();
    }

    /** 技能当前是否处于激活期（含永续与弹药类）。 */
    public static boolean isActive(PixelUnit unit) {
        return com.guardianprotocol.combat.Skills.isActive(unit);
    }

    /**
     * 剩余弹药：{@code -1} = 不适用；{@code >= 0} = 还剩几发。
     *
     * <p>只有「持续时间类型 = 有限持续(弹药耗尽结束)」的 18 条技能会用到它。</p>
     */
    public static int ammoOf(PixelUnit unit) {
        return unit.getSkillAmmo();
    }

    /**
     * 棋子实际的出手方式：有技能覆盖就用覆盖，否则是分支模板的默认值。
     *
     * <p><b>战斗侧要读的是这个，而不是 {@code unit.getBranch().attackMethod()}</b>：
     * 后者读不到技能改出来的出手形式。</p>
     */
    public static UnitBranch.AttackMethod effectiveAttackMethod(PixelUnit unit) {
        return unit.effectiveAttackMethod();
    }

    /**
     * 一行描述（技能位 / 技能名 / 技力 / 激活 / 弹药 / 最近结束原因 / 实际出手 / 技能数 / 放过几次），
     * 排查与报告用。
     *
     * <p>只读、不改任何状态，客户端也能调。</p>
     */
    public static String describe(PixelUnit unit) {
        return com.guardianprotocol.combat.Skills.describe(unit);
    }
}
