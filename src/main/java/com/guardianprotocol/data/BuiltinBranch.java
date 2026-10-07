package com.guardianprotocol.data;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 内置分支：把生成的 {@link UnitBranch} 枚举包装成 {@link BranchDef}。
 *
 * <p>刻意做成「包装」而不是「把枚举里的字段抄一份」：枚举是生成链的产物、是数值的唯一真源
 * （见 设计说明），抄一份出来迟早会和它对不上。这里每个方法都<b>转发</b>给枚举。</p>
 *
 * <p>内置分支的 id 由枚举名小写而来：{@code SNIPER_MARKSMAN → guardian_protocol:sniper_marksman}。
 * 这条换算规则必须与资源/语言键一致（{@code branch.guardian_protocol.sniper_marksman}），
 * 所以只在这里写一次，别处一律调 {@link #id()} / {@link #key()}。</p>
 */
@SuppressWarnings("removal")   // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath（1.21 才加）
public final class BuiltinBranch implements BranchDef {

    /** 内置分支 id 的命名空间（与本 mod 的 modId 相同）。 */
    public static final String NAMESPACE = "guardian_protocol";

    private final UnitBranch branch;
    private final ResourceLocation id;

    /** 包一层内置分支（对外可见：自验台与数据包覆盖都要能直接包一个枚举）。 */
    @SuppressWarnings("removal")
    public BuiltinBranch(UnitBranch branch) {
        this.branch = branch;
        this.id = new ResourceLocation(NAMESPACE, branch.name().toLowerCase(Locale.ROOT));
    }

    /** 由枚举算 id（模块内与生成链核对都用它）。 */
    public static ResourceLocation idOf(UnitBranch branch) {
        return new ResourceLocation(NAMESPACE, branch.name().toLowerCase(Locale.ROOT));
    }

    /** 被包装的枚举。 */
    public UnitBranch branch() {
        return this.branch;
    }

    @Override
    public ResourceLocation id() {
        return this.id;
    }

    @Override
    public String key() {
        return this.branch.name();
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public Optional<UnitBranch> builtin() {
        return Optional.of(this.branch);
    }

    @Override
    public UnitClass unitClass() {
        return this.branch.unitClass();
    }

    @Override
    public String branchName() {
        return this.branch.branchName();
    }

    @Override
    public String attackRangeKey() {
        return this.branch.attackRangeKey();
    }

    /**
     * 打击格：先用分支自己的范围键，取不到再退回「所属职业的默认范围」。
     *
     * <p>这条兜底原本写在 {@code PixelUnit.getAttackCells()} 里，现在挪到数据层 ——
     * 自定义分支也要享受同一条兜底，放在这里才只有一份实现。</p>
     */
    @Override
    public List<AttackRange.Cell> attackCells() {
        List<AttackRange.Cell> cells = AttackRange.resolve(this.branch.attackRangeKey());
        if (cells.isEmpty()) {
            cells = AttackRange.resolve(this.branch.unitClass().defaultRangeKey());
        }
        return cells;
    }

    @Override
    public double maxHealth() {
        return com.guardianprotocol.combat.CombatStats.maxHealth(this.branch);
    }

    @Override
    public double attackDamage() {
        return com.guardianprotocol.combat.CombatStats.attackDamage(this.branch);
    }

    @Override
    public double armor() {
        return com.guardianprotocol.combat.CombatStats.armor(this.branch);
    }

    @Override
    public int blockCount() {
        return this.branch.blockCount();
    }

    /**
     * ★ 常态阻挡：<b>必须转发枚举里的字段</b>，不能退回 {@link BranchDef} 的默认实现。
     *
     * <p>默认实现返回 {@code blockCount()} —— 对数据包分支是对的（它们的 JSON 只有
     * {@code block} 一个数），但对内置分支就<b>错</b>了：解放者表里 3、常态 0，
     * 退回默认值会让它<b>又拿到碰撞箱、又占阻挡名额</b>（正是实测报告的那个现象）。
     * 这与 {@link #blockSearch()} / {@link #aoe()} 那两处「不转发就静默退回默认」是同一个坑。</p>
     */
    @Override
    public int normalBlockCount() {
        return this.branch.normalBlockCount();
    }

    /** 阻挡搜索范围：转发枚举里的字段（不要退回默认实现，否则守望者的九宫格会丢）。 */
    @Override
    public UnitBranch.BlockSearch blockSearch() {
        return this.branch.blockSearch();
    }

    @Override
    public String blockSearchSource() {
        return this.branch.blockSearchSource();
    }

    // ---- 群伤（2026-10 第二轮）：必须转发枚举字段，否则炮手/链术师/投掷手的群伤会**静默消失** ----

    @Override
    public UnitBranch.Aoe aoe() {
        return this.branch.aoe();
    }

    @Override
    public int attackCount() {
        return this.branch.attackCount();
    }

    @Override
    public double tailFactor() {
        return this.branch.tailFactor();
    }

    @Override
    public String aoeSource() {
        return this.branch.aoeSource();
    }

    // ---- 治疗（2026-10 第三轮）：同样必须转发，否则医疗职业会退化成「打敌人」 ----

    @Override
    public UnitBranch.Heal heal() {
        return this.branch.heal();
    }

    @Override
    public String healSource() {
        return this.branch.healSource();
    }

    // ---- 停顿（2026-10 第四轮）：同样必须转发，否则凝滞师/链术师的特性停顿会**静默消失** ----

    @Override
    public UnitBranch.Pause pause() {
        return this.branch.pause();
    }

    @Override
    public String pauseSource() {
        return this.branch.pauseSource();
    }

    // ---- 天赋两个槽位（2026-10）：同样必须转发 —— 漏转发会静默退回空槽位，
    //      表现是「生成物里明明有天赋、读出来却是（无）」，而且不报错 ----

    @Override
    public Talent talent1() {
        return this.branch.talent1();
    }

    @Override
    public Talent talent2() {
        return this.branch.talent2();
    }

    // ---- 禁疗（2026-10 第四轮）：漏转发会让武者/收割者/不屈者**又变成可以被治好**，
    //      而数据层那条断言读的是枚举、照样是绿的 ----

    @Override
    public boolean healable() {
        return this.branch.healable();
    }

    @Override
    public String healableSource() {
        return this.branch.healableSource();
    }

    /** 分支特性原文：**必须转发**，否则朝向界面那一行会是空的（生成物里明明有）。 */
    @Override
    public String traitText() {
        return this.branch.traitText();
    }

    /** 伤害乘区：**必须转发** —— 漏转发会静默退回「无乘区」，教官/领主/攻城手全都不加不减。 */
    @Override
    public UnitBranch.DamageMod damageMod() {
        return this.branch.damageMod();
    }

    @Override
    public String damageModSource() {
        return this.branch.damageModSource();
    }

    /**
     * 模组槽位：**必须转发** —— 槽现在是空的（设计口径），
     * 但漏转发同样是静默 bug：将来导入脚本填进枚举之后，界面/下游会读到「待定」而不是内容。
     */
    @Override
    public ModuleEffect module() {
        return this.branch.module();
    }

    @Override
    public String moduleSource() {
        return this.branch.moduleSource();
    }

    @Override
    public double attackIntervalSeconds() {
        return this.branch.attackIntervalSeconds();
    }

    @Override
    public UnitBranch.TargetPriority targetPriority() {
        return this.branch.targetPriority();
    }

    @Override
    public UnitBranch.TargetCount targetCount() {
        return this.branch.targetCount();
    }

    @Override
    public boolean canAttack() {
        return this.branch.canAttack();
    }

    @Override
    public String targetRuleSource() {
        return this.branch.targetRuleSource();
    }

    @Override
    public UnitBranch.VerticalTargeting verticalTargeting() {
        return this.branch.verticalTargeting();
    }

    /**
     * 攻击方式：直接转发枚举里那个字段（生成链的产物）。
     *
     * <p>注意<b>不要</b>退回 {@link BranchDef#attackMethod()} 的默认实现 ——
     * 那一条只按「远程位 / 近战位」推，会把情报官 / 领主 / 哨戒铁卫 / 要塞 /
     * 伏击客 / 钩索师这 6 个近战位远程分支判成近战武器。</p>
     */
    @Override
    public UnitBranch.AttackMethod attackMethod() {
        return this.branch.attackMethod();
    }

    @Override
    public String attackMethodSource() {
        return this.branch.attackMethodSource();
    }

    @Override
    public String toString() {
        return "BuiltinBranch[" + this.id + "]";
    }
}
