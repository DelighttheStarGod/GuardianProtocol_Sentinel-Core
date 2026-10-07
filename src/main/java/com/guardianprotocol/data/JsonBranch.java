package com.guardianprotocol.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 数据包分支：从 JSON 读出来的「追加 / 覆盖」分支。
 *
 * <h3>JSON 长什么样</h3>
 * <pre>{@code
 * // data/<命名空间>/guardian_branches/<名字>.json
 * {
 *   "replaces": "guardian_protocol:sniper_marksman",   // 可选：覆盖已有分支（缺省 = 新增）
 *   "class": "sniper",                                  // 职业（新增时必填；覆盖时缺省继承）
 *   "name": "自定义狙击",                                // 分支名（新增时必填）
 *   "block": 0,                                         // 阻挡数
 *   "attack_interval": 1.5,                             // 基础攻击间隔（秒），0 = 不攻击
 *   "stats": {"max_health": 14, "attack_damage": 6, "armor": 1},
 *   "targeting": {"priority": "lowest_armor", "count": "single", "attack": true},
 *   "vertical": "both",                                 // both / air_only / ground_only / ranged
 *   "attack_method": "projectile",                      // melee / projectile；缺省按 vertical 推
 *   "block_search": "foot",                             // foot / surrounding；缺省 foot
 *   "range": ["0,1", "0,2", "0,3"],                     // 打击格（相对自身格的 x,z）；缺省继承
 *   "range_id": "sniper_3x4_..."                        // 或直接引用内置范围键，二选一
 * }
 * }</pre>
 *
 * <h3>三条刻意选择</h3>
 * <ol>
 *     <li><b>「缺字段」与「字段写错」区别对待</b>：缺字段 = 继承（覆盖时）或用默认值（新增时）；
 *         写错（枚举名不认识、类型不对）= <b>抛异常</b>，由加载器跳过这个文件并打日志。
 *         若把写错也当缺省，玩家会得到一个「静默不对」的分支，比直接报错难查一百倍。</li>
 *     <li><b>解析期就把值定死</b>（不在 getter 里查表）：加载时报错的位置最清楚，
 *         也避免每次索敌都做一次字符串比较。</li>
 *     <li><b>{@code attackCells()} 兜底到职业默认范围</b>：数据包漏写范围时，
 *         结果是一个「能正常打人」的棋子，而不是站着不动的摆设。</li>
 * </ol>
 */
public final class JsonBranch implements BranchDef {

    private final ResourceLocation id;
    private final String key;
    private final boolean replaces;
    private final UnitClass unitClass;
    private final String branchName;
    private final String attackRangeKey;
    private final List<AttackRange.Cell> attackCells;
    private final double maxHealth;
    private final double attackDamage;
    private final double armor;
    private final int blockCount;
    private final double attackIntervalSeconds;
    private final UnitBranch.TargetPriority targetPriority;
    private final UnitBranch.TargetCount targetCount;
    private final boolean canAttack;
    private final UnitBranch.VerticalTargeting verticalTargeting;
    private final UnitBranch.AttackMethod attackMethod;
    private final UnitBranch.BlockSearch blockSearch;

