package com.guardianprotocol.client;

import com.guardianprotocol.block.SpawnPointBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端缓存：出怪点的<b>服务端权威快照</b>（按坐标索引）。
 *
 * <p>界面只画这里的内容，不去读客户端的方块实体 —— 这是本轮踩坑后的设计决定：
 * 方块实体那份数据要靠原版包同步，出过「服务端新值、界面旧值」的事故（见
 * {@code SpawnPointSyncPacket} 的类注释）。</p>
 *
 * <p>缓存的生命周期：打开界面时服务端推一份、每次编辑再推一份、退出世界时整体清空
 * （见 {@code ModEvents} 的卸载清理）。不写回方块实体，避免「显示的真值」与「存档的真值」
 * 变成两份会互相打架的数据。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientSpawnPointConfig {

    private static final Map<BlockPos, SpawnPointBlockEntity.Snapshot> CACHE = new HashMap<>();

    private ClientSpawnPointConfig() {
    }

    /** 服务端推来的快照（网络线程 → 主线程后调用）。 */
    public static void put(BlockPos pos, SpawnPointBlockEntity.Snapshot snapshot) {
        CACHE.put(pos.immutable(), snapshot);
    }

    /** 取某个坐标的快照；还没收到过就是 null（界面据此显示「读取中」而不是画旧值）。 */
    @Nullable
    public static SpawnPointBlockEntity.Snapshot get(BlockPos pos) {
        return CACHE.get(pos);
    }

    /** 退出世界/切换维度时清空：坐标相同但世界不同的话，缓存会张冠李戴。 */
    public static void clear() {
        CACHE.clear();
    }
}
