package com.guardianprotocol.data;

import com.guardianprotocol.GuardianProtocol;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分支注册表：全工程查「一个分支是谁」的唯一入口。
 *
 * <h3>两层结构</h3>
 * <ul>
 *     <li><b>内置层</b>：72 个分支，由 {@link UnitBranch} 枚举包装而来（不可变，编译期就有）；</li>
 *     <li><b>数据包层</b>：{@code data/<命名空间>/guardian_branches/*.json}，
 *         可<b>新增</b>分支，也可用 {@code "replaces"} <b>覆盖</b>内置分支的部分字段。
 *         覆盖是「整体替换一个 id 的定义」，不是往内置对象上打补丁。</li>
 * </ul>
 *
 * <h3>三条纪律</h3>
 * <ol>
 *     <li><b>查询一律走这里</b>（{@link #getOrNull} / {@link #require} / {@link #byKey}），
 *         不要自己 {@code UnitBranch.valueOf(...)} —— 那样永远看不到数据包的覆盖，
 *         表现是「数据包改了数值却不生效」，而且很难查。</li>
 *     <li><b>身份用 {@link ResourceLocation}，不用序号</b>：数据包一加分支，
 *         枚举 ordinal 与「第几个分支」就再也不是一回事。</li>
 *     <li><b>重载是整体替换</b>（{@link #applyDatapack}）：这样「删掉一个 JSON 文件」
 *         就真的删掉了那个分支，不需要逐条 diff。</li>
 * </ol>
 */
public final class BranchRegistry {

    /** 内置层：id → 包装好的内置分支。 */
    private static final Map<ResourceLocation, BranchDef> BUILTIN;

    /** 数据包层：id → JSON 分支（覆盖内置或新增）。volatile：重载在服务端线程，读取可能在别处。 */
    private static volatile Map<ResourceLocation, BranchDef> datapack = Map.of();

    /** 覆盖层版本号：每次重载 +1。给「按分支缓存结果」的地方做失效判断用。 */
    private static volatile int revision;

    static {
        Map<ResourceLocation, BranchDef> map = new LinkedHashMap<>();
        for (UnitBranch branch : UnitBranch.values()) {
            BuiltinBranch def = new BuiltinBranch(branch);
            map.put(def.id(), def);
        }
        BUILTIN = Map.copyOf(map);
    }

    private BranchRegistry() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 按 id 查；数据包覆盖优先。找不到返回 null。 */
    @Nullable
    public static BranchDef getOrNull(@Nullable ResourceLocation id) {
        if (id == null) {
            return null;
        }
        BranchDef fromDatapack = datapack.get(id);
        return fromDatapack != null ? fromDatapack : BUILTIN.get(id);
    }

    /** 按 id 查；找不到抛异常（用于「必须存在」的路径，早失败早发现）。 */
    public static BranchDef require(ResourceLocation id) {
        BranchDef def = getOrNull(id);
        if (def == null) {
            throw new IllegalArgumentException("不存在的分支：" + id);
        }
        return def;
    }

    /**
     * 按「存档/物品里写的那个字符串」查分支 —— 兼容三种写法：
     * <ol>
     *     <li>内置枚举名：{@code SNIPER_MARKSMAN}（**老存档里存的就是这个**，必须继续认）；</li>
     *     <li>完整 id：{@code guardian_protocol:sniper_marksman}；</li>
     *     <li>光秃秃的 path：{@code sniper_marksman}（按本 mod 命名空间补全）。</li>
     * </ol>
     * 找不到返回 null（调用方决定是兜底还是报错）。
     */
    @Nullable
    @SuppressWarnings("removal")   // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath（1.21 才加）
    public static BranchDef byKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String raw = key.trim();
        ResourceLocation parsed = ResourceLocation.tryParse(raw);
        if (parsed != null) {
            BranchDef def = getOrNull(parsed);
            if (def != null) {
                return def;
            }
        }
        // 老存档 / 物品 NBT 里的枚举名（大小写不敏感地兜一下）
        for (UnitBranch branch : UnitBranch.values()) {
            if (branch.name().equalsIgnoreCase(raw)) {
                return getOrNull(BuiltinBranch.idOf(branch));
            }
        }
        // 光 path：补上本 mod 命名空间（注意 ResourceLocation.tryParse("sniper_marksman")
        // 在 1.20.1 会返回 minecraft:sniper_marksman，查不到才会走到这里）
        //
        // ★ 只对**不含冒号的纯 path** 做这一步：带上命名空间的字符串已经在上面 tryParse 过、
        //   也查过了，再拼一次会构造出 `guardian_protocol:guardian_selftest:probe_sniper`
        //   这种非法 id 并**抛 ResourceLocationException** —— 而本方法的契约是「找不到返回 null」。
        //   实测踩过：数据包没被启用时，自验场景在这里直接抛异常（而不是打印「没被读到」）。
        //   这里的输入来自**老存档 / 物品 NBT / 数据包**，属于外部输入，只能降级不能抛。
        if (raw.indexOf(':') >= 0) {
            return null;
        }
        return getOrNull(new ResourceLocation(BuiltinBranch.NAMESPACE, raw.toLowerCase(Locale.ROOT)));
    }

    /** 全部分支（内置 + 数据包，覆盖后只留覆盖版）。顺序：内置在前，数据包补充在后。 */
    public static Collection<BranchDef> all() {
        Map<ResourceLocation, BranchDef> merged = new LinkedHashMap<>(BUILTIN);
        merged.putAll(datapack);
        return List.copyOf(merged.values());
    }

    /** 全部内置分支（刷怪蛋等「跟着表格走」的东西用它，数据包不该长出物品）。 */
    public static List<BuiltinBranch> builtins() {
        List<BuiltinBranch> out = new ArrayList<>(UnitBranch.values().length);
        for (UnitBranch branch : UnitBranch.values()) {
            out.add(new BuiltinBranch(branch));
        }
        return List.copyOf(out);
    }

    /** 数据包加进来的分支（不含覆盖内置的那些）。 */
    public static List<BranchDef> datapackAdditions() {
        List<BranchDef> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, BranchDef> e : datapack.entrySet()) {
            if (!BUILTIN.containsKey(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return List.copyOf(out);
    }

    /** 数据包覆盖掉的内置分支。 */
    public static List<BranchDef> datapackOverrides() {
        List<BranchDef> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, BranchDef> e : datapack.entrySet()) {
            if (BUILTIN.containsKey(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return List.copyOf(out);
    }

    public static boolean isBuiltinId(ResourceLocation id) {
        return BUILTIN.containsKey(id);
    }

    /**
     * 取某个职业的第一个内置分支 —— <b>只用于「分支 id 解析不出来」时的兜底</b>。
     *
     * <p>典型场景：多人游戏里客户端没装同一个数据包，于是它认不出
     * {@code 某数据包:某分支}。旗帜/贴图不会错（职业是单独同步的），
     * 错的只会是「攻击范围预览」—— 用同职业的默认范围顶一下，
     * 比退回「默认分支（冲锋手）的范围」靠谱得多。</p>
     */
    @Nullable
    public static BranchDef firstOfClass(UnitClass unitClass) {
        for (UnitBranch branch : UnitBranch.values()) {
            if (branch.unitClass() == unitClass) {
                return new BuiltinBranch(branch);
            }
        }
        return null;
    }

    /** 覆盖层版本号（每次数据包重载 +1）。 */
    public static int revision() {
        return revision;
    }

    // ------------------------------------------------------------------
    // 数据包层装载
    // ------------------------------------------------------------------

    /**
     * 用新解析出来的一整层替换掉旧的数据包层（由 {@code BranchReloadListener} 调用）。
     *
     * <p>整体替换而不是合并：数据包里删掉一个文件，那个分支就该消失。</p>
     *
     * <p>公开是为了让 {@code api/Branches} 能转发它（下游自己解析 JSON 的少见场景）；
     * 正常路径由资源重载监听器调用，不需要手装。</p>
     */
    public static void applyDatapack(Map<ResourceLocation, BranchDef> loaded) {
        datapack = Map.copyOf(loaded);
        revision++;
        int overrides = 0;
        for (ResourceLocation id : loaded.keySet()) {
            if (BUILTIN.containsKey(id)) {
                overrides++;
            }
        }
        GuardianProtocol.LOGGER.info("[{}] 职业分支数据包已装载：新增 {} 个、覆盖 {} 个（版本 {}）",
                GuardianProtocol.MODID, loaded.size() - overrides, overrides, revision);
    }

    /** 供自验/指令读当前数据包层的描述。 */
    public static String describeDatapack() {
        return "数据包分支层：新增 " + datapackAdditions().size()
                + " 个、覆盖 " + datapackOverrides().size() + " 个（版本 " + revision + "）";
    }
}
