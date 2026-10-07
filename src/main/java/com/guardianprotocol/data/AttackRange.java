package com.guardianprotocol.data;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 攻击范围。
 *
 * <h3>数据来源（已改为游戏真实数据）</h3>
 * <p>早先两张参考表里没有逐格攻击范围，所以这里按职业特性自行设计了 5 种形状。
 * 现在改成了<b>游戏真实数据</b>：每个分支用其模板干员【精英二】的 {@code rangeId}，
 * 几何取自 {@code range_table.json}。形状表在 {@link RangeShapes}（脚本生成）。</p>
 *
 * <h3>坐标系约定（很重要，写判定逻辑时必须遵守）</h3>
 * <p>每个格子用两个整数 {@code (x, z)} 表示，相对「棋子自己所在的格子」：</p>
 * <ul>
 *     <li>{@code x}：左右方向。0 = 自己这一列，正 = 向右（棋子右手边）</li>
 *     <li>{@code z}：前后方向。<b>0 = 正前方一格</b>（棋子的朝向），
 *         -1 = 自己所在格，-2 = 身后一格</li>
 * </ul>
 *
 * <p>形状字符串里每一行是「从左到右的一排格子」，自上而下为 z 从大到小
 * （最上面那行是正前方最远处），字符含义：</p>
 * <ul>
 *     <li>{@code X} = 有攻击范围</li>
 *     <li>{@code O} = 自己所在格（必须且只能有一个）</li>
 *     <li>{@code .} = 空</li>
 * </ul>
 *
 * <p>形状里的「向右」= 棋子的正前方，朝向由 {@link PieceFacing} 负责旋转
 * （见 {@code PixelUnit.worldCells()}）。</p>
 */
public final class AttackRange {

    /** 一格：{x, z}。 */
    public record Cell(int x, int z) {
    }

    private AttackRange() {
    }

    /** 所有可用的范围代号（= 游戏里的 rangeId）。 */
    public static List<String> keys() {
        return new ArrayList<>(RangeShapes.codes());
    }

    /**
     * 把形状字符串解析成格子列表。
     *
     * @param key 范围代号；为 null 或不存在时返回空列表
     */
    public static List<Cell> resolve(@Nullable String key) {
        if (key == null) {
            return Collections.emptyList();
        }
        String shape = RangeShapes.get(key);
        if (shape == null) {
            return Collections.emptyList();
        }
        String[] rows = shape.split("\n");
        List<Cell> cells = new ArrayList<>();
        // 行自上而下 = z 从大到小（最上面那行是正前方最远处）。
        // 找到 'O' 所在的行与列，那一格的 z 就是 -1，x 就是 0，其余按相对偏移算。
        int originRow = -1;
        int midCol = 0;
        for (int r = 0; r < rows.length; r++) {
            int idx = rows[r].indexOf('O');
            if (idx >= 0) {
                originRow = r;
                midCol = idx;
                break;
            }
        }
        if (originRow < 0) {
            return Collections.emptyList();
        }
        for (int r = 0; r < rows.length; r++) {
            // originRow 那一行是 z=-1；往上（行号更小）z 增大
            int z = -1 + (originRow - r);
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                // ★ 'O'（自身所在格）也必须是可攻击格：原作里干员所在格一定在范围内
                //   （PRTS「面前一格」= 本格 + 正前 1 格）。早先这里只收 'X'，
                //   导致 1-1 只剩正前方一格、打不到脚下的敌人，而预览里那个
                //   「自身格」是单独画的，正好把这个问题盖住了。
                if (ch == 'X' || ch == 'O') {
                    cells.add(new Cell(c - midCol, z));
                }
            }
        }
        return Collections.unmodifiableList(cells);
    }

    /** 该范围里是否包含「自己所在格」（x=0, z=-1）。 */
    public static boolean includesSelf(String key) {
        return resolve(key).stream().anyMatch(c -> c.x() == 0 && c.z() == -1);
    }

    /**
     * 便于调试：把某个形状打印成多行文本。
     *
     * <p>注意这里会把形状整体缩放到「O 左侧补齐」的视角，
     * 直接原样返回即可 —— 形状里 O 不一定在最左列。</p>
     */
    public static String describe(String key) {
        String shape = RangeShapes.get(key);
        if (shape == null) {
            return "（未知范围：" + key + "）";
        }
        return shape.replace('X', '■').replace('O', '◎').replace('.', '·');
    }
}
