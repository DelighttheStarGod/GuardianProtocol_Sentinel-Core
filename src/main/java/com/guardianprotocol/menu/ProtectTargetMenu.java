package com.guardianprotocol.menu;

import com.guardianprotocol.block.ProtectTargetBlockEntity;
import com.guardianprotocol.block.TauntRadius;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 保护目标的设置界面（服务端与客户端共用这个容器）。
 *
 * <p><b>为什么用 {@link AbstractContainerMenu} 而不是「纯客户端 Screen + 自定义网络包」</b>：</p>
 * <ul>
 *     <li>原版已经提供了「服务端打开界面 → 客户端自动创建 Screen」的通道
 *         （{@code NetworkHooks.openScreen} + {@code MenuScreens.register}），
 *         不需要自己写网络代码（订正见下）。</li>
 *     <li>按钮点击有原版现成的 {@code clickMenuButton} 回调：客户端点按钮 → 原版帮我发
 *         一个标准包到服务端 → 服务端在这里改数据。<b>不用自己发包，也就没法被客户端伪造
 *         成"把半径改成 999"</b>（服务端只接受档位序号，而且会 clamp）。</li>
 * </ul>
 *
 * <p>界面里没有物品槽位，所以 {@link #quickMoveStack} 返回空、{@link #stillValid}
 * 只校验玩家没走远、方块还在。</p>
 *
 * <p><b>订正（2026-10）</b>：本条原本的理由写的是「开发环境拿不到 {@code SimpleChannel}」，
 * 那句话是错的（复核：{@code jar tf forge-...-universal.jar} 里有
 * {@code net/minecraftforge/network/simple/SimpleChannel.class}，见 踩坑记录的订正）。
 * 选菜单的真实理由是上面第 2 条：<b>按钮走原版 {@code clickMenuButton}，客户端无法伪造成任意值</b>
 * —— 这比「做不到自定义网络」强得多。</p>
 */
public class ProtectTargetMenu extends AbstractContainerMenu {

    /** 按钮 id。 */
    public static final int BUTTON_RADIUS_DOWN = 0;
    public static final int BUTTON_RADIUS_UP = 1;
    public static final int BUTTON_RADIUS_RESET = 2;

    private final ProtectTargetBlockEntity blockEntity;

    /** 客户端创建用（由 MenuType 调用，pos 来自 openScreen 的额外数据）。 */
    public ProtectTargetMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.PROTECT_TARGET.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof ProtectTargetBlockEntity t ? t : null;
    }

    /** 服务端创建用。 */
    public ProtectTargetMenu(int containerId, Inventory playerInventory, ProtectTargetBlockEntity blockEntity) {
        super(ModMenus.PROTECT_TARGET.get(), containerId);
        this.blockEntity = blockEntity;
    }

    /** 可能为 null（方块在界面开着的时候被拆了/区块卸载）。所有读取都要判空。 */
    public ProtectTargetBlockEntity blockEntity() {
        return this.blockEntity;
    }

    /** 当前嘲讽半径档位；方块没了就返回默认档，避免 NPE 崩界面。 */
    public TauntRadius currentRadius() {
        if (this.blockEntity == null || this.blockEntity.getLevel() == null) {
            return TauntRadius.DEFAULT;
        }
        return this.blockEntity.getLevel().getBlockState(this.blockEntity.getBlockPos())
                .getOptionalValue(TauntRadius.PROPERTY)
                .orElse(TauntRadius.DEFAULT);
    }

    /**
     * 按钮点击（服务端执行）。
     *
     * <p>只接受三个固定的按钮 id，并且用 {@code next()/previous()} 在枚举内部走档，
     * 所以越界或伪造的输入最多只是「没变化」，不会写入非法值。</p>
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (this.blockEntity == null || this.blockEntity.getLevel() == null) {
            return false;
        }
        TauntRadius current = this.currentRadius();
        TauntRadius next = switch (buttonId) {
            case BUTTON_RADIUS_DOWN -> current.previous();
            case BUTTON_RADIUS_UP -> current.next();
            case BUTTON_RADIUS_RESET -> TauntRadius.DEFAULT;
            default -> null;
        };
        if (next == null || next == current) {
            return false;
        }
        this.blockEntity.setTauntRadius(next);
        return true;
    }

    /** 界面里没有槽位，所以永远返回空。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** 方块还在、且玩家离得不太远才允许界面继续开着。 */
    @Override
    public boolean stillValid(Player player) {
        if (this.blockEntity == null || this.blockEntity.getLevel() == null) {
            return false;
        }
        BlockPos pos = this.blockEntity.getBlockPos();
        // 8 格是原版「容器界面」的常规上限；太远就自动关闭。
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }
}
