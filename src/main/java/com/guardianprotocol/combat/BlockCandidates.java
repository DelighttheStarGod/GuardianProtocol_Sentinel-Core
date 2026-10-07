package com.guardianprotocol.combat;

import com.guardianprotocol.GuardianConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * 「谁会被棋子挡住」的判据。
 *
 * <h3>规则（顺序即优先级）</h3>
 * <ol>
 *     <li><b>硬性排除</b>（不受白名单影响）：玩家、棋子自己、已被驯服 / 有主人的生物、非生物；</li>
 *     <li><b>白名单准入</b>：只有配置 {@code [blocking] whitelist} 里列出的实体类型才会被挡
 *         —— <b>默认 {@code ["#monster"]}</b>（原版「怪物」整类）；清空成 {@code []} 则<b>谁都不挡</b>
 *         （此时阻挡系统完全不介入，棋子只是站着输出）。</li>
 * </ol>
 *
 * <h3>为什么是白名单而不是「除玩家外全都挡」</h3>
 * <p>塔防里出现「村民被棋子挡住」「牛羊排成一排被挡」会很怪。默认取 {@code #monster}
 * 是 2026-10 设计确定的口径：**塔防里该挡的就是怪物那一类**，而村民 / 铁傀儡 / 动物属于
 * {@code misc} / {@code creature}，天然被排除。</p>
 *
 * <h3>白名单写法（{@link #matchesEntry} 是唯一判据）</h3>
 * <ul>
 *     <li>{@code #monster} —— 按<b>刷怪类别</b>整类匹配（{@code MobCategory} 的原版名：
 *         {@code monster} / {@code creature} / {@code ambient} / {@code axolotls} /
 *         {@code underground_water_creature} / {@code water_creature} / {@code water_ambient} /
 *         {@code misc}）；</li>
 *     <li>{@code minecraft:zombie} —— 精确匹配；</li>
 *     <li>{@code minecraft:zombified_*} —— 末尾 {@code *} 表示前缀匹配；</li>
 *     <li>{@code minecraft:} —— 命名空间，等价于「该命名空间下全部」；</li>
 *     <li>{@code *} —— 所有实体（不建议）。</li>
 * </ul>
 * <p>大小写不敏感（实体 id 全小写，人不一定）。{@code #} 开头的条目需要<b>类别信息</b>，
 * 所以只在 {@link #matches(String, EntityType)} 这条路上生效，见 {@link #matches(String, String)}。</p>
 */
public final class BlockCandidates {

    /**
     * 自验 / 调试用的白名单覆盖。
     *
     * <p>{@code null} = 用配置文件里的值。存在的理由与
     * {@code PawnCombatManager.setAttackIntervalMultiplier} 一样：自验要在一轮里反复跑
     * 「会挡人」「谁都不挡」两种口径，不能要求测试世界先去改配置文件。</p>
     */
    @Nullable
    private static List<String> whitelistOverride;

    private BlockCandidates() {
    }

    /** 临时覆盖白名单（返回旧值便于还原；传 null 表示恢复读配置）。 */
    @Nullable
    public static List<String> setWhitelistOverride(@Nullable List<String> entries) {
        List<String> old = whitelistOverride;
        whitelistOverride = entries == null ? null : List.copyOf(entries);
        return old;
    }

    /** 当前生效的白名单（只读快照）。 */
    public static List<String> whitelistInEffect() {
        if (whitelistOverride != null) {
            return whitelistOverride;
        }
        return List.copyOf(GuardianConfig.blockingWhitelist());
    }

    /**
     * 这个实体能不能被棋子挡住。
     *
     * <p>返回 {@code false} 时可以用 {@link #rejectReason} 拿到原因（自验报告与排查用）——
     * 「没挡住」有很多互不相干的原因，光看结果猜不出来。</p>
     */
    public static boolean isBlockable(@Nullable Entity entity) {
        return rejectReason(entity) == null;
    }

    /**
     * 不能挡的原因；能挡则返回 {@code null}。
     *
     * <p>顺序与类注释里的规则一致，所以第一个命中的原因就是最终原因。</p>
     */
    @Nullable
    public static String rejectReason(@Nullable Entity entity) {
        if (entity == null) {
            return "实体为空";
        }
        if (!(entity instanceof LivingEntity)) {
            return "不是生物";
        }
        if (entity instanceof Player) {
            return "玩家（硬性排除）";
        }
        if (entity instanceof com.guardianprotocol.entity.PixelUnit) {
            return "棋子自己";
        }
        if (entity instanceof TamableAnimal tame && tame.isTame()) {
            return "已被驯服";
        }
        if (entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
            return "有主人（仆从/召唤物）";
        }
        if (!entity.isAlive()) {
            return "已经死了";
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null) {
            return "实体没有注册 id";
        }
        List<String> list = whitelistInEffect();
        if (list.isEmpty()) {
            return "白名单为空（谁都不挡）";
        }
        EntityType<?> type = entity.getType();
        for (String entry : list) {
            if (matches(entry, type)) {
                return null;
            }
        }
        return "不在白名单里（" + id + " / 类别 " + type.getCategory().getName() + "）";
    }

    /**
     * 白名单条目 vs 实体<b>类型</b>（完整判据，含 {@code #类别} 写法）。
     *
     * <p>这是「这个实体会不会被挡」真正走的那条路 —— {@link #rejectReason} 用的就是它。</p>
     */
    public static boolean matches(@Nullable String entry, @Nullable EntityType<?> type) {
        if (entry == null || type == null) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        // 拿不到 id 也照样能按类别匹配（类别挂在类型上，不依赖注册表）；
        // 传空串让「按 id 匹配」的几种写法都命中不了（唯一例外是 "*"，那是「所有实体」的本意）。
        return matchesEntry(entry, id == null ? "" : id.toString(), type.getCategory());
    }

    /**
     * 白名单条目 vs 实体 <b>id 字符串</b>（没有类型信息时的入口）。
     *
     * <p>{@code #类别} 条目在这条路上<b>一律不匹配</b>：类别是挂在 {@link EntityType} 上的，
     * 光有一个 id 字符串推不出来。这条口子留给「手上只有 id」的场合（排查、脚本核对）；
     * 玩法判定请走 {@link #matches(String, EntityType)}。</p>
     */
    public static boolean matches(@Nullable String entry, @Nullable String entityId) {
        if (entityId == null) {
            return false;
        }
        return matchesEntry(entry, entityId, null);
    }

    /**
     * 判据本体（**唯一实现**，两个 {@code matches} 都转发到这里）。
     *
     * <p>{@code category} 为 {@code null} 表示调用方没有类别信息，此时 {@code #类别} 条目不匹配。</p>
     *
     * <p>空条目一律不匹配 —— 空字符串在「前缀匹配」下会命中一切，那是最危险的一种默认。</p>
     */
    private static boolean matchesEntry(String entry, String entityId, @Nullable MobCategory category) {
        String want = entry.trim().toLowerCase(Locale.ROOT);
        String id = entityId.toLowerCase(Locale.ROOT);
        if (want.isEmpty()) {
            return false;
        }
        if (want.charAt(0) == '#') {
            // "#monster" → MobCategory 的原版名（getName()：monster/creature/...）
            return category != null && category.getName().equalsIgnoreCase(want.substring(1));
        }
        if (want.equals("*")) {
            return true;
        }
        if (want.endsWith("*")) {
            return id.startsWith(want.substring(0, want.length() - 1));
        }
        if (want.endsWith(":")) {
            return id.startsWith(want);
        }
        return id.equals(want);
    }

    /** 一行描述（自验/调试用）：白名单几条、长什么样。 */
    public static String describe() {
        List<String> list = whitelistInEffect();
        return "白名单 " + list.size() + " 条" + (list.isEmpty() ? "（= 谁都不挡）" : "：" + list);
    }
}
