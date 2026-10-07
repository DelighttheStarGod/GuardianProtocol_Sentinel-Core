package com.guardianprotocol.combat;

import com.guardianprotocol.data.UnitBranch;

/**
 * <b>伤害乘区</b>的纯判据 —— 「这一击打多疼」。
 *
 * <h2>★ 设计口径（2026-10-05 原话）</h2>
 * <blockquote>
 * 「N9 伤害乘区做一下，包括教官未阻挡时造成的伤害 120%，领主攻击 1 格外（不含一格）的敌人时
 * 伤害 80%，解放者未开技能时攻击力逐步提升的这些都做一下……此外<b>造成伤害就是指在进行伤害
 * 计算时的最终伤害，而不是攻击力</b>。」
 * </blockquote>
 * <p>所以这些倍率<b>不改 {@code attackDamage()} 面板</b>（朝向界面第 1 行显示的那个数不变、
 * 治疗量也不变），而是乘在 {@code hurt()} 的<b>入参</b>上 —— 乘完再走原版护甲/附魔减免。
 * 在同一次问答里明确选了「乘在 hurt() 的入参上（护甲之前）」。</p>
 *
 * <h2>为什么单独一个纯类</h2>
 * <p>与 {@link VerticalRange} / {@link BlockGeometry} 同一个理由：判据是**纯算术 + 几个布尔**，
 * 抽出来之后自验台可以在**不需要世界、不需要 Minecraft 类**的情况下把真值表逐条验一遍
 * （自验场景 {@code traits} 的 A/B 两组就是这么做的）。关系信息的采集（目标是不是我挡住的、
 * 格距多少、多重）留在 {@code PawnCombatManager} 里 —— 那一层薄到没有分支逻辑。</p>
 *
 * <h2>★ 为什么分两侧：判据只有一条 —— <b>面板上能不能直接看见</b>（设计口径）</h2>
 * <p>给定的例子（原话）：「**白金的天赋：蓄力攻击**（距离上次攻击的间隔越长，下次攻击的
 * 攻击力就越高（最长 2.5 秒，攻击力 180%））这个在游戏内的**干员面板上是看不到的**，
 * 但当攻击到敌人时能从**敌人受到的伤害**中看到，这就算作是**伤害乘区**的范畴，同理教官、领主、
 * 撼地者、猎手、散射手……之类的也是，但像**解放者**这样的攻击力逐渐提升至最高 +200%
 * 是**可以直接在干员面板中看见**的，就算是**攻击力乘区**。」</p>
 * <table border="1">
 *   <tr><th>判据</th><th>归哪一侧</th><th>例子</th></tr>
 *   <tr><td>面板攻击力那个数字**本身就是它**（玩家不打架也看得见）</td>
 *       <td><b>攻击力乘区</b>（{@link #attackMultiplier}）</td>
 *       <td>解放者（未开技能时 40 秒爬到 +200%）</td></tr>
 *   <tr><td>面板**看不见**，只有打中敌人、从掉的伤害里才看得出</td>
 *       <td><b>伤害乘区</b>（{@link #factor}）</td>
 *       <td>白金·蓄力攻击（天赋）· 教官 · 领主 · 撼地者 · 猎手 · 散射手 …</td></tr>
 * </table>
 *
 * <h3>★★ 为什么「计算时机」**不能**当判据（这条踩过，写下来免得再错）</h3>
 * <p>我一度按「出手前能不能确定」来分：能 ⇒ 攻击力侧、不能 ⇒ 伤害侧。看着挺顺，
 * 但**白金的蓄力攻击正好是反例**：它只看**自己**的状态（距上次攻击过去了多久），
 * 跟解放者一样在出手前就能算死 —— 按那条判据它该归攻击力侧，**而它是伤害侧**。</p>
 * <p>所以：<b>时机只是伴随现象，不是判据</b>。两者的相关是「通常面板可见的值都是自己身上的
 * 持续状态 ⇒ 出手前就定了」，但反过来不成立（白金自己身上的状态、面板却看不见）。
 * 唯一判据就是上面那条<b>面板可见性</b>。</p>
 *
 * <h3>两侧各自挂在哪（顺带记下时机，便于读代码）</h3>
 * <ul>
 *     <li><b>攻击力乘区</b>：{@code PawnCombatManager#attack} 读
 *         {@code PixelUnit#effectiveAttackDamage()}（出手那一刻的快照），投掷物带着飞；</li>
 *     <li><b>伤害乘区</b>：{@code PawnCombatManager#hurtWithMods} → {@code #damageFactor}
 *         （命中/结算那一刻，逐目标）。</li>
 * </ul>
 * <p>★ 一条硬规矩：**两边不许乘两次** —— {@link #factor} 对 {@code LIBERATOR_RAMP} 恒返回 1.0
 * （自验 {@code traits} 钉了这条）。</p>
 *
 * <h2>真值表</h2>
 * <table border="1">
 *   <caption>条件不满足 ⇒ 一律 1.0（不乘）</caption>
 *   <tr><th>Kind</th><th>触发条件</th><th>倍率</th><th>哪个分支</th></tr>
 *   <tr><td>{@code TARGET_NOT_BLOCKED}</td><td>目标**不是**我挡住的</td><td>1.20</td>
 *       <td>近卫·教官（精二特性自带）</td></tr>
 *   <tr><td>{@code TARGET_BLOCKED}</td><td>目标**是我挡住的**</td><td>1.10~1.15</td>
 *       <td>强攻手 / 无畏者 / 要塞（★ 专属模组）</td></tr>
 *   <tr><td>{@code TARGET_FAR}</td><td>切比雪夫格距 <b>&gt; 1</b></td><td>0.80</td>
 *       <td>近卫·领主（精二特性自带）</td></tr>
 *   <tr><td>{@code TARGET_AIR}</td><td>目标是空中单位</td><td>1.10</td>
 *       <td>狙击·速射手（★ 专属模组）</td></tr>
 *   <tr><td>{@code TARGET_HEAVY}</td><td>重量等级 <b>&ge; 3</b></td><td>1.15</td>
 *       <td>狙击·攻城手（★ 专属模组）</td></tr>
 *   <tr><td>{@code TARGET_FRONT_ROW}</td><td>目标在**我前方一横排**（面朝方向的相邻那一排）</td>
 *       <td>1.50</td><td>狙击·散射手（精二特性自带）</td></tr>
 *   <tr><td>{@code CONSUMES_BULLET}</td><td>攻击消耗子弹时（★ 子弹系统未做 ⇒ **每一击都算**）</td>
 *       <td>1.20</td><td>狙击·猎手（精二特性自带）</td></tr>
 *   <tr><td>{@code LIBERATOR_RAMP}</td><td>**不在这里** —— 那是**攻击力**倍率，走
 *       {@link #attackMultiplier}（口径修正：原文写的是「**攻击力**逐渐提升
 *       至最高 +200%」，不是「伤害」）</td><td>1.0（不乘）</td>
 *       <td>近卫·解放者（精二特性自带）</td></tr>
 * </table>
 *
 * <h2>★ 每个常数的出处</h2>
 * <ul>
 *     <li>{@link #FAR_TILES} = 1：设计口径「攻击 <b>1 格外（不含一格）</b>的敌人时
 *         伤害 80%」，量法选了<b>切比雪夫格距</b>（{@code max(|dx|,|dz|)}）；</li>
 *     <li>{@link #HEAVY_LEVEL} = 3：PRTS 模组 SIE-X 原文「重量等级<b>大于等于 3</b> 的敌人」；</li>
 *     <li>{@link #LIBERATOR_RAMP_SECONDS} = 40 / {@link #LIBERATOR_RAMP_MAX_BONUS} = 2.0：
 *         PRTS 精二特性「技能未开启时 <b>40 秒</b>内攻击力逐渐提升至最高 <b>+200%</b> 且技能结束时
 *         重置攻击力」；《分支特性信息》「攻击力加成效果<b>每 1 秒更新一次</b>」。</li>
 * </ul>
 *
 * <h2>★★ 2026-10-07 补的两条（设计点名「撼地者、猎手、散射手为什么不做他们的倍率」）</h2>
 * <p>猎手 120% 与散射手 150% 的原文**一直写在对照表的「分支机制（干员特性原文）」列里**，
 * 上一轮却被记成「未做」—— 因为有一条噪音把它们带偏了：对照表还有一列
 * <b>「分支特性（模组更新后）」</b>，我拿它当依据写过「撼地者 = 模组 HAM-X 115%」。
 * 给定的《…_去除干扰项版.xlsx》**正是把那一列删掉了**（连同模板干员/英文名/上线日期/
 * 攻击间隔/天赋四列，共 9 列），只留 12 列分支级事实。补完这两条之后：</p>
 * <ul>
 *     <li><b>撼地者</b>不需要再补 —— 它的 50% 是**溅射系数**（打在目标周围那圈身上的比例），
 *         已按 {@code Aoe.splash(1.0, 0.5)} 实现；</li>
 *     <li><b>炮手</b>「攻击时无视敌人 100 点防御力」**不做** —— 那句同样出自被删掉的模组列，
 *         设计口径「这是模组带的，和原来的特性有什么关系？」；</li>
 * </ul>
 */
