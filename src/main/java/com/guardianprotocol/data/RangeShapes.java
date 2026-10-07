package com.guardianprotocol.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 攻击范围形状表（<b>由脚本生成，不要手改</b>）。
 *
 * <p>数据链（<b>形状的生成源是图片</b>，理由见 设计说明）：</p>
 * <pre>
 * 精二攻击范围截图/&lt;代号&gt;.png        27 张 PRTS 原图的屏幕截图（一手证据）
 *        ↓ 生成链脚本   解像素 → 反推格子 → 转置 + 定向
 *      range_shapes.txt
 *        ↓ 生成链脚本
 *      本文件
 *
 * 反向核对（只查不生成）：
 *   生成链脚本       图片 ↔ 本表 ↔ 文档「文本示意」
 *   生成链脚本    文档一览索引表 ↔ UnitBranch.java ↔ 本表
 * </pre>
 *
 * <p><b>字符含义</b>（自上而下 = z 从大到小，最上面一行是正前方最远处）：</p>
 * <ul>
 *     <li>{@code X} = 可攻击格</li>
 *     <li>{@code O} = 自身所在格（每个形状恰好一个）</li>
 *     <li>{@code .} = 空</li>
 * </ul>
 *
 * <p><b>朝向与两个坐标系</b>：截图里 <b>列</b> 是游戏的前后轴
 * （列号增大 = 正前方），<b>行</b> 是左右轴；本表把两轴对调：
 * 本表 <b>行</b> 是前后轴（{@code O} 所在行即 z=-1），<b>列</b> 是左右轴。
 * 于是本表里的「向右」就是棋子的正前方，朝向由 {@link PieceFacing} 旋转。</p>
 *
 * <p>「列号增大 = 正前方」有三条互相独立的证据（别再靠感觉猜）：</p>
 * <ol>
 *     <li>{@code 1-1} 的图是「□ 与 ■ <b>左右相邻</b>」，而 {@code 1-1} 的语义是
 *         「本格 + 正前 1 格」—— 前后轴只可能是横向；</li>
 *     <li>{@code 4-9} 是<b>箭头</b>形，箭头朝右张开，张开的方向就是前；</li>
 *     <li>{@code 3-3}（速射手）的图是 <b>4 宽 × 3 高</b>，即向前 4 格 ——
 *         速射手本来就是「向前伸得远」的职业；若把「行」当前后轴会变成 4 宽 3 深，
 *         那就不是速射手的范围了。</li>
 * </ol>
 *
 * <p>⚠️ 《…（截图版）.md》的「说明」里原本写着「默认朝向向上」，
 * 与上面三条证据矛盾，**已按证据更正** —— 这句写反会让全部 27 个范围旋转 90°。</p>
 *
 * <p><b>{@code O} 不一定在最后一行</b>：{@code AttackRange.resolve} 是以
 * {@code O} 所在行锚定 z=-1 的，所以形状可以往「身后」延伸
 * （{@code y-6} 的护佑者/守望者就是身前 2 列 + 身后 1 列）。
 * 早先 {@code to_shape} 假定「身后没有格子」，把这些格子静默丢掉了，别再改回去。</p>
 *
 * <p><b>注意 {@code 0-1}</b>：它只有自身一格 —— 那**就是**它的攻击范围，
 * 也就是「只打得到站在自己身上的敌人」。重装·本源铁卫 / 守护者 这类纯阻挡单位就是它。
 * ⚠️ 早先这里写作「代表『没有攻击范围』」，那个说法是错的：自身格一直算在范围里
 * （见 {@code AttackRange.resolve} 对 {@code O} 的处理），而且干员**挡住的**敌人
 * 一律按「站在自身格里」算，所以 {@code 0-1} 的分支照样打得到它挡住的人
 * （设计约定的口径，落地在 {@code PawnCombatManager.inAttackRange}）。</p>
 */
final class RangeShapes {

    /** rangeId -> 形状。键就是游戏里的范围代号（如 {@code 3-1}、{@code y-6}）。 */
    private static final Map<String, String> SHAPES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 1 行 x 1 列
        m.put("0-1", String.join("\n", "O"));

