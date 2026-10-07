package com.guardianprotocol.block;

import com.guardianprotocol.GuardianConfig;
import com.guardianprotocol.blockentity.ModBlockEntities;
import com.guardianprotocol.menu.ProtectTargetMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 保护目标的方块实体：存血量、维护「活跃实例表」，并作为右键界面的入口。
 *
 * <p>活动状态有两处：</p>
 * <ul>
 *     <li><b>血量</b> —— 存在方块实体里，随方块实体更新包同步到客户端；</li>
 *     <li><b>嘲讽半径</b> —— 存在<b>方块状态</b>里（见 {@link TauntRadius}），
 *         这样原版方块更新会免费同步，不需要自定义网络包。</li>
 * </ul>
 *
 * <h3>为什么在这里维护活跃实例表</h3>
 * <p>{@link ProtectTargetManager} 需要「每间隔若干 tick 遍历世界上所有保护目标」。
 * 原版没有「按方块实体类型查询所有实例」的 API，遍历已加载区块既不直观、
 * 又容易用错方法名（{@code ServerChunkCache} 上并没有一个直白的 getLoadedChunks）。</p>
 *
 * <p>改成「方块实体自己在构造时登记、被移除时注销」，管理器直接读这张表：
 * 语义清楚、没有隐藏的 API 依赖，代价只是一个静态列表。
 * 用 {@link CopyOnWriteArrayList} 是因为登记/注销可能发生在不同线程
 * （区块在工作线程上加载），而遍历只发生在服务端主线程。</p>
 */
public class ProtectTargetBlockEntity extends BlockEntity implements MenuProvider {

    /** NBT 键名。 */
    private static final String TAG_HEALTH = "Health";

    /** 所有已加载的保护目标方块实体。管理器只读这份表。 */
    private static final List<ProtectTargetBlockEntity> ACTIVE =
            new CopyOnWriteArrayList<>();

    /** 当前活跃实例（只读视图）。 */
    public static List<ProtectTargetBlockEntity> activeTargets() {
        return Collections.unmodifiableList(ACTIVE);
    }

    /** 当前血量；负值表示「还没初始化过」，首次访问时按配置补满。 */
    private int health = -1;

    public ProtectTargetBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PROTECT_TARGET.get(), pos, state);
        ACTIVE.add(this);
    }

    /** 被移除（挖掉 / 区块卸载 / 世界关闭）时注销，避免静态表持有失效引用。 */
    @Override
    public void setRemoved() {
        ACTIVE.remove(this);
        super.setRemoved();
    }

    /** 当前血量（会自动按配置初始化）。 */
    public int getHealth() {
        if (this.health < 0) {
            this.health = Math.max(0, GuardianConfig.targetHealth());
        }
        return this.health;
    }

    public void setHealth(int value) {
        this.health = Math.max(0, value);
        this.setChanged();
    }

    /** 最大血量，直接取配置（不随方块变化）。 */
    public int getMaxHealth() {
        return Math.max(0, GuardianConfig.targetHealth());
    }

    /** 是否不可摧毁（配置 <= 0）。 */
    public boolean isIndestructible() {
        return GuardianConfig.targetHealth() <= 0;
    }

    /**
     * 受到伤害。
     *
     * @return true 表示这次伤害把保护目标打掉了（调用方负责摧毁方块）
     */
    public boolean hurt(int amount) {
        if (this.isIndestructible() || amount <= 0) {
            return false;
        }
        int next = this.getHealth() - amount;
        if (next <= 0) {
            this.setHealth(0);
            return true;
        }
        this.setHealth(next);
        return false;
    }

    // ------------------------------------------------------------------
    // 嘲讽半径（存在方块状态里，见 TauntRadius 的注释）
    // ------------------------------------------------------------------

    /** 当前嘲讽半径档位。 */
    public TauntRadius getTauntRadius() {
        if (this.level == null) {
            return TauntRadius.DEFAULT;
        }
        return this.level.getBlockState(this.worldPosition)
                .getOptionalValue(TauntRadius.PROPERTY)
                .orElse(TauntRadius.DEFAULT);
    }

    /**
     * 设置嘲讽半径档位。
     *
     * <p>写方块状态即可：原版的方块更新会把新状态同步给所有能看到这个方块的客户端，
     * 所以不需要自己写网络包。</p>
     */
    public void setTauntRadius(TauntRadius radius) {
        if (this.level == null || radius == null) {
            return;
        }
        BlockState state = this.level.getBlockState(this.worldPosition);
        if (!state.hasProperty(TauntRadius.PROPERTY)
                || state.getValue(TauntRadius.PROPERTY) == radius) {
            return;
        }
        this.level.setBlock(this.worldPosition,
                state.setValue(TauntRadius.PROPERTY, radius), Block.UPDATE_ALL);
        this.setChanged();
    }

    // ------------------------------------------------------------------
    // 界面（MenuProvider）
    // ------------------------------------------------------------------

    /** 界面标题。 */
    @Override
    public Component getDisplayName() {
        return Component.translatable("block.guardian_protocol.protect_target");
    }

    /** 服务端打开界面时创建容器。 */
    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ProtectTargetMenu(containerId, inventory, this);
    }

    // ------------------------------------------------------------------
    // 存档与同步
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(TAG_HEALTH, this.getHealth());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(TAG_HEALTH)) {
            this.health = Math.max(0, tag.getInt(TAG_HEALTH));
        }
    }

    /** 服务端 -> 客户端：方块实体内容同步（放下/破坏/更新时都会带上）。 */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        this.saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        // 客户端只用来显示，直接把 NBT 读进来即可。
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            this.load(tag);
        }
    }
}
