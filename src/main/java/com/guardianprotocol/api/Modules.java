package com.guardianprotocol.api;

import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.ModuleEffect;

import javax.annotation.Nullable;

/**
 * 「模组待定槽」的公开查询接口（{@link BranchDef#module()} 的门面）。
 *
 * <h2>★ 设计口径（原话）</h2>
 * <p>「天赋不填，目前应该是 72 个分支的天赋槽全空，<b>模组只需要像天赋一样留一个待定槽即可</b>」。</p>
 *
 * <p>所以这个门面和 {@link Talents} 是**一对**：两个槽都只固定字段落点、都不执行内容。
 * 区别只有「槽位语义」—— 天赋是干员自带的，模组是**专属模组**（例：CEN-X
 * 「攻击被阻挡的敌人攻击力提升至 110%」）。</p>
 *
 * <ul>
 *     <li>★★ <b>72 个内置分支现在一律是空槽位</b> ⇒ {@link #present(BranchDef)} 对所有内置分支
 *         都是 {@code false}；{@link #source(BranchDef)} 会打出那句「待定」以及内容去了哪；</li>
 *     <li>本轮同时把原先**默认生效**的 5 条<b>模组伤害乘区</b>（强攻手/无畏者/要塞/速射手/攻城手）
 *         从规则表里摘了出来 —— 它们本来就是模组效果，不该在「未开启模组」的基线里生效。
 *         出处与倍率留档在 生成链 的 {@code MODULE_DAMAGE_MODS_PENDING}，
 *         108 条模组特性原文留在 {@code PRTS 对照报告} 附录 A；</li>
 *     <li><b>本工程不执行模组效果</b> —— 没有任何代码会去解释 {@link ModuleEffect#effect()}。</li>
 * </ul>
 *
 * <h2>下游怎么用</h2>
 * <pre>{@code
 * BranchDef branch = Branches.byKey("GUARD_CENTURION");
 * ModuleEffect m = Modules.get(branch);
 * if (m.present()) { ... m.name(), m.effect(), m.target() ... }
 * }</pre>
 */
public final class Modules {

    private Modules() {
    }

    /** 模组槽（没填时返回空槽位，**不是** null）。 */
    public static ModuleEffect get(BranchDef branch) {
        return branch == null ? ModuleEffect.empty() : branch.module();
    }

    /** 这个分支**有没有**模组内容（现在一律 {@code false}）。 */
    public static boolean present(@Nullable BranchDef branch) {
        return branch != null && branch.module().present();
    }

    /** 模组槽的出处（内置分支现在都是那句「待定」）。 */
    public static String source(@Nullable BranchDef branch) {
        return branch == null ? "（无分支）" : branch.moduleSource();
    }

    /** 一行摘要（报告 / 提示框用）：空槽位如实写成「待定」，不留空。 */
    public static String describe(BranchDef branch) {
        if (branch == null) {
            return "（无分支）";
        }
        return "模组：" + branch.module().describe();
    }
}
