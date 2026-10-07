package com.guardianprotocol.net;

import com.guardianprotocol.block.SpawnPointBlockEntity;
import com.guardianprotocol.client.ClientSpawnPointConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：一个出怪组的<b>完整配置快照</b>。
 *
 * <h3>为什么要有它（本轮最贵的一课）</h3>
 * <p>界面原来每 tick 从「客户端方块实体」取权威值，而那份数据依赖原版方块实体包同步过来。
 * 结果：原版 {@code BlockEntity#getUpdateTag()} 默认返回<b>空 tag</b>，只重写了
 * {@code getUpdatePacket()} 等于每次发空包 —— 服务端明明改了（{@code /data get block} 能看到新值），
 * 界面却永远显示旧值，看起来像「按钮被锁住」。</p>
 *
 * <p>修掉空包之后<b>仍然</b>保留这条快照通道，理由有三：</p>
 * <ol>
 *     <li><b>权威值由我们自己控制</b>：不依赖原版包在什么时机发、发给谁；</li>
 *     <li><b>夹取结果能立刻回到界面</b>：数量满了还点 +，下一帧界面就是 20 而不是 21；</li>
 *     <li><b>可测</b>：快照的编解码能在自验里真跑一遍（造 buf → 编码 → 解码 → 比对），
 *         而「原版包有没有送到客户端」在无头环境里根本验不了。</li>
 * </ol>
 *
 * <p>客户端只把它存进一份按坐标索引的缓存，界面从缓存画 —— 不写回方块实体，
 * 免得「显示用的数据」和「真正存档的数据」变成两份会互相打架的真值。</p>
 */
public record SpawnPointSyncPacket(BlockPos pos, boolean enabled,
                                   List<SpawnPointBlockEntity.Entry> entries) {

    /** 上限兜底：条目数与文本长度都按方块实体的常量走，异常包解不出超长列表。 */
    private static final int MAX_ENTRIES = SpawnPointBlockEntity.MAX_ENTRIES;
    private static final int MAX_TEXT = SpawnPointBlockEntity.MAX_ENTITY_ID_LENGTH;

    public SpawnPointSyncPacket {
        entries = List.copyOf(entries);
    }

    public static void encode(SpawnPointSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.enabled);
        buf.writeVarInt(msg.entries.size());
        for (SpawnPointBlockEntity.Entry e : msg.entries) {
            buf.writeUtf(e.entityId(), MAX_TEXT);
            buf.writeVarInt(e.count());
            buf.writeVarInt(e.delayTicks());
            buf.writeVarInt(e.intervalTicks());
        }
    }

    public static SpawnPointSyncPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean enabled = buf.readBoolean();
        int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<SpawnPointBlockEntity.Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String id = buf.readUtf(MAX_TEXT);
            int count = buf.readVarInt();
            int delay = buf.readVarInt();
            int interval = buf.readVarInt();
            // Entry 的构造器会夹取：即使对面发来越界值，客户端也不会显示成非法组合
            entries.add(new SpawnPointBlockEntity.Entry(id, count, delay, interval));
        }
        return new SpawnPointSyncPacket(pos, enabled, entries);
    }

    public static void handle(SpawnPointSyncPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSpawnPointConfig.put(msg.pos, msg.snapshot())));
        ctx.setPacketHandled(true);
    }

    /** 转成方块实体那边的快照类型（客户端缓存与界面都用它）。 */
    public SpawnPointBlockEntity.Snapshot snapshot() {
        return new SpawnPointBlockEntity.Snapshot(this.enabled, this.entries);
    }
}
