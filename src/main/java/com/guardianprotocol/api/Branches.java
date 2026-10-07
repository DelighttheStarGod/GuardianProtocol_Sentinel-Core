package com.guardianprotocol.api;

import com.guardianprotocol.data.AttackRange;
import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.BranchRegistry;
import com.guardianprotocol.data.BuiltinBranch;
import com.guardianprotocol.data.JsonBranch;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.data.UnitClass;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * 「职业模板」的公开查询接口（{@link BranchRegistry} 的门面）。
 *
 * <p>下游拿它做的是：按 id/枚举名取一个分支、读它的数值与特性、枚举全部内置分支，
 * 或者<b>注册自己从数据包读来的分支</b>（正常路径不需要：数据包由 core 自己加载，
 * 只有「下游自己解析了 JSON 想手装」这种特殊场景才用 {@link #applyDatapackLayer}）。</p>
 *
 * <p><b>不要</b>自己去 {@code UnitBranch.valueOf(...)}：那样看不到数据包的覆盖
 * （表现是「数据包改了数值却不生效」，见 踩坑记录一类）。</p>
 */
public final class Branches {

    private Branches() {
    }

    /** 按 id 取分支；不存在抛异常（早失败早发现）。 */
    public static BranchDef require(ResourceLocation id) {
        return BranchRegistry.require(id);
    }

    /** 按 id 取分支；不存在返回 null。 */
    @Nullable
    public static BranchDef getOrNull(@Nullable ResourceLocation id) {
        return BranchRegistry.getOrNull(id);
    }

    /**
     * 按「存档/物品里写的那种字符串」取分支，兼容三种写法：
     * 完整 id、**枚举名**（老存档）、光 path。取不到返回 null。
     */
    @Nullable
    public static BranchDef byKey(@Nullable String key) {
        return BranchRegistry.byKey(key);
    }

    /** 全部分支（内置 + 数据包，被覆盖的只留覆盖版）。 */
    public static Collection<BranchDef> all() {
        return BranchRegistry.all();
    }

    /** 72 个内置分支（要跟着参考表走的东西 —— 例如给每个分支生成一个物品 —— 用它）。 */
    public static List<BuiltinBranch> builtins() {
        return BranchRegistry.builtins();
    }

    /** 8 大职业。 */
    public static List<UnitClass> classes() {
        return List.of(UnitClass.values());
    }

    /** 数据包新增的分支。 */
    public static List<BranchDef> datapackAdditions() {
        return BranchRegistry.datapackAdditions();
    }

    /** 数据包覆盖掉的内置分支。 */
    public static List<BranchDef> datapackOverrides() {
        return BranchRegistry.datapackOverrides();
    }

    /** 数据包层的状态描述（排查用）：新增/覆盖各几个、当前版本号。 */
    public static String datapackSummary() {
        return BranchRegistry.describeDatapack();
    }

    /** 一个分支的打击格（相对自身格的 x/z 偏移）；数据包分支也一样。 */
    public static List<AttackRange.Cell> cellsOf(BranchDef branch) {
        return branch.attackCells();
    }

    /** 内置分支对应的枚举；数据包分支返回 empty。 */
    public static java.util.Optional<UnitBranch> builtinOf(BranchDef branch) {
        return branch.builtin();
    }

    /**
     * 覆盖层版本号：每次数据包重载 +1。
     *
     * <p>下游若有「按分支缓存的结果」，用它在重载后失效缓存 —— 否则
     * {@code /reload} 之后你手里的还是旧数值。</p>
     */
    public static int revision() {
        return BranchRegistry.revision();
    }

    /**
     * 手动装一整层数据包分支（**整体替换**，不是合并）。
     *
     * <p>正常路径用不到：core 自己挂了资源重载监听器。这个入口是给
     * 「下游自己解析了 {@link JsonBranch} 想直接装」的场景留的，调用方自己保证线程。</p>
     */
    public static void applyDatapackLayer(java.util.Map<ResourceLocation, BranchDef> loaded) {
        BranchRegistry.applyDatapack(loaded);
    }
}