public final class DamageMods {

    /** 「远程」的格距门槛：切比雪夫格距 {@code > 1} 才算远程（领主）。 */
    public static final int FAR_TILES = 1;

    /** 「重量较重」的等级门槛（攻城手；原作口径「重量等级 ≥ 3」）。 */
    public static final int HEAVY_LEVEL = 3;

    /** 解放者 ramp 的时长（秒）。 */
    public static final int LIBERATOR_RAMP_SECONDS = 40;

    /** 解放者 ramp 的最高加成（+200% ⇒ ×3.0）。 */
    public static final double LIBERATOR_RAMP_MAX_BONUS = 2.0D;

    /** 一秒多少 tick（与全工程一致）。 */
    private static final int TICKS_PER_SECOND = 20;

    private DamageMods() {
    }

    /**
     * 这一击的伤害倍率（<b>1.0 = 不乘</b>）。
     *
     * <p><b>纯函数</b>：所有关系信息都由调用方算好传进来，这里只有真值表，
     * 所以在无头台上可以脱离世界逐条验。</p>
     *
     * @param mod          分支的乘区规格
     * @param targetHeld   目标当前是否**正被这个攻击者挡住**
     * @param chebyshevTiles 攻击者与目标的**切比雪夫格距**（{@code max(|dx|,|dz|)}，整数格）
     * @param targetFlying 目标是否被判为空中单位（{@link Targeting#isFlying}）
     * @param weightLevel  目标的**重量等级**（{@link Targeting#approxWeightLevel}，近似值）
     * @param targetInFrontRow 目标是否在攻击者的**前方一横排**（散射手；见
     *                     {@link Targeting#isInFrontRow}）
     */
    public static double factor(UnitBranch.DamageMod mod,
                                boolean targetHeld,
                                int chebyshevTiles,
                                boolean targetFlying,
                                int weightLevel,
                                boolean targetInFrontRow) {
        if (mod == null || mod.kind() == null) {
            return 1.0D;
        }
        return switch (mod.kind()) {
            // 教官：打「不是我挡住的」⇒ ×1.2；打自己挡住的 ⇒ 不乘
            case TARGET_NOT_BLOCKED -> targetHeld ? 1.0D : mod.factor();
            // 强攻手 / 无畏者 / 要塞：只对**自己挡住的**那一只加伤
            case TARGET_BLOCKED -> targetHeld ? mod.factor() : 1.0D;
            // 领主：格距 ≤ 1 算近战（100%），> 1 才降到 80%
            case TARGET_FAR -> chebyshevTiles > FAR_TILES ? mod.factor() : 1.0D;
            // 速射手：只对空中的加伤
            case TARGET_AIR -> targetFlying ? mod.factor() : 1.0D;
            // 攻城手：只对「重量等级 ≥ 3」的加伤
            case TARGET_HEAVY -> weightLevel >= HEAVY_LEVEL ? mod.factor() : 1.0D;
            // 散射手：只对**我前方一横排**的加伤（原文「对自己前方一横排的敌人攻击力提升至150%」）
            case TARGET_FRONT_ROW -> targetInFrontRow ? mod.factor() : 1.0D;
            // 猎手：攻击消耗子弹时加伤。★ 子弹系统未做 ⇒ 每一击都视为消耗（见 Kind 的注释）
            case CONSUMES_BULLET -> mod.factor();
            // ★ 解放者**不是伤害乘区**（2026-10-05 口径修正）：
            //   原文写的是「**攻击力**逐渐提升至最高 +200%」⇒ 归到攻击力那一侧，
            //   走 {@link #attackMultiplier}，这里恒 1.0（别再乘一遍，否则会乘两次）。
            case LIBERATOR_RAMP -> 1.0D;
            case NONE -> 1.0D;
        };
    }

