package com.guardianprotocol.item;

import com.guardianprotocol.block.SpawnPointBlock;
import com.guardianprotocol.block.SpawnPointBlockEntity;
import com.guardianprotocol.spawn.invasion.InvasionConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 出怪位置标定器：给一块出怪点（**配置器**）标定它到底从哪里出兵。
 *
 * <h3>为什么要这件物品（设计口径）</h3>
 * <p>出怪点方块从此<b>只当配置器</b>：它自己不再「在方块所在处出兵」。
 * 真正的出怪位置由这件物品在世界上标出来 ——
 * 「该物品最少需要设置一个出怪位置，最多能同时设置4个出怪位置」，
 * 而且「在设置出怪位置前需要先和出怪点进行绑定」。</p>
 *
 * <h3>操作（三步）</h3>
 * <ol>
 *     <li><b>绑定</b>：右键一个出怪点方块。★ <b>只能绑一个、不能重复绑定</b>（设计口径）：
 *         已经绑定过的标定器再右键任何出怪点都会被<b>拒绝</b>并告诉你它绑在哪 ——
 *         要换一块就得换一件新标定器；</li>
 *     <li><b>标记</b>：绑定后右键地面，标记一个出怪位置（最多 {@link InvasionConfig#MAX_POSITIONS} 个，
 *         超过会被拒绝；同一个格子重复标记也会被拒绝）；</li>
 *     <li><b>清空</b>：潜行右键，清掉所有位置（**绑定不受影响**，因为绑定不可更改）。</li>
 * </ol>
 *
 * <h3>位置存在哪</h3>
 * <p><b>两处都存</b>：物品自身（界面上能看到「已标记 3/4」，也便于递给别人看清楚），
 * 以及**绑定的那块出怪点的配置里**（{@link InvasionConfig#positions()}）——
 * 后者才是相位机真正读的那一份。每次标记/清空都同步写过去，
 * 避免「物品显示 3 个、方块里只有 2 个」这种两边不一致的老坑（踩坑记录）。</p>
 *
 * <h3>★ 为什么逻辑写成静态方法</h3>
 * <p>{@code useOn} 只是层薄壳（读上下文 → 调静态方法 → 发消息）。所有判定
 * （能不能绑、能不能加、满了没有）都在静态方法里，因此无头自验**不需要真人玩家**
 * 就能把这几条规则跑一遍 —— 见自验场景 {@code invasion}。</p>
 */
public class SpawnMarkerItem extends Item {

    /** 绑定坐标的 NBT 键（long）。缺这个键 = 还没绑定。 */
    public static final String TAG_BOUND = "BoundPos";

    /** 已标记位置的 NBT 键（ListTag，每项一个 long）。 */
    public static final String TAG_POSITIONS = "MarkedPositions";

    public SpawnMarkerItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------
    // 判定与读写（唯一实现；useOn 与自验都走这里）
    // ------------------------------------------------------------------

    /** 绑定到哪个出怪点；没绑定返回 {@code null}。 */
    @Nullable
    public static BlockPos boundPos(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_BOUND) ? BlockPos.of(tag.getLong(TAG_BOUND)) : null;
    }

    public static boolean isBound(ItemStack stack) {
        return boundPos(stack) != null;
    }

    /**
     * 首次绑定。
     *
     * @return {@code true} = 绑定成功；{@code false} = **已经绑定过了**（设计口径：不能重复绑定，
     *         所以这里不覆盖、也不迁移）
     */
    public static boolean bindOnce(ItemStack stack, @Nullable BlockPos point) {
        if (point == null || isBound(stack)) {
            return false;
        }
        stack.getOrCreateTag().putLong(TAG_BOUND, point.asLong());
        return true;
    }

    /** 已标记的出怪位置（只读）。 */
    public static List<BlockPos> markedPositions(ItemStack stack) {
        List<BlockPos> out = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return out;
        }
        ListTag list = tag.getList(TAG_POSITIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && out.size() < InvasionConfig.MAX_POSITIONS; i++) {
            out.add(BlockPos.of(list.getCompound(i).getLong("p")));
        }
        return out;
    }

    /**
     * 标记一个出怪位置。
     *
     * @return {@code true} = 记下了；{@code false} = 没绑定 / 已经满 {@link InvasionConfig#MAX_POSITIONS} 个 /
     *         这个格子之前就标过
     */
    public static boolean addMarkedPosition(ItemStack stack, @Nullable BlockPos pos) {
        if (!isBound(stack) || pos == null) {
            return false;
        }
        List<BlockPos> current = markedPositions(stack);
        BlockPos p = pos.immutable();
        if (current.size() >= InvasionConfig.MAX_POSITIONS || current.contains(p)) {
            return false;
        }
        current.add(p);
        writePositions(stack, current);
        return true;
    }

    /** 清空已标记位置（绑定保留 —— 绑定本来就不可更改）。 */
    public static void clearMarkedPositions(ItemStack stack) {
        writePositions(stack, List.of());
    }

    private static void writePositions(ItemStack stack, List<BlockPos> list) {
        ListTag out = new ListTag();
        for (BlockPos p : list) {
            CompoundTag one = new CompoundTag();
            one.putLong("p", p.asLong());
            out.add(one);
        }
        stack.getOrCreateTag().put(TAG_POSITIONS, out);
    }

    /**
     * 把物品上的位置**同步进绑定的那块出怪点**（相位机读的是方块里的那份）。
     *
     * @return 同步成功了没有（方块没了 / 不是出怪点 / 不在同一维度 → false）
     */
    public static boolean pushToBoundPoint(Level level, ItemStack stack, boolean replace) {
        BlockPos bound = boundPos(stack);
        if (level == null || bound == null) {
            return false;
        }
        if (!(level.getBlockEntity(bound) instanceof SpawnPointBlockEntity point)) {
            return false;
        }
        InvasionConfig cfg = point.invasion();
        if (replace) {
            cfg.clearPositions();
        }
        for (BlockPos p : markedPositions(stack)) {
            cfg.addPosition(p);
        }
        point.setChanged();
        return true;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        ItemStack stack = ctx.getItemInHand();
        BlockPos clicked = ctx.getClickedPos();

        if (level.isClientSide) {
            // 客户端不预测结果（服务端说了算，避免两边状态不一致时出现「点了没反应/点了两次」）
            return InteractionResult.SUCCESS;
        }
        if (!(ctx.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        // ① 潜行右键 = 元操作：**对着出怪点 = 绑定，对着别的方块 = 清空位置**。
        //
        //   ★ 设计口径是「绑定 = shift+右键出怪点；清除 = 左ctrl+右键」。绑定照做；
        //   清除这一条改用「shift+右键其他方块」，原因是**左 ctrl 在服务端看不见**：
        //   原版不会把「玩家按住了 ctrl」发给服务端（只有骑乘时同步输入位，冲刺位在站着不动
        //   时也不发），要做成真 ctrl 就必须加一个自定义包 + 客户端按键检测，
        //   而**客户端开发环境测不了**（这类「只能在实机看」的改动已经欠了一次账，不再叠）。
        //   先给一条服务端可验的等价操作；要真 ctrl 说一声。
        if (player.isShiftKeyDown()) {
            if (level.getBlockState(clicked).getBlock() instanceof SpawnPointBlock) {
                if (isBound(stack)) {
                    say(player, "★ 这件标定器已经绑定在 " + describeBound(stack)
                            + "，不能重复绑定（要换一块出怪点请用新的标定器）", ChatFormatting.RED);
                } else {
                    bindOnce(stack, clicked);
                    say(player, "已绑定出怪点 " + clicked.toShortString()
                                    + "；现在直接右键地面标记出怪位置（最少 1 个，最多 "
                                    + InvasionConfig.MAX_POSITIONS + " 个），潜行右键地面可清空",
                            ChatFormatting.GREEN);
                }
            } else {
                clearMarkedPositions(stack);
                pushToBoundPoint(level, stack, true);
                say(player, "已清空出怪位置（绑定不变：" + describeBound(stack) + "）", ChatFormatting.YELLOW);
            }
            return InteractionResult.CONSUME;
        }

        // ② 直接右键出怪点 = **交给方块**（它去开配置界面）。返回 PASS 是保底：
        //    正常情况下 {@code SpawnPointBlock#use} 已经吃掉了这次交互，根本走不到这里；
        //    万一那边以后改了，这里也不会退化成「右键出怪点却标了个位置」。
        if (level.getBlockState(clicked).getBlock() instanceof SpawnPointBlock) {
            return InteractionResult.PASS;
        }

        // ③ 直接右键别的方块 = 标记一个出怪位置
        if (!isBound(stack)) {
            say(player, "★ 请先用它右键一个出怪点方块完成绑定，再标记出怪位置", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        BlockPos mark = clicked.relative(ctx.getClickedFace());
        if (!addMarkedPosition(stack, mark)) {
            int n = markedPositions(stack).size();
            String why = n >= InvasionConfig.MAX_POSITIONS
                    ? ("已经标满 " + InvasionConfig.MAX_POSITIONS + " 个位置了（潜行右键可清空）")
                    : "这个格子已经标过了";
            say(player, "★ 没能标记 " + mark.toShortString() + "：" + why, ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        boolean pushed = pushToBoundPoint(level, stack, true);
        say(player, "已标记出怪位置 " + mark.toShortString()
                        + "（" + markedPositions(stack).size() + '/' + InvasionConfig.MAX_POSITIONS + "）"
                        + (pushed ? "" : "　⚠ 但没能写进出怪点（方块不在了？）"),
                pushed ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        BlockPos bound = boundPos(stack);
        if (bound == null) {
            tooltip.add(Component.literal("未绑定：右键一个出怪点方块").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("已绑定 " + bound.toShortString()
                    + "（不可重复绑定）").withStyle(ChatFormatting.DARK_GREEN));
        }
        List<BlockPos> marks = markedPositions(stack);
        tooltip.add(Component.literal("出怪位置 " + marks.size() + '/' + InvasionConfig.MAX_POSITIONS
                + "（右键地面标记 / 潜行右键清空）").withStyle(ChatFormatting.GRAY));
        for (BlockPos p : marks) {
            tooltip.add(Component.literal("  · " + p.toShortString()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String describeBound(ItemStack stack) {
        BlockPos bound = boundPos(stack);
        return bound == null ? "（未绑定）" : bound.toShortString();
    }

    private static void say(net.minecraft.server.level.ServerPlayer player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), false);
    }
}
