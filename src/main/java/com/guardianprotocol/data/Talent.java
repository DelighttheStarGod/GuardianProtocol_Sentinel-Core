package com.guardianprotocol.data;

/**
 * 一个**天赋槽位**的内容：天赋名 / 天赋效果 / 天赋作用对象。
 *
 * <h2>★ 本工程「只解析、不执行」</h2>
 * <p>设计口径（原话）：「天赋目前需要空出两个接口（『天赋1』和『天赋2』），
 * 到时候在干员导入时跟着玩家的干员导入表一起被脚本解析填入，天赋在脚本内解析时需要注重
 * <b>天赋名</b>、<b>天赋效果</b>、<b>天赋作用对象</b>。（这个目前不做和后续一起做）」</p>
 *
 * <p>所以这个类型现在的职责只有一条：<b>把三个字段的落点固定下来</b>，让后面的干员导入脚本
 * 有地方可写、让下游项目有地方可读。<b>本工程不会解释 {@link #effect()}，也不会执行它</b>。</p>
 *
 * <h2>★★ 2026-10-07 设计口径：**槽位现在全空**</h2>
 * <p>设计原话：「<b>天赋不填</b>，目前应该是 72 个分支的天赋槽<b>全空</b>，模组只需要像天赋一样
 * 留一个待定槽即可」。</p>
 *
 * <p>⇒ 生成链**不再把参考表里的天赋原文写进枚举**：72 个内置分支的 {@link BranchDef#talent1()} /
 * {@link BranchDef#talent2()} <b>一律是 {@link #empty()}</b>（本轮之前它们抄的是模板干员那一行）。
 * 参考表里那些原文仍然留在 生成链参考表 与 乘区归类表 表 2
 * 供「干员导入」时对照，但**代码里没有它们** —— 棋子的天赋要等玩家的导入表进来才成立。</p>
 *
 * <h2>三个字段各是什么</h2>
 * <ul>
 *     <li>{@link #name()} <b>天赋名</b>：如「军事传统」「不毁重构」。对照表里那一列就有；</li>
 *     <li>{@link #effect()} <b>天赋效果</b>：效果的原文（含数值），对照表里那一列也有；</li>
 *     <li>{@link #target()} <b>天赋作用对象</b>：这条天赋**作用在谁身上** ——
 *         自身 / 范围内友方 / 编入队伍的所有【先锋】/ 被攻击的目标 …
 *         <p><b>★ 对照表里没有这一列</b>，所以内置分支的 {@code target} 目前一律是空串。
 *         它是给**干员导入**预定的：那时脚本按玩家的导入表把这一格填上（同一个天赋在不同
 *         干员身上作用对象可能不同），本工程不猜、也不兜底成一个默认值。</p></li>
 * </ul>
 *
 * <h2>两个槽位在哪</h2>
 * <p>{@link BranchDef#talent1()} 与 {@link BranchDef#talent2()}。
 * 对外门面见 {@code com.guardianprotocol.api.Talents}。</p>
 *
 * <h2>为什么不是「两个 interface」而是「一个类型 + 两个槽位」</h2>
 * <p>槽位（天赋1 / 天赋2）是**位置**，{@code Talent} 是**内容**。把位置做成两个接口会得到
 * 两个一模一样的签名，还得在每个实现类里各写一遍；而现在的写法：槽位在 {@link BranchDef}
 * 上（各自可以有自己的默认实现），内容只有一个类型，将来导入脚本填的是内容、不是新类型。</p>
 *
 * @param name   天赋名（可为空串 = 该分支没写这一格）
 * @param effect 天赋效果原文（可为空串）
 * @param target 天赋作用对象（**内置分支一律空串**，等干员导入脚本填）
 */
public record Talent(String name, String effect, String target) {

    /** 把三个字段都收紧成非 null（生成物与数据包都可能给 null）。 */
    public Talent {
        name = name == null ? "" : name;
        effect = effect == null ? "" : effect;
        target = target == null ? "" : target;
    }

    /** 空槽位（没写天赋）。 */
    public static Talent empty() {
        return new Talent("", "", "");
    }

    /** 三个字段一起造（生成物用这个工厂；{@code target} 现在传空串，见类注释）。 */
    public static Talent of(String name, String effect, String target) {
        return new Talent(name, effect, target);
    }

    /** 只有名字与效果（**内置分支用这个**：作用对象还没解析出来）。 */
    public static Talent of(String name, String effect) {
        return new Talent(name, effect, "");
    }

    /** 这个槽位有没有内容（名字或效果有一个非空就算有）。 */
    public boolean present() {
        return !name.isEmpty() || !effect.isEmpty();
    }

    /** 作用对象**是否已经解析出来**（干员导入之前的答案一律是 false）。 */
    public boolean hasTarget() {
        return !target.isEmpty();
    }

    /**
     * 一行摘要（报告与提示框用）。
     *
     * <p>作用对象没解析出来时如实写成「作用对象待干员导入解析」，<b>不</b>留空 ——
     * 空着看起来像「这条天赋不作用于任何人」，会误导读报告的人。</p>
     */
    public String describe() {
        if (!present()) {
            return "（无）";
        }
        String t = hasTarget() ? target : "作用对象待干员导入解析";
        return effect.isEmpty() ? name + "（" + t + "）" : name + "：" + effect + "（" + t + "）";
    }
}