        // 2 行 x 1 列
        m.put("1-1", String.join("\n", "X", "O"));

        // 2 行 x 3 列
        m.put("1-2", String.join("\n", ".X.", "XOX"));

        // 2 行 x 3 列
        m.put("1-3", String.join("\n", "XXX", ".O."));

        // 3 行 x 1 列
        m.put("2-2", String.join("\n", "X", "X", "O"));

        // 3 行 x 3 列
        m.put("2-5", String.join("\n", "XXX", "XXX", ".O."));

        // 4 行 x 3 列
        m.put("3-1", String.join("\n", ".X.", "XXX", "XXX", "XOX"));

        // 4 行 x 1 列
        m.put("3-2", String.join("\n", "X", "X", "X", "O"));

        // 4 行 x 3 列
        m.put("3-3", String.join("\n", "XXX", "XXX", "XXX", "XOX"));

        // 4 行 x 5 列
        m.put("3-4", String.join("\n", ".XXX.", "XXXXX", "XXXXX", "XXOXX"));

        // 3 行 x 5 列
        m.put("3-5", String.join("\n", ".XXX.", "XXXXX", "XXOXX"));

        // 3 行 x 3 列
        m.put("3-6", String.join("\n", "XXX", "XXX", "XOX"));

        // 5 行 x 5 列
        m.put("3-9", String.join("\n", "..X..", ".XXX.", "XXXXX", "XXXXX", "XXOXX"));

        // 5 行 x 3 列
        m.put("3-10", String.join("\n", "XXX", "XXX", "XXX", "XXX", "XOX"));

        // 4 行 x 3 列
        m.put("3-12", String.join("\n", ".X.", ".X.", "XXX", "XOX"));

        // 4 行 x 3 列
        m.put("3-14", String.join("\n", "XXX", "XXX", "XXX", ".O."));

        // 4 行 x 5 列
        m.put("3-17", String.join("\n", "XXXXX", "XXXXX", "XXXXX", ".XOX."));

        // 4 行 x 5 列
        m.put("3-18", String.join("\n", "..X..", ".XXX.", "XXXXX", "XXOXX"));

        // 5 行 x 5 列
        m.put("4-3", String.join("\n", ".XXX.", "XXXXX", "XXXXX", ".....", "..O.."));

        // 6 行 x 3 列
        m.put("4-6", String.join("\n", ".X.", "XXX", "XXX", "...", "...", ".O."));

        // 5 行 x 3 列
        m.put("4-9", String.join("\n", ".X.", ".X.", ".X.", "XXX", "XOX"));

        // 6 行 x 1 列
        m.put("5-1", String.join("\n", "X", "X", "X", "X", "X", "O"));

        // 5 行 x 5 列
        m.put("x-1", String.join("\n", "..X..", ".XXX.", "XXOXX", ".XXX.", "..X.."));

        // 4 行 x 3 列
        m.put("y-1", String.join("\n", ".X.", "XXX", "XOX", "XXX"));

        // 4 行 x 3 列
        m.put("y-2", String.join("\n", "XXX", "XXX", "XOX", "XXX"));

        // 4 行 x 5 列
        m.put("y-6", String.join("\n", ".XXX.", "XXXXX", "XXOXX", ".XXX."));

        // 5 行 x 3 列
        m.put("y-7", String.join("\n", "XXX", "XXX", "XXX", "XOX", "XXX"));
        SHAPES = Collections.unmodifiableMap(m);

        // 自检：每个形状必须恰好一个 'O'，否则自身格定位会错，整个范围都会偏
        for (Map.Entry<String, String> e : SHAPES.entrySet()) {
            long n = e.getValue().chars().filter(ch -> ch == 'O').count();
            if (n != 1) {
                throw new IllegalStateException(
                        "攻击范围 " + e.getKey() + " 的 'O' 数量应为 1，实际 " + n);
            }
        }
    }

    private RangeShapes() {
    }

    /** 取某个代号的形状；不存在返回 null。 */
    static String get(String code) {
        return SHAPES.get(code);
    }

    /** 全部代号（便于调试与列举）。 */
    static java.util.Set<String> codes() {
        return SHAPES.keySet();
    }
}