    /** 兼容旧调用点（没有「前方一横排」这个判据时传 false）。 */
    public static double factor(UnitBranch.DamageMod mod,
                                boolean targetHeld,
                                int chebyshevTiles,
                                boolean targetFlying,
                                int weightLevel) {
        return factor(mod, targetHeld, chebyshevTiles, targetFlying, weightLevel, false);
    }

    /**
     * 这一分支的修正算不算「**攻击力**」那一侧（而不是「造成伤害」那一侧）。
     *
     * <h3>★ 唯一判据：这个加成在**干员面板上能不能直接看见**（设计口径）</h3>
     * <blockquote>
     * 「白金的**蓄力攻击**这个在游戏内的**干员面板上是看不到的**，但当攻击到敌人时能从
     * **敌人受到的伤害**中看到，这就算作是**伤害乘区**……但像**解放者**这样的攻击力逐渐
     * 提升至最高 +200% 是**可以直接在干员面板中看见**的，就算是**攻击力乘区**。」
     * </blockquote>
     * <ul>
     *     <li><b>面板看得见</b>（面板攻击力那个数字本身就是它）⇒ {@code LIBERATOR_RAMP}
     *         ⇒ 走 {@link #attackMultiplier}（{@code PixelUnit#effectiveAttackDamage()}）；</li>
     *     <li><b>面板看不见</b>（只有打中敌人、从掉的伤害里才看得出）⇒ 其余全部
     *         ⇒ 走 {@link #factor}。</li>
     * </ul>
     *
     * <p><b>★ 别用「计算时机」当判据</b>：白金的蓄力只看自己的状态（距上次攻击多久），
     * 与解放者一样在出手前就能算死，可它是**伤害**侧 —— 时机只是伴随现象
     * （面板可见的值通常是自己身上的持续状态 ⇒ 出手前就定了，但反过来不成立）。</p>
     */
    public static boolean isAttackSide(UnitBranch.DamageMod mod) {
        return mod != null && mod.kind() == UnitBranch.DamageMod.Kind.LIBERATOR_RAMP;
    }

