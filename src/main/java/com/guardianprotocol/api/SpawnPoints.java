package com.guardianprotocol.api;

import com.guardianprotocol.block.SpawnPointBlockEntity;
import com.guardianprotocol.spawn.SpawnPointService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「出怪点」的公开接口 —— core 只提供<b>「按定义生成一批敌人」</b>这一个动词。
 *
 * <p><b>本项目不做、也不该做的事</b>：什么时候出怪（准备 30s → 出怪 60s → 协防）、
 * 出怪与胜负/金币的关系。那是后续「塔防循环」项目的节奏，core 一旦替它决定，
 * 它就没法接自己的节奏了（见 设计说明）。</p>
 *
 * <p>两条入口对应两种用法：</p>
 * <ul>
 *     <li>世界上摆了出怪点方块 → {@link #spawnBatch(ServerLevel, SpawnPointBlockEntity)}；</li>
 *     <li>不想摆方块、或在别处出怪 → {@link #spawnOnce(ServerLevel, Vec3, EntityType, int, CompoundTag)}
 *         （纯生成原语，不碰任何方块）。</li>
 * </ul>
 */
public final class SpawnPoints {

    private SpawnPoints() {
    }

    /** 当前已加载的全部出怪点（只读快照）。 */
    public static List<SpawnPointBlockEntity> active() {
        return SpawnPointService.activePoints();
    }

    /**
     * 一个出怪点的**出怪组**：每一条是「一种怪 + 数量 + 它自己的延迟与间隔」。
     *
     * <p>下游如果只是想「出一批」，用 {@link #spawnBatch} 就够了；
     * 想按条目自己调度（例如按羁绊改数量、按波次只放其中某几种）就读这个列表，
     * 再配合 {@link #spawnOnce} 单放。</p>
     */
    public static List<SpawnPointBlockEntity.Entry> entriesOf(SpawnPointBlockEntity point) {
        return point.entries();
    }

    /** 一个出怪点的配置快照（与推给客户端界面的是同一份）。 */
    public static SpawnPointBlockEntity.Snapshot snapshotOf(SpawnPointBlockEntity point) {
        return point.snapshot();
    }

    /**
     * 按这个出怪点的配置出一批怪。
     *
     * <p><b>返回值语义</b>：{@code true} = 这批<b>被受理了</b>；{@code false} = 压根没受理
     * （方块不在这个世界 / 被关掉了 / 实体 id 解析不出来 / 数量非法）。
     * 配置里带了延迟或间隔时，本方法返回 true 但<b>当场一只都没出</b> —— 由服务端每 tick 推进。</p>
     *
     * <p>重复调用 = 重复排期，**不做去重**：一个点该不该连发属于上层节奏。</p>
     */
    public static boolean spawnBatch(ServerLevel level, SpawnPointBlockEntity point) {
        return SpawnPointService.spawnBatch(level, point);
    }

    /**
     * 在指定坐标出一批怪，返回<b>真的生成出来的只数</b>。
     *
     * <p>落点会自动散开（第 0 只居中，其余沿黄金角往外，最多 1.8 格），
     * 避免整批叠在一格里互相挤压。{@code extraNbt} 可为 null，
     * 其中的 {@code Pos}/{@code Rotation} <b>会被忽略</b>（生成后坐标会被重新钉一次）。</p>
     */
    public static int spawnOnce(ServerLevel level, Vec3 pos, EntityType<?> type, int count,
                                @Nullable CompoundTag extraNbt) {
        return SpawnPointService.spawnOnce(level, pos, type, count, extraNbt);
    }

    /** 排期中（延迟/间隔还没走完）的批次数。 */
    public static int pendingBatches() {
        return SpawnPointService.pendingBatchCount();
    }

    /** 排期中还没出场的敌人只数。 */
    public static int pendingMobs() {
        return SpawnPointService.pendingMobCount();
    }

    /**
     * 把一个字符串解析成「可以生成的生物类型」；解析不出来返回 null。
     *
     * <p>给上层做界面/指令的输入校验用：它的口径与出怪点写入时完全一致
     * （必须是注册表里真实存在、且能造出 {@code Mob} 的类型）。</p>
     */
    @Nullable
    public static EntityType<?> resolveMobType(ServerLevel level, @Nullable String entityId) {
        return SpawnPointBlockEntity.resolveMobType(level, entityId);
    }
}