    private JsonBranch(ResourceLocation id, String key, boolean replaces, UnitClass unitClass,
                       String branchName, String attackRangeKey, List<AttackRange.Cell> attackCells,
                       double maxHealth, double attackDamage, double armor, int blockCount,
                       double attackIntervalSeconds, UnitBranch.TargetPriority targetPriority,
                       UnitBranch.TargetCount targetCount, boolean canAttack,
                       UnitBranch.VerticalTargeting verticalTargeting,
                       UnitBranch.AttackMethod attackMethod,
                       UnitBranch.BlockSearch blockSearch) {
        this.id = id;
        this.key = key;
        this.replaces = replaces;
        this.unitClass = unitClass;
        this.branchName = branchName;
        this.attackRangeKey = attackRangeKey;
        this.attackCells = List.copyOf(attackCells);
        this.maxHealth = maxHealth;
        this.attackDamage = attackDamage;
        this.armor = armor;
        this.blockCount = blockCount;
        this.attackIntervalSeconds = attackIntervalSeconds;
        this.targetPriority = targetPriority;
        this.targetCount = targetCount;
        this.canAttack = canAttack;
        this.verticalTargeting = verticalTargeting;
        this.attackMethod = attackMethod;
        this.blockSearch = blockSearch;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析一个 JSON 对象。
     *
     * @param id   该文件对应的分支 id（{@code replaces} 的目标 id，或「命名空间 + 文件名」）
     * @param key  写进 NBT / 语言键的短名：覆盖内置分支时沿用被覆盖者的 key（老存档才认得），
     *             新增分支用 id 的 path
     * @param json 文件内容
     * @param base 被覆盖的分支；新增时为 null（决定「缺字段」怎么兜底）
     * @throws IllegalArgumentException 字段写错或新增时缺必填字段（加载器会跳过并打日志）
     */
    public static JsonBranch parse(ResourceLocation id, String key, JsonObject json, BranchDef base) {
        boolean replaces = base != null;

        String classKey = string(json, "class", base == null ? null : base.unitClass().spriteId());
        if (classKey == null) {
            throw new IllegalArgumentException("新增分支必须写 class（职业）");
        }
        UnitClass unitClass = UnitClass.byName(classKey);
        if (unitClass == null) {
            throw new IllegalArgumentException("不认识的职业 class=" + classKey
                    + "，可选：" + classIds());
        }

        String name = string(json, "name", base == null ? null : base.branchName());
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("分支名 name 不能为空");
        }

        int block = integer(json, "block", base == null ? 0 : base.blockCount());
        if (block < 0) {
            throw new IllegalArgumentException("block（阻挡数）不能为负：" + block);
        }

        double interval = decimal(json, "attack_interval",
                base == null ? 0.0D : base.attackIntervalSeconds());
        if (interval < 0.0D) {
            throw new IllegalArgumentException("attack_interval（攻击间隔秒）不能为负：" + interval);
        }

        JsonObject stats = object(json, "stats");
        double hp = decimal(stats, "max_health", base == null ? -1.0D : base.maxHealth());
        double atk = decimal(stats, "attack_damage", base == null ? -1.0D : base.attackDamage());
        double arm = decimal(stats, "armor", base == null ? 0.0D : base.armor());
        if (base == null && (hp < 0.0D || atk < 0.0D)) {
            throw new IllegalArgumentException(
                    "新增分支必须写 stats.max_health 与 stats.attack_damage（缺省值会做出 0 血/0 攻的棋子）");
        }
        // 下限与 CombatStats 同口径：血量至少 1（否则放出来就死），攻击/护甲不为负
        hp = Math.max(1.0D, hp);
        atk = Math.max(0.0D, atk);
        arm = Math.max(0.0D, arm);

        JsonObject targeting = object(json, "targeting");
        UnitBranch.TargetPriority priority = priority(targeting, "priority",
                base == null ? UnitBranch.TargetPriority.NEAREST : base.targetPriority());
        UnitBranch.TargetCount count = count(targeting, "count",
                base == null ? UnitBranch.TargetCount.SINGLE : base.targetCount());
        boolean canAttack = bool(targeting, "attack", base == null || base.canAttack());

        UnitBranch.VerticalTargeting vertical = vertical(json, "vertical",
                base == null ? null : base.verticalTargeting());
        if (vertical == null) {
            throw new IllegalArgumentException(
                    "新增分支必须写 vertical（对空口径），可选 both / air_only / ground_only / ranged");
        }

        // 攻击方式（投掷物 / 近战武器）：缺省继承 base；新增分支按出厂口径兜底 ——
        // 「远程位 = 投掷物，近战位 = 近战武器」。内置分支那 6 个「近战位但远程」的特例
        // 由生成链写进枚举，数据包若要复刻同样的分支，就得显式写 attack_method。
        UnitBranch.AttackMethod attackMethod = attackMethod(json, "attack_method",
                base == null
                        ? (vertical == UnitBranch.VerticalTargeting.RANGED
                                ? UnitBranch.AttackMethod.PROJECTILE
                                : UnitBranch.AttackMethod.MELEE)
                        : base.attackMethod());

        // 阻挡搜索范围：缺省继承 base；新增分支默认 foot（只找脚下那一格，与内置口径一致）。
        UnitBranch.BlockSearch blockSearch = blockSearch(json, "block_search",
                base == null ? UnitBranch.BlockSearch.FOOT : base.blockSearch());

        // 范围：range_id 优先，其次 range 数组，最后继承 base / 职业默认
        String rangeId = string(json, "range_id", base == null ? null : base.attackRangeKey());
        List<AttackRange.Cell> cells = parseCells(json.get("range"));
        if (cells.isEmpty() && base != null && json.get("range") == null && json.get("range_id") == null) {
            cells = base.attackCells();
        }
        if (cells.isEmpty()) {
            cells = AttackRange.resolve(rangeId);
        }
        if (cells.isEmpty()) {
            cells = AttackRange.resolve(unitClass.defaultRangeKey());
        }

        return new JsonBranch(id, key, replaces, unitClass, name, rangeId, cells,
                hp, atk, arm, block, interval, priority, count, canAttack, vertical,
                attackMethod, blockSearch);
    }

