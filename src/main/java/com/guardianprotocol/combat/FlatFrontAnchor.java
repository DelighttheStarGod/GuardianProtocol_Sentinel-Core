package com.guardianprotocol.combat;

import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 锚点策略的<b>占位实现</b>：棋子正前方 {@link BlockGeometry#ANCHOR_FORWARD} 格，
 * 多个敌人按名次横向错开 {@link BlockGeometry#ANCHOR_LATERAL_STEP} 格。
 *
 * <h3>它只保证「平坦地形上观感正确」</h3>
 * <p>设计约定：真正的锚点选择要结合地形与关卡设计（台阶、坡道、锚点被方块挡住、
 * 多波次重置、与保护目标的距离关系），那属于塔防循环项目。这一版只把
 * 「并排挤在棋子前面」这件事做出来：1 个居中，3 个是左/中/右（±0.3），
 * 2 个对称落在 ±0.15（见 {@link BlockGeometry#lateralOffset}）。</p>
 *
 * <h3>已知局限（写出来，免得后人当 bug 查）</h3>
 * <ul>
 *     <li><b>不做可达性检查</b>：锚点若落在墙里/悬空，敌人的寻路会失败或绕远 ——
 *         表现是「它不去那个位置」，而不是报错。这正是塔防循环项目要负责的部分；</li>
 *     <li><b>不看地形高度</b>：锚点 y 直接取棋子所在方块的 y（平坦地面成立，台阶上会偏一层）；</li>
 *     <li><b>不感知保护目标</b>：锚点与保护目标之间该保持什么距离，留给后续项目定。</li>
 * </ul>
 */
public final class FlatFrontAnchor implements BlockAnchorStrategy {

    /** 单例：它没有任何状态，反复 new 只会让「换了哪个策略」更难看清。 */
    public static final FlatFrontAnchor INSTANCE = new FlatFrontAnchor();

    private FlatFrontAnchor() {
    }

    @Nullable
    @Override
    public Vec3 anchor(ServerLevel level, PixelUnit unit, LivingEntity target, int slot, int total) {
        BlockPos pos = unit.blockPosition();
        double[] off = BlockGeometry.anchorOffset(unit.getFacing(), slot, total);
        return new Vec3(pos.getX() + off[0], pos.getY(), pos.getZ() + off[1]);
    }

    @Override
    public String name() {
        return "平地占位：正前方 " + BlockGeometry.ANCHOR_FORWARD + " 格 + 名次横向错开 "
                + BlockGeometry.ANCHOR_LATERAL_STEP + " 格";
    }
}
