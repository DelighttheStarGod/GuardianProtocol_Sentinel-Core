package com.guardianprotocol;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 卫戍协议的配置。
 *
 * <p>用 Forge 的 COMMON 配置（服务器与客户端都要有一份，且以服务端为准）。
 * 文件落在 {@code config/guardian_protocol-common.toml}。</p>
 *
 * <p><b>注意</b>：1.20.1 的 {@code FMLJavaModLoadingContext} <b>没有</b>
 * {@code registerConfig} 方法（那是 MDK 模板里 {@code context.registerConfig(...)}
 * 的新版写法），必须用 {@link net.minecraftforge.fml.ModLoadingContext#get()}。</p>
 */
public final class GuardianConfig {

    /** 保护目标的默认血量。 */
    public static final int DEFAULT_TARGET_HEALTH = 100;

    public static final ForgeConfigSpec SPEC;

    // ---- 保护目标 ----
    private static final ForgeConfigSpec.IntValue TARGET_HEALTH;
    private static final ForgeConfigSpec.DoubleValue TAUNT_RADIUS;
    private static final ForgeConfigSpec.BooleanValue TAUNT_ENABLED;
    private static final ForgeConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    private static final ForgeConfigSpec.BooleanValue KILL_ON_CONTACT;
    private static final ForgeConfigSpec.DoubleValue KILL_RADIUS;
    private static final ForgeConfigSpec.BooleanValue ONLY_HOSTILE;
    private static final ForgeConfigSpec.BooleanValue DAMAGE_ALL_MOBS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> KILL_WHITELIST;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> KILL_BLACKLIST;

    // ---- 棋子布设朝向 ----
    private static final ForgeConfigSpec.EnumValue<PawnFacingMode> PAWN_FACING_MODE;

    // ---- 阻挡 ----
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCK_WHITELIST;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        // ---------------- 保护目标 ----------------
        b.comment("保护目标（Protect Target）：塔防里的「失守条件」核心。",
                "自带嘲讽（强制吸引敌人 AI），并把接触到它的敌人抹杀。")
                .push("protect_target");

        TARGET_HEALTH = b
                .comment("保护目标的最大生命值。归零时方块被摧毁（塔防中即「失守」）。",
                        "设为 0 或负数表示不可摧毁。")
                .defineInRange("maxHealth", DEFAULT_TARGET_HEALTH, -1, Integer.MAX_VALUE);

        TAUNT_ENABLED = b
                .comment("是否开启嘲讽：强制范围内的敌对生物把 AI 目标改为保护目标。")
                .define("tauntEnabled", true);

        TAUNT_RADIUS = b
                .comment("嘲讽半径默认值（格）。★ 这只是「新放置的保护目标」的默认档位；",
                        "游戏里可以右键保护目标，用界面单独调整那一个方块的半径。",
                        "提示：半径按 8 格一档离散化（见 TauntRadius），配置值会被量化到最近档位。",
                        "原默认 32 偏小，已抬到 48。")
                .defineInRange("tauntRadius", 48.0D, 16.0D, 160.0D);

        SCAN_INTERVAL_TICKS = b
                .comment("嘲讽扫描间隔（tick）。20 tick = 1 秒。",
                        "别设太小：每次扫描都要遍历范围内的实体。")
                .defineInRange("scanIntervalTicks", 20, 1, 200);

        KILL_ON_CONTACT = b
                .comment("是否开启「接触即抹杀」。")
                .define("killOnContact", true);

        KILL_RADIUS = b
                .comment("抹杀判定半径（格）。以方块中心为心的一个立方体半边长。",
                        "0.5 = 正好一个方块的体积；1.0 = 覆盖方块及其外扩 0.5 格（默认，手感更干脆）。",
                        "★ 必须大于 MoveToProtectTargetGoal 的「到达距离」(0.5 格) 的一半，",
                        "  否则敌人会在抹杀区外就停下，永远死不掉。")
                .defineInRange("killRadius", 1.0D, 0.05D, 8.0D);

        // ---------------- 抹杀范围 ----------------
        b.comment("抹杀范围：决定哪些实体会被保护目标抹杀。")
                .push("kill_scope");

        ONLY_HOSTILE = b
                .comment("只抹杀敌对生物（实现 Enemy 接口，或属于 MONSTER 类别的生物）。",
                        "默认 true —— 这样不会误伤动物、村民、铁傀儡。",
                        "设为 false 则「除玩家外的一切生物」都会被抹杀（含动物）。")
                .define("onlyHostile", true);

        DAMAGE_ALL_MOBS = b
                .comment("当 onlyHostile=false 时，是否连同玩家的宠物/仆从一起抹杀。",
                        "默认 true；设为 false 可放过已被驯服或由玩家召唤的生物。")
                .define("damageAllMobsWhenNotOnlyHostile", true);

        KILL_WHITELIST = b
                .comment("白名单：这里列出的实体类型【一定会】被抹杀（优先级最高）。",
                        "写法：完整 id，例如 \"minecraft:zombie\" 或 \"minecraft:zombified_piglin\"。")
                .defineListAllowEmpty("whitelist", new ArrayList<String>(),
                        o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

        KILL_BLACKLIST = b
                .comment("黑名单：这里列出的实体类型【一定不会】被抹杀（优先级高于白名单之外的一切规则）。",
                        "例：把 \"minecraft:villager\" 加进来，即使 onlyHostile=false 村民也不会死。")
                .defineListAllowEmpty("blacklist", new ArrayList<String>(),
                        o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

        b.pop();  // kill_scope
        b.pop();  // protect_target

        // ---------------- 棋子布设朝向 ----------------
        b.comment("棋子布设朝向：决定棋子放下时朝哪边，以及能不能用界面手动转。",
                "朝向直接影响「攻击范围」与「阻挡位置」（两者都随朝向旋转 90° 的整数倍）。")
                .push("pawn_facing");

        PAWN_FACING_MODE = b
                .comment("布设朝向方案（二选一）：",
                        "FIXED_EAST    方案1：所有棋子放下时一律朝【正东(+X)】，",
                        "              之后右键棋子打开朝向界面，用 +/- 每次顺时针/逆时针转 90°。",
                        "FOLLOW_PLAYER 方案2：按【放置瞬间玩家所面向的方向】设定朝向（吸附到最近的东南西北），",
                        "              此方案下不提供旋转界面，朝向放下即定。",
                        "注意：本开关只影响「放下时怎么定朝向」，不影响攻击范围、阻挡这些玩法逻辑。")
                .defineEnum("mode", PawnFacingMode.FIXED_EAST);

        b.pop();  // pawn_facing

        // ---------------- 阻挡 ----------------
        b.comment("阻挡：棋子把敌人「挡在身前」的规则（设计口径）。",
                "实现方式是【只改寻路目标 + 锁攻击目标】：棋子的战斗系统不碰敌人的坐标，",
                "敌人自己走到锚点、被击退后自己走回来（观感像撞到墙被弹开）。")
                .push("blocking");

        BLOCK_WHITELIST = b
                .comment("★ 白名单：**只有**这里列出的生物才会被棋子阻挡。",
                        "默认 [\"#monster\"] = 原版「怪物」这一整类（僵尸/骷髅/苦力怕/蜘蛛/史莱姆/",
                        "  幻翼/监守者…），也就是塔防里该被挡住的那些。要换口径就改这一行。",
                        "写法（大小写不敏感）：",
                        "  \"#monster\"               —— 按【刷怪类别】整类匹配（MobCategory，见下）",
                        "  \"minecraft:zombie\"       —— 某一个实体类型",
                        "  \"minecraft:zombified_*\"  —— 前缀通配（末尾 * 表示前缀匹配）",
                        "  \"minecraft:\"             —— 整个命名空间（等价于该命名空间下全部）",
                        "  \"*\"                     —— 所有实体（不建议：村民/牛羊也会被挡）",
                        "可用类别名（= MobCategory 的原版名）：monster / creature / ambient / axolotls /",
                        "  underground_water_creature / water_creature / water_ambient / misc。",
                        "★ 玩家属于 misc、村民/铁傀儡也属于 misc，所以 #monster 不会把「非怪物」算进来；",
                        "  硬性排除仍然不受白名单影响：玩家、棋子自己、已被驯服/有主人的生物。",
                        "★ 想「谁都不挡」就把它清空成 []（那时阻挡系统完全不介入，棋子只是站着输出）。")
                .defineListAllowEmpty("whitelist", new ArrayList<>(List.of("#monster")),
                        o -> o instanceof String s && !s.isBlank());

        b.pop();  // blocking

        SPEC = b.build();
    }

    private GuardianConfig() {
    }

    /** 保护目标最大生命值；<=0 表示不可摧毁。 */
    public static int targetHealth() {
        return TARGET_HEALTH.get();
    }

    public static boolean tauntEnabled() {
        return TAUNT_ENABLED.get();
    }

    public static double tauntRadius() {
        return TAUNT_RADIUS.get();
    }

    public static int scanIntervalTicks() {
        return SCAN_INTERVAL_TICKS.get();
    }

    public static boolean killOnContact() {
        return KILL_ON_CONTACT.get();
    }

    public static double killRadius() {
        return KILL_RADIUS.get();
    }

    /**
     * 棋子布设朝向方案。
     *
     * <p>读取这个值的时机有两处，都在服务端且都在「刚放下棋子」或「右键棋子」时，
     * 不在方块/实体的构造里 —— 配置在构造期可能还没加载好（踩过这个坑，
     * 见 {@code ProtectTargetBlock} 的注释）。</p>
     */
    public static PawnFacingMode pawnFacingMode() {
        return PAWN_FACING_MODE.get();
    }

    /**
     * 阻挡白名单（原始字符串列表；匹配规则见 {@code combat/BlockCandidates}）。
     *
     * <p>默认 {@code ["#monster"]}（= 原版「怪物」整类）。把它清空成 {@code []} 就回到
     * 「谁都不挡」——那时阻挡系统完全不介入，棋子只是站着输出。
     * 解析放在 combat 侧，配置这层只负责「把值读出来」——判据与配置分开，换判据不用动配置文件。</p>
     */
    public static List<? extends String> blockingWhitelist() {
        return BLOCK_WHITELIST.get();
    }

    /**
     * 判定某个实体是否应该被保护目标抹杀。
     *
     * <p>判定顺序（先排除、再准入，保证黑名单永远优先）：</p>
     * <ol>
     *     <li>玩家永远豁免（创造/旁观/生存都不杀）—— 保护目标是给敌人准备的，不是陷阱；</li>
     *     <li>黑名单命中 → 不杀；</li>
     *     <li>白名单命中 → 杀；</li>
     *     <li>否则按 onlyHostile 判定：敌对生物杀，其余不杀。</li>
     * </ol>
     */
    public static boolean shouldErase(Entity entity) {
        if (entity == null || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }
        EntityType<?> type = entity.getType();
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        String key = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);

        if (containsIgnoreCase(KILL_BLACKLIST.get(), key)) {
            return false;
        }
        if (containsIgnoreCase(KILL_WHITELIST.get(), key)) {
            return true;
        }
        if (ONLY_HOSTILE.get()) {
            return isHostile(entity);
        }
        // onlyHostile = false：除玩家外的一切生物都杀。
        // 关掉 damageAllMobsWhenNotOnlyHostile 时，放过已被驯服 / 有主人的生物（宠物、仆从）。
        if (!DAMAGE_ALL_MOBS.get()) {
            if (entity instanceof net.minecraft.world.entity.TamableAnimal tame && tame.isTame()) {
                return false;
            }
            if (entity instanceof net.minecraft.world.entity.OwnableEntity ownable
                    && ownable.getOwnerUUID() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 敌对生物判定：实现 {@link Enemy} 接口，或属于 MONSTER 类别。
     *
     * <p><b>这一处是「什么算敌人」的唯一实现</b>：抹杀判定（{@link #shouldErase}）
     * 与棋子的索敌（{@code combat/Targeting.isEnemy}）都走它。
     * 早先 {@code PawnCombatManager} 自己抄了一份同样的判断 —— 两份迟早会不一致，
     * 所以合并到这里（同源思想见 踩坑记录）。</p>
     */
    public static boolean isHostile(Entity entity) {
        return entity instanceof Enemy || entity.getType().getCategory() == MobCategory.MONSTER;
    }

    private static boolean containsIgnoreCase(List<? extends String> list, String key) {
        for (String s : list) {
            if (s != null && s.toLowerCase(Locale.ROOT).equals(key)) {
                return true;
            }
        }
        return false;
    }
}