    /** 报错信息里列出可选职业（用 JSON 里能写的那个短名）。 */
    private static String classIds() {
        StringBuilder sb = new StringBuilder();
        for (UnitClass c : UnitClass.values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(c.spriteId());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 字段读取小工具（缺省 = 用 fallback；类型错 = 抛异常）
    // ------------------------------------------------------------------

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return new JsonObject();
        }
        JsonElement e = parent.get(key);
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException(key + " 必须是对象");
        }
        return e.getAsJsonObject();
    }

    private static String string(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " 必须是字符串");
        }
        String v = e.getAsString().trim();
        return v.isEmpty() ? fallback : v;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " 必须是数字");
        }
        return e.getAsInt();
    }

    private static double decimal(JsonObject json, String key, double fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " 必须是数字");
        }
        return e.getAsDouble();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " 必须是 true/false");
        }
        return e.getAsBoolean();
    }

    private static UnitBranch.TargetPriority priority(JsonObject json, String key,
                                                      UnitBranch.TargetPriority fallback) {
        String raw = string(json, key, null);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "nearest" -> UnitBranch.TargetPriority.NEAREST;
            case "lowest_armor", "lowestarmor" -> UnitBranch.TargetPriority.LOWEST_ARMOR;
            case "heaviest" -> UnitBranch.TargetPriority.HEAVIEST;
            case "air_first", "airfirst" -> UnitBranch.TargetPriority.AIR_FIRST;
            case "air_only", "aironly" -> UnitBranch.TargetPriority.AIR_ONLY;
            default -> throw new IllegalArgumentException("targeting.priority 不认识：" + raw
                    + "（可选 nearest / lowest_armor / heaviest / air_first / air_only）");
        };
    }

    private static UnitBranch.TargetCount count(JsonObject json, String key,
                                                UnitBranch.TargetCount fallback) {
        String raw = string(json, key, null);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "single" -> UnitBranch.TargetCount.SINGLE;
            case "multi_blocked", "multiblocked" -> UnitBranch.TargetCount.MULTI_BLOCKED;
            case "multi_all", "multiall" -> UnitBranch.TargetCount.MULTI_ALL;
            default -> throw new IllegalArgumentException("targeting.count 不认识：" + raw
                    + "（可选 single / multi_blocked / multi_all）");
        };
    }

    private static UnitBranch.VerticalTargeting vertical(JsonObject json, String key,
                                                         UnitBranch.VerticalTargeting fallback) {
        String raw = string(json, key, null);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "ranged" -> UnitBranch.VerticalTargeting.RANGED;
            case "both", "air_and_ground" -> UnitBranch.VerticalTargeting.AIR_AND_GROUND;
            case "ground_only", "ground" -> UnitBranch.VerticalTargeting.GROUND_ONLY;
            // 「只打空中」= 对地盲区：本工程用 AIR_ONLY 的索敌优先级表达（见 Targeting.requiresAir），
            // 垂直轴这里给 AIR_AND_GROUND，两轴配合才等价于「只打空中」。
            case "air_only", "air" -> UnitBranch.VerticalTargeting.AIR_AND_GROUND;
            default -> throw new IllegalArgumentException("vertical 不认识：" + raw
                    + "（可选 both / air_only / ground_only / ranged）");
        };
    }

    /**
     * 解析 {@code attack_method}：{@code melee}（近战武器）/ {@code projectile}（发射投掷物）。
     *
     * <p>取值不认识一律抛异常，不退回默认 —— 数据包写错一个字，玩家看到的是
     * 「这个分支的攻击方式不对」，而不会报任何错（同 踩坑记录的口径）。</p>
     */
    private static UnitBranch.AttackMethod attackMethod(JsonObject json, String key,
                                                        UnitBranch.AttackMethod fallback) {
        String raw = string(json, key, null);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "melee" -> UnitBranch.AttackMethod.MELEE;
            case "projectile" -> UnitBranch.AttackMethod.PROJECTILE;
            default -> throw new IllegalArgumentException("attack_method 不认识：" + raw
                    + "（可选 melee / projectile）");
        };
    }

    /**
     * 解析 {@code block_search}：{@code foot}（只找脚下那一格）/ {@code surrounding}（周围九格）。
     *
     * <p>九宫格只对「真的会挡人」的分支有意义：阻挡数为 0 的分支写了 surrounding，
     * 这里直接抛异常 —— 那是一个永远不会生效的设置，静默接受等于把数据错误藏起来
     * （同 踩坑记录的口径）。</p>
     */
    private static UnitBranch.BlockSearch blockSearch(JsonObject json, String key,
                                                      UnitBranch.BlockSearch fallback) {
        String raw = string(json, key, null);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "foot" -> UnitBranch.BlockSearch.FOOT;
            case "surrounding" -> UnitBranch.BlockSearch.SURROUNDING;
            default -> throw new IllegalArgumentException("block_search 不认识：" + raw
                    + "（可选 foot / surrounding）");
        };
    }

    /**
     * 解析 {@code "range": ["0,1", "0,2", ...]}。
     *
     * <p>坐标是**相对自身所在格**的偏移，与本工程 {@link AttackRange.Cell} 同口径：
     * 第一个数是 x（东正西负），第二个是 z（南正北负）。乱写的条目直接报错，
     * 因为「范围差一格」这种错误在游戏里几乎看不出来。</p>
     */
    private static List<AttackRange.Cell> parseCells(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("range 必须是数组，形如 [\"0,1\",\"0,2\"]");
        }
        JsonArray array = element.getAsJsonArray();
        List<AttackRange.Cell> cells = new ArrayList<>(array.size());
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("range 的每一项都必须是字符串，形如 \"0,1\"");
            }
            String raw = e.getAsString().trim();
            String[] parts = raw.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("range 的项必须是 x,z 两段：" + raw);
            }
            try {
                cells.add(new AttackRange.Cell(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("range 的项不是整数：" + raw);
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------
    // BranchDef
    // ------------------------------------------------------------------

    @Override
    public ResourceLocation id() {
        return this.id;
    }

    @Override
    public String key() {
        return this.key;
    }

    @Override
    public boolean isBuiltin() {
        return false;
    }

    @Override
    public Optional<UnitBranch> builtin() {
        return Optional.empty();
    }

    /** 这条定义是「覆盖已有分支」还是「新增分支」（排查用）。 */
    public boolean replaces() {
        return this.replaces;
    }

    @Override
    public UnitClass unitClass() {
        return this.unitClass;
    }

    @Override
    public String branchName() {
        return this.branchName;
    }

    @Override
    public String attackRangeKey() {
        return this.attackRangeKey;
    }

    @Override
    public List<AttackRange.Cell> attackCells() {
        return this.attackCells;
    }

    @Override
    public double maxHealth() {
        return this.maxHealth;
    }

    @Override
    public double attackDamage() {
        return this.attackDamage;
    }

    @Override
    public double armor() {
        return this.armor;
    }

    @Override
    public int blockCount() {
        return this.blockCount;
    }

    @Override
    public double attackIntervalSeconds() {
        return this.attackIntervalSeconds;
    }

    @Override
    public UnitBranch.TargetPriority targetPriority() {
        return this.targetPriority;
    }

    @Override
    public UnitBranch.TargetCount targetCount() {
        return this.targetCount;
    }

    @Override
    public boolean canAttack() {
        return this.canAttack;
    }

    /** 数据包分支没有表格原文依据，固定给一句话，便于在报告里一眼区分来源。 */
    @Override
    public String targetRuleSource() {
        return this.replaces ? "数据包覆盖（" + this.id + "）" : "数据包新增（" + this.id + "）";
    }

    @Override
    public UnitBranch.VerticalTargeting verticalTargeting() {
        return this.verticalTargeting;
    }

    @Override
    public UnitBranch.AttackMethod attackMethod() {
        return this.attackMethod;
    }

    @Override
    public UnitBranch.BlockSearch blockSearch() {
        return this.blockSearch;
    }

    /** 与 {@link #attackMethodSource()} 同口径：数据包分支固定给一句话，标明来源。 */
    @Override
    public String blockSearchSource() {
        return this.replaces ? "数据包覆盖（" + this.id + "）" : "数据包新增（" + this.id + "）";
    }

    /** 与 {@link #targetRuleSource()} 同口径：数据包分支固定给一句话，标明来源。 */
    @Override
    public String attackMethodSource() {
        return this.replaces ? "数据包覆盖（" + this.id + "）" : "数据包新增（" + this.id + "）";
    }

    @Override
    public String toString() {
        return "JsonBranch[" + this.id + (this.replaces ? " replaces" : " new") + "]";
    }
}
