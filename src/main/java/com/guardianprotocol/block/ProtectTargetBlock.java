package com.guardianprotocol.block;

import com.guardianprotocol.blockentity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * 保护目标方块。
 *
 * <p>塔防玩法的「失守条件」核心：它自带嘲讽，把敌人吸过来并把碰到它的敌人抹杀。
 * 血量与同步交给 {@link ProtectTargetBlockEntity}。</p>
 *
 * <h3>形状设计（这是本方块最容易踩坑的地方）</h3>
 * <p>用 {@code noCollission()}：方块<b>不参与碰撞</b>，生物可以直接穿过它 ——
 * 这正是「无碰撞箱」的字面效果。但 {@code noCollission()} 会把
 * {@code hasCollision} 置为 false，进而让 {@code getCollisionShape} 恒返回空，
 * 所以不能靠碰撞来做「接触判定」。</p>
 *
 * <p>因此：</p>
 * <ul>
 *     <li>{@link #getShape} 给一个略小于整格的<b>视觉/选取形状</b>
 *         （准星选中、破坏判定用）；</li>
 *     <li>{@code getCollisionShape} 用 {@link Shapes#empty()}，
 *         生物与玩家都能穿过（只留一个视觉上的「能量立方体」）；</li>
 *     <li>真正的「接触即抹杀」由 {@code ProtectTargetManager} 按配置的半径做 AABB 判定，
 *         不依赖碰撞箱，所以半径可以自由调。</li>
 * </ul>
 */
public class ProtectTargetBlock extends Block implements EntityBlock {

    /** 视觉/选取形状：比整格略小一圈，看起来像一个悬浮的能量立方体。 */
    private static final VoxelShape SHAPE = Block.box(1.0D, 1.0D, 1.0D, 15.0D, 15.0D, 15.0D);

    public ProtectTargetBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLUE)
                .strength(-1.0F, 3_600_000.0F)   // 硬度 -1 = 玩家破坏不掉（见下方说明）
                .sound(SoundType.GLASS)
                .lightLevel(state -> 12)          // 自带冷光，夜里也能看清
                .noCollission()                   // ★ 无碰撞：生物与玩家都能穿过
                .noOcclusion()                    // 不遮挡相邻面，避免出现黑面
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false));
        // 注册默认方块状态。嘲讽半径放在方块状态里（见 TauntRadius 注释）：
        // 这样原版方块更新会免费把半径同步给客户端，不需要自定义网络包。
        //
        // ★ 这里<b>绝对不能读配置</b>：方块的构造发生在 RegisterEvent 期间，
        //   而 ModLoadingContext.registerConfig(...) 是在主类构造器里、注册之后才调用的。
        //   在构造器里读 GuardianConfig 会拿到未初始化的 spec，抛异常 → 异常被事件系统吞掉
        //   → ModBlocks.PROTECT_TARGET 变成 null → 之后 ModItems 里 .get() 报
        //   「Registry Object not present」。（这个 bug 真实发生过，排查了很久。）
        //   配置里的默认半径改为在 commonSetup 里对已放置的目标生效，见 GuardianProtocol。
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(TauntRadius.PROPERTY, TauntRadius.HARDCODED_DEFAULT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TauntRadius.PROPERTY);
    }

    // ------------------------------------------------------------------
    // 右键：打开嘲讽半径设置界面
    // ------------------------------------------------------------------

    /**
     * 右键方块打开设置界面。
     *
     * <p>服务端负责真正打开界面（{@code NetworkHooks.openScreen}），客户端只返回
     * 「成功」以播放挥手动画。这是原版的标准分工 —— 不要在客户端直接开界面，
     * 否则服务端不知道、也无法校验。</p>
     *
     * <p>本方块不与任何物品交互，所以直接打开、不消耗物品。</p>
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ProtectTargetBlockEntity target && player instanceof ServerPlayer serverPlayer) {
            // ★ 必须用带 BlockPos 的三参重载：MenuType 的客户端工厂会 readBlockPos()
            //   来找回方块实体。用两参重载会写入一个空 buffer，客户端读坐标直接越界报错。
            NetworkHooks.openScreen(serverPlayer, target, pos);
        }
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------
    // 形状
    // ------------------------------------------------------------------

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * 碰撞形状：空。
     *
     * <p>父类的 {@code noCollission()} 已经会返回空，这里显式写出来是为了
     * <b>明确意图</b>——「生物能穿过保护目标」是设计决定，不是疏忽。
     * 将来若有人误删 {@code noCollission()}，这个方法会让行为保持不变。<p>
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return Shapes.empty();
    }

    // ------------------------------------------------------------------
    // 方块实体
    // ------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProtectTargetBlockEntity(pos, state);
    }

    /*
     * 关于「玩家能不能打掉它」：
     *   上面 strength(-1.0F, ...) 让硬度的爆炸抗性极大、且 hardness 为负，
     *   玩家徒手/工具都无法正常破坏（与原版基岩同理）。
     *   这是刻意的：保护目标是塔防的失守条件，应该只被敌人打掉，
     *   而不是被己方玩家一个镐子拆了。血量归零时的摧毁走
     *   ProtectTargetManager 里的 removeBlock 逻辑，不受 hardness 影响。
     */
}
