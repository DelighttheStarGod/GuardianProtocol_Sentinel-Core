package com.guardianprotocol.data;

import javax.annotation.Nullable;

/**
 * 职业分支表（**全部 72 个分支**，8 大职业下的每一个分支）。
 *
 * <p><b>本文件由 生成链 从两张参考表生成</b>，不要手工改数值：</p>
 * <ul>
 *     <li>明日方舟职业机制与职业分支对照表.xlsx → 阻挡数 / 部署费用 / 攻击间隔 /
 *         再部署 / 分支特性 / 天赋</li>
 *     <li>明日方舟模板干员技能表.xlsx → 每个分支的 3 个战斗技能</li>
 * </ul>
 *
 * <p>每个分支的攻击范围用其模板干员【精英二】的 {@code rangeId}
 * （由《…精二攻击范围.md》的一览索引表给出，见 {@link AttackRange}）。</p>
 *
 * <p><b>索敌机制（{@link TargetPriority} / {@link TargetCount} / {@link #canAttack()}）</b>
 * 同样由脚本从表格的「分支机制（干员特性原文）」一列推出，规则表在
 * 生成链；每个分支的 {@link #targetRuleSource()} 就是它的依据原文。</p>
 *
 * <p><b>常态攻击方式（{@link AttackMethod}）</b>：远程位分支与「近战位但有远程攻击」
 * 的 6 个分支发射投掷物，其余近战位分支用近战武器攻击。规则同样在
 * 生成链（{@code attack_method}），每个分支的
 * {@link #attackMethodSource()} 是它的依据。</p>
 *
 * <p><b>常态阻挡（{@link #normalBlockCount()}）</b>：表里「阻挡数」那一列填的是
 * <b>技能期 / 显示值</b>，不是常态 —— 常态阻挡默认就等于它，只有两个「常态不挡敌人」
 * 的分支（特种·伏击客 / 近卫·解放者）被覆盖成 <b>0</b>（判据在 生成链 的
 * {@code NORMAL_BLOCK_OVERRIDES}，每条都带出处）。<b>常态阻挡为 0 ⇒ 棋子没有碰撞箱、
 * 也不占阻挡名额</b>——怪物能从它身上直接走过去（设计口径）。</p>
 *
 * <p>数值含义与换算见 {@link UnitBranch} 各字段注释；
 * 攻击间隔(秒) 直接用表格原值，运行时按 20 tick/秒 换算。</p>
 *
 * <p><b>停顿（{@link #pause()}）</b>：特性自带的「短暂停顿」只有两个分支有 ——
 * 凝滞师「攻击…造成短暂的停顿」（0.8 秒 = 16 tick，管每次普攻的主目标）与
 * 链术师「每次跳跃…造成短暂停顿(0.5s)」（10 tick，只管连锁跳到的目标）。
 * 数值来自 PRTS（精二特性 + 《分支特性信息》），规则表在 生成链 的
 * {@code PAUSE_OVERRIDES}，{@link #pauseSource()} 是它的出处。</p>
 *
 * <p><b>禁疗（{@link #healable()}）</b>：默认<b>能</b>被友方治疗，只有三条写「不能」——
 * 近卫·武者「不成为其他角色的治疗目标」、近卫·收割者与重装·不屈者
 * 「无法被友方角色治疗」。规则表在 生成链 的 {@code HEAL_BAN_OVERRIDES}，
 * {@link #healableSource()} 是它的出处。「自身的回血无视禁疗」本轮不做
 * （那属于特性被动）。</p>
 */
public enum UnitBranch {

