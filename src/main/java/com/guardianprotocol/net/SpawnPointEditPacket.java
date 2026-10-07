package com.guardianprotocol.net;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.block.SpawnPointBlockEntity;
import com.guardianprotocol.menu.SpawnPointMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：一个出怪组编辑动作。
 *
 * <h3>为什么连数值也走自己的包，而不是原版 {@code clickMenuButton}</h3>
 * <p>原版那条路能用（实测按钮点击确实到了服务端），但本轮要把「出怪组」做成
 * <b>行数可变、每行四个字段</b>，一个 int 载荷就得手工编码「第几行 + 哪个字段 + 方向」，
 * 还要与界面布局常量保持同步 —— 那是典型的「同一件事两处维护」。
 * 加一个动作枚举反而更短、更好验：<b>语义集中在 {@link SpawnPointBlockEntity#applyEdit}</b>，
 * 自验可以直接调它，不依赖网络。</p>
 *
 * <h3>服务端的四道校验（任一不过：静默丢弃 + 日志）</h3>
 * <ol>
 *     <li>发送者存在；</li>
 *     <li>玩家<b>正开着这个坐标的出怪点界面</b>（{@code containerMenu} 是我们这个容器，
 *         且它持有的方块实体就是这个坐标的实例）—— 没开界面就改不了别人的方块；</li>
 *     <li>距离 ≤ 8 格（与容器的 {@code stillValid} 同口径）；</li>
 *     <li>真正写入只走 {@code applyEdit}：数量/延迟/间隔的区间、最大条目数、至少留一条，
 *         全部由方块实体夹取。所以伪造的包最多只是「没变化」，写不进越界值。</li>
 * </ol>
 *
 * <p>处理完<b>一定回推一份快照</b>（{@link SpawnPointSyncPacket}），哪怕这次没改动 ——
 * 界面靠它显示夹取后的真值（例如数量本来 20 还点 +，回推后界面仍是 20，不会显示成 21）。</p>
 */
public record SpawnPointEditPacket(BlockPos pos, SpawnPointBlockEntity.EditOp op, int index,
                                   String text) {

    /** 文本长度上限（与方块实体的规范化一致，这里再兜一层，防止超大包）。 */
    private static final int MAX_TEXT = SpawnPointBlockEntity.MAX_ENTITY_ID_LENGTH;

    public static void encode(SpawnPointEditPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeEnum(msg.op);
        buf.writeVarInt(msg.index);
        buf.writeUtf(msg.text == null ? "" : msg.text, MAX_TEXT);
    }

    public static SpawnPointEditPacket decode(FriendlyByteBuf buf) {
        return new SpawnPointEditPacket(buf.readBlockPos(), buf.readEnum(SpawnPointBlockEntity.EditOp.class),
                buf.readVarInt(), buf.readUtf(MAX_TEXT));
    }

    public static void handle(SpawnPointEditPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            ctx.setPacketHandled(true);
            return;
        }
        ctx.enqueueWork(() -> {
            if (!(player.containerMenu instanceof SpawnPointMenu menu) || !menu.pos().equals(msg.pos)) {
                GuardianProtocol.LOGGER.debug("[{}] 丢弃出怪点编辑包：玩家没有开着 {} 的界面",
                        GuardianProtocol.MODID, msg.pos);
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            SpawnPointBlockEntity point = menu.blockEntity();
            if (point == null || !point.getBlockPos().equals(msg.pos) || point.getLevel() != level) {
                GuardianProtocol.LOGGER.debug("[{}] 丢弃出怪点编辑包：{} 上的方块实体已失效",
                        GuardianProtocol.MODID, msg.pos);
                return;
            }
            if (player.distanceToSqr(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                    msg.pos.getZ() + 0.5D) > 64.0D) {
                GuardianProtocol.LOGGER.debug("[{}] 丢弃出怪点编辑包：玩家离 {} 太远",
                        GuardianProtocol.MODID, msg.pos);
                return;
            }
            boolean accepted = point.applyEdit(msg.op, msg.index, msg.text);
            if (!accepted) {
                GuardianProtocol.LOGGER.debug("[{}] 出怪点编辑未受理：{} 在 {}（第 {} 条）",
                        GuardianProtocol.MODID, msg.op, msg.pos, msg.index);
            }
            // 不论受理与否都回推：界面必须显示服务端夹取后的真值。
            ModNetwork.sendSnapshot(player, point);
        });
        ctx.setPacketHandled(true);
    }

    /** 便于日志/自验阅读。 */
    @Override
    public String toString() {
        return "SpawnPointEdit[" + this.pos.toShortString() + " " + this.op
                + " #" + this.index + (this.text.isEmpty() ? "" : " \"" + this.text + "\"") + "]";
    }

    /** 供将来做提示用（目前服务端不回错误消息，界面靠快照自证）。 */
    public static Component nothing() {
        return Component.empty();
    }
}
