package com.guardianprotocol.combat;

import com.guardianprotocol.data.PieceFacing;

/**
 * 阻挡锚点与出手节拍的<b>纯数据</b>（不引用任何 Minecraft 类）。
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>「敌人被挡住之后走向哪里」是纯算术（正前方多少格、多个敌人怎么错开），
 * 抽出来之后 离线核对工具 可以脱离 Minecraft 把四朝向 × 多名额的落点
 * 全算一遍并断言（与 {@code RangeCheck} 同一个套路）。</p>
 *
 * <h3>★ 与上一版的关键差别：这里不再有「落点必须留在同一格」的约束</h3>
 * <p>上一版阻挡是<b>每 tick 把敌人 setPos 到锚点</b>，所以锚点必须落在棋子正前方那一格内 ——
 * 否则「按整格判定的攻击范围」就打不到外圈那个敌人。那一版因此把横向错开压到 0.2 格，
 * 还写了整整一段推导当理由。</p>
 * <p>现在阻挡改成<b>只下发寻路目标</b>（见 {@code BlockAnchorStrategy}）：敌人是自己走过去的，
 * 锚点落在哪一格都不影响「它算不算被挡住」（那由阻挡搜索范围决定），也不影响
 * 「墙」分支的攻击（它们打的是<b>被挡集合</b>，不再按格子判定）。所以：</p>
 * <ul>
 *     <li>横向错开按设计口径放宽到 <b>0.3</b> 格（观感上「并排挤在棋子前面」更清楚）；</li>
 *     <li>旧的 {@code anchorCell(...)}（把落点换算成格子）已删除 —— 它服务的那个约束没有了。
 *         <b>约束随实现改变而失效，别把旧约束当圣旨</b>。</li>
 * </ul>
 *
 * <h3>坐标约定（与 PieceFacing 一致）</h3>
 * <p>{@code dx/dz} 是棋子朝向的**世界**单位向量（+X 东、+Z 南）；
 * 「右手方向」= 前方顺时针 90° = {@code (-dz, dx)}。</p>
 */
public final class BlockGeometry {

    /** Minecraft 的 tick 频率：1 秒 = 20 tick。 */
    public static final int TICKS_PER_SECOND = 20;

    /**
     * <b>攻击间隔倍率（全局，可调）</b>。
     *
     * <h3>口径（设计约定）</h3>
     * <p>「目前的攻击速度有些过快了，先统一乘 1.5 倍试试」+ 随后的更正
     * 「<b>是攻击间隔乘 1.5 倍，不是攻速</b>」 —— 也就是出手<b>变慢</b>：</p>
     * <pre>
     * 实际间隔 tick = 分支的基础间隔(秒) × 20 × ATTACK_INTERVAL_MULTIPLIER
     * </pre>
     *
     * <p><b>2026-10 第二轮：1.5 → 2.0</b>（设计：「对攻击间隔做出进一步延长」）。
     * 频率变成表值的 1/2。</p>
     *
     * <p><b>⚠️ 为什么标注「间隔」而不是「速度」</b>：「攻击速度」这个词在本工程里有歧义 ——
     * PRTS 的 <b>ASPD（攻击速度 +N）是越大越快</b>，而<b>攻击间隔是越大越慢</b>，
     * 两者的倍率互为倒数（`实际周期 = 间隔 ÷ (1 + 攻速/100)`，设计说明有这条口径）。
     * 第一版我按「攻速 ×1.5」实现成了 `间隔 / 1.5`（更快），方向正好反了（踩坑记录）。</p>
     *
     * <p><b>为什么不改表格那一列</b>：{@code UnitBranch.attackIntervalSeconds()} 是
     * 《…对照表》里逐分支的基础间隔（PRTS 口径），试玩手感不该污染它。手感在此处调。</p>
     *
     * <p>1.0 = 表里的原值。改这一个数就全局生效，所有分支同倍率。</p>
     */
    public static final double ATTACK_INTERVAL_MULTIPLIER = 2.0D;

    /**
     * 锚点：棋子朝向的<b>正前方</b>多少格（占位策略的数字）。
     *
     * <p>取 1.3 与上一版一致：1.0 太近（敌人中心与棋子碰撞箱重叠，等于贴脸）、
     * 2.0 太远（敌人够不到棋子），1.3 是「够得着、又不重叠」的位置。</p>
     */
    public static final double ANCHOR_FORWARD = 1.3D;

