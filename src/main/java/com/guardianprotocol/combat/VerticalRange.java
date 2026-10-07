package com.guardianprotocol.combat;

import java.util.Locale;

/**
 * 攻击的垂直判定口径（<b>纯算术，不引用任何 Minecraft 类</b>）。
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>与 {@link BlockGeometry} / {@link ProjectileFlight} 同一个理由：垂直判定是纯算术
 * （两个脚底高度相减，看落在不在区间里），写在 {@code PawnCombatManager} 里就<b>只能跑起来验</b>。
 * 抽到这里之后，离线核对工具 可以脱离 Minecraft 把
 * 「哪个高度差算打得到、哪个算够不着」全算一遍并打印出来
 * （{@link #describe} 那行文本就是为报告准备的）。</p>
 *
 * <h3>★ 为什么不再用「整格高碰撞盒」来隐含限制（2026-10 设计确定）</h3>
 * <p>之前的垂直限制不是写出来的，而是<b>副作用</b>：判断「这个格子能不能打到」用的是一个
 * <b>整格高</b>（y 从格底到 +1）的 AABB，目标的碰撞箱只要不与它相交就算够不着。
 * 它确实挡住了「高出一格以上」的目标，但有两个毛病：</p>
 * <ol>
 *     <li><b>它把区间锁死在上下各一格</b> —— 于是模板里写的「远程单位没有垂直盲区」
 *         从未真正生效：远程棋子打站在 3 格高台上的敌人时，两个盒子根本不相交。
 *         口径说是「无盲区」，实机是「上下各一格」，两者对不上，而且<b>不报任何错</b>；</li>
 *     <li><b>约束藏在几何相交里</b>，读代码看不出来，边界也不干净 ——
 *         半砖、台阶、正在下落的敌人各算哪一档，得反推 AABB 才说得清。</li>
 * </ol>
 * <p>现在把区间<b>显式写出来</b>：判定逻辑一行不改，试玩后只调下面这四个常量
 * （设计口径：远程 / 对空上下各 4 格；只打地面时上方 1 格、下方 2 格）。</p>
 *
 * <h3>与「阻挡」的同层容差不是一回事</h3>
 * <p>{@code PawnCombatManager.isOnSameGroundLevel} 的那个 1.0 格管的是「能不能被挡住」，
 * 本类管的是「能不能打到」。两者各有各的口径，<b>不要合并</b>：合并之后改一个会动到另一个，
 * 而它们本来就该分别调（一个是站位手感，一个是打击范围）。</p>
 *
 * <h3>坐标约定</h3>
 * <p>一律用实体的<b>脚底</b> Y（就是 {@code Entity#getY()}，原版实体的 y 坐标即碰撞箱底面），
 * 与项目里其它地方（{@code isOnSameGroundLevel}、{@link BlockGeometry}）保持同一套口径。
 * 高度差定义为 {@code 目标脚底 - 自己脚底}：正数 = 目标更高。</p>
 */
public final class VerticalRange {

    /** 远程 / 对空：向上容忍 4 格。 */
    public static final double RANGED_UP = 4.0D;

    /** 远程 / 对空：向下容忍 4 格。 */
    public static final double RANGED_DOWN = 4.0D;

    /** 只打地面：上方 1 格（打不到明显比自己高的）。 */
    public static final double GROUND_UP = 1.0D;

    /** 只打地面：下方 2 格（但能打到脚下的）。 */
    public static final double GROUND_DOWN = 2.0D;

    /**
     * 垂直判定的两种口径。
     *
     * <h3>取值与它们对应的分支</h3>
     * <ul>
     *     <li>{@link #RANGED} —— 远程 / 对空（上下各 {@link #RANGED_UP} / {@link #RANGED_DOWN} 格）。
     *         数据侧的 {@code UnitBranch.VerticalTargeting.RANGED} 与 {@code AIR_AND_GROUND}
     *         <b>都</b>归到这一档（两者在垂直判定上没有区别：能对空就等于没有盲区）；
     *         这个映射在调用方一处完成，别处不许再判；</li>
     *     <li>{@link #GROUND_ONLY} —— 只打地面（上方 {@link #GROUND_UP} 格、下方 {@link #GROUND_DOWN} 格）。
     *         上方收得比下方紧，正是设计口径「打不到明显比自己高的，但能打到脚下的」。</li>
     * </ul>
     */
    public enum Mode {
        /** 远程 / 对空：上下各 4 格，真正做到没有盲区。 */
        RANGED,
        /** 只打地面：上方 1 格、下方 2 格。 */
        GROUND_ONLY
    }

