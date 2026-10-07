package com.guardianprotocol.api;

import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.Talent;

import javax.annotation.Nullable;

/**
 * 「天赋两个槽位」的公开查询接口（{@link BranchDef#talent1()} / {@link BranchDef#talent2()}
 * 的门面）。
 *
 * <h2>★ 这个门面现在只解决一件事：把三个字段的落点固定下来</h2>
 * <p>设计口径（原话）：「天赋目前需要空出两个接口（『天赋1』和『天赋2』），
 * 到时候在干员导入时跟着玩家的干员导入表一起被脚本解析填入，天赋在脚本内解析时需要注重
 * <b>天赋名</b>、<b>天赋效果</b>、<b>天赋作用对象</b>。（这个目前不做和后续一起做）」</p>
 *
 * <p>所以：</p>
 * <ul>
 *     <li><b>本工程不执行天赋</b> —— 没有任何代码会去解释 {@link Talent#effect()} 里的文本；</li>
 *     <li>★★ <b>两个槽位现在全是空的</b>（设计口径：「<b>天赋不填</b>，目前应该是
 *         72 个分支的天赋槽<b>全空</b>」）⇒ {@link #present(BranchDef)} 对**所有内置分支**
 *         都是 {@code false}。本轮之前这里抄的是参考表里**模板干员那一行**的天赋原文，
 *         那会让人误以为「这个分支自带这条天赋」—— 参考原文仍留在 生成链参考表
 *         与 乘区归类表 表 2，但**代码里没有它们**；</li>
 *     <li>连<b>天赋作用对象</b>也一样是空串（{@link Talent#hasTarget()} 为 {@code false}）——
 *         它是给**干员导入**脚本按玩家的导入表填的。本工程不猜、也不兜底成一个默认值
 *         （兜底会让「还没解析」看起来像「作用对象就是它」）。</li>
 * </ul>
 *
 * <h2>下游怎么用</h2>
 * <pre>{@code
 * BranchDef branch = Branches.byKey("SNIPER_MARKSMAN");
 * Talent t1 = Talents.first(branch);
 * if (t1.present()) { ... t1.name(), t1.effect(), t1.target() ... }
 * }</pre>
 *
 * <p>注意 {@link #first(BranchDef)} / {@link #second(BranchDef)} <b>永远不返回 null</b>：
 * 没写的槽位返回 {@link Talent#empty()}。所以下游不用判空，只看 {@link Talent#present()}。</p>
 */
public final class Talents {

    private Talents() {
    }

    /** 天赋 1（没写时返回空槽位，**不是** null）。 */
    public static Talent first(BranchDef branch) {
        return branch == null ? Talent.empty() : branch.talent1();
    }

    /** 天赋 2（没写时返回空槽位，**不是** null）。 */
    public static Talent second(BranchDef branch) {
        return branch == null ? Talent.empty() : branch.talent2();
    }

    /** 两个槽位拼成一行（报告 / 提示框用）。 */
    public static String describe(BranchDef branch) {
        if (branch == null) {
            return "（无分支）";
        }
        Talent a = branch.talent1();
        Talent b = branch.talent2();
        StringBuilder sb = new StringBuilder();
        sb.append("天赋1：").append(a.describe());
        sb.append("｜天赋2：").append(b.describe());
        return sb.toString();
    }

    /**
     * 这个分支**有没有**天赋（两个槽位任一有内容即真）。
     *
     * <p>★ 2026-10-07 起**所有内置分支都是 {@code false}**（天赋槽全空，等干员导入填）。
     * 它现在的作用是「将来导入表填完之后，要不要显示天赋那一栏」的判据。</p>
     */
    public static boolean present(@Nullable BranchDef branch) {
        return branch != null && (branch.talent1().present() || branch.talent2().present());
    }
}