    /**
     * 先锋 · 战术家 —— 模板干员「可露希尔」（Closure，6★）。
     *
     * <p>分支特性：可以在攻击范围内选择一次战术点来召唤援军，自身攻击援军阻挡的敌人时攻击力提升至150%</p>
     */
    VANGUARD_TACTICIAN(UnitClass.VANGUARD, "战术家", "可露希尔",
            "Closure", "6", "远程位",
            1, 1, 13, 1.00D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、费用回复、控场",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "递归策略", "自动回复", "自动触发", 8, 8.0D, DurationKind.TIMED, 1, -1, -1, "立即使自身的援军获得1层护盾（不叠加），技能持续时间内逐渐获得3点部署费用；部署后每使用过一次技能，获得的部署费用+1（最多提升至7点）"),
                    new Skill("技能2", "模型扩展", "自动回复", "手动触发", 40, 30.0D, DurationKind.TIMED, 34, -1, -1, "立即获得10点部署费用，持续时间内逐渐获得15点部署费用，自身的援军防御力+60%，阻挡数+1，自身攻击力+80%，同时攻击2个目标且能攻击到自身援军阻挡的敌人；在战术点效果范围内部署干员时，立即返还部署费用的40%"),
                    new Skill("技能3", "Q.E.D.", "自动回复", "手动触发", 30, 30.0D, DurationKind.TIMED, 23, -1, -1, "技能持续时间内逐渐获得18点部署费用，攻击间隔缩短，锁定自身和援军原本攻击范围内的敌人进行攻击，造成可露希尔250%攻击力的物理伤害并施加持续3秒的6%迟钝效果（可叠加，最高60%），每攻击9次后攻击目标数+1（最多触发6次）")
            },
            "3-3", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「召唤援军 / 援军阻挡的敌人增伤」，没有优先攻击与多目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可以在攻击范围内选择一次战术点来召唤援军，自身攻击援军阻挡的敌人时攻击力提升至150%",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 先锋 · 策士 —— 模板干员「凛御银灰」（SilverAsh the Reignfrost，6★）。
     *
     * <p>分支特性：能够阻挡两个敌人，可以支援待部署区的我方单位</p>
     */
    VANGUARD_STRATEGIST(UnitClass.VANGUARD, "策士", "凛御银灰",
            "SilverAsh the Reignfrost", "6", "近战位",
            2, 2, 11, 1.20D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、费用回复、支援",
            "谢拉格",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "周旋的谋略", "自动回复", "自动触发", 29, 0.0D, DurationKind.INSTANT, 19, -1, -1, "（与基础等级相同）"),
                    new Skill("技能2", "御敌的锋锐", "自动回复", "手动触发", 17, 0.0D, DurationKind.TIMED, 9, -1, -1, "对前方6名敌人造成攻击力380%的物理伤害，使其6秒内寒冷且隐匿失效；待部署区中距离“风雪之眼”最近的1名干员费用-11（优先选择右侧近卫/术师/狙击干员）；使“风雪之眼”左侧所有干员部署时以自身攻击力施放一次本技能的范围效果（最多叠加2次）；可充能2次"),
                    new Skill("技能3", "变革已至", "自动回复", "手动触发", 55, 48.0D, DurationKind.TIMED, 46, -1, -1, "攻击范围扩大并使敌人隐匿失效，攻击对直线范围造成攻击力200%的物理伤害和30%的脆弱；立即获得9点后持续获得24点部署费用；首次开启时交换待部署区中费用最高（优先选择近卫/术师/狙击干员）与最低干员的基础部署费用；技能期间“风雪之眼”变为可部署")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡两个敌人，可以支援待部署区」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡两个敌人，可以支援待部署区的我方单位",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 先锋 · 尖兵 —— 模板干员「忍冬」（Vulpisfoglia，6★）。
     *
     * <p>分支特性：能够阻挡两个敌人</p>
     */
    VANGUARD_PIONEER(UnitClass.VANGUARD, "尖兵", "忍冬",
            "Vulpisfoglia", "6", "近战位",
            2, 2, 12, 1.05D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、费用回复、输出",
            "叙拉古",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "小施惩戒", "自动回复", "自动触发", 4, 0.0D, DurationKind.TIMED, 8, -1, -1, "下次攻击额外造成攻击力290%的法术伤害，并获得1点部署费用；可充能3次"),
                    new Skill("技能2", "坠刃拷问", "自动回复", "手动触发", 20, 0.0D, DurationKind.TIMED, 20, -1, -1, "立即获得7点部署费用，对周围最多6名敌人造成相当于攻击力300%的法术伤害，并令击中目标停顿5秒；若敌人已经处于停顿状态，则额外使敌人晕眩5秒；可充能2次"),
                    new Skill("技能3", "隐狐之艺", "自动回复", "手动触发", 18, 10.0D, DurationKind.TIMED, 10, -1, -1, "技能开启时立即获得9点部署费用，攻击距离+1，攻击力+110%，攻击速度从+180逐渐衰减至+0，同时攻击阻挡的所有敌人，每次攻击晕眩敌人0.2秒；若技能期间击倒敌人，则技能结束时进入迷彩状态，直至下一次开启技能")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡两个敌人」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡两个敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 先锋 · 情报官 —— 模板干员「伊内丝」（Ines，6★）。
     *
     * <p>分支特性：再部署时间减少，可使用远程攻击</p>
     */
    VANGUARD_AGENT(UnitClass.VANGUARD, "情报官", "伊内丝",
            "Ines", "6", "近战位",
            1, 1, 9, 1.00D, 35,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、费用回复、快速复活",
            "巴别塔",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "淬影突袭", "攻击回复", "自动触发", 3, 0.0D, DurationKind.TIMED, 3, -1, -1, "下次攻击使目标3秒内每秒受到相当于伊内丝攻击力80%的法术伤害（不叠加），并获得2点部署费用"),
                    new Skill("技能2", "暗夜无明", "自动回复", "手动触发", 20, 12.0D, DurationKind.TIMED, 15, -1, -1, "攻击范围扩大，攻击力+110%，自身获得隐匿，每次攻击获得1点部署费用并偷取目标7点攻击速度（最多70点，持续至技能结束或伊内丝离场）"),
                    new Skill("技能3", "独影归途", "初始触发", "被动", 0, 16.0D, DurationKind.PASSIVE, 0, -1, -1, "首次部署时不消耗部署费用，放置一个影哨后离场并立刻刷新再部署时间；部署后攻击力+160%，立刻收回影哨对穿过的最多6名敌人造成相当于攻击力200%的物理伤害，技能期间每对一个敌人造成伤害就获得1点部署费用")
            },
            "2-2", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「再部署时间减少，可使用远程攻击」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "近战位但有远程攻击：可使用远程攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "再部署时间减少，可使用远程攻击",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 先锋 · 执旗手 —— 模板干员「琴柳」（Saileach，6★）。
     *
     * <p>分支特性：技能发动期间阻挡数变为0</p>
     * <p>模组后：技能发动期间阻挡数变为0，但使身前一名干员阻挡数+1</p>
     */
    VANGUARD_STANDARD_BEARER(UnitClass.VANGUARD, "执旗手", "琴柳",
            "Saileach", "6", "近战位",
            1, 1, 10, 1.30D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、费用回复、支援",
            "维多利亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "支援号令·γ型", "自动回复", "手动触发", 26, 8.0D, DurationKind.TIMED, 15, -1, -1, "（与基础等级相同）"),
                    new Skill("技能2", "信仰传承", "自动回复", "手动触发", 29, 15.0D, DurationKind.TIMED, 15, -1, -1, "停止攻击，持续时间内回复总共20点部署费用；将军旗掷向范围内生命比例最低的干员所在位置，使其防御力+50%且每秒恢复相当于琴柳攻击力50%的生命值。技能结束时收回军旗。"),
                    new Skill("技能3", "光辉旗帜", "自动回复", "手动触发", 20, 10.0D, DurationKind.TIMED, 7, -1, -1, "停止攻击，立即回复10点部署费用，将军旗掷向地面敌人所在位置，并对周围造成300%的物理伤害和3.5秒晕眩；期间军旗周围8格敌人受到蓝色|停顿和30%的脆弱效果。技能结束时收回军旗。")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「技能发动期间阻挡数变为0」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "技能发动期间阻挡数变为0",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 先锋 · 冲锋手 —— 模板干员「风笛」（Bagpipe，6★）。
     *
     * <p>分支特性：击杀敌人后获得1点部署费用，撤退时返还初始部署费用</p>
     * <p>模组后：击杀敌人后获得2点部署费用，撤退时返还该次部署费用</p>
     */
    VANGUARD_CHARGER(UnitClass.VANGUARD, "冲锋手", "风笛",
            "Bagpipe", "6", "近战位",
            1, 1, 11, 1.00D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、费用回复、输出",
            "维多利亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "迅捷打击·γ型", "自动回复", "手动触发", 35, 35.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+45%，攻击速度+45"),
                    new Skill("技能2", "高效冲击", "自动回复", "自动触发", 4, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下一次的攻击力提升至200%，且额外攻击一次；"),
                    new Skill("技能3", "闭膛连发", "自动回复", "手动触发", 40, 20.0D, DurationKind.TIMED, 25, -1, -1, "攻击间隔增大，阻挡数+1，攻击力和防御力+120%，攻击变为三连击")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「击杀敌人后获得部署费用」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "击杀敌人后获得1点部署费用，撤退时返还初始部署费用",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 佣兵 —— 模板干员「雷狼龙S空爆」（Zinogre S Catapult，5★）。
     *
     * <p>分支特性：可消耗部署费用来强化作战能力</p>
     */
    GUARD_MERCENARY(UnitClass.GUARD, "佣兵", "雷狼龙S空爆",
            "Zinogre S Catapult", "5", "近战位",
            2, 2, 8, 1.25D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、群攻、输出、爆发",
            "罗德岛、行动预备组A6",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "高压回填斩", "攻击回复", "手动触发", 2, 4.0D, DurationKind.TIMED, 0, -1, -1, "消耗20点部署费用，攻击间隔增大，交替使用五连击与十连击攻击阻挡的所有敌人，且每一击附带相当于攻击力35%的法术伤害；可充能5次"),
                    new Skill("技能2", "超高输出属性解放斩", "攻击回复", "手动触发", 2, 4.0D, DurationKind.TIMED, 0, -1, -1, "消耗5点部署费用，攻击力和防御力+100%；技能期间可消耗10点部署费用使用装备应变，对前方造成一次相当于攻击力350%的物理伤害，且开启技能时每消耗一层充能附加一次相当于攻击力150%的法术伤害，之后技能立即结束；可充能5次"),
                    new Skill("技能3", "（表格无）", "", "", 0, 0.0D, DurationKind.INSTANT, 0, -1, -1, "")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「可消耗部署费用来强化作战能力」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可消耗部署费用来强化作战能力",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 撼地者 —— 模板干员「怒潮凛冬」（Zima the Raging Tide，6★）。
     *
     * <p>分支特性：攻击使目标周围的其他敌人受到相当于攻击力50%的群体物理伤害</p>
     */
    GUARD_EARTHSHAKER(UnitClass.GUARD, "撼地者", "怒潮凛冬",
            "Zima the Raging Tide", "6", "近战位",
            2, 2, 18, 1.80D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、控场",
            "乌萨斯、乌萨斯学生自治团",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "誓不低头", "自动回复", "手动触发", 30, 30.0D, DurationKind.TIMED, 20, -1, -1, "攻击力+50%，攻击速度+50"),
                    new Skill("技能2", "绝不罢休", "自动回复", "自动触发", 50, 16.0D, DurationKind.INFINITE, 36, -1, -1, "被动效果：每次有高台触发第一天赋的效果时，获得1点技力；自动开启：攻击范围扩大，攻击力+90%，防御力+60%；第二次及以后使用时能力加成变为最初的两倍，且持续时间无限"),
                    new Skill("技能3", "无可抵挡", "自动回复", "手动触发", 27, 0.0D, DurationKind.TIMED, 20, -1, -1, "攻击力+100%，对前方一格持续进行五次溅射范围更大的锤击，每次造成相当于攻击力240%的物理伤害并使攻击力额外+30%；技能期间触发第一天赋的高台会向四周高台扩散该效果（扩散次数随攻击次数递增），并使伤害效果提升至3.5倍，控制效果变为2秒束缚")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里的「群体伤害」打在**目标周围**（落点溅射），不是选多个目标",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.splash(1.0D, 0.5D, true), 1, 1.0D,
            "落点溅射 r=1.0 ×0.5｜EN 官方 wiki（Arknights Terra Wiki）分支页：radius of 1 around the main target for half the ATK",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击使目标周围的其他敌人受到相当于攻击力50%的群体物理伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 斗士 —— 模板干员「贝洛内」（Bellone，6★）。
     *
     * <p>分支特性：能够阻挡一个敌人</p>
     */
    GUARD_FIGHTER(UnitClass.GUARD, "斗士", "贝洛内",
            "Bellone", "6", "近战位",
            1, 1, 9, 0.78D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、削弱",
            "叙拉古",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "家主的余裕", "攻击回复", "自动触发", 3, 0.0D, DurationKind.TIMED, 0, -1, -1, "下次攻击对目标造成两次相当于攻击力250%的物理伤害"),
                    new Skill("技能2", "军师的手段", "自动回复", "手动触发", 22, 22.0D, DurationKind.TIMED, 14, -1, -1, "攻击范围扩大，攻击速度+80，每次攻击对3名目标造成相当于攻击力200%的物理伤害；技能期间第一天赋使目标防御力降低的效果改为最多叠加8次，并且叠满后对目标造成持续的停顿效果"),
                    new Skill("技能3", "清算", "自动回复", "手动触发", 35, 30.0D, DurationKind.TIMED, 27, -1, -1, "攻击力+170%，攻击速度+50，攻击时有50%的几率造成相当于攻击力185%的物理伤害；技能开启后立即选取自身周围一定范围内的一名地面目标，若其所在位置可部署，自身会移动至目标所在位置进行攻击；自身攻击的精英、领袖敌人被击倒，或自身持续1秒未进行攻击时，会立即重新选取目标；受到致命伤害时不撤退但会结束技能，技能结束后返回初始位置")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡一个敌人」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡一个敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 术战者 —— 模板干员「赤刃明霄陈」（Ch'en the Dawnstreak，6★）。
     *
     * <p>分支特性：攻击造成法术伤害</p>
     */
    GUARD_ARTS_FIGHTER(UnitClass.GUARD, "术战者", "赤刃明霄陈",
            "Ch'en the Dawnstreak", "6", "近战位",
            1, 1, 19, 1.25D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、爆发、输出",
            "炎",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "赤霄·奔夜", "自动回复", "手动触发", 20, 18.0D, DurationKind.TIMED, 10, -1, -1, "攻击力+120%，攻击变为二连击，攻击使目标敌人特殊能力失效持续至技能结束"),
                    new Skill("技能2", "赤霄·绝影-驰", "自动回复", "手动触发", 20, 6.0D, DurationKind.TIMED, 15, -1, -1, "对周围最近的1名敌人发动10次斩击，每次造成攻击力480%的法术伤害，敌人被击倒时转移至目标周围最近的其他敌人并使剩余攻击次数+1；斩击结束时，若目标未被击倒且其位置可部署则移动至该位置，否则返回原位置。接下来攻击力+300%，获得60%物理和法术闪避"),
                    new Skill("技能3", "赤霄·天喟", "自动回复", "手动触发", 25, 20.0D, DurationKind.TIMED, 18, -1, -1, "技能开启时向前释放一道可转向的剑气，对穿过的敌人造成相当于其当前生命值6%的法术伤害（至少造成自身攻击力580%的法术伤害）；攻击范围扩大，每次攻击对最多4名地面敌人造成3次攻击力210%的法术伤害")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「攻击造成法术伤害」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 领主 —— 模板干员「丰川祥子」（Togawa Sakiko，6★）。
     *
     * <p>分支特性：可以进行远程攻击，但此时攻击力降低至80%</p>
     */
    GUARD_LORD(UnitClass.GUARD, "领主", "丰川祥子",
            "Togawa Sakiko", "6", "近战位",
            2, 2, 18, 1.30D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出",
            "AveMujica",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "新月的苏醒", "攻击回复", "手动触发", 3, 0.0D, DurationKind.INSTANT, 0, -1, -1, "演奏出8个音符。每个音符造成的法术伤害依次从攻击力的100%逐渐降低至5%；可充能2次，充能至最大层数时自动释放一次"),
                    new Skill("技能2", "满月的舞会", "攻击回复", "手动触发", 5, 0.0D, DurationKind.INSTANT, 0, -1, -1, "可以切换钢琴（初始）或风琴音色演奏：；钢琴：攻击力+110%，音符飞行速度加快，命中目标后穿过敌人造成物理伤害；风琴：攻击速度+140，音符造成法术伤害且飞行速度减缓；Fever期间变为当前音色的二连击"),
                    new Skill("技能3", "残月的余响", "攻击回复", "手动触发", 42, 25.0D, DurationKind.TIMED, 34, -1, -1, "攻击范围扩大，攻击同时使用钢琴和风琴音色演奏，各自演奏2个造成相当于攻击力220%物理和法术伤害的音符，且分别追踪法术抗性和防御力最高的敌人；Fever期间AveMujica成员受到致命伤害时不撤退，Fever结束后退场")
            },
            "3-12", true,
            TargetPriority.BLOCKED_FIRST, TargetCount.SINGLE, true,
            "《分支特性信息》：「可以且优先攻击自身阻挡的单位（即使目标处于攻击范围外）」",
            AttackMethod.PROJECTILE,
            "近战位但有远程攻击：可以进行远程攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可以进行远程攻击，但此时攻击力降低至80%",
            DamageMod.of(DamageMod.Kind.TARGET_FAR, 0.8D, false),
            "打切比雪夫格距 > 1 的敌人 → ×0.8｜PRTS 干员页精二特性原文：「可以进行远程攻击，但此时攻击力降低至80%」；★「远程」的判据 = **切比雪夫格距 > 1**（设计口径：「攻击 1 格外（不含一格）的敌人时伤害 80%」）",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 解放者 —— 模板干员「司霆惊蛰」（Leizi the Thunderbringer，6★）。
     *
     * <p>分支特性：通常不攻击且阻挡数为0，技能未开启时40秒内攻击力逐渐提升至最高+200%且技能结束时重置攻击力</p>
     */
    GUARD_LIBERATOR(UnitClass.GUARD, "解放者", "司霆惊蛰",
            "Leizi the Thunderbringer", "6", "近战位",
            3, 0, 10, 1.20D, 70,   // 常态阻挡 0 ← PRTS 特性原文「通常不攻击且阻挡数为0」（4 位解放者全是这句） ⇒ 表里那格 3 是技能期/显示值
            "近战位、输出、爆发",
            "炎",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "浩气长存", "自动回复", "手动触发", 11, 0.0D, DurationKind.INSTANT, 17, -1, -1, "攻击范围朝前方和两侧扩大。对三个方向的地面敌人各造成相当于攻击力355%的物理伤害；可充能3次，充能耗尽前特性不重置"),
                    new Skill("技能2", "正霆摄威", "自动回复", "手动触发", 43, 36.0D, DurationKind.TIMED, 22, -1, -1, "攻击范围沿地面地块扩展至最远三格，攻击对4个目标造成相当于攻击力150%的物理伤害。技能期间每次落雷使攻击力+10%（最多可叠加25次）"),
                    new Skill("技能3", "天地通明", "自动回复", "手动触发", 36, 24.0D, DurationKind.TIMED, 21, -1, -1, "攻击范围扩大，攻击间隔大幅延长，攻击造成攻击力300%的范围物理伤害，目标位置产生朝四周流动三格的电流。电流碰到自身或高台时反弹，电流所在地块上所有敌人每0.6秒受到司霆惊蛰攻击力70%的法术伤害，且有15%概率战栗3秒")
            },
            "1-2", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, false,
            "通常不攻击",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "通常不攻击且阻挡数为0，技能未开启时40秒内攻击力逐渐提升至最高+200%且技能结束时重置攻击力",
            DamageMod.of(DamageMod.Kind.LIBERATOR_RAMP, 3.0D, false),
            "未开技能时随时间爬升（上限） → ×3.0｜PRTS 干员页精二特性原文：「技能未开启时 40 秒内攻击力逐渐提升至最高 +200%且技能结束时重置攻击力」；《分支特性信息》：「攻击力加成效果每 1 秒更新一次」⇒ 线性爬升，×1.0 → ×3.0，仅在技能未激活时累积",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 本源近卫 —— 模板干员「聆音」（Gracebearer，5★）。
     *
     * <p>分支特性：能够阻挡两个敌人，可以造成元素伤害</p>
     */
    GUARD_PRIMAL(UnitClass.GUARD, "本源近卫", "聆音",
            "Gracebearer", "5", "近战位",
            2, 2, 18, 1.20D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、爆发、输出",
            "玻利瓦尔",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "开颅挽歌", "自动回复", "手动触发", 30, 30.0D, DurationKind.TIMED, 15, -1, -1, "攻击变为二连击，每次攻击造成相当于攻击力150%的物理伤害，攻击附带造成伤害10%的神经损伤"),
                    new Skill("技能2", "破膛弥撒", "自动回复", "手动触发", 15, 0.0D, DurationKind.INSTANT, 0, -1, -1, "立即对周围最多6名地面敌人造成三次相当于攻击力250%的物理伤害，若目标处于神经损伤爆发期间则改为造成元素伤害；可充能2次"),
                    new Skill("技能3", "（表格无）", "", "", 0, 0.0D, DurationKind.INSTANT, 0, -1, -1, "")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡两个敌人，可以造成元素伤害」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡两个敌人，可以造成元素伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 收割者 —— 模板干员「隐德来希」（Entelechia，6★）。
     *
     * <p>分支特性：无法被友方角色治疗，攻击造成群体伤害，每攻击到一个敌人回复自身50生命，最大生效数等于阻挡数</p>
     */
    GUARD_REAPER(UnitClass.GUARD, "收割者", "隐德来希",
            "Entelechia", "6", "近战位",
            2, 2, 20, 1.30D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、生存",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "玫影觅迹", "攻击回复", "自动触发", 2, 0.0D, DurationKind.TIMED, 0, -1, -1, "下次攻击的攻击力提升至175%，并连续攻击两次"),
                    new Skill("技能2", "绯红壁合", "自动回复", "手动触发", 20, 12.0D, DurationKind.TIMED, 15, -1, -1, "停止攻击，在自身及1名其他地面单位处召唤血镰，对周围所有敌人进行切割，每0.5秒造成相当于攻击力195%的物理伤害"),
                    new Skill("技能3", "灵与欲的惜别", "自动回复", "手动触发", 30, 20.0D, DurationKind.TIMED, 22, -1, -1, "攻击范围扩大，攻击力+135%，攻击速度+100，立刻为攻击范围内最多3名生命值最高的地面敌人召唤对应的心烛，每次攻击对心烛至少造成35%攻击力的伤害，心烛继承原敌人当前60%的生命值，被攻击时原敌人也会流失同等生命值；心烛只受隐德来希攻击的影响")
            },
            "1-3", false,
            TargetPriority.NEAREST, TargetCount.MULTI_BLOCKED, true,
            "攻击造成群体伤害…最大生效数等于阻挡数",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            false,
            "PRTS 干员页精二特性原文（《分支特性信息》另写「常态持有禁疗」）：「无法被友方角色治疗」",
            "无法被友方角色治疗，攻击造成群体伤害，每攻击到一个敌人回复自身50生命，最大生效数等于阻挡数",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 重剑手 —— 模板干员「乌尔比安」（Ulpianus，6★）。
     *
     * <p>分支特性：同时攻击阻挡的所有敌人</p>
     */
    GUARD_CRUSHER(UnitClass.GUARD, "重剑手", "乌尔比安",
            "Ulpianus", "6", "近战位",
            2, 2, 20, 2.50D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、生存",
            "阿戈尔、深海猎人",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "必须促成的接触", "自动回复", "自动触发", 4, 0.0D, DurationKind.INSTANT, 0, -1, -1, "向前方扔出船锚，船锚会把目标地点周围的两名敌人中等力度地拖拽至面前，对其造成相当于攻击力270%的物理伤害"),
                    new Skill("技能2", "必须维系的界限", "自动回复", "自动触发", 70, 0.0D, DurationKind.INFINITE, 0, -1, -1, "第一天赋的效果提升至2倍，阻挡数+1，生命上限+60%，攻击力+160%；持续时间无限"),
                    new Skill("技能3", "必须开辟的通路", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 20, -1, -1, "最大生命值+80%，攻击力+260%，立即朝面前扔出一个船锚，撞击到目标或达到最远距离时停止，并对周围所有敌人造成攻击力160%的物理伤害和6秒晕眩。若船锚停留的位置可以部署，乌尔比安会移动到该位置；可以手动结束技能，技能结束时乌尔比安会返回到初始的位置")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.MULTI_BLOCKED, true,
            "同时攻击阻挡的所有敌人",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "同时攻击阻挡的所有敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 武者 —— 模板干员「左乐」（Zuo Le，6★）。
     *
     * <p>分支特性：不成为其他角色的治疗目标，每次攻击到敌人后恢复自身一定生命（精英阶段0为30，精英阶段1为50，精英阶段2为70）</p>
     * <p>模组后：生命值低于50%时，获得25%的庇护</p>
     */
    GUARD_SOLOBLADE(UnitClass.GUARD, "武者", "左乐",
            "Zuo Le", "6", "近战位",
            1, 1, 22, 1.20D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、生存",
            "炎",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "破虏", "自动回复", "自动触发", 4, 0.0D, DurationKind.TIMED, 6, -1, -1, "下次攻击的攻击力提升至200%，自身生命低于80%时额外攻击1次，低于50%时额外攻击2次；可充能3次"),
                    new Skill("技能2", "行险", "自动回复", "手动触发", 20, 12.0D, DurationKind.TIMED, 13, -1, -1, "立即流失50%当前生命并获得相当于最大生命120%的屏障，攻击力+170%，阻挡数+1，同时攻击所有阻挡的敌人；屏障最高叠加至最大生命的2倍，技能结束后逐渐流失"),
                    new Skill("技能3", "佑序有炎", "自动回复", "手动触发", 25, 0.0D, DurationKind.TIMED, 10, -1, -1, "立刻对前方进行7次斩击，每次对最多3名敌人造成攻击力245%的物理伤害（最后一击系数加倍且使目标晕眩5秒），期间特性的生命回复改为获得相当于回复量3倍的屏障；屏障最高叠加至最大生命的2倍，持续15秒")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「不成为治疗目标，攻击后回血」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            false,
            "PRTS 干员页精二特性原文（《分支特性信息》另写「常态持有禁疗」）：「不成为其他角色的治疗目标」",
            "不成为其他角色的治疗目标，每次攻击到敌人后恢复自身一定生命（精英阶段0为30，精英阶段1为50，精英阶段2为70）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 剑豪 —— 模板干员「锏」（Degenbrecher，6★）。
     *
     * <p>分支特性：普通攻击连续造成两次伤害</p>
     * <p>模组后：技能造成的伤害提升10%</p>
     */
    GUARD_SWORDMASTER(UnitClass.GUARD, "剑豪", "锏",
            "Degenbrecher", "6", "近战位",
            2, 2, 19, 1.30D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、爆发、输出、削弱",
            "谢拉格、喀兰贸易",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "纯粹的武力", "攻击回复", "自动触发", 3, 0.0D, DurationKind.TIMED, 0, -1, -1, "下次攻击对周围最多6名地面敌人造成两次相当于攻击力220%的物理伤害"),
                    new Skill("技能2", "无声的嘲笑", "攻击回复", "手动触发", 12, 0.0D, DurationKind.TIMED, 10, -1, -1, "对前方范围内最多6名地面敌人发动2次斩击，攻击被阻挡的敌人时改为3次斩击，天赋的发动概率提升至100%，每次斩击造成相当于攻击力310%的物理伤害；可充能2次"),
                    new Skill("技能3", "归于宁静", "自动回复", "手动触发", 30, 0.0D, DurationKind.TIMED, 20, -1, -1, "持续发动总计10次斩击，每次斩击对最多6名敌人造成相当于攻击力235%的物理伤害，天赋的发动概率提升至100%，并持续将敌人中等力度地拖拽至自身中心，之后将造成一次相当于攻击力330%的物理伤害并将敌人较大力地拖拽至自身中心")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「普通攻击连续造成两次伤害」—— 两次都打在**同一目标**上，目标数仍是 1",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 2, 1.0D,
            "无｜EN 官方 wiki（Arknights Terra Wiki）分支页：Swordmaster「Normal attacks deal damage twice.」（两段各 100%、一次出手算一次、护甲每段各扣一次）",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "普通攻击连续造成两次伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 无畏者 —— 模板干员「止颂」（Lessing，6★）。
     *
     * <p>分支特性：能够阻挡一个敌人</p>
     * <p>模组后：攻击被阻挡的敌人时攻击力提升至115%</p>
     */
    GUARD_DREADNOUGHT(UnitClass.GUARD, "无畏者", "止颂",
            "Lessing", "6", "近战位",
            1, 1, 19, 1.50D, 80,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出",
            "莱塔尼亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "强力击·γ型", "攻击回复", "自动触发", 2, 0.0D, DurationKind.TIMED, 0, -1, -1, "下次攻击的攻击力提高至290%"),
                    new Skill("技能2", "虔修对决", "初始触发", "被动", 0, 24.0D, DurationKind.PASSIVE, 0, -1, -1, "部署后，第一天赋效果提升至2.2倍，攻击力+60%，攻击变为2连击"),
                    new Skill("技能3", "苦修破誓", "自动回复", "手动触发", 40, 20.0D, DurationKind.TIMED, 30, -1, -1, "自身免疫异常状态，生命值+110%，攻击被阻挡目标时，造成相当于攻击力220%的物理伤害；干员处于异常状态时可以释放技能并清除异常状态，但会对自身造成600点法术伤害")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡一个敌人」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡一个敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 强攻手 —— 模板干员「百炼嘉维尔」（Gavial the Invincible，6★）。
     *
     * <p>分支特性：同时攻击阻挡的所有敌人</p>
     * <p>模组后：同时攻击阻挡的所有敌人，攻击被阻挡的敌人攻击力提升至110%</p>
     */
    GUARD_CENTURION(UnitClass.GUARD, "强攻手", "百炼嘉维尔",
            "Gavial the Invincible", "6", "近战位",
            3, 3, 20, 1.20D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、爆发、输出、生存",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "精准痛击", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 10, -1, -1, "攻击力+80%，每次攻击治疗自身相当于造成伤害40%的生命值"),
                    new Skill("技能2", "链锯强袭", "自动回复", "手动触发", 35, 40.0D, DurationKind.TIMED, 25, -1, -1, "攻击范围扩大，攻击力+180%，防御力+50%；攻击到未被阻挡的敌人时，将其中等力度地拖拽至面前"),
                    new Skill("技能3", "丛林之魂", "自动回复", "手动触发", 35, 25.0D, DurationKind.TIMED, 25, -1, -1, "攻击力+140%，攻击速度+100，阻挡数+2，技能期间暂时只受到50%的伤害，其余伤害延后至技能结束，变为持续20秒等量的生命流失效果")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.MULTI_BLOCKED, true,
            "同时攻击阻挡的所有敌人",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "同时攻击阻挡的所有敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 近卫 · 教官 —— 模板干员「帕拉斯」（Pallas，6★）。
     *
     * <p>分支特性：可以攻击到较远敌人，攻击自身未阻挡的敌人时攻击力提升至120%</p>
     * <p>模组后：可以攻击到较远敌人，攻击自身未阻挡的敌人时攻击力提升至130%</p>
     */
    GUARD_INSTRUCTOR(UnitClass.GUARD, "教官", "帕拉斯",
            "Pallas", "6", "近战位",
            2, 2, 15, 1.00D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "输出、支援、近战位",
            "米诺斯",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "胜利的连击", "攻击回复", "自动触发", 2, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击造成相当于攻击力175%的物理伤害，并连续攻击两次"),
                    new Skill("技能2", "信念的长鞭", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 10, -1, -1, "攻击范围向前延伸一格，攻击力+80%，每次攻击有+85%几率使目标晕眩0.2秒"),
                    new Skill("技能3", "英勇的祝福", "自动回复", "手动触发", 50, 30.0D, DurationKind.TIMED, 35, -1, -1, "攻击力+100%，额外攻击两个目标，身前一格为近战位并部署我方干员时使其获得以下增益：生命值高于80%获得50%攻击力的橙色|精力充沛、防御力+35%、阻挡数+1（若身前一格不存在干员或不为近战位时，该效果由自身获得）")
            },
            "2-2", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「攻击自身未阻挡的敌人时增伤」，没有优先攻击与多目标",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可以攻击到较远敌人，攻击自身未阻挡的敌人时攻击力提升至120%",
            DamageMod.of(DamageMod.Kind.TARGET_NOT_BLOCKED, 1.2D, false),
            "打「不是我挡住的」敌人 → ×1.2｜PRTS 干员页精二特性原文：「可以攻击到较远敌人，攻击自身未阻挡的敌人时攻击力提升至120%」；《分支特性信息》：「攻击力提升效果为攻击力倍率提升，可对任何『自身阻挡的单位』以外的任何单位生效」",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 裂空炮手 —— 模板干员「埃癸斯」（Aegis，5★）。
     *
     * <p>分支特性：部署后起飞，起飞后只攻击空中敌人；技能开启时降落且攻击造成群体物理伤害</p>
     */
    SNIPER_SKYBREAKER(UnitClass.SNIPER, "裂空炮手", "埃癸斯",
            "Aegis", "5", "远程位",
            1, 1, 20, 2.10D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、群攻",
            "S.E.E.S.",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "启动狂宴模式", "自动回复", "手动触发", 24, 20.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+140%，防御力+70%，随机攻击范围内的目标；技能结束后自身晕眩10秒"),
                    new Skill("技能2", "全弹发射", "自动回复", "手动触发", 24, 0.0D, DurationKind.INSTANT, 15, -1, -1, "锁定范围内一个目标，对其发射6枚导弹，每枚导弹造成相当于攻击力160%的物理伤害，之后自身飞踢向目标，对目标及目标周围敌人造成相当于攻击力300%的物理伤害"),
                    new Skill("技能3", "（表格无）", "", "", 0, 0.0D, DurationKind.INSTANT, 0, -1, -1, "")
            },
            "3-1", true,
            TargetPriority.AIR_ONLY, TargetCount.SINGLE, true,
            "起飞后只攻击空中敌人",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "部署后起飞，起飞后只攻击空中敌人；技能开启时降落且攻击造成群体物理伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 重射手 —— 模板干员「焰狐龙梓兰」（Violet Mizutsune Orchid，6★）。
     *
     * <p>分支特性：高精度的近距离射击</p>
     */
    SNIPER_HEAVYSHOOTER(UnitClass.SNIPER, "重射手", "焰狐龙梓兰",
            "Violet Mizutsune Orchid", "6", "远程位",
            1, 1, 16, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、快速复活",
            "罗德岛、行动预备组A6",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "刚射", "自动回复", "手动触发", 4, 0.0D, DurationKind.INSTANT, 14, -1, -1, "发射4支攻击力160%的箭矢，若还有充能则额外消耗1层施展刚连射：发射5支攻击力200%的箭矢，每支箭矢有20%概率使目标晕眩2秒；可充能4次，充能至最大层数时自动释放一次"),
                    new Skill("技能2", "飞翔瞪射", "自动回复", "手动触发", 20, 4.2D, DurationKind.TIMED, 17, -1, -1, "立刻起飞并对前方范围的所有目标射击3次，分别射出3、4、5支攻击力提升至180%的箭矢；之后降落并对前方小范围内的所有敌人造成攻击力300%的物理伤害；可充能3次，部署后立即释放一次"),
                    new Skill("技能3", "龙之箭", "自动回复", "手动触发", 25, 0.0D, DurationKind.INFINITE, 30, -1, -1, "蓄力3秒后射出直线飞行的贯穿箭矢，箭矢以较大力度推动每个经过的敌人，每飞行一段距离都会对周围所有敌人造成攻击力360%的物理伤害和攻击力60%的法术伤害，箭矢射程无限；可充能2次")
            },
            "3-6", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「高精度的近距离射击」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "高精度的近距离射击",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 神射手 —— 模板干员「蕾缪安」（Lemuen，6★）。
     *
     * <p>分支特性：优先攻击攻击范围内防御力最低的敌方单位</p>
     */
    SNIPER_DEADEYE(UnitClass.SNIPER, "神射手", "蕾缪安",
            "Lemuen", "6", "远程位",
            1, 1, 20, 2.70D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、爆发",
            "拉特兰",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "重逢问候", "攻击回复", "自动触发", 8, 0.0D, DurationKind.AMMO, 0, 6, 1, "攻击时攻击力提升至210%，额外攻击1名敌人；攻击装有6发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能2", "归乡邀约", "自动回复", "手动触发", 30, 0.0D, DurationKind.AMMO, 15, 7, 1, "攻击速度+80，攻击力+70%，场上有被通缉敌人时会消耗1发弹药对其瞄准最多3.5秒后进行无视闪避的狙击（期间攻击力从180%逐渐提升至425%，攻击力高于敌人剩余生命值和防御力之和时停止瞄准并立刻攻击）；攻击装有7发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能3", "礼炮·强制追思", "自动回复", "手动触发", 38, 0.0D, DurationKind.AMMO, 32, 5, 1, "停止攻击，每0.5秒消耗1发弹药依次锁定敌人，技能结束时对每名被锁定的敌人所在位置进行轰炸，轰炸造成攻击力300%的范围物理伤害（中心伤害提升至攻击力的450%）；攻击装有5发弹药，打完后结束（可随时停止技能）")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」   // 暂定：原作是「每 0.5 秒消耗 1 发弹药」（按**时间**消耗），本 mod 只做「每次出手消耗 N 发」这一条判据，这里按出手近似
            },
            "3-9", true,
            TargetPriority.LOWEST_ARMOR, TargetCount.SINGLE, true,
            "优先攻击攻击范围内防御力最低的敌方单位",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "优先攻击攻击范围内防御力最低的敌方单位",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 回环射手 —— 模板干员「娜仁图亚」（Narantuya，6★）。
     *
     * <p>分支特性：持有回旋投射物时才能够攻击（投射物需要时间回收）</p>
     */
    SNIPER_LOOPSHOOTER(UnitClass.SNIPER, "回环射手", "娜仁图亚",
            "Narantuya", "6", "远程位",
            1, 1, 14, 1.00D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "萨尔贡",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "旋刃", "自动回复", "手动触发", 5, 0.0D, DurationKind.TIMED, 0, -1, -1, "可以在下列状态和初始状态间切换：；攻击距离-1，回旋投射物造成相当于攻击力190%的物理伤害，并且可以在敌人间重复弹射（最多弹跳3次）"),
                    new Skill("技能2", "恶魇", "攻击回复", "手动触发", 15, 30.0D, DurationKind.TIMED, 8, -1, -1, "每次攻击造成相当于攻击力250%的物理伤害并使目标停顿1秒，投射物命中目标后会在短暂突进后折返，返回时对穿过的所有敌人造成一次相当于攻击力200%的物理伤害"),
                    new Skill("技能3", "吞日", "自动回复", "手动触发", 30, 20.0D, DurationKind.TIMED, 20, -1, -1, "每次攻击发射3个回旋投射物（每个造成相当于攻击力175%的物理伤害），投射物全部回收时娜仁图亚对周围8格内至多3名敌人造成一次相当于攻击力160%的物理伤害并使其停顿1秒")
            },
            "y-7", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「持有回旋投射物时才能攻击」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "持有回旋投射物时才能够攻击（投射物需要时间回收）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 投掷手 —— 模板干员「维什戴尔」（Wiš'adel，6★）。
     *
     * <p>分支特性：攻击对小范围的地面敌人造成两次物理伤害（第二次为余震，伤害降低至攻击力的一半）</p>
     * <p>模组后：攻击对小范围的地面敌人造成三次物理伤害（后两次为余震，伤害降低至攻击力的50％）</p>
     */
    SNIPER_FLINGER(UnitClass.SNIPER, "投掷手", "维什戴尔",
            "Wiš'adel", "6", "远程位",
            1, 1, 23, 2.10D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "巴别塔",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "定点清算", "攻击回复", "自动触发", 2, 0.0D, DurationKind.TIMED, 0, -1, -1, "下次攻击额外造成2次余震并使所有目标晕眩1.5秒，此次攻击的溅射范围略微扩大且余震伤害变为攻击力的120%"),
                    new Skill("技能2", "饱和复仇", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+35%，攻击间隔缩短，可同时攻击3名敌人；过载：攻击改为攻击力80%的4连发，随机攻击范围内的目标；该技能可随时主动关闭"),
                    new Skill("技能3", "爆裂黎明", "自动回复", "手动触发", 50, 0.0D, DurationKind.AMMO, 40, 6, 1, "立刻在攻击范围内召唤2个魂灵之影（最多存在3个，技能结束后保留），攻击力+180%，攻击间隔大幅增大，攻击时攻击力提升至220%，溅射范围大幅扩大且第一天赋的发动概率提高至100%；攻击装有6发弹药，打完后结束（可随时停止技能）")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
            },
            "3-9", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里的「两次伤害」是对同一目标（第二次为余震），目标数仍是 1",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.splash(0.9D, 1.0D, false), 2, 0.5D,
            "落点溅射 r=0.9 ×1.0 不可对空｜PRTS《溅射半径一览》（特性表，精英二）：两次物理伤害，第二次为余震，伤害降低至攻击力的一半（半径 0.9、不可对空）",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击对小范围的地面敌人造成两次物理伤害（第二次为余震，伤害降低至攻击力的一半）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 猎手 —— 模板干员「莱伊」（Ray，6★）。
     *
     * <p>分支特性：攻击时需要消耗子弹且攻击力提升至120%，不攻击时会缓慢地装填子弹（最多4发）【初始】</p>
     */
    SNIPER_HUNTER(UnitClass.SNIPER, "猎手", "莱伊",
            "Ray", "6", "远程位",
            1, 1, 17, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "雷姆必拓",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "脱身矢", "自动回复", "手动触发", 10, 0.0D, DurationKind.INSTANT, 10, -1, -1, "立即用额外特殊子弹攻击目标，造成相当于攻击力450%的物理伤害并将其较大力地推开，若将其击倒则使下次装填额外加装2发子弹；可充能2次"),
                    new Skill("技能2", "广域警觉", "攻击回复", "自动触发", 16, 0.0D, DurationKind.INFINITE, 0, -1, -1, "被动效果：沙地兽撤退时回收命中该区域的子弹；自动开启：攻击范围扩大，攻击力+120%，沙地兽的再部署时间-40%；持续时间无限"),
                    new Skill("技能3", "“得见光芒”", "自动回复", "手动触发", 30, 16.0D, DurationKind.TIMED, 20, -1, -1, "立即停止攻击直至子弹装满，装填间隔大幅缩短，攻击范围扩大，攻击造成相当于攻击力330%的物理伤害并使目标束缚2秒；技能期间若击倒敌人，技能结束时获得10点技力")
            },
            "4-9", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「消耗子弹 / 装填子弹」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击时需要消耗子弹且攻击力提升至120%，不攻击时会缓慢地装填子弹（最多4发）【初始】",
            DamageMod.of(DamageMod.Kind.CONSUMES_BULLET, 1.2D, false),
            "CONSUMES_BULLET → ×1.2｜PRTS 干员页精二特性原文：「攻击时需要消耗子弹且攻击力提升至120%，不攻击时会缓慢地装填子弹（最多4发）」；★ 子弹系统未做 ⇒ 把**每一击都视为消耗子弹**（等价于每次都乘）；空仓/装填（精二 6 发）与「不攻击时装填」留作另一根轴",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 攻城手 —— 模板干员「提丰」（Typhon，6★）。
     *
     * <p>分支特性：优先攻击重量最重的敌人</p>
     * <p>模组后：攻击重量较重（重量等级大于等于3）的敌人时，攻击力提升至115%</p>
     */
    SNIPER_BESIEGER(UnitClass.SNIPER, "攻城手", "提丰",
            "Typhon", "6", "远程位",
            1, 1, 22, 2.40D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "萨米",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "迅捷打击·γ型", "自动回复", "手动触发", 35, 35.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+45%，攻击速度+45"),
                    new Skill("技能2", "冰原秩序", "自动回复", "手动触发", 50, 20.0D, DurationKind.INFINITE, 42, -1, -1, "攻击力+50%，每次攻击发射两支箭矢（优先攻击不同目标），并有40%概率晕眩目标1秒；第二次及以后使用时持续时间无限"),
                    new Skill("技能3", "“永恒狩猎”", "自动回复", "手动触发", 40, 0.0D, DurationKind.AMMO, 25, 10, 1, "立刻标记攻击范围内的一名目标，攻击间隔大幅增大，攻击变为对标记目标发射一轮箭雨；箭雨会随机攻击标记目标周围的敌人，共造成5次相当于攻击力175%的物理伤害并使目标晕眩0.4秒；攻击装有10发弹药，打完后结束（可随时停止技能）")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
            },
            "4-3", true,
            TargetPriority.HEAVIEST, TargetCount.SINGLE, true,
            "优先攻击重量最重的敌人",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "优先攻击重量最重的敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 炮手 —— 模板干员「菲亚梅塔」（Fiammetta，6★）。
     *
     * <p>分支特性：攻击造成群体物理伤害</p>
     * <p>模组后：攻击造成群体物理伤害；攻击时无视敌人100点的防御力</p>
     */
    SNIPER_ARTILLERYMAN(UnitClass.SNIPER, "炮手", "菲亚梅塔",
            "Fiammetta", "6", "远程位",
            1, 1, 25, 2.80D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "拉特兰",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "“你须直面”", "攻击回复", "手动触发", 10, 30.0D, DurationKind.TIMED, 5, -1, -1, "攻击距离+1，攻击力+100%"),
                    new Skill("技能2", "“你须愧悔”", "攻击回复", "手动触发", 7, 0.0D, DurationKind.INSTANT, 0, -1, -1, "向前发射灼痕弹且每飞行一段距离在弹道上留下灼痕，到达攻击范围最远距离时爆炸造成400%的物理伤害；之后灼痕依次爆炸造成200%的物理伤害"),
                    new Skill("技能3", "“你须偿还”", "攻击回复", "手动触发", 15, 0.0D, DurationKind.INSTANT, 0, -1, -1, "持续固定攻击范围内正前方最远一格，爆炸范围扩大，攻击力提升至125%，对目标位置附近小范围内的敌人攻击力额外提升至220%；")
            },
            "3-10", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里的「群体物理伤害」是落点溅射，不是一次选多个目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.splash(1.0D, 1.0D, true), 1, 1.0D,
            "落点溅射 r=1.0 ×1.0｜PRTS《溅射半径一览》（特性表，精英二）",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成群体物理伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 散射手 —— 模板干员「假日威龙陈」（Ch'en the Holungday，6★）。
     *
     * <p>分支特性：攻击范围内的所有敌人，对自己前方一横排的敌人攻击力提升至150%</p>
     */
    SNIPER_SPREADSHOOTER(UnitClass.SNIPER, "散射手", "假日威龙陈",
            "Ch'en the Holungday", "6", "远程位",
            1, 1, 29, 2.30D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、群攻",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "高压冲击", "攻击回复", "自动触发", 5, 0.0D, DurationKind.AMMO, 0, 4, 1, "攻击力+100%，攻击时对攻击范围内的所有敌人应用特性加成；攻击装有4发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能2", "“堇青之夜”", "自动回复", "手动触发", 20, 0.0D, DurationKind.AMMO, 12, 8, 1, "攻击力+80%，每次攻击在范围内生成持续5秒的粘液，地面敌人经过时移动速度-35%、防御力-170（不叠加），蓄力|技力达到上限可继续回复，回复至上限2倍时进入蓄力状态，此时开启技能会触发额外效果（任何时候开启均消耗全部技力）额外效果：改为20发弹药；攻击装有8发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能3", "“假日风暴”", "自动回复", "手动触发", 55, 0.0D, DurationKind.AMMO, 30, 32, 2, "攻击范围扩大，攻击力+100%，攻击造成两次伤害，对攻击范围内的所有敌人应用特性加成，每次攻击在范围内生成持续5秒的粘液，地面敌人经过时移动速度-45%、防御力-220（不叠加）；攻击装有32发弹药，每次攻击消耗2发，打完后结束（可随时停止技能）")
            },
            "2-5", true,
            TargetPriority.NEAREST, TargetCount.MULTI_ALL, true,
            "攻击范围内的所有敌人",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击范围内的所有敌人，对自己前方一横排的敌人攻击力提升至150%",
            DamageMod.of(DamageMod.Kind.TARGET_FRONT_ROW, 1.5D, false),
            "TARGET_FRONT_ROW → ×1.5｜PRTS 干员页精二特性原文：「攻击范围内的所有敌人，对自己**前方一横排**的敌人攻击力提升至150%」；判据 = 面朝方向的相邻那一排（forward == 1，横向不限，见 Targeting#isInFrontRow）",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 狙击 · 速射手 —— 模板干员「灰烬」（Ash，6★）。
     *
     * <p>分支特性：优先攻击空中单位</p>
     * <p>模组后：范围内存在地面敌人时攻击速度+8</p>
     */
    SNIPER_MARKSMAN(UnitClass.SNIPER, "速射手", "灰烬",
            "Ash", "6", "远程位",
            1, 1, 12, 1.00D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "彩虹小队",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "支援射击", "自动回复", "自动触发", 45, 0.0D, DurationKind.INSTANT, 0, -1, -1, "攻击力+15%，攻击变为2连击"),
                    new Skill("技能2", "突击战术", "自动回复", "手动触发", 25, 0.0D, DurationKind.INSTANT, 0, -1, -1, "立即触发第一天赋，攻击间隔大幅度缩短（-80%），且攻击被晕眩目标时攻击力提高至250%"),
                    new Skill("技能3", "攻坚榴弹", "自动回复", "手动触发", 25, 0.0D, DurationKind.INSTANT, 0, -1, -1, "向前发射破墙弹，对沿途敌人造成300%的物理伤害并向后较大力度推动；爆炸对周围造成400%的物理伤害（从低地撞到高台直接爆炸且造成800%的物理伤害）；每次部署只能释放2次")
            },
            "3-3", true,
            TargetPriority.AIR_FIRST, TargetCount.SINGLE, true,
            "优先攻击空中单位",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "优先攻击空中单位",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 本源铁卫 —— 模板干员「珊比」（Thumpy，6★）。
     *
     * <p>分支特性：能够阻挡三个敌人，可以造成元素损伤</p>
     */
    DEFENDER_PRIMAL_PROTECTOR(UnitClass.DEFENDER, "本源铁卫", "珊比",
            "Thumpy", "6", "近战位",
            3, 3, 22, 1.60D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、元素、防护",
            "雷姆必拓",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "“还不走？”", "自动回复", "手动触发", 30, 30.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+50%，防御力+50%，同时攻击并以小力度推动所有阻挡的敌人"),
                    new Skill("技能2", "“慢慢走~”", "自动回复", "手动触发", 23, 20.0D, DurationKind.TIMED, 14, -1, -1, "攻击力和防御力+70%；向前方可通行地面喷洒占据4格的强力胶，每0.7秒造成相当于珊比攻击力50%的物理伤害和0.5秒停顿，其他干员对强力胶内的敌人造成物理伤害时附带相当于珊比攻击力15%的侵蚀损伤"),
                    new Skill("技能3", "“不准走！”", "自动回复", "手动触发", 55, 60.0D, DurationKind.TIMED, 42, -1, -1, "阻挡数+2且同时攻击所有阻挡的敌人，攻击力+100%，防御力+135%；向前展开4格的便携传送带，使重量小于等于4且未被阻挡的敌人以每秒0.8格的速度移向珊比；传送带上的敌人每秒受到相当于珊比攻击力40%的物理伤害，且侵蚀损伤爆发时防御力额外-30")
            },
            "0-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡三个敌人，可以造成元素损伤」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡三个敌人，可以造成元素损伤",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 哨戒铁卫 —— 模板干员「机械师」（Mechanist，6★）。
     *
     * <p>分支特性：能够阻挡三个敌人，可以进行远程攻击</p>
     */
    DEFENDER_SENTRY_PROTECTOR(UnitClass.DEFENDER, "哨戒铁卫", "机械师",
            "Mechanist", "6", "近战位",
            3, 3, 19, 1.20D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、生存、召唤、输出",
            "罗德岛、罗德岛-精英干员",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "聚类分析", "攻击回复", "自动触发", 7, 0.0D, DurationKind.AMMO, 0, 3, 1, "攻击间隔增大，攻击变为五连击的群体攻击，每次造成攻击力155%的范围物理伤害；攻击装有3发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能2", "协防术式", "自动回复", "手动触发", 50, 0.0D, DurationKind.AMMO, 35, 8, 1, "攻击力+150%，自身和结构性原理立刻获得最大生命10%的屏障，屏障被摧毁时对周围敌人造成攻击力200%的法术伤害和3秒的战栗，并消耗1发弹药再次获得屏障；攻击装有8发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能3", "工程学十字星", "自动回复", "手动触发", 35, 40.0D, DurationKind.TIMED, 25, -1, -1, "攻击力+280%，攻击间隔大幅增大，攻击变为对十字范围内所有敌人造成攻击力260%的法术伤害，可攻击结构性原理阻挡的目标；结构性原理向前方冲锋，碰到敌人或高台时对范围内所有敌人造成机械师攻击力300%的物理伤害并停下，周围的敌人获得逐渐衰减的50%的虚弱效果")
            },
            "2-2", true,
            TargetPriority.BLOCKED_FIRST, TargetCount.SINGLE, true,
            "《分支特性信息》：「可以且优先攻击自身阻挡的单位（即使目标处于攻击范围外）」",
            AttackMethod.PROJECTILE,
            "近战位但有远程攻击：可以进行远程攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡三个敌人，可以进行远程攻击",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 驭法铁卫 —— 模板干员「斩业星熊」（Hoshiguma the Breacher，6★）。
     *
     * <p>分支特性：技能开启时普通攻击会造成法术伤害</p>
     */
    DEFENDER_ARTS_PROTECTOR(UnitClass.DEFENDER, "驭法铁卫", "斩业星熊",
            "Hoshiguma the Breacher", "6", "近战位",
            3, 3, 22, 1.60D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、生存",
            "炎-龙门、龙门近卫局",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "恶业苦果", "受击回复", "自动触发", 15, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击力+70%，防御力+70%，每次受到攻击时对目标造成相当于斩业星熊攻击力175%的法术伤害；持续时间无限"),
                    new Skill("技能2", "无始无明", "攻击回复", "自动触发", 6, 0.0D, DurationKind.INSTANT, 0, -1, -1, "投出盾牌并以攻击力90%的三连击攻击所有阻挡的敌人，盾牌会环绕斩业星熊飞行一圈；盾牌碰到敌人时使目标停顿，每0.5秒对其造成攻击力130%的法术伤害，自身回复盾牌造成伤害15%的生命"),
                    new Skill("技能3", "地狱变相", "自动回复", "手动触发", 45, 32.0D, DurationKind.TIMED, 37, -1, -1, "攻击范围扩大，生命上限+100%，攻击力+230%，以二连击攻击最多3名敌人；主动关闭技能后11秒内保留技能加成且不会被击倒，攻击变为四连击，承担攻击范围内我方干员受到的致命伤害，11秒后强制退出战场；可主动关闭（期间可随时停止技能）")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「技能开启时普通攻击会造成法术伤害」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "技能开启时普通攻击会造成法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 守护者 —— 模板干员「黍」（Shu，6★）。
     *
     * <p>分支特性：技能可以治疗友方单位</p>
     * <p>模组后：治疗生命值低于50%的友方单位时治疗量提升15%</p>
     */
    DEFENDER_GUARDIAN(UnitClass.DEFENDER, "守护者", "黍",
            "Shu", "6", "近战位",
            3, 3, 18, 1.20D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、防护、治疗、支援",
            "炎、炎-岁",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "化被草木", "自动回复", "自动触发", 4, 0.0D, DurationKind.TIMED, 0, -1, -1, "下一次攻击会为周围血量不足一半的一名友方单位恢复相当于攻击力180%的生命；可充能3次"),
                    new Skill("技能2", "嘉禾盈仓", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 20, -1, -1, "停止攻击专注治疗范围内至多两名我方单位，攻击力+120%，阻挡数+1，第一天赋效果提升至1.5倍；治疗拥有特殊间隔"),
                    new Skill("技能3", "离离枯荣", "自动回复", "手动触发", 45, 30.0D, DurationKind.TIMED, 30, -1, -1, "治疗范围扩大，攻击同时可治疗我方干员，攻击力+50%，有地面敌人处于播种地块时技能范围内的我方单位攻击力+25%、攻击速度+25，敌人经过播种地块时获得如下效果：当远离该地块超过2格时被传送回该地块")
            },
            "0-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「技能可以治疗友方单位」，对敌索敌没有偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "技能可以治疗友方单位",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 不屈者 —— 模板干员「斥罪」（Penance，6★）。
     *
     * <p>分支特性：无法被友方角色治疗</p>
     * <p>模组后：受到来自自身阻挡单位的伤害降低15%</p>
     */
    DEFENDER_JUGGERNAUT(UnitClass.DEFENDER, "不屈者", "斥罪",
            "Penance", "6", "近战位",
            3, 3, 32, 1.60D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、生存、输出",
            "叙拉古",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "一锤定音", "自动回复", "自动触发", 3, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击额外造成相当于攻击力200%的法术伤害；"),
                    new Skill("技能2", "坚心苦修", "自动回复", "手动触发", 30, 20.0D, DurationKind.TIMED, 20, -1, -1, "停止攻击，获得60%的庇护，每秒对周围所有地面敌人造成相当于攻击力140%的法术伤害，技能期间第一天赋的屏障获取效果提升100%"),
                    new Skill("技能3", "披荆斩棘", "受击回复", "手动触发", 20, 30.0D, DurationKind.TIMED, 0, -1, -1, "立即获得相当于生命上限130%的屏障，攻击间隔增大，攻击力+400%，自身更容易受到敌人攻击")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「无法被友方角色治疗」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            false,
            "PRTS 干员页精二特性原文（《分支特性信息》另写「常态持有禁疗」）：「无法被友方角色治疗」",
            "无法被友方角色治疗",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 要塞 —— 模板干员「号角」（Horn，6★）。
     *
     * <p>分支特性：不阻挡敌人时优先远程群体物理攻击</p>
     * <p>模组后：攻击被阻挡的敌人时攻击力提升至110%</p>
     */
    DEFENDER_FORTRESS(UnitClass.DEFENDER, "要塞", "号角",
            "Horn", "6", "近战位",
            3, 3, 24, 2.80D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、防护",
            "维多利亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "照明榴弹", "自动回复", "自动触发", 5, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击时造成280%攻击力的物理伤害，若为远程攻击则溅射范围扩大且在周围产生一个持续8秒的照明区域使敌人隐匿失效；"),
                    new Skill("技能2", "暴风号令", "自动回复", "手动触发", 25, 0.0D, DurationKind.INSTANT, 18, -1, -1, "每次攻击造成240%攻击力的物理溅射伤害；"),
                    new Skill("技能3", "终极防线", "自动回复", "手动触发", 35, 24.0D, DurationKind.TIMED, 25, -1, -1, "攻击力+70%，攻击间隔大幅缩短（-1.8）；")
            },
            "4-6", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「不阻挡敌人时优先远程群体物理攻击」，对敌索敌没有偏好",
            AttackMethod.PROJECTILE,
            "近战位但有远程攻击：不阻挡敌人时优先远程群体物理攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.splash(1.0D, 1.0D, false), 1, 1.0D,
            "落点溅射 r=1.0 ×1.0 不可对空｜PRTS《溅射半径一览》（特性表，精英二）（备注「不可对空」）",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "不阻挡敌人时优先远程群体物理攻击",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 决战者 —— 模板干员「森蚺」（Eunectes，6★）。
     *
     * <p>分支特性：只有阻挡敌人时才能够回复技力</p>
     * <p>模组后：缓慢回复技力，只有阻挡敌人时恢复技力回复速度</p>
     */
    DEFENDER_DUELIST(UnitClass.DEFENDER, "决战者", "森蚺",
            "Eunectes", "6", "近战位",
            1, 1, 29, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、生存、防护",
            "萨尔贡",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "轻型挂斧", "初始触发", "被动", 0, 0.0D, DurationKind.PASSIVE, 0, -1, -1, "攻击力+25%，防御力+25%"),
                    new Skill("技能2", "震慑劈砍", "自动回复", "手动触发", 28, 18.0D, DurationKind.TIMED, 15, -1, -1, "攻击间隔略微增大，攻击力+180%，技能期间持续使阻挡的敌人全部晕眩"),
                    new Skill("技能3", "钢铁意志", "自动回复", "手动触发", 45, 35.0D, DurationKind.TIMED, 25, -1, -1, "攻击力+230%，防御力+160%，阻挡数+2，每秒恢复6%生命<BR>")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「只有阻挡敌人时才能够回复技力」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "只有阻挡敌人时才能够回复技力",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 重装 · 铁卫 —— 模板干员「年」（Nian，6★）。
     *
     * <p>分支特性：能够阻挡三个敌人</p>
     */
    DEFENDER_PROTECTOR(UnitClass.DEFENDER, "铁卫", "年",
            "Nian", "6", "近战位",
            3, 3, 19, 1.50D, 70,   // 常态阻挡 3 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、防护、支援",
            "炎、炎-岁",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "锡灼", "自动回复", "手动触发", 30, 30.0D, DurationKind.TIMED, 10, -1, -1, "防御力+70%，攻击力+45%，普通攻击造成法术伤害"),
                    new Skill("技能2", "铜印", "自动回复", "手动触发", 50, 35.0D, DurationKind.TIMED, 35, -1, -1, "停止攻击；防御力+130%，阻挡数+1，每次受到攻击时对目标造成相当于年攻击力90%的法术伤害并使其失去特殊能力5秒"),
                    new Skill("技能3", "铁御", "自动回复", "手动触发", 85, 45.0D, DurationKind.TIMED, 70, -1, -1, "攻击力+120%；周围其他友方干员的防御力+80%，阻挡数+1，并获得抵抗（晕眩、寒冷和冻结的持续时间减少50%）")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡三个敌人」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡三个敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 轰击术师 —— 模板干员「谬因」（Aphrissa，6★）。
     *
     * <p>分支特性：攻击造成超远距离的群体法术伤害</p>
     */
    CASTER_BLAST(UnitClass.CASTER, "轰击术师", "谬因",
            "Aphrissa", "6", "远程位",
            1, 1, 31, 2.90D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、群攻、输出",
            "哥伦比亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "连续映射", "自动回复", "自动触发", 35, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击力+165%，“中继器”持续时间变为无限；持续时间无限"),
                    new Skill("技能2", "临界瞬爆", "自动回复", "手动触发", 18, 8.0D, DurationKind.INFINITE, 16, -1, -1, "向前发射一道无限长直线的光束，每0.5秒造成攻击力150%的法术伤害，光束可被我方干员改变方向"),
                    new Skill("技能3", "混沌的本质", "自动回复", "手动触发", 40, 0.0D, DurationKind.AMMO, 33, 20, 1, "技能开启后停止攻击3秒，撤回已部署的“中继器”并重置再部署；攻击间隔较大幅度缩短，攻击力+145%，攻击造成200%的法术伤害，并使目标停顿0.4秒；攻击经过“中继器”后，额外造成一次攻击力30%的法术伤害，且停顿时间延长0.4秒；技能期间可额外部署2；攻击装有20发弹药，打完后结束（可随时停止技能）")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
            },
            "5-1", true,
            TargetPriority.NEAREST, TargetCount.MULTI_ALL, true,
            "攻击造成超远距离的群体法术伤害（实为直线群体，见 EN wiki 特性原文）",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成超远距离的群体法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 秘术师 —— 模板干员「维伊」（Вий，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，在找不到攻击目标时可以将攻击能量储存起来之后一齐发射（最多3个）</p>
     */
    CASTER_MYSTIC(UnitClass.CASTER, "秘术师", "维伊",
            "Вий", "6", "远程位",
            1, 1, 25, 3.00D, 80,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "乌萨斯",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "“自呼号生发”", "自动回复", "自动触发", 25, 18.0D, DurationKind.TIMED, 12, -1, -1, "攻击速度+100"),
                    new Skill("技能2", "“以鲜血洗去”", "自动回复", "手动触发", 32, 28.0D, DurationKind.TIMED, 10, -1, -1, "攻击间隔小幅缩短，每次攻击或发射储存能量后，使自身后续攻击力+15%，攻击速度+8（至多叠加9层，发射转置能量可叠加3层），持续至技能结束"),
                    new Skill("技能3", "“用赤铁铭记”", "自动回复", "手动触发", 45, 0.0D, DurationKind.AMMO, 28, 27, 1, "攻击速度+180，储存的能量发射后会在敌人间（优先不同目标）跳跃一次，并造成相当于165%攻击力的法术伤害，转置能量改为跳跃3次；攻击装有27发弹药，仅在发射每个储存能量时消耗1发弹药，发射转置能量消耗3发弹药，弹药打完后结束（可随时停止技能）")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
            },
            "3-14", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里的「储存能量一齐发射」是聚能爆发，不是一次选多个目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，在找不到攻击目标时可以将攻击能量储存起来之后一齐发射（最多3个）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 阵法术师 —— 模板干员「圣聆初雪」（Pramanix the Prerita，6★）。
     *
     * <p>分支特性：通常时不攻击且防御力和法术抗性大幅度提升，技能开启时攻击造成群体法术伤害</p>
     */
    CASTER_PHALANX(UnitClass.CASTER, "阵法术师", "圣聆初雪",
            "Pramanix the Prerita", "6", "远程位",
            1, 1, 22, 2.00D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、群攻、控场",
            "谢拉格",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "铃音吹雪", "自动回复", "手动触发", 12, 0.0D, DurationKind.INSTANT, 12, -1, -1, "立即对范围内所有敌人造成相当于攻击力520%的法术伤害及3.5秒寒冷并将其中等力度地朝部署方向推动，之后积雪向前方地面扩散（最多向前扩散5格）；可充能2次"),
                    new Skill("技能2", "霜涛覆岭", "自动回复", "自动触发", 45, 0.0D, DurationKind.INFINITE, 0, -1, -1, "每次攻击造成相当于攻击力380%的法术伤害，积雪超过5层时会向周围扩散一层（最多向外扩散20格），处于积雪上的地面敌人每秒受到攻击力20%的法术伤害，敌人离开积雪时获得5秒寒冷，积雪在目标点积累至5层时，使目标点变为冻结状态；持续时间无限，可主动关闭技能（期间可随时停止技能）"),
                    new Skill("技能3", "群山俯首", "自动回复", "手动触发", 50, 35.0D, DurationKind.TIMED, 42, -1, -1, "攻击范围扩大，立即诱导攻击范围内的所有敌人至自身周围的可达地面，持续10秒，积雪生成速度加快，攻击力+100%，攻击速度+30，攻击无视目标10点法术抗性，每次攻击造成相当于攻击力260%的法术伤害")
            },
            "x-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, false,
            "通常时不攻击",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "通常时不攻击且防御力和法术抗性大幅度提升，技能开启时攻击造成群体法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 本源术师 —— 模板干员「真言」（Mantra，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，可以造成元素伤害</p>
     */
    CASTER_PRIMAL(UnitClass.CASTER, "本源术师", "真言",
            "Mantra", "6", "远程位",
            1, 1, 19, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、元素、输出、控场",
            "罗德岛、罗德岛-精英干员",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "共鸣溃缩", "攻击回复", "自动触发", 2, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击造成攻击力325%的法术伤害并附带伤害30%的神经损伤，若目标处于神经损伤爆发期间则额外造成200%攻击力的元素伤害"),
                    new Skill("技能2", "意识联协", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 18, -1, -1, "攻击间隔缩短，攻击造成攻击力240%的法术伤害并以短暂间隔跳跃至其他3名敌人造成攻击力120%的法术伤害，均附带伤害18%的神经损伤，若目标处于神经损伤爆发期间附带25%攻击力的元素伤害"),
                    new Skill("技能3", "无言为真", "自动回复", "手动触发", 40, 40.0D, DurationKind.TIMED, 31, -1, -1, "攻击范围扩大，范围内敌人隐匿失效且麻痹层数上限降为2，攻击力+275%同时攻击2个目标，范围中干员开启技能时（包括自身）使范围内敌人获得2层麻痹（至多3次），范围内敌人麻痹层数每超过上限1层在目标及1名敌人间跳跃1次185%攻击力的元素伤害，跳跃间存在短暂间隔")
            },
            "3-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「攻击造成法术伤害，可以造成元素伤害」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，可以造成元素伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 塑灵术师 —— 模板干员「死芒」（Necrass，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，可以通过击倒敌人生成召唤物，可攻击到自身召唤物阻挡的敌人</p>
     */
    CASTER_SHAPER(UnitClass.CASTER, "塑灵术师", "死芒",
            "Necrass", "6", "远程位",
            1, 1, 19, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、召唤、输出",
            "维多利亚、塔拉",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "噩愿", "自动回复", "手动触发", 13, 0.0D, DurationKind.INSTANT, 8, -1, -1, "被动：每个悲叹的仆役生成和升级时对周围所有敌人造成相当于死芒攻击力450%的法术伤害；开启：立刻重新召唤所有悲叹的仆役，若场上没有悲叹的仆役存在则召唤1个"),
                    new Skill("技能2", "折朽", "自动回复", "手动触发", 20, 12.0D, DurationKind.TIMED, 14, -1, -1, "令最多2名攻击范围内的敌人陷入沉睡，每0.5秒对目标造成相当于攻击力160%的法术伤害；技能期间目标被击倒时额外召唤2个悲叹的仆役"),
                    new Skill("技能3", "冠死以冕", "自动回复", "手动触发", 16, 0.0D, DurationKind.INSTANT, 5, -1, -1, "被动：最多1名悲叹的仆役变为特殊形态，此形态下只能通过技能升级，最多升级6次；开启：立刻对范围内所有敌人造成攻击力800%的法术伤害，之后消耗一个悲叹的仆役使特殊仆役升级并回复20%生命（若悲叹的仆役已升级则翻倍），重复3次")
            },
            "3-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「可攻击到自身召唤物阻挡的敌人」，没有优先攻击与多目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，可以通过击倒敌人生成召唤物，可攻击到自身召唤物阻挡的敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 驭械术师 —— 模板干员「荒芜拉普兰德」（Lappland the Decadenza，6★）。
     *
     * <p>分支特性：操作浮游单元造成法术伤害；单元攻击同一敌人伤害提升（最高造成干员110%攻击力的伤害）</p>
     */
    CASTER_MECH_ACCORD(UnitClass.CASTER, "驭械术师", "荒芜拉普兰德",
            "Lappland the Decadenza", "6", "远程位",
            1, 1, 20, 1.30D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、削弱",
            "叙拉古",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "慵怠者悲鸣", "自动回复", "手动触发", 6, 0.0D, DurationKind.INSTANT, 0, -1, -1, "被动效果：浮游单元+1；主动触发可以在下列状态和初始状态间切换：；攻击力+35%，释放浮游单元随机锁定整个战场内的非移动敌人进行攻击，目标移动或倒下后重新索敌"),
                    new Skill("技能2", "逐猎狂飙", "自动回复", "手动触发", 28, 22.0D, DurationKind.TIMED, 18, -1, -1, "浮游单元+3，攻击范围扩大，攻击力+120%，释放浮游单元随机锁定敌人攻击，浮游单元攻击时有10%几率使目标恐惧1秒；浮游单元在锁定目标倒下后重新索敌，直至技能结束后返回干员身边"),
                    new Skill("技能3", "终幕·浩劫", "自动回复", "手动触发", 54, 40.0D, DurationKind.TIMED, 38, -1, -1, "浮游单元+2，攻击力+80%，释放特殊形态的浮游单元散开后在整个战场范围各自追逐较近的敌人，追上时使目标恐惧3秒并锁定其攻击，浮游单元周围的敌人移动速度-50%且每秒受到相当于攻击力120%的法术伤害（不叠加）；浮游单元在锁定目标倒下后重新索敌，直至技能结束后返回干员身边")
            },
            "3-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「单元攻击同一敌人伤害提升」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "操作浮游单元造成法术伤害；单元攻击同一敌人伤害提升（最高造成干员110%攻击力的伤害）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 扩散术师 —— 模板干员「玛露西尔」（Marcille，6★）。
     *
     * <p>分支特性：攻击造成群体法术伤害</p>
     */
    CASTER_SPLASH(UnitClass.CASTER, "扩散术师", "玛露西尔",
            "Marcille", "6", "远程位",
            1, 1, 31, 2.90D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、群攻、治疗、控场",
            "莱欧斯小队",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "才女的实力", "自动回复", "手动触发", 80, 0.0D, DurationKind.INFINITE, 25, -1, -1, "短暂吟唱后开启技能，每次攻击消耗2点魔力，攻击力+140%，在找不到攻击目标时改为治疗友方干员；技能可随时主动关闭"),
                    new Skill("技能2", "召唤使魔", "自动回复", "自动触发", 80, 0.0D, DurationKind.INFINITE, 10, -1, -1, "吟唱11秒后，消耗35点魔力制作并使用使魔攻击，攻击力+100%，攻击使所有命中目标停顿0.5秒；第二次使用时使魔升级，攻击距离+1，攻击速度+60，攻击晕眩主目标0.5秒；技能持续时间无限，第一次使用可随时停止"),
                    new Skill("技能3", "爆破魔法", "自动回复", "手动触发", 80, 0.0D, DurationKind.INFINITE, 40, -1, -1, "吟唱5秒后，消耗8点魔力，在正前方位置造成爆炸，对周围敌人造成390%攻击力的法术伤害，炸到的高台会崩开碎片晕眩其周围敌人4秒；可追加吟唱10秒，完成后消耗剩余所有魔力，每额外消耗8点魔力，追加1次爆炸；追加吟唱可随时停止")
            },
            "3-6", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里的「群体法术伤害」是落点溅射，不是一次选多个目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.splash(1.1D, 1.0D, true), 1, 1.0D,
            "落点溅射 r=1.1 ×1.0｜PRTS《溅射半径一览》（特性表，精英二）（干员可覆盖：Pith 0.9 / 格雷伊 1.0）",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成群体法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 中坚术师 —— 模板干员「逻各斯」（Logos，6★）。
     *
     * <p>分支特性：攻击造成法术伤害</p>
     * <p>模组后：造成法术伤害时附带相当于8%伤害的凋亡损伤·我方|显示=凋亡损伤</p>
     */
    CASTER_CORE(UnitClass.CASTER, "中坚术师", "逻各斯",
            "Logos", "6", "远程位",
            1, 1, 19, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "罗德岛、罗德岛-精英干员",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "殁亡", "自动回复", "自动触发", 60, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击范围扩大，攻击力+100%，使攻击范围内生命值低于逻各斯攻击力150%的敌人立刻倒下、并对另一个随机目标造成与倒下单位生命值相等的法术伤害；持续时间无限"),
                    new Skill("技能2", "提喻", "自动回复", "手动触发", 30, 20.0D, DurationKind.TIMED, 20, -1, -1, "法术抗性+70，攻击改为锁定一个目标对其每0.5秒造成一次相当于攻击力75%的法术伤害，对相同目标的伤害逐渐提高至3倍并使其移动速度逐渐降低至40%(锁定5秒后达到上限)，被打断或目标倒下时重新索敌且效果重置"),
                    new Skill("技能3", "延异视阈", "自动回复", "手动触发", 45, 30.0D, DurationKind.TIMED, 30, -1, -1, "攻击范围扩大，攻击力+300%，同时攻击4个目标，使攻击范围内敌方子弹的飞行速度大幅降低、并在技能结束时将其全部清除")
            },
            "3-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「攻击造成法术伤害」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 术师 · 链术师 —— 模板干员「异客」（Passenger，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，且会在3（精英阶段2后为4）个敌人间跳跃，每次跳跃伤害降低15%并造成短暂（0.5秒）停顿|移动速度降低80%</p>
     * <p>模组后：攻击造成法术伤害，且会在4个敌人间跳跃，每次跳跃伤害降低10%并造成一定时间（0.8秒）停顿|移动速度降低80%</p>
     */
    CASTER_CHAIN(UnitClass.CASTER, "链术师", "异客",
            "Passenger", "6", "远程位",
            1, 1, 30, 2.30D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出",
            "萨尔贡",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "电能之触", "自动回复", "自动触发", 5, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击时攻击力提升至250%，最多在4个目标间跳跃，攻击造成的停顿|移动速度降低80%时间延长至1.5秒"),
                    new Skill("技能2", "聚焦指令", "自动回复", "手动触发", 40, 35.0D, DurationKind.TIMED, 20, -1, -1, "攻击距离+1，攻击力+30%，攻击间隔较大幅度缩短（-0.5），攻击最大弹跳次数提升至5"),
                    new Skill("技能3", "辉煌裂片", "自动回复", "手动触发", 30, 0.0D, DurationKind.INSTANT, 0, -1, -1, "立即寻找大范围内生命值最高的目标，在其位置生成持续4秒的雷暴区域，期间每0.5秒以150%的攻击力对雷暴区域内的随机敌人进行一次额外攻击；可充能2次")
            },
            "3-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里的「在 3~4 个敌人间跳跃」是连锁，不是一次选多个目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.chain(4, 0.85D, 1.7D), 1, 1.0D,
            "连锁 4 目标 · 每跳 ×0.85 · 搜索半径 1.7｜PRTS《溅射半径一览》（特性表，精英二）：会在4个敌人间跳跃，每次跳跃伤害降低15%",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.CHAIN, 10),
            "连锁每跳目标 → 停顿 10 tick（0.5 秒）｜PRTS 干员页精二特性原文：「每次跳跃伤害降低 15% 并造成短暂停顿(0.5s)」｜PRTS《分支特性信息》（特性细则）：「『短暂停顿』的时长为 0.5s」",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，且会在3（精英阶段2后为4）个敌人间跳跃，每次跳跃伤害降低15%并造成短暂（0.5秒）停顿|移动速度降低80%",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 守望者 —— 模板干员「凯尔希·思衡托」（Kal'tsit·Esperanta，6★）。
     *
     * <p>分支特性：恢复友方单位生命，并且可以起飞</p>
     */
    MEDIC_WATCHMAN(UnitClass.MEDIC, "守望者", "凯尔希·思衡托",
            "Kal'tsit·Esperanta", "6", "远程位",
            1, 1, 17, 2.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、高空、治疗、支援",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "应急肃正防线", "自动回复", "手动触发", 35, 35.0D, DurationKind.TIMED, 28, -1, -1, "攻击力+90%，攻击速度+50，所有其他起飞的友方干员阻挡范围扩大"),
                    new Skill("技能2", "保护性拒止", "自动回复", "手动触发", 35, 0.0D, DurationKind.AMMO, 28, 10, 1, "攻击范围扩大，攻击力+150%，攻击变为射出医疗单元（优先选择敌人），击中时对目标周围所有敌人造成相当于攻击力380%的真实伤害，使其停顿5秒并回复目标周围所有友方干员相当于攻击力200%的生命；攻击装有10发弹药，可以随时停止技能"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能3", "破梏重生", "自动回复", "手动触发", 50, 35.0D, DurationKind.TIMED, 35, -1, -1, "立刻获得战术锚点，攻击力+150%，攻击间隔大幅缩小，额外治疗1个目标，部署战术锚点后，自身移动至该位置，并使攻击范围内最多2名友方干员可以部署至自身攻击范围的另一位置")
            },
            "y-6", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「恢复友方单位生命」（治疗机制，本次不做），对敌索敌无偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.SURROUNDING,
            "设计指定：该分支精二范围 y-6 绕着自身一圈（含身后一列）→ 阻挡搜索按周围九格",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.SINGLE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "单体治疗｜对照表「分支机制（干员特性原文）」：恢复友方单位生命，并且可以起飞（★「起飞」与治疗无关，本轮不做）",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "恢复友方单位生命，并且可以起飞",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 咒愈师 —— 模板干员「缇缇」（Titi，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，攻击敌人时为攻击范围内一名友方干员治疗相当于50%伤害的生命值</p>
     */
    MEDIC_INCANTATION(UnitClass.MEDIC, "咒愈师", "缇缇",
            "Titi", "6", "远程位",
            1, 1, 15, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、治疗、防护、控场",
            "萨尔贡",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "缓蚀", "自动回复", "手动触发", 40, 17.0D, DurationKind.TIMED, 18, -1, -1, "攻击力+100%，每次攻击有50%概率使目标沉睡3秒"),
                    new Skill("技能2", "封护", "自动回复", "手动触发", 20, 15.0D, DurationKind.TIMED, 15, -1, -1, "停止攻击，攻击力+180%，技能开启时使自身与攻击范围内生命比例最低的干员陷入沉睡直到技能结束，期间持续使自身与该目标周围的敌人陷入沉睡，第一天赋效果提升至3.3倍；可主动关闭技能（期间可随时停止技能）"),
                    new Skill("技能3", "旧日绽放", "自动回复", "手动触发", 37, 25.0D, DurationKind.TIMED, 28, -1, -1, "攻击力+180%，同时攻击两名敌人，攻击非沉睡目标时使其沉睡5秒，技能期间，全场敌人从沉睡状态醒来或于沉睡中被击倒时，受到法术伤害（随沉睡时长提高至攻击力460%），并使周围一名其他敌人沉睡5秒，攻击范围内的其他友方干员受到致命伤害时，陷入沉睡至生命值完全恢复或技能结束")
            },
            "3-3", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「攻击敌人时治疗友方」（治疗机制，本次不做），对敌索敌无偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.POST_HIT, 1, 1.0D, 1.0D, "", 0.5D, 0.0D, 20),
            "先攻击，再治疗一名友方 = 伤害的 50.0%｜对照表「分支机制（干员特性原文）」：攻击造成法术伤害，攻击敌人时为攻击范围内一名友方干员治疗相当于50%伤害的生命值（★ 口径确认口径）",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，攻击敌人时为攻击范围内一名友方干员治疗相当于50%伤害的生命值",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 链愈师 —— 模板干员「Mon3tr」（Mon3tr，6★）。
     *
     * <p>分支特性：恢复友方单位生命，且会在3个友方单位间跳跃，每次跳跃治疗量降低25%</p>
     */
    MEDIC_CHAIN(UnitClass.MEDIC, "链愈师", "Mon3tr",
            "Mon3tr", "6", "远程位",
            1, 1, 16, 2.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、治疗、输出、支援",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "策略：超压链接", "攻击回复", "自动触发", 2, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次治疗可以恢复相当于攻击力200%的生命，治疗的跳跃次数+1"),
                    new Skill("技能2", "策略：超负荷", "攻击回复", "手动触发", 15, 30.0D, DurationKind.TIMED, 13, -1, -1, "技能期间优先治疗重构体，重构体每受到一次Mon3tr的治疗，就会进行一次跳跃治疗，第二天赋的效果提升至2.8倍"),
                    new Skill("技能3", "策略：熔毁", "攻击回复", "手动触发", 15, 25.0D, DurationKind.TIMED, 11, -1, -1, "攻击范围改变，移动至重构体位置，攻击力+330%，攻击间隔降低，阻挡数+2，生命上限+5000，每秒流失80点生命值，同时攻击阻挡的所有敌人，伤害类型变为真实，攻击治疗自身相当于攻击力50%的生命值；技能结束或受到致命伤害时，返回初始位置")
            },
            "y-2", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「在 3 个友方单位间跳跃」（治疗机制，本次不做），对敌索敌无偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.CHAIN, 3, 0.75D, 1.0D, "", 0.0D, 0.0D, 20),
            "连锁治疗 3 目标 · 每跳 ×0.75｜对照表「分支机制（干员特性原文）」：会在3个友方单位间跳跃，每次跳跃治疗量降低25%",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "恢复友方单位生命，且会在3个友方单位间跳跃，每次跳跃治疗量降低25%",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 行医 —— 模板干员「纯烬艾雅法拉」（Eyjafjalla the Hvít Aska，6★）。
     *
     * <p>分支特性：恢复友方单位生命，并回复相当于攻击力50%的元素损伤（可以回复未受伤友方单位的元素损伤）</p>
     */
    MEDIC_WANDERING(UnitClass.MEDIC, "行医", "纯烬艾雅法拉",
            "Eyjafjalla the Hvít Aska", "6", "远程位",
            1, 1, 14, 2.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、治疗",
            "莱塔尼亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "无声润物", "自动回复", "自动触发", 60, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击力+40%，每次可额外治疗一名单位，攻击范围内所有友方单位每秒回复纯烬艾雅法拉攻击力8%的元素损伤；持续时间无限"),
                    new Skill("技能2", "云霭荫佑", "自动回复", "手动触发", 20, 0.0D, DurationKind.INSTANT, 0, -1, -1, "立即对攻击范围内所有友方单位进行一次治疗，并生成一个持续20秒的范围损伤屏障，为范围内的友方单位吸收相当于攻击力900%的元素损伤"),
                    new Skill("技能3", "火山回响", "自动回复", "手动触发", 60, 50.0D, DurationKind.TIMED, 40, -1, -1, "攻击范围扩大至整个战场，治疗变为60%治疗量和元素损伤回复量的5连发，优先治疗不同的目标，第二天赋的效果提升至5倍")
            },
            "3-17", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「恢复友方单位生命 + 元素损伤」（治疗机制，本次不做）",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.SINGLE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "单体治疗｜对照表「分支机制（干员特性原文）」：恢复友方单位生命，并回复相当于攻击力50%的元素损伤（★ 本 mod 无元素损伤系统，该部分不做）",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "恢复友方单位生命，并回复相当于攻击力50%的元素损伤（可以回复未受伤友方单位的元素损伤）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 疗养师 —— 模板干员「流明」（Lumen，6★）。
     *
     * <p>分支特性：拥有较大治疗范围，但在治疗较远目标时治疗量变为80%</p>
     * <p>模组后：自身获得抵抗且更不容易受到敌人的攻击</p>
     */
    MEDIC_THERAPIST(UnitClass.MEDIC, "疗养师", "流明",
            "Lumen", "6", "远程位",
            1, 1, 21, 2.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、治疗、支援",
            "伊比利亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "沐雨", "自动回复", "自动触发", 10, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击使目标和周围的友军每秒受到相当于55%流明攻击力的治疗效果，持续5秒"),
                    new Skill("技能2", "沛霖", "自动回复", "手动触发", 13, 0.0D, DurationKind.INSTANT, 5, -1, -1, "立刻恢复攻击范围内最多3名友方单位相当于攻击力260%的生命；；蓄力额外效果：并解除目标所受的异常状态"),
                    new Skill("技能3", "灯火不灭", "自动回复", "手动触发", 50, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击力+55%，攻击速度+30，优先治疗处于异常状态中的单位；只在治疗异常状态中的单位时会消耗子弹，使该次治疗量提升至攻击力的200%并解除目标所受的异常状态；橙色|攻击装有8发子弹，打完后技能结束（期间可随时停止技能）")
            },
            "3-4", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「治疗较远目标时治疗量变为80%」（治疗机制，本次不做）",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.SINGLE, 1, 1.0D, 0.8D, "3-10", 0.0D, 0.0D, 20),
            "单体治疗（内圈外 ×0.8）｜对照表「分支机制（干员特性原文）」：较大治疗范围，治疗较远目标时治疗量变为80%｜EN 官方 wiki 分支页：内圈 = 3×2 + 前方 1×1",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "拥有较大治疗范围，但在治疗较远目标时治疗量变为80%",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 医师 —— 模板干员「凯尔希」（Kal'tsit，6★）。
     *
     * <p>分支特性：恢复友方单位生命</p>
     * <p>模组后：治疗生命值低于50%的友方单位时治疗量提升15%</p>
     */
    MEDIC_MEDIC(UnitClass.MEDIC, "医师", "凯尔希",
            "Kal'tsit", "6", "远程位",
            1, 1, 18, 2.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、召唤、治疗",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "指令：结构加固", "自动回复", "手动触发", 20, 40.0D, DurationKind.TIMED, 10, -1, -1, "自身和Mon3tr的防御力+150%，且自身获得50%的物理格挡"),
                    new Skill("技能2", "指令：战术协同", "自动回复", "手动触发", 8, 20.0D, DurationKind.TIMED, 0, -1, -1, "自身的攻击速度+100，Mon3tr的攻击力+90%，Mon3tr可以攻击阻挡的所有敌人；"),
                    new Skill("技能3", "指令：熔毁", "自动回复", "手动触发", 15, 20.0D, DurationKind.TIMED, 0, -1, -1, "Mon3tr的防御力+200%，技能期间攻击力从+260%逐渐降低至+0%且伤害类型变为真实，此期间如果未击杀任何敌人则技能结束后流失最大生命的50%。橙色|该技能与Mon3tr绑定")
            },
            "3-10", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「恢复友方单位生命」（治疗机制，本次不做），对敌索敌无偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.SINGLE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "单体治疗｜对照表「分支机制（干员特性原文）」：恢复友方单位生命",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "恢复友方单位生命",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 医疗 · 群愈师 —— 模板干员「夜莺」（Nightingale，6★）。
     *
     * <p>分支特性：同时恢复三个友方单位的生命</p>
     */
    MEDIC_MULTI_TARGET(UnitClass.MEDIC, "群愈师", "夜莺",
            "Nightingale", "6", "远程位",
            1, 1, 16, 2.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、治疗、支援",
            "使徒",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "治疗强化·γ型", "自动回复", "手动触发", 30, 30.0D, DurationKind.TIMED, 20, -1, -1, "攻击力+90%"),
                    new Skill("技能2", "法术护盾", "自动回复", "自动触发", 8, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次治疗使所有目标获得一个持续5秒的屏障；屏障能吸收相当于夜莺攻击力90%的法术伤害，同时使目标法术抗性+20；可充能3次"),
                    new Skill("技能3", "圣域", "自动回复", "手动触发", 120, 60.0D, DurationKind.TIMED, 115, -1, -1, "攻击范围扩大，攻击力+80%，攻击范围内的友方单位法术抗性+150%并获得25%的法术闪避")
            },
            "y-2", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里说的是「同时恢复三个友方单位」（治疗机制，本次不做）",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.ALL, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "范围内全体治疗｜对照表「分支机制（干员特性原文）」：同时恢复三个友方单位的生命（★ 设计口径 = 范围内所有干员，见 设计文档）",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "同时恢复三个友方单位的生命",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 游击手 —— 模板干员「岳羽由加莉」（Yukari Takeba，5★）。
     *
     * <p>分支特性：可以使用触发型效果协助作战</p>
     */
    SUPPORTER_SUPPORTIVE_RANGER(UnitClass.SUPPORTER, "游击手", "岳羽由加莉",
            "Yukari Takeba", "5", "远程位",
            1, 1, 14, 2.10D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、支援",
            "S.E.E.S.",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "龙卷箭", "自动回复", "手动触发", 20, 0.0D, DurationKind.INSTANT, 10, -1, -1, "立即发射三发箭矢，每发对目标造成相当于80%攻击力的法术伤害，随后追加一次相当于攻击力400%的范围法术伤害并浮空所有目标1.5秒"),
                    new Skill("技能2", "明镜止水", "自动回复", "手动触发", 30, 0.0D, DurationKind.INSTANT, 15, -1, -1, "为范围内最多4名我方干员（优先选择结城理、术师干员）施加触发型效果：；干员施放技能后，自身在15秒内获得30%术法充盈"),
                    new Skill("技能3", "（表格无）", "", "", 0, 0.0D, DurationKind.INSTANT, 0, -1, -1, "")
            },
            "3-18", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「可以使用触发型效果协助作战」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可以使用触发型效果协助作战",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 工匠 —— 模板干员「娜斯提」（Nasti，6★）。
     *
     * <p>分支特性：能够阻挡两个敌人，使用<支援装置>协助作战</p>
     */
    SUPPORTER_ARTIFICER(UnitClass.SUPPORTER, "工匠", "娜斯提",
            "Nasti", "6", "近战位",
            2, 2, 15, 1.50D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、支援、防护",
            "哥伦比亚、莱茵生命",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "“拱卫”", "自动回复", "手动触发", 40, 40.0D, DurationKind.TIMED, 28, -1, -1, "携带此技能时：装置使前方干员防御力提升；攻击力+80%，防御力+80%，同时攻击阻挡的所有敌人；装置效果改为使前方干员阻挡数+1，防御力+55%，装置每秒流失2%最大生命值；技能开启时获得一个装置"),
                    new Skill("技能2", "“执行”", "自动回复", "手动触发", 28, 4.0D, DurationKind.TIMED, 23, -1, -1, "携带此技能时：装置部署后逐渐回复自身生命值，激活后每0.5秒流失1点生命值，使前方干员获得1点技力和相当于娜斯提生命上限5%的屏障；立即激活场上装置，停止攻击，每0.5秒获得相当于生命上限10%的屏障（最多不超过生命上限80%）；技能结束时获得一个装置"),
                    new Skill("技能3", "栖脚地", "自动回复", "手动触发", 25, 15.0D, DurationKind.TIMED, 17, -1, -1, "携带此技能时：装置可建造并升级一个可以放置远程干员并为其提供增益的特殊高台；攻击力+160%，防御力+160%；小工程师装置为高台装置回复技力和生命的效果翻倍；技能开启时获得一个装置")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「能够阻挡两个敌人，使用支援装置」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "能够阻挡两个敌人，使用<支援装置>协助作战",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 凝滞师 —— 模板干员「溯光星源」（Astgenne the Lightchaser，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，并对敌人造成短暂的停顿</p>
     */
    SUPPORTER_DECEL_BINDER(UnitClass.SUPPORTER, "凝滞师", "溯光星源",
            "Astgenne the Lightchaser", "6", "远程位",
            1, 1, 16, 1.90D, 80,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、减速、支援、输出",
            "哥伦比亚、莱茵生命",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "星图闪烁", "自动回复", "手动触发", 30, 20.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+100%，攻击可在敌人间重复跳跃，最多跳跃3次"),
                    new Skill("技能2", "星束引力", "攻击回复", "自动触发", 3, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击选择攻击范围内距离目标点最远的2个敌人为目标，造成相当于攻击力145%的法术伤害，攻击造成的停顿时间延长至3秒，并分别链接目标附近的最多3个敌人中等力度地拉向目标所在位置同时造成相同的法术伤害"),
                    new Skill("技能3", "并流连锁", "自动回复", "手动触发", 35, 25.0D, DurationKind.TIMED, 25, -1, -1, "攻击范围扩大，攻击力+70%，攻击间隔缩短，持续锁定2个敌人进行攻击，被锁定的敌人会互相链接；每个被链接的敌人会把即将受到法术伤害的25%额外传导给其他链接目标")
            },
            "y-2", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「对敌人造成短暂的停顿」，没有优先攻击与多目标",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.HIT, 16),
            "普攻命中主目标 → 停顿 16 tick（0.8 秒）｜PRTS《分支特性信息》（特性细则）：「特性停顿时间为 0.8 秒」｜PRTS 干员页精二特性原文",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，并对敌人造成短暂的停顿",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 护佑者 —— 模板干员「遥」（Haruka，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，技能开启后改为治疗友方单位（治疗量相当于75%攻击力）</p>
     */
    SUPPORTER_ABJURER(UnitClass.SUPPORTER, "护佑者", "遥",
            "Haruka", "6", "远程位",
            1, 1, 11, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、支援、生存、治疗",
            "东",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "夜啼彩羽", "自动回复", "手动触发", 25, 20.0D, DurationKind.TIMED, 15, -1, -1, "攻击速度+60，技能期间浮泡破碎时，立即使周围8格一名没有浮泡的友方单位获得浮泡"),
                    new Skill("技能2", "幽隙栖萤", "自动回复", "手动触发", 17, 26.0D, DurationKind.INFINITE, 0, -1, -1, "治疗目标数+1，浮光泡影的目标数+1；友方单位受到遥的治疗效果时，对周围3名敌人造成相当于治疗量200%的法术伤害；第二次及以后使用时攻击力+40%，且持续时间无限"),
                    new Skill("技能3", "夏末游鳞", "自动回复", "手动触发", 60, 40.0D, DurationKind.TIMED, 53, -1, -1, "攻击力+55%，攻击范围扩大，攻击间隔缩短，技能期间浮泡提供的庇护提升至2倍；攻击我方浮泡的敌人将浮空4秒，在此浮空期间每秒受到相当于遥攻击力80%的法术伤害")
            },
            "y-6", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「技能开启后改为治疗友方」，对敌索敌没有偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，技能开启后改为治疗友方单位（治疗量相当于75%攻击力）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 召唤师 —— 模板干员「电弧」（Raidian，6★）。
     *
     * <p>分支特性：攻击造成法术伤害；可以使用召唤物协助作战</p>
     */
    SUPPORTER_SUMMONER(UnitClass.SUPPORTER, "召唤师", "电弧",
            "Raidian", "6", "远程位",
            1, 1, 10, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、召唤、输出、支援",
            "罗德岛、罗德岛-精英干员",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "律动线", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 15, -1, -1, "被动：召唤物可部署在近战位；开启：自身和召唤物防御力+80%并获得100%最大生命值的屏障，持续至技能结束；技能开启时获得1个召唤物"),
                    new Skill("技能2", "环形鳞地", "自动回复", "手动触发", 25, 25.0D, DurationKind.TIMED, 16, -1, -1, "被动：召唤物可部署在近战位，会持续向前方发射子弹；开启：自身和召唤物攻击力+80%，召唤物攻击额外创造出2颗子弹且子弹飞行距离+2；技能开启时获得1个召唤物"),
                    new Skill("技能3", "手牵手", "自动回复", "手动触发", 40, 30.0D, DurationKind.TIMED, 30, -1, -1, "被动：召唤物可部署在远程位，召唤物找不到攻击目标且可攻击时，可以与其自身范围内其他电弧的召唤物协同攻击；开启：自身和召唤物攻击力+150%，对敌人造成伤害时，使敌人受到停顿与35％法术脆弱效果，持续2秒；技能开启时获得1个召唤物")
            },
            "3-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「可以使用召唤物协助作战」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害；可以使用召唤物协助作战",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 巫役 —— 模板干员「酒神」（Tragodia，6★）。
     *
     * <p>分支特性：攻击造成法术伤害，可以造成元素损伤</p>
     */
    SUPPORTER_RITUALIST(UnitClass.SUPPORTER, "巫役", "酒神",
            "Tragodia", "6", "远程位",
            1, 1, 14, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、元素、控场",
            "维多利亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "暗夜回声", "攻击回复", "自动触发", 2, 0.0D, DurationKind.INSTANT, 2, -1, -1, "下次攻击造成两次相当于攻击力150%的法术伤害并使目标束缚3秒，该次束缚期间目标受到的神经损伤提升至1.8倍，优先攻击未处于爆发期间的敌人"),
                    new Skill("技能2", "群体性谵妄", "自动回复", "自动触发", 25, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击速度+35，第一天赋溅射范围扩大；期间可以使用一个本能的召唤，部署后诱导至多4名进入范围的可达敌人（优先精英或领袖）10秒，首个目标到达后撤退并使周围所有敌人在6秒内停顿、每0.5秒受到酒神攻击力150%的法术伤害和攻击力25%的神经损伤；持续时间无限，本能的召唤有独立再部署时间"),
                    new Skill("技能3", "空剧场", "自动回复", "手动触发", 40, 30.0D, DurationKind.TIMED, 30, -1, -1, "攻击范围扩大，攻击力+125%，优先攻击未处于损伤爆发期间的敌人，技能期间酒神造成过神经损伤的敌人每秒受到攻击力10%的神经损伤直至爆发；范围内敌人神经损伤冷却恢复速度+50%，神经损伤爆发时在其地面位置生成/刷新一个迷狂牢笼")
            },
            "y-2", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「攻击造成法术伤害，可以造成元素损伤」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害，可以造成元素损伤",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 吟游者 —— 模板干员「魔王」（Civilight Eterna，6★）。
     *
     * <p>分支特性：不攻击，持续恢复范围内所有友军生命（每秒恢复相当于自身攻击力10%的生命），自身不受鼓舞影响</p>
     */
    SUPPORTER_BARD(UnitClass.SUPPORTER, "吟游者", "魔王",
            "Civilight Eterna", "6", "远程位",
            1, 1, 8, 1.30D, 80,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、支援、生存、输出",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "往昔萦绕身旁", "自动回复", "自动触发", 65, 0.0D, DurationKind.INFINITE, 0, -1, -1, "自身特性效果提高至35%，“微尘”的重生速度加快；持续时间无限"),
                    new Skill("技能2", "明日渺远不及", "自动回复", "手动触发", 35, 35.0D, DurationKind.TIMED, 20, -1, -1, "“微尘”上限+3，立刻获得6枚“微尘”，“微尘”旋转半径扩大，攻击范围内所有其他友方单位获得相当于魔王100%攻击力的鼓舞效果，“微尘”不再碰撞友方干员，“微尘”碰撞敌方单位时造成相当于魔王攻击力275%的真实伤害，并使目标束缚3.5秒"),
                    new Skill("技能3", "编织重构现世", "自动回复", "手动触发", 55, 30.0D, DurationKind.TIMED, 35, -1, -1, "攻击范围扩大，自身特性效果提高至90%，“微尘”不再消失，攻击范围内所有其它友方单位获得相当于魔王100%的最大生命值的鼓舞效果，每隔2秒重新分配攻击范围内所有友方单位的生命")
            },
            "x-1", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, false,
            "不攻击",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.REGEN, 1, 1.0D, 1.0D, "", 0.0D, 0.1D, 20),
            "被动：每 20 tick 给范围内全体友军回 0.1×攻击力（不攻击）｜对照表「分支机制（干员特性原文）」：不攻击，持续恢复范围内所有友军生命（每秒恢复相当于自身攻击力10%的生命）｜★ 鼓舞机制本轮不做",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "不攻击，持续恢复范围内所有友军生命（每秒恢复相当于自身攻击力10%的生命），自身不受鼓舞影响",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 辅助 · 削弱者 —— 模板干员「灵知」（Gnosis，6★）。
     *
     * <p>分支特性：攻击造成法术伤害</p>
     * <p>模组后：并对目标造成10％虚弱效果，持续2秒</p>
     */
    SUPPORTER_HEXER(UnitClass.SUPPORTER, "削弱者", "灵知",
            "Gnosis", "6", "远程位",
            1, 1, 11, 1.60D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、削弱",
            "谢拉格、喀兰贸易",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "高速思考", "自动回复", "自动触发", 4, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击连续攻击两次，每次造成相当于攻击力170%的法术伤害"),
                    new Skill("技能2", "零度爆发", "自动回复", "手动触发", 6, 0.0D, DurationKind.INSTANT, 0, -1, -1, "对范围内所有敌人造成4秒寒冷和相当于攻击力200%的法术伤害；蓄力|技力达到上限可继续回复，回复至上限2倍时进入蓄力状态，此时开启技能会触发额外效果（任何时候开启均消耗全部技力）：额外造成一层寒冷"),
                    new Skill("技能3", "失温症", "自动回复", "手动触发", 40, 13.0D, DurationKind.TIMED, 25, -1, -1, "攻击速度+130，同时攻击2个敌人；范围内所有敌人的冻结延长至技能结束，且技能结束时对所有冻结的敌人造成600%的法术伤害并结束冻结；优先攻击未冻结的单位")
            },
            "y-6", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「攻击造成法术伤害」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "攻击造成法术伤害",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 傀儡师 —— 模板干员「结城理」（Makoto Yuki，6★）。
     *
     * <p>分支特性：受到致命伤时不撤退，切换成<替身>作战（替身阻挡数为0），持续20秒后自身再次替换<替身></p>
     */
    SPECIALIST_DOLLKEEPER(UnitClass.SPECIALIST, "傀儡师", "结城理",
            "Makoto Yuki", "6", "近战位",
            2, 2, 14, 1.20D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、输出、快速复活、治疗",
            "S.E.E.S.",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "俄耳甫斯的竖琴", "自动回复", "手动触发", 7, 0.0D, DurationKind.TIMED, 7, -1, -1, "被动：<替身>状态召唤俄耳甫斯，攻击对敌人造成攻击力280%的法术伤害，范围内有生命值低于50%的友方干员时，改为对其治疗相当于结城理攻击力60%的生命值；主动：立即切换为<替身>状态作战"),
                    new Skill("技能2", "塔纳托斯的囚锁", "自动回复", "手动触发", 12, 0.0D, DurationKind.TIMED, 7, -1, -1, "被动：<替身>状态召唤塔纳托斯，攻击对至多4名敌人造成攻击力250%的法术伤害并有35%概率恐惧敌人1.5秒，范围内恐惧中的敌人若生命值低于结城理攻击力的280%则立刻倒下；主动：立即切换为<替身>状态作战"),
                    new Skill("技能3", "开辟明日的剑刃", "自动回复", "手动触发", 15, 0.0D, DurationKind.TIMED, 10, -1, -1, "被动：<替身>状态初始召唤塔纳托斯，攻击速度+60，攻击对至多4名敌人造成攻击力230%的弱点伤害，塔纳托斯在场时，开启技能或受到致命伤时改为召唤俄耳甫斯，阻挡数+2，使范围内友方干员获得40%的物理与法术闪避并每秒治疗范围内最多4名友方干员相当于结城理攻击力35%的生命值，直到<替身>状态结束；主动：立即切换为<替身>状态作战")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「致命伤时切换替身作战」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "受到致命伤时不撤退，切换成<替身>作战（替身阻挡数为0），持续20秒后自身再次替换<替身>",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 巡空者 —— 模板干员「予愿安洁莉娜」（Angelina the Mellow Wish，6★）。
     *
     * <p>分支特性：起飞后能够阻挡2个飞行敌人</p>
     */
    SPECIALIST_SKYRANGER(UnitClass.SPECIALIST, "巡空者", "予愿安洁莉娜",
            "Angelina the Mellow Wish", "6", "近战位",
            2, 2, 16, 1.50D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、高空、输出、控场",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "极速送达", "被动", "被动", 0, 60.0D, DurationKind.PASSIVE, 0, -1, -1, "部署后立刻起飞，攻击范围扩大，攻击力+135%，同时攻击两个目标"),
                    new Skill("技能2", "重力自定义", "自动回复", "手动触发", 25, 22.0D, DurationKind.TIMED, 21, -1, -1, "攻击范围扩大并向前方滑翔起飞，使掠过的攻击范围内的地面敌人浮空12秒、飞行敌人缚地22秒，随后攻击间隔较大幅缩短，攻击力+165%，攻击变为法术伤害且同时攻击5个目标"),
                    new Skill("技能3", "酸橙的心事", "自动回复", "手动触发", 30, 0.0D, DurationKind.AMMO, 24, 33, 1, "立刻起飞，攻击力+30%，获得65%的对空庇护，攻击范围扩大，且周围8格视作予愿安洁莉娜的额外攻击范围，攻击对3个敌人造成攻击力380%的物理伤害且额外攻击1个飞行敌人；范围内飞行敌人移动速度-60%，原技能范围内有未被阻挡的可阻挡飞行敌人，且自身未阻挡时，移动至该敌人所在格；攻击装有33发弹药，打完后技能结束（期间可随时停止技能）")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
            },
            "2-2", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「起飞后能够阻挡 2 个飞行敌人」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "起飞后能够阻挡2个飞行敌人",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 陷阱师 —— 模板干员「望」（Wang，6★）。
     *
     * <p>分支特性：可以使用陷阱来协助作战，但陷阱无法放置于敌人已在的格子中</p>
     */
    SPECIALIST_TRAPMASTER(UnitClass.SPECIALIST, "陷阱师", "望",
            "Wang", "6", "远程位",
            1, 1, 10, 0.85D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、召唤、输出",
            "炎、炎-岁",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "取势", "自动回复", "自动触发", 16, 0.0D, DurationKind.INSTANT, 0, -1, -1, "被动效果：棋子触发时使目标敌人停顿且每秒受到相当于望攻击力的135%的法术伤害，持续6.5秒；主动效果：立即获得两枚棋子"),
                    new Skill("技能2", "连星", "自动回复", "自动触发", 15, 0.0D, DurationKind.INSTANT, 0, -1, -1, "被动效果：棋子触发时，对连线方向上两侧3格范围内的敌人造成相当于攻击力的580%的法术伤害，并使其6秒内移动速度降低50%；主动效果：立即获得两枚棋子"),
                    new Skill("技能3", "天下劫", "自动回复", "手动触发", 50, 0.0D, DurationKind.AMMO, 38, 20, 1, "被动效果：棋子的触发和伤害范围扩大，造成相当于攻击力380%的法术伤害；主动效果：停止攻击但攻击范围扩大；立即获得8枚棋子，然后将超出上限的棋子优先部署在范围内敌人所在位置；在攻击范围内手动部署棋子时可部署至敌人所在位置，且第一天赋额外至多部署3枚棋子并消耗等量弹药；装有20发弹药，手动停止或棋子耗尽后技能结束，剩余的弹药返还为棋子")   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
            },
            "3-3", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「可以使用陷阱协助作战」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可以使用陷阱来协助作战，但陷阱无法放置于敌人已在的格子中",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 怪杰 —— 模板干员「新约能天使」（Exusiai the New Covenant，6★）。
     *
     * <p>分支特性：自身生命会不断流失</p>
     */
    SPECIALIST_GEEK(UnitClass.SPECIALIST, "怪杰", "新约能天使",
            "Exusiai the New Covenant", "6", "远程位",
            1, 1, 11, 1.30D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、支援",
            "炎-龙门、企鹅物流",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "天空大扫除", "自动回复", "自动触发", 12, 0.0D, DurationKind.AMMO, 8, 8, 1, "每次攻击造成攻击力250%的物理伤害，优先攻击空中单位，主动关闭时发射剩余所有弹药攻击范围内的随机敌人；攻击装有8发弹药，打完后结束（可随时停止技能）"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能2", "开火成瘾症", "自动回复", "手动触发", 30, 0.0D, DurationKind.AMMO, 25, 35, 1, "立即偷取攻击范围内1名友方干员70点攻击速度（持续至技能结束或新约能天使离场），自身与其获得最大生命值250%的屏障，该屏障会持续衰减，攻击间隔降低，每次攻击造成攻击力300%的物理伤害；攻击装有35发弹药，打完后结束（可随时停止技能），如果成功偷取攻击速度则额外获得5发弹药"),   // 每击消耗「暂定 1」：原文里没有「每次攻击消耗N发」
                    new Skill("技能3", "使命必达！", "自动回复", "手动触发", 35, 0.0D, DurationKind.AMMO, 30, 50, 5, "攻击力+30%，每次攻击变为相当于攻击力160%的5连击，若存在投递坐标，立即对该处造成一次相当于攻击力250%的物理溅射伤害并将一名再部署时间最长的地面干员部署至该处，使其获得6点技力；部署后获得投递坐标，攻击装有50发弹药，每次攻击消耗5发，打完后结束（可随时停止技能）")
            },
            "3-3", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「自身生命会不断流失」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "自身生命会不断流失",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 炼金师 —— 模板干员「引星棘刺」（Thorns the Lodestar，6★）。
     *
     * <p>分支特性：可以投掷炼金单元协助作战</p>
     */
    SPECIALIST_ALCHEMIST(UnitClass.SPECIALIST, "炼金师", "引星棘刺",
            "Thorns the Lodestar", "6", "远程位",
            1, 1, 14, 1.50D, 70,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "远程位、输出、支援、削弱",
            "伊比利亚",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "度算浪波", "自动回复", "自动触发", 5, 0.0D, DurationKind.INSTANT, 5, -1, -1, "向友方单位投掷一个炼金单元，在8秒内使落点和周围8格的友方单位防御力+100，每秒回复相当于攻击力20%的生命值"),
                    new Skill("技能2", "解构涌潮", "自动回复", "手动触发", 20, 0.0D, DurationKind.INSTANT, 14, -1, -1, "投掷一个炼金单元，在15秒内使落点周围的地面敌人受到的治疗和回复效果降低50%，每秒受到相当于攻击力180%的法术伤害，友方单位每秒恢复相当于攻击力18%的生命；炼金单元能在15秒内缓慢沿投掷方向移动且影响范围持续扩大；可充能2次"),
                    new Skill("技能3", "“我的海疆”", "自动回复", "手动触发", 60, 0.0D, DurationKind.INSTANT, 48, -1, -1, "被动效果：攻击范围扩大；主动开启：向阻挡数最低的4名干员投掷炼金单元，在23秒内使围绕区域内的敌人攻击力-15%，防御力-35%，法术抗性-35%（不叠加），每秒受到攻击力210%的法术伤害，效果逐渐提升（15秒后达到最大，攻击力-30%，防御力-50%，法术抗性-50%，每秒伤害390%）")
            },
            "3-5", true,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「可以投掷炼金单元协助作战」，没有索敌偏好",
            AttackMethod.PROJECTILE,
            "表格「部署位」= 远程位 → 发射投掷物",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "可以投掷炼金单元协助作战",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 处决者 —— 模板干员「弑君者」（Crownslayer，6★）。
     *
     * <p>分支特性：再部署时间大幅度减少</p>
     */
    SPECIALIST_EXECUTOR(UnitClass.SPECIALIST, "处决者", "弑君者",
            "Crownslayer", "6", "近战位",
            1, 1, 10, 0.93D, 22,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、快速复活",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "尘烟蔽目", "初始触发", "被动", 0, 10.0D, DurationKind.PASSIVE, 0, -1, -1, "部署后攻击力+100%，并获得50%物理和法术闪避"),
                    new Skill("技能2", "硝烟震爆", "初始触发", "被动", 0, 0.0D, DurationKind.PASSIVE, 0, -1, -1, "部署后8秒内停止攻击，自身不容易成为敌人的攻击目标，第一天赋使地面敌人物理与法术命中率降低的效果提高至2.5倍，并在技能结束时对烟雾内的所有地面敌人造成相当于攻击力500%的物理伤害"),
                    new Skill("技能3", "烽烟行刑场", "初始触发", "被动", 0, 16.0D, DurationKind.PASSIVE, 0, -1, -1, "部署后第一天赋的生效范围扩大，弑君者消失在烟雾中，获得隐匿且阻挡数变为0，每2秒现身对烟雾内的一名地面敌人造成两次相当于攻击力250%的物理伤害并使目标晕眩4秒（对同一目标6秒内只会触发一次）")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「再部署时间大幅度减少」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "再部署时间大幅度减少",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 伏击客 —— 模板干员「阿斯卡纶」（Ascalon，6★）。
     *
     * <p>分支特性：对攻击范围内所有敌人造成伤害；拥有50%的物理和法术闪避且不容易成为敌人的攻击目标</p>
     * <p>模组后：攻击范围内所有敌人移动速度-20%</p>
     */
    SPECIALIST_AMBUSHER(UnitClass.SPECIALIST, "伏击客", "阿斯卡纶",
            "Ascalon", "6", "近战位",
            0, 0, 19, 3.50D, 70,   // 常态阻挡 0 ← PRTS 数据字段查询 [[阻挡数::0]] → 7 位干员全是伏击客（本工程表里也是 0）
            "近战位、减速、输出",
            "罗德岛、S.W.E.E.P.",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "追袭", "自动回复", "自动触发", 7, 0.0D, DurationKind.INSTANT, 4, -1, -1, "下次攻击的攻击力提升至210%，并连续攻击两次；可充能3次"),
                    new Skill("技能2", "恩赐", "自动回复", "手动触发", 20, 35.0D, DurationKind.TIMED, 15, -1, -1, "攻击力+130%，使攻击范围内所有敌人移动速度-60%，并在其被击倒时对周围地面敌人施加一层第一天赋效果"),
                    new Skill("技能3", "降临", "自动回复", "手动触发", 45, 45.0D, DurationKind.TIMED, 35, -1, -1, "攻击范围扩大，攻击力+50%，攻击间隔较大幅度缩短，使攻击范围内的地面敌人物理与法术命中率-50%。自身更易受到敌人攻击，敌人未命中自身或自身闪避时回复8%最大生命值")
            },
            "y-1", false,
            TargetPriority.NEAREST, TargetCount.MULTI_ALL, true,
            "对攻击范围内所有敌人造成伤害",
            AttackMethod.PROJECTILE,
            "近战位但有远程攻击：设计指定：攻击范围超出自身格 + 对范围内所有敌人造成伤害",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "对攻击范围内所有敌人造成伤害；拥有50%的物理和法术闪避且不容易成为敌人的攻击目标",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 行商 —— 模板干员「琳琅诗怀雅」（Swire the Elegant Wit，6★）。
     *
     * <p>分支特性：再部署时间减少，撤退时不返还部署费用，在场时每3秒消耗3点部署费用（不足时自动撤退）</p>
     * <p>模组后：再部署时间减少，撤退时不返还部署费用，在场时每3秒消耗2点部署费用（不足时自动撤退）</p>
     */
    SPECIALIST_MERCHANT(UnitClass.SPECIALIST, "行商", "琳琅诗怀雅",
            "Swire the Elegant Wit", "6", "近战位",
            1, 1, 7, 1.00D, 25,   // 常态阻挡 1 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、快速复活、爆发",
            "炎-龙门",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "仗义疏财", "初始触发", "被动", 0, 0.0D, DurationKind.PASSIVE, 0, -1, -1, "消耗一枚金币，下一次攻击会为周围八格内血量不足70%的一名友方单位恢复相当于攻击力80%的生命；携带此技能时金币上限为3"),
                    new Skill("技能2", "“见面礼”", "初始触发", "被动", 0, 0.0D, DurationKind.PASSIVE, 0, -1, -1, "消耗一枚金币在范围内一个可放置且可通行的地面放置香槟炸弹，香槟炸弹会对触碰到的首个敌人造成相当于攻击力200%的物理伤害，并使目标停顿2秒；香槟炸弹在场3秒后可额外造成一次伤害；携带此技能时金币上限为5"),
                    new Skill("技能3", "千金一掷", "自动回复", "自动触发", 5, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击变为二连击，击倒敌人时获得一枚金币；主动关闭技能时消耗所有金币对前方范围内敌人随机攻击，每消耗一枚金币造成一次相当于攻击力150%的物理伤害，并将目标中等力度地向前推开；持续时间无限，可随时主动关闭技能；携带此技能时金币上限为10")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.SINGLE, true,
            "表里只说「再部署时间减少 + 消耗部署费用」，没有索敌偏好",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "再部署时间减少，撤退时不返还部署费用，在场时每3秒消耗3点部署费用（不足时自动撤退）",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 钩索师 —— 模板干员「歌蕾蒂娅」（Gladiia，6★）。
     *
     * <p>分支特性：技能可以使敌人产生位移；</p>
     */
    SPECIALIST_HOOKMASTER(UnitClass.SPECIALIST, "钩索师", "歌蕾蒂娅",
            "Gladiia", "6", "近战位",
            2, 2, 14, 1.80D, 80,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、位移、输出、控场",
            "阿戈尔、深海猎人",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "缺水的大洋裂断", "自动回复", "自动触发", 4, 0.0D, DurationKind.INSTANT, 0, -1, -1, "下次攻击会将目标较大力度地拖拽至面前，并对其造成相当于攻击力210%的物理伤害"),
                    new Skill("技能2", "缺水的掌握怒海", "自动回复", "手动触发", 25, 20.0D, DurationKind.TIMED, 20, -1, -1, "攻击间隔增大，攻击范围扩大，每次攻击优先对被阻挡的两个目标造成相当于攻击力180%的物理伤害且以较大力度地拖拽至自己面前"),
                    new Skill("技能3", "缺水的碎漩狂舞", "自动回复", "手动触发", 35, 8.0D, DurationKind.TIMED, 26, -1, -1, "对一个远处目标束缚并制造一个龙卷风使周围敌人移动速度-50%，每1.5秒造成130%攻击力的法术伤害并较大力度地拖拽至中心。技能结束时把目标地点周围的敌人较大力度地拖拽至面前")
            },
            "3-2", true,
            TargetPriority.BLOCKED_FIRST, TargetCount.SINGLE, true,
            "《分支特性信息》：「普通攻击可以且优先攻击自身阻挡的单位（即使目标处于攻击范围外）」",
            AttackMethod.PROJECTILE,
            "近战位但有远程攻击：设计指定：攻击范围超出自身格（远程钩索）",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "技能可以使敌人产生位移；",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽"),

    /**
     * 特种 · 推击手 —— 模板干员「温蒂」（Weedy，6★）。
     *
     * <p>分支特性：同时攻击阻挡的所有敌人；</p>
     * <p>模组后：同时攻击阻挡的所有敌人,可以放置于远程位，并返还该次部署费用的一半</p>
     */
    SPECIALIST_PUSH_STROKER(UnitClass.SPECIALIST, "推击手", "温蒂",
            "Weedy", "6", "近战位",
            2, 2, 19, 1.20D, 70,   // 常态阻挡 2 ← 表里的「阻挡数」列（该分支常态值与技能期值相同，未列入常态阻挡覆盖表）
            "近战位、位移、输出、控场",
            "罗德岛",
            Talent.empty(),   // 天赋1：★ 设计口径「天赋不填」⇒ 槽全空
            Talent.empty(),   // 天赋2：同上（参考原文见 生成链参考表）
            new Skill[] {
                    new Skill("技能1", "炮管敲击", "自动回复", "自动触发", 5, 0.0D, DurationKind.TIMED, 0, -1, -1, "下次攻击会把目标往攻击方向较大力地推开，造成相当于攻击力150%的物理伤害并令其晕眩1.2秒"),
                    new Skill("技能2", "水炮模式", "自动回复", "自动触发", 70, 0.0D, DurationKind.INFINITE, 0, -1, -1, "攻击间隔增大，攻击力+200%，攻击距离+2格且切换为远程群体攻击，且会把击中目标中等力度地推开；持续时间无限"),
                    new Skill("技能3", "液氮大炮", "自动回复", "手动触发", 33, 0.0D, DurationKind.INSTANT, 20, -1, -1, "立即发射一个压缩液氮炮，造成相当于攻击力350%的群体法术伤害并将敌人大力地推开，令其8秒内移动时受到正比于距离的真实伤害；如果蓄水炮在周围4格内的话也会同样进行发射")
            },
            "1-1", false,
            TargetPriority.NEAREST, TargetCount.MULTI_BLOCKED, true,
            "同时攻击阻挡的所有敌人；",
            AttackMethod.MELEE,
            "近战位且无远程攻击 → 近战武器攻击",
            BlockSearch.FOOT,
            "默认：只找棋子脚下那一格（设计口径）",
            Aoe.none(), 1, 1.0D,
            "无",
            Heal.of(Heal.Kind.NONE, 1, 1.0D, 1.0D, "", 0.0D, 0.0D, 20),
            "无",
            Pause.of(Pause.Kind.NONE, 0),
            "无",
            true,
            "（未列入禁疗名单 ⇒ 可被友方治疗）",
            "同时攻击阻挡的所有敌人；",
            DamageMod.of(DamageMod.Kind.NONE, 1.0D, false),
            "无",
            ModuleEffect.empty(),   // 模组槽：★ 待定（同天赋，见 data/ModuleEffect.java）
            "待定：模组系统未做 —— 设计口径「模组只需要像天赋一样留一个待定槽即可」。已查证的 5 条模组乘区留在 生成链 的 MODULE_DAMAGE_MODS_PENDING，108 条模组特性原文留在 PRTS 对照报告 附录 A，等干员导入 / 模组表填进这个槽");

    /**
     * 一个战斗技能的数据（**最高等级**口径）。
     *
     * <p><b>数值来源</b>：《明日方舟模板干员技能表.xlsx》的「最高等级」列 ——
     * 表里写成 {@code 基础→最高} 的列（消耗技力 / 初始技力 / 持续时间(秒)）一律取
     * {@code →} <b>后面</b>那个值；纯数字就用它本身；空 → 0。</p>
     *
     * <p><b>技力机制出处</b>：PRTS「技能」页
     * <a href="https://prts.wiki/w/技能">https://prts.wiki/w/技能</a>
     * （技力需求 / 触发条件 / 特殊属性三节）：技力上限 = 该技能的消耗技力、
     * 自动回复每 20 tick（=1 秒）+1、攻击回复每次出手 +1、受击回复每次被攻击 +1、
     * 激活期间阻回（不回技力）、装有 X 发弹药打完后结束。</p>
     *
     * <p><b>弹药两个字段</b>：{@code ammo} = 效果文本里<b>最后一个</b>
     * {@code 装有(\d+)发弹药}（弹药型 18 条全部提得到；提不出来生成链会报错退出，
     * 绝不静默兜底）；{@code ammoPerAttack} = {@code 每次攻击消耗(\d+)发}，
     * <b>取不到就按 1 并标「暂定」</b>（生成物里那几行带行尾注释）。
     * 非弹药型这两个字段都是 {@code -1}（不适用）。</p>
     *
     * <p><b>★ 一条真例外（按时间消耗弹药）</b>：神射手技能3「礼炮·强制追思」的原文是
     * 「每0.5秒消耗1发弹药」——那是<b>按时间</b>消耗，而本 mod 只有「每次出手消耗 N 发」
     * 这一条判据（不做时间驱动的弹药路径，见 设计文档），所以对它只是近似：
     * <b>暂定：原作按时间消耗，本 mod 按出手消耗</b>。生成链每次都会把这条打印出来，
     * 生成物里那一行也带同样意思的行尾注释 —— 偏差看得见，不静默。</p>
     *
     * <p><b>★ {@code durationSeconds} 是 {@code double}</b>（不是 int）：表里有一条
     * <b>4.2 秒</b>（重射手技能2「飞翔瞪射」）。按 int 会静默变成 4 秒 = 80 tick
     * （少了 4 tick），而且生成物与核对脚本会一起读成 4 秒、全绿看不出差别。
     * {@link #durationTicks()} 因此是 {@code (int) Math.round(秒 × 20)}。</p>
     *
     * <p>{@code recovery} 保留**表里原值**（自动回复 / 攻击回复 / 初始触发 /
     * 受击回复 / 被动）—— 这样「初始触发」与「被动」这两种被动型还分得开；
     * 语义档看 {@link #recoveryKind()}。{@code durationKind} 已经把
     * 「(可主动关闭)」两种降级成对应档（卫戍协议活动里没有手动入口，设计口径）。</p>
     */
    public record Skill(String slot, String name, String recovery, String trigger,
                        int cost, double durationSeconds, DurationKind durationKind,
                        int initialSp, int ammo, int ammoPerAttack, String effect) {

        /**
         * 表里**真有**这条技能。
         *
         * <p>表里有 4 个分支只有 2 条技能（近卫·佣兵 / 近卫·本源近卫 / 狙击·裂空炮手 /
         * 辅助·游击手），生成器把它们补成第 3 个空条目 —— 靠这个方法区分。
         * 槽位校验（2 技能分支的技能位 3 非法）与技能数统计都读它。</p>
         */
        public boolean present() {
            return !recovery.isEmpty();
        }

        /**
         * 回复类型 → {@link RecoveryKind}。
         *
         * <p>「初始触发」与「被动」都归 {@link RecoveryKind#PASSIVE}；
         * 剩下的写法（存档/表格写坏）一律按被动兜底，不抛异常。</p>
         */
        public RecoveryKind recoveryKind() {
            switch (recovery) {
                case "自动回复":
                    return RecoveryKind.AUTO;
                case "攻击回复":
                    return RecoveryKind.ON_ATTACK;
                case "受击回复":
                    return RecoveryKind.ON_HURT;
                default:
                    return RecoveryKind.PASSIVE;
            }
        }

        /**
         * 持续时间换算成 tick（**20 tick = 1 秒**，与
         * {@link UnitBranch#attackIntervalTicks()} 同一口径）。
         *
         * <p><b>★ 必须四舍五入，不能截断</b>：{@code durationSeconds} 是 double，
         * 表里有 4.2 秒这一条（重射手技能2「飞翔瞪射」）—— 4.2 × 20 = 84 tick；
         * 写成 int 会把 4.2 静默变成 4 秒 = 80 tick（少 4 tick），
         * 而生成物与四个核对会**一起**读错，全绿看不出差别。</p>
         *
         * <p>{@link DurationKind#INSTANT} / {@link DurationKind#PASSIVE} /
         * {@link DurationKind#INFINITE} 都不是「按时间结束」，返回 0 ——
         * 它们的结束条件分别是「放完就结束」「永不触发」「永不结束」。</p>
         */
        public int durationTicks() {
            return durationKind == DurationKind.TIMED
                    ? (int) Math.round(durationSeconds * 20.0D) : 0;
        }
    }

    /**
     * 技能回复类型：技力（SP）怎么涨（表里 5 种写法归成 4 档）。
     *
     * <p>速率与上限全部出自 PRTS「技能」页（链接见 {@link Skill} 的注释）：
     * 上限 = 该技能的消耗技力；激活期间阻回（不回技力）。</p>
     */
    public enum RecoveryKind {
        /** 自动回复：每 20 tick（= 1 秒）+1 点技力。 */
        AUTO,
        /** 攻击回复：每次出手 +1 点（连击 = 一次出手，只回 1 点）。 */
        ON_ATTACK,
        /** 受击回复：每次被攻击 +1 点。 */
        ON_HURT,
        /**
         * 被动（含表里那 8 条「初始触发」）：不进技力循环、永不触发。
         *
         * <p>PRTS：「被动：技能直接生效，无需触发、释放」。本轮只做「技力 → 触发」这一条链，
         * 所以这 9 条只有状态、没有行为（见 设计文档）。</p>
         */
        PASSIVE
    }

    /**
     * 技能持续时间类型：激活之后**怎么结束**。
     *
     * <p>表里 7 种写法里的「(可主动关闭)」两种**降级**成对应档：
     * 卫戍协议活动里没有手动释放/手动关闭（设计口径 明确），
     * 所以永续照旧永续、有限持续到时结束。</p>
     */
    public enum DurationKind {
        /** 瞬发：不进激活期，放完立刻结束。 */
        INSTANT,
        /** 有限持续：{@code durationSeconds} × 20 tick 之后结束（四舍五入）。 */
        TIMED,
        /** 永续：激活之后不按时间结束。 */
        INFINITE,
        /** 弹药：不按时间递减，由**出手**把弹量打到 ≤ 0 之后退出技能。 */
        AMMO,
        /** 被动(常驻生效)：不进技力循环、永不触发。 */
        PASSIVE
    }

    private final UnitClass unitClass;
    private final String branchName;
    private final String templateOperator;
    private final String templateOperatorEn;
    private final String rarity;
    private final String deployPosition;
    private final int blockCount;
    private final int normalBlockCount;
    private final int deployCost;
    private final double attackIntervalSeconds;
    private final int redeploySeconds;
    private final String tags;
    private final String faction;
    private final Talent talent1;
    private final Talent talent2;
    private final Skill[] skills;
    private final String attackRangeKey;
    private final boolean antiAir;
    private final TargetPriority targetPriority;
    private final TargetCount targetCount;
    private final boolean canAttack;
    private final String targetRuleSource;
    private final AttackMethod attackMethod;
    private final String attackMethodSource;
    private final BlockSearch blockSearch;
    private final String blockSearchSource;
    private final Aoe aoe;
    private final int attackCount;
    private final double tailFactor;
    private final String aoeSource;
    private final Heal heal;
    private final String healSource;
    private final Pause pause;
    private final String pauseSource;
    private final boolean healable;
    private final String healableSource;
    private final String traitText;
    private final DamageMod damageMod;
    private final String damageModSource;
    private final ModuleEffect module;
    private final String moduleSource;

    UnitBranch(UnitClass unitClass, String branchName, String templateOperator,
               String templateOperatorEn, String rarity, String deployPosition,
               int blockCount, int normalBlockCount, int deployCost,
               double attackIntervalSeconds,
               int redeploySeconds, String tags, String faction,
               Talent talent1, Talent talent2,
               Skill[] skills, String attackRangeKey, boolean antiAir,
               TargetPriority targetPriority, TargetCount targetCount,
               boolean canAttack, String targetRuleSource,
               AttackMethod attackMethod, String attackMethodSource,
               BlockSearch blockSearch, String blockSearchSource,
               Aoe aoe, int attackCount, double tailFactor, String aoeSource,
               Heal heal, String healSource,
               Pause pause, String pauseSource,
               boolean healable, String healableSource,
               String traitText,
               DamageMod damageMod, String damageModSource,
               ModuleEffect module, String moduleSource) {
        this.unitClass = unitClass;
        this.branchName = branchName;
        this.templateOperator = templateOperator;
        this.templateOperatorEn = templateOperatorEn;
        this.rarity = rarity;
        this.deployPosition = deployPosition;
        this.blockCount = blockCount;
        this.normalBlockCount = normalBlockCount;
        this.deployCost = deployCost;
        this.attackIntervalSeconds = attackIntervalSeconds;
        this.redeploySeconds = redeploySeconds;
        this.tags = tags;
        this.faction = faction;
        this.talent1 = talent1;
        this.talent2 = talent2;
        this.skills = skills;
        this.attackRangeKey = attackRangeKey;
        this.antiAir = antiAir;
        this.targetPriority = targetPriority;
        this.targetCount = targetCount;
        this.canAttack = canAttack;
        this.targetRuleSource = targetRuleSource;
        this.attackMethod = attackMethod;
        this.attackMethodSource = attackMethodSource;
        this.blockSearch = blockSearch;
        this.blockSearchSource = blockSearchSource;
        this.aoe = aoe;
        this.attackCount = attackCount;
        this.tailFactor = tailFactor;
        this.aoeSource = aoeSource;
        this.heal = heal;
        this.healSource = healSource;
        this.pause = pause;
        this.pauseSource = pauseSource;
        this.healable = healable;
        this.healableSource = healableSource;
        this.traitText = traitText;
        this.damageMod = damageMod;
        this.module = module;
        this.moduleSource = moduleSource;
        this.damageModSource = damageModSource;
    }

    public UnitClass unitClass() { return unitClass; }
    public String branchName() { return branchName; }
    public String templateOperator() { return templateOperator; }
    public String templateOperatorEn() { return templateOperatorEn; }
    public String rarity() { return rarity; }
    public String deployPosition() { return deployPosition; }
    /**
     * 阻挡数：能同时拦住的敌人数。<b>0 = 不阻挡</b>。
     *
     * <p><b>★ 这一列是「技能期 / 显示值」，不是常态</b>（2026-10 设计实机发现）：对照表这一格填的是
     * 技能期（或界面显示）的阻挡数 —— 解放者的特性原文写着「通常不攻击且阻挡数为0」，表里这格却是 3。
     * 所以「这颗棋子平时挡不挡路」<b>一律看 {@link #normalBlockCount()}</b>，不要拿本方法去判碰撞箱与阻挡名额。</p>
     *
     * <p>本方法仍有它的用处：<b>原文里就写着「阻挡数」的那几条机制</b>（如收割者「最大生效数等于阻挡数」）
     * 读的就是这个数 —— 那是技能期口径，与常态口径是两件事。</p>
     */
    public int blockCount() { return blockCount; }

    /**
     * <b>常态阻挡数</b>：这个分支<b>平时</b>能拦住几个敌人（= 怪能不能从它身上走过去）。
     *
     * <p>数值来源：生成链 的 {@code normal_block} ——
     * <b>默认就是表里的 {@link #blockCount()}</b>（71 个分支的技能期值与常态值相同），只有
     * {@code NORMAL_BLOCK_OVERRIDES} 里列出的分支用覆盖值。当前覆盖表 2 条：</p>
     * <ul>
     *     <li><b>近卫 · 解放者</b>：表里 3 ⇒ 常态 <b>0</b>。依据 PRTS 特性原文「通常不攻击且阻挡数为0」
     *         （4 位解放者的特性全是这句），PRTS 的「阻挡数」字段同样记成 2→2→3，也是技能期值；</li>
     *     <li><b>特种 · 伏击客</b>：表里本来就是 0（数据字段与常态一致），显式登记成权威名单的一条。</li>
     * </ul>
     *
     * <p>三处反例（<b>都不是</b>常态 0）：执旗手「技能发动期间阻挡数变为0」、
     * 傀儡师「替身阻挡数为0」、要塞「<b>不阻挡敌人时</b>优先远程群体物理攻击」（条件句，照样挡）。</p>
     *
     * <p><b>谁读它（两处，必须一致）</b>：{@code PixelUnit#canBeCollidedWith()}（0 ⇒ 去掉碰撞箱）
     * 与 {@code PawnCombatManager#updateBlocking} 的容量（0 ⇒ 一个名额都不占）。</p>
     */
    public int normalBlockCount() { return normalBlockCount; }
    /** 部署费用。 */
    public int deployCost() { return deployCost; }
    /** 攻击间隔（秒）。0 表示表格未给（该分支不参与自动攻击）。 */
    public double attackIntervalSeconds() { return attackIntervalSeconds; }
    /** 攻击间隔换算成 tick（20 tick = 1 秒）。 */
    public int attackIntervalTicks() {
        return attackIntervalSeconds <= 0.0D ? 0
                : (int) Math.round(attackIntervalSeconds * 20.0D);
    }
    public int redeploySeconds() { return redeploySeconds; }
    public String tags() { return tags; }
    public String faction() { return faction; }
    /**
     * 天赋 1（**只解析、不执行**，而且**现在是空的** —— 设计口径
     * 「天赋不填，目前应该是 72 个分支的天赋槽全空」⇒ 一律 {@link Talent#empty()}，
     * 等干员导入脚本按玩家的导入表填。参考表里那些原文见 生成链参考表）。
     */
    public Talent talent1() { return talent1; }
    /**
     * 天赋 2（同上；现在也是空的 —— 两个槽都留着等导入表）。
     */
    public Talent talent2() { return talent2; }
    /** 该分支的 3 个战斗技能（技能1/2/3）。 */
    public Skill[] skills() { return skills.clone(); }
    public Skill skill(int index) {
        return index >= 0 && index < skills.length ? skills[index] : null;
    }
    /** 攻击范围形状键，交给 AttackRange 解析成具体格子。 */
    public String attackRangeKey() { return attackRangeKey; }

    /**
     * 是否有「对空」能力。
     *
     * <p><b>判据（2026-10 第四轮改）</b>：远程位 ⇒ 对空（表格「部署位」列那条规则）；
     * 近战位 ⇒ 默认不对空，<b>除非</b>登记在 生成链 的
     * {@code ANTI_AIR_OVERRIDES} —— 目前 5 条，每条带 PRTS 原文：
     * <b>情报官 / 领主 / 哨戒铁卫 / 巡空者 / 钩索师</b>。
     * 旧口径是「远程位才对空」，把这 5 个近战位分支判成打不到空中单位，与原文相反。</p>
     *
     * <p>它只影响<b>垂直判定</b>，见 {@link #verticalTargeting()}。</p>
     */
    public boolean antiAir() { return antiAir; }

    /**
     * 垂直判定模式：决定这个分支能不能打到「比自己高/低」的敌人。
     * <p>攻击范围是<b>二维</b>的（相对格子的 x/z），所以必须单独规定垂直口径，
     * 否则站在方块顶上（只高出 2 格）的敌人会因为水平坐标落在范围内而被打到 ——
     * 这正是「上方盲区」要表达的东西。</p>
     *
     * <ul>
     *     <li>{@link VerticalTargeting#RANGED}：远程位。<b>不检查高度</b>，天然对空；</li>
     *     <li>{@link VerticalTargeting#AIR_AND_GROUND}：有对空能力，撤销垂直盲区；</li>
     *     <li>{@link VerticalTargeting#GROUND_ONLY}：对地，保留上方盲区。</li>
     * </ul>
     */
    public VerticalTargeting verticalTargeting() {
        if (!isMelee()) {
            return VerticalTargeting.RANGED;
        }
        return antiAir ? VerticalTargeting.AIR_AND_GROUND
                : VerticalTargeting.GROUND_ONLY;
    }

    /** 垂直判定模式。 */
    public enum VerticalTargeting {
        /** 远程位：不检查高度。 */
        RANGED,
        /** 有对空：撤销垂直盲区。 */
        AIR_AND_GROUND,
        /** 对地：保留上方盲区，只取消下方盲区。 */
        GROUND_ONLY
    }

    /** 是否是近战位分支（部署在近战位）。 */
    public boolean isMelee() {
        return deployPosition != null && deployPosition.contains("近战");
    }

    // ------------------------------------------------------------------
    // 索敌机制（打谁 + 一次打几个）
    // ------------------------------------------------------------------

    /**
     * 目标优先级：在攻击范围内**先打谁**。
     *
     * <p>来源是《明日方舟职业机制与职业分支对照表》的「分支机制（干员特性原文）」一列。
     * 表里没写「优先攻击 X」的分支一律 {@link #NEAREST}（离棋子最近），
     * 也就是本工程原来的唯一口径 —— 所以这次改动对它们是**零行为变化**。</p>
     */
    public enum TargetPriority {
        /** 离棋子最近的敌人（表里没有偏好时的常规口径）。 */
        NEAREST,
        /** 物理防御力最低的敌人（狙击 · 神射手）。 */
        LOWEST_ARMOR,
        /** 重量最重的敌人（狙击 · 攻城手）。 */
        HEAVIEST,
        /** 空中单位优先，没有空中目标时才打地面（狙击 · 速射手）。 */
        AIR_FIRST,
        /** 只打空中单位，地面目标一律无视（狙击 · 裂空炮手「起飞后只攻击空中敌人」）。 */
        AIR_ONLY,
        /**
         * **优先打「我正挡住的」敌人**（N2，2026-10 第五轮）。
         *
         * <p>依据：PRTS《分支特性信息》里 32 个分支的「可以且优先攻击自身阻挡的单位
         * （即使目标处于攻击范围外或为飞行单位）」。本工程对其中 27 个已经实现了**更强**的
         * 语义（{@code attacksBlockedOnly()}：只打被挡的），所以这一档只登记**缺口**那 3 个：
         * 领主 / 哨戒铁卫 / 钩索师（见 生成链的 BLOCKED_FIRST_OVERRIDES 覆盖表）。</p>
         *
         * <p>排序规则：被挡住的一律排前面，其后仍按距离 —— 是「优先」不是「只能」，
         * 范围内的其他敌人照样能打（那 27 个的「只能」由 attacksBlockedOnly 负责，两者正交）。</p>
         */
        BLOCKED_FIRST
    }

    /**
     * 目标数：一次攻击打在**几个**敌人身上。
     *
     * <p>只覆盖表里「同时攻击阻挡的所有敌人」「对攻击范围内所有敌人造成伤害」这两类。
     * 「攻击造成群体伤害」「在 3~4 个敌人间跳跃」属于**落点溅射 / 连锁**，
     * 不是一次选多个目标，留在以后的「群伤」分支做。</p>
     */
    public enum TargetCount {
        /** 单个目标（默认）。 */
        SINGLE,
        /** 同时打自己**阻挡住的**全部敌人（上限 = 阻挡数）。 */
        MULTI_BLOCKED,
        /** 同时打攻击范围内的**全部**敌人。 */
        MULTI_ALL
    }

    /** 目标优先级（打谁）。 */
    public TargetPriority targetPriority() { return targetPriority; }

    /** 一次攻击的目标数（打几个）。 */
    public TargetCount targetCount() { return targetCount; }

    /**
     * 这个分支会不会**主动攻击**。
     *
     * <p>表里明写「通常不攻击 / 不攻击」的三个分支（近卫·解放者、术师·阵法术师、
     * 辅助·吟游者）为 {@code false}：它们仍然会被摆上台、仍然按阻挡数拦人，
     * 只是不出手。原作里它们靠技能才输出，而技能系统还没做 —— 所以现在它们
     * 确实是「站桩」的，等技能系统上线再让技能期间放开攻击。</p>
     */
    public boolean canAttack() { return canAttack; }

    /**
     * 这条索敌规则的**依据**（一句话，注明出自表格原文的哪半句）。
     *
     * <p>三条轴的默认值都在这句话里交代了来源，核对脚本 生成链脚本
     * 会拿它和表格原文比对 —— 「我读错了原文」这类错误因此能被抓住。</p>
     */
    public String targetRuleSource() { return targetRuleSource; }

    // ------------------------------------------------------------------
    // 常态攻击方式（投掷物 / 近战武器）
    // ------------------------------------------------------------------

    /**
     * 常态攻击方式：这一分支平时怎么出手。
     *
     * <ul>
     *     <li>{@link #PROJECTILE}：发射投掷物（远程位分支，以及「近战位但有远程攻击」的
     *         情报官 / 领主 / 哨戒铁卫 / 要塞 / 伏击客 / 钩索师）；</li>
     *     <li>{@link #MELEE}：近战武器攻击（其余近战位分支）。</li>
     * </ul>
     *
     * <p><b>它与「近战位 / 远程位」不是同一件事</b>：近战位说的是<b>部署格子</b>
     * （影响阻挡与垂直判定），攻击方式是<b>出手形态</b>。那 6 个分支站在近战位、
     * 照样阻挡，但攻击打出去的是投掷物。</p>
     */
    public enum AttackMethod {
        /** 近战武器攻击：贴身挥砍，伤害即时结算。 */
        MELEE,
        /** 发射投掷物：无视重力、带轨迹，命中后结算伤害。 */
        PROJECTILE
    }

    /** 常态攻击方式（投掷物 / 近战武器）。 */
    public AttackMethod attackMethod() { return attackMethod; }

    /**
     * 攻击方式这条规则的**依据**（一句话，注明来自表格哪一列或设计指定）。
     *
     * <p>与 {@link #targetRuleSource()} 同一个用途：核对脚本
     * 生成链脚本 拿它和表格「部署位」「分支机制」两列比对，
     * 「我读错了原文」这类错误因此能被抓住。</p>
     */
    public String attackMethodSource() { return attackMethodSource; }

    // ------------------------------------------------------------------
    // 阻挡搜索范围（本 tick 在哪些格子里找「该被挡住的敌人」）
    // ------------------------------------------------------------------

    /**
     * 阻挡搜索范围：**本 tick 在哪些格子里找候选敌人**。
     *
     * <ul>
     *     <li>{@link #FOOT}（默认）：只有棋子**脚下那一格** —— 敌人得贴到棋子身上
     *         才算被挡住（设计口径）；</li>
     *     <li>{@link #SURROUNDING}：棋子所在格 + 周围一圈，共 <b>九格</b> ——
     *         目前只有医疗·守望者用（它的精二范围 y-6 本来就绕着自身一圈）。</li>
     * </ul>
     *
     * <p><b>它只影响「找谁」，不影响锚点</b>：被挡住之后走向哪里由
     * {@code combat/BlockAnchorStrategy} 决定（占位实现走正前方 1.3 格）。</p>
     */
    public enum BlockSearch {
        /** 只有棋子脚下那一格（默认）。 */
        FOOT,
        /** 棋子所在格 + 周围一圈（九格）。 */
        SURROUNDING
    }

    /** 阻挡搜索范围（找谁）。 */
    public BlockSearch blockSearch() { return blockSearch; }

    /** 这条阻挡搜索范围的依据（默认口径 / 设计指定）。 */
    public String blockSearchSource() { return blockSearchSource; }

    // ------------------------------------------------------------------
    // 群伤（落点溅射 / 连锁）—— 2026-10 第二轮
    // ------------------------------------------------------------------

    /**
     * 群伤规格：打中之后，伤害还会扩散到谁。
     *
     * <p>★ 它与 {@link #targetCount()} 是<b>两条不同的轴</b>：
     * targetCount 决定「这一手选中了谁」，本规格决定「打中之后还会波及谁」。
     * 一个分支可以只有前者（散射手）、只有后者（炮手）、或者两者都有。</p>
     *
     * <p>数值全部查证自外部来源（PRTS《溅射半径一览》与 EN 官方 wiki 分支页），
     * 每条的出处写在 {@link #aoeSource()} 里。<b>半径内一刀切，不随距离衰减</b>；
     * 能不能打空中按各自来源的备注。真实游戏里半径**可以按干员覆盖**
     * （同为扩散术师，Pith 0.9 / 格雷伊 1.0），这里给的是分支默认值。</p>
     */
    public record Aoe(Kind kind, double radius, double factor, boolean canHitAir,
                      int chainTargets, double chainDecay) {

        /** 群伤类型。 */
        public enum Kind {
            /** 没有群伤：伤害只落在被选中的目标身上。 */
            NONE,
            /** 落点溅射：以**命中点**为中心、{@code radius} 格内的敌人也吃伤害。 */
            SPLASH,
            /** 连锁：命中后依次跳到附近的敌人，每跳伤害乘以 {@code chainDecay}。 */
            CHAIN
        }

        /** 无群伤。 */
        public static Aoe none() {
            return new Aoe(Kind.NONE, 0.0D, 1.0D, true, 1, 1.0D);
        }

        /** 落点溅射：半径 radius 格、伤害系数 factor、能不能打空中。 */
        public static Aoe splash(double radius, double factor, boolean canHitAir) {
            return new Aoe(Kind.SPLASH, radius, factor, canHitAir, 1, 1.0D);
        }

        /**
         * 连锁：一共打中 {@code chainTargets} 个敌人（**含第一个**），
         * 每跳伤害 ×{@code chainDecay}，跳跃时在 {@code radius} 格内找下一个目标。
         */
        public static Aoe chain(int chainTargets, double chainDecay, double radius) {
            return new Aoe(Kind.CHAIN, radius, 1.0D, true, chainTargets, chainDecay);
        }

        /** 有没有群伤。 */
        public boolean present() {
            return kind != Kind.NONE;
        }
    }

    /** 群伤规格（默认 {@code Aoe.none()}）。 */
    public Aoe aoe() { return aoe; }

    /** 一次攻击结算几段（投掷手 = 2：第二段是余震）。 */
    public int attackCount() { return attackCount; }

    /** 第二段及以后的伤害系数（投掷手余震 = 0.5）。 */
    public double tailFactor() { return tailFactor; }

    /** 群伤数值的出处（查证来源，便于复核）。 */
    // ------------------------------------------------------------------
    // 治疗（医疗职业的出手改为治友方）—— 2026-10 第三轮
    // ------------------------------------------------------------------

    /**
     * 治疗规格：这一分支出手时治谁。
     *
     * <p>★ 治疗量**就是 {@link #attackDamage()}**（设计口径「治疗量 = 攻击值」）——
     * 不另立一份「治疗力」，否则两份数值迟早漂移。</p>
     *
     * <p>★ 与 {@link #aoe()} 的关系：那个管「打中之后波及谁」，这个管「出手治谁」。
     * 一个分支要么在输出、要么在治疗；咒愈师是特例 —— 它先输出，再按
     * {@code postHitFactor} 治一名友方。</p>
     */
    public record Heal(Kind kind, int targets, double chainDecay, double farFactor,
                      String innerRangeKey, double postHitFactor,
                      double regenFactor, int regenInterval) {

        /** 治疗类型。 */
        public enum Kind {
            /** 不治疗（照旧打敌人）。 */
            NONE,
            /** 单体：只治**当前生命值最低**的那名友方。 */
            SINGLE,
            /** 范围内**全体**友方各治一次（群愈师）。 */
            ALL,
            /** 连锁：从最低血量那名开始依次跳到附近友方，每跳递减（链愈师）。 */
            CHAIN,
            /** 先攻击敌人，再按造成的伤害比例治一名友方（咒愈师）。 */
            POST_HIT,
            /**
             * **被动**：不攻击，每 {@code regenInterval} tick 给范围内全体友军回一次血
             * （吟游者：每秒 10% 攻击力）。
             *
             * <p>它不经过攻击路径与出手冷却 —— 吟游者本来就不攻击，
             * 这条被动是它「出手」的替代品（见 PawnCombatManager 的 tick 循环）。</p>
             */
            REGEN
        }

        /** 造一个规格（生成物里用这个工厂，参数顺序固定）。 */
        public static Heal of(Kind kind, int targets, double chainDecay, double farFactor,
                             String innerRangeKey, double postHitFactor,
                             double regenFactor, int regenInterval) {
            return new Heal(kind, targets, chainDecay, farFactor,
                    innerRangeKey == null ? "" : innerRangeKey, postHitFactor,
                    regenFactor, regenInterval);
        }

        /** 会不会治疗（含咒愈师那种「打完再治」）。 */
        public boolean present() {
            return kind != Kind.NONE;
        }

        /** 要不要按「内圈 / 外圈」分档（疗养师 0.8；其余不衰减）。 */
        public boolean hasInnerRange() {
            return !innerRangeKey.isEmpty() && farFactor != 1.0D;
        }
    }

    /** 治疗规格（默认不治疗）。 */
    public Heal heal() { return heal; }

    /** 治疗规则的依据（对照表原文 / 查证来源）。 */
    public String healSource() { return healSource; }

    // ------------------------------------------------------------------
    // 停顿（特性自带的「短暂停顿」）—— 2026-10 第四轮
    // ------------------------------------------------------------------

    /**
     * 停顿规格：这一分支的**特性**在命中时让敌人「短暂停顿」多久。
     *
     * <p>★ 数值全部来自 PRTS，**没有暂定值**：</p>
     * <ul>
     *     <li><b>链术师</b>：精二特性「…每次跳跃伤害降低 15% 并造成短暂停顿**(0.5s)**」；
     *         《分支特性信息》同 ⇒ 0.5 秒 = <b>10 tick</b>；</li>
     *     <li><b>凝滞师</b>：精二特性「攻击造成法术伤害，并对敌人造成短暂的停顿」；
     *         《分支特性信息》「**特性停顿时间为 0.8 秒**」⇒ 0.8 秒 = <b>16 tick</b>。</li>
     * </ul>
     *
     * <p>★ 两者的**作用范围不同**（这条区别只在 {@link Kind} 里表达一次，别处不许再判）：
     * 链术师是「每次**跳跃**」⇒ 只管连锁跳到的目标；凝滞师是「**攻击**」⇒ 管每次普攻的
     * 主目标。</p>
     *
     * <p>★ 实现是**一次短时强减速**（幅度见 PawnCombatManager），不是眩晕 ——
     * 本工程的硬规矩是「不碰敌人的坐标」。</p>
     */
    public record Pause(Kind kind, int ticks) {

        /** 停顿作用于谁。 */
        public enum Kind {
            /** 不停顿（默认）。 */
            NONE,
            /** 每次**普攻命中的主目标**（凝滞师）。 */
            HIT,
            /** **连锁跳到的每个目标**（链术师；主目标不在此列）。 */
            CHAIN
        }

        /** 造一个规格（生成物里用这个工厂，参数顺序固定）。 */
        public static Pause of(Kind kind, int ticks) {
            return new Pause(kind, Math.max(0, ticks));
        }

        /** 默认：不停顿。 */
        public static Pause none() {
            return new Pause(Kind.NONE, 0);
        }

        /** 会不会停顿。 */
        public boolean present() {
            return kind != Kind.NONE && ticks > 0;
        }
    }

    /** 停顿规格（默认不停顿）。 */
    public Pause pause() { return pause; }

    /** 停顿数值的出处（PRTS 原文，便于复核）。 */
    public String pauseSource() { return pauseSource; }

    /**
     * <b>伤害乘区</b>：这一击按「攻击者与目标的关系」乘多少（默认不乘）。
     *
     * <p>判据的真值表在 {@code combat/DamageMods.java} —— <b>纯函数</b>，
     * 可以脱离 Minecraft 逐条验（自验场景 traits 的 A/B 两组就是这么做的）。
     * 这里只存「是哪个条件 + 倍率」。</p>
     *
     * <p>★ {@link #fromModule()}：True = 这条来自**专属模组**而不是精二特性。
     * 本工程没有模组开关 ⇒ 目前默认生效；将来做模组时按它门控。</p>
     */
    public record DamageMod(Kind kind, double factor, boolean fromModule) {

        /** 乘区的触发条件。 */
        public enum Kind {
            /** 不乘（默认）。 */
            NONE,
            /** 目标**不是**我当前挡住的敌人（教官 120%）。 */
            TARGET_NOT_BLOCKED,
            /** 目标**是**我当前挡住的敌人（强攻手/无畏者/要塞）。 */
            TARGET_BLOCKED,
            /** 目标与我的**切比雪夫格距 > 1**（领主：远程攻击降到 80%）。 */
            TARGET_FAR,
            /** 目标是空中单位（速射手对空 110%）。 */
            TARGET_AIR,
            /** 目标的**重量等级 >= 3**（攻城手对重 115%）。 */
            TARGET_HEAVY,
            /**
             * 目标在**我前方一横排**（散射手 150%）。
             *
             * <p>「前方一横排」= 面朝方向的**相邻那一排**（forward == 1，横向不限）——
             * 判据在 {@code Targeting#isInFrontRow}，与索敌/阻挡共用同一套朝向旋转。</p>
             */
            TARGET_FRONT_ROW,
            /**
             * **攻击消耗子弹时**（猎手 120%）。
             *
             * <p>★ 口径：子弹系统未做（弹药/装填是另一根轴）⇒ 本工程把**每一击都视为
             * 消耗子弹**，所以它现在等价于「每次都乘」。等做子弹系统时，条件改成
             * 「这一击真的消耗了子弹」（空仓那一击不享受加成）。</p>
             */
            CONSUMES_BULLET,
            /**
             * 解放者：未开技能时随时间**线性爬升**，{@code factor} 存的是**上限**倍率
             * （3.0 = 「最高 +200%」）。爬升用的 tick 数存在棋子实体上（见 PixelUnit）。
             */
            LIBERATOR_RAMP
        }

        /** 造一个规格（生成物里用这个工厂，参数顺序固定）。 */
        public static DamageMod of(Kind kind, double factor, boolean fromModule) {
            return new DamageMod(kind, factor, fromModule);
        }

        /** 默认：不乘。 */
        public static DamageMod none() {
            return new DamageMod(Kind.NONE, 1.0D, false);
        }

        /** 有没有乘区。 */
        public boolean present() {
            return kind != Kind.NONE;
        }
    }

    // ------------------------------------------------------------------
    // 禁疗（这个分支能不能被友方治疗）—— 2026-10 第四轮
    // ------------------------------------------------------------------

    /**
     * 这个分支的棋子**能不能被友方治疗**（默认 {@code true}）。
     *
     * <p>★ 只有三条写「不能」（PRTS 精二特性原文）：</p>
     * <ul>
     *     <li><b>近卫 · 武者</b>：「<b>不成为其他角色的治疗目标</b>…」；</li>
     *     <li><b>近卫 · 收割者</b>：「<b>无法被友方角色治疗</b>…」；</li>
     *     <li><b>重装 · 不屈者</b>：「<b>无法被友方角色治疗</b>」。</li>
     * </ul>
     * <p>《分支特性信息》另写「常态持有<b>禁疗</b>；通过自身特性/天赋/技能产生的
     * <b>作用于自身的</b>治疗效果会无视自身的禁疗」⇒ <b>别人的治疗不生效、自己的回血照旧</b>。
     * 本轮只做前半句（后者的自回血属于特性被动，还没做）。</p>
     *
     * <p>谁读它：{@code PawnCombatManager#isFriendly} —— 挑目标
     * （{@code alliesInRange} / {@code woundedCandidates}）、连锁的
     * {@code chainHealJump}、结算的 {@code applyHeal} **全都过这一道闸门**，
     * 所以禁疗只写一处、三条路径一起受约束。</p>
     */
    public boolean healable() { return healable; }

    /** 禁疗这个值的出处（PRTS 原文；没列入禁疗名单的分支写「可被友方治疗」）。 */
    public String healableSource() { return healableSource; }

    /**
     * <b>分支特性原文</b>（对照表「分支机制（干员特性原文）」那一列，**逐字**）。
     *
     * <p>★ 它是**原始文本**，可能带 wiki 残留（例如链术师那格里有 {@code |} 当成分行）；
     * 显示请用 {@link com.guardianprotocol.data.BranchDef#traitDisplayText()} ——
     * 清洗规则只写在那一处。</p>
     *
     * <p>谁读它：棋子朝向界面（把「分支特性」那一行显示给玩家）。</p>
     */
    public String traitText() { return traitText; }

    // ------------------------------------------------------------------
    // 伤害乘区（「这一击打多疼」）—— 2026-10 第五轮
    // ------------------------------------------------------------------

    /**
     * 这一分支的**伤害乘区**：命中时按「攻击者与目标的关系」给这一击乘一个系数。
     *
     * <p>★ 设计口径：「造成伤害就是指在**进行伤害计算时的最终伤害**，
     * 而不是攻击力」—— 所以它**不改 {@link #attackDamage()} 面板**
     * （朝向界面第 1 行显示的那个数不变），而是乘在 {@code hurt()} 的入参上
     * （护甲/附魔减免**之前**）。纯判据见 {@code combat/DamageMods.java} 的真值表。</p>
     *
     * <p>★ 只剩下**精二特性自带**的三条：教官 120% / 领主 80% / 解放者 ramp。
     * 原先那 5 条<b>专属模组</b>乘区已按设计口径<b>不再默认生效</b>
     * （「模组只需要像天赋一样留一个待定槽」）—— 它们进了 {@link #module()} 那个待定槽，
     * 出处与倍率留档在 生成链 的 MODULE_DAMAGE_MODS_PENDING。
     * {@link DamageMod#fromModule()} 这个标记保留：将来模组落地时按它门控。</p>
     */
    public DamageMod damageMod() { return damageMod; }

    /** 伤害乘区的出处（PRTS 原文，便于复核）。 */
    public String damageModSource() { return damageModSource; }

    /**
     * <b>模组槽位</b>：模组名 / 效果 / 作用对象（见 {@link com.guardianprotocol.data.ModuleEffect}）。
     *
     * <p>★ 设计口径：「天赋不填，目前应该是 72 个分支的天赋槽全空，
     * <b>模组只需要像天赋一样留一个待定槽即可</b>」⇒ 现在 <b>72 个分支一律是空槽位</b>
     * （{@code ModuleEffect.empty()}）。出处那一行写明「待定」以及内容去了哪。</p>
     */
    public ModuleEffect module() { return module; }

    /** 模组槽的出处（现在 72 个分支都是同一句「待定」）。 */
    public String moduleSource() { return moduleSource; }

    public String aoeSource() { return aoeSource; }

    /** 按分支中文名查找。 */
    @Nullable
    public static UnitBranch byBranchName(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim();
        for (UnitBranch b : values()) {
            if (b.branchName.equals(key)) {
                return b;
            }
        }
        return null;
    }

    /** 按序号安全还原（存档/同步用）。 */
    @Nullable
    public static UnitBranch byOrdinal(int ordinal) {
        UnitBranch[] v = values();
        return ordinal < 0 || ordinal >= v.length ? null : v[ordinal];
    }

    static {
        // 自检：索敌规则必须**自洽**（改表改出来的矛盾在启动时就要炸，不能悄悄跑）。
        for (UnitBranch b : values()) {
            if (b.targetCount == TargetCount.MULTI_BLOCKED && b.blockCount <= 0) {
                throw new IllegalStateException(String.format(
                        "分支 %s 是「同时攻击阻挡的所有敌人」，但阻挡数是 %d" ,
                        b.branchName(), b.blockCount));
            }
            if (b.targetCount != TargetCount.SINGLE && b.targetPriority == TargetPriority.AIR_ONLY) {
                throw new IllegalStateException(String.format(
                        "分支 %s 同时标了「只打空中」与多目标，两种口径冲突", b.branchName()));
            }
            if (!b.canAttack && b.targetCount != TargetCount.SINGLE) {
                throw new IllegalStateException(String.format(
                        "分支 %s 不攻击却标了多目标", b.branchName()));
            }
            // 攻击方式与部署位必须相容：远程位一定是投掷物，近战武器一定是近战位。
            // ★ 这条自检挡的是「有人在生成物里手改」与「改了生成器模板却写反」——
            //   同一条规则在生成器（branch_meta.attack_method）与这里各拦一次
            //   （同 踩坑记录的思路）。
            if (!b.isMelee() && b.attackMethod != AttackMethod.PROJECTILE) {
                throw new IllegalStateException(String.format(
                        "分支 %s 部署在远程位，攻击方式却不是投掷物（%s）",
                        b.branchName(), b.attackMethod));
            }
            if (b.attackMethod == AttackMethod.MELEE && !b.isMelee()) {
                throw new IllegalStateException(String.format(
                        "分支 %s 部署在远程位，却标了近战武器攻击", b.branchName()));
            }
            // 阻挡搜索范围：九宫格只对「真的会挡人」的分支有意义 —— 阻挡数为 0 的分支
            // 标了九宫格，等于数据写错了（它会去找一圈敌人却一个名额都没有）。
            if (b.blockSearch == BlockSearch.SURROUNDING && b.blockCount <= 0) {
                throw new IllegalStateException(String.format(
                        "分支 %s 标了「阻挡搜索=周围九格」，但阻挡数是 0", b.branchName()));
            }
            // ★ 常态阻挡（2026-10 设计口径）：它可以**小于**表里的阻挡数
            //   （解放者 3 → 0：表里那格是技能期/显示值），但不能是负数、
            //   也不能大于表里的数 ——「常态比技能期还多」只可能是表或覆盖表写错了。
            //   判据只有一处：生成链 的 normal_block。
            if (b.normalBlockCount < 0 || b.normalBlockCount > b.blockCount) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的常态阻挡 %d 不合理（表里阻挡数 %d）——要么改表，要么改 branch_meta 的常态阻挡覆盖表",
                        b.branchName(), b.normalBlockCount, b.blockCount));
            }
            // 群伤（2026-10 第二轮）：数值可以换来源，但**自相矛盾**必须启动就炸。
            if (b.aoe.kind() == Aoe.Kind.SPLASH
                    && (b.aoe.radius() <= 0.0D || b.aoe.factor() <= 0.0D)) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的溅射半径/系数不合理（r=%s, ×%s）",
                        b.branchName(), b.aoe.radius(), b.aoe.factor()));
            }
            if (b.aoe.kind() == Aoe.Kind.CHAIN
                    && (b.aoe.chainTargets() < 2 || b.aoe.chainDecay() <= 0.0D
                        || b.aoe.chainDecay() > 1.0D || b.aoe.radius() <= 0.0D)) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的连锁参数不合理（目标 %d, 每跳 ×%s, 搜索半径 %s）",
                        b.branchName(), b.aoe.chainTargets(), b.aoe.chainDecay(),
                        b.aoe.radius()));
            }
            if (b.attackCount < 1 || b.tailFactor <= 0.0D || b.tailFactor > 1.0D) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的多段参数不合理（段数 %d, 尾段系数 %s）",
                        b.branchName(), b.attackCount, b.tailFactor));
            }
            // 治疗（2026-10 第三轮）：数值可以换来源，但自相矛盾必须启动就炸。
            if (b.heal.kind() == Heal.Kind.CHAIN
                    && (b.heal.targets() < 2 || b.heal.chainDecay() <= 0.0D
                        || b.heal.chainDecay() > 1.0D)) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的连锁治疗参数不合理（目标 %d, 每跳 ×%s）",
                        b.branchName(), b.heal.targets(), b.heal.chainDecay()));
            }
            if (b.heal.kind() == Heal.Kind.POST_HIT
                    && (b.heal.postHitFactor() <= 0.0D || b.heal.postHitFactor() > 1.0D)) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的「打完再治」比例不合理（%s）",
                        b.branchName(), b.heal.postHitFactor()));
            }
            // ★ REGEN 是**被动**（吟游者不攻击，靠它回血）⇒ 不受「治疗就是出手」这条约束；
            //   其余治疗档位都挂在常态攻击上，不攻击就永远治不到人，必须拦下。
            if (b.heal.present() && b.heal.kind() != Heal.Kind.REGEN && !b.canAttack) {
                throw new IllegalStateException(String.format(
                        "分支 %s 登记了治疗却「不攻击」—— 治疗就是它的出手", b.branchName()));
            }
            if (b.heal.kind() == Heal.Kind.REGEN
                    && (b.heal.regenFactor() <= 0.0D || b.heal.regenFactor() > 1.0D
                        || b.heal.regenInterval() <= 0)) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的被动回血参数不合理（×%s 每 %d tick）",
                        b.branchName(), b.heal.regenFactor(), b.heal.regenInterval()));
            }
            if (b.heal.hasInnerRange() && b.heal.innerRangeKey().isEmpty()) {
                throw new IllegalStateException(String.format(
                        "分支 %s 的治疗标了「内圈外 ×%s」，却没给内圈范围键",
                        b.branchName(), b.heal.farFactor()));
            }
            if (b.aoe.present() && !b.canAttack) {
                throw new IllegalStateException(String.format(
                        "分支 %s 登记了群伤却「不攻击」—— 群伤挂在常态攻击上",
                        b.branchName()));
            }
        }
    }
}
