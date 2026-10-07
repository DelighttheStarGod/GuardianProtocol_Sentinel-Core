package com.guardianprotocol.net;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.block.SpawnPointBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 本 mod 的网络通道。
 *
 * <p>两个包：客户端发的「编辑动作」（{@link SpawnPointEditPacket}）与服务端回推的
 * 「配置快照」（{@link SpawnPointSyncPacket}）。</p>
 *
 * <h3>★ 纠正一条项目里的旧结论</h3>
 * <p>本项目原先多处写着「开发环境拿不到 Forge 的 {@code SimpleChannel}，缓存里没有该类」
 * （见 踩坑记录与 {@code block/TauntRadius}、{@code menu/*Menu} 的旧注释）。
 * <b>那个结论是错的</b>：实测该类就在 {@code net/minecraftforge/network/simple/SimpleChannel}
 * （注意中间那一层 {@code .simple}；搜 {@code net.minecraftforge.network.SimpleChannel} 确实没有，
 * 大概是因此得出了「拿不到」）。2026-10 已逐处订正 —— 嘲讽半径放进方块状态、界面走原版容器
 * 这两个设计本身没问题（更省事、且客户端无法伪造数据），但**不能再当作「网络不可用」的论据**。</p>
 *
 * <h3>为什么在构造器里注册，而不是 commonSetup</h3>
 * <p>{@code NetworkRegistry.createInstance} 会检查一个静态 {@code lock}，
 * 它由加载阶段 {@code NETWORK_LOCK}（{@code ModLoadingPhase.COMPLETE}）置上。
 * 也就是说只要在「加载完成」之前注册都合法 —— 主类构造器在它之前很久。
 * 放这里的好处是与 {@code ModBlocks/ModItems/ModMenus} 的注册排在一起，
 * 看主类就能一眼知道本 mod 挂了哪些东西。</p>
 *
 * <h3>版本串</h3>
 * <p>通道协议版本写死 {@code "1"}，两端必须完全相等才允许连接（{@code VERSION::equals}）。
 * 之所以不用「接受任意版本」的宽松判据：包体一旦改字段，宽松判据会让新旧两端连上然后
 * 在解码时炸在莫名其妙的偏移上，不如连接阶段直接拒绝，报错清楚得多。</p>
 */
public final class ModNetwork {

    /** 通道协议版本。改了包格式就必须一起改它。 */
    public static final String PROTOCOL_VERSION = "1";

    /** 通道名（用 modId 当路径，避免与别的 mod 撞名）。 */
    private static final ResourceLocation CHANNEL_NAME =
            new ResourceLocation(GuardianProtocol.MODID, "main");

    /** 唯一通道。 */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_NAME,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private ModNetwork() {
    }

    /** 注册本 mod 的所有网络包（由主类构造器调用一次）。 */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SpawnPointEditPacket.class,
                SpawnPointEditPacket::encode,
                SpawnPointEditPacket::decode,
                SpawnPointEditPacket::handle);
        CHANNEL.registerMessage(id++, SpawnPointSyncPacket.class,
                SpawnPointSyncPacket::encode,
                SpawnPointSyncPacket::decode,
                SpawnPointSyncPacket::handle);
        GuardianProtocol.LOGGER.info("[{}] 网络通道就绪：{}（协议版本 {}，已注册 {} 个包）",
                GuardianProtocol.MODID, CHANNEL_NAME, PROTOCOL_VERSION, id);
    }

    /**
     * 把某个出怪组的配置快照推给一名玩家。
     *
     * <p>只在「打开界面」与「刚处理完一次编辑」时调用，不每 tick 推 ——
     * 快照很小，但也犯不着每秒 20 次。</p>
     */
    public static void sendSnapshot(ServerPlayer player, SpawnPointBlockEntity point) {
        if (player == null || point == null) {
            return;
        }
        SpawnPointBlockEntity.Snapshot snapshot = point.snapshot();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SpawnPointSyncPacket(point.getBlockPos(), snapshot.enabled(), snapshot.entries()));
    }
}