    /**
     * <b>攻击力倍率</b>（默认 1.0；目前只有解放者的 ramp 会 ≠ 1）。
     *
     * <p>调用点：① {@code PixelUnit#effectiveAttackDamage()}（伤害结算与面板显示**共用**它）；
     * ② 朝向界面。判据只有这一处，别处不许再算一遍。</p>
     */
    public static double attackMultiplier(UnitBranch.DamageMod mod, int rampTicks) {
        if (!isAttackSide(mod)) {
            return 1.0D;
        }
        return liberatorRamp(mod.factor(), rampTicks);
    }

    /**
     * 解放者的 ramp 倍率：<b>每秒更新一次</b>的线性爬升，40 秒到顶（×3.0）。
     *
     * <p>口径来自两条原文：精二特性「技能未开启时 40 秒内攻击力逐渐提升至最高 +200% 且技能结束时
     * 重置攻击力」+《分支特性信息》「攻击力加成效果<b>每 1 秒更新一次</b>」。
     * 所以这里按 <b>整秒</b>取进度（不是逐 tick 连续），与原文的「每 1 秒更新」对齐。</p>
     *
     * <p>★ 「技能期间不累积、技能结束时重置」不在这里判 —— 那是**棋子的状态**：
     * {@code PixelUnit} 每 tick 维护「ramp tick 数」，技能激活时直接归零
     * （归零发生在技能期间，所以技能一结束它天然就是 0 = 「技能结束时重置」）。</p>
     *
     * @param factor       规格里的上限倍率（解放者是 3.0）
     * @param rampTicks    技能未激活期间累积的 tick 数（0 ~ 40×20）
     * @return 1.0 ~ factor
     */
    public static double liberatorRamp(double factor, int rampTicks) {
        int seconds = Math.max(0, rampTicks) / TICKS_PER_SECOND;
        double progress = Math.min(seconds, LIBERATOR_RAMP_SECONDS) / (double) LIBERATOR_RAMP_SECONDS;
        // 1.0 → factor 线性（progress 0 ⇒ 1.0，progress 1 ⇒ factor）
        return 1.0D + (factor - 1.0D) * progress;
    }

    /** ramp 最多累积多少 tick（40 秒）。 */
    public static int liberatorRampMaxTicks() {
        return LIBERATOR_RAMP_SECONDS * TICKS_PER_SECOND;
    }

