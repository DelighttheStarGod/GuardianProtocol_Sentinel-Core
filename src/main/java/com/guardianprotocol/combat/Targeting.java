package com.guardianprotocol.combat;

import com.guardianprotocol.GuardianConfig;
import com.guardianprotocol.data.PieceFacing;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * 索敌用的判据与比较器（{@link UnitBranch.TargetPriority} 的落地实现）。
 *
 * <h3>为什么单独一个类，而不是塞进 PawnCombatManager</h3>
 * <p>「谁更该被打」这件事由三条互不相干的判据拼起来：物理防御、重量、是不是空中单位。
 * 它们的取法都带着**启发式**（原版没有这些字段），所以要有个地方把「为什么这么取」
 * 写清楚，而不是散在 manager 的循环里。</p>
 *
 * <h3>三条判据的来源与近似</h3>
 * <ul>
 *     <li><b>物理防御</b>：原版 {@code Attributes.ARMOR} 就是「防御力」，
 *         神射手的「优先攻击防御力最低」直接照它比，不需要近似。</li>
 *     <li><b>空中单位</b>：见 {@link #isFlying}，用
 *         {@code !onGround() && !isInWater()} <b>再接一个「不是地面生物」的排除</b>。
 *         单看 {@code onGround()} 会把「正在跳的僵尸」当成空中单位 —— 这个坑真踩过。</li>
 *     <li><b>重量</b>：原版根本没有「重量」这个属性，只能<b>近似</b>，见 {@link #weight}。
 *         改它就是改可玩性，注释里写全了。</li>
 * </ul>
 */
public final class Targeting {

    /**
     * 「刚离地」的容差（格）。
     *
     * <p>原版跳跃最高约 1.25 格，被爆炸顶起来也常见 1 格出头，所以脚底离地
     * 1.0 格以内<b>不算飞</b>；幻翼那种贴地掠过（飞得很低）仍然算。</p>
     */
    private static final double AIRBORNE_MARGIN = 1.0D;

    /** {@link #weight} 的三项权重：体积 1.0 / 血量 1.0 / 抗击退 2.0（抗击退最能代表「重」）。 */
    private static final double SIZE_WEIGHT = 1.0D;
    private static final double HEALTH_WEIGHT = 1.0D;
    private static final double KNOCKBACK_WEIGHT = 2.0D;

    /** {@link #weight} 的夹取区间（避免 log2(0) = -∞ 与数值爆炸）。 */
    private static final double MIN_VOLUME = 0.01D;
    private static final double MAX_VOLUME = 1024.0D;
    private static final double MIN_HEALTH = 1.0D;
    private static final double MAX_HEALTH = 100_000.0D;

    /**
     * 判定「是不是空中单位」。
     *
     * <h3>为什么不能只看 {@code onGround()}</h3>
     * <p>跳跃中的僵尸、刚被打飞的骷髅、走台阶的一瞬间，{@code onGround()} 全是 false。
     * 速射手「优先攻击空中单位」若照它判，就会在僵尸跳起来的那一下把它当空中目标 ——
     * 观感是「速射手乱打」，而原因藏在一次跳跃里，极难查。</p>
     *
     * <h3>判据（三条，任一成立即算飞）</h3>
     * <ol>
     *     <li><b>{@link MobCategory#AMBIENT}</b>：原版把蝙蝠 / 悦灵归在这一类，
     *         它们本来就是飞行生物（贴地掠过时 {@code onGround()} 可能为 true，
     *         所以这一条必须**先判**、且不看高度）；</li>
     *     <li><b>脚底离地超过 {@link #AIRBORNE_MARGIN}</b>，并且</li>
     *     <li><b>垂直速度很小</b>（|Δy| &lt; 0.05）。跳跃是「向上几十 tick 的抛物线」，
     *         落地/掉落的 Δy 也很大；而飞行生物水平巡航时 Δy 接近 0。
     *         第 2 条负责「它确实在空中」，第 3 条负责「它是在飞，不是在跳/在掉」。</li>
     * </ol>
     *
     * <h3>已知的近似（写出来，免得下次当成 bug 查）</h3>
     * <ul>
     *     <li>幻翼**俯冲**时 Δy 很大 → 那一瞬间不算空中，速射手会先打别的；
     *         俯冲本来就是要落地攻击，影响很小。</li>
     *     <li>「站在 2 格高柱子上不动的骷髅」脚底离地 2 格、Δy≈0 → 会被当成空中单位。
     *         实机少见，但确实存在；真要修就得逐格查支撑方块（去改 {@link #isFlying}）。</li>
     *     <li>严格说这是「当前是否悬空 + 是否在平飞」，不是「生物类型是不是飞行生物」——
     *         原版没有「飞行生物」这个类别标志可读，所以只能用行为近似。</li>
     * </ul>
     */
    public static boolean isFlying(Entity entity) {
        if (entity.getType().getCategory() == MobCategory.AMBIENT) {
            return true;
        }
        if (entity.onGround() || entity.isInWater()) {
            return false;
        }
        if (groundClearance(entity) <= AIRBORNE_MARGIN) {
            return false;
        }
        // 跳跃 / 坠落：垂直速度明显不为 0，不算「巡航中的空中单位」
        return Math.abs(entity.getDeltaMovement().y) < 0.05D;
    }

    /**
     * 脚底离**支撑面**的高度（格）。找不到支撑面时返回一个「很高」的值。
     *
     * <h3>★★★ 这里原来是一个恒假的判据（2026-10 查到并修掉）</h3>
     * <p>老写法是 {@code feetY - Math.floor(feetY) <= AIRBORNE_MARGIN} ——
     * 那是 **y 坐标的小数部分**（取值范围 {@code [0,1)}），而 {@code AIRBORNE_MARGIN} 正好是
     * {@code 1.0}：判据<b>永远成立</b>，于是 {@link #isFlying} 对**除 AMBIENT（蝙蝠/悦灵）
     * 以外的所有实体都返回 false**。幻翼、恶魂、恼鬼这些真飞行生物全被当成「地面单位」，
     * 后果是：速射手「优先攻击空中单位」几乎永不生效、裂空炮手「只打空中」打不到任何目标、
     * 要塞与投掷手的「溅射不可对空」形同虚设 —— 而且全都<b>不报错</b>。</p>
     * <p><b>怎么发现的</b>：给群伤写自验时摆了一只幻翼当「空中靶」，结果它被判成地面单位、
     * 照样吃到溅射。原来那些场景从没用幻翼验过空中口径（只用过蝙蝠那种 AMBIENT），
     * 于是这条判据一直没人碰。</p>
     * <p>现在真的去量高度：从脚底往下逐格找最近的<b>有碰撞体积</b>的方块（最多 8 格）。
     * 站在地上 ⇒ 高度 0；跳起来 1 格出头 ⇒ ≤ 1.0 仍算地面（与 {@link #AIRBORNE_MARGIN}
     * 的注释一致）；巡航中的幻翼一般在十几格高 ⇒ 算飞。</p>
     *
     * <p>已知近似（写出来，免得下次当 bug 查）：贴地掠过的幻翼（高度 ≤1 格）会被算成地面单位 ——
     * 这与「1.0 格以内不算飞」这条容差是同一件事，不是新引入的偏差。</p>
     */
    private static double groundClearance(Entity entity) {
        double feetY = entity.getBoundingBox().minY;
        BlockPos start = BlockPos.containing(entity.getX(), feetY - 1.0E-3D, entity.getZ());
        for (int i = 0; i <= 8; i++) {
            BlockPos p = start.below(i);
            if (!entity.level().getBlockState(p).getCollisionShape(entity.level(), p).isEmpty()) {
                return feetY - (p.getY() + 1);
            }
        }
        return 9.0D;
    }

    /**
     * 重量权重（越大越「重」）——攻城手（提丰/攻城手分支）的索敌判据。
     *
     * <h3>为什么不是整数分档</h3>
     * <p>早先用「碰撞箱尺寸分 3 档」的整数近似（宽高各一刀切）。它的毛病是
     * <b>档内完全没有区分度</b>：同一档里的苦力怕和末影人权重相同，攻城手会按
     * 「离得近」随便挑一个；而档与档之间又是硬跳变，稍微大一点点的生物权重直接翻倍。
     * 现在改成<b>连续加权</b>：三个可读的物理量各自取 log2 后线性加和。</p>
     *
     * <h3>三项与它们的权重</h3>
     * <ul>
     *     <li><b>体积</b>（宽×高，权重 1.0）：log2 后「线性放大」变成「指数放大」，
     *         体积翻倍 = +1，便于和血量项比较量级。</li>
     *     <li><b>最大生命</b>（权重 1.0）：同上。</li>
     *     <li><b>抗击退</b>（{@code KNOCKBACK_RESISTANCE}，权重 2.0）：本来就在 0~1，
     *         直接线性相加。它最能代表「推不动 = 重」，所以给两倍权重。</li>
     * </ul>
     *
     * <h3>三条刻意保留的近似（写出来，免得下次当成 bug 查）</h3>
     * <ol>
     *     <li><b>体积是「体型」的代理，不是真实质量</b>：原版没有质量字段。
     *         想要精确质量，只能靠数据包给每个生物标级 —— 那时改这一个方法即可。</li>
     *     <li><b>血量是「强度」的代理，也会让攻城手顺手优先打 BOSS</b>：
     *         血厚的生物权重更高，于是攻城手会先啃 BOSS。<b>这是有意的</b>
     *         ——「重甲/巨型单位」和「高血量单位」在塔防里本来就该由同一个人处理，
     *         别看到攻城手打 BOSS 就当 bug 修。</li>
     *     <li><b>负的抗击退当 0 处理</b>（{@link #clamp}）：某些 mod 生物会给出负抗性，
     *         不夹的话 log 之外的线性项会被拉成负权重，把「最重」判反。</li>
     * </ol>
     *
     * <p>三个量都先 {@link #clamp} 到合理区间再取 log：血量 0（某些无敌实体）会让
     * {@code log2(0) = -∞}，体积为 0 同理；不夹会让整个比较器输出 NaN，
     * 表现为「索敌随机乱跳」，极难查。</p>
     */
    public static double weight(LivingEntity entity) {
        final double log2 = Math.log(2.0D);
        double volume = clamp(entity.getBbWidth() * entity.getBbHeight(), MIN_VOLUME, MAX_VOLUME);
        double health = clamp(entity.getMaxHealth(), MIN_HEALTH, MAX_HEALTH);
        double knockback = clamp(entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
        return SIZE_WEIGHT * Math.log(volume) / log2
                + HEALTH_WEIGHT * Math.log(health) / log2
                + KNOCKBACK_WEIGHT * knockback;
    }

    /** 把值夹进区间；NaN 一律取下界（NaN 参与比较会让排序结果不确定）。 */
    private static double clamp(double value, double low, double high) {
        if (Double.isNaN(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }

    /**
     * <b>近似</b>的「重量等级」（原作口径的 1~4 级）—— 攻城手对重加伤（≥ 3 级）要用它。
     *
     * <h3>为什么是近似</h3>
     * <p>原作的「重量等级」是策划表里的一个整数（1 = 小体型的狗/士兵，4 = BOSS / 巨型单位），
     * <b>原版 Minecraft 没有这个属性</b>，任何实体都没有地方存它。所以只能从身体尺寸与
     * 抗击退反推 —— 与 {@link #weight} 是同一族「刻意近似」，改动它就是在改可玩性。</p>
     *
     * <h3>分档（先按体积，再按抗击退加一档，最后夹到 1~4）</h3>
     * <ul>
     *     <li>体积（宽×高）&lt; 1.0 ⇒ 1 级（小鸡 / 蝙蝠 / 小僵尸那类）；</li>
     *     <li>&lt; 2.5 ⇒ 2 级（僵尸 / 骷髅 / 苦力怕，最常见的杂兵）；</li>
     *     <li>&lt; 6.0 ⇒ 3 级（铁傀儡 / 猪灵 brute / 凋灵骷髅那类大体型）；</li>
     *     <li>其余 ⇒ 4 级（末影龙 / 凋灵 / 巨人）；</li>
     *     <li>抗击退 ≥ 0.5 再 <b>+1 档</b>（推不动的就是「重」），最后夹在 1~4。</li>
     * </ul>
     *
     * <p><b>已知近似</b>：一只普通的僵尸会被判成 2 级、铁傀儡 3 级 —— 攻城手的加伤因此
     * 打得到铁傀儡而打不到僵尸。这是<b>有意的</b>：塔防里「重甲/巨型单位」本来就该由
     * 攻城手处理（同 {@link #weight} 注释里那条「顺手优先打 BOSS 不是 bug」）。</p>
     */
    public static int approxWeightLevel(LivingEntity entity) {
        double volume = entity.getBbWidth() * entity.getBbHeight();
        int level;
        if (volume < 1.0D) {
            level = 1;
        } else if (volume < 2.5D) {
            level = 2;
        } else if (volume < 6.0D) {
            level = 3;
        } else {
            level = 4;
        }
        double knockback = clamp(entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
        if (knockback >= 0.5D) {
            level++;
        }
        return Math.max(1, Math.min(4, level));
    }

    /**
     * 两个实体之间的**切比雪夫格距**：{@code max(|dx|, |dz|)}，用**方块坐标**算（整数格）。
     *
     * <p>口径出处：设计确定 —— 领主「攻击 <b>1 格外（不含一格）</b>的敌人时伤害 80%」
     * 里的「1 格」按<b>切比雪夫格距</b>量（与本工程「攻击范围是格子集合」的一贯口径一致）。</p>
     *
     * <p>只用水平两轴：垂直距离不影响「近战还是远程」这个判断（飞在天上的敌人若水平就在
     * 正上方那一格，仍然是 0 格 —— 它是不是能被打到由 {@link VerticalRange} 管）。</p>
     */
    public static int chebyshevTiles(Entity a, Entity b) {
        BlockPos pa = a.blockPosition();
        BlockPos pb = b.blockPosition();
        return Math.max(Math.abs(pa.getX() - pb.getX()), Math.abs(pa.getZ() - pb.getZ()));
    }

    /**
     * 物理防御力（原版 {@code ARMOR}）。实体没有这个属性时记 0（= 最低防御）。
     */
    public static double armor(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attributes.ARMOR);
        return attr == null ? 0.0D : attr.getValue();
    }

    /**
     * 按优先级建比较器：**排在前面 = 更该被打**。
     *
     * <p>所有优先级最后都用「离棋子更近」收尾（{@link #tieBreak}），
     * 这样同优先级的目标选择是确定的，而且不会出现「两个一样重/一样甲的目标反复横跳」。</p>
     *
     * @param preferred 「该优先打的」判据（{@link UnitBranch.TargetPriority#BLOCKED_FIRST} 用；
     *                  其余优先级传 null）—— 由调用方给出，因为「谁被这个棋子挡住了」是
     *                  战斗管理器的状态，不是索敌自己的知识
     */
    public static Comparator<LivingEntity> comparator(UnitBranch.TargetPriority priority, Vec3 from,
                                                      @Nullable java.util.function.Predicate<LivingEntity> preferred) {
        return (a, b) -> {
            int primary = switch (priority) {
                case LOWEST_ARMOR -> Double.compare(armor(a), armor(b));
                case HEAVIEST -> Double.compare(weight(b), weight(a));   // 重的在前
                case AIR_FIRST -> Integer.compare(rank(isFlying(a)), rank(isFlying(b)));
                // ★ N2（2026-10 第五轮）：**优先**打「我正挡住的」敌人 —— 被挡的排前面，
                //   其后仍按距离。注意它**不筛掉**其他目标（「优先」不等于「只能」；
                //   那 27 个「只能」由 BranchDef#attacksBlockedOnly 负责，两者正交）。
                case BLOCKED_FIRST -> preferred == null ? 0
                        : Integer.compare(preferredRank(preferred, a), preferredRank(preferred, b));
                case AIR_ONLY, NEAREST -> 0;
            };
            return primary != 0 ? primary : Double.compare(a.distanceToSqr(from), b.distanceToSqr(from));
        };
    }

    /** 兼容旧调用点：不带「优先打谁」判据（除 BLOCKED_FIRST 外都用这个重载）。 */
    public static Comparator<LivingEntity> comparator(UnitBranch.TargetPriority priority, Vec3 from) {
        return comparator(priority, from, null);
    }

    /** {@code BLOCKED_FIRST} 的排序键：命中「优先」判据 = 0（在前），否则 1。 */
    private static int preferredRank(java.util.function.Predicate<LivingEntity> preferred,
                                     LivingEntity entity) {
        return preferred.test(entity) ? 0 : 1;
    }

    /** {@code AIR_FIRST} 用的排序键：空中 = 0（在前），地面 = 1。 */
    private static int rank(boolean flying) {
        return flying ? 0 : 1;
    }

    /**
     * 这个优先级是不是「只打空中」。
     *
     * <p>单独提出来是因为它不只是排序，还**过滤掉**全部地面目标 ——
     * 若把裂空炮手的偏好当成普通排序，它就会「没有空中目标时打地面」，
     * 而表里写的是「起飞后<b>只</b>攻击空中敌人」。</p>
     */
    public static boolean requiresAir(UnitBranch.TargetPriority priority) {
        return priority == UnitBranch.TargetPriority.AIR_ONLY;
    }

    /**
     * 什么算「敌人」：与保护目标的抹杀口径一致 —— 实现 {@link Enemy} 接口，
     * 或属于 {@link MobCategory#MONSTER} 类别。
     *
     * <p><b>★ 口径只有一处实现</b>：这里委托给 {@code GuardianConfig.isHostile(...)}，
     * 而不是自己再抄一遍 {@code instanceof Enemy || MONSTER}。
     * 早先 {@code PawnCombatManager.isEnemy} 就抄过一份，现在合并掉了 ——
     * 两处各写一份「什么算敌人」，将来配置里加了白/黑名单就只改一处、另一处悄悄不一致
     * （这个项目在「同一份数据抄两遍」上栽过好几次，见 踩坑记录）。</p>
     */
    public static boolean isEnemy(LivingEntity entity) {
        if (entity instanceof com.guardianprotocol.entity.PixelUnit) {
            return false;
        }
        return GuardianConfig.isHostile(entity);
    }

    /**
     * 目标是不是在攻击者的<b>前方一横排</b>（散射手 150% 的判据）。
     *
     * <h3>「前方一横排」怎么量（口径写死在这一处）</h3>
     * <p>把「目标格 - 棋子格」这个<b>世界</b>偏移，投影到棋子朝向自己的坐标系里：</p>
     * <ul>
     *     <li>{@code forward} = 偏移 · 朝向的前方单位向量（{@link PieceFacing#dx()}/{@link PieceFacing#dz()}）</li>
     *     <li>{@code lateral} 不参与判定 —— 原文说的是「一**横排**」，即横向整排都算</li>
     * </ul>
     * <p>⇒ <b>{@code forward == 1}</b> 即为「前方一横排」（面朝方向的相邻那一排）。
     * 投影用的是与 {@link PixelUnit#worldCells()} / 阻挡同一套朝向定义
     * （{@link PieceFacing#toWorldOffset} 的逆变换），所以不会出现「索敌一套、这里又一套」。</p>
     *
     * <p>★ 为什么不用「在攻击范围内」代替：散射手打的是**范围内所有敌人**，
     * 但那 150% 只对**前方一横排**生效 —— 两者必须分开判。</p>
     */
    public static boolean isInFrontRow(PixelUnit pawn, LivingEntity target) {
        if (pawn == null || target == null) {
            return false;
        }
        int dx = target.blockPosition().getX() - pawn.blockPosition().getX();
        int dz = target.blockPosition().getZ() - pawn.blockPosition().getZ();
        PieceFacing facing = pawn.getFacing();
        // toWorldOffset(x, z) 把「横向 x / 前后 z」映到世界；这里要它的**逆**（正交变换 = 转置）：
        //   forward = 世界偏移 · 前方单位向量
        int forward = dx * facing.dx() + dz * facing.dz();
        return forward == FRONT_ROW_FORWARD;
    }

    /** 「前方一横排」的前向格数：面朝方向的**相邻**那一排（不是自身那一排、也不是更远）。 */
    public static final int FRONT_ROW_FORWARD = 1;

    private Targeting() {
    }
}