    /**
     * 相邻名次之间的<b>横向错开</b>（格）。
     *
     * <p>设计口径：「第一个在最左、第二个居中、第三个最右，每个错开 0.3 格」。
     * 规则见 {@link #lateralOffset}：以中间为轴对称分列，所以 1 个不散、3 个正好是
     * -0.3 / 0 / +0.3，2 个落在 ±0.15。</p>
     */
    public static final double ANCHOR_LATERAL_STEP = 0.3D;

    /**
     * 认为「已经走到锚点」的距离（格）。
     *
     * <p>走到这么近就不再下发寻路（让敌人停在锚点上，由它自己的攻击 AI 打棋子）。
     * 取 0.5 与生物碰撞箱半径同量级：再小会出现「明明站到了却一直原地抖」。</p>
     */
    public static final double ANCHOR_ARRIVE_DISTANCE = 0.5D;

    private BlockGeometry() {
    }

    /**
     * 第 {@code slot} 个（共 {@code total} 个）被挡住的敌人的**横向偏移**（格）。
     *
     * <p>对称分列：{@code lateral = (slot - (total-1)/2) × ANCHOR_LATERAL_STEP}。
     * 偶数个时中间会空出来（2 个落到 ±0.15），这是刻意的 —— 让它们始终对称地贴在棋子两侧，
     * 而不是一个正中、一个偏一边。</p>
     *
     * @param slot  名次，0 起；越界会被夹到 [0, total-1]
     * @param total 名额内敌人的总数；&lt;= 1 表示不散开
     */
    public static double lateralOffset(int slot, int total) {
        int n = Math.max(1, total);
        int s = Math.max(0, Math.min(slot, n - 1));
        if (n <= 1) {
            return 0.0D;
        }
        return (s - (n - 1) / 2.0D) * ANCHOR_LATERAL_STEP;
    }

    /**
     * 计算「第 {@code slot} 个（共 {@code total} 个）被挡住的敌人」相对棋子所在方块
     * <b>最小角</b>的偏移：{@code [dx, dz]}，格子边长 1.0。
     *
     * <p>换算成世界坐标就是 {@code blockPos.getX() + result[0]}（Z 同理）。</p>
     *
     * @param facing 棋子朝向（不能为 null）
     */
    public static double[] anchorOffset(PieceFacing facing, int slot, int total) {
        if (facing == null) {
            throw new IllegalArgumentException("facing 不能为 null");
        }
        double lateral = lateralOffset(slot, total);
        // 右手方向（= 前方顺时针 90°）：(-dz, dx)
        double rightX = -facing.dz();
        double rightZ = facing.dx();
        return new double[]{
                0.5D + facing.dx() * ANCHOR_FORWARD + rightX * lateral,
                0.5D + facing.dz() * ANCHOR_FORWARD + rightZ * lateral};
    }

    /**
     * 基础攻击间隔（秒）→ 实际出手间隔（tick）：{@code round(base * 20 * 倍率)}。
     *
     * <p>返回 0 只有一种情况：{@code baseSeconds <= 0}（表格没给间隔 = 该分支不参与自动攻击）。
     * 只要表里有值，结果<b>至少 1 tick</b> —— 否则 {@code Math.round} 在极端参数下
     * 可能把它算成 0，而 0 在上层被解释成「不攻击」，
     * 于是「调节奏」反而把分支改成不攻击了（同 踩坑记录）。</p>
     *
     * <p>本重载用<b>默认倍率</b>（{@link #ATTACK_INTERVAL_MULTIPLIER}）。
     * 运行时那份倍率是一个<b>可调变量</b>（自验命令会临时改成 1.0，
     * 见 {@code PawnCombatManager.setAttackIntervalMultiplier}），
     * 所以那边调的是下面那个带倍率参数的重载 —— 两边共用同一个算式。</p>
     */
    public static int intervalTicks(double baseSeconds) {
        return intervalTicks(baseSeconds, ATTACK_INTERVAL_MULTIPLIER);
    }

    /** 同上，但显式给倍率（自验/调参用；{@code multiplier <= 0} 视为非法）。 */
    public static int intervalTicks(double baseSeconds, double multiplier) {
        if (!(multiplier > 0.0D)) {
            throw new IllegalArgumentException("攻击间隔倍率必须 > 0，收到 " + multiplier);
        }
        if (baseSeconds <= 0.0D) {
            return 0;
        }
        return Math.max(1, (int) Math.round(
                baseSeconds * TICKS_PER_SECOND * multiplier));
    }
}
