package com.guardianprotocol.client;

import com.guardianprotocol.data.AttackRange;
import com.guardianprotocol.entity.PixelUnit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 攻击范围预览：当鼠标准心对准某个棋子时，在世界上画出它的攻击范围格子。
 *
 * <h3>触发方式</h3>
 * <p>用 {@link RenderHighlightEvent.Entity} —— 这个事件<b>只在准星命中实体的那一帧</b>触发，
 * 正好就是「鼠标准心对准棋子」这个条件，不需要自己每帧做射线检测。</p>
 *
 * <p>同时用 {@link TickEvent.ClientTickEvent} 记住「最近一次对准的棋子」，
 * 这样准星移开后范围会再保留一小会儿（{@link #KEEP_TICKS} tick = 1 秒），
 * 避免鼠标轻微抖动就闪一下。</p>
 *
 * <h3>为什么不用 RenderLevelStageEvent</h3>
 * <p>{@code RenderLevelStageEvent} <b>不提供 MultiBufferSource</b>，拿不到画线用的 buffer
 * （它只给 poseStack / projectionMatrix / camera）。而 {@link RenderHighlightEvent}
 * 直接给了 {@code getMultiBufferSource()} 与 {@code getPoseStack()}，
 * 且触发时机与「准星瞄准」天然一致，所以选它。</p>
 *
 * <h3>画法</h3>
 * <p>对攻击范围的每个格子画一个 {@link LevelRenderer#renderLineBox} 线框。
 * 「自己所在格」用另一种颜色，这样一眼能看出范围朝向
 * （前方 = 棋子当前朝向，见 {@code PixelUnit.getFacing()}）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AttackRangePreview {

    /** 准星离开后，范围继续显示的 tick 数（20 tick = 1 秒）。 */
    private static final int KEEP_TICKS = 20;

    /** 可攻击格子的线框颜色（青蓝）。 */
    private static final float[] COLOR_RANGE = {0.35F, 0.75F, 1.0F};
    /** 自己所在格的颜色（暖黄），便于确认朝向。 */
    private static final float[] COLOR_SELF = {1.0F, 0.85F, 0.35F};

    /** 最近一次被准星对准的棋子。 */
    @Nullable
    private static PixelUnit lastTargeted;
    /** 剩余保持显示的 tick 数。 */
    private static int keepTicks;

    private AttackRangePreview() {
    }

    /**
     * 准星命中实体时记录棋子。
     *
     * <p>由 {@link ClientSetup} 注册到游戏事件总线（FORGE）。</p>
     */
    @SubscribeEvent
    public static void onRenderHighlight(RenderHighlightEvent.Entity event) {
        if (!(event.getTarget().getEntity() instanceof PixelUnit unit)) {
            return;
        }
        // 命名牌跟着准星走：只显示「正在被指着」的那一个，换目标时把上一个收起来。
        // （设计要求：头顶文字平时不显示，否则几个棋子摆一起就糊住视线。）
        if (lastTargeted != null && lastTargeted != unit && !lastTargeted.isRemoved()) {
            lastTargeted.setNameRevealed(false);
        }
        unit.setNameRevealed(true);
        lastTargeted = unit;
        keepTicks = KEEP_TICKS;

        // 直接在这一帧画出来：event 已经给了 poseStack（含相机偏移）与 buffer。
        renderRange(event.getPoseStack(), event.getMultiBufferSource(), event.getCamera(), unit);
        // 立刻提交，否则线段可能被后续渲染覆盖/延迟到下一帧
        if (event.getMultiBufferSource() instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch(RenderType.lines());
        }
    }

    /** 客户端每 tick 递减保持计时。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (keepTicks > 0 && --keepTicks == 0) {
            // 准星移开、保持时间也过了：把命名牌一起收起来，和范围框同进同退
            if (lastTargeted != null && !lastTargeted.isRemoved()) {
                lastTargeted.setNameRevealed(false);
            }
            lastTargeted = null;
        }
    }

    /**
     * 画出某个棋子的攻击范围。
     *
     * <p>复用 {@link RenderHighlightEvent} 给的 poseStack 与 buffer：
     * 此时 poseStack 已经带了相机偏移，所以世界坐标可以直接用。</p>
     */
    public static void renderRange(PoseStack poseStack, MultiBufferSource buffers,
                                  Camera camera, PixelUnit unit) {
        // ★ 用 worldCells()：已按棋子朝向旋转到世界偏移，与服务端索敌用的是同一份数据，
        //   所以预览画出来的格子就是真正会挨打的格子。
        List<AttackRange.Cell> cells = unit.worldCells();
        if (cells.isEmpty()) {
            return;
        }
        BlockPos origin = unit.blockPosition();
        Vec3 cam = camera.getPosition();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        // 先画「自己所在格」，让朝向一目了然
        drawCell(lines, poseStack, origin, cam, COLOR_SELF);

        for (AttackRange.Cell cell : cells) {
            // ★ 自身格（世界偏移 0,0）已经用自身色画过一次了，这里必须跳过。
            //   修好 toWorldOffset 漏掉的 +1 之后，自身格才真正落在棋子脚下、
            //   与上面那个自身色方框**完全重合**；不跳过的话黄蓝两条线会重叠闪烁。
            if (cell.x() == 0 && cell.z() == 0) {
                continue;
            }
            BlockPos p = origin.offset(cell.x(), 0, cell.z());
            drawCell(lines, poseStack, p, cam, COLOR_RANGE);
        }
    }

    /** 贴地高度偏移（格）：抬 1cm，避免与原版地面/方块表面抢深度（Z-fighting）。 */
    private static final double GROUND_LIFT = 0.01D;

    /** 贴地方框的「厚度」。不取 0 是为了避免退化成零长度线段（法线会算出 0/0）。 */
    private static final double GROUND_THICKNESS = 0.02D;

    /**
     * 画一个格子的<b>贴地</b>方框。
     *
     * <p>★ 为什么不画成立方体（改过一次）：原来每格画一整条 1 格高的立体线框，
     * 十几个立方体的竖边在斜视角下互相穿插，看上去就是一团乱线，
     * 反而看不出攻击范围的形状。攻击范围本来就是<b>地面上</b>的格子，
     * 压成贴地的扁方框之后就只剩俯视能看到的四条边，干净得多。</p>
     */
    private static void drawCell(VertexConsumer lines, PoseStack poseStack, BlockPos pos,
                                 Vec3 cam, float[] rgb) {
        double inset = 0.02D;
        double y0 = pos.getY() + GROUND_LIFT;
        AABB box = new AABB(
                pos.getX() + inset, y0, pos.getZ() + inset,
                pos.getX() + 1.0D - inset, y0 + GROUND_THICKNESS, pos.getZ() + 1.0D - inset)
                .move(-cam.x, -cam.y, -cam.z);
        LevelRenderer.renderLineBox(poseStack, lines, box, rgb[0], rgb[1], rgb[2], 1.0F);
    }

    /** 当前需要显示范围的棋子；没有则返回 null。 */
    @Nullable
    public static PixelUnit currentTarget() {
        if (lastTargeted == null || lastTargeted.isRemoved()) {
            return null;
        }
        return lastTargeted;
    }
}
