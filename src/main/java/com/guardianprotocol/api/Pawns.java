package com.guardianprotocol.api;

import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.PieceFacing;
import com.guardianprotocol.entity.ModEntities;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「棋子」的公开接口：生成一个棋子、读场上所有棋子、读它的关键状态。
 *
 * <p>下游（角色导入 / 商店）需要的就是这一层：把「某个分支的棋子摆在某个坐标朝某个方向」，
 * 以及「场上现在有哪些棋子」。战斗本身不由下游驱动 —— 它在 core 的每 tick 循环里自动跑。</p>
 */
public final class Pawns {

    private Pawns() {
    }

    /**
     * 在指定位置生成一个棋子。
     *
     * <p><b>这是下游摆放棋子的正规入口</b>（不必依赖物品/刷怪蛋：那 72 个刷怪蛋只是
     * 本项目的占位与自验手段，见 设计说明第 7 条）。</p>
     *
     * <p>注意三点：① 必须在<b>服务端</b>调用；② 生成后棋子会自己按分支写好生命/攻击/护甲，
     * 不必也不该由调用方再设属性；③ 返回 {@code null} 表示实体创建失败（理论上不会发生），
     * 调用方应当当作「这次摆放失败」处理而不是继续。</p>
     *
     * @param level  服务端世界
     * @param pos    落点（会用方块中心：x+0.5 / z+0.5，y 按传入值）
     * @param branch 分支（内置或数据包分支都行）
     * @param facing 朝向：决定攻击范围与阻挡方向；不确定就给 {@link PieceFacing#EAST}
     */
    @Nullable
    public static PixelUnit spawn(ServerLevel level, Vec3 pos, BranchDef branch,
                                 @Nullable PieceFacing facing) {
        PixelUnit unit = ModEntities.PIXEL_UNIT.get().create(level);
        if (unit == null) {
            return null;
        }
        unit.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        unit.assign(branch);
        unit.setFacing(facing == null ? PieceFacing.EAST : facing);
        level.addFreshEntity(unit);
        // addFreshEntity 会跑 finalizeSpawn（可能把分支随机掉），所以再钉一次
        unit.assign(branch);
        unit.setFacing(facing == null ? PieceFacing.EAST : facing);
        return unit;
    }

    /** 当前已加载的全部棋子（只读快照）。 */
    public static List<PixelUnit> active() {
        return PixelUnit.activeUnits();
    }

    /** 场上棋子的数量（常用来判「这一波还能不能继续摆」）。 */
    public static int count() {
        return PixelUnit.activeUnits().size();
    }

    /** 棋子的分支（内置或数据包分支）。 */
    public static BranchDef branchOf(PixelUnit unit) {
        return unit.getBranch();
    }

    /** 棋子的朝向。 */
    public static PieceFacing facingOf(PixelUnit unit) {
        return unit.getFacing();
    }

    /** 棋子的阶位（1~6，自走棋那一层的数据；不影响战斗数值）。 */
    public static int tierOf(PixelUnit unit) {
        return unit.getTier();
    }

    /** 设置阶位（越界自动夹取）。 */
    public static void setTier(PixelUnit unit, int tier) {
        unit.setTier(tier);
    }

    /** 棋子是否已精炼（表现层会叠金色光效）。 */
    public static boolean isRefined(PixelUnit unit) {
        return unit.isRefined();
    }

    public static void setRefined(PixelUnit unit, boolean refined) {
        unit.setRefined(refined);
    }
}
