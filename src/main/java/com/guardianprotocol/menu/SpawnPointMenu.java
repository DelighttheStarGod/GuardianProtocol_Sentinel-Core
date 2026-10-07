package com.guardianprotocol.menu;

import com.guardianprotocol.block.SpawnPointBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * 出怪组的配置容器：<b>只负责「打开界面」这一件事</b>。
 *
 * <p>沿用 {@link ProtectTargetMenu} 的通道（服务端 {@code NetworkHooks.openScreen} →
 * 客户端自动建容器与 Screen），但<b>不再用它传数据</b>：数值与文本全走
 * {@code net/SpawnPointEditPacket}，服务端改完回推 {@code net/SpawnPointSyncPacket}。</p>
 *
 * <h3>为什么把 {@code clickMenuButton} 这条路去掉了</h3>
 * <p>原版那条路本身没问题（实测点击确实到达服务端），但「出怪组」一行有四个字段、
 * 行数还会变，一个 int 载荷就得手工编码「第几行 + 哪个字段 + 方向」，并与界面布局常量
 * 保持同步 —— 那正是本项目反复踩的「同一件事两处维护」。现在只有一个写入口
 * （{@code SpawnPointBlockEntity.applyEdit}），自验也能不依赖网络地把它跑一遍。</p>
 *
 * <p>这个容器里没有物品槽位，所以 {@link #quickMoveStack} 返回空、{@link #stillValid}
 * 只校验玩家没走远、方块还在。</p>
 */
public class SpawnPointMenu extends AbstractContainerMenu {

    private final SpawnPointBlockEntity blockEntity;

    /**
     * 界面绑定的方块坐标。
     *
     * <p>★ 单独存一份：方块没了（被拆/区块卸载）时 {@link #blockEntity} 会变成 null，
     * 但界面仍需坐标来把编辑动作发回服务端，服务端也用它校验「你开着的是不是这个方块」。</p>
     */
    private final BlockPos pos;

    /** 客户端创建用（由 MenuType 调用，pos 来自 openScreen 的额外数据）。 */
    public SpawnPointMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.SPAWN_POINT.get(), containerId);
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof SpawnPointBlockEntity p ? p : null;
        this.pos = pos;
    }

    /** 服务端创建用。 */
    public SpawnPointMenu(int containerId, Inventory playerInventory, SpawnPointBlockEntity blockEntity) {
        super(ModMenus.SPAWN_POINT.get(), containerId);
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getBlockPos();
    }

    /** 可能为 null（方块在界面开着的时候被拆了/区块卸载）。 */
    @Nullable
    public SpawnPointBlockEntity blockEntity() {
        return this.blockEntity;
    }

    /** 界面绑定的坐标（客户端发包与服务端校验都用它）。 */
    public BlockPos pos() {
        return this.pos;
    }

    /** 界面里没有槽位，所以永远返回空。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** 方块还在、且玩家离得不太远才允许界面继续开着（8 格，与原版容器同口径）。 */
    @Override
    public boolean stillValid(Player player) {
        if (this.blockEntity == null || this.blockEntity.getLevel() == null) {
            return false;
        }
        BlockPos p = this.blockEntity.getBlockPos();
        return player.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) <= 64.0D;
    }
}
