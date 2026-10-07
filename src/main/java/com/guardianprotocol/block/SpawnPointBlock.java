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
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * 出怪点方块：一个「敌人从这里出来」的标记。
 *
 * <p>它<b>自己什么都不做</b> —— 不 tick、不自发出怪、不认识波次。它只是一份
 * 「出什么怪、出几只、多快出」的配置载体 + 一个右键入口；真正出怪的动作在
 * {@code spawn/SpawnPointService}，由上层（后来的塔防项目）决定何时调用。
 * 这样「摆在哪」与「什么时候出」就是两件互不纠缠的事。</p>
 *
 * <h3>形状：无碰撞的全格标记</h3>
 * <p>用 {@code noCollission()}（与 {@link ProtectTargetBlock} 同一套做法），但
 * <b>视觉与选取形状给整格</b>（{@code Shapes.block()}）：</p>
 * <ul>
 *     <li>无碰撞 → 生物可以直接站在标记里、穿过去，不会卡在出怪点上（塔防里这是致命的）；</li>
 *     <li>整格选取形状 → 准星好点、右键好点。保护目标那边把形状缩到 1..15 是因为它是个
 *         悬浮立方体，缩一点更好看；出怪点是个贴地的门框，缩了反而难点中。</li>
 * </ul>
 *
 * <p>注意 {@code noCollission()} 会让 {@code getCollisionShape} 恒为空，
 * 所以这里显式写出 {@link #getCollisionShape} 表明「这是设计，不是漏写」。</p>
 *
 * <h3>强度：可被玩家挖掉</h3>
 * <p>不像保护目标用 {@code strength(-1.0F)}（那是失守条件，只能被敌人打掉），
 * 出怪点是玩家自己的工具，必须能拆能搬。掉落走战利品表
 * {@code data/guardian_protocol/loot_tables/blocks/spawn_point.json}。</p>
 */
public class SpawnPointBlock extends Block implements EntityBlock {

    /** 视觉/选取形状：整格。 */
    private static final VoxelShape SHAPE = Shapes.block();

    public SpawnPointBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(1.5F, 6.0F)
                .sound(SoundType.METAL)
                .lightLevel(state -> 7)           // 自发光，夜里也找得到自己摆的出怪点
                .noCollission()                   // ★ 无碰撞：敌人从标记里走出来，不会被自己绊住
                .noOcclusion()
                .isViewBlocking((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false));
        // 没有任何方块状态属性：本方块的全部配置都在方块实体的 NBT 里
        // （数量/间隔/延迟是自由数值，方块状态放不下；见 SpawnPointBlockEntity 的注释）。
    }

    // ------------------------------------------------------------------
    // 右键：打开配置界面
    // ------------------------------------------------------------------

    /**
     * 右键打开配置界面。
     *
     * <p>与服务端的分工和保护目标完全一致：服务端用 {@code NetworkHooks.openScreen}
     * 真正开界面，客户端只返回「成功」播挥手动画。客户端自己开界面的话，服务端不知道、
     * 也就没法校验玩家的改动。</p>
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        // ★ 潜行右键 = **让给手里的物品**。
        //
        //   1.20.1 的交互顺序是「先 {@code block.use}，再 {@code stack.useOn}」——
        //   方块只要吃掉这次交互，手里的东西就永远收不到。标定器第一版正是栽在这里：
        //   右键出怪点只会打开配置界面，它的「绑定」分支是**死代码**
        //   （实测反馈：「直接右键出怪点会打开出怪点配置」）。
        //
        //   潜行时返回 PASS 是原版惯例（潜行 = 别用这个方块），而且**两边都要返回**：
        //   客户端预测与服务端必须一致，否则客户端会先播一个「开了界面」的结果。
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SpawnPointBlockEntity point && player instanceof ServerPlayer serverPlayer) {
            // ★ 必须用带 BlockPos 的三参重载：MenuType 的客户端工厂会 readBlockPos()
            //   来找回方块实体，用两参重载会写入一个空 buffer，客户端读坐标直接越界报错。
            NetworkHooks.openScreen(serverPlayer, point, pos);
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
     * <p>父类的 {@code noCollission()} 已经会返回空，这里显式写出来是为了明确意图 ——
     * 「生物能穿过出怪点」是设计决定，不是疏忽。</p>
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
        return new SpawnPointBlockEntity(pos, state);
    }
}