    private VerticalRange() {
    }

    /**
     * 这个口径允许目标比自己<b>高</b>多少格（正数，闭区间上界）。
     *
     * <p>{@code mode} 为 null 时按 {@link Mode#GROUND_ONLY} 处理：本方法跑在战斗循环里
     * （每 tick、每个目标都调），抛异常等于把服务器日志刷满；而 null 只可能来自接线错误，
     * 退到<b>最保守</b>的地面口径不会误伤 —— 顶多少打几个高处的目标，
     * 绝不会因为一个 null 就把「远程没有盲区」放宽成无限高。</p>
     */
    public static double up(Mode mode) {
        return normalize(mode) == Mode.RANGED ? RANGED_UP : GROUND_UP;
    }

    /** 这个口径允许目标比自己<b>低</b>多少格（正数，闭区间下界的绝对值）；null 同 {@link #up}。 */
    public static double down(Mode mode) {
        return normalize(mode) == Mode.RANGED ? RANGED_DOWN : GROUND_DOWN;
    }

    /**
     * 目标的<b>脚底</b>高度差是否落在允许区间内（<b>闭区间，含边界</b>）。
     *
     * <p>判据就是一行：{@code -down <= (目标脚底 - 自己脚底) <= +up}。
     * 边界算「打得到」—— 给定的是「上下各 4 格 / 上 1 下 2」这种整数口径，
     * 恰好落在 4.0 上的目标当然算在里面（写成开区间的话，站在整格高台上的敌人
     * 会因为一个浮点尾数打不到，那是典型的「差一格」手感 bug）。</p>
     *
     * <p>NaN（某一边的坐标坏掉）一律判<b>不覆盖</b>：NaN 参与比较恒为 false，
     * 这里显式写出来，免得下次有人以为它也能走通区间判断。</p>
     */
    public static boolean covers(Mode mode, double unitFeetY, double targetFeetY) {
        double delta = targetFeetY - unitFeetY;
        if (Double.isNaN(delta)) {
            return false;
        }
        return delta >= -down(mode) && delta <= up(mode);
    }

    /**
     * 便于自验 / 报告：把允许区间打印成 {@code "[-2.0, +1.0]"} 这样的字符串
     * （左端 = 允许的最低高度差 = 负数，右端 = 允许的最高高度差 = 正数）。
     *
     * <p>口径：只调常量时这行文本会跟着变，所以它可以当作「本轮到底放宽/收紧了什么」的
     * 一行证据放进自验报告 —— 数值改了而报告没变，就说明改的不是这里。</p>
     *
     * <p>格式化用 {@link Locale#ROOT}：固定小数点与正负号写法，免得跟着系统区域变
     * （土耳其语区域那类问题会让小数点变成逗号，报告就没法逐字比对了）。</p>
     */
    public static String describe(Mode mode) {
        // 0.0 - x 而不是 -x：x 为 0 时前者是 +0.0、后者是 -0.0，会打印成 "-0.0"。
        double low = 0.0D - down(mode);
        return String.format(Locale.ROOT, "[%+.1f, %+.1f]", low, up(mode));
    }

    /**
     * null 一律当 {@link Mode#GROUND_ONLY}（最保守的一档），理由见 {@link #up}。
     *
     * <p>只在这里判一次，{@link #up} / {@link #down} / {@link #covers} / {@link #describe}
     * 都走它 —— 免得四个入口各写一套 null 兜底、将来改口径时漏掉一个。</p>
     */
    private static Mode normalize(Mode mode) {
        return mode == null ? Mode.GROUND_ONLY : mode;
    }
}
