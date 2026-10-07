package com.guardianprotocol.data;

/**
 * 一个**模组槽位**的内容：模组名 / 模组效果 / 作用对象。
 *
 * <h2>★ 现在**全空**（设计口径）</h2>
 * <p>设计原话：「天赋不填，目前应该是 72 个分支的天赋槽全空，<b>模组只需要像天赋一样
 * 留一个待定槽即可</b>」。</p>
 *
 * <p>所以这个类型的职责和 {@link Talent} 一样只有一条：<b>把字段落点固定下来</b>，
 * 让后面的干员导入脚本有地方可写、让下游项目有地方可读。72 个内置分支的这个槽
 * <b>一律是 {@link #empty()}</b>；数据包分支不声明这个字段也返回空槽位。
 * 本工程<b>不解释也不执行</b> {@link #effect()}。</p>
 *
 * <h2>为什么字段形状与 {@link Talent} 故意一致</h2>
 * <p>名 / 效果 / 作用对象三个字段和天赋槽位<b>逐字段对齐</b>：将来「干员导入」的脚本用
 * 同一套解析逻辑填两个槽（玩家的导入表里模组与天赋各占一列）。两者只差「槽位语义」——
 * 天赋是干员自带的，模组是**专属模组**（例：CEN-X「攻击被阻挡的敌人攻击力提升至 110%」）。</p>
 *
 * <h2>已知内容去哪了</h2>
 * <p>本轮把原先**默认生效**的 5 条模组伤害乘区从规则表里摘了出来（它们本来就是模组效果，
 * 不该在「未开启模组」的基线里生效）：出处与倍率留在
 * 生成链 的 {@code MODULE_DAMAGE_MODS_PENDING}，
 * 108 条模组特性原文留在 {@code PRTS 对照报告} 附录 A。等这个槽有内容时再落地。</p>
 *
 * @param name   模组名（可为空串 = 该分支这个槽还没填）
 * @param effect 模组效果原文（可为空串）
 * @param target 作用对象（**现在一律空串**，等干员导入脚本按导入表填）
 */
public record ModuleEffect(String name, String effect, String target) {

    /** 把三个字段都收紧成非 null（生成物与数据包都可能给 null）。 */
    public ModuleEffect {
        name = name == null ? "" : name;
        effect = effect == null ? "" : effect;
        target = target == null ? "" : target;
    }

    /** **空槽位**：现在 72 个内置分支全是它（设计口径：「留一个待定槽即可」）。 */
    public static ModuleEffect empty() {
        return new ModuleEffect("", "", "");
    }

    /** 三个字段一起造（将来干员导入/模组表用这个工厂）。 */
    public static ModuleEffect of(String name, String effect, String target) {
        return new ModuleEffect(name, effect, target);
    }

    /** 只有名字与效果（作用对象还没解析出来时用）。 */
    public static ModuleEffect of(String name, String effect) {
        return new ModuleEffect(name, effect, "");
    }

    /** 这个槽位有没有内容（名字或效果有一个非空就算有）。 */
    public boolean present() {
        return !name.isEmpty() || !effect.isEmpty();
    }

    /** 一行摘要（报告与提示框用）：空槽位如实写成「待定」，不留空。 */
    public String describe() {
        if (!present()) {
            return "（待定 —— 模组槽现在是空的）";
        }
        String t = target.isEmpty() ? "作用对象待干员导入解析" : target;
        return effect.isEmpty() ? name + "（" + t + "）" : name + "：" + effect + "（" + t + "）";
    }
}
