package com.guardianprotocol.menu;

import com.guardianprotocol.GuardianConfig;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 棋子朝向界面（服务端与客户端共用）。
 *
 * <p>只有「旋转」一个功能，两个按钮：{@code +} 顺时针 90°、{@code -} 逆时针 90°。</p>
 *
 * <h3>为什么沿用切比雪夫（容器）通道而不是自定义网络包</h3>
 * <p>与 {@link ProtectTargetMenu} 同样的理由：原版自带「服务端开界面 → 客户端建 Screen」
 * 与「按钮点击 → 服务端回调」两条现成通道，这条路够用，而且客户端无法伪造数据
 * （按钮只传固定 id，角度由服务端算）。</p>
 *
 * <p><b>订正（2026-10）</b>：这里原本写着「开发环境拿不到 Forge 的 {@code SimpleChannel}」——
 * 那句话是错的（{@code jar tf forge-...-universal.jar} 里就有，详见 踩坑记录的订正）。
 * 选容器的真实理由是**客户端无法伪造**与**省代码**，不是因为做不到。</p>
 *
 * <h3>实体怎么找回来</h3>
 * <p>保护目标界面传的是 {@code BlockPos}，这里传的是<b>实体 id</b>：
 * 由 {@code PixelUnit.mobInteract} 里的 {@code NetworkHooks.openScreen(..., buf -> buf.writeVarInt(id))}
 * 写出，客户端在 {@code ModMenus} 的工厂里 {@code readVarInt()} 读回，
 * 再在玩家当前世界里按 id 找回这个棋子。</p>
 */
public class PawnFacingMenu extends AbstractContainerMenu {

    /** 逆时针 90°。 */
    public static final int BUTTON_ROTATE_CCW = 0;
    /** 顺时针 90°。 */
    public static final int BUTTON_ROTATE_CW = 1;

    /** 界面允许的最大交互距离（格），沿用原版容器的 8 格习惯。 */
    private static final double MAX_DISTANCE_SQR = 64.0D;

    /** 目标棋子的实体 id；找不到实体时为 -1。 */
    private final int unitEntityId;
    /** 服务端持有实体引用，避免每次点按钮都重新查找。 */
    private PixelUnit unit;
    /** 玩家（用于校验与查找实体）。 */
    private final Player owner;

    /**
     * 统一构造：两端都用它。
     *
     * <p>客户端此时实体可能还没同步到，{@link #unit} 会是 null —— 读取方法都做了兜底。</p>
     */
    public PawnFacingMenu(int containerId, Inventory playerInventory, int unitEntityId) {
        super(ModMenus.PAWN_FACING.get(), containerId);
        this.unitEntityId = unitEntityId;
        this.owner = playerInventory.player;
        Entity found = this.owner.level().getEntity(unitEntityId);
        this.unit = found instanceof PixelUnit u ? u : null;
    }

    /** 当前棋子；可能为 null（实体还没同步过来 / 已经被移除）。 */
    public PixelUnit unit() {
        if (this.unit == null || this.unit.isRemoved()) {
            Entity found = this.owner.level().getEntity(this.unitEntityId);
            this.unit = found instanceof PixelUnit u ? u : null;
        }
        return this.unit;
    }

    /**
     * 按钮点击（服务端执行）。
     *
     * <p>只认两个固定 id，并且是在枚举内部 {@code clockwise()/counterClockwise()} 走一步，
     * 所以伪造的输入最多是「没变化」，不可能把朝向写成非法值。</p>
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        // 方案2 下不给旋转界面；这里再挡一道，防止旧界面残留或伪造调用
        if (!GuardianConfig.pawnFacingMode().allowsManualRotation()) {
            return false;
        }
        PixelUnit target = this.unit();
        if (target == null) {
            return false;
        }
        switch (buttonId) {
            case BUTTON_ROTATE_CW -> target.rotateClockwise();
            case BUTTON_ROTATE_CCW -> target.rotateCounterClockwise();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 界面里没有槽位，所以永远返回空。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** 棋子还在、没被拆、且玩家离得不远，界面才继续开着。 */
    @Override
    public boolean stillValid(Player player) {
        PixelUnit target = this.unit();
        return target != null && !target.isRemoved()
                && player.distanceToSqr(target) <= MAX_DISTANCE_SQR;
    }
}