    /** ramp 已经爬了多少**整秒**（界面那一行用；整数秒，与「每 1 秒更新一次」同一口径）。 */
    public static int rampSeconds(int rampTicks) {
        return Math.min(Math.max(0, rampTicks) / TICKS_PER_SECOND, LIBERATOR_RAMP_SECONDS);
    }

    // ------------------------------------------------------------------
    // 界面文案（朝向界面的「伤害乘区」那一行）—— 2026-10 第五轮
    // ------------------------------------------------------------------

    /**
     * 乘区条件的**中文短语**（静态：只看 Kind）。
     *
     * <p>★ 放在这里而不是写进语言键：它与 {@code UnitBranch#traitText()} 是同一条口径 ——
     * <b>数据侧的中文原文直接显示</b>（en_us 不翻译，与分支名/分支特性那两行一致）；
     * 真正属于「界面外壳」的前缀「伤害乘区：」才走语言键。</p>
     */
    public static String conditionText(UnitBranch.DamageMod mod) {
        if (mod == null || !mod.present()) {
            return "";
        }
        return switch (mod.kind()) {
            case TARGET_NOT_BLOCKED -> "打未被自己阻挡的敌人时";
            case TARGET_BLOCKED -> "打被自己阻挡的敌人时";
            case TARGET_FAR -> "打 1 格外（不含 1 格）的敌人时";
            case TARGET_AIR -> "打空中单位时";
            case TARGET_HEAVY -> "打重量等级 ≥3 的敌人时";
            case TARGET_FRONT_ROW -> "打前方一横排的敌人时";
            case CONSUMES_BULLET -> "攻击消耗子弹时（每击都算）";
            case LIBERATOR_RAMP -> "未开技能时爬升";
            case NONE -> "";
        };
    }

    /**
     * 朝向界面「伤害乘区」那一行要显示的**正文**（不含前缀）。
     *
     * <p>两种形态：</p>
     * <ul>
     *     <li><b>条件型</b>（教官 / 领主 / 猎手 / 散射手 / 模组那 5 条）：{@code 条件 ×倍率}，
     *         模组来的那几条再缀一个「（★模组）」—— 因为它严格说不属于「未开启模组」的
     *         精二特性，界面上要能看出来（口径见 设计说明）；</li>
     *     <li><b>爬升型</b>（解放者）：{@code 未开技能时爬升：当前 ×1.35（最高 ×3.00，12/40 秒）}
     *         —— 实测反馈「解放者的效果从面板上看不到」，这一行就是给它看的：
     *         倍率随 {@code rampTicks} 实时变，能直接看着它爬。</li>
     * </ul>
     *
     * @param rampTicks 棋子的 ramp tick 数（非 ramp 分支忽略它）
     */
    public static String panelText(UnitBranch.DamageMod mod, int rampTicks) {
        if (mod == null || !mod.present()) {
            return "";
        }
        if (isAttackSide(mod)) {
            // ★ 解放者：**攻击力**爬升（设计口径：「解放者是攻击力逐渐提升至 200%，
            //   它也没说是伤害啊」）。措辞跟着原文走 —— 写「攻击力」不写「伤害」。
            return String.format(java.util.Locale.ROOT,
                    "攻击力爬升：当前 ×%.2f（最高 ×%.2f，未开技能 %d/%d 秒）",
                    liberatorRamp(mod.factor(), rampTicks), mod.factor(),
                    rampSeconds(rampTicks), LIBERATOR_RAMP_SECONDS);
        }
        return String.format(java.util.Locale.ROOT, "伤害乘区：%s ×%.2f%s",
                conditionText(mod), mod.factor(), mod.fromModule() ? "（★模组）" : "");
    }

    /**
     * 展平成一行文本（自验报告 / 排查用）。
     *
     * <p>固定小数点、{@link java.util.Locale#ROOT}：免得跟着系统区域把小数点变成逗号，
     * 报告就没法逐字比对了（同 {@link VerticalRange#describe} 的理由）。</p>
     */
    public static String describe(UnitBranch.DamageMod mod) {
        if (mod == null || !mod.present()) {
            return "无乘区";
        }
        return String.format(java.util.Locale.ROOT, "%s ×%.3f%s",
                mod.kind(), mod.factor(), mod.fromModule() ? "（★模组）" : "（精二特性）");
    }
}
