package com.guardianprotocol.spawn;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.block.SpawnPointBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 出怪服务：本模块对外唯一的「动词」。
 *
 * <p>核心库的定位决定了这里的边界：本类只提供<b>「按配置出一批怪」</b>这一个动作，
 * 不提供「准备 30 秒 → 出怪 60 秒 → 协防」这种节奏，也不认识波次、金币、羁绊。
 * 上层的塔防项目自己决定<b>什么时候</b>调 {@link #spawnBatch} 或 {@link #spawnOnce}。</p>
 *
 * <h3>两条入口，用途不同</h3>
 * <ul>
 *     <li>{@link #spawnBatch(ServerLevel, SpawnPointBlockEntity)} —— 「按这个方块上的配置出」。
 *         给「玩家在世界上摆的出怪点」用。</li>
 *     <li>{@link #spawnOnce(ServerLevel, Vec3, EntityType, int, CompoundTag)} ——
 *         「在某个坐标出 N 只某某」。给「不想摆方块、想在代码里直接出怪」的波次管理器用。
 *         它<b>完全不碰方块</b>，所以上层可以拿它当纯粹的生成原语。</li>
 * </ul>
 *
 * <h3>延迟与间隔到底是哪一个的</h3>
 * <p>两个字段都定义在「一批」的粒度上，名字里都写清楚了：</p>
 * <ul>
 *     <li><b>出怪延迟</b>（delayTicks）：从 {@code spawnBatch} 被调用，到<b>这一批的第一只</b>
 *         出现，等多少 tick。</li>
 *     <li><b>批次内间隔</b>（intervalTicks）：<b>同一批里</b>相邻两只之间隔多少 tick；
 *         0 表示整批一次性出（默认）。</li>
 * </ul>
 * <p>刻意不做成「批次之间的间隔」：那是波次节奏，属于上层；核心库一旦管了这件事，
 * 上层就没法接自己的节奏了。</p>
 *
 * <h3>★ 排期为什么不持有方块实体引用</h3>
 * <p>延迟/间隔不为 0 时，这一批没法在调用瞬间出完，必须排期到后续 tick。
 * 排期里只快照 {@code level + 坐标 + 类型 + 剩余只数 + 间隔}，<b>不持有
 * {@code SpawnPointBlockEntity}</b>：方块随时可能在排期期间被挖掉、区块被卸载，
 * 那时方块实体已经 {@code setRemoved()}，再读它的字段就是读一个失效对象
 * （静态活跃表正是靠 {@code setRemoved} 把它摘掉的）。快照掉就没有这个生命周期问题。
 * 代价是「排期期间改配置不影响已经在途的这一批」—— 这恰好也是我们想要的语义。</p>
 */
public final class SpawnPointService {

    /** 散开摆放的黄金角（弧度）。用它比用随机数好：同一批的落点可复现，自验才断言得住。 */
    private static final double GOLDEN_ANGLE = 2.399963229728653D;

    /** 散开半径的基数（格）：第 i 只落在 {@code 基数 * sqrt(i)} 的圈上。 */
    private static final double SCATTER_BASE_RADIUS = 0.55D;

    /** 散开半径上限（格）。 */
    private static final double MAX_SCATTER_RADIUS = 1.8D;

    /** 已排期、还没出完的批次。只在服务端主线程读写，但用同步表防意外。 */
    private static final List<PendingBatch> PENDING = new ArrayList<>();

    private SpawnPointService() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 所有已加载的出怪点。
     *
     * <p>直接转发 {@link SpawnPointBlockEntity#activePoints()}：原版没有「按方块实体类型
     * 查所有实例」的 API，本项目的约定是「方块实体自己登记、注销」。
     * 再包一层是因为<b>上层只该依赖本服务</b>，不该直接摸 {@code block} 包 ——
     * 将来若换成「区块扫描」的实现，改这里一处即可。</p>
     */
    public static List<SpawnPointBlockEntity> activePoints() {
        return SpawnPointBlockEntity.activePoints();
    }

    /** 排期中的批次数量（调试/自验用）。 */
    public static int pendingBatchCount() {
        return PENDING.size();
    }

    /** 排期中还剩多少只没出（调试/自验用）。 */
    public static int pendingMobCount() {
        int total = 0;
        for (PendingBatch batch : PENDING) {
            total += batch.remaining;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 入口 1：按方块配置出一批
    // ------------------------------------------------------------------

    /**
     * 按这个出怪点的配置出一组怪。
     *
     * <p><b>返回值语义</b>：true = 这一组被受理了；false = 受理都没受理
     * （方块不在这个世界 / 被关掉了 / 一条都解析不出来）。组里带了延迟或间隔时，
     * 本方法返回 true 但<b>当场一只都没出</b> —— 它们由 {@link #tickPending} 按 tick 放出。
     * 这个区别写在方法名旁边而不是让调用方猜，是因为「出了 0 只」在塔防里既可能是
     * 「被关掉了」也可能是「还没到点」，两者责任完全不同。</p>
     *
     * <p><b>每种怪各排各的</b>：条目 i 用<b>它自己的</b>延迟与间隔。所以「先出 3 只僵尸、
     * 2 秒后再出 1 只监守者」这种配置不需要上层做任何事 —— 一次 {@code spawnBatch} 就够。</p>
     *
     * <p>重复调用 = 重复排期，本方法不做去重：一个出怪点该不该被重复触发是<b>上层节奏</b>
     * 的事（比如「每 60 秒出一次」），核心库替它去重只会让上层没法做连发。</p>
     */
    public static boolean spawnBatch(ServerLevel level, SpawnPointBlockEntity point) {
        if (level == null || point == null) {
            return false;
        }
        if (point.getLevel() != level) {
            // 方块实体属于别的世界（例如同一坐标在另一个维度）—— 不是同一件事，拒绝。
            return false;
        }
        if (!point.isEnabled()) {
            return false;
        }

        List<SpawnPointBlockEntity.Entry> entries = point.entries();
        if (entries.isEmpty()) {
            return false;
        }

        // 先把每条解析成类型；解析不出来的条目**单独跳过**，其余照常出。
        // 为什么不整组拒绝：一条 id 打错就整组不出怪，玩家会以为「这个出怪点坏了」，
        // 而日志里已经指名道姓说是哪一条解析不出来（见下面的 WARN）。
        List<Ready> ready = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            SpawnPointBlockEntity.Entry entry = entries.get(i);
            EntityType<?> type = SpawnPointBlockEntity.resolveMobType(level, entry.entityId());
            if (type == null) {
                GuardianProtocol.LOGGER.warn("[{}] 出怪点 {} 第 {} 条（{}）解析不出来，本组跳过这一条",
                        GuardianProtocol.MODID, point.getBlockPos().toShortString(), i, entry.entityId());
                continue;
            }
            if (entry.count() > 0) {
                ready.add(new Ready(entry, type));
            }
        }
        if (ready.isEmpty()) {
            return false;
        }

        Vec3 origin = Vec3.atBottomCenterOf(point.getBlockPos());
        // 全部「立即出完」时省掉排期：这样「调用完就已经站在场上」可断言，自验才好写结论。
        boolean allImmediate = ready.stream().allMatch(r -> r.entry.delayTicks() == 0
                && r.entry.intervalTicks() == 0);
        if (allImmediate) {
            int spawned = 0;
            int cursor = 0;
            for (Ready r : ready) {
                spawned += spawnOnce(level, origin, r.type, r.entry.count(), null, cursor);
                cursor += r.entry.count();
            }
            return spawned > 0;
        }

        // 有延迟/间隔：每条各自排期。散开起点错开，避免两种怪落在同一圈位置上互相挤。
        int cursor = 0;
        for (Ready r : ready) {
            PENDING.add(new PendingBatch(level, origin, r.type, r.entry.count(),
                    r.entry.intervalTicks(), r.entry.delayTicks(), cursor));
            cursor += r.entry.count();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 入口 2：纯坐标出怪（后续波次管理器用这个）
    // ------------------------------------------------------------------

    /**
     * 在指定坐标出一批怪，返回<b>真的生成出来的只数</b>。
     *
     * <p>这是给上层「不摆方块也要出怪」用的原语。坐标为 {@code pos}（实体脚底），
     * 第 i 只会沿黄金角散开一点，避免整批叠在同一个点上互相挤压 ——
     * 原版 {@code addFreshEntity} 不做「推开重叠实体」，20 只叠在一格里会变成
     * 「看起来只出了一只」，而且碰撞挤压会把它们甩到莫名其妙的地方。</p>
     *
     * <h3>★ 生成顺序（对着原版 {@code EntityType.create} 抄的，别随意调换）</h3>
     * <ol>
     *     <li>{@code type.create(level)} 造实体；</li>
     *     <li>{@code moveTo} 先摆到位（这样 {@code blockPosition()} 才是对的，
     *         下面难度取样要用它）；</li>
     *     <li>是 {@link Mob} 就补 {@code yHeadRot/yBodyRot} 并调
     *         {@code finalizeSpawn(MOB_SUMMONED)} —— <b>这一步不能省</b>：僵尸的
     *         「会不会破门」、骷髅的武器、羊的颜色、史莱姆的体型全在这里决定，
     *         跳过它出来的怪是「半成品」（原版 {@code EntityType.create} 里就是这么做的，
     *         连把 tag 传进第五个参数都一样）；</li>
     *     <li>再套 {@code extraNbt}：放在 {@code finalizeSpawn} <b>之后</b>，
     *         调用方给的数值（血量、名字、装备）才能覆盖掉随机结果；</li>
     *     <li>把坐标再钉一次：{@code Entity.load} 会读 NBT 里的 {@code Pos/Rotation}，
     *         调用方随手塞一个带坐标的 tag 就会把怪挪走（典型的「明明给了坐标却出在别处」）；</li>
     *     <li>{@code setPersistenceRequired()}：塔防的怪不能被原版「离玩家太远就消失」清掉；</li>
     *     <li>{@code addFreshEntity}，按它的返回值计数 —— 生成失败（区块拒收等）时
     *         返回的是 false，这里如实不计，不虚报。</li>
     * </ol>
     *
     * @param extraNbt 额外的实体 NBT，可为 null。<b>其中的 Pos/Rotation 会被忽略</b>（见上）。
     */
    public static int spawnOnce(ServerLevel level, Vec3 pos, EntityType<?> type, int count,
                                @Nullable CompoundTag extraNbt) {
        return spawnOnce(level, pos, type, count, extraNbt, 0);
    }

    /**
     * 同上，但指定<b>散开序列的起点下标</b>。
     *
     * <p>为什么需要它：一组怪里有多条（僵尸 3 只 + 骷髅 2 只），如果每条都从下标 0 开始散开，
     * 两条的落点会重合（都从中心那一格起步），表现为「挤成一坨」。让每条从不同的下标起步，
     * 落点就自然错开。</p>
     */
    public static int spawnOnce(ServerLevel level, Vec3 pos, EntityType<?> type, int count,
                                @Nullable CompoundTag extraNbt, int scatterStart) {
        if (level == null || pos == null || type == null || count <= 0) {
            return 0;
        }
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = buildOne(level, scatter(pos, scatterStart + i), type, extraNbt);
            if (entity == null) {
                continue;
            }
            if (level.addFreshEntity(entity)) {
                spawned++;
            }
        }
        return spawned;
    }

    /** 一组怪里「一条可出的怪」——解析通过后的中间结果。 */
    private record Ready(SpawnPointBlockEntity.Entry entry, EntityType<?> type) {
    }

    /** 算第 {@code index} 只的落点：0 号正好在中心，之后沿黄金角一圈圈往外散。 */
    private static Vec3 scatter(Vec3 origin, int index) {
        if (index <= 0) {
            return origin;
        }
        double angle = index * GOLDEN_ANGLE;
        double radius = Math.min(MAX_SCATTER_RADIUS, SCATTER_BASE_RADIUS * Math.sqrt(index));
        return new Vec3(origin.x + Math.cos(angle) * radius,
                origin.y,
                origin.z + Math.sin(angle) * radius);
    }

    /** 造一只（还没入场）。见 {@link #spawnOnce} 的顺序说明。 */
    @Nullable
    private static Entity buildOne(ServerLevel level, Vec3 at, EntityType<?> type,
                                   @Nullable CompoundTag extraNbt) {
        Entity entity = type.create(level);
        if (entity == null) {
            return null;
        }
        // 朝向随机，和原版刷怪一致；用 level.random 而不是 RandomSource 新的，
        // 这样同一世界的随机序列与其它刷怪共享，不会出现「自验跑过就改变了刷怪随机性」。
        float yRot = level.random.nextFloat() * 360.0F;
        entity.moveTo(at.x, at.y, at.z, yRot, 0.0F);

        if (entity instanceof Mob mob) {
            mob.yHeadRot = mob.getYRot();
            mob.yBodyRot = mob.getYRot();
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.MOB_SUMMONED, null, extraNbt);
        }
        if (extraNbt != null && !extraNbt.isEmpty()) {
            entity.load(extraNbt);
        }
        // ★ 再钉一次坐标：extraNbt 里若带 Pos，上面那行 load 已经把实体挪走了。
        entity.setPos(at.x, at.y, at.z);
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        return entity;
    }

    // ------------------------------------------------------------------
    // 排期推进
    // ------------------------------------------------------------------

    /**
     * 服务端每 tick 调用（由 {@code ModEvents} 挂到 {@code TickEvent.ServerTickEvent} 的 END 阶段）。
     *
     * <p>没有排期时是空操作，所以即使世界上一个出怪点都没有，代价也只是一次空表判断。</p>
     */
    public static void tickPending(MinecraftServer server) {
        if (PENDING.isEmpty() || server == null) {
            return;
        }
        Iterator<PendingBatch> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingBatch batch = it.next();
            if (batch.level.getServer() != server) {
                // 换了服务器实例（单人世界重开）—— 这一批已经不属于当前世界，丢弃。
                it.remove();
                continue;
            }
            if (batch.advance()) {
                it.remove();
            }
        }
    }

    /** 世界卸载：丢掉该世界的排期，避免跨世界出怪或内存泄漏。 */
    public static void clear(ServerLevel level) {
        if (level == null) {
            return;
        }
        PENDING.removeIf(batch -> batch.level == level);
    }

    /** 服务器停止：兜底清空（与其它 manager 的 clear 口径一致）。 */
    public static void clearAll() {
        PENDING.clear();
    }

    /**
     * 一个已排期的批次。
     *
     * <p>写成小类而不是几个平行列表：剩余只数、下一次等待、间隔这三者必须一起变，
     * 拆成三份数据就会出现「同一状态两处存放」（本项目反复踩过的坑）。</p>
     */
    private static final class PendingBatch {

        private final ServerLevel level;
        private final Vec3 origin;
        private final EntityType<?> type;
        private final int interval;
        /** 这一条一共要出几只（用来算散开偏移）。 */
        private final int total;
        /** 散开序列的起点下标（同一组内不同条目错开，避免落点重合）。 */
        private final int scatterStart;
        /** 距离下一次出怪还要等几 tick。 */
        private int wait;
        /** 还没出的只数。 */
        private int remaining;

        PendingBatch(ServerLevel level, Vec3 origin, EntityType<?> type,
                     int remaining, int interval, int delay, int scatterStart) {
            this.level = level;
            this.origin = origin;
            this.type = type;
            this.total = remaining;
            this.remaining = remaining;
            this.interval = interval;
            this.scatterStart = scatterStart;
            this.wait = delay;
        }

        /**
         * 推进一 tick。
         *
         * @return true 表示这一批已经出完、可以从排期表里摘掉
         */
        boolean advance() {
            // 「先减再判」而不是「先判再减」：delay=5 时正好在第 5 次 tick 出第一只。
            // 反过来写会变成第 6 次 —— 差一 tick 这种错在报告里根本看不出来。
            if (this.wait > 0) {
                this.wait--;
            }
            if (this.wait > 0) {
                return false;
            }
            // 间隔为 0 = 「整批一次出完」，所以一次性把剩下的全放掉，
            // 而不是「每 tick 一只」—— 后者会让「0」实际等于「1 tick 间隔」。
            int batchNow = this.interval <= 0 ? this.remaining : 1;
            // 这一条已经出过几只 = 总数 - 剩余；用它当散开序列的偏移，
            // 于是「同一组里不同条目」与「同一条目的先后批次」都不会落在同一个点上。
            int alreadyOut = this.total - this.remaining;
            int spawned = spawnOnce(this.level, this.origin, this.type, batchNow, null,
                    this.scatterStart + alreadyOut);
            this.remaining -= batchNow;
            if (this.remaining <= 0) {
                return true;
            }
            this.wait = this.interval;
            if (spawned == 0) {
                // 一只都没出成（区块没加载/实体被拒）：留着只会每 tick 空转，
                // 直接把这批丢掉并留一条日志，好过静默地永远排下去。
                GuardianProtocol.LOGGER.warn("[{}] 排期出怪一只都没生成，丢弃剩余 {} 只：{} {}",
                        GuardianProtocol.MODID, this.remaining, this.type, this.origin);
                return true;
            }
            return false;
        }
    }
}
