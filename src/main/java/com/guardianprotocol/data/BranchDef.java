package com.guardianprotocol.data;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * 一个「职业分支」的只读定义 —— 战斗、渲染、界面都只认这个接口。
 *
 * <h3>为什么不是直接用 {@link UnitBranch} 枚举</h3>
 * <p>枚举装不下<b>数据包加进来的分支</b>：枚举常量是编译期固定的。而下游项目
 * （「角色导入」）与玩家都需要能追加/覆盖分支，所以把「一个分支长什么样」抽成接口：
 * <ul>
 *     <li>{@link BuiltinBranch}：包装内置枚举，数值仍以生成的 {@code UnitBranch} 为真源；</li>
 *     <li>{@link JsonBranch}：来自数据包 JSON，可新增，也可覆盖内置分支的部分字段。</li>
 * </ul>
 * 查询统一走 {@link BranchRegistry}。</p>
 *
 * <h3>契约</h3>
 * <ul>
 *     <li>{@link #id()} 是**唯一身份**（{@code guardian_protocol:sniper_marksman}），
 *         存档、同步、语言键都以它/它的 path 为准，<b>不要</b>用 ordinal ——
 *         数据包一加分支，序号就会漂移。</li>
 *     <li>{@link #key()} 是给人看与写 NBT 的短名：内置分支 = 枚举名（如
 *         {@code SNIPER_MARKSMAN}），自定义分支 = id 的 path。老存档里存的正是枚举名，
 *         所以读取时必须先按 key 解析（见 {@link BranchRegistry#byKey}）。</li>
 *     <li>所有几何/数值方法都必须**自己兜底**（不含 null、不抛异常）：数据包是外部输入，
 *         写坏了只能降级，不能把服务器带崩。</li>
 * </ul>
 */
public interface BranchDef {

    /** 唯一身份。内置分支形如 {@code guardian_protocol:sniper_marksman}。 */
    ResourceLocation id();

    /** 短名（写 NBT、拼语言键用）：内置 = 枚举名，自定义 = id 的 path。 */
    String key();

    /** 是不是内置分支（内置的才有模板干员、再部署、技能文本这些表里来的字段）。 */
    boolean isBuiltin();

    /** 内置分支对应的枚举；自定义分支返回 {@link Optional#empty()}。 */
    Optional<UnitBranch> builtin();

    // ---------------- 身份 / 展示 ----------------

    UnitClass unitClass();

    /** 分支名（中文），例如「速射手」。棋子的自定义名与界面都直接用它。 */
    String branchName();

    /** 攻击范围键（{@link AttackRange} 的键）；没有则返回 null。 */
    String attackRangeKey();

    /**
     * 实际打击格（相对自身所在格的偏移）。
     *
     * <p><b>不允许返回 null</b>：自定义分支若没写范围，就退回「所属职业的默认范围」，
     * 这样数据包写漏一个字段不至于做出一个「打不到任何东西」的棋子。</p>
     */
    List<AttackRange.Cell> attackCells();

    // ---------------- 基础数值 ----------------

    double maxHealth();

    double attackDamage();

    double armor();

    // ---------------- 阻挡 ----------------

    /** 阻挡数（0 = 不阻挡）。★ 它是表里那一列的原值 = <b>技能期 / 显示值</b>，不是常态 —— 见 {@link #normalBlockCount()}。 */
    int blockCount();

    /**
     * <b>常态阻挡数</b>：这个分支<b>平时</b>能拦住几个敌人（= 怪能不能从它身上走过去）。
     *
     * <h3>★ 为什么它与 {@link #blockCount()} 是两个量（设计口径 实机发现）</h3>
     * <p>对照表的「阻挡数」那一列填的是<b>技能期 / 显示值</b> —— 最典型的例子是
     * <b>近卫 · 解放者</b>：表里那格是 3（技能期口径），而 PRTS 的特性原文写的是
     * 「<b>通常不攻击且阻挡数为0</b>」。于是「常态不挡敌人」的棋子照样拿到了碰撞箱、
     * 也照样被算作阻挡者（设计实机看到「伏击客会阻挡怪物」才暴露）。</p>
     *
     * <p>判据只有一处：生成链 的 {@code normal_block} ——
     * <b>默认 = 表里的 {@link #blockCount()}</b>（71 个分支的技能期值与常态值相同），
     * 例外清单 2 条（特种·伏击客 / 近卫·解放者，两条都带出处）。生成物里的字段是
     * {@code UnitBranch#normalBlockCount()}。</p>
     *
     * <h3>谁读它（两处，必须读同一个量）</h3>
     * <ol>
     *     <li>{@code entity/PixelUnit#canBeCollidedWith()}：<b>0 ⇒ 去掉碰撞箱</b>
     *         （怪物能从棋子身上走过去）；</li>
     *     <li>{@code combat/PawnCombatManager#updateBlocking} 的容量：<b>0 ⇒ 一个名额都不占</b>
     *         —— 否则会出现「不挡路却还去把怪拽到锚点」这种自相矛盾。</li>
     * </ol>
     *
     * <p><b>数据包分支</b>的 JSON 只有 {@code block} 一个字段（没有「技能期 / 常态」两列），
     * 所以默认实现直接返回它 —— 对数据包作者来说，写下的那个数<b>就是常态值</b>。</p>
     */
    default int normalBlockCount() {
        return blockCount();
    }

    /**
     * 阻挡搜索范围：本 tick 在<b>哪些格子</b>里找「该被挡住的敌人」。
     *
     * <p>设计口径：默认 {@link UnitBranch.BlockSearch#FOOT}（只有棋子脚下那一格），
     * 唯医疗·守望者用 {@link UnitBranch.BlockSearch#SURROUNDING}（周围九格）——
     * 它的精二范围本来就绕着自身一圈。</p>
     *
     * <p>它只决定「找谁」，<b>不</b>决定被挡住之后走向哪里 —— 那是
     * {@code combat/BlockAnchorStrategy} 的事（可替换，占位实现走正前方 1.3 格）。</p>
     */
    default UnitBranch.BlockSearch blockSearch() {
        return UnitBranch.BlockSearch.FOOT;
    }

    /** 这条阻挡搜索范围的依据（内置 = 生成链里那一句；数据包 = 来源说明）。 */
    default String blockSearchSource() {
        return "";
    }

    // ---------------- 出手 ----------------

    /** 基础攻击间隔（秒）。0 = 不攻击。 */
    double attackIntervalSeconds();

    // ---------------- 索敌（职业特性） ----------------

    UnitBranch.TargetPriority targetPriority();

    UnitBranch.TargetCount targetCount();

    /** 会不会主动出手（解放者/阵法术师/吟游者为 false）。 */
    boolean canAttack();

    /** 这条索敌规则在对照表里的原文依据（排查用，可为空串）。 */
    String targetRuleSource();

    // ---------------- 对空 / 对地 ----------------

    UnitBranch.VerticalTargeting verticalTargeting();

    // ---------------- 攻击方式 ----------------

    /**
     * 常态攻击方式：<b>发射投掷物</b>还是<b>近战武器攻击</b>。
     *
     * <p>它与「近战位 / 远程位」<b>不是同一件事</b>：部署位说的是棋子摆在哪一格
     * （影响阻挡与垂直判定），攻击方式说的是出手形态。原作里有一批分支站在近战位
     * 却能远程攻击（情报官 / 领主 / 哨戒铁卫 / 要塞 / 伏击客 / 钩索师），
     * 它们的部署位是近战、攻击方式是投掷物 —— 两个字段各管一件事。</p>
     *
     * <p><b>默认实现为什么按 {@code verticalTargeting()} 兜底</b>：数据包分支没写
     * {@code attack_method} 时，用「远程位 = 投掷物，其余近战」这条出厂口径，
     * 与内置分支的生成规则一致（见 生成链的 attack_method 规则）——
     * 这样漏写一个字段不至于做出一个「远程位却在贴身砍」的怪棋子。</p>
     */
    default UnitBranch.AttackMethod attackMethod() {
        return verticalTargeting() == UnitBranch.VerticalTargeting.RANGED
                ? UnitBranch.AttackMethod.PROJECTILE
                : UnitBranch.AttackMethod.MELEE;
    }

    /** 这条攻击方式规则的依据（内置 = 表格「部署位」/原文；数据包 = 一句话说明来源）。 */
    default String attackMethodSource() {
        return "";
    }

    /**
     * 是不是「<b>只打自己挡住的那批敌人</b>」的分支 —— 也就是那道真正的「墙」。
     *
     * <h3>判据（设计确定：分档）</h3>
     * <p>近战位 ∧ 阻挡数 &gt; 0 ∧ <b>近战武器攻击</b> ⇒ 只打被挡的；
     * 其余分支仍按攻击范围格子索敌（远程棋子的射程照旧有用）。</p>
     *
     * <h3>★ 为什么不是「近战位」四个字通吃（三处硬冲突，别改回去）</h3>
     * <table border="1">
     *     <tr><th>分支</th><th>数据</th><th>按字面执行会怎样</th></tr>
     *     <tr><td>领主 / 要塞 / 钩索师 / 情报官 / 哨戒铁卫</td>
     *         <td>近战位 + 阻挡 &gt; 0 + <b>投掷物</b>（上一轮刚定的）</td>
     *         <td>只能打贴身目标 ⇒ 上一轮「发射投掷物」白做</td></tr>
     *     <tr><td>伏击客</td><td>近战位 + <b>阻挡 0</b> + 范围 y-1</td>
     *         <td>被挡集合恒为空 ⇒ 永远不出手</td></tr>
     * </table>
     * <p>所以判据里必须有「阻挡数 &gt; 0」（挡不住东西的分支不是墙）和
     * 「近战武器」（能远程打的不该被钉在贴身目标上）。</p>
     *
     * <p><b>★ 注意这里读的是 {@link #blockCount()}（表里的值），不是 {@link #normalBlockCount()}</b>
     * —— 今天两者**行为等价**：唯一差别在解放者（表里 3 / 常态 0），而它
     * {@link #canAttack()} 为 false，根本不出手；被挡集合恒空这件事只在「常态阻挡 &gt; 0」的
     * 分支上才可能非空。但等技能系统让「不攻击」的分支也能出手之后，这一条要重新过一遍：
     * 那时「表里 &gt; 0 但常态 0」的分支会走进「只打被挡的」而永远没有目标（口径见 踩坑记录）。</p>
     */
    default boolean attacksBlockedOnly() {
        return attacksBlockedOnly(attackMethod());
    }

    /**
     * 同上，但由调用方给出**运行时**的出手形式。
     *
     * <p>技能可以改出手形式（{@code PixelUnit#setAttackMethodOverride}），所以运行时的口径
     * 必须用 {@code unit.effectiveAttackMethod()} 来问 —— 否则「技能把墙分支改成投掷物」
     * 之后它还会被钉在「只打被挡的」上。规则本身只有这一份实现，两个重载共用。</p>
     */
    default boolean attacksBlockedOnly(UnitBranch.AttackMethod effectiveAttackMethod) {
        return isMelee() && blockCount() > 0
                && effectiveAttackMethod == UnitBranch.AttackMethod.MELEE;
    }

    /**
     * 是不是近战位分支（<b>部署位</b>口径，不只是打击表现）。
     *
     * <p>它决定两件事：{@link UnitBranch#verticalTargeting()} 的垂直口径，
     * 以及数据包分支漏写 {@code attack_method} 时 {@link #attackMethod()} 的兜底。
     * <b>打击表现</b>（投掷物 / 近战武器）请看 {@link #attackMethod()}，不要再从这里推：
     * 那 6 个近战位远程分支就是「isMelee() 为 true、attackMethod() 为 PROJECTILE」。</p>
     */
    default boolean isMelee() {
        return verticalTargeting() != UnitBranch.VerticalTargeting.RANGED;
    }

    // ------------------------------------------------------------------
    // 群伤（落点溅射 / 连锁 / 多段）—— 2026-10 第二轮
    // ------------------------------------------------------------------

    /**
     * 群伤规格：打中之后，伤害还会扩散到谁（默认 {@link UnitBranch.Aoe#none()}）。
     *
     * <p>★ 与 {@link #targetCount()} 是**两条不同的轴**：那个决定「这一手选中了谁」，
     * 这个决定「打中之后还会波及谁」。一个分支可以只有前者（散射手）、只有后者（炮手）。</p>
     *
     * <p><b>内置分支由 {@link BuiltinBranch} 转发枚举字段</b>（炮手 / 扩散术师 / 撼地者 /
     * 要塞 / 链术师 / 投掷手）；<b>数据包分支目前一律是「无群伤」</b> ——
     * JSON 还没有声明它的字段（设计说明已注明）。这里不返回「像是有」的值：
     * 数据包分支想要溅射，得先给 {@link JsonBranch} 加解析。</p>
     */
    default UnitBranch.Aoe aoe() {
        return UnitBranch.Aoe.none();
    }

    /** 一次攻击结算几段（默认 1；投掷手 = 2：第二段是余震）。 */
    default int attackCount() {
        return 1;
    }

    /** 第二段及以后的伤害系数（默认 1.0；投掷手余震 = 0.5）。 */
    default double tailFactor() {
        return 1.0D;
    }

    /** 群伤数值的出处（查证来源；数据包分支默认写明「未声明」）。 */
    default String aoeSource() {
        return "（数据包分支未声明群伤）";
    }

    // ------------------------------------------------------------------
    // 治疗（医疗职业的出手改为治友方）—— 2026-10 第三轮
    // ------------------------------------------------------------------

    /**
     * 治疗规格：这一分支出手时治谁（默认不治疗）。
     *
     * <p>★ 治疗量**就是 {@link #attackDamage()}**（设计口径「治疗量 = 攻击值」）——
     * 不另立一份「治疗力」，否则两份数值迟早漂移。</p>
     *
     * <p>内置分支由 {@link BuiltinBranch} 转发枚举字段（7 个医疗分支）；
     * <b>数据包分支目前一律「不治疗」</b> —— JSON 还没有声明它的字段（设计说明已注明）。</p>
     */
    default UnitBranch.Heal heal() {
        return UnitBranch.Heal.of(UnitBranch.Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20);
    }

    /** 治疗规则的依据（对照表原文 / 查证来源）。 */
    default String healSource() {
        return "（数据包分支未声明治疗）";
    }

    // ------------------------------------------------------------------
    // 停顿（特性自带的「短暂停顿」）—— 2026-10 第四轮
    // ------------------------------------------------------------------

    /**
     * 停顿规格：这一分支的**特性**命中敌人时让它「短暂停顿」多久（默认不停顿）。
     *
     * <p>★ 目前只有两个内置分支有，**数值全部来自 PRTS、没有暂定值**：
     * 凝滞师 0.8 秒 = 16 tick（管每次普攻的主目标）、链术师 0.5 秒 = 10 tick（只管连锁跳到的目标）。
     * 详表见 {@link UnitBranch#pause()} 的注释。</p>
     *
     * <p><b>数据包分支目前一律「不停顿」</b> —— JSON 还没有声明它的字段（与 {@link #aoe()} /
     * {@link #heal()} 同一条口径，见 设计说明）。这里不返回「像是有」的值：
     * 数据包分支想要停顿，得先给 {@link JsonBranch} 加解析。</p>
     */
    default UnitBranch.Pause pause() {
        return UnitBranch.Pause.none();
    }

    /** 停顿数值的出处（内置 = PRTS 原文；数据包分支默认写明「未声明」）。 */
    default String pauseSource() {
        return "（数据包分支未声明停顿）";
    }

    // ------------------------------------------------------------------
    // 天赋两个槽位（**只解析、不执行**）—— 2026-10 设计口径
    // ------------------------------------------------------------------

    /**
     * <b>天赋 1</b>：天赋名 / 天赋效果 / 天赋作用对象三个字段。
     *
     * <p>★ <b>本工程只把三个字段的落点固定下来，不解释也不执行效果</b>。
     * 作用对象（{@link Talent#target()}）对照表里没有这一列，内置分支现在是空串，
     * 等**干员导入**时由脚本按玩家的导入表解析填入 —— 口径见 {@link Talent} 的类注释。</p>
     *
     * <p>内置分支由 {@link BuiltinBranch} 转发枚举字段（生成链从对照表的
     * 「天赋1 / 天赋1描述」两列取）；<b>数据包分支没有声明天赋的字段，一律返回空槽位</b>
     * （与 {@link #aoe()} / {@link #heal()} / {@link #pause()} 同一条口径，见 设计说明）。</p>
     */
    default Talent talent1() {
        return Talent.empty();
    }

    /** <b>天赋 2</b>：同 {@link #talent1()}；该分支只有一个天赋时返回空槽位。 */
    default Talent talent2() {
        return Talent.empty();
    }

    // ------------------------------------------------------------------
    // 禁疗（这个分支能不能被友方治疗）—— 2026-10 第四轮
    // ------------------------------------------------------------------

    /**
     * 这个分支的棋子**能不能被友方治疗**（默认 {@code true}）。
     *
     * <p>★ 只有三条写「不能」（PRTS 精二特性原文）：
     * <b>近卫·武者</b>「不成为其他角色的治疗目标」、
     * <b>近卫·收割者</b> 与 <b>重装·不屈者</b>「无法被友方角色治疗」。
     * 《分支特性信息》另写「常态持有禁疗；通过自身特性/天赋/技能产生的**作用于自身的**
     * 治疗效果会无视自身的禁疗」⇒ <b>别人的治疗不生效、自己的回血照旧</b>；
     * 本轮只做前半句（自回血属于特性被动，还没做）。</p>
     *
     * <p><b>数据包分支默认「可被治疗」</b> —— JSON 还没有声明这个字段（与
     * {@link #aoe()} / {@link #heal()} / {@link #pause()} 同一条口径，见 设计说明）。</p>
     */
    default boolean healable() {
        return true;
    }

    /** 禁疗这个值的出处（内置 = PRTS 原文；数据包分支默认写明「未声明」）。 */
    default String healableSource() {
        return "（数据包分支未声明禁疗）";
    }

    // ------------------------------------------------------------------
    // 分支特性原文（朝向界面要显示它）—— 2026-10-07
    // ------------------------------------------------------------------

    /**
     * <b>分支特性原文</b>（对照表「分支机制（干员特性原文）」那一列，逐字）。
     *
     * <p>内置分支由 {@link BuiltinBranch} 转发生成物；<b>数据包分支没有声明这个字段</b>，
     * 返回空串（与 {@link #aoe()} / {@link #heal()} / {@link #pause()} 同一条口径，
     * 见 设计说明）。</p>
     */
    default String traitText() {
        return "";
    }

    /**
     * <b>显示用</b>的分支特性：把原始文本里 wiki 残留的 {@code |}（当成分行/并列用）
     * 换成中文分号，并把多余空白压掉。
     *
     * <h3>为什么清洗放在这里，而不是让生成物直接存干净文本</h3>
     * <p>生成物是**表的逐字产物**（生成链脚本 会拿它与表比对，
     * 见 设计说明）—— 在数据层动手脚会让「生成物 = 表」这条核对失效。
     * 而 {@code |} 是**显示**问题（PRTS 那格用它当分隔符），所以清洗只写在显示这一层。</p>
     *
     * <p><b>唯一一处</b>：界面不要自己再 {@code replace} 一遍 —— 同一条口径写两遍迟早漂移。</p>
     */
    default String traitDisplayText() {
        String raw = traitText();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace('|', '；').replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // 伤害乘区（「这一击打多疼」）—— 2026-10 第五轮
    // ------------------------------------------------------------------

    /**
     * 这一分支的**伤害乘区**：命中时按「攻击者与目标的关系」给这一击乘一个系数。
     *
     * <p>★ 设计口径：「造成伤害就是指在**进行伤害计算时的最终伤害**，而不是攻击力」——
     * 所以它**不改 {@link #attackDamage()} 面板**，而是乘在 {@code hurt()} 的入参上。
     * 真值表在 {@code combat/DamageMods.factor(...)}。</p>
     *
     * <p><b>数据包分支默认没有乘区</b>（JSON 还没有声明这个字段，与 {@link #aoe()} /
     * {@link #heal()} / {@link #pause()} 同一条口径，见 设计说明）。</p>
     */
    default UnitBranch.DamageMod damageMod() {
        return UnitBranch.DamageMod.none();
    }

    /** 伤害乘区的出处（内置 = PRTS 原文 / 模组名；数据包分支默认写明「未声明」）。 */
    default String damageModSource() {
        return "（数据包分支未声明伤害乘区）";
    }

    // ------------------------------------------------------------------
    // 模组槽位（**待定**）—— 2026-10-07 设计口径
    // ------------------------------------------------------------------

    /**
     * <b>模组槽位</b>：模组名 / 模组效果 / 作用对象三个字段。
     *
     * <p>★ 设计口径（原话）：「天赋不填，目前应该是 72 个分支的天赋槽全空，
     * <b>模组只需要像天赋一样留一个待定槽即可</b>」。</p>
     *
     * <p>所以本工程现在<b>只留槽、不填内容</b>：{@link ModuleEffect#empty()}。
     * 与天赋槽（{@link #talent1()}）**逐字段对齐** —— 将来「干员导入」的脚本用同一套解析
     * 逻辑把两个槽一起填（玩家的导入表里模组与天赋各占一列）。本工程不解释也不执行。</p>
     *
     * <p>★ 另一件同轮的事：原先**默认生效**的 5 条<b>模组伤害乘区</b>（强攻手/无畏者/要塞/
     * 速射手/攻城手）已经从规则表里摘出来，它们本来就是模组效果、不该在「未开启模组」的
     * 基线里生效；出处与倍率留在 生成链 的
     * {@code MODULE_DAMAGE_MODS_PENDING}，等这个槽有内容时再落地。</p>
     */
    default ModuleEffect module() {
        return ModuleEffect.empty();
    }

    /** 模组槽位的出处（内置 = 「待定」这句话本身；数据包分支默认写明「未声明」）。 */
    default String moduleSource() {
        return "（数据包分支未声明模组）";
    }
}
