package com.guardianprotocol.command;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.ai.MoveToProtectTargetGoal;
import com.guardianprotocol.combat.BlockGeometry;
import com.guardianprotocol.combat.BlockCandidates;
import com.guardianprotocol.combat.DamageMods;
import com.guardianprotocol.combat.PawnCombatManager;
import com.guardianprotocol.combat.ProjectileFlight;
import com.guardianprotocol.combat.Skills;
import com.guardianprotocol.combat.Targeting;
import com.guardianprotocol.data.AttackRange;
import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.BranchRegistry;
import com.guardianprotocol.data.BuiltinBranch;
import com.guardianprotocol.data.PieceFacing;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.data.UnitClass;
import com.guardianprotocol.entity.ModEntities;
import com.guardianprotocol.entity.PawnProjectile;
import com.guardianprotocol.api.Invasions;
import com.guardianprotocol.entity.PixelUnit;
import com.guardianprotocol.spawn.invasion.BossEntry;
import com.guardianprotocol.spawn.invasion.CreatureRow;
import com.guardianprotocol.spawn.invasion.InvasionConfig;
import com.guardianprotocol.spawn.invasion.SpawnStrategy;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 自验：把「索敌选了谁 / 一次打几个 / 阻挡落点 / 出手间隔」变成**可读的文件结论**。
 *
 * <h3>两种跑法，同一份实现</h3>
 * <ol>
 *     <li><b>游戏内</b>：{@code /guardianprotocol selftest [场景]} —— 见
 *         {@link #onRegisterCommands}。报告追加写到运行目录下的 {@link #REPORT_FILE}。</li>
 *     <li><b>无头</b>：服务端启动时加 {@code -Dguardianprotocol.selftest=1}，
 *         由 {@code ModEvents.onServerStarted} 调 {@link #runAllAndHalt}。
 *         这条是为「把真实存档拖进来、跑一遍、把结论交回」准备的，
 *         因为**这个环境没法往服务端控制台敲命令**（stdin 管道不通，
 *         Forge 的 conditional-file runFunction 在 47.4.23 上也不触发）。</li>
 * </ol>
 * <p>两条路都调 {@link #runScenario}，所以不可能出现「游戏内跑的和无头跑的不是同一套」。</p>
 *
 * <h3>为什么要落盘而不是只在聊天框输出</h3>
 * <p>聊天框在无头服务端上没有可复制的回显，而报告文件是<b>服务端权威</b>的事实：
 * 谁选了什么目标、打了几下、间隔多少 tick，都带 tick 时间戳。
 * 这样「实机验证」不需要人盯着屏幕截图，也不受客户端渲染影响。</p>
 *
 * <h3>隔离性（保证不污染实机存档）</h3>
 * <ul>
 *     <li>只生成 {@code zombie/skeleton/cow/phantom} 与本 mod 的棋子；</li>
 *     <li>跑完把生成物全部 {@code discard()}，并在结果里回报清理数量；</li>
 *     <li>不放置、不破坏任何方块；</li>
 *     <li>无头跑法最后 {@code halt(false)}（<b>不保存</b>），所以测试世界不会留下痕迹。</li>
 * </ul>
 */
public final class DebugCommands {

    /** 自验报告文件名（写在服务端运行目录下，也就是 {@code run}/、{@code run-data}/ 或 {@code 自验运行目录}/）。 */
    public static final String REPORT_FILE = "guardianprotocol-selftest.log";

    /** 无头自验的开关：{@code -Dguardianprotocol.selftest=1}（见 {@link #enabledByProperty}）。 */
    public static final String PROP_ENABLE = "guardianprotocol.selftest";

    /** 场景名（顺序即 {@link #runScenario} 的默认全跑顺序）。 */
    public static final String[] SCENARIOS = {"interval", "piece", "datapack", "debug", "targeting", "multi", "block", "solid", "vertical", "skills", "attack", "aoe", "pause", "traits", "heal", "spawnpoint", "invasion", "live", "livedemo"};

    /**
     * 手动指定自验场地中心：{@code -Dguardianprotocol.selftest.base=x,y,z}。
     *
     * <p>真实存档里通常有建筑，世界出生点未必是空地；有了这个就能把场景**指定到**
     * 玩家站的地方（进游戏按 F3 看成坐标）。不指定时按
     * {@link #defaultBase} 的顺序自己找。</p>
     */
    public static final String PROP_BASE = "guardianprotocol.selftest.base";

    /**
     * 实机场景里棋子摆在保护目标的哪一侧、多远：
     * {@code -Dguardianprotocol.selftest.liveOffset=x,y,z}（相对保护目标方块的偏移）。
     *
     * <p>默认 {@code 4,0,0}（正东 4 格、朝西）。为什么要能调：棋子必须落在
     * 「出怪点 → 保护目标」的**必经之路**上才有意义 —— 摆太远，敌人还没走到就被
     * 保护目标抹杀了（实测踩过：摆在正东 8 格，10 秒观察里一个敌人都没来过）；
     * 摆太近又会被抹杀范围一起带走。不同存档的出怪点位置不同，所以留一个可调参数。</p>
     */
    public static final String PROP_LIVE_OFFSET = "guardianprotocol.selftest.liveOffset";

    /** 观察窗口 tick 数：{@code -Dguardianprotocol.selftest.liveTicks=200}。 */
    public static final String PROP_LIVE_TICKS = "guardianprotocol.selftest.liveTicks";

    /**
     * 只跑一个场景：{@code -Dguardianprotocol.selftest.scenario=piece}。
     *
     * <p>留空 = 全套。Gradle 侧写 {@code -PselftestScenario=piece}（见 build.gradle）。</p>
     */
    public static final String PROP_SCENARIO = "guardianprotocol.selftest.scenario";


    private DebugCommands() {
    }

    // ------------------------------------------------------------------
    // 调试出怪（设计口径：客户端验不了出怪）
    // ------------------------------------------------------------------
    //
    // 为什么需要它：本轮的「出怪点」被改成了**纯配置器**（方块不再自己出兵，见
    // SpawnPointBlockEntity#invasion 的注释），而按配置出怪的**相位机（S2）还没实现** ——
    // 于是「配好了却看不到任何怪」是当前版本的正常状态，玩家没有任何办法验证自己配得对不对。
    // 这三条命令（here / at / fire）就是那条验证通道：不走相位机、不排队、立刻出怪。

    /**
     * 本命令生成的怪统一打这个记名 tag。
     *
     * <p>{@code spawn clear} 只清带这个 tag 的实体，所以<b>不可能误删玩家自己的怪</b>；
     * 反过来说，随手用 {@code /kill @e[type=zombie]} 清场是危险的 —— 玩家世界里可能本来就有僵尸。
     * tag 走 NBT 的 {@code Tags}（{@code spawnOnce} 的 extraNbt 第 4 步会 load 它），
     * 因此也能用原版命令直接查：{@code /kill @e[tag=gp_debug_spawn]}。</p>
     */
    public static final String DEBUG_SPAWN_TAG = "gp_debug_spawn";

    /** 一条命令一次最多生成多少只（手滑写 9999 不该把服务端按死）。 */
    public static final int DEBUG_SPAWN_MAX = 256;

    /** 给 spawnOnce 的 extraNbt：只干一件事 —— 打上 {@link #DEBUG_SPAWN_TAG}。 */
    private static CompoundTag debugTagNbt() {
        ListTag tags = new ListTag();
        tags.add(net.minecraft.nbt.StringTag.valueOf(DEBUG_SPAWN_TAG));
        CompoundTag tag = new CompoundTag();
        tag.put("Tags", tags);
        return tag;
    }

    /**
     * 落点：标定器标的是「点击面的相邻格」，从侧面点出来的那一格可能是实心的。
     *
     * <p>直接按坐标生成会得到「怪卡在石头里（窒息 / 被挤出来）」，看起来像「出怪坏了」。
     * 这里往上找最多 3 格空气；找不到就还是用原坐标（宁可位置差，也不要静默不出怪）。</p>
     */
    private static Vec3 standVec(ServerLevel level, BlockPos pos) {
        BlockPos p = pos;
        for (int i = 0; i < 3 && !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); i++) {
            p = p.above();
        }
        return Vec3.atBottomCenterOf(p);
    }

    /** 生成 count 只并打上调试 tag，返回真正生成出来的只数。 */
    private static int spawnTagged(ServerLevel level, Vec3 pos, EntityType<?> type, int count) {
        return com.guardianprotocol.spawn.SpawnPointService.spawnOnce(level, pos, type, count,
                debugTagNbt(), 0);
    }

    /**
     * {@code /guardianprotocol spawn here|at ...}：立刻在指定位置生成指定生物。
     *
     * @param where 目标坐标；null = 执行者当前位置
     */
    private static int debugSpawnAt(CommandSourceStack src, @javax.annotation.Nullable Vec3 where, String entityId, int count) {
        ServerLevel level = src.getLevel();
        StringBuilder sb = new StringBuilder();
        sb.append("--- debug spawn at ---\n")
                .append("执行者：").append(src.getTextName())
                .append("  世界：").append(level.dimension().location()).append('\n')
                .append("请求：").append(entityId).append(" × ").append(count).append('\n');

        // ★ 复用出怪点那套解析（containsKey 先查，绕开 Forge「未注册 id 返回默认值 = 猪」的坑）
        EntityType<?> type = com.guardianprotocol.block.SpawnPointBlockEntity.resolveMobType(level, entityId);
        if (type == null) {
            String why = com.guardianprotocol.block.SpawnPointBlockEntity
                    .describeEntityId(level, entityId).getString();
            sb.append("结果：解析不出生物（").append(why).append("）\n");
            appendReport(sb.toString());
            src.sendFailure(Component.literal("解析不出生物：" + why));
            return 0;
        }

        Vec3 pos = where == null ? src.getPosition() : standVec(level, BlockPos.containing(where));
        int spawned = spawnTagged(level, pos, type, count);
        sb.append("类型：").append(type.getDescription().getString())
                .append("（").append(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                        .getKey(type)).append("）\n")
                .append("落点：").append(fmt(pos)).append('\n')
                .append("★ 实际生成：").append(spawned).append(" 只（tag=").append(DEBUG_SPAWN_TAG).append("）\n");
        appendReport(sb.toString());

        boolean ok = spawned > 0;
        src.sendSuccess(() -> Component.literal("已生成 " + spawned + "/" + count + " 只 "
                + type.getDescription().getString() + " @ " + fmt(pos)
                + "（清理：/guardianprotocol spawn clear）"), false);
        return ok ? spawned : 0;
    }

    /**
     * {@code /guardianprotocol spawn fire ...}：<b>强制</b>某个出怪点按它自己的配置立刻出一波。
     *
     * <p>「强制」的确切含义（这三条是这条命令存在的理由，别悄悄改）：</p>
     * <ul>
     *   <li>忽略 {@code enabled}：关着的点也照出 —— 否则「我明明关了它」与「命令没反应」分不清；</li>
     *   <li>忽略 {@code firstSpawn / interval}：不排队、不等待，但<b>发牌</b>在生成的兵源里
     *       <b>按出怪位置逐个生成</b>，不走 SpawnPointService 的排期表；</li>
     *   <li>忽略相位：不推进备战/战斗，也不碰保护目标血量（那是 S2 的事）。</li>
     * </ul>
     *
     * @param at       出怪点方块坐标；null = 离执行者最近的那个
     * @param override 覆盖「每个位置出几只」；&lt;= 0 = 用每行自己的 spawnCnt
     */
    private static int debugFire(CommandSourceStack src, @javax.annotation.Nullable BlockPos at, int override) {
        ServerLevel level = src.getLevel();
        List<com.guardianprotocol.block.SpawnPointBlockEntity> points =
                com.guardianprotocol.spawn.SpawnPointService.activePoints();

        StringBuilder sb = new StringBuilder();
        sb.append("--- debug spawn fire ---\n")
                .append("执行者：").append(src.getTextName())
                .append("  世界：").append(level.dimension().location()).append('\n')
                .append("已加载的出怪点：").append(points.size()).append(" 个")
                .append("（排期表：").append(com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount())
                .append(" 批 / ").append(com.guardianprotocol.spawn.SpawnPointService.pendingMobCount())
                .append(" 只）\n");

        com.guardianprotocol.block.SpawnPointBlockEntity point = null;
        if (at != null) {
            for (com.guardianprotocol.block.SpawnPointBlockEntity p : points) {
                if (p.getBlockPos().equals(at)) {
                    point = p;
                    break;
                }
            }
        } else {
            double best = Double.MAX_VALUE;
            Vec3 from = src.getPosition();
            for (com.guardianprotocol.block.SpawnPointBlockEntity p : points) {
                double d = Vec3.atCenterOf(p.getBlockPos()).distanceToSqr(from);
                if (d < best) {
                    best = d;
                    point = p;
                }
            }
        }

        if (point == null) {
            String why = at != null
                    ? "坐标 " + at.toShortString() + " 处没有出怪点方块实体"
                    : "世界上没有任何「已加载」的出怪点";
            sb.append("结果：").append(why).append('\n')
                    .append("提示：出怪点只在**所在区块被加载**时登记（走近它 / 传送过去再看）；")
                    .append("方块被拆掉也会注销。\n");
            appendReport(sb.toString());
            src.sendFailure(Component.literal(why + " —— 用 /guardianprotocol spawn list 看已加载的点"));
            return 0;
        }

        InvasionConfig cfg = point.invasion();
        sb.append("出怪点：").append(point.getBlockPos().toShortString())
                .append("  启用=").append(cfg.enabled())
                .append("  显示名=").append(cfg.displayName())
                .append("  稀有度=").append(cfg.rarity()).append('\n');

        boolean fallback = !cfg.hasAnyPosition();
        List<BlockPos> spots = fallback
                ? List.of(point.getBlockPos().above())
                : new ArrayList<>(cfg.positions());
        sb.append("出怪位置：");
        if (fallback) {
            sb.append("★ 配置里一个都没有 → 退回方块上方 ").append(spots.get(0).toShortString())
                    .append("（正式流程要求至少 1 个，用「出怪位置标定器」标）\n");
        } else {
            for (BlockPos p : spots) {
                sb.append(p.toShortString()).append(' ');
            }
            sb.append("（共 ").append(spots.size()).append(" 个，每个位置各出一份）\n");
        }

        int total = 0;
        int planned = 0;
        int rowIdx = 0;
        for (CreatureRow row : cfg.rows()) {
            rowIdx++;
            if (!row.isConfigured()) {
                sb.append("  第 ").append(rowIdx).append(" 行：空 → 跳过\n");
                continue;
            }
            EntityType<?> type = com.guardianprotocol.block.SpawnPointBlockEntity
                    .resolveMobType(level, row.entity());
            if (type == null) {
                String why = com.guardianprotocol.block.SpawnPointBlockEntity
                        .describeEntityId(level, row.entity()).getString();
                sb.append("  第 ").append(rowIdx).append(" 行：").append(row.entity())
                        .append(" → ★ 解析失败（").append(why).append("）跳过\n");
                continue;
            }
            int per = override > 0 ? override : Math.max(1, row.spawnCnt());
            int perPlanned = per * spots.size();
            planned += perPlanned;
            int got = 0;
            for (BlockPos spot : spots) {
                got += spawnTagged(level, standVec(level, spot), type, per);
            }
            total += got;
            sb.append("  第 ").append(rowIdx).append(" 行：").append(row.entity())
                    .append("（").append(type.getDescription().getString()).append("）")
                    .append(" 策略=").append(row.strategy().display)
                    .append(" 单次生成=").append(row.spawnCnt())
                    .append(row.firstSpawn() > 0 ? " 首次延迟=" + row.firstSpawn() : "")
                    .append(row.interval() > 0 ? " 间隔=" + row.interval() : "")
                    .append(" → 每处 ").append(per).append(" 只 × ").append(spots.size())
                    .append(" 处 = ").append(perPlanned).append(" 只，实际 ").append(got).append(" 只\n");
        }

        sb.append("★ 合计：计划 ").append(planned).append(" 只，实际生成 ").append(total)
                .append(" 只（均带 tag=").append(DEBUG_SPAWN_TAG).append("）\n")
                .append("★ 本次是强制出怪：忽略启用状态、首次延迟、生成间隔，也不推进任何相位\n");
        appendReport(sb.toString());

        if (total == 0) {
            src.sendFailure(Component.literal("一只都没出 —— 看报告里的原因："
                    + reportPath().toAbsolutePath()));
            return 0;
        }
        // lambda 里只能引用「事实上不可变」的局部变量，所以先落到 final 快照上
        final BlockPos pointPos = point.getBlockPos();
        final int done = total;
        final int want = planned;
        final int spotCount = spots.size();
        src.sendSuccess(() -> Component.literal("强制出怪：出怪点 " + pointPos.toShortString()
                + " → 生成 " + done + "/" + want + " 只，位置 " + spotCount + " 处"
                + "（清理：/guardianprotocol spawn clear）"), false);
        return total;
    }

    /** {@code /guardianprotocol spawn list}：现在有哪些出怪点、都配了什么。 */
    private static int debugSpawnList(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        List<com.guardianprotocol.block.SpawnPointBlockEntity> points =
                com.guardianprotocol.spawn.SpawnPointService.activePoints();
        StringBuilder sb = new StringBuilder();
        sb.append("--- debug spawn list ---\n")
                .append("世界：").append(level.dimension().location())
                .append("  已加载出怪点：").append(points.size()).append(" 个\n")
                .append("排期表：").append(com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount())
                .append(" 批 / ").append(com.guardianprotocol.spawn.SpawnPointService.pendingMobCount())
                .append(" 只\n");
        for (com.guardianprotocol.block.SpawnPointBlockEntity p : points) {
            InvasionConfig cfg = p.invasion();
            sb.append('\n').append(p.getBlockPos().toShortString())
                    .append(" 启用=").append(cfg.enabled())
                    .append(" 行数=").append(cfg.configuredRows().size())
                    .append(" BOSS=").append(cfg.configuredBosses().size())
                    .append(" 奖励=").append(cfg.rewards().size())
                    .append(" 出怪位置=").append(cfg.positions().size())
                    .append(" 保护目标=").append(cfg.targetPos() == null ? "未绑" : cfg.targetPos().toShortString())
                    .append('\n');
            List<String> problems = cfg.validate();
            if (!problems.isEmpty()) {
                sb.append("  校验提示（").append(problems.size()).append(" 条）：\n");
                for (String s : problems) {
                    sb.append("    · ").append(s).append('\n');
                }
            }
            int i = 0;
            for (CreatureRow row : cfg.rows()) {
                i++;
                if (!row.isConfigured()) {
                    continue;
                }
                sb.append("  第 ").append(i).append(" 行：").append(row.describe()).append('\n');
            }
        }
        appendReport(sb.toString());
        for (com.guardianprotocol.block.SpawnPointBlockEntity p : points) {
            InvasionConfig cfg = p.invasion();
            final BlockPos at = p.getBlockPos();
            final boolean on = cfg.enabled();
            final int rowCount = cfg.configuredRows().size();
            final int posCount = cfg.positions().size();
            final int problemCount = cfg.validate().size();
            src.sendSuccess(() -> Component.literal(at.toShortString()
                    + " 启用=" + on + " 行=" + rowCount
                    + " 位置=" + posCount + " 校验问题=" + problemCount), false);
        }
        src.sendSuccess(() -> Component.literal("已加载出怪点 " + points.size() + " 个 → 报告："
                + reportPath().toAbsolutePath()), false);
        return points.size();
    }

    /** {@code /guardianprotocol spawn clear}：只清本命令生成的怪（按 tag）。 */
    private static int debugSpawnClear(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e.getTags().contains(DEBUG_SPAWN_TAG)) {
                doomed.add(e);
            }
        }
        for (Entity e : doomed) {
            e.discard();
        }
        appendReport("--- debug spawn clear ---\n世界：" + level.dimension().location()
                + "  清了 " + doomed.size() + " 只（tag=" + DEBUG_SPAWN_TAG + "）\n");
        int n = doomed.size();
        src.sendSuccess(() -> Component.literal("已清理调试生成的怪 " + n + " 只（只清 tag="
                + DEBUG_SPAWN_TAG + " 的，玩家自己的怪不动）"), false);
        return n;
    }

    /** 打印一个坐标（报告与聊天共用一种写法）。 */
    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("guardianprotocol")
                .then(Commands.literal("selftest")
                        // 不带场景名 = 全部跑一遍
                        .executes(ctx -> runFromCommand(ctx.getSource(), null))
                        .then(Commands.argument("scenario", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (String s : SCENARIOS) {
                                        b.suggest(s);
                                    }
                                    b.suggest("path");
                                    return b.buildFuture();
                                })
                                .executes(ctx -> runFromCommand(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "scenario")))))
                // ---- 调试出怪：相位机（S2）还没做、方块自己也不出兵，所以给一条强制出怪的命令 ----
                .then(Commands.literal("spawn")
                        // 在「执行者脚下」生成（最常用）
                        .then(Commands.literal("here")
                                .then(Commands.argument("entity", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> debugSpawnAt(ctx.getSource(), null,
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "entity").toString(), 1))
                                        .then(Commands.argument("count",
                                                        IntegerArgumentType.integer(1, DEBUG_SPAWN_MAX))
                                                .executes(ctx -> debugSpawnAt(ctx.getSource(), null,
                                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "entity").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        // 在指定坐标生成
                        .then(Commands.literal("at")
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("entity", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                                                .executes(ctx -> debugSpawnAt(ctx.getSource(),
                                                                        debugVec(ctx), net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "entity").toString(), 1))
                                                                .then(Commands.argument("count",
                                                                                IntegerArgumentType.integer(1, DEBUG_SPAWN_MAX))
                                                                        .executes(ctx -> debugSpawnAt(ctx.getSource(),
                                                                                debugVec(ctx), net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "entity").toString(),
                                                                                IntegerArgumentType.getInteger(ctx, "count")))))))))
                        // 强制某个出怪点按配置立刻出一波（忽略启用 / 延迟 / 间隔）
                        .then(Commands.literal("fire")
                                .executes(ctx -> debugFire(ctx.getSource(), null, -1))
                                .then(Commands.literal("n")
                                        .then(Commands.argument("count",
                                                        IntegerArgumentType.integer(1, DEBUG_SPAWN_MAX))
                                                .executes(ctx -> debugFire(ctx.getSource(), null,
                                                        IntegerArgumentType.getInteger(ctx, "count")))))
                                .then(Commands.literal("at")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> debugFire(ctx.getSource(), debugPos(ctx), -1))
                                                                .then(Commands.literal("n")
                                                                        .then(Commands.argument("count",
                                                                                        IntegerArgumentType.integer(1, DEBUG_SPAWN_MAX))
                                                                                .executes(ctx -> debugFire(ctx.getSource(), debugPos(ctx),
                                                                                        IntegerArgumentType.getInteger(ctx, "count"))))))))))
                        // 看看现在有哪些出怪点、都配了什么
                        .then(Commands.literal("list").executes(ctx -> debugSpawnList(ctx.getSource())))
                        // 清掉本命令生成的怪（按记名 tag，绝不碰玩家自己的怪）
                        .then(Commands.literal("clear").executes(ctx -> debugSpawnClear(ctx.getSource()))))
                // ---- 技能位（预设技能）：**不带参数 = 只读看状态**；带槽位 = 切槽（会清技力）----
                //
                // ★ 参数**故意不写 1..3 的范围**（写 `integer(1,3)` 会让 Brigadier 用它的英文
                //   默认文案直接拒绝 5，而我们要的是「为什么不行」——越界与「这个分支只有 2 个技能」
                //   是同一句中文判据（combat.Skills.slotRejectReason），只此一处。
                // ★ 无参那一条是**只读**入口（设计口径：技力满即自动放、没有手动释放，
                //   所以「看着技力涨、看它到点放」只能靠一条绝不改状态的入口 —— 切槽那条做不到，
                //   它会把技力清零）。两条路径共用 nearestPawnOrFail：**找 8 格内最近的棋子这条判据
                //   只此一处实现**，不许各自再写一份循环。
                .then(Commands.literal("skill")
                        .executes(ctx -> debugShowSkill(ctx.getSource()))
                        .then(Commands.argument("slot", IntegerArgumentType.integer())
                                .executes(ctx -> debugSetSkill(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "slot")))))
                // ---- 阻挡诊断：**只读**看「怪到底卡在哪一环」（不还手这个缺陷的实机定位器）----
                //
                // 实测报告的缺陷：「棋子阻挡怪物时，怪物不攻击棋子」。三次尝试（改站位判据 /
                // 改「到锚点就停」的口径 / 放大受击盒到 2.4）在实机上都没解决，前两次已回退、第三次
                // 也已回退（它不治本还牵出真缺陷，见 踩坑记录）。所以改用**可观测**：
                // 这条命令把「白名单 / 棋子 / 附近每只怪的逐项判据 / 一句结论」打出来，
                // 让实机里那一次「怪到底卡在哪一环」直接看得见。
                //
                // ★ 只读：一个字段都不改、不发包、不触发任何行为。写法与注释风格照
                //   `skill`（不带参数）那条只读入口；「找 8 格内最近的棋子」照旧共用
                //   nearestPawnOrFail（只此一处实现），失败文案与切槽那条逐字同一条。
                .then(Commands.literal("block")
                        .executes(ctx -> debugShowBlock(ctx.getSource()))));
    }

    /**
     * {@code /guardianprotocol skill}（**不带参数**）：**只读** —— 把「离执行者最近的棋子」的
     * 技能状态发到聊天框，<b>一个字段都不改、也不触发技能</b>。
     *
     * <p><b>为什么必须有一条只读入口</b>（设计口径）：技力满就自动放，<b>绝对没有手动释放 /
     * 手动关闭</b>；于是玩家想「看着技力涨、看它到点放」时，{@code skill <槽位>} 那条路**走不通**
     * —— 它切槽会把技力清零。本方法只调 {@link Skills#describe(PixelUnit)}（它自己也不发包、不改状态），
     * 与切槽路径共用 {@link #nearestPawnOrFail}：附近没有棋子时给的是**同一条**失败文案。</p>
     *
     * @return 1 = 状态已发出；0 = 附近没有棋子（失败文案由辅助方法已发）
     */
    private static int debugShowSkill(CommandSourceStack src) {
        PixelUnit target = nearestPawnOrFail(src);
        if (target == null) {
            return 0;
        }
        // ★ 成功反馈那行要在 lambda 里读这颗棋子，捕获的必须是**有效 final** 变量（同 debugSetSkill）
        final PixelUnit unit = target;
        src.sendSuccess(() -> Component.literal("技能状态（只读，未改动）：" + Skills.describe(unit)
                + "　｜　要换技能位用 /guardianprotocol skill <槽位>（1~3；注意：切槽会把技力清零）"), false);
        return 1;
    }

    /**
     * {@code /guardianprotocol block}（**不带参数**）：**只读** —— 把「离执行者最近的棋子」的
     * <b>阻挡现场</b>发到聊天框（多行），用来在实机上定位实测报告的缺陷
     * 「<b>棋子阻挡怪物时，怪物不攻击棋子</b>」。
     *
     * <h3>为什么需要它（三次盲改都失败的教训）</h3>
     * <p>前三次尝试（改站位判据 / 改「到锚点就停」的口径 / 把受击盒放大到 2.4）在实机上都没解决，
     * 前两次回退、第三次也已回退（不治本且牵出真缺陷，见 踩坑记录）。三次共同的问题是
     * <b>拿不到现场</b>：服务端里「这只怪到底被挡住了没有 / 攻击目标是不是棋子 / 离得够不够近 /
     * 它自己的寻路停没停」四件事一件都看不见，只能靠猜。这条命令把那四件事一次打全。</p>
     *
     * <h3>只读保证（与 {@link #debugShowSkill} 同一套写法）</h3>
     * <p>本方法只做两件事：调 {@link #debugBlockReport}（纯读，连 {@code onServerTick} 都不碰）
     * 与把结果发给执行者。**一个字段都不改、不发包、不触发任何行为**；
     * 「找 8 格内最近的棋子」照旧共用 {@link #nearestPawnOrFail}（只此一处实现），
     * 附近没有棋子时给的是与技能那条**逐字相同**的失败文案。
     * 只读性由自验场景 {@code block} 的 E 组钉住（四项同步字段 —— 槽位 / 技力 / 激活剩余 / 弹药
     * + 被挡名单大小 + 场上实体数全不变，外加一条「命令真的跑到了」的对照断言）。</p>
     *
     * @return 1 = 诊断已发出；0 = 附近没有棋子（失败文案由辅助方法已发）
     */
    private static int debugShowBlock(CommandSourceStack src) {
        PixelUnit target = nearestPawnOrFail(src);
        if (target == null) {
            return 0;
        }
        final PixelUnit unit = target;
        String text = debugBlockReport(src.getLevel(), unit);
        // ★ 自验的对照断言要的是「命令实际输出了非空内容」——把它留一份（见 lastBlockReport 的注释）。
        //   命令与自验共用 debugBlockReport，所以这里存的就是玩家聊天框里看到的同一段文本。
        lastBlockReport = text;
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /**
     * 最近一次 {@code /guardianprotocol block} 实际发出的诊断文本（自验用）。
     *
     * <p><b>为什么要有这个字段</b>：自验里「命令是否只读」的断言只有在「命令真的跑到了」时才成立
     * —— 否则一个「什么都没做的命令」会让所有「不变」的断言假通过（同族教训见 踩坑记录）。
     * 命令与自验共用 {@link #debugBlockReport}，但**只有走命令那条路**才会写这个字段，
     * 所以它恰好能证明「brigadier 解析 → 命令体 → 报告文本」整条链路真的通了。</p>
     */
    private static String lastBlockReport = "";

    /** 自验用：读最近一次 {@code /guardianprotocol block} 的输出（没跑过时是空串）。 */
    public static String debugLastBlockReport() {
        return lastBlockReport;
    }

    /**
     * 诊断报告的窗口半径（格）：{@code /guardianprotocol block} 里「附近每只怪一行」的范围。
     *
     * <p>与 {@link #SKILL_COMMAND_RANGE}（找棋子的半径）数值相同但**语义不同**：那个决定「命令
     * 作用于哪颗棋子」，这个只决定「报告列出多远的怪」。两者各自的判据分开写，不共用常量。</p>
     */
    private static final double BLOCK_REPORT_RANGE = 8.0D;

    /**
     * 生成 {@code /guardianprotocol block} 的**全部诊断文本** —— 命令唯一的产出点；
     * 自验读的是命令实际发出的**同一段文本**（见 {@link #debugLastBlockReport()}），
     * 所以「报告里写了什么」与「玩家聊天框看到什么」不可能不一致。
     *
     * <p>内容（顺序固定，便于人眼按行对照）：</p>
     * <ol>
     *     <li><b>生效白名单</b>：{@link BlockCandidates#whitelistInEffect()} 的当前值 + 来源
     *         （自验/调试覆盖 / 配置文件）+ 条数与「是不是空」；</li>
     *     <li><b>棋子</b>：分支 / 宽度 / <b>阻挡数（表/技能期）+ 常态阻挡 + 碰撞开关</b>（★ 2026-10：
     *         让实机一眼看出「这颗棋子该不该挡路」，口径见 踩坑记录）/ 技能位 /
     *         被挡名单大小；</li>
     *     <li><b>附近 8 格内每只 {@link PathfinderMob} 一行</b>：名称 / 中心距 / 按原版公式算出的
     *         攻击距离 / 是否在射程内 / {@code nav.isDone} / target 是不是这颗棋子 / 在不在被挡名单 /
     *         <b>★ 在不在拉怪范围 + 拉怪门是开是关</b>（2026-10 新增；两个读数都来自
     *         {@link PawnCombatManager#debugLureCandidates}，诊断**不重算**范围）/
     *         {@link BlockCandidates#rejectReason}（没被挡住的原因，{@code null} = 准入）；
     *         <b>★ 后面紧跟两行 goal 读数</b>（{@link #goalReadout}：正在跑的 goal + 谁占着 MOVE 旗标），
     *         见下面第 3.5 条；</li>
     *     <li><b>3.5) goal 读数（★ 2026-10 新增，实机复查「打不还手」靠它）</b>：每只怪后面
     *         「运行中的 goal（只算 {@code isRunning}）」+「占用 MOVE 的运行中 goal = 类简名(priority N)
     *         或 （MOVE 空闲）」两行 —— 口径收窄的理由见 {@link #goalReadout} 的 javadoc；</li>
     *     <li><b>3.9) 拉怪结论（★ 2026-10 新增，三条，排在阻挡结论之前）</b>：<b>⓪′</b> 门是开是关
     *         （关 = 解放者 / 阵法术师 / 吟游者，它们不拉怪）/ <b>⓪″</b> 在拉怪范围内 + 门开 +
     *         target 不是棋子 ⇒ <b>异常</b>（本 mod 没锁上）/ <b>⓪‴</b> 附近没有一只在拉怪范围内
     *         ⇒ 不该被拉。★ 位置是判据的一部分：门关着时「范围内一只都没被锁」是**正常**的，
     *         让它落到下面 ①/②/③ 就成了假警报；</li>
     *     <li><b>一行结论</b>：把「卡在哪一环」直接写出来（各条分支见 {@code verdict}；
     *         其中 <b>⓪</b> 是 2026-10 新增的「常态阻挡为 0 ⇒ 它本来就不是墙」，排在白名单那一条**前面**；
     *         <b>⑤</b> 另补一句「MOVE 被嘲讽 goal 占用 + target 是棋子 ⇒ 近战 goal 正被饿死」的可判读结论）。</li>
     * </ol>
     *
     * <p><b>★ 距离与攻击距离只有一处实现</b>：全部走 {@link PawnCombatManager#centerDistance} /
     * {@link PawnCombatManager#meleeAttackReach} / {@link PawnCombatManager#isWithinMeleeAttackRange}
     * —— 本方法**不内联任何公式**，也不自己再写一份「够不够得着」。</p>
     *
     * <p><b>只读</b>：只读实体与缓存的当前值，不改任何状态、不发包、不推进任何逻辑。</p>
     *
     * @return 多行文本（至少 4 行，一行都不为空）
     */
    public static String debugBlockReport(ServerLevel level, PixelUnit unit) {
        List<String> whitelist = BlockCandidates.whitelistInEffect();
        List<String> configured = List.copyOf(com.guardianprotocol.GuardianConfig.blockingWhitelist());
        // 「覆盖」的判据：生效值与配置文件那份不一致。两者恰好相同时无法区分（覆盖只可能来自自验/调试），
        // 所以来源那一行写的是「检测到/未检测到覆盖」，而不是断言「一定是配置文件」。
        boolean overridden = !whitelist.equals(configured);
        List<PathfinderMob> held = PawnCombatManager.debugBlocked(unit);
        // ★ 拉怪（2026-10 重做）：诊断**不重算任何距离/范围** —— 直接调 combat 里那份只读口子，
        //   它内部就是索敌用的同一个 candidatesInRange（只留下能被下目标的 PathfinderMob）。
        //   口径：拉怪范围 = 棋子的攻击范围。
        List<LivingEntity> lureCandidates = PawnCombatManager.debugLureCandidates(level, unit);
        boolean lureGate = unit.getBranch().canAttack();

        StringBuilder sb = new StringBuilder();
        sb.append("--- /guardianprotocol block（只读诊断：怪为什么不还手？一个字段都不改）---\n");
        sb.append("   口径：攻击距离 = √((2×怪宽)² + 棋子宽)，够不够得着 = 中心距 ≤ 攻击距离（原版 "
                        + "MeleeAttackGoal#getAttackReachSqr；唯一实现在 PawnCombatManager）\n");

        // ---- 1) 生效白名单 ----
        sb.append("1) 生效白名单：").append(whitelist)
                .append("（共 ").append(whitelist.size()).append(" 条，")
                .append(whitelist.isEmpty() ? "空 = 谁都不挡" : "非空").append("）\n");
        sb.append("   来源：").append(overridden
                        ? "自验/调试覆盖（BlockCandidates.setWhitelistOverride）—— 配置文件那一份当前没生效"
                        : "配置文件 [blocking] whitelist（未检测到自验/调试覆盖；"
                        + "文件里没有这一行时 = 代码默认 [\"#monster\"]）")
                .append('\n');

        // ---- 2) 棋子 ----
        sb.append("2) 棋子：").append(unit.getBranch().branchName())
                .append("（").append(unit.getBranch().key()).append("）")
                .append(" 位置=").append(shortVec(unit.position()))
                .append(" 方块格=").append(fmt(unit.blockPosition()))
                .append(" 朝向=").append(unit.getFacing().displayName())
                .append(" 宽度=").append(r2(unit.getBbWidth())).append('\n');
        sb.append("   阻挡数(表/技能期)=").append(unit.getBranch().blockCount())
                .append(" 常态阻挡=").append(unit.getBranch().normalBlockCount())
                .append("（★ 0 = 没有碰撞箱、也不占阻挡名额：怪能从它身上走过去）")
                .append(" 技能位=").append(unit.getSkillSlot())
                .append(" 碰撞开关 canBeCollidedWith=").append(unit.canBeCollidedWith())
                .append(" 被挡名单=").append(held.size()).append(" 个（口径只此一处：debugBlocked）")
                .append('\n');
        sb.append("   锚点：").append(PawnCombatManager.anchorStrategy().name())
                .append("（正前 ").append(r1(BlockGeometry.ANCHOR_FORWARD))
                .append(" 格 / 横向按名次 ").append(r1(BlockGeometry.ANCHOR_LATERAL_STEP))
                .append(" 格；到达判定 ").append(r1(BlockGeometry.ANCHOR_ARRIVE_DISTANCE))
                .append(" 格 —— 口径不许改）\n");

        // ---- 3) 附近每只怪一行 ----
        AABB around = new AABB(unit.blockPosition()).inflate(BLOCK_REPORT_RANGE);
        List<PathfinderMob> mobs = new ArrayList<>(level.getEntitiesOfClass(PathfinderMob.class, around,
                mob -> !(mob instanceof PixelUnit)
                        && !mob.isRemoved() && !mob.isDeadOrDying()
                        && PawnCombatManager.centerDistance(unit, mob) <= BLOCK_REPORT_RANGE));
        mobs.sort(java.util.Comparator.comparingDouble(unit::distanceToSqr));
        sb.append("3) 附近 ").append((int) BLOCK_REPORT_RANGE)
                .append(" 格内的 PathfinderMob（已排除棋子自己）：").append(mobs.size()).append(" 只\n");
        int heldButNotTargeting = 0;
        int targetingButTooFar = 0;
        for (PathfinderMob mob : mobs) {
            boolean inHeld = held.contains(mob);
            boolean inReach = PawnCombatManager.isWithinMeleeAttackRange(mob, unit);
            boolean targeting = mob.getTarget() == unit;
            String reason = BlockCandidates.rejectReason(mob);
            if (inHeld && !targeting) {
                heldButNotTargeting++;
            }
            if (targeting && !inReach) {
                targetingButTooFar++;
            }
            sb.append("   · ").append(name(mob))
                    .append(" 中心距=").append(r2(PawnCombatManager.centerDistance(unit, mob))).append("格")
                    .append(" 攻击距离=").append(String.format(Locale.ROOT, "%.4f",
                            PawnCombatManager.meleeAttackReach(mob, unit))).append("格")
                    .append("（怪宽 ").append(r2(mob.getBbWidth())).append("）")
                    .append(" 在射程内=").append(inReach)
                    .append(" nav.isDone=").append(mob.getNavigation().isDone())
                    .append(" target=").append(targeting ? "棋子"
                            : (mob.getTarget() == null ? "无" : "别的东西"))
                    .append(" 被挡名单=").append(inHeld ? "是" : "否")
                    .append(" 在拉怪范围=").append(lureCandidates.contains(mob) ? "是" : "否")
                    .append(" 拉怪门=").append(lureGate ? "开(canAttack)" : "关(canAttack)")
                    .append(" 未挡住原因=").append(reason == null ? "null（准入）" : reason)
                    .append('\n');
            // ★★ 2026-10 新增：每只怪后面再挂两行 **goal 读数** —— 这是实机复查「打不还手」
            //   唯一能直接看出「近战 goal 有没有资格起跑」的现场（机理与判据见
            //   MoveToProtectTargetGoal#canUse 的注释）。只读、不 tick、不改任何状态。
            sb.append(goalReadout(mob));
        }

        // ---- 3.9) 拉怪结论（★ 三条，**排在阻挡结论之前**）----
        //   ★ 为什么必须排在前面：门关着（canAttack=false：解放者 / 阵法术师 / 吟游者）时
        //     「范围内一只都没被锁」是**正常**的 —— 让它落到下面 ①/②/③ 里，
        //     玩家看到的就是「怪没被锁 ⇒ 出问题了」这种**假警报**（误报等于没有仪器）。
        int lureMissed = 0;
        if (lureGate) {
            for (LivingEntity e : lureCandidates) {
                // 候选一定都是 PathfinderMob（见 PawnCombatManager.lureCandidatesIn）；
                // 这里必须落成 Mob 才拿得到 getTarget()（LivingEntity 上没有这个方法）。
                if (e instanceof PathfinderMob p && p.getTarget() != unit) {
                    lureMissed++;
                }
            }
        }
        sb.append("3.9) 拉怪结论（★ 三条排在阻挡结论之前 —— 门关着时「范围内一只都没被锁」是正常的，"
                        + "不能报成异常）：\n");
        sb.append("   ⓪′ 拉怪门=").append(lureGate ? "开(canAttack)" : "关(canAttack)")
                .append(lureGate
                        ? " ⇒ 每 tick 把棋子攻击范围内的敌人锁成 target=棋子"
                        + "（口径 = 棋子的攻击范围；判据与索敌同一份 candidatesInRange，只此一处）"
                        : "（" + unit.getBranch().branchName()
                        + " 属于「通常不攻击」那一族：解放者 / 阵法术师 / 吟游者）⇒ 它**不拉怪**"
                        + " —— 它进攻范围内的怪没被锁是正常的")
                .append('\n');
        sb.append("   ⓪″ ").append(!lureGate
                        ? "门关着 ⇒ 「一只都没被锁」不是异常（本分支不拉怪，见 ⓪′）"
                        : (lureMissed == 0
                        ? "在拉怪范围内 + 门开 + target 是棋子的有 " + lureCandidates.size()
                        + " 只 ⇒ 无异常"
                        : "**异常**：在拉怪范围内 + 门开，但 target 不是棋子的有 " + lureMissed
                        + " 只 ⇒ 那是本 mod 没锁上（对照 3) 里每只怪的「在拉怪范围 / 拉怪门」两列）"))
                .append('\n');
        sb.append("   ⓪‴ ").append(lureCandidates.isEmpty()
                        ? "附近没有一只在拉怪范围内 ⇒ 不该被拉（把棋子摆到怪经过的路上，"
                        + "或等怪走进棋子的攻击范围）"
                        : "在拉怪范围内共 " + lureCandidates.size() + " 只（这批就是「该被拉」的）")
                .append('\n');

        // ---- 4) 一行结论（帮实机定位「卡在哪一环」）----
        String verdict;
        if (unit.getBranch().normalBlockCount() <= 0) {
            // ★ 2026-10 设计口径：常态阻挡为 0 的棋子**去掉碰撞箱**（怪从它身上走过去）。
            //   这一条必须排在白名单那一条**前面** —— 这种棋子根本不是「墙」，
            //   白名单里有没有敌人对它都一样（命令报「谁都不挡」会把人引到错的方向）。
            verdict = "⓪ 这颗棋子的**常态阻挡为 0**（表里「阻挡数」=" + unit.getBranch().blockCount()
                    + " 是技能期/显示值）⇒ 它**没有碰撞箱**、也不占阻挡名额，怪会从它身上走过去 —— "
                    + "它本来就不是「墙」，被挡名单为空是正常的（设计口径）";
        } else if (whitelist.isEmpty()) {
            verdict = "① 白名单为空（谁都不挡）⇒ 怪不会被按住，也就不会被锁成打棋子 —— "
                    + "先在 config 的 [blocking] whitelist 里列出敌人";
        } else if (held.isEmpty()) {
            verdict = "② 被挡名单为空 + 白名单非空 ⇒ 怪还没走到棋子那一格（FOOT 候选盒 = 棋子脚下那一格），"
                    + "先让它走到棋子面前再看";
        } else if (heldButNotTargeting > 0) {
            verdict = "③ 在名单里但 target 不是棋子（" + heldButNotTargeting
                    + " 只）⇒ 那是本 mod 没锁上（driveToAnchor → lockAttackTarget）";
        } else if (targetingButTooFar > 0) {
            verdict = "④ target 是棋子但中心距 > 攻击距离（" + targetingButTooFar + " 只）⇒ 够不着";
        } else {
            verdict = "⑤ 以上都不满足（被挡住 + 锁上了 + 够得着）⇒ 该是原版 goal 没跑，看实机"
                    + "（原版没有任何 goal 会「主动」把棋子选成目标；每 tick 锁定它的地方有两处："
                    + "**拉怪**（攻击范围内，见 3.9）与**「被挡住」这一路**（driveToAnchor → lockAttackTarget））"
                    + "　★ 先看上面每只怪那两行 goal 读数：**MOVE 被 MoveToProtectTargetGoal 占用 + 它的 target"
                    + " 是棋子 ⇒ 近战 goal 正被饿死（这条就是「打不还手」）**；修复后这里应当看不到嘲讽 goal 在跑，"
                    + "占用者应当是它自己的近战 goal 或 MOVE 空闲";
        }
        sb.append("4) 结论：").append(verdict).append('\n');
        return sb.toString();
    }

    /**
     * ★★ 2026-10 新增：<b>一只怪的 goal 读数</b>（报告 3) 里每只怪后面那两行）——
     * 「谁在跑、谁占着 MOVE 旗标」。唯一实现：命令报告（{@link #debugBlockReport}）与
     * 自验场景 {@code block} 的 <b>X 组</b>读的是同一个方法，所以「玩家聊天框看到什么」与
     * 「断言比的是什么」不可能不一致。
     *
     * <h3>为什么是它（这才是「打不还手」的现场）</h3>
     * <p>实机 A/B 定位出来的真凶是<b>旗标饿死</b>：嘲讽 goal 挂在 priority 0 且独占
     * {@code Flag.MOVE}，而原版近战 goal（僵尸 {@code ZombieAttackGoal} 挂 priority 2、
     * 要 {@code MOVE + LOOK}）能不能起跑由原版 {@code GoalSelector} 判：
     * {@code GoalSelector#goalCanBeReplacedForAllFlags}（:70，遍历候选要的每一面旗标，
     * 要求锁着该旗标的那一个 {@code canBeReplacedBy(候选)}）+ {@code GoalSelector#tick}（:103 起跑判据），
     * 而 {@code WrappedGoal#canBeReplacedBy(o)} = {@code this.isInterruptable() && this.getPriority()
     * >= o.getPriority()}；把它写成「候选的 priority 必须<b>更小</b>才能顶掉现在的占用者」
     * 就是同一条判据 —— 于是「锁着 MOVE 的是 0、候选是 2」时近战 goal 永远起不来。
     * 这一条在实机上<b>看不见</b>（没有第二条命令能打出 goal 列表），所以把它加进报告。</p>
     *
     * <h3>★★ 判定口径必须收窄（上一版第一稿在这里误报过一次）</h3>
     * <ul>
     *     <li><b>只有 {@code isRunning()} 的 goal 才算</b> —— 僵尸自带一串<b>未在跑</b>的 MOVE goal
     *         （随机游荡、随机看向等），把它们列成「占用者」会得到一份自相矛盾的报告
     *         （一边喊「旗标饿死」一边列五个占用者）。<b>误报等于没有仪器</b>；</li>
     *     <li>多个在跑的 MOVE goal 同时存在时（原版正常情况下不会有：同一面旗标只留一个占用者），
     *         报<b>priority 数字最小</b>的那个 —— 按上面那条判据，数字越小的占用者越难被顶掉，
     *         所以它才是真正的锁者（口径与自验 X5 的原版语义夹具同源）；</li>
     *     <li>没有在跑的 MOVE goal 时写 {@code （MOVE 空闲）}，<b>不许</b>把「有 MOVE goal 但没在跑」
     *         说成占用。</li>
     * </ul>
     *
     * <p><b>只读</b>：只遍历 {@code goalSelector.getAvailableGoals()} 读 {@code isRunning()} /
     * {@code getPriority()} / {@code getGoal().getFlags()}，不 tick、不挂摘 goal、不改任何字段。</p>
     *
     * @return 两行文本（含结尾换行），供报告直接 append
     */
    static String goalReadout(PathfinderMob mob) {
        List<WrappedGoal> running = new ArrayList<>();
        WrappedGoal moveLock = null;
        for (WrappedGoal wg : mob.goalSelector.getAvailableGoals()) {
            if (!wg.isRunning()) {
                continue;   // ★ 未在跑的 goal **不占旗标**：这是本方法唯一的关键判据
            }
            running.add(wg);
            if (wg.getGoal().getFlags().contains(Goal.Flag.MOVE)
                    && (moveLock == null || wg.getPriority() < moveLock.getPriority())) {
                moveLock = wg;   // 多个时取 priority 数字最小者（最难被顶掉的那个 = 真正的锁者）
            }
        }
        // 排序只为「同一版本输出稳定」：先 priority，再类简名。
        running.sort(Comparator.comparingInt(WrappedGoal::getPriority)
                .thenComparing((WrappedGoal w) -> w.getGoal().getClass().getSimpleName()));

        StringBuilder out = new StringBuilder();
        out.append("     ↳ 运行中的 goal（只算 isRunning，共 ").append(running.size()).append(" 个）：");
        if (running.isEmpty()) {
            out.append("（没有运行中的 goal）");
        } else {
            for (int i = 0; i < running.size(); i++) {
                if (i > 0) {
                    out.append("｜");
                }
                out.append(goalLabel(running.get(i)));
            }
        }
        out.append('\n');
        out.append("     ↳ 占用 MOVE 的运行中 goal = ")
                .append(moveLock == null ? "（MOVE 空闲）" : goalLabel(moveLock));
        // 可判读的结论：只有「锁者就是嘲讽 goal，而且它锁的是棋子」时才说饿死 ——
        // 否则会变成一句永远挂在那里、跟现场无关的标语（同「误报等于没有仪器」）。
        if (moveLock != null && moveLock.getGoal() instanceof MoveToProtectTargetGoal
                && mob.getTarget() instanceof PixelUnit pawn && pawn.isAlive()) {
            out.append("　⇒ MOVE 被嘲讽 goal 占用 + 它的 target 是棋子 ⇒ 它自己的近战 goal 正被**饿死**"
                    + "（这条就是「打不还手」）");
        }
        out.append("　（修复后这里应当看不到嘲讽 goal 在跑，占用者应当是它自己的近战 goal 或 MOVE 空闲）");
        out.append('\n');
        return out.toString();
    }

    /** goal 的一行标签：{@code 类简名(priority N, flags=[MOVE, LOOK])}（打印口径只此一处）。 */
    private static String goalLabel(WrappedGoal wg) {
        return wg.getGoal().getClass().getSimpleName()
                + "(priority " + wg.getPriority()
                + ", flags=" + wg.getGoal().getFlags() + ")";
    }

    /**
     * 只读：某个 goal <b>实例</b>此刻在 selector 里跑没跑（按实例身份找 {@code WrappedGoal}）。
     *
     * <p>自验 X 组用：{@code WrappedGoal#isRunning()} 在 {@code WrappedGoal} 上（{@code Goal} 自己没有
     * 这个口子），所以必须先按实例找到那个包装（坑：上一轮就是在这里找错了对象）。</p>
     *
     * @return 找到且正在跑 = true；没找到 / 没在跑 = false
     */
    private static boolean isGoalRunning(PathfinderMob mob, Goal goal) {
        for (WrappedGoal wg : mob.goalSelector.getAvailableGoals()) {
            if (wg.getGoal() == goal) {
                return wg.isRunning();
            }
        }
        return false;
    }

    /** 只读：某个 goal 实例注册时的 priority；没找到返回 -1（自验用）。 */
    private static int goalPriority(PathfinderMob mob, Goal goal) {
        for (WrappedGoal wg : mob.goalSelector.getAvailableGoals()) {
            if (wg.getGoal() == goal) {
                return wg.getPriority();
            }
        }
        return -1;
    }

    /**
     * {@code /guardianprotocol skill <槽位>}：给**离执行者最近的棋子**切技能位。
     *
     * <p>为什么是「最近的棋子」而不是「执行者自己」：技能位是<b>棋子</b>（{@code PixelUnit}）
     * 的属性，玩家身上没有这个字段。命令里给实体选择器也行，但本轮只需要一个能用的调试口子
     * —— 真正的入口是后续项目的界面（设计口径：界面留给后面）。</p>
     *
     * <p>拒绝的两类（都走 {@code combat.Skills.slotRejectReason} 这一条判据，
     * <b>不静默夹取</b>）：① 槽位越界（不是 1~3）；② 超过**该分支实际技能数**
     * —— 表里有 4 个分支只有 2 个技能（近卫·佣兵 / 近卫·本源近卫 / 狙击·裂空炮手 / 辅助·游击手）。</p>
     *
     * <p>切槽会把技力清零（新技能从 0 开始攒），不会顺带放一次技能。</p>
     */
    private static int debugSetSkill(CommandSourceStack src, int slot) {
        PixelUnit nearest = nearestPawnOrFail(src);
        if (nearest == null) {
            return 0;
        }
        String why = com.guardianprotocol.combat.Skills.slotRejectReason(nearest.getBranch(), slot);
        if (why != null) {
            src.sendFailure(Component.literal("拒绝切技能位：" + why));
            return 0;
        }
        String refused = com.guardianprotocol.combat.Skills.setSlot(nearest, slot, 0);
        if (!refused.isEmpty()) {
            src.sendFailure(Component.literal("拒绝切技能位：" + refused));
            return 0;
        }
        // ★ 成功反馈那行要在 lambda 里读这颗棋子，而 lambda 捕获的必须是**有效 final** 变量。
        //   `nearest` 现在是 nearestPawnOrFail 的返回值（局部变量、非 final），照旧取一个
        //   final 副本 —— 语义不变，也不依赖「它以后会不会变成循环里赋值的变量」。
        final PixelUnit target = nearest;
        src.sendSuccess(() -> Component.literal("已装 " + slot + " 号技能（技力清零）→ "
                + com.guardianprotocol.combat.Skills.describe(target)), false);
        return 1;
    }

    /**
     * ★ 「离执行者最近的棋子」+「附近没有棋子」这**一条判据的唯一实现** ——
     * 只读入口 {@link #debugShowSkill}、切槽入口 {@link #debugSetSkill} 与
     * 阻挡诊断入口 {@link #debugShowBlock} 共用。
     *
     * <p>为什么不许三条路径各写一份循环：它们的区别只是「读」还是「写」，
     * 而「认哪颗棋子」必须完全一致 —— 复制第二份的那天，几条路径就会开始漂移
     * （一条按 8 格、另一条忘了改半径；一条排除了移除的、另一条没有），
     * 而现象是「只读看到的棋子和切槽切到的那颗不是同一颗」，排查起来毫无线索。
     * 本项目纪律：**一条判据只留一处实现**。</p>
     *
     * <p>只认同一维度、还活着、没被移除的棋子（与技能心跳 {@code Skills.onServerTick} 的口径一致）。
     * 找不到就**当场把失败文案发给执行者**并返回 {@code null}（文案与切槽路径逐字同一条）。</p>
     *
     * @return 8 格内最近的棋子；附近没有棋子时 {@code null}（失败文案已发）
     */
    @Nullable
    private static PixelUnit nearestPawnOrFail(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Vec3 at = src.getPosition();
        PixelUnit nearest = null;
        double best = SKILL_COMMAND_RANGE * SKILL_COMMAND_RANGE;
        for (PixelUnit unit : PixelUnit.activeUnits()) {
            if (unit.isRemoved() || !unit.isAlive() || unit.level() != level) {
                continue;
            }
            double d = unit.position().distanceToSqr(at);
            if (d <= best) {
                best = d;
                nearest = unit;
            }
        }
        if (nearest == null) {
            src.sendFailure(Component.literal("附近 " + (int) SKILL_COMMAND_RANGE
                    + " 格内没有棋子 —— 技能位是**棋子**的属性，先把棋子摆下来再切"));
        }
        return nearest;
    }

    /** 切技能位命令的搜索半径（格）：只认这么近的棋子，免得在远处误改别人的棋子。 */
    private static final double SKILL_COMMAND_RANGE = 8.0D;

    /** {@code spawn at x y z} 的坐标（三个 double 参数）。 */
    private static Vec3 debugVec(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return new Vec3(DoubleArgumentType.getDouble(ctx, "x"),
                DoubleArgumentType.getDouble(ctx, "y"),
                DoubleArgumentType.getDouble(ctx, "z"));
    }

    /** {@code spawn fire at x y z} 的坐标（三个整数参数）。 */
    private static BlockPos debugPos(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static int runFromCommand(CommandSourceStack src, String scenario) {
        if ("path".equals(scenario)) {
            src.sendSuccess(() -> Component.literal("自验报告：" + reportPath().toAbsolutePath()), false);
            return 1;
        }
        ServerLevel level = src.getLevel();
        BlockPos base = src.getPlayer() != null
                ? src.getPlayer().blockPosition()
                : level.getSharedSpawnPos();
        String text = (scenario == null)
                ? runAll(level, base)
                : runScenario(level, base, scenario);
        appendReport(text);
        src.sendSuccess(() -> Component.literal("自验完成：" + (scenario == null ? "全部" : scenario)
                + " → " + reportPath().toAbsolutePath()), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 入口（两种跑法共用）
    // ------------------------------------------------------------------

    /** 无头开关是否打开。 */
    public static boolean enabledByProperty() {
        return "1".equals(System.getProperty(PROP_ENABLE, "0").trim());
    }

    /**
     * 无头自验：跑完所有场景 → 写报告 → 关服。
     *
     * <p>由 {@code ModEvents} 在 {@code ServerStartedEvent} 里调用（那时世界已加载完成，
     * 可以安全地生成实体）。最后 {@code halt(false)} <b>不保存</b>，
     * 因为测试世界对我们没有价值，而保存会拖慢整个流程。</p>
     *
     * <p><b>只跑一个场景</b>：加 {@code -Dguardianprotocol.selftest.scenario=<场景名>}
     * （Gradle 侧是 {@code -PselftestScenario=<场景名>}）就只跑那一个。
     * 全套场景包含 200 tick 的实机观察，改一处小逻辑却付整套代价太贵 ——
     * 单场景通常十几秒就出报告。</p>
     */
    public static void runAllAndHalt(net.minecraft.server.MinecraftServer server) {
        try {
            ServerLevel level = server.overworld();
            BlockPos base = defaultBase(level);
            GuardianProtocol.LOGGER.info("[{}] 无头自验开始（场地中心 {}）", GuardianProtocol.MODID, base);
            String text = runRequested(level, base);
            appendReport(text);
            // 把结论也打进日志，方便只看 latest.log 的人
            for (String line : text.split("\n")) {
                GuardianProtocol.LOGGER.info("[selftest] {}", line);
            }
            GuardianProtocol.LOGGER.info("[{}] 无头自验完成 → {}",
                    GuardianProtocol.MODID, reportPath().toAbsolutePath());
        } catch (Exception ex) {
            GuardianProtocol.LOGGER.error("[{}] 无头自验异常", GuardianProtocol.MODID, ex);
        } finally {
            // ★ 有实机观察在跑时**先别关服**：live 场景要靠服务端自己的 tick 推进
            //   （200 tick ≈ 10 秒），而这里如果立刻 halt，观察一行都来不及写。
            //   实测踩过：报告里 live 只有「已启动实机观察」那几行，之后直接关服。
            //   真正的关服交给实机观察结束时的 tickLiveObservation()。
            if (LIVE == null) {
                // 不保存：测试世界没有保留价值，保存只会拖时间
                server.halt(false);
            } else {
                GuardianProtocol.LOGGER.info("[{}] live 观察进行中，等它跑完再关服",
                        GuardianProtocol.MODID);
            }
        }
    }

    /**
     * 自验场地中心：优先用 {@link #PROP_BASE} 指定的坐标，否则退回世界出生点。
     *
     * <p>为什么允许指定：真实存档里出生点可能被建筑占满，而自验要在一块干净的地上
     * 按精确坐标摆棋子与敌人。玩家在游戏里按 F3 看到坐标后，用
     * {@code -Dguardianprotocol.selftest.base=x,y,z} 传进来即可。</p>
     */
    public static BlockPos defaultBase(ServerLevel level) {
        String raw = System.getProperty(PROP_BASE, "").trim();
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            if (parts.length == 3) {
                try {
                    return new BlockPos(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()));
                } catch (NumberFormatException ex) {
                    GuardianProtocol.LOGGER.warn("[{}] {} 解析失败（{})，改用出生点",
                            GuardianProtocol.MODID, PROP_BASE, raw);
                }
            }
        }
        return level.getSharedSpawnPos();
    }

    /** 按 {@link #PROP_SCENARIO} 决定跑全套还是单场景。 */
    public static String runRequested(ServerLevel level, BlockPos base) {
        String only = System.getProperty(PROP_SCENARIO, "").trim();
        if (only.isEmpty()) {
            return runAll(level, base);
        }
        GuardianProtocol.LOGGER.info("[{}] 只跑场景：{}", GuardianProtocol.MODID, only);
        return runScenario(level, base, only);
    }

    /** 跑全部场景，返回报告正文。 */
    public static String runAll(ServerLevel level, BlockPos base) {        StringBuilder sb = new StringBuilder();
        sb.append("=== guardianprotocol selftest :: ALL ===\n");
        sb.append("level=").append(level.dimension().location())
                .append("  gameTime=").append(level.getGameTime())
                .append("  间隔倍率=").append(PawnCombatManager.attackIntervalMultiplier()).append('\n');
        for (String s : SCENARIOS) {
            sb.append(runScenario(level, base, s));
        }
        return sb.toString();
    }

    /** 跑一个场景，返回报告正文（不写文件）。 */
    public static String runScenario(ServerLevel level, BlockPos base, String scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- scenario: ").append(scenario).append(" ---\n");
        try {
            switch (scenario) {
                case "interval" -> scenarioInterval(sb);
                case "piece" -> scenarioPiece(sb, level, base);
                case "datapack" -> scenarioDatapack(sb, level, base);
                case "debug" -> scenarioDebug(sb, level, base);
                case "targeting" -> scenarioTargeting(sb, level, base);
                case "multi" -> scenarioMulti(sb, level, base);
                case "block" -> scenarioBlock(sb, level, base);
                case "solid" -> scenarioSolid(sb, level, base);
                case "vertical" -> scenarioVertical(sb, level, base);
                case "skills" -> scenarioSkills(sb, level, base);
                case "attack" -> scenarioAttack(sb, level, base);
                case "aoe" -> scenarioAoe(sb, level, base);
                case "pause" -> scenarioPause(sb, level, base);
                case "traits" -> scenarioTraits(sb, level, base);
                case "heal" -> scenarioHeal(sb, level, base);
                case "spawnpoint" -> scenarioSpawnPoint(sb, level, base);
                case "invasion" -> scenarioInvasion(sb, level, base);
                case "live" -> scenarioLive(sb, level, base);
                case "livedemo" -> scenarioLivedemo(sb, level);
                default -> sb.append("!! 未知场景：").append(scenario).append('\n');
            }
        } catch (Exception ex) {
            // ★ 异常必须计入**失败**，不能只打一行「场景异常」：否则统计里 FAIL=0，
            //   看起来一切正常，实际上这个场景一条断言都没跑（同族教训见 踩坑记录）。
            sb.append("   [FAIL] 场景异常（本场景的断言未执行完）：").append(ex).append('\n');
            GuardianProtocol.LOGGER.error("[{}] 自验场景 {} 异常",
                    GuardianProtocol.MODID, scenario, ex);
        }
        // ★ 收尾：把场上残留的投掷物全部清掉。
        //   场景之间只隔 20~30 格，而「场上有没有弹体」这类断言是按 24 格半径查的 ——
        //   上一个场景射出去、又没人推它的那颗弹会窜进下一个场景，把断言搞红
        //   （第一次跑就是这么误报的：vertical 里速射手开了一枪，没 tick 也没清理）。
        discardProjectilesNear(level, base, 512.0D);
        return sb.toString();
    }

    /** 把某点附近的投掷物全部 discard（自验场景之间的收尾，见 {@link #runScenario}）。 */
    private static void discardProjectilesNear(ServerLevel level, BlockPos base, double radius) {
        for (PawnProjectile shot : level.getEntitiesOfClass(PawnProjectile.class,
                new AABB(base).inflate(radius))) {
            if (!shot.isRemoved()) {
                shot.discard();
            }
        }
    }

    // ------------------------------------------------------------------
    // 场景 0：判据追踪（为什么选中/没选中）
    // ------------------------------------------------------------------

    /**
     * 把「为什么选中/没选中」的每一步中间量打出来。
     *
     * <p>这条是**排查用**的：{@code 选中=0} 有很多互不相干的原因（不在格子里、
     * 垂直判定挡掉、被挡住但没名额、实体根本不在同一个 level…），只看结果猜不出来。
     * 报告里会列出 12 格内每个实体：算不算敌人、过不过垂直判定、落不落在打击格、
     * 有没有被挡住、距棋子多远、脚底差多少。</p>
     */
    private static void scenarioDebug(StringBuilder sb, ServerLevel level, BlockPos base) {
        sb.append("-- debug：判据追踪（场地基准 ").append(fmt(base)).append("）\n");
        int bx = base.getX() + 8;
        int bz = base.getZ() + 60;
        int by = groundYAt(level, bx, bz, base.getY());
        sb.append("   生成点=").append(bx).append(',').append(by).append(',').append(bz).append('\n');

        List<Entity> cleanup = new ArrayList<>();
        BlockPos at = new BlockPos(bx, by, bz);
        PixelUnit pawn = spawnPawn(level, at, UnitBranch.GUARD_CENTURION);
        cleanup.add(pawn);
        try {
            // 棋子实际落在哪：实体 spawn 后可能被碰撞/落地修正，所以**以实际位置为准**
            sb.append("   生成后棋子 y=").append(String.format(Locale.ROOT, "%.3f", pawn.getY()))
                    .append(" 方块格=").append(fmt(pawn.blockPosition())).append('\n');
            for (int i = 0; i < 3; i++) {
                Mob m = spawn(level, EntityType.ZOMBIE, bx, by, bz, "僵尸" + (i + 1));
                // 关键：按棋子**实际**位置摆敌人，而不是按地面扫描的猜测值
                m.setPos(pawn.getX() + 1.3D, pawn.getY(), pawn.getZ() + (i - 1) * 0.35D);
                cleanup.add(m);
                sb.append("   僵尸").append(i + 1).append(" 摆到 y=")
                        .append(String.format(Locale.ROOT, "%.3f", m.getY()))
                        .append(" 位置=").append(shortVec(m.position())).append('\n');
            }
            PawnCombatManager.onServerTick(level.getServer());
            sb.append(PawnCombatManager.debugTrace(pawn));
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    // ------------------------------------------------------------------
    // 场景 1：出手间隔（只读数据，不生成实体）
    // ------------------------------------------------------------------

    private static void scenarioInterval(StringBuilder sb) {
        sb.append("-- 出手间隔（基础秒 × 20 × 倍率 ")
                .append(PawnCombatManager.attackIntervalMultiplier()).append("）\n");
        String[] show = {"VANGUARD_CHARGER", "SNIPER_MARKSMAN", "GUARD_SWORDMASTER",
                "DEFENDER_FORTRESS", "CASTER_CORE", "MEDIC_MEDIC", "SPECIALIST_AMBUSHER"};
        for (String want : show) {
            for (UnitBranch b : UnitBranch.values()) {
                if (!b.name().equals(want)) {
                    continue;
                }
                int ticks = BlockGeometry.intervalTicks(b.attackIntervalSeconds(),
                        PawnCombatManager.attackIntervalMultiplier());
                sb.append(String.format(Locale.ROOT,
                        "   %-10s %-12s 基础 %.2fs → %3d tick（%.2fs）%n",
                        b.name(), b.branchName(), b.attackIntervalSeconds(), ticks, ticks / 20.0D));
            }
        }
        int zero = 0;
        for (UnitBranch b : UnitBranch.values()) {
            if (BlockGeometry.intervalTicks(b.attackIntervalSeconds()) == 0) {
                zero++;
            }
        }
        sb.append("   分支总数 ").append(UnitBranch.values().length)
                .append("，间隔为 0（不攻击）的 ").append(zero).append(" 个\n");
    }

    // ------------------------------------------------------------------
    // 场景 1.5：阶位 / 精炼（数据 + 存档往返）
    // ------------------------------------------------------------------

    /**
     * 校验「阶位 / 精炼」这两个自走棋字段：默认值、越界夹取、以及**存档往返**。
     *
     * <p><b>为什么必须测存档往返</b>：这两个字段是给后续的商店/羁绊项目用的，
     * 那边的用法一定是「放下棋子 → 存档 → 重启 → 读回来」。只测 set/get 只能证明
     * 内存里对，证不了 NBT 键写对 —— 键写错的表现是「重启后棋子全部退回 1 阶」，
     * 而那种 bug 要等商店做完才暴露，届时很难定位。</p>
     *
     * <p>这里走的是与存档完全相同的两条方法：{@code saveWithoutId}（内部调
     * {@code addAdditionalSaveData}）与 {@code load}（内部调 {@code readAdditionalSaveData}）。</p>
     */
    private static void scenarioPiece(StringBuilder sb, ServerLevel level, BlockPos base) {
        sb.append("-- 阶位 / 精炼（默认值 + 夹取 + 存档往返）\n");
        int bx = base.getX() + 8;
        int bz = base.getZ() + 80;
        int by = groundYAt(level, bx, bz, base.getY());

        List<Entity> cleanup = new ArrayList<>();
        PixelUnit pawn = spawnPawn(level, new BlockPos(bx, by, bz), UnitBranch.GUARD_CENTURION);
        cleanup.add(pawn);
        try {
            check(sb, "默认阶位 = " + PixelUnit.DEFAULT_TIER, pawn.getTier() == PixelUnit.DEFAULT_TIER);
            check(sb, "默认未精炼", !pawn.isRefined());

            pawn.setTier(4);
            pawn.setRefined(true);
            check(sb, "设 4 阶读回 4 阶", pawn.getTier() == 4);
            check(sb, "设精炼读回 true", pawn.isRefined());

            pawn.setTier(99);
            check(sb, "越界 99 夹到 " + PixelUnit.MAX_TIER, pawn.getTier() == PixelUnit.MAX_TIER);
            pawn.setTier(-3);
            check(sb, "越界 -3 夹到 " + PixelUnit.MIN_TIER, pawn.getTier() == PixelUnit.MIN_TIER);
            pawn.setTier(4);

            // 存档往返
            CompoundTag tag = pawn.saveWithoutId(new CompoundTag());
            sb.append("   NBT：")
                    .append(tag.contains(PixelUnit.TAG_TIER) ? PixelUnit.TAG_TIER : "缺 " + PixelUnit.TAG_TIER)
                    .append('=').append(tag.getInt(PixelUnit.TAG_TIER))
                    .append(' ')
                    .append(tag.contains(PixelUnit.TAG_REFINED) ? PixelUnit.TAG_REFINED
                            : "缺 " + PixelUnit.TAG_REFINED)
                    .append('=').append(tag.getBoolean(PixelUnit.TAG_REFINED)).append('\n');

            PixelUnit reloaded = spawnPawn(level, new BlockPos(bx + 2, by, bz), UnitBranch.GUARD_CENTURION);
            cleanup.add(reloaded);
            reloaded.load(tag);
            check(sb, "往返后阶位仍是 4", reloaded.getTier() == 4);
            check(sb, "往返后精炼仍是 true", reloaded.isRefined());

            // ---- ★ 2026-10-07：朝向界面那三行要读的数据（界面本身是客户端类，无头台验不了，
            //      但它**喂给界面的数据**全在这里；数据缺了界面只会显示「—」，谁都不报错）----
            sb.append("-- 朝向界面要显示的数据（属性 / 分支职业 / 分支特性）\n");
            check(sb, "★ 该棋子的攻击力 > 0（界面第 1 行第 1 项）",
                    pawn.getBranch().attackDamage() > 0.0D);
            check(sb, "★ 该棋子的护甲 >= 0（界面第 1 行第 3 项）", pawn.getBranch().armor() >= 0.0D);
            check(sb, "★ 该棋子的最大生命值 > 0（界面第 1 行第 2 项给「当前/最大」）",
                    pawn.getMaxHealth() > 0.0F);
            check(sb, "★ 分支职业两半都有：职业名非空 + 分支名非空（界面第 2 行）",
                    !pawn.getBranch().unitClass().displayName().isEmpty()
                            && !pawn.getBranch().branchName().isEmpty());

            // 分支特性：72 个内置分支逐个扫，一个都不能空（空 ⇒ 界面那一行只剩「—」）。
            // ★ 走 BuiltinBranch 而不是直接读枚举：`traitDisplayText()` 是 **BranchDef** 上的
            //   默认方法（枚举没实现那个接口），而界面拿到的正是 BranchDef —— 这样验的
            //   就是界面真正会走的那条路。
            int noTrait = 0;
            int sanitized = 0;
            StringBuilder sample = new StringBuilder();
            for (UnitBranch b : UnitBranch.values()) {
                com.guardianprotocol.data.BuiltinBranch def =
                        new com.guardianprotocol.data.BuiltinBranch(b);
                if (def.traitText().isEmpty()) {
                    noTrait++;
                }
                if (def.traitText().contains("|")) {
                    sanitized++;      // 原文里带 `|` 的，显示时必须已经被换成 `；`
                }
                if (def.traitDisplayText().contains("|")) {
                    check(sb, "★ 分支特性显示文本不该残留 `|`（" + b.branchName() + "）", false);
                }
            }
            com.guardianprotocol.data.BuiltinBranch chainDef =
                    new com.guardianprotocol.data.BuiltinBranch(UnitBranch.CASTER_CHAIN);
            com.guardianprotocol.data.BuiltinBranch bardDef =
                    new com.guardianprotocol.data.BuiltinBranch(UnitBranch.SUPPORTER_BARD);
            check(sb, "★ 72 个内置分支的分支特性原文都非空（空的 " + noTrait + " 个）", noTrait == 0);
            check(sb, "★ 至少有一条原文带 `|`（否则下面那条清洗断言等于没验）", sanitized > 0);
            // 取那条真带 `|` 的（链术师）当读数：原文有 `|`、显示文本没有、且有 `；`
            check(sb, "★ 清洗真的发生了：链术师原文含 `|` 而显示文本变成「；」",
                    chainDef.traitText().contains("|")
                            && !chainDef.traitDisplayText().contains("|")
                            && chainDef.traitDisplayText().contains("；"));
            sample.append(bardDef.traitDisplayText());
            check(sb, "★ BuiltinBranch 转发没丢（漏转发 ⇒ 界面那一行是空的）",
                    !bardDef.traitDisplayText().isEmpty());
            sb.append("   吟游者那行会显示成：").append(sample).append('\n');
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    // ------------------------------------------------------------------
    // 场景 1.6：数据包分支（新增 / 覆盖）
    // ------------------------------------------------------------------

    /**
     * 校验数据包分支：**新增**一个分支、**覆盖**一个内置分支，并确认战斗侧真的读到了。
     *
     * <p>数据包文件在 {@code 自验数据包/guardian_selftest/data/guardian_selftest/
     * guardian_branches/} 下（入库的那份），由 {@code run-自验运行目录.bat} 同步进存档。
     * 这条场景只做只读核对 + 生成一个棋子验证属性，不改存档里的任何方块。</p>
     *
     * <p>为什么必须验到「战斗侧」：注册表里查得到、但 {@code PixelUnit} 仍按枚举取数值，
     * 是这类改动最典型的半成品状态（表现是「数据包改了数值却不生效」）。
     * 所以这里不只查注册表，还确实造一个棋子、读它的最大生命与打击格。</p>
     */
    private static void scenarioDatapack(StringBuilder sb, ServerLevel level, BlockPos base) {
        sb.append("-- 数据包分支（新增 + 覆盖）\n");
        sb.append("   ").append(BranchRegistry.describeDatapack()).append('\n');

        // ---- 1) 新增分支 ----
        ResourceLocation probeId = new ResourceLocation("guardian_selftest", "probe_sniper");
        BranchDef probe = BranchRegistry.byKey(probeId.toString());
        check(sb, "新增分支 " + probeId + " 已注册", probe != null);
        if (probe == null) {
            sb.append("   !! 数据包没被读到：确认 自验数据包 已同步进存档的 datapacks/，"
                    + "且数据包被启用\n");
            return;
        }
        check(sb, "新增分支不是内置（isBuiltin=false）", !probe.isBuiltin());
        check(sb, "职业取自 class=sniper", probe.unitClass() == UnitClass.SNIPER);
        check(sb, "分支名取自 JSON", "探针狙击".equals(probe.branchName()));
        check(sb, "血量 = 33", Math.abs(probe.maxHealth() - 33.0D) < 1.0E-6D);
        check(sb, "攻击 = 7", Math.abs(probe.attackDamage() - 7.0D) < 1.0E-6D);
        check(sb, "护甲 = 2", Math.abs(probe.armor() - 2.0D) < 1.0E-6D);
        check(sb, "索敌优先级 = AIR_FIRST",
                probe.targetPriority() == UnitBranch.TargetPriority.AIR_FIRST);
        check(sb, "目标数 = MULTI_ALL", probe.targetCount() == UnitBranch.TargetCount.MULTI_ALL);
        check(sb, "打击格 = 3 个（来自 range 数组）", probe.attackCells().size() == 3);
        check(sb, "对空口径 = AIR_AND_GROUND（vertical=both）",
                probe.verticalTargeting() == UnitBranch.VerticalTargeting.AIR_AND_GROUND);
        // 攻击方式：JSON 写了 "attack_method": "projectile"。
        // ★ 这条字段**必须显式写**才有效果：这个探针的 vertical 是 both（不是远程位），
        //   不写的话兜底口径会给出 MELEE —— 也就是说这条断言证明的是「字段真的被读了」。
        check(sb, "攻击方式 = PROJECTILE（JSON attack_method 生效）",
                probe.attackMethod() == UnitBranch.AttackMethod.PROJECTILE);

        // ---- 2) 覆盖内置分支 ----
        ResourceLocation overriddenId = BuiltinBranch.idOf(UnitBranch.MEDIC_WANDERING);
        testOverride(sb, overriddenId);

        // ---- 3) 战斗侧：真造一个棋子，看属性/范围/间隔 ----
        int bx = base.getX() + 8;
        int bz = base.getZ() + 90;
        int by = groundYAt(level, bx, bz, base.getY());
        List<Entity> cleanup = new ArrayList<>();
        PixelUnit pawn = spawnPawn(level, new BlockPos(bx, by, bz), probe);
        cleanup.add(pawn);
        try {
            check(sb, "棋子的分支 id = 数据包 id",
                    probeId.equals(pawn.getBranch().id()));
            check(sb, "棋子最大生命 = 33（属性已按数据包写）",
                    Math.abs(pawn.getMaxHealth() - 33.0F) < 1.0E-3F);
            check(sb, "棋子攻击 = 7",
                    Math.abs(pawn.getAttributeValue(Attributes.ATTACK_DAMAGE) - 7.0D) < 1.0E-6D);
            check(sb, "棋子护甲 = 2",
                    Math.abs(pawn.getAttributeValue(Attributes.ARMOR) - 2.0D) < 1.0E-6D);
            check(sb, "棋子打击格 = 3 个", pawn.getAttackCells().size() == 3);
            // 间隔：1.0 秒 × 20 tick × 全局倍率（当前 2.0）= 40 tick。
            // ★ 这里**不写死倍率**：期望值从当前生效的倍率算出来，改倍率时这条断言不会变成假报警。
            int expected = BlockGeometry.intervalTicks(probe.attackIntervalSeconds(),
                    PawnCombatManager.attackIntervalMultiplier());
            check(sb, "出手间隔 = " + expected + " tick", PawnCombatManager.debugIntervalTicks(pawn) == expected);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /** 覆盖内置分支的核对：数值变了，但身份（id / key）必须没变 —— 老存档才认得。 */
    private static void testOverride(StringBuilder sb, ResourceLocation overriddenId) {
        BranchDef overridden = BranchRegistry.byKey(overriddenId.toString());
        check(sb, "被覆盖的分支仍查得到 " + overriddenId, overridden != null);
        if (overridden == null) {
            return;
        }
        check(sb, "覆盖版不是内置对象（isBuiltin=false）", !overridden.isBuiltin());
        check(sb, "覆盖版血量 = 77（JSON 生效）", Math.abs(overridden.maxHealth() - 77.0D) < 1.0E-6D);
        check(sb, "覆盖版间隔 = 0.5s", Math.abs(overridden.attackIntervalSeconds() - 0.5D) < 1.0E-6D);
        check(sb, "覆盖后职业仍继承为医疗", overridden.unitClass() == UnitClass.MEDIC);
        check(sb, "★ 老存档兼容：按枚举名 SNIPER 式短名仍能查到同一个分支",
                BranchRegistry.byKey("MEDIC_WANDERING") == overridden);
        check(sb, "覆盖版没有改掉 id", overriddenId.equals(overridden.id()));
        // 攻击方式：这个覆盖 JSON **没有**写 attack_method，所以它必须原样继承（不丢字段）。
        // ★ 注意这条是**回归哨兵**而不是判别实验：行医本身是远程位，继承与「按 vertical 兜底」
        //   两条口径在这一例里恰好给出同一个答案（都是 PROJECTILE）。要真正区分两条口径，
        //   得拿一个近战位分支去覆盖 —— 那需要另一个夹具，暂不做（口径本身已在 JsonBranch 注释里写明）。
        check(sb, "覆盖版攻击方式没有丢 = PROJECTILE", 
                overridden.attackMethod() == UnitBranch.AttackMethod.PROJECTILE);
    }

    /** 报告一行 PASS/FAIL（自验台统一格式，便于 grep）。 */
    private static void check(StringBuilder sb, String what, boolean ok) {
        sb.append("   [").append(ok ? "PASS" : "FAIL").append("] ").append(what).append('\n');
    }

    // ------------------------------------------------------------------
    // 场景 2：优先级（打谁）
    // ------------------------------------------------------------------

    /**
     * 同一批目标摆在同一个正前格里，只有**优先级**不同 —— 选谁只可能由优先级决定。
     */
    private static void scenarioTargeting(StringBuilder sb, ServerLevel level, BlockPos origin) {
        int fx = origin.getX() + 8;
        int fz = origin.getZ() + 8;
        int fy = groundYAt(level, fx, fz, origin.getY());

        checkPriority(sb, level, new BlockPos(fx, fy, fz), UnitBranch.SNIPER_DEADEYE,
                "神射手·优先防御最低", () -> {
                    Mob fat = spawn(level, EntityType.ZOMBIE, fx, fy, fz, "高甲僵尸");
                    Mob thin = spawn(level, EntityType.SKELETON, fx, fy, fz, "低甲骷髅");
                    setArmor(fat, 20.0D);
                    setArmor(thin, 0.0D);
                    place(fat, fx, fy, fz, 1.3D, 0.15D);
                    place(thin, fx, fy, fz, 1.3D, -0.15D);
                    return List.of(fat, thin);
                });

        checkPriority(sb, level, new BlockPos(fx, fy, fz + 12), UnitBranch.SNIPER_BESIEGER,
                "攻城手·优先最重", () -> {
                    Mob cow = spawn(level, EntityType.COW, fx, fy, fz + 12, "牛（大体型）");
                    Mob zom = spawn(level, EntityType.ZOMBIE, fx, fy, fz + 12, "僵尸（常规）");
                    place(cow, fx, fy, fz + 12, 1.3D, 0.15D);
                    place(zom, fx, fy, fz + 12, 1.3D, -0.15D);
                    return List.of(cow, zom);
                });

        checkPriority(sb, level, new BlockPos(fx, fy, fz + 24), UnitBranch.SNIPER_MARKSMAN,
                "速射手·空中优先", () -> {
                    Mob zom = spawn(level, EntityType.ZOMBIE, fx, fy, fz + 24, "僵尸（地面）");
                    Mob ph = spawn(level, EntityType.PHANTOM, fx, fy, fz + 24, "幻翼（空中）");
                    place(zom, fx, fy, fz + 24, 1.3D, -0.15D);
                    place(ph, fx, fy + 1, fz + 24, 1.3D, 0.15D);
                    return List.of(zom, ph);
                });

        checkPriority(sb, level, new BlockPos(fx, fy, fz + 36), UnitBranch.SNIPER_SKYBREAKER,
                "裂空炮手·只打空中（场上有地面 + 空中）", () -> {
                    Mob zom = spawn(level, EntityType.ZOMBIE, fx, fy, fz + 36, "僵尸（地面）");
                    Mob ph = spawn(level, EntityType.PHANTOM, fx, fy, fz + 36, "幻翼（空中）");
                    place(zom, fx, fy, fz + 36, 1.3D, -0.15D);
                    place(ph, fx, fy + 1, fz + 36, 1.3D, 0.15D);
                    return List.of(zom, ph);
                });

        checkPriority(sb, level, new BlockPos(fx, fy, fz + 46), UnitBranch.SNIPER_SKYBREAKER,
                "裂空炮手·只打空中（场上只有地面）", () -> {
                    Mob zom = spawn(level, EntityType.ZOMBIE, fx, fy, fz + 46, "僵尸（地面）");
                    place(zom, fx, fy, fz + 46, 1.3D, 0.0D);
                    return List.of(zom);
                });

        blockedFirstGroup(sb, level, new BlockPos(fx, fy, fz));
    }

    /**
     * <b>N3 组</b>（2026-10 第五轮）：{@code BLOCKED_FIRST} 的<b>排序行为</b> ——
     * N2 落地时只验到<b>数据层</b>（三个分支的优先级写进了生成物、全枚举扫下来正好 3 个），
     * 「真有一只被挡的怪时它到底赢不赢」一直没验：那需要一只<b>真进「被挡名单」</b>的怪
     * （夹具与 {@code /guardianprotocol block} 的 E 组同一套：临时覆盖阻挡白名单 +
     * 把它摆在 0.70 格，让碰撞箱与棋子所在格重叠 0.1 格 ⇒ 进得了 FOOT 候选盒）。
     *
     * <h3>为什么是两个子实验，而不是一条「被挡的赢了」</h3>
     * <p>「被挡的那只赢了」单独看<b>证明不了任何事</b>：它可能只是碰巧更近。所以</p>
     * <ul>
     *     <li>另一只靶子（骷髅）<b>不在白名单里</b> ⇒ 永远进不了被挡名单，而且被刻意摆得
     *         <b>更近</b>（0.778 格 vs 僵尸的 0.990 格 —— 两只都在棋子的攻击格子里）；</li>
     *     <li>② 号子实验把白名单清空（设计口径：<b>空 = 谁都不挡</b>）⇒ 判据恒 false ⇒
     *         {@code BLOCKED_FIRST} 退化成「最近优先」⇒ 必须选中<b>骷髅</b>。</li>
     * </ul>
     * <p>两个子实验之间，场地几何 / 分支 / 朝向 / 两只怪的相对坐标<b>完全一样</b>，
     * 唯一变量是「僵尸有没有进被挡名单」 ⇒ 结论只可能来自 {@code BLOCKED_FIRST}。
     * 两条还互为对方的候选集证据：② 选中骷髅 ⇒ 骷髅确实是候选（不是「只有一只可打」）。</p>
     */
    private static void blockedFirstGroup(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- N3) BLOCKED_FIRST 排序行为：打「我正挡住的」，哪怕它比另一只更远\n");
        List<Entity> cleanup = new ArrayList<>();
        // 白名单在本组内被反复覆盖 ⇒ 记下旧值，退出时还原（同 block 场景的做法）
        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of());
        try {
            blockedFirstCase(sb, level, origin.getX() + 30, origin.getZ() + 8, origin.getY(),
                    List.of("minecraft:zombie"), true, cleanup);
            blockedFirstCase(sb, level, origin.getX() + 30, origin.getZ() + 24, origin.getY(),
                    List.of(), false, cleanup);
        } finally {
            BlockCandidates.setWhitelistOverride(oldWhitelist);
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * N3 的一个子实验：**同一套几何**摆一次，只有阻挡白名单不同。
     *
     * <p>先摆夹具、再验夹具、最后才读被测对象（踩坑记录的规矩：夹具不成立时下面那条断言
     * 会变成一句空话）。夹具包含三件事：①僵尸是否进了被挡名单、②两只靶子<b>都在</b>
     * 棋子的攻击格子里（否则「选中谁」是在候选集只有一只时比的）、③僵尸确实<b>更远</b>
     * （否则「被挡的赢了」与「最近的赢了」给出同一个答案，分不出差别）。</p>
     *
     * @param whitelist      本子实验生效的阻挡白名单（空 = 谁都不挡）
     * @param expectHeldWin  true = 期望「被挡的僵尸」赢（白名单放行时）；
     *                       false = 期望「更近的骷髅」赢（谁都不挡 ⇒ 退化成最近优先）
     */
    private static void blockedFirstCase(StringBuilder sb, ServerLevel level, int x, int z, int fromY,
                                         List<String> whitelist, boolean expectHeldWin,
                                         List<Entity> cleanup) {
        BlockCandidates.setWhitelistOverride(whitelist);
        // flatSpotNear：3x3 同高 ⇒ 斜前方那格也是平的，怪物不会被地形挤到别的高度上去
        BlockPos spot = flatSpotNear(level, x, z, fromY);
        PixelUnit pawn = spawnPawn(level, spot, UnitBranch.GUARD_LORD);
        cleanup.add(pawn);
        Mob tank = spawn(level, EntityType.ZOMBIE, spot.getX(), spot.getY(), spot.getZ(),
                "僵尸（白名单内）");
        Mob nearer = spawn(level, EntityType.SKELETON, spot.getX(), spot.getY(), spot.getZ(),
                "骷髅（白名单外）");
        cleanup.add(tank);
        cleanup.add(nearer);
        makeTanky(tank, 500.0D);
        makeTanky(nearer, 500.0D);
        setArmor(tank, 0.0D);
        setArmor(nearer, 0.0D);
        // 僵尸：斜前方 +0.70/+0.70 —— 碰撞箱与棋子所在格重叠 0.1 格（E 组同一套几何）
        tank.setPos(pawn.getX() + 0.70D, pawn.getY(), pawn.getZ() + 0.70D);
        // 骷髅：斜后方另一侧，距 0.778 格（**更近**），且与僵尸的碰撞箱不重叠
        nearer.setPos(pawn.getX() + 0.55D, pawn.getY(), pawn.getZ() - 0.55D);
        // 只推 manager 心跳（不 tick 生物）：两只怪停在原地，上面那些距离是确定的
        PawnCombatManager.onServerTick(level.getServer());

        List<PathfinderMob> heldNow = PawnCombatManager.debugBlocked(pawn);
        boolean held = heldNow.contains(tank);
        double dTank = Math.sqrt(tank.distanceToSqr(pawn));
        double dNearer = Math.sqrt(nearer.distanceToSqr(pawn));
        // 「两只都在攻击格子里」：直接与棋子**旋转后**的打击格对表（不是猜）
        Set<String> cellset = new HashSet<>();
        for (var c : pawn.worldCells()) {
            cellset.add(c.x() + "," + c.z());
        }
        BlockPos po = pawn.blockPosition();
        boolean tankInRange = cellset.contains((tank.blockPosition().getX() - po.getX())
                + "," + (tank.blockPosition().getZ() - po.getZ()));
        boolean nearerInRange = cellset.contains((nearer.blockPosition().getX() - po.getX())
                + "," + (nearer.blockPosition().getZ() - po.getZ()));
        String tag = whitelist.isEmpty() ? "①白名单空" : "②白名单含僵尸";
        sb.append("   ").append(tag).append("｜棋子格=").append(fmt(po))
                .append(" 朝向=").append(pawn.getFacing().displayName())
                .append(" 打击格=").append(cells(pawn)).append('\n');
        sb.append("     白名单=").append(whitelist.isEmpty() ? "（空 = 谁都不挡）" : whitelist)
                .append("｜被挡名单 ").append(heldNow.size()).append(" 个")
                .append("｜僵尸距 ").append(r2(dTank)).append("（被挡=").append(held).append(')')
                .append("｜骷髅距 ").append(r2(dNearer)).append('\n');

        boolean fixture = held == !whitelist.isEmpty()
                && tankInRange && nearerInRange && dTank > dNearer;
        check(sb, "N3 夹具·" + tag + "：僵尸被挡 = " + held + "（期望 " + !whitelist.isEmpty() + "）"
                        + "，两只都在打击格里（僵尸 " + tankInRange + " / 骷髅 " + nearerInRange + "）"
                        + "，且僵尸更远（" + r2(dTank) + " > " + r2(dNearer) + "）",
                fixture);
        if (!fixture) {
            sb.append("   ⚠ 跳过本子实验的选中断言：夹具没成立，断言会变成一句空话\n");
            return;
        }

        List<LivingEntity> picked = PawnCombatManager.debugLastTargets(pawn);
        LivingEntity want = expectHeldWin ? tank : nearer;
        sb.append("     判据：").append(PawnCombatManager.debugSteps()).append('\n');
        sb.append("     → 选中：").append(picked.isEmpty() ? "**（无）**" : name(picked.get(0)))
                .append("（期望 ").append(name(want)).append("）\n");
        check(sb, "N3 " + (expectHeldWin
                        ? "② 白名单放行 ⇒ 被挡的僵尸赢（尽管它比骷髅远 " + r2(dTank - dNearer) + " 格）"
                        : "① 谁都不挡 ⇒ 退化成最近优先 ⇒ 更近的骷髅赢"),
                picked.size() == 1 && picked.get(0) == want);
    }

    private static void checkPriority(StringBuilder sb, ServerLevel level, BlockPos at,
                                      UnitBranch branch, String title, Supplier<List<Mob>> setup) {
        sb.append("-- ").append(title).append("（").append(branch.name())
                .append(" / 范围 ").append(branch.attackRangeKey())
                .append(" / 优先级 ").append(branch.targetPriority()).append("）\n");
        List<Entity> cleanup = new ArrayList<>();
        PixelUnit pawn = spawnPawnOnGround(level, at.getX(), at.getZ(), at.getY(), branch);
        cleanup.add(pawn);
        try {
            List<Mob> targets = setup.get();
            cleanup.addAll(targets);
            // 敌人按棋子**实际**落点重摆一次（setup 里只给了大致相对位置）
            int idx = 0;
            for (Mob m : targets) {
                m.setPos(pawn.getX() + 1.3D, pawn.getY(), pawn.getZ() + (idx == 0 ? 0.15D : -0.15D));
                idx++;
            }
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> picked = PawnCombatManager.debugLastTargets(pawn);
            // 说明：这里的「选中」数故意不在这里报 —— 早期版本在 append 之前取
            // picked.size()，恒为 0（把「没选中」误报成结论）。真实判据看 [判据] 那一行。

            sb.append("   棋子格=").append(fmt(pawn.blockPosition()))
                    .append(" 朝向=").append(pawn.getFacing().displayName())
                    .append(" 打击格=").append(cells(pawn)).append('\n');
            for (Mob t : targets) {
                sb.append("   候选 ").append(name(t)).append(" 位置=").append(shortVec(t.position()))
                        .append(" 护甲=").append(r1(armor(t)))
                        .append(" 重量=").append(r1(Targeting.weight(t)))
                        .append(" 空中=").append(Targeting.isFlying(t)).append('\n');
            }
            if (picked.isEmpty()) {
                sb.append("   → 选中：**（无）**\n");
                sb.append("   [判据] ").append(PawnCombatManager.debugSteps()).append('\n');
                for (Mob t : targets) {
                    sb.append("   [体检] ").append(name(t))
                            .append(" 算敌人=").append(Targeting.isEnemy(t))
                            .append(" 空中=").append(Targeting.isFlying(t))
                            .append(" 距=").append(r2(Math.sqrt(t.distanceToSqr(pawn))))
                            .append(" y差=").append(r2(t.getY() - pawn.getY()))
                            .append('\n');
                }
            } else {
                for (LivingEntity t : picked) {
                    sb.append("   → 选中：").append(name(t))
                            .append("（护甲=").append(r1(armor(t)))
                            .append(" 重量=").append(r1(Targeting.weight(t)))
                            .append(" 空中=").append(Targeting.isFlying(t)).append("）\n");
                }
            }
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    // ------------------------------------------------------------------
    // 场景 3：一次打几个
    // ------------------------------------------------------------------

    private static void scenarioMulti(StringBuilder sb, ServerLevel level, BlockPos origin) {
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 60;
        int by = groundYAt(level, bx, bz, origin.getY());
        // ★ 阻挡候选默认只找**棋子脚下那一格**，而近战「墙」分支（强攻手…）只打被挡住的：
        //   所以本场景①临时开阻挡白名单、②把靶子摆进脚下那一格，否则 MULTI_BLOCKED 会是 0 个。
        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
        try {
        for (UnitBranch b : new UnitBranch[]{UnitBranch.SNIPER_MARKSMAN, UnitBranch.SNIPER_SPREADSHOOTER,
                UnitBranch.GUARD_CENTURION, UnitBranch.SPECIALIST_AMBUSHER}) {
            sb.append("-- 目标数：").append(b.branchName()).append("（").append(b.name())
                    .append(" / 范围 ").append(b.attackRangeKey())
                    .append(" / 目标数 ").append(b.targetCount())
                    .append(" / 阻挡数 ").append(b.blockCount()).append("）\n");
            List<Entity> cleanup = new ArrayList<>();
            BlockPos at = new BlockPos(bx, by, bz);
            PixelUnit pawn = spawnPawnOnGround(level, at.getX(), at.getZ(), at.getY(), b);
            cleanup.add(pawn);
            try {
                List<Mob> mobs = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    Mob m = spawnWithAi(level, EntityType.ZOMBIE, bx, by, bz, "僵尸" + (i + 1));
                    makeTanky(m, 200.0D);
                    // ★ 摆进**棋子脚下那一格**：阻挡候选默认只找脚下（见 6.3.2），
                    //   而所有分支的攻击范围都含自身格（AttackRange 把 'O' 也算进去），
                    //   所以这个位置对「按范围索敌」的分支同样有效 —— 一个位置满足两种口径。
                    m.setPos(pawn.getX() + 0.3D, pawn.getY(), pawn.getZ() + (i - 1) * 0.25D);
                    mobs.add(m);
                    cleanup.add(m);
                }
                double[] before = new double[mobs.size()];
                for (int i = 0; i < mobs.size(); i++) {
                    before[i] = mobs.get(i).getHealth();
                }
                // 临时间隔倍率拉到 1.0：30 tick 内至少出一次手，否则要等上百 tick
                double old = PawnCombatManager.setAttackIntervalMultiplier(1.0D);
                try {
                    for (int t = 0; t < 30; t++) {
                        PawnCombatManager.onServerTick(level.getServer());
                        // ★ 投掷物分支（速射手/散射手/伏击客）的伤害发生在弹体命中那一刻，
                        //   而弹体飞行靠的是实体自己的 tick —— 手动心跳里必须一起推，
                        //   否则这三个分支会显示成「打了 0 个」（详见 tickProjectiles 的注释）。
                        tickProjectiles(level, origin);
                    }
                } finally {
                    PawnCombatManager.setAttackIntervalMultiplier(old);
                }
                int hit = 0;
                List<LivingEntity> picked = PawnCombatManager.debugLastTargets(pawn);
                sb.append("   打击格=").append(cells(pawn))
                        .append(" 挡住=").append(PawnCombatManager.debugBlocked(pawn).size())
                        .append(" 本 tick 选中 ").append(picked.size()).append(" 个")
                        .append(" 出手间隔=").append(PawnCombatManager.debugIntervalTicks(pawn))
                        .append(" tick\n");
                for (int i = 0; i < mobs.size(); i++) {
                    double after = mobs.get(i).getHealth();
                    boolean damaged = after < before[i] - 1e-6D;
                    if (damaged) {
                        hit++;
                    }
                    sb.append("   ").append(name(mobs.get(i))).append(" 血 ").append(r1(before[i]))
                            .append(" → ").append(r1(after))
                            .append(damaged ? "  受到伤害 ✓" : "  没受伤").append('\n');
                }
                sb.append("   → 实际打中 ").append(hit).append(" 个（期望：")
                        .append(expectedHits(b)).append("）\n");
            } finally {
                discardAll(cleanup);
                sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
            }
        }
        } finally {
            BlockCandidates.setWhitelistOverride(oldWhitelist);
        }
    }

    private static String expectedHits(UnitBranch b) {
        return switch (b.targetCount()) {
            case SINGLE -> "1 个（表里没说是群攻，默认只打一个）";
            case MULTI_BLOCKED -> "挡住的全部（上限 " + b.blockCount() + "）";
            case MULTI_ALL -> "范围内全部（3 个）";
        };
    }

    // ------------------------------------------------------------------
    // 场景 4：阻挡（容量 / 导航 / 击退恢复 / 放行 / 玩家豁免）
    // ------------------------------------------------------------------

    /**
     * 阻挡的自验（<b>平坦地形</b>）—— 给定的 5 条断言逐条落地。
     *
     * <ol>
     *     <li><b>没有瞬移</b>：逐 tick 位移不超过生物一步的量级（上一版每 tick
     *         {@code setPos} 到锚点，一步能跳 2 格）；</li>
     *     <li><b>被击退后自己走回锚点</b>：先把它推开，再看着它自己走回来；</li>
     *     <li><b>多个被挡的横向散开</b>：两两不重合；</li>
     *     <li><b>前 N 个被挡、第 N+1 个放行</b>：容量=1 时只挡最近的，
     *         被放行者的<b>导航与攻击目标都已被清掉</b>（交还原版 AI）；</li>
     *     <li><b>玩家不被拦</b>：候选判据对 Player 直接拒绝。</li>
     * </ol>
     *
     * <h3>★ 为什么这个场景要手动 tick 生物</h3>
     * <p>新的阻挡是「只下发寻路目标、让敌人自己走」——<b>敌人的移动发生在它自己的 tick 里</b>。
     * 测试台在一条心跳里连推 30 步，如果只推 manager，敌人一步都不会走，
     * 于是「没瞬移」会因为「根本没动」而假通过。所以这里的循环是
     * <b>先 {@code mob.tick()}（生物 AI + 寻路 + 移动），再推 manager</b>，
     * 顺序与真实 tick 一致（manager 挂在 ServerTickEvent 的 END 阶段）。</p>
     *
     * <h3>白名单</h3>
     * <p>阻挡候选只认配置白名单（默认空 = 谁都不挡），所以本场景临时把白名单覆盖成
     * 「僵尸」，跑完还原 —— 与攻击间隔倍率那套「临时改、跑完还原」的做法一致。</p>
     */
    private static void scenarioBlock(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- 阻挡：容量 / 导航 / 击退恢复 / 放行 / 玩家豁免 / 拉怪（G 组）\n");
        sb.append("   锚点策略：").append(PawnCombatManager.anchorStrategy().name()).append('\n');

        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
        sb.append("   候选判据（本场景临时覆盖）：").append(BlockCandidates.describe()).append('\n');
        try {
            blockCapacityAndScatter(sb, level, origin);
        } finally {
            BlockCandidates.setWhitelistOverride(oldWhitelist);
        }
        blockPlayerExempt(sb, level, origin);
        blockDefaultWhitelist(sb, level, origin);
        blockDiagnosisGroup(sb, level, origin);
        blockLureGroup(sb, level, origin);
        blockGoalYieldGroup(sb, level, origin);
    }

    /**
     * <b>E 组</b>（★ 2026-10 新增）：只读诊断命令 {@code /guardianprotocol block} 的三件事 ——
     * ① 命令树里注册了、② 它**一个字段都不改**、③ 它打出来的「攻击距离 / 在射程内 / 被挡名单」
     * 与手算的期望值一致。
     *
     * <h3>为什么这三条都要断言</h3>
     * <p>只读入口最危险的失败模式是「悄悄变成了写入口」（同 {@code skills} 的 D 组），而诊断命令
     * 还多一层风险：<b>内容悄悄变错</b>—— 距离公式换个写法、被挡判据换成另一份、白名单认错那一份，
     * 都不会抛异常，只会让人在实机里按错的报告去改代码。所以期望值全部**手写**：</p>
     * <ul>
     *     <li>僵尸宽 <b>0.60</b>、棋子宽 <b>0.60</b>（回退后）⇒ 出手距离 = √(1.44 + 0.6) = √2.04
     *         = <b>1.4283</b> 格（手算，1.20.1 {@code MeleeAttackGoal#getAttackReachSqr}）；</li>
     *     <li>摆在 <b>0.70</b> 格 ⇒ 0.70 ≤ 1.4283 ⇒ 「在射程内 = true」；</li>
     *     <li>0.70 格同时落在 FOOT 候选盒里 ⇒「被挡名单 = 是」。⚠️ 这里相交的是
     *         「僵尸碰撞箱 ∩ 棋子<b>所在格</b>的盒」<b>不是</b>「僵尸 ∩ 棋子自己的碰撞箱」：
     *         0.70 时僵尸箱相对棋子格是 <b>[0.9, 1.5]</b>、格盒是 <b>[0, 1]</b> ⇒ X 上只重叠
     *         <b>0.10 格</b>（僵尸其实没碰到棋子 —— 棋子自身箱是 [0.2, 0.8]）。
     *         所以这个 0.70 是有余量但**余量不大**的位置，别把「脚下那一格」当成一个很宽的盒子；</li>
     *     <li>被挡住 + 锁上目标 + 够得着 ⇒ 结论必须是第 ⑤ 条（「该是原版 goal 没跑」）。</li>
     * </ul>
     *
     * <h3>★ 对照断言（否则「不变」可能只是空过）</h3>
     * <p>「状态全不变」在命令压根没跑时同样成立，所以另有两条对着干的断言：命令返回值必须是 1
     * （不是「附近没有棋子」那条失败分支），且它实际输出的文本非空、并且真的含那只怪的那一行。
     * 输出文本读的是 {@link #lastBlockReport}，它**只有走命令那条路**才会被写
     * —— 所以这两条一起证明「brigadier 解析 → 命令体 → 报告文本」整条链通了。</p>
     */
    private static void blockDiagnosisGroup(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- E) 只读诊断命令 /guardianprotocol block\n");
        // 单开一列场地（离 A 段 20 格、离 D 段 30 格），并把命令源摆在棋子**自己身上**：
        // 命令认的是「8 格内最近的棋子」，距离 0 ⇒ 最近的那颗只可能是它（同 skills 的 D 组）。
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 100;
        int by = groundYAt(level, bx, bz, origin.getY());

        // 白名单：本组要「僵尸进得了被挡名单」，所以显式覆盖一份（而不是赌自验台配置文件的内容）。
        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
        List<Entity> cleanup = new ArrayList<>();
        try {
            PixelUnit pawn = spawnPawn(level, new BlockPos(bx, by, bz), UnitBranch.GUARD_FIGHTER);
            cleanup.add(pawn);
            makeTanky(pawn, 400.0D);
            Mob mob = spawnWithAi(level, EntityType.ZOMBIE, bx, by, bz, "诊断僵尸");
            cleanup.add(mob);
            // 血给足：斗士会打被挡住的目标，而它死掉就会从被挡名单里被摘掉（踩坑记录）
            makeTanky(mob, 500.0D);
            // ★ 摆到「距棋子 0.70 格」：既在 FOOT 候选盒里，又小于这台僵尸 1.4283 格的出手距离
            mob.setPos(pawn.getX() + 0.70D, pawn.getY(), pawn.getZ());
            // 只推 manager 心跳（不 tick 生物）⇒ 僵尸停在原地 0.70 格，断言里那个距离是确定的
            PawnCombatManager.onServerTick(level.getServer());

            List<PathfinderMob> held = PawnCombatManager.debugBlocked(pawn);
            sb.append("   场地=").append(fmt(new BlockPos(bx, by, bz)))
                    .append("  棋子宽度=").append(r2(pawn.getBbWidth()))
                    .append("  僵尸宽度=").append(r2(mob.getBbWidth())).append('\n');

            // ---- E1 前置夹具：夹具自己先成立，再谈被测对象（踩坑记录的规矩）----
            check(sb, "E1 夹具：棋子宽度已回退到 0.60 / 僵尸宽度 0.60 / 它拿到了阻挡名额（被挡名单 "
                            + held.size() + " 个）",
                    Math.abs(pawn.getBbWidth() - 0.6D) < 1.0E-6D
                            && Math.abs(mob.getBbWidth() - 0.6D) < 1.0E-6D
                            && held.contains(mob));
            if (!held.contains(mob)) {
                sb.append("   ⚠ 跳过 E2~E6：夹具没成立（僵尸没进被挡名单），断言会变成假的\n");
                return;
            }

            // ---- E2/E3：命令真的跑了 + 只读 ----
            int slot0 = pawn.getSkillSlot();
            int points0 = pawn.getSkillPoints();
            int active0 = pawn.getSkillActiveTicks();
            int ammo0 = pawn.getSkillAmmo();
            int held0 = held.size();
            int entities0 = entityCount(level);

            CommandSourceStack console = level.getServer().createCommandSourceStack()
                    .withPosition(pawn.position());
            CommandDispatcher<CommandSourceStack> disp = level.getServer().getCommands().getDispatcher();
            int rc = 0;
            String cmdErr = "";
            try {
                rc = disp.execute("guardianprotocol block", console);   // 走 brigadier 解析，不直接调方法
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            String out = debugLastBlockReport();
            int lines = out.isEmpty() ? 0 : out.split("\n").length;

            check(sb, "E2 ★ 命令真的跑到了（返回值 = " + rc + "，输出 " + lines + " 行"
                            + (cmdErr.isEmpty() ? "" : "｜异常=" + cmdErr) + "）",
                    rc == 1 && lines >= 4);
            check(sb, "E3 ★ 只读不改状态：槽位/技力/激活剩余/弹药（"
                            + slot0 + "/" + points0 + "/" + active0 + "/" + ammo0 + " → "
                            + pawn.getSkillSlot() + "/" + pawn.getSkillPoints() + "/"
                            + pawn.getSkillActiveTicks() + "/" + pawn.getSkillAmmo()
                            + "）、被挡名单 " + held0 + " → "
                            + PawnCombatManager.debugBlocked(pawn).size()
                            + "、场上实体 " + entities0 + " → " + entityCount(level) + " 全不变",
                    pawn.getSkillSlot() == slot0 && pawn.getSkillPoints() == points0
                            && pawn.getSkillActiveTicks() == active0 && pawn.getSkillAmmo() == ammo0
                            && PawnCombatManager.debugBlocked(pawn).size() == held0
                            && entityCount(level) == entities0);

            // ★ 把命令的**实际输出**整段抄进报告：报告里这一块就是玩家聊天框看到的那几行，
            //   「命令到底给人看了什么」不再需要靠转述（开发环境起不了客户端）。
            if (!out.isEmpty()) {
                sb.append("   ---- 命令实际输出（照抄，" ).append(lines).append(" 行）----\n");
                for (String line : out.split("\n")) {
                    sb.append("   | ").append(line).append('\n');
                }
            }

            // ---- E4 白名单那一行（覆盖态）----
            //   ⚠️ 期望值里**不能带引号**：报告那一行是 `StringBuilder.append(List)` 落到
            //   `AbstractCollection.toString()`，格式是 [a, b]（元素不加引号）。
            //   实跑第一版就是在这里栽的：断言写成 `["minecraft:zombie"]`（把下面 675 行散文里
            //   TOML 写法的引号当成了列表渲染），于是 `contains` 为假 ⇒ 一条**假失败**
            //   （命令输出本身是对的，见报告里照抄的那 10 行）。
            String wlLine = lineContaining(out, "生效白名单：");
            String srcLine = lineContaining(out, "来源：");
            check(sb, "E4 生效白名单那一行 = " + wlLine.trim()
                            + "｜" + srcLine.trim(),
                    wlLine.contains("生效白名单：[minecraft:zombie]")
                            && wlLine.contains("共 1 条") && wlLine.contains("非空")
                            && srcLine.contains("来源：自验/调试覆盖"));

            // ---- E5 内容正确性：手算期望值（写死常量，不调被测实现算）----
            //   ⚠️ 精确值（2026-10 实跑读回来的那个）：1.4282856723544584 —— 宽度是 float 0.6F，
            //   原版那条式子先按 float 算 `2.0400001F` 再落到 double，所以比手算 √2.04 = 1.4282856857
            //   还小 1.3E-8（纯浮点）。这里按「四舍五入到 4 位小数」断言：容差取半宽 5.0E-5
            //   （= 1.4283 ± 0.00005，实测偏差 1.43E-5），既不放过真的口径漂移、也不被 float 末位咬到。
            final double EXPECTED_REACH = 1.4283D;      // √(1.44 + 0.6) = √2.04
            final double REACH_TOLERANCE = 5.0E-5D;     // 4 位小数的四舍五入半宽
            double actualReach = PawnCombatManager.meleeAttackReach(mob, pawn);
            String mobLine = lineContaining(out, "诊断僵尸");
            check(sb, "E5 ★ 攻击距离 = √2.04 = " + EXPECTED_REACH + "（实际 " + actualReach + "）",
                    Math.abs(actualReach - EXPECTED_REACH) <= REACH_TOLERANCE);
            check(sb, "E5 ★ 那只怪的那一行（照抄）：" + mobLine.trim(),
                    mobLine.contains("中心距=0.70格")
                            && mobLine.contains("攻击距离=1.4283格")
                            && mobLine.contains("在射程内=true")
                            && mobLine.contains("被挡名单=是")
                            && mobLine.contains("target=棋子")
                            && mobLine.contains("未挡住原因=null（准入）"));

            // ---- E6 结论行：被挡住 + 锁上 + 够得着 ⇒ 第 ⑤ 条 ----
            String verdictLine = lineContaining(out, "4) 结论：");
            check(sb, "E6 ★ 结论落在第 ⑤ 条（该是原版 goal 没跑）：" + verdictLine.trim(),
                    verdictLine.contains("⑤ 以上都不满足"));

            // ---- E7 命令节点：注册了 + 无参那条可被解析 ----
            com.mojang.brigadier.tree.CommandNode<CommandSourceStack> rootNode =
                    disp.getRoot().getChild("guardianprotocol");
            com.mojang.brigadier.tree.CommandNode<CommandSourceStack> blockNode =
                    rootNode == null ? null : rootNode.getChild("block");
            check(sb, "E7 ★ 命令节点 /guardianprotocol block 已注册（命令树）", blockNode != null);
            boolean parsed = false;
            String parseErr = "";
            try {
                // 同 D5：brigadier 的失败不靠「返回 null」表达（解析不掉的输入进异常表），两种都认
                var pr = blockNode == null ? null : disp.parse("guardianprotocol block", console);
                parsed = pr != null && pr.getContext() != null && pr.getExceptions().isEmpty();
                if (pr != null && !pr.getExceptions().isEmpty()) {
                    parseErr = String.valueOf(pr.getExceptions());
                }
            } catch (Exception ex) {
                parseErr = String.valueOf(ex.getMessage());
            }
            check(sb, "E7 ★ 不带参数那条**可被解析**且本身就是可执行节点（getCommand != null）"
                            + (parseErr.isEmpty() ? "" : "｜解析异常=" + parseErr),
                    parsed && blockNode != null && blockNode.getCommand() != null);

            // ---- E8 来源那一行的另一支：清掉覆盖后必须显示「配置文件」----
            //   ⚠️ 判据不能写 `!src2.contains("自验/调试覆盖")`：非覆盖分支的文案**自己**就写着
            //   「未检测到自验/调试覆盖」（见上面标题分支），否定式会被兄弟分支的措辞证伪 ⇒ 假失败。
            //   改成正面判据：非覆盖分支必须打出「来源：配置文件」且带着「未检测到」那句。
            BlockCandidates.setWhitelistOverride(null);
            try {
                int rc2 = disp.execute("guardianprotocol block", console);
                String out2 = debugLastBlockReport();
                String wl2 = lineContaining(out2, "生效白名单：").trim();
                String src2 = lineContaining(out2, "来源：").trim();
                check(sb, "E8 清掉自验覆盖后 → 返回值 " + rc2 + "｜" + wl2 + "｜" + src2,
                        rc2 == 1 && src2.contains("来源：配置文件")
                                && src2.contains("未检测到自验/调试覆盖"));
            } finally {
                BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
            }
        } catch (Exception ex) {
            // ★ 异常必须计入 FAIL，不能只打一行提示（踩坑记录：否则统计里 FAIL=0，看着一切正常）
            check(sb, "E 组异常（本组断言未执行完）：" + ex, false);
            GuardianProtocol.LOGGER.error("[{}] 自验 E 组（阻挡诊断命令）异常", GuardianProtocol.MODID, ex);
        } finally {
            discardAll(cleanup);
            BlockCandidates.setWhitelistOverride(oldWhitelist);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * <b>X 组</b>（★ 2026-10 新增）：把已提交的核心修复 —— <b>嘲讽 goal 在「盯上我方棋子」时让出 MOVE 旗标</b>
     * —— 钉成断言。被测对象是 {@link MoveToProtectTargetGoal#canUse()}，判据逐字是
     * {@code mob.getTarget() instanceof PixelUnit && isAlive()}（注释里已写全机理）。
     *
     * <h3>为什么这组断言必须存在（设计实机 A/B 原话）</h3>
     * <p>「当敌方被保护目标嘲讽后被棋子阻挡会出现打不还手，但如果没被保护目标嘲讽被阻挡时则会正常攻击棋子」；
     * 「除了监守者是一直能攻击棋子外，其他敌人都是打不还手」。机理：本 goal 挂 priority 0 且独占
     * {@code Flag.MOVE}，原版近战 goal（僵尸挂 priority 2、要 {@code MOVE + LOOK}）能不能起跑由
     * {@code GoalSelector#goalCanBeReplacedForAllFlags}（:70）+ {@code GoalSelector#tick}（:103）判，
     * 而 {@code WrappedGoal#canBeReplacedBy(o)} = {@code this.isInterruptable() && this.getPriority()
     * >= o.getPriority()} ⇒ 锁着的是 0、候选是 2 ⇒ 近战 goal 永远起不来（<b>监守者走 {@code Brain}/Activity、
     * 不经过 GoalSelector，所以它是例外</b> —— 这条例外反过来印证机理）。</p>
     *
     * <h3>X0~X7</h3>
     * <ul>
     *     <li><b>X0</b> 夹具自证：夹具自己先成立，断言才是在测产品（踩坑记录）；</li>
     *     <li><b>X1</b> 让位：target = 我方棋子且活着 ⇒ {@code canUse() == false}，且
     *         {@code canContinueToUse()} 同值（同一条判据 ⇒ 跑到一半也立刻让位）；</li>
     *     <li><b>X2 / X3 / X4</b> 三条对照：没有 target ⇒ true；target 是别的生物 ⇒ true；
     *         棋子 {@code setHealth(0)} ⇒ true（死了就不让位）；</li>
     *     <li><b>X5</b> 原版语义夹具：两个测试专用假 goal（都占 MOVE，priority 0 / 2 各一），
     *         走真 {@code goalSelector.tick()} 证明「priority 0 占着 ⇒ priority 2 起不来」、
     *         「priority 0 失效后 priority 2 立刻起跑」—— 把「旗标饿死」从推理变成实测；</li>
     *     <li><b>X6</b> 夹具读数：报告里那两行 goal 读数（命令与自验共用的 {@link #goalReadout}）
     *         必须能分辨「修复前 / 修复后」两种现场；另<b>如实记</b>「真出手（挥臂 + 掉血）在无头台上验不了」；</li>
     *     <li><b>X7</b> 与其它锁目标路径的<b>正交性</b>：让位只由 {@code canUse()} 一个判据决定 ——
     *         旗标仍是 MOVE、priority 仍是 0、调用无副作用（不碰坐标 / 不重下寻路 / 不挂摘 goal）、
     *         也不碰 manager 的「锁目标」那一路（target 仍归它管）。</li>
     * </ul>
     */
    private static void blockGoalYieldGroup(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- X) 嘲讽 goal 的让位（MOVE 旗标 / priority / 原版 GoalSelector 语义）\n");
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 170;   // 另开一列：离 E 组 70 格、离 A/B/D 段都远
        int by = groundYAt(level, bx, bz, origin.getY());
        List<Entity> cleanup = new ArrayList<>();
        try {
            PixelUnit pawn = spawnPawn(level, new BlockPos(bx, by, bz), UnitBranch.GUARD_FIGHTER);
            cleanup.add(pawn);
            makeTanky(pawn, 400.0D);        // 血够厚：X4 会把它打到 0 再抬回来
            PathfinderMob stranger = (PathfinderMob) spawnWithAi(level, EntityType.ZOMBIE,
                    bx + 4, by, bz, "X 别的生物");
            cleanup.add(stranger);
            PathfinderMob mob = (PathfinderMob) spawnWithAi(level, EntityType.ZOMBIE,
                    bx + 2, by, bz, "X 被嘲讽的怪");
            cleanup.add(mob);
            clearOwnGoals(mob);             // 夹具：它自带的 goal 全清掉，剩下的一律是我们摆的（同 solid 的踩坑记录）
            MoveToProtectTargetGoal taunt = new MoveToProtectTargetGoal(mob);
            mob.goalSelector.addGoal(0, taunt);   // 与 ProtectTargetManager#taunt 逐字同一条挂法（priority 0）
            Vec3 far = new Vec3(bx + 12.5D, by, bz + 0.5D);
            taunt.setTargetPos(far);

            // ---- X0 夹具自证 ----
            check(sb, "X0 夹具：怪身上只剩我们挂的嘲讽 goal（goal 数 "
                            + mob.goalSelector.getAvailableGoals().size() + "）、注册 priority = "
                            + goalPriority(mob, taunt) + "、旗标 = " + taunt.getFlags(),
                    mob.goalSelector.getAvailableGoals().size() == 1 && goalPriority(mob, taunt) == 0
                            && taunt.getFlags().equals(EnumSet.of(Goal.Flag.MOVE)));
            check(sb, "X0 夹具：目标点摆到 " + r2(Math.sqrt(mob.distanceToSqr(far))) + " 格外（> 0.5 格）"
                            + "⇒ 距离判据恒成立，canUse() 的真值只由「让位」那条决定；此刻 target="
                            + (mob.getTarget() == null ? "无" : name(mob.getTarget())),
                    mob.distanceToSqr(far) > 0.25D && mob.getTarget() == null);

            // ---- X1 让位（被测对象）----
            mob.setTarget(pawn);
            boolean x1 = taunt.canUse();
            boolean x1cont = taunt.canContinueToUse();
            check(sb, "X1 ★ 让位：target = 我方棋子（活着）⇒ canUse() = " + x1
                            + " / canContinueToUse() = " + x1cont + "（同值 ⇒ 跑到一半也立刻让位）",
                    !x1 && !x1cont);

            // ---- X2 / X3 / X4 三条对照 ----
            mob.setTarget(null);
            boolean x2 = taunt.canUse();
            check(sb, "X2 对照：没有 target ⇒ canUse() = " + x2 + "（照旧行军）", x2);

            mob.setTarget(stranger);
            boolean x3 = taunt.canUse();
            check(sb, "X3 对照：target 是别的生物（" + name(stranger) + "）⇒ canUse() = " + x3
                            + "（让位只认「我方棋子」，不牵连别的锁目标路径）", x3);

            mob.setTarget(pawn);
            pawn.setHealth(0.0F);
            boolean x4 = taunt.canUse();
            check(sb, "X4 对照：棋子 setHealth(0) ⇒ canUse() = " + x4
                            + "（让位只让给「活着的」棋子；死了就照旧行军）", x4);
            pawn.setHealth(pawn.getMaxHealth());   // 抬回夹具：后面的断言还要用它
            check(sb, "X4 夹具复原：棋子血 = " + r1(pawn.getHealth())
                            + "（isAlive=" + pawn.isAlive() + "）", pawn.isAlive());

            // ---- X5 原版语义夹具（旗标饿死本体）----
            sb.append("-- X5) 原版语义夹具：两个测试专用假 goal（都占 MOVE，priority 0 / 2 各一）\n");
            PathfinderMob fixture = (PathfinderMob) spawnWithAi(level, EntityType.ZOMBIE,
                    bx + 6, by, bz, "X5 旗标夹具");
            cleanup.add(fixture);
            clearOwnGoals(fixture);
            FlagFixtureGoal low = new FlagFixtureGoal();    // priority 0、恒 true（＝修复前的嘲讽 goal）
            FlagFixtureGoal high = new FlagFixtureGoal();   // priority 2、恒 true（＝原版近战 goal）
            fixture.goalSelector.addGoal(0, low);
            fixture.goalSelector.addGoal(2, high);
            fixture.goalSelector.tick();                    // 走真 GoalSelector#tick（:103 起跑判据）
            boolean lowRun = isGoalRunning(fixture, low);
            boolean highRun = isGoalRunning(fixture, high);
            check(sb, "X5 ★ 原版语义：priority 0 在跑 = " + lowRun + " / priority 2 在跑 = " + highRun
                            + "（同一面 MOVE 旗标：0 占着 ⇒ 2 起不来 —— 这就是「打不还手」的机制本体）",
                    lowRun && !highRun);
            low.setUsable(false);                           // 让 priority 0 那个失效（＝嘲讽 goal 让位）
            fixture.goalSelector.tick();
            check(sb, "X5 ★ 让开后：priority 0 在跑 = " + isGoalRunning(fixture, low)
                            + " / priority 2 在跑 = " + isGoalRunning(fixture, high)
                            + "（⇒ 近战 goal 立刻起跑：修复让出的就是这一面旗标）",
                    !isGoalRunning(fixture, low) && isGoalRunning(fixture, high));

            // ---- X6 夹具读数（报告里那两行的口径本身也要能分辨现场）----
            String fixtureReadout = goalReadout(fixture);
            String fixtureOcc = lineContaining(fixtureReadout, "占用 MOVE 的运行中 goal = ").trim();
            check(sb, "X6 ★ 读数与夹具一致：运行中 1 个｜" + fixtureOcc,
                    fixtureReadout.contains("运行中的 goal（只算 isRunning，共 1 个）")
                            && fixtureOcc.startsWith("↳ 占用 MOVE 的运行中 goal = FlagFixtureGoal(priority 2"));
            //   ① 「修复前」的现场签名：嘲讽 goal 正在跑（占着 MOVE）+ target 已经锁上棋子。
            //      夹具用「先 tick 起跑、再锁目标、不 tick」造出这一拍（等价于修复前的稳态）。
            mob.setTarget(null);
            mob.goalSelector.tick();                        // 起跑：占住 MOVE
            mob.setTarget(pawn);                            // 这一拍不动 selector ⇒ 快照 = 修复前
            String beforeFix = goalReadout(mob);
            String beforeOcc = lineContaining(beforeFix, "占用 MOVE 的运行中 goal = ").trim();
            check(sb, "X6 ★ 读数能指认真凶（修复前签名）：" + beforeOcc,
                    beforeOcc.startsWith("↳ 占用 MOVE 的运行中 goal = MoveToProtectTargetGoal(priority 0")
                            && beforeOcc.contains("饿死"));
            //   ② 「修复后」的现场签名：再 tick 一拍 ⇒ canUse() 返回 false ⇒ 旗标让出来。
            mob.goalSelector.tick();
            String afterFix = goalReadout(mob);
            String afterOcc = lineContaining(afterFix, "占用 MOVE 的运行中 goal = ").trim();
            check(sb, "X6 ★ 让位后读数（修复后应当看到的）：" + afterOcc,
                    afterOcc.startsWith("↳ 占用 MOVE 的运行中 goal = （MOVE 空闲）")
                            && afterFix.contains("（没有运行中的 goal）"));
            sb.append("   ⚠ 跳过（如实记）：真出手（挥臂包 + 棋子掉血）在无头台上**验不了** —— 台子不推进游戏刻，\n")
                    .append("      MeleeAttackGoal#canUse() 的 20 tick 门读的是冻结的 getGameTime()，最多只放行一次\n")
                    .append("      （踩坑记录）。实机验法：站到被嘲讽的怪旁边敲 /guardianprotocol block，\n")
                    .append("      看它那两行 goal 读数 —— 修复后应当看不到嘲讽 goal 在跑、占用者是它自己的近战 goal\n")
                    .append("      （或 MOVE 空闲），并且它真的在挥拳、棋子掉血。\n");

            // ---- X7 正交性：让位只由 canUse() 一个判据决定，别的路径一律没动 ----
            check(sb, "X7 让位不改旗标：嘲讽 goal 仍只占 MOVE（" + taunt.getFlags() + "）",
                    taunt.getFlags().equals(EnumSet.of(Goal.Flag.MOVE)));
            check(sb, "X7 让位不改 priority：注册值仍是 " + goalPriority(mob, taunt)
                            + "（ProtectTargetManager 挂 0 —— 让位靠返回 false，不靠调 priority）",
                    goalPriority(mob, taunt) == 0);
            Vec3 pos0 = mob.position();
            int goals0 = mob.goalSelector.getAvailableGoals().size();
            boolean pure = true;
            mob.setTarget(pawn);
            for (int i = 0; i < 3; i++) {
                pure &= !taunt.canUse();
            }
            for (int i = 0; i < 3; i++) {
                mob.setTarget(null);
                pure &= taunt.canUse();
                mob.setTarget(pawn);
            }
            check(sb, "X7 ★ 让位判据无副作用：连问 3+3 次 canUse()，坐标 " + shortVec(pos0)
                            + " → " + shortVec(mob.position()) + "、target 仍是棋子、goal 数 " + goals0
                            + " → " + mob.goalSelector.getAvailableGoals().size()
                            + "（不碰坐标 / 不重下寻路 / 不挂摘 goal）",
                    pure && mob.position().equals(pos0) && mob.getTarget() == pawn
                            && mob.goalSelector.getAvailableGoals().size() == goals0);
            check(sb, "X7 ★ 与「锁目标」那一路正交：让位期间 mob.getTarget() 仍是棋子（target 归 manager 的 "
                            + "lockAttackTarget 管，让位不动它）、嘲讽 goal 仍在 selector 里（让位 ≠ 卸载嘲讽）",
                    mob.getTarget() == pawn && goalPriority(mob, taunt) == 0 && !isGoalRunning(mob, taunt));
        } catch (Exception ex) {
            // ★ 异常必须计入 FAIL（踩坑记录：否则统计里 FAIL=0，看着一切正常）
            check(sb, "X 组异常（本组断言未执行完）：" + ex, false);
            GuardianProtocol.LOGGER.error("[{}] 自验 X 组（嘲讽 goal 让位）异常", GuardianProtocol.MODID, ex);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * <b>G 组</b>（★ 2026-10 新增，重做「拉怪」）：把「棋子把<b>攻击范围内</b>的敌人锁成
     * {@code target = 棋子}」钉成断言（被测对象 = {@code PawnCombatManager} 主循环里的
     * 「拉怪」那一步，门 = {@code BranchDef#canAttack()}）。
     *
     * <h3>为什么必须有它（这不是锦上添花，是链条缺的那一环）</h3>
     * <p>原版<b>没有任何 goal 会主动把棋子选成目标</b>：僵尸的 {@code targetSelector} 只有
     * {@code HurtByTargetGoal} + {@code NearestAttackableTargetGoal<Player>} +
     * {@code <AbstractVillager>} + {@code <IronGolem>} + {@code <Turtle>}
     * （1.20.1 {@code Zombie#addBehaviourGoals}）；而本 mod 原来唯一给怪赋目标的地方
     * （{@code updateBlocking → driveToAnchor → lockAttackTarget}）<b>只对已经进了被挡名单的怪生效</b>
     * —— 等于要求它「先自己走到棋子面前」。缺起点：怪不来找棋子 ⇒ 走不到 ⇒ 进不了名单 ⇒ 不还手
     * （实机 {@code /guardianprotocol block} 抓到过：附近 5 只尸壳 {@code target=无}、{@code 被挡名单=否}）。</p>
     *
     * <h3>G 组前置（判据可分离的**关键**）</h3>
     * <p>把阻挡白名单<b>清空</b>（谁都不挡）⇒ 阻挡那条锁目标的路径（{@code updateBlocking →
     * driveToAnchor → lockAttackTarget}）在本组<b>根本不可能跑</b>。于是「target 变成了棋子」
     * <b>只可能来自拉怪那一步</b>。前置读数（生效白名单）会在报告里打出来。</p>
     *
     * <h3>G1 ~ G6</h3>
     * <ul>
     *     <li><b>G1</b> 拉到了：僵尸摆进棋子的攻击范围 ⇒ 跑一次主循环 ⇒ {@code getTarget() == pawn}；</li>
     *     <li><b>G2</b> 对照·范围外不拉：同一只摆到 12 格外 ⇒ {@code getTarget() == null}
     *         （先证明它真的在外面，再证明没被拉）；</li>
     *     <li><b>G3</b> 对照·不攻击的分支不拉：换成 {@code canAttack()==false} 的阵法术师，
     *         怪<b>同样落在它的攻击范围格中心、同样在「拉怪范围」候选里</b> ⇒ {@code null}
     *         —— <b>这条测的是「门」</b>：门要是写漏了（无脑拉全场），它就会红；
     *         ★ 另开一块场地（+6 格），否则 G1 那颗斗士的拉怪范围会罩到本组的怪，测不出门；</li>
     *     <li><b>G4</b> 夹具自证：怪真生成了、真在候选里（打出实际中心距格数）、只读口子连调两次
     *         不改状态 —— 免得「没拉到」其实是夹具没成立、伪装成功能没生效（踩坑记录的规矩）；</li>
     *     <li><b>G5</b> 与索敌/阻挡<b>正交</b>：沿用前置读数 —— 被挡名单恒空（那条锁目标的路径没跑），
     *         而 target 是棋子 ⇒ 这一锁只可能来自拉怪那一步；</li>
     *     <li><b>G6</b> 诊断读数：门关着时 {@code /guardianprotocol block} 的 ⓪′/⓪″ <b>不许报异常</b>
     *         （否则最该看的仪器成了假警报源），且每只怪那行带着「在拉怪范围 / 拉怪门」两个新读数。</li>
     * </ul>
     */
    private static void blockLureGroup(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- G) 拉怪：把攻击范围内的敌人锁成 target=棋子（门 = canAttack()）\n");
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 150;    // 另开一列：离 E 组 50 格、离 D(130)/A_B(120)/X(170) 都 ≥20 格
        int by = groundYAt(level, bx, bz, origin.getY());

        // ★ G 组前置：把阻挡白名单**清空**（谁都不挡）。
        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of());
        List<Entity> cleanup = new ArrayList<>();
        try {
            sb.append("   G 组前置：阻挡白名单 = ").append(BlockCandidates.describe())
                    .append("（清空 = 谁都不挡 ⇒ updateBlocking→driveToAnchor→lockAttackTarget "
                            + "这条锁目标的路径整条关掉；本组锁上目标只可能来自拉怪）\n");

            PixelUnit pawn = spawnPawn(level, new BlockPos(bx, by, bz), UnitBranch.GUARD_FIGHTER);
            cleanup.add(pawn);
            makeTanky(pawn, 400.0D);
            AttackRange.Cell cell = frontCell(pawn);
            sb.append("   场地=").append(fmt(new BlockPos(bx, by, bz)))
                    .append(" 棋子=").append(pawn.getBranch().branchName())
                    .append(" 攻击范围格子=").append(cells(pawn))
                    .append("（夹具取最远那格 (").append(cell.x()).append(',').append(cell.z())
                    .append(")；门 canAttack=").append(pawn.getBranch().canAttack()).append("）\n");

            Mob mob = spawnWithAi(level, EntityType.ZOMBIE, bx, by, bz, "G 被拉的怪");
            cleanup.add(mob);
            makeTanky(mob, 500.0D);
            mob.setTarget(null);
            placeInRangeCell(mob, pawn, cell);

            // ---- G4 夹具自证（先成立，再谈被测对象）----
            Vec3 pos0 = mob.position();
            List<LivingEntity> inRange1 = PawnCombatManager.debugLureCandidates(level, pawn);
            List<LivingEntity> inRange2 = PawnCombatManager.debugLureCandidates(level, pawn);
            double dist = PawnCombatManager.centerDistance(pawn, mob);
            check(sb, "G4 夹具①：怪真的生成了（活着=" + !mob.isDeadOrDying() + "、已移除=" + mob.isRemoved()
                            + "）、摆在攻击范围格 (" + cell.x() + "," + cell.z() + ") 的中心，实际中心距 "
                            + r2(dist) + " 格",
                    !mob.isDeadOrDying() && !mob.isRemoved() && dist > 0.0D);
            check(sb, "G4 夹具②：它确实在「拉怪范围」候选里（范围内 " + inRange1.size() + " 只；含它="
                            + inRange1.contains(mob) + "）—— 否则 G1 的「没拉到」会是夹具没成立、"
                            + "伪装成功能没生效",
                    inRange1.contains(mob));
            check(sb, "G4 夹具③：只读口子连调两次结果一致（" + inRange1.size() + " / " + inRange2.size()
                            + " 只）、并把状态原样留着（target=" + (mob.getTarget() == null ? "null" : "非空")
                            + "、坐标 " + shortVec(mob.position()) + "）",
                    inRange1.size() == inRange2.size() && mob.getTarget() == null
                            && mob.position().equals(pos0));

            // ---- G1 被测：拉到了 ----
            //   onServerTick = 一次真实主循环（拉怪 → 阻挡 → 绕行 → 索敌 → 攻击），顺序与线上一致。
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "G1 ★ 拉到了：跑一次主循环后 mob.getTarget() = "
                            + (mob.getTarget() == pawn ? "棋子（" + pawn.getBranch().branchName() + "）"
                            : String.valueOf(mob.getTarget()))
                            + "；棋子被挡名单 = " + PawnCombatManager.debugBlocked(pawn).size() + " 个",
                    mob.getTarget() == pawn);

            // ---- G2 对照：同一只摆到 12 格外 ----
            mob.setTarget(null);
            mob.setPos(pawn.getX() - 12.5D, pawn.getY(), pawn.getZ());
            double farDist = PawnCombatManager.centerDistance(pawn, mob);
            List<LivingEntity> farCand = PawnCombatManager.debugLureCandidates(level, pawn);
            check(sb, "G2 夹具：同一只已摆到 " + r2(farDist) + " 格外、**不在**拉怪范围候选里（候选 "
                            + farCand.size() + " 只；含它=" + farCand.contains(mob) + "）、它本身还活着="
                            + !mob.isDeadOrDying(),
                    farDist > 12.0D && !farCand.contains(mob) && !mob.isDeadOrDying());
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "G2 对照 ★ 范围外不拉：getTarget() = "
                            + (mob.getTarget() == null ? "null" : name(mob.getTarget())),
                    mob.getTarget() == null);

            // ---- G3 对照：不攻击的分支不拉（另开一块场地，否则 G1 那颗斗士会替它拉）----
            //   ★ 场地自己重新扫一次地面（+6 格那一列的地面高度可能与基准列不同）。
            int by3 = groundYAt(level, bx, bz + 6, origin.getY());
            PixelUnit noAttack = spawnPawn(level, new BlockPos(bx, by3, bz + 6), UnitBranch.CASTER_PHALANX);
            cleanup.add(noAttack);
            makeTanky(noAttack, 400.0D);
            AttackRange.Cell cell3 = frontCell(noAttack);
            Mob mob3 = spawnWithAi(level, EntityType.ZOMBIE, bx, by3, bz + 6, "G3 不攻击分支的怪");
            cleanup.add(mob3);
            makeTanky(mob3, 500.0D);
            mob3.setTarget(null);
            placeInRangeCell(mob3, noAttack, cell3);
            List<LivingEntity> inRange3 = PawnCombatManager.debugLureCandidates(level, noAttack);
            check(sb, "G3 夹具：棋子 = " + noAttack.getBranch().branchName() + "（canAttack="
                            + noAttack.getBranch().canAttack() + "，与 G1 那颗相隔 6 格）；怪摆在它的攻击范围格 ("
                            + cell3.x() + "," + cell3.z() + ") 中心、中心距 "
                            + r2(PawnCombatManager.centerDistance(noAttack, mob3))
                            + " 格、**在拉怪范围候选里**（" + inRange3.size() + " 只，含它="
                            + inRange3.contains(mob3) + "）⇒ 这条测的是「门」，不是「怪不在范围里」",
                    !noAttack.getBranch().canAttack() && inRange3.contains(mob3));
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "G3 对照 ★ 不攻击的分支不拉（门关着）：getTarget() = "
                            + (mob3.getTarget() == null ? "null" : name(mob3.getTarget())),
                    mob3.getTarget() == null);

            // ---- G5 与索敌/阻挡正交（沿用 G 组前置的读数）----
            mob.setTarget(null);
            placeInRangeCell(mob, pawn, cell);
            PawnCombatManager.onServerTick(level.getServer());
            List<PathfinderMob> heldG5 = PawnCombatManager.debugBlocked(pawn);
            check(sb, "G5 ★ 与索敌/阻挡正交：白名单空 ⇒ 被挡名单 = " + heldG5.size()
                            + " 个（driveToAnchor→lockAttackTarget 这一路根本没跑），而同一只的 getTarget() = "
                            + (mob.getTarget() == pawn ? "棋子" : "不是棋子")
                            + " ⇒ 这一锁只可能来自拉怪那一步",
                    heldG5.isEmpty() && mob.getTarget() == pawn);

            // ---- G6 诊断读数：门关着时不许报异常（钉住 ⓪′/⓪″/⓪‴ 的判据与顺序）----
            String repNoAttack = debugBlockReport(level, noAttack);
            String line0p = lineContaining(repNoAttack, "⓪′").trim();
            String line0pp = lineContaining(repNoAttack, "⓪″").trim();
            String line0ppp = lineContaining(repNoAttack, "⓪‴").trim();
            String noAttackMobLine = lineContaining(repNoAttack, name(mob3)).trim();
            check(sb, "G6 ★ 诊断（门关着）：⓪′ 报拉怪门=关(canAttack)、⓪″ 也不报异常 —— "
                            + line0p + "｜" + line0pp,
                    line0p.contains("拉怪门=关(canAttack)") && line0pp.contains("门关着"));
            check(sb, "G6 ★ 诊断：那只怪那行带着两个新读数 —— " + noAttackMobLine,
                    noAttackMobLine.contains("在拉怪范围=是")
                            && noAttackMobLine.contains("拉怪门=关(canAttack)"));
            check(sb, "G6 ★ 诊断：⓪‴ 报出「范围内 " + inRange3.size() + " 只」而不是「不该被拉」—— "
                            + line0ppp,
                    line0ppp.contains("在拉怪范围内共"));
            //   对着干的另一半：门开 + 范围内有怪 + 已经锁上 ⇒ ⓪″ 必须报「无异常」
            String repAttack = debugBlockReport(level, pawn);
            String yes0pp = lineContaining(repAttack, "⓪″").trim();
            check(sb, "G6 对照（门开 + 已锁上）：⓪″ 报无异常 —— " + yes0pp,
                    yes0pp.contains("无异常"));

            // ★ 没验到的部分**如实写**（别设抽奖断言）：拉过去之后它会不会真的走到并挥拳，
            //   在无头台上验不了 —— 世界时钟不推进，MeleeAttackGoal#canUse() 的 20 tick 门
            //   读的是冻结的 getGameTime()（踩坑记录、踩坑记录）。
            sb.append("   ⚠ 跳过（如实记）：拉怪只保证「给它目标」，**它会不会真的走到棋子面前并挥拳**\n")
                    .append("      在无头台上验不了 —— 台子不推进游戏刻，MeleeAttackGoal#canUse() 的 20 tick 门\n")
                    .append("      读冻结的 getGameTime()（踩坑记录）。\n")
                    .append("      实机验法：把棋子摆在怪必经之路上（或让保护目标嘲讽它），看它是否**主动朝棋子走来**、\n")
                    .append("      贴到 1.3 格锚点、挥拳让棋子掉血；同时 /guardianprotocol block 会打出每只怪的\n")
                    .append("      「在拉怪范围=是」「拉怪门=开(canAttack)」「target=棋子」，以及 ⓪′/⓪″/⓪‴ 三条结论。\n");
        } catch (Exception ex) {
            // ★ 异常必须计入 FAIL（踩坑记录：否则统计里 FAIL=0，看着一切正常）
            check(sb, "G 组异常（本组断言未执行完）：" + ex, false);
            GuardianProtocol.LOGGER.error("[{}] 自验 G 组（拉怪）异常", GuardianProtocol.MODID, ex);
        } finally {
            discardAll(cleanup);
            BlockCandidates.setWhitelistOverride(oldWhitelist);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * 把怪摆到「棋子的某个攻击范围格」的中心。
     *
     * <p>位置<b>直接问范围数据要</b>（{@code worldCells()} 已是世界偏移）——
     * 别凭想象写「正前方几格」：各分支范围形状不同，第一版夹具就是这么悄悄摆到范围外的
     * （同 {@code placeAlliesInRange} 的教训）。</p>
     */
    private static void placeInRangeCell(Mob mob, PixelUnit pawn, AttackRange.Cell cell) {
        mob.setPos(pawn.getX() + cell.x(), pawn.getY(), pawn.getZ() + cell.z());
    }

    /** 棋子的攻击范围格里**最远那一格**（相对棋子的世界偏移），夹具按它摆怪。 */
    private static AttackRange.Cell frontCell(PixelUnit pawn) {
        List<AttackRange.Cell> cs = pawn.worldCells();
        AttackRange.Cell best = cs.get(0);
        for (AttackRange.Cell c : cs) {
            if ((long) c.x() * c.x() + (long) c.z() * c.z()
                    > (long) best.x() * best.x() + (long) best.z() * best.z()) {
                best = c;
            }
        }
        return best;
    }

    /**
     * X5 的**测试专用假 goal**：什么都不做，只是「占着 MOVE 旗标」。
     *
     * <p>为什么不用真的近战 goal 当夹具：{@code MeleeAttackGoal#canUse()} 有一道读
     * {@code getGameTime()} 的 20 tick 门（无头台上时钟冻结 ⇒ 判决时有时无，踩坑记录），
     * 而夹具必须<b>恒 true</b>，才能把「priority 0 占着旗标 ⇒ priority 2 起不来」这条
     * <b>原版语义</b>单独测出来（不掺进别的不确定因素）。</p>
     */
    private static final class FlagFixtureGoal extends Goal {

        /** 是否「想跑」：置 false 即代表它失效（等价于嘲讽 goal 让位）。 */
        private boolean usable = true;

        FlagFixtureGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        void setUsable(boolean usable) {
            this.usable = usable;
        }

        @Override
        public boolean canUse() {
            return this.usable;
        }
    }

    /** 报告里含某个标记的那一行（找不到时空串；断言与打印共用，只此一处）。 */
    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "";
    }

    /** 这个维度里现在还活着的实体数（只读；自验用来证明命令没凭空造/删实体）。 */
    private static int entityCount(ServerLevel level) {
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (!e.isRemoved()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 断言 6：**配置里的默认白名单**（清掉自验覆盖之后读到的真值）确实是「怪物那一类」。
     *
     * <p><b>为什么值得专门验</b>：这条口径是 2026-10 从「默认空 = 谁都不挡」改过来的，
     * 而「默认值」最容易出现「代码改了、实机没变」—— Forge 的配置文件**一旦落到磁盘就
     * 不跟着新默认值走**（老文件里的 {@code whitelist = []} 会一直留在那儿，
     * 自验台的 {@code 自验配置目录} 里就躺着一份）。所以这里显式把覆盖清成 {@code null}
     * 再判，读的就是玩家实机看到的那一份配置。</p>
     *
     * <p>三类各来一只：僵尸（{@code monster}）该被挡，牛（{@code creature}）与
     * 村民（{@code misc}，踩坑记录里就写过它不在 MONSTER 类）都不该被挡。</p>
     */
    private static void blockDefaultWhitelist(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- D) 配置默认白名单（清掉自验覆盖，读玩家那份配置）\n");
        List<String> old = BlockCandidates.setWhitelistOverride(null);
        List<Entity> cleanup = new ArrayList<>();
        try {
            sb.append("   生效白名单：").append(BlockCandidates.describe()).append('\n');
            int bx = origin.getX() + 8;
            int bz = origin.getZ() + 130;
            int by = groundYAt(level, bx, bz, origin.getY());
            Mob zombie = spawn(level, EntityType.ZOMBIE, bx, by, bz, "僵尸(monster)");
            Mob cow = spawn(level, EntityType.COW, bx + 2, by, bz, "牛(creature)");
            Mob villager = spawn(level, EntityType.VILLAGER, bx + 4, by, bz, "村民(misc)");
            cleanup.add(zombie);
            cleanup.add(cow);
            cleanup.add(villager);
            String zombieReason = BlockCandidates.rejectReason(zombie);
            String cowReason = BlockCandidates.rejectReason(cow);
            String villagerReason = BlockCandidates.rejectReason(villager);
            sb.append("   判据：僵尸=").append(zombieReason == null ? "准入" : zombieReason)
                    .append("；牛=").append(cowReason == null ? "准入" : cowReason)
                    .append("；村民=").append(villagerReason == null ? "准入" : villagerReason).append('\n');
            check(sb, "★ 默认白名单挡得住僵尸（monster 类）", zombieReason == null);
            check(sb, "★ 默认白名单不挡牛（creature 类）", cowReason != null);
            check(sb, "★ 默认白名单不挡村民（misc 类）", villagerReason != null);
        } finally {
            discardAll(cleanup);
            BlockCandidates.setWhitelistOverride(old);
        }
    }

    /** 断言 1~4：容量、散开、无瞬移、击退恢复、放行。 */
    private static void blockCapacityAndScatter(StringBuilder sb, ServerLevel level, BlockPos origin) {
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 120;
        int by = groundYAt(level, bx, bz, origin.getY());
        // ★ 先找一块平地：棋盘验证的前提是「平坦地形」，台地/草丛会让敌人掉下去（见 findFlatSpot）
        BlockPos flat = findFlatSpot(level, bx, bz, origin.getY());
        boolean flatOk = flat != null;
        if (!flatOk) {
            flat = new BlockPos(bx, by, bz);
        }
        sb.append("   场地：").append(fmt(flat))
                .append("（3×3 可站立平地 = ").append(flatOk).append("）\n");
        bx = flat.getX();
        bz = flat.getZ();
        by = flat.getY();

        // ---- A) 容量：阵法术师（阻挡数 1）+ 2 只僵尸 ⇒ 只挡最近的 1 只，另一只放行 ----
        //   ★ 2026-10（拉怪上线后改的夹具，**断言一条都没改弱**）：这一段要验的是
        //     「容量 / 粘滞 / 无瞬移 / 放行」四件事，而**拉怪会改全局索敌** ——
        //     原来用的是斗士（canAttack()=true），被放行的那只只要还在棋子的攻击范围内，
        //     下一 tick 就会被拉怪重新锁成 target=棋子，于是「放行者 target=null」这条口径
        //     在本段失效（那是 6.3.9 的新语义，不是 bug）。
        //     换成的**阵法术师**：常态阻挡与阻挡数**同样是 1**（容量语义逐字不变），
        //     而 canAttack()=false（表里写「通常时不攻击」）⇒ 拉怪门关着，变量只剩阻挡那一个。
        //     拉怪本身的语义由 G 组与 /guardianprotocol block 的 ⓪′/⓪″ 单独钉。
        sb.append("-- A) 容量：阵法术师（阻挡数 1 · canAttack=false ⇒ 拉怪门关着）+ 2 只僵尸\n");
        List<Entity> cleanup = new ArrayList<>();
        // ★ 用 spawnPawn（直接放在给定坐标）而不是 spawnPawnOnGround（自己再扫一次地面）：
        //   平地点已经是「按可站立地面筛过」的，再让 groundYAt 扫一遍会把高草又当成地面。
        PixelUnit fighter = spawnPawn(level, flat, UnitBranch.CASTER_PHALANX);
        cleanup.add(fighter);
        // ★ 棋子也要血厚：被挡住的那只会一直打棋子，而斗士只有十几点血 ——
        //   测试跑到一半棋子被打死，「被击退后走回锚点」就没人驱动了（第一版正是这样失败）。
        makeTanky(fighter, 400.0D);
        try {
            Mob near = spawnWithAi(level, EntityType.ZOMBIE, bx, by, bz, "近僵尸");
            Mob far = spawnWithAi(level, EntityType.ZOMBIE, bx, by, bz, "远僵尸");
            cleanup.add(near);
            cleanup.add(far);
            // ★ 血调厚：棋子会打被挡住的那只，20 血的僵尸一下就被打死，
            //   死掉之后粘滞名额顺延给第二只 —— 断言会莫名其妙地失败（见 makeTanky 的注释）
            makeTanky(near, 200.0D);
            makeTanky(far, 200.0D);
            // 都在「脚下那一格」里（默认搜索范围）：一个偏东 0.3、一个偏西 0.3
            // ⚠️ 注意这两个位置**与棋子的碰撞箱重叠**（棋子宽 0.6、格宽 1.0，脚下那一格里放不下
            //    两个不重叠的僵尸）。棋子变实心（6.3a）之后，这个起点属于**退化状态**：
            //    游戏里正常走过来的敌人会停在 0.6 格外，不会站在棋子里。
            //    本段的断言（容量 / 粘滞 / 无瞬移 / 放行）不受影响；
            //    「会不会钻进棋子」由 `solid` 场景专门验，那里用的是**自己走过来**的敌人。
            near.setPos(fighter.getX() + 0.3D, fighter.getY(), fighter.getZ());
            far.setPos(fighter.getX() - 0.45D, fighter.getY(), fighter.getZ() + 0.3D);

            double maxStep = driveWorld(level, List.of(near, far), 30);
            List<PathfinderMob> held = PawnCombatManager.debugBlocked(fighter);
            check(sb, "被挡住 1 个（阻挡数 " + fighter.getBranch().blockCount() + "）", held.size() == 1);
            check(sb, "挡住的是最近那只", held.size() == 1 && held.get(0) == near);
            check(sb, "★ 没有瞬移：单 tick 最大位移 " + r2(maxStep) + " 格（阈值 0.6）",
                    maxStep <= 0.6D);
            sb.append("   近僵尸 距棋子=").append(r2(Math.sqrt(near.distanceToSqr(fighter))))
                    .append(" 追击目标=").append(near.getTarget() == fighter ? "棋子" : "—")
                    .append("；远僵尸（放行）追击目标=").append(far.getTarget() == null ? "无" : "有")
                    .append(" 导航=").append(far.getNavigation().isDone() ? "停下" : "在走").append('\n');
            check(sb, "★ 放行者已被交还原版 AI（攻击目标被清掉；本段拉怪门是关的 —— 阵法术师 "
                            + "canAttack=false）—— 口径「放行者一定 target=null」只在**攻击范围之外**成立，"
                            + "范围内会被拉怪重新锁成 target=棋子（见 G 组 / 设计说明）",
                    far.getTarget() == null);
            check(sb, "★ 放行者不在被挡集合里", !held.contains(far));
            check(sb, "被挡住的那只盯着棋子", near.getTarget() == fighter);

            // ---- 击退恢复：把它推开，看它自己走回来 ----
            Vec3 anchor = PawnCombatManager.anchorStrategy()
                    .anchor(level, fighter, near, 0, 1);
            double before = near.position().distanceTo(anchor);
            // ★ 朝**侧向**推（+Z）：棋子在东侧、锚点也在东侧，朝东推等于把它推向锚点，
            //   测不出「被击退后走回来」。侧推才能让「离锚点变远 → 自己走回来」显形。
            // ★ 力度用 0.6（真击退量级）：1.5 会把僵尸甩出 8 格的「粘滞半径」，
            //   它被放行之后就不再被驱动了 —— 那种失败是测试台造成的，不是阻挡坏了。
            near.knockback(0.6D, 0.0D, 1.0D);
            double pushed = near.position().distanceTo(anchor);
            driveWorld(level, List.of(near, far), 80);
            double after = near.position().distanceTo(anchor);
            boolean stillBlocked = PawnCombatManager.debugBlocked(fighter).contains(near);
            sb.append("   击退恢复：距锚点 ").append(r2(before)).append(" → 推开后 ")
                    .append(r2(pushed)).append(" → 80 tick 后 ").append(r2(after))
                    .append("（其间仍在被挡集合里=").append(stillBlocked)
                    .append("，棋子血=").append(r1(fighter.getHealth())).append("）\n");
            check(sb, "★ 击退没有把它甩出粘滞半径（仍在被挡住名单里）", stillBlocked);
            check(sb, "★ 棋子在这段观察里活着（否则没人驱动敌人）", !fighter.isDeadOrDying());
            if (flatOk) {
                check(sb, "★ 被击退后它自己走回了锚点（距离明显变小）", after < pushed - 0.2D);
            } else {
                // ★ 「没验」必须与「通过」「失败」都区分开（踩坑记录的老规矩）：
                //   坡地/草丛上敌人在锚点下方，这条断言测不出东西，如实报「跳过 + 怎么才能验」。
                sb.append("   ⚠ 跳过「击退后走回锚点」：基准点附近 36 格内找不到 3×3 可站立平地，\n")
                        .append("      敌人在坡地/草丛上会掉到锚点下方 2 格以上，测不出「自己走回去」。\n")
                        .append("      要验这一条：把平坦存档放回 自验存档目录，或加\n")
                        .append("      -Dguardianprotocol.selftest.base=x,y,z 指到一块平地上再跑。\n");
            }
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }

        // ---- B) 散开：强攻手（阻挡数 3）+ 3 只僵尸 ⇒ 三只都在锚点上、互不重合 ----
        sb.append("-- B) 散开：强攻手（阻挡数 3）+ 3 只僵尸\n");
        List<Entity> cleanup2 = new ArrayList<>();
        BlockPos flat2 = findFlatSpot(level, bx + 20, bz, origin.getY());
        if (flat2 == null) {
            // 找不到平地也要能跑（这一段的断言不依赖平地：散开量是按锚点算的）
            flat2 = new BlockPos(bx + 20, groundYAt(level, bx + 20, bz, origin.getY()), bz);
            sb.append("   （第二块场地没找到平地，退回 ").append(fmt(flat2)).append("）\n");
        }
        PixelUnit centurion = spawnPawn(level, flat2, UnitBranch.GUARD_CENTURION);
        cleanup2.add(centurion);
        makeTanky(centurion, 400.0D);
        try {
            List<Mob> mobs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Mob m = spawnWithAi(level, EntityType.ZOMBIE, flat2.getX(), flat2.getY(), flat2.getZ(),
                        "僵尸" + (i + 1));
                makeTanky(m, 200.0D);
                m.setPos(centurion.getX() + 0.35D, centurion.getY(),
                        centurion.getZ() + (i - 1) * 0.2D);
                cleanup2.add(m);
                mobs.add(m);
            }
            driveWorld(level, mobs, 40);
            List<PathfinderMob> held = PawnCombatManager.debugBlocked(centurion);
            check(sb, "3 只都被挡住", held.size() == 3);
            double minGap = Double.MAX_VALUE;
            for (int i = 0; i < held.size(); i++) {
                for (int j = i + 1; j < held.size(); j++) {
                    double dx = held.get(i).getX() - held.get(j).getX();
                    double dz = held.get(i).getZ() - held.get(j).getZ();
                    minGap = Math.min(minGap, Math.sqrt(dx * dx + dz * dz));
                }
            }
            check(sb, "★ 横向散开：两两最小间距 " + r2(minGap) + " 格（> 0.1 即不重合）",
                    minGap > 0.1D);
            int i = 0;
            for (PathfinderMob m : held) {
                sb.append("   ").append(++i).append(". ").append(name(m))
                        .append(" 坐标=").append(shortVec(m.position())).append('\n');
            }
        } finally {
            discardAll(cleanup2);
            sb.append("   清理生成物 ").append(cleanup2.size()).append(" 个\n");
        }
    }

    /** 断言 5：玩家不被拦。 */
    private static void blockPlayerExempt(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- C) 玩家豁免\n");
        String reason;
        try {
            var profile = new com.mojang.authlib.GameProfile(
                    java.util.UUID.randomUUID(), "gp_selftest_player");
            var fake = new net.minecraft.server.level.ServerPlayer(level.getServer(), level, profile);
            boolean blockable = BlockCandidates.isBlockable(fake);
            reason = BlockCandidates.rejectReason(fake);
            check(sb, "★ 玩家不被拦（isBlockable=false）", !blockable);
        } catch (Throwable ex) {
            // 无头环境构造 ServerPlayer 有可能因为缺少连接而失败：如实报告「没验到」，
            // 不要写成 PASS（「跳过」与「通过」在这一层必须区分）。
            sb.append("   ⚠ 跳过：无法在无头环境构造测试玩家（").append(ex.getClass().getSimpleName())
                    .append(": ").append(ex.getMessage()).append("）\n");
            reason = "（未验）";
        }
        sb.append("   拒绝原因：").append(reason).append('\n');
    }

    /**
     * 在 (x, z) 附近找一块**脚下 3×3 同高**的平地，返回那一点。
     *
     * <p>为什么要找：本轮阻挡的验证前提是「**平坦地形**」（设计口径），而自验台的基准点
     * 落在一处**台地边缘**时会出现这种假失败 —— 棋子站在 y=63 的方块上，僵尸被摆到同一个 y
     * 却因为旁边没有地面而掉下去（实测：僵尸 y 从 60.8 一路沉到 59.1），
     * 于是「被击退后走回锚点」永远不成立，看着像阻挡坏了。**先把地面找平，再谈断言。**</p>
     *
     * <p>找不到就退回原点（并让调用方把它打出来）。</p>
     */
    private static BlockPos findFlatSpot(ServerLevel level, int x, int z, int fromY) {
        for (int ring = 0; ring <= 12; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    int cx = x + dx * 3;
                    int cz = z + dz * 3;
                    int y = groundYAt(level, cx, cz, fromY);
                    if (isFlatGround(level, cx, cz, y)) {
                        return new BlockPos(cx, y, cz);
                    }
                }
            }
        }
        return null;    // 找不到平地：调用方要**说清「这一条没验」**，不要拿坡地硬测
    }

    /**
     * 这一列周围 3×3 的地面高度是否都与中心一致，且**真的能站人**。
     *
     * <p>★ 后半条是必须的：{@code groundYAt} 把「非空气」都算地面，于是高草、花、雪层
     * 也会被当成地面返回，而生物会**穿过它们掉下去** —— 实测踩过：报告里写着
     * 「3×3 同高 = true」，僵尸却从 y=63 一路沉到 59，于是「被击退后走回锚点」
     * 永远不成立（看着像阻挡坏了，其实是测试台站在草丛上）。</p>
     */
    private static boolean isFlatGround(ServerLevel level, int cx, int cz, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (groundYAt(level, cx + dx, cz + dz, y + 2) != y) {
                    return false;
                }
                if (!level.getBlockState(new BlockPos(cx + dx, y - 1, cz + dz)).blocksMotion()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 推 N 轮「生物 tick + manager 心跳」（顺序与真实 tick 一致），返回观察到的<b>单 tick 最大位移</b>。
     *
     * <p>返回值是断言 1（没有瞬移）的判据：真实寻路一步不会超过 0.6 格，
     * 而上一版「每 tick setPos 到锚点」能一步跳 2 格 —— 这条断言就是冲着它写的。</p>
     */
    private static double driveWorld(ServerLevel level, List<? extends Mob> mobs, int ticks) {
        List<Vec3> last = new ArrayList<>();
        for (Mob m : mobs) {
            last.add(m.position());
        }
        double maxStep = 0.0D;
        for (int t = 0; t < ticks; t++) {
            for (int i = 0; i < mobs.size(); i++) {
                Mob m = mobs.get(i);
                if (m.isRemoved() || m.isDeadOrDying()) {
                    continue;
                }
                m.tick();          // 生物 AI + 寻路 + 移动（真实 tick 里就发生在这一步）
                double step = m.position().distanceTo(last.get(i));
                maxStep = Math.max(maxStep, step);
                last.set(i, m.position());
            }
            PawnCombatManager.onServerTick(level.getServer());   // 相当于 ServerTickEvent 的 END 阶段
        }
        return maxStep;
    }

    // ------------------------------------------------------------------
    // 场景 4.1b：实体障碍（碰撞箱）
    // ------------------------------------------------------------------

    /** 本场景最多推进多少 tick（不足会提前判负，故给足）。 */
    private static final int SOLID_TICKS = 300;

    /**
     * 实体障碍的自验（实测反馈「<b>碰撞箱没了，根本挡不了实体</b>」的回归测试）。
     *
     * <h3>验什么</h3>
     * <ol>
     *     <li><b>敌人钻不进棋子里面</b>：棋子的碰撞箱是硬障碍（{@code PixelUnit#canBeCollidedWith}），
     *         贴上去只能停在 0.6 格 = 两个 0.6 宽碰撞箱的半宽之和之外；</li>
     *     <li><b>没被挡住的还能过去</b>：棋子实心之后，原版寻路看不见实体
     *         （{@code PathNavigationRegion#getEntityCollisions} 返回空），
     *         所以第 N+1 个要么绕过（{@code PawnCombatManager#routeAround} 的侧步）、
     *         要么当场卡死。这一条就是判它到底会不会绕；</li>
     *     <li><b>被挡住的仍然透不过去</b>：白名单开着时名额内的那只被钉在锚点上。</li>
     * </ol>
     *
     * <h3>★ 为什么这里「生物自己的 AI」是自验台模拟的</h3>
     * <p>无头自验<b>不推进游戏刻</b>（只 {@code mob.tick()}），{@code level.getGameTime()} 是冻结的，
     * 而 {@code MeleeAttackGoal#canUse()} 有一道 {@code gameTime - lastCanUseCheck < 20} 的门 ——
     * 于是「生物自己去追目标」在自验台上<b>时有时无</b>（取决于跑这个场景时世界跑了多少刻），
     * 断言会变成看运气。这里改用「<b>导航空闲就重下目标点</b>」代表它自己的 goal
     * （真实 tick 里 goal 也是隔几 tick 重下一次寻路），而每次迭代的顺序与真实 tick
     * <b>完全一致</b>：它自己的寻路 → {@code mob.tick()}（AI + 移动）→ manager（END 阶段）。</p>
     *
     * <p>真·AI 的端到端验证交给 {@code livedemo}（按真实节奏跑，游戏刻在走）。</p>
     */
    private static void scenarioSolid(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- solid：棋子是实体障碍（碰撞箱）+ 放行者绕得过去\n");
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 190;
        // ★ 自建平台（见 buildPlatform）：本场景验的「碰撞 + 绕行」与地形无关，
        //   而**敌人往哪儿走完全由地形决定** —— 三次运行跑出过三种结局（贴到 0.61 / 0.61 / 全程 4.24 格），
        //   最后一次就是假失败：棋子没问题，是怪压根没走过来。
        Platform platform = buildPlatform(level, bx, bz, origin.getY());
        if (platform == null) {
            sb.append("   ⚠ 跳过：在基准点附近搭不出「平台四周都是空气」的场地（6 次尝试都撞到方块），\n")
                    .append("      本场景需要一块干净的水平面才能把地形变量排除掉。\n");
            return;
        }
        bx = platform.center.getX();
        int by = platform.center.getY();
        bz = platform.center.getZ();
        sb.append("   场地：自建平台 中心 ").append(fmt(platform.center))
                .append("（").append(platform.size()).append(" 格，跑完原样还回去）\n");

        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of());
        try {
            // ---- A) 白名单空（谁都不挡）：棋子只是「一堵墙」，敌人必须自己绕过去 ----
            //   ★ 2026-10 换夹具（拉怪上线后；**断言一条都没改弱**）：这一段验的是「棋子实心 +
            //     侧步绕行」，而 routeAround 的护栏里本来就有一条「**不碰把棋子当目标的怪**」——
            //     斗士（canAttack()=true）会把走进它攻击范围的过路僵尸拉怪锁成 target=棋子，
            //     于是侧步一次都不会下发、怪的路线也不再只由测试下发（同 block 场景 A 段的原因）。
            //     换成**阵法术师**：常态阻挡同样是 1（照样是**实心墙**，A 段验的碰撞与绕行逐字不变），
            //     而 canAttack()=false ⇒ 拉怪门关着，变量只剩「碰撞 + 绕行」。
            sb.append("-- A) 只当墙：白名单空（谁都不挡），僵尸从西边去东边的诱饵"
                            + "（棋子 = 阵法术师：常态阻挡 1 / canAttack=false ⇒ 拉怪门关着）\n");
            List<Entity> cleanup = new ArrayList<>();
            try {
                PixelUnit wall = spawnPawn(level, new BlockPos(bx, by, bz), UnitBranch.CASTER_PHALANX);
                wall.setFacing(PieceFacing.WEST);       // 面朝敌人来的方向（锚点在棋子西侧）
                makeTanky(wall, 400.0D);
                cleanup.add(wall);
                // 平台只有 9×5，所以「西边 8 格」缩到 3 格：够它走过来撞上棋子就行，
                // 短距离还能保证它的路一定在平台上（不会为了绕台阶跑出去）
                Mob bait = spawn(level, EntityType.COW, bx + 3, by, bz, "诱饵牛");
                makeTanky(bait, 5000.0D);
                cleanup.add(bait);
                Mob walker = spawnWithAi(level, EntityType.ZOMBIE, bx - 3, by, bz, "过路僵尸");
                clearOwnGoals(walker);      // 见 clearOwnGoals：不清掉随机游荡，这条断言就是看运气
                // ★ 血给足：本场景要观察 300 tick，中途死掉会让「有没有绕过去」变成无解（踩坑记录）
                makeTanky(walker, 2000.0D);
                // 给它一个「想去的地方」（东边的诱饵牛）：真实战场上这个目标是玩家/保护目标，
                // 关键是它<b>在棋子的另一侧</b>，所以它必须从棋子身上（或旁边）过去。
                walker.setTarget(bait);
                cleanup.add(walker);
                PawnCombatManager.resetDetourCount();

                List<WalkerTrack> tracks = walkPast(level, List.of(walker),
                        List.of(new BlockPos(bx + 3, by, bz)), wall, SOLID_TICKS);
                WalkerTrack w0 = tracks.get(0);
                sb.append("   ").append(w0.describe()).append('\n');
                check(sb, "★ 敌人在整段过程里没钻进棋子的碰撞箱（最小中心距 "
                                + r2(w0.minGap) + " 格，理论下限 0.60）",
                        w0.minGap >= 0.55D);
                check(sb, "★ 棋子确实挡住了它的去路（曾经贴到 0.8 格以内）", w0.minGap <= 0.8D);
                check(sb, "★ 它绕过去了（第 " + w0.crossedAt + " tick 越过棋子）", w0.crossed());
                check(sb, "★ 绕行确实是本 mod 下发的（侧步 "
                                + PawnCombatManager.debugDetourCount() + " 次）",
                        PawnCombatManager.debugDetourCount() > 0);
                check(sb, "★ 全程没有瞬移（单 tick 最大位移 " + r2(w0.maxStep) + " 格，阈值 0.6）",
                        w0.maxStep <= 0.6D);
            } finally {
                discardAll(cleanup);
            }

            // ---- B) 白名单开：名额内那只被钉住，名额外的仍然过得去 ----
            //   ★ 2026-10 换夹具（同 A 段的原因，**断言一条都没改弱**）：斗士会把走进攻击范围的
            //     怪拉怪锁成 target=棋子 ⇒ 名额外的第 N+1 个再也不会被判为「可以绕行」，
            //     「名额外的过得去」测的就不是放行语义了。阵法术师阻挡数同为 1、canAttack()=false。
            sb.append("-- B) 又挡又放：阵法术师（阻挡数 1 · canAttack=false ⇒ 拉怪门关着）+ 2 只僵尸都要去东边\n");
            BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
            List<Entity> cleanup2 = new ArrayList<>();
            try {
                PixelUnit fighter = spawnPawn(level, new BlockPos(bx, by, bz), UnitBranch.CASTER_PHALANX);
                fighter.setFacing(PieceFacing.WEST);    // 面朝敌人来的方向：锚点落在棋子西侧
                makeTanky(fighter, 400.0D);
                cleanup2.add(fighter);
                Mob bait2 = spawn(level, EntityType.COW, bx + 3, by, bz - 1, "诱饵牛2");
                makeTanky(bait2, 5000.0D);
                cleanup2.add(bait2);
                // ★ 被挡的那只**直接摆在棋子西侧、贴着碰撞箱**（0.62 = 两个半宽之和 + 一点余量）：
                //   ① 「贴着但不重叠」：既不会被挤开，也不会卡在棋子里面 ——
                //      棋子实心之后，摆进碰撞箱里的生物是出不来的（原版只拦「进不来」）；
                //   ② 位置与棋子同层、且都在平台上，跟「这一带地形平不平」彻底无关。
                Mob nearMob = spawnWithAi(level, EntityType.ZOMBIE, bx - 2, by, bz, "近僵尸");
                nearMob.setPos(fighter.getX() - 0.62D, fighter.getY(), fighter.getZ());
                // ★ 名额外那只走**隔壁车道**（z 差 1 格）：如果在同一车道，它会先被「被挡住的那只」
                //   的身体顶住 —— 生物之间没有硬碰撞，但互相推挤会形成拉锯（实测它卡在离棋子 1.12 格处），
                //   那是真实的「墙前堆积」，不是放行失效。**物理阻挡与绕行由 A 段验**，
                //   这一段只验容量语义：名额内的被钉住、名额外的**没被钉住、能走到对面**。
                Mob farMob = spawnWithAi(level, EntityType.ZOMBIE, bx - 4, by, bz - 1, "远僵尸");
                clearOwnGoals(nearMob);
                clearOwnGoals(farMob);
                // ★ 血给足：被挡住的那只会被棋子**一直打**，300 tick 窗口里够出手 6 次左右；
                //   200 血扛不住，它会在窗口末尾被打死，prune() 随即把它从名单里摘掉 ——
                //   第一版正是这样报出「名额内那只没被挡住」的假失败（踩坑记录）。
                makeTanky(nearMob, 5000.0D);
                makeTanky(farMob, 5000.0D);
                nearMob.setTarget(bait2);
                farMob.setTarget(bait2);
                cleanup2.add(nearMob);
                cleanup2.add(farMob);
                PawnCombatManager.resetDetourCount();

                List<WalkerTrack> tracks = walkPast(level, List.of(nearMob, farMob),
                        List.of(new BlockPos(bx + 3, by, bz), new BlockPos(bx + 3, by, bz - 1)),
                        fighter, SOLID_TICKS);
                WalkerTrack nearTrack = tracks.get(0);
                WalkerTrack farTrack = tracks.get(1);
                sb.append("   ").append(nearTrack.describe()).append('\n');
                sb.append("   ").append(farTrack.describe()).append('\n');
                List<PathfinderMob> held = PawnCombatManager.debugBlocked(fighter);
                String reason = BlockCandidates.rejectReason(nearMob);
                sb.append("   收尾时被挡名单=").append(held.size())
                        .append("（阻挡数 ").append(fighter.getBranch().blockCount()).append("）")
                        .append("；近那只血=").append(r1(nearMob.getHealth()))
                        .append('/').append(r1(nearMob.getMaxHealth()))
                        .append(" 已死=").append(nearMob.isDeadOrDying())
                        .append("；侧步 ").append(PawnCombatManager.debugDetourCount()).append(" 次\n");
                sb.append("   判据诊断（近那只）：与棋子同层=")
                        .append(PawnCombatManager.debugSameGroundLevel(fighter, nearMob))
                        .append("，白名单=").append(reason == null ? "准入" : reason)
                        .append("（距离 ").append(r2(Math.sqrt(nearMob.distanceToSqr(fighter)))).append(" 格）\n");
                check(sb, "★ 名额内那只被挡住了（整段里进过被挡名单）", nearTrack.everHeld);
                check(sb, "★ 它没钻进棋子（最小中心距 " + r2(nearTrack.minGap) + " 格）",
                        nearTrack.minGap >= 0.55D);
                check(sb, "★ 它也始终没越过棋子（是被挡住，不是被放过去）", !nearTrack.crossed());
                check(sb, "★ 名额外的第 N+1 个仍然过得去（第 " + farTrack.crossedAt + " tick 越过棋子）",
                        farTrack.crossed());
                check(sb, "★ 它也全程没钻进棋子的碰撞箱（最小中心距 " + r2(farTrack.minGap) + " 格）",
                        farTrack.minGap >= 0.55D);
            } finally {
                discardAll(cleanup2);
            }

            // ---- C) ★ 常态阻挡为 0 的棋子**没有碰撞箱**（设计口径）----
            solidNormalBlockGroup(sb, level, bx, by, bz);
        } finally {
            BlockCandidates.setWhitelistOverride(oldWhitelist);
            restorePlatform(level, platform);
            sb.append("   平台已原样还原（").append(platform.size()).append(" 格）\n");
        }
    }

    /**
     * <b>C 组</b>（★ 2026-10 新增）：<b>常态阻挡为 0 的棋子没有碰撞箱</b> ——
     * 设计口径「常态阻挡为 0 的棋子，去掉它的碰撞箱」（怪物应当能从它身上走过去）。
     *
     * <h3>为什么必须摆三颗棋子 + 一条对照</h3>
     * <p>「它穿过去了」有两种完全相反的解释：<b>①</b> 这颗棋子常态不挡人（本次要的那个）、
     * <b>②</b> 这只怪压根没动 / 没生成 / 走了别的路。所以在同一块平台上摆
     * <b>三颗棋子</b>（伏击客 = 常态阻挡 0、解放者 = 常态阻挡 0、斗士 = 常态阻挡 1）各占一条走道，
     * 每只僵尸走<b>同一条</b>「西 → 东」直路：前两条必须<b>穿过去</b>，第三条
     * （斗士）必须<b>被挡住</b> —— 它同时是判据的<b>对照组</b>（同一块地、同一个方向、
     * 同样的僵尸，只有棋子的分支不同）和「怪真的在动」的活证据。</p>
     *
     * <h3>夹具自证（照 block 场景 E 组 / 踩坑记录的规矩：先证明夹具成立，再谈被测对象）</h3>
     * <ul>
     *     <li>C1：三颗棋子真的生成了、分支没被随机掉（枚举名逐字比）、都活着，
     *         且 {@code canBeCollidedWith()} 与常态阻挡一致；</li>
     *     <li>C2：那只僵尸真的在动（单 tick 最大位移 &gt; 0）且真的从西往东移动过；</li>
     *     <li>C4：三条走道的结果必须是「两穿一挡」—— 少一条都不算验到。</li>
     * </ul>
     *
     * <h3>★ 两条刻意的实验设计（别当成漏测）</h3>
     * <ol>
     *     <li><b>攻击目标设成「它要穿过的那颗棋子」</b>，不是东边的诱饵：{@code routeAround}
     *         （侧步绕行）的护栏里本来就有一条「<b>不碰把棋子当目标的怪</b>」，于是绕行这条路被
     *         <b>显式关掉</b>。本组要测的是<b>碰撞箱</b>（怪直冲棋子能不能穿过去），
     *         而不是「本 mod 的侧步提示」—— 侧步本身由 A/B 段验。不关掉的话，
     *         僵尸会在贴近时被侧步带走，{@code minGap} 变成 0.4~0.7 之间的随机数，
     *         断言的就不是碰撞箱了。</li>
     *     <li><b>三只僵尸逐只跑</b>（三颗棋子同时在场）：同一 tick 只放一只怪走，
     *         否则相邻走道的僵尸会互相推挤（生物之间是 0.05 格的软推挤），
     *         「有没有从棋子身上过去」会被隔壁那只搅浑。</li>
     * </ol>
     */
    private static void solidNormalBlockGroup(StringBuilder sb, ServerLevel level, int bx, int by, int bz) {
        sb.append("-- C) ★ 常态阻挡为 0 ⇒ 没有碰撞箱（设计口径：怪应当能从它身上走过去）\n");

        // ===== C0 数据层：期望值**手写死**（不调被测实现算；同 block 场景 A 组的思路）=====
        check(sb, "C0 数据层① 伏击客 SPECIALIST_AMBUSHER.normalBlockCount() = "
                        + UnitBranch.SPECIALIST_AMBUSHER.normalBlockCount()
                        + "（表里「阻挡数」= " + UnitBranch.SPECIALIST_AMBUSHER.blockCount() + "）",
                UnitBranch.SPECIALIST_AMBUSHER.normalBlockCount() == 0);
        check(sb, "C0 数据层② 解放者 GUARD_LIBERATOR.normalBlockCount() = "
                        + UnitBranch.GUARD_LIBERATOR.normalBlockCount()
                        + "（表里「阻挡数」= " + UnitBranch.GUARD_LIBERATOR.blockCount()
                        + " ← 技能期/显示值；PRTS 特性原文「通常不攻击且阻挡数为0」）",
                UnitBranch.GUARD_LIBERATOR.normalBlockCount() == 0
                        && UnitBranch.GUARD_LIBERATOR.blockCount() == 3);
        check(sb, "C0 数据层③ 对照（都必须 > 0）：斗士 = "
                        + UnitBranch.GUARD_FIGHTER.normalBlockCount() + "、铁卫 = "
                        + UnitBranch.DEFENDER_PROTECTOR.normalBlockCount(),
                UnitBranch.GUARD_FIGHTER.normalBlockCount() > 0
                        && UnitBranch.DEFENDER_PROTECTOR.normalBlockCount() > 0);
        // 「表里 3 且没进覆盖表」的分支：常态阻挡必须逐字等于表里的 3（证明默认真的走表值）
        check(sb, "C0 数据层④ 默认走表值：铁卫 = " + UnitBranch.DEFENDER_PROTECTOR.normalBlockCount()
                        + "、哨戒铁卫 = " + UnitBranch.DEFENDER_SENTRY_PROTECTOR.normalBlockCount()
                        + "（表里都是 3，都没进覆盖表）",
                UnitBranch.DEFENDER_PROTECTOR.normalBlockCount() == 3
                        && UnitBranch.DEFENDER_SENTRY_PROTECTOR.normalBlockCount() == 3);
        int zeroNormal = 0;
        StringBuilder zeroNames = new StringBuilder();
        for (UnitBranch b : UnitBranch.values()) {
            if (b.normalBlockCount() == 0) {
                zeroNormal++;
                zeroNames.append(zeroNames.length() == 0 ? "" : "、").append(b.branchName());
            }
        }
        check(sb, "C0 数据层⑤ 72 个分支里常态阻挡为 0 的正好 2 个：" + zeroNames
                        + "（= branch_meta 覆盖表那 2 条）",
                zeroNormal == 2);

        // ===== C1~C4 行为层：三颗棋子（伏击客 / 解放者 / 斗士）各占一条走道 =====
        UnitBranch[] branches = {UnitBranch.SPECIALIST_AMBUSHER, UnitBranch.GUARD_LIBERATOR,
                UnitBranch.GUARD_FIGHTER};
        String[] labels = {"伏击客（常态阻挡 0）", "解放者（常态阻挡 0）", "斗士（常态阻挡 1 · 对照）"};
        boolean[] expectCross = {true, true, false};

        // 白名单：斗士那条走道要「真的去挡」（否则对照就只剩碰撞箱一个变量）；
        // 伏击客/解放者 常态阻挡 0 ⇒ 容量 0，它们连一个名额都不占（这一条与白名单无关）。
        BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
        sb.append("   白名单（本组）：").append(BlockCandidates.describe()).append('\n');

        List<Entity> cleanup = new ArrayList<>();
        List<PixelUnit> pawns = new ArrayList<>();
        try {
            for (int lane = 0; lane < 3; lane++) {
                int lz = bz + lane - 1;              // 三条走道：bz-1 / bz / bz+1
                PixelUnit pawn = spawnPawn(level, new BlockPos(bx, by, lz), branches[lane]);
                pawn.setFacing(PieceFacing.WEST);    // 面朝怪来的方向（占位锚点落在棋子西侧）
                makeTanky(pawn, 400.0D);
                pawns.add(pawn);
                cleanup.add(pawn);
                Mob bait = spawn(level, EntityType.COW, bx + 3, by, lz, "诱饵牛C" + (lane + 1));
                makeTanky(bait, 5000.0D);
                cleanup.add(bait);
            }

            // ---- C1 夹具：三颗棋子真的生成了、分支没被随机掉、碰撞开关与常态阻挡一致 ----
            boolean fixtureOk = true;
            for (int lane = 0; lane < 3; lane++) {
                PixelUnit pawn = pawns.get(lane);
                boolean branchOk = pawn.getBranch().key().equals(branches[lane].name());
                boolean switchOk = pawn.canBeCollidedWith() == (branches[lane].normalBlockCount() > 0);
                boolean ok = branchOk && pawn.isAlive() && switchOk;
                fixtureOk &= ok;
                check(sb, "C1 夹具：" + labels[lane] + " 真的生成了（分支=" + pawn.getBranch().key()
                                + "，活着=" + pawn.isAlive()
                                + "，常态阻挡=" + pawn.getBranch().normalBlockCount()
                                + "，canBeCollidedWith=" + pawn.canBeCollidedWith() + "）", ok);
            }
            if (!fixtureOk) {
                sb.append("   ⚠ 跳过 C2~C4：C1 夹具没成立（棋子没生成/分支被随机掉），"
                                + "那几条断言会变成假的\n");
                return;
            }

            // ---- C2/C3：逐条走道让一只僵尸从西往东走 ----
            List<WalkerTrack> results = new ArrayList<>();
            for (int lane = 0; lane < 3; lane++) {
                int lz = bz + lane - 1;
                PixelUnit pawn = pawns.get(lane);
                Mob walker = spawnWithAi(level, EntityType.ZOMBIE, bx - 3, by, lz, "过路僵尸C" + (lane + 1));
                clearOwnGoals(walker);        // 踩坑记录：不清掉随机游荡 goal，这条断言就是看运气
                makeTanky(walker, 2000.0D);   // 踩坑记录：中途死掉会让「有没有过去」变成无解
                cleanup.add(walker);
                walker.setTarget(pawn);       // ★ 见方法注释：这样关掉 routeAround 的侧步（只测碰撞箱）
                WalkerTrack tr = walkPast(level, List.of(walker),
                        List.of(new BlockPos(bx + 3, by, lz)), pawn, SOLID_TICKS).get(0);
                results.add(tr);
                sb.append("   [").append(labels[lane]).append("] ").append(tr.describe()).append('\n');

                // ---- C2 夹具：这只怪真的在动、真的从西往东移动过 ----
                check(sb, "C2 夹具（" + labels[lane] + "）：僵尸真的在动（单 tick 最大位移 "
                                + r2(tr.maxStep) + " 格 > 0，观察 " + tr.ticks + " tick）",
                        tr.maxStep > 0.0D && tr.ticks > 0);
                check(sb, "C2 夹具（" + labels[lane] + "）：它确实从西往东移动过（起点 x="
                                + r2(bx - 2.5D) + " → 收尾 x=" + r2(walker.getX()) + "）",
                        walker.getX() > bx - 2.5D);

                // ---- C3 被测：能不能从这颗棋子身上走过去 ----
                String evidence = expectCross[lane]
                        ? "（最小中心距 " + r2(tr.minGap) + " 格 < 0.60 = 真的从**它身上**过去了，不是绕开）"
                        : "（最小中心距 " + r2(tr.minGap) + " 格 ≥ 0.55 = 没钻进它的碰撞箱）";
                check(sb, "C3 ★ " + labels[lane] + "：常态阻挡=" + pawn.getBranch().normalBlockCount()
                                + "、canBeCollidedWith=" + pawn.canBeCollidedWith()
                                + " ⇒ 僵尸" + (expectCross[lane] ? "**穿过去**" : "**被挡住**")
                                + "（越过=" + tr.crossed()
                                + (tr.crossed() ? "，第 " + tr.crossedAt + " tick" : "") + "）" + evidence,
                        expectCross[lane]
                                ? (tr.crossed() && tr.minGap < 0.6D)
                                : (!tr.crossed() && tr.minGap >= 0.55D));
            }

            // ---- C4 对照断言：三条走道的结果必须「两穿一挡」----
            if (results.size() == 3) {
                boolean c0 = results.get(0).crossed();
                boolean c1 = results.get(1).crossed();
                boolean c2 = results.get(2).crossed();
                check(sb, "C4 ★ 对照：三只僵尸走同一条直路（西 → 东），伏击客=" + c0 + "、解放者=" + c1
                                + "、斗士=" + c2 + " —— 两只常态阻挡 0 的棋子被穿过，"
                                + "斗士把第三只挡在 " + r2(results.get(2).minGap) + " 格外",
                        c0 && c1 && !c2);
            }
        } finally {
            discardAll(cleanup);
            sb.append("   C 组清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /** 平台半宽 / 半深（格）：9×5 —— 放得下棋子、两侧各有 3~4 格跑动、还够 1 格出界的侧步。 */
    private static final int PLATFORM_HALF_X = 4;
    private static final int PLATFORM_HALF_Z = 2;

    /**
     * 把生物<b>自己的 goal 全清掉</b>，只留下「本场景每 tick 下发的寻路」。
     *
     * <h3>★ 为什么必须清（这是本场景第四次运行才抓到的真凶）</h3>
     * <p>僵尸自带走路 goal {@code WaterAvoidingRandomStrollGoal}（随机游荡）。而无头自验
     * <b>不推进游戏刻</b>，{@code MeleeAttackGoal#canUse()} 那道 {@code gameTime - lastCanUseCheck < 20}
     * 的门让它<b>时有时无</b>：门没过的时候，「追诱饵」这件事就交给<b>随机游荡</b>接管了 ——
     * 于是同一只怪、同一条直路，三次跑出三种结局：贴到 0.61 格 / 0.61 格 / 全程 4.24 格。
     * 前两次被当成「验到了」，第三次报 FAIL，看着像「碰撞时好时坏」，实际是
     * <b>测试台上那只怪自己在乱走</b>（踩坑记录）。</p>
     *
     * <p>清掉 goal 之后它的位移<b>只</b>由我们下发的 {@code moveTo} 决定 ——
     * 断言才是在测产品，而不是在测运气。寻路本身不是 goal（{@code Mob#serverAiStep} 里
     * 直接 tick {@code navigation}），所以清 goal 不影响 {@code moveTo} 生效。</p>
     */
    private static void clearOwnGoals(Mob mob) {
        mob.goalSelector.removeAllGoals(g -> true);
    }

    /** 自验用临时平台：中心格（= 站人的那一格）与「每格原来的方块」（还原用）。 */
    private static final class Platform {
        final BlockPos center;
        final java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> original;

        Platform(BlockPos center,
                 java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> original) {
            this.center = center;
            this.original = original;
        }

        int size() {
            return original.size();
        }
    }

    /**
     * 在 (cx, cz) 上方搭一块<b>悬空平台</b>，返回中心格（其 Y = 站人的那一格）与还原表。
     *
     * <h3>★ 为什么非要自己搭（第三次运行才想明白）</h3>
     * <p>本场景验的是「棋子是实体障碍」与「放行者绕得过去」，这两件事<b>与地形无关</b>；
     * 但「敌人往哪儿走」<b>完全由地形决定</b> —— 原版寻路只认方块，场地不是平的，
     * 它的 A* 就绕开台阶/坑。于是同一套断言三次跑出三种结局：两次「贴到 0.61 格」（验到了），
     * 一次「全程离 4.24 格、侧步 0 次」—— 那是<b>假失败</b>：棋子没问题，是怪压根没走过来。
     * 搭一块平的、悬空的台子，等于把这个变量从实验里删掉。</p>
     *
     * <h3>两条安全规矩（设计的存档不能被弄脏）</h3>
     * <ol>
     *     <li><b>只在空中搭</b>：从「本地最高地面 + 3」起，每次尝试再抬 3 格，
     *         直到平台那两层<b>全是空气</b>才动手 —— 绝不覆盖、也不破坏原有方块；</li>
     *     <li><b>原样还回去</b>：记下每格原来的方块状态，跑完按<b>原状态</b>写回
     *         （不是 {@code removeBlock} —— 那会把「本来就在那儿」的方块挖掉）。</li>
     * </ol>
     *
     * <p>搭不出来（6 次尝试都撞到方块）返回 {@code null}，由调用方<b>如实报跳过</b>：
     * 宁可说「没验」，也不要拿一个会被地形左右的结果当结论。</p>
     */
    @javax.annotation.Nullable
    private static Platform buildPlatform(ServerLevel level, int cx, int cz, int fromY) {
        int maxGround = fromY;
        for (int dx = -PLATFORM_HALF_X; dx <= PLATFORM_HALF_X; dx++) {
            for (int dz = -PLATFORM_HALF_Z; dz <= PLATFORM_HALF_Z; dz++) {
                maxGround = Math.max(maxGround, groundYAt(level, cx + dx, cz + dz, fromY));
            }
        }
        for (int attempt = 0; attempt < 6; attempt++) {
            int y = maxGround + 3 + attempt * 3;
            List<BlockPos> floor = new ArrayList<>();
            boolean clear = true;
            for (int dx = -PLATFORM_HALF_X; dx <= PLATFORM_HALF_X && clear; dx++) {
                for (int dz = -PLATFORM_HALF_Z; dz <= PLATFORM_HALF_Z; dz++) {
                    BlockPos under = new BlockPos(cx + dx, y - 1, cz + dz);
                    // 两层都必须是空气：y-1 放台面、y 站生物（撞到方块就抬高 3 格再试）
                    if (!level.getBlockState(under).isAir() || !level.getBlockState(under.above()).isAir()) {
                        clear = false;
                        break;
                    }
                    floor.add(under);
                }
            }
            if (!clear) {
                continue;
            }
            java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> original =
                    new java.util.LinkedHashMap<>();
            var slab = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            for (BlockPos p : floor) {
                original.put(p, level.getBlockState(p));
                level.setBlock(p, slab, 3);
            }
            return new Platform(new BlockPos(cx, y, cz), original);
        }
        return null;
    }

    /** 拆平台：按记录下来的<b>原方块状态</b>写回（不是 removeBlock，见 {@link #buildPlatform}）。 */
    private static void restorePlatform(ServerLevel level, Platform platform) {
        if (platform == null) {
            return;
        }
        for (var e : platform.original.entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), 3);
        }
    }

    /**
     * 推进世界若干 tick，一边推一边采样「过路者有没有钻进墙里 / 有没有越过去」。
     *
     * <p>每次迭代的顺序与真实 tick 一致：它自己的寻路（导航空闲时重下）→ {@code mob.tick()} →
     * manager（{@code ServerTickEvent} 的 END 阶段）。{@code goals} 是<b>逐只</b>的「它想去的地方」
     * —— B 段要让被放行的那只走<b>隔壁车道</b>（见那里的说明），所以不能共用一个目标点。</p>
     */
    private static List<WalkerTrack> walkPast(ServerLevel level, List<? extends Mob> walkers,
                                              List<BlockPos> goals, PixelUnit wall, int ticks) {
        List<WalkerTrack> tracks = new ArrayList<>();
        List<Vec3> last = new ArrayList<>();
        for (Mob m : walkers) {
            tracks.add(new WalkerTrack(m.getCustomName() == null ? "敌人" : m.getCustomName().getString()));
            last.add(m.position());
        }
        for (int t = 0; t < ticks; t++) {
            for (int i = 0; i < walkers.size(); i++) {
                Mob m = walkers.get(i);
                if (m.isRemoved() || m.isDeadOrDying()) {
                    continue;
                }
                // 「它自己的 goal」：这次寻路走完就再下一次（真实 tick 里 goal 也是这样重下的）
                if (m.getNavigation().isDone()) {
                    BlockPos g = goals.get(i);
                    m.getNavigation().moveTo(g.getX() + 0.5D, g.getY(), g.getZ() + 0.5D, 1.0D);
                }
                m.tick();
                tracks.get(i).maxStep = Math.max(tracks.get(i).maxStep, m.position().distanceTo(last.get(i)));
                last.set(i, m.position());
            }
            PawnCombatManager.onServerTick(level.getServer());   // 相当于 ServerTickEvent 的 END 阶段
            // ★ 「曾被挡住」必须逐 tick 记，**不能只看收尾那一次**：被挡住的那只会被棋子一直打，
            //   万一它在观察窗口末尾被打死，prune() 会把它从名单里摘掉，
            //   于是收尾时读到「名单 0 个」——断言失败，而真相是「它整段都被挡住了」（踩坑记录）。
            List<PathfinderMob> heldNow = PawnCombatManager.debugBlocked(wall);
            for (int i = 0; i < walkers.size(); i++) {
                Mob m = walkers.get(i);
                if (m.isRemoved() || m.isDeadOrDying()) {
                    continue;
                }
                WalkerTrack track = tracks.get(i);
                if (m instanceof PathfinderMob pm && heldNow.contains(pm)) {
                    track.everHeld = true;
                }
                track.minGap = Math.min(track.minGap, horizontalGap(m, wall));
                if (track.crossedAt < 0 && m.getX() > wall.getX() + 0.6D) {
                    track.crossedAt = t;
                }
                track.ticks = t + 1;
            }
        }
        return tracks;
    }

    /** 两只实体中心的<b>水平</b>距离（垂直差不管：本场景全在同一层）。 */
    private static double horizontalGap(Entity a, Entity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 一只过路者被观察到的量（见 {@link #walkPast}）。 */
    private static final class WalkerTrack {
        final String label;
        /** 与棋子中心的水平距离的最小值（理论下限 = 两个半宽之和 0.6）。 */
        double minGap = Double.MAX_VALUE;
        /** 单 tick 最大位移（「有没有瞬移」的判据）。 */
        double maxStep;
        /** 第几 tick 越过棋子所在格（-1 = 一直没过去）。 */
        int crossedAt = -1;
        /** 观察了多少 tick。 */
        int ticks;
        /** 曾经进过「被挡住」名单吗（★ 必须逐 tick 采样，见 {@link #scenarioSolid} 的说明）。 */
        boolean everHeld;

        WalkerTrack(String label) {
            this.label = label;
        }

        boolean crossed() {
            return this.crossedAt >= 0;
        }

        String describe() {
            return label + "：最小中心距=" + r2(minGap) + " 格，越过棋子="
                    + (crossed() ? ("第 " + crossedAt + " tick") : ("没有（观察了 " + ticks + " tick）"))
                    + "，曾进被挡名单=" + everHeld
                    + "，单 tick 最大位移=" + r2(maxStep) + " 格";
        }
    }

    // ------------------------------------------------------------------
    // 场景 4.2：垂直攻击判定（显式区间）
    // ------------------------------------------------------------------

    /**
     * 垂直判定的自验（设计口径）。
     *
     * <ul>
     *     <li>远程分支：<b>能</b>打到高 3 格的敌人（上下各 4 格，没有盲区）；</li>
     *     <li>只打地面的分支：<b>打不到</b>高 3 格的，<b>但能</b>打到低 1.5 格的。</li>
     * </ul>
     *
     * <p>★ 为什么这两个断言必须真的在游戏里跑：以前「远程没有垂直盲区」这条模板口径
     * 被<b>整格高的碰撞盒</b>隐含地压回上下各一格，读代码完全看不出来 ——
     * 只有把敌人真的摆到 +3 格上，才验得出这次改动是否落地。</p>
     */
    private static void scenarioVertical(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- vertical：垂直攻击区间（远程上下 4 格 / 对地 上 1 下 2）\n");
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 170;
        int by = groundYAt(level, bx, bz, origin.getY());

        // 只打地面的分支里，近战武器那些「只打被挡的」（attacksBlockedOnly），
        // 所以这里把阻挡白名单临时开成僵尸，否则它连一个候选都没有。
        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
        try {
            // ---- A) 远程（速射手，RANGED）：+3 格也能打 ----
            sb.append("-- A) 远程：速射手 vs 高 3 格的敌人\n");
            List<Entity> cleanup = new ArrayList<>();
            PixelUnit shooter = spawnPawnOnGround(level, bx, bz, by, UnitBranch.SNIPER_MARKSMAN);
            cleanup.add(shooter);
            try {
                Mob high = spawn(level, EntityType.ZOMBIE, bx, by, bz, "高3格");
                cleanup.add(high);
                high.setNoGravity(true);
                // 摆在棋子正前方那一格、抬高 3 格
                high.setPos(shooter.getX() + 1.5D, shooter.getY() + 3.0D, shooter.getZ());
                boolean selected = tickAndSelected(level, shooter, high);
                sb.append("   棋子 y=").append(r2(shooter.getY()))
                        .append(" 目标 y=").append(r2(high.getY()))
                        .append("（差 ").append(r2(high.getY() - shooter.getY())).append(" 格）\n");
                check(sb, "★ 远程分支能打到高 3 格的敌人", selected);
            } finally {
                discardAll(cleanup);
                sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
            }

            // ---- B) 只打地面（要塞，GROUND_ONLY + 投掷物 ⇒ 按范围索敌）：+3 打不到、-1.5 打得到 ----
            //  ★ 为什么不用铁卫：铁卫是「只打被挡的」那道墙，而被挡候选要求与棋子**同层**
            //    （容差 1.0 格）—— 低 1.5 格的靶子根本不进候选集，那条断言就永远为假。
            //    要塞是 GROUND_ONLY 里唯一「按攻击范围格子索敌」的近战位分支，才能验脚下的目标。
            sb.append("-- B) 对地：要塞 vs 高 3 格 / 低 1.5 格\n");
            List<Entity> cleanup2 = new ArrayList<>();
            PixelUnit guard = spawnPawnOnGround(level, bx + 20, bz, by, UnitBranch.DEFENDER_FORTRESS);
            cleanup2.add(guard);
            try {
                // 把两个靶子放进**它射程里的同一个格子**：范围 4-6 与自身格之间隔着两格空，
                // 所以不能用「正前方一格」这种想当然的位置 —— 直接问它自己的打击格。
                BlockPos cell = firstAttackCellWorld(guard);
                Mob high = spawn(level, EntityType.ZOMBIE, cell.getX(), cell.getY(), cell.getZ(), "高3格");
                Mob low = spawn(level, EntityType.ZOMBIE, cell.getX(), cell.getY(), cell.getZ(), "低1.5格");
                cleanup2.add(high);
                cleanup2.add(low);
                high.setNoGravity(true);
                low.setNoGravity(true);
                high.setPos(cell.getX() + 0.5D, guard.getY() + 3.0D, cell.getZ() + 0.5D);
                low.setPos(cell.getX() + 0.5D, guard.getY() - 1.5D, cell.getZ() + 0.5D);
                PawnCombatManager.onServerTick(level.getServer());
                List<LivingEntity> picked = PawnCombatManager.debugLastTargets(guard);
                boolean hitHigh = picked.stream().anyMatch(e -> e == high);
                boolean hitLow = picked.stream().anyMatch(e -> e == low);
                sb.append("   棋子 y=").append(r2(guard.getY()))
                        .append(" 打击格中心=").append(fmt(cell))
                        .append(" 选中 ").append(picked.size()).append(" 个")
                        .append("（高3格=").append(hitHigh).append(" 低1.5格=").append(hitLow).append("）\n");
                check(sb, "★ 对地分支打不到高 3 格的敌人", !hitHigh);
                check(sb, "★ 对地分支能打到低 1.5 格的敌人", hitLow);
            } finally {
                discardAll(cleanup2);
                sb.append("   清理生成物 ").append(cleanup2.size()).append(" 个\n");
            }

            // ---- C) 近战位**但对空**（2026-10 第四轮补，审计报告 N3）----
            //  旧口径「远程位才对空」把 5 个近战位分支判成打不到空中单位，
            //  而 PRTS《分支特性信息》对它们都写着「可对空」。
            sb.append("-- C) 近战位对空：领主（《分支特性信息》「可对空」）vs 高 3 格的敌人\n");
            check(sb, "★ 数据层：领主（近战位）判为对空", UnitBranch.GUARD_LORD.antiAir());
            check(sb, "★ 数据层：情报官 / 哨戒铁卫 / 巡空者 / 钩索师 也都判为对空",
                    UnitBranch.VANGUARD_AGENT.antiAir()
                            && UnitBranch.DEFENDER_SENTRY_PROTECTOR.antiAir()
                            && UnitBranch.SPECIALIST_SKYRANGER.antiAir()
                            && UnitBranch.SPECIALIST_HOOKMASTER.antiAir());
            check(sb, "数据层对照：斗士（近战位、原文没写可对空）仍**不**对空",
                    !UnitBranch.GUARD_FIGHTER.antiAir());
            int meleeAir = 0;
            for (UnitBranch b : UnitBranch.values()) {
                if (b.isMelee() && b.antiAir()) {
                    meleeAir++;
                }
            }
            check(sb, "★ 全枚举扫一遍：近战位但对空的分支正好 5 个（实际 " + meleeAir + "）",
                    meleeAir == 5);
            List<Entity> cleanup3 = new ArrayList<>();
            PixelUnit lord = spawnPawnOnGround(level, bx + 40, bz, by, UnitBranch.GUARD_LORD);
            cleanup3.add(lord);
            try {
                BlockPos cell3 = firstAttackCellWorld(lord);
                Mob airTarget = spawn(level, EntityType.ZOMBIE, cell3.getX(), cell3.getY(),
                        cell3.getZ(), "高3格");
                cleanup3.add(airTarget);
                airTarget.setNoGravity(true);
                airTarget.setPos(cell3.getX() + 0.5D, lord.getY() + 3.0D, cell3.getZ() + 0.5D);
                boolean selected3 = tickAndSelected(level, lord, airTarget);
                sb.append("   棋子 y=").append(r2(lord.getY()))
                        .append(" 目标 y=").append(r2(airTarget.getY()))
                        .append("（差 ").append(r2(airTarget.getY() - lord.getY())).append(" 格）\n");
                check(sb, "★ 近战位对空分支能打到高 3 格的敌人", selected3);
            } finally {
                discardAll(cleanup3);
                sb.append("   清理生成物 ").append(cleanup3.size()).append(" 个\n");
            }
        } finally {
            BlockCandidates.setWhitelistOverride(oldWhitelist);
        }
    }

    /** 推两次心跳后，这个棋子是否选中了指定目标（垂直场景用）。 */
    private static boolean tickAndSelected(ServerLevel level, PixelUnit pawn, LivingEntity target) {
        PawnCombatManager.onServerTick(level.getServer());
        PawnCombatManager.onServerTick(level.getServer());
        return PawnCombatManager.debugLastTargets(pawn).stream().anyMatch(e -> e == target);
    }

    /**
     * 取棋子某个**打击格**的世界坐标（垂直场景用）。
     *
     * <p>为什么不能想当然地摆「正前方一格」：各分支的 rangeId 形状差很远（要塞是 {@code 4-6}，
     * 与自身格之间隔着两格空），随手摆的位置很可能根本不在射程里 ——
     * 那会让「打不到/打得到」的断言变成在测「摆错地方」。直接问它自己的 {@code worldCells()}。</p>
     */
    private static BlockPos firstAttackCellWorld(PixelUnit pawn) {
        var cells = pawn.worldCells();
        if (cells.isEmpty()) {
            return pawn.blockPosition();
        }
        AttackRange.Cell c = cells.get(cells.size() / 2);      // 取中间那一格，避免取到自身格
        return pawn.blockPosition().offset(c.x(), 0, c.z());
    }

    // ------------------------------------------------------------------
    // 场景 4.3：技能位（技力 → 触发 → 激活 / 弹药）★ 2026-10 第四轮：设计文档 的 A/B/C 三组
    // ------------------------------------------------------------------

    /**
     * 技能位的自验 —— <b>设计文档 的 A（数据层）/ B（行为层）/ C（反例）三组 + D（只读入口）</b>。
     *
     * <h3>★ 断言独立性（本项目硬纪律）</h3>
     * <p>下面每一个期望值都是**从表 / PRTS 手算出来的**，没有一个是「先调实现函数、再拿它的返回值当期望」：
     * 212 条 / 72 分支 / 回复类型 173+28+8+2+1 / 持续类型 119+46+18+20+9 / 初动 ≥ 消耗 12 条 /
     * 弹药 18 条（散射手技能3 = 32 发 / 每击 2 发，怪杰技能3 = 50 发 / 每击 5 发）、
     * 重射手技能2 = 4.2 秒 → <b>84 tick</b>（4.2 × 20 四舍五入）、尖兵技能1 的 8/4、
     * 「自动回复每 20 tick +1」。拿 {@code Skills.durationTicks()} 之类的实现函数先算一遍再比对，
     * 等于让实现自己给自己判卷：实现错了断言会跟着一起错。</p>
     *
     * <h3>计时为什么用本 mod 的节拍</h3>
     * <p>{@code Skills.onServerTick} 由本场景<b>手动推进</b> —— 自验台不推进游戏刻
     * （{@code getGameTime()} 冻住，踩坑记录），而且本场景跑在 {@code ServerStartedEvent} 里，
     * 服务端 tick 循环还没开始。所以「每 20 tick +1」这类断言是**确定的**，不是抽奖。</p>
     *
     * <h3>没验到的会显式打印</h3>
     * <p>任何跳过或近似（例如「弹药 ≤ 0 之后到底在哪一次出手退出」有两种读法）都在报告里写清
     * 原因与算法，不许长得像通过（踩坑记录）。</p>
     */
    private static void scenarioSkills(StringBuilder sb, ServerLevel level, BlockPos origin) {
        sb.append("-- skills：技能位（技力 → 触发 → 激活 / 弹药）—— 设计文档 的 A/B/C 三组 + 只读入口 D\n");
        int bx = origin.getX() + 8;
        int bz = origin.getZ() + 190;
        int by = groundYAt(level, bx, bz, origin.getY());

        List<Entity> cleanup = new ArrayList<>();
        try {
            Skills.resetDebugCounters();
            skillsDataGroup(sb);
            skillsBehaviorGroup(sb, level, bx, bz, by, cleanup);
            skillsCounterGroup(sb, level, bx, bz, by, cleanup);
            skillsReadOnlyGroup(sb, level, bx, bz, by, cleanup);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * <b>A 数据层</b>：只读生成物里的 72 个分支 × 3 条技能，把 设计文档 的实测数字逐条钉住。
     *
     * <p>期望值是**表里的数**（写死在这里），不是「读一遍生成物再把读到的值当期望」——
     * 后者在生成链读错列时会一起错（同 踩坑记录「来源不独立」）。</p>
     */
    private static void skillsDataGroup(StringBuilder sb) {
        sb.append("   [A] 数据层（期望值来自表 / 设计文档）\n");

        int branches = UnitBranch.values().length;
        int present = 0;
        int ammoKinds = 0;
        Map<String, Integer> byRecovery = new TreeMap<>();
        Map<UnitBranch.RecoveryKind, Integer> byKind = new EnumMap<>(UnitBranch.RecoveryKind.class);
        Map<UnitBranch.DurationKind, Integer> byDuration =
                new EnumMap<>(UnitBranch.DurationKind.class);
        List<String> initGeCost = new ArrayList<>();
        List<String> nonIntSeconds = new ArrayList<>();
        for (UnitBranch b : UnitBranch.values()) {
            for (UnitBranch.Skill s : b.skills()) {
                if (!s.present()) {
                    continue;                 // 2 技能分支的第 3 条是补位空条目
                }
                present++;
                byRecovery.merge(s.recovery(), 1, Integer::sum);
                byKind.merge(s.recoveryKind(), 1, Integer::sum);
                byDuration.merge(s.durationKind(), 1, Integer::sum);
                if (s.cost() > 0 && s.initialSp() >= s.cost()) {
                    // 消耗 0 的那 8 条是「初始触发」的被动技能：不进技力循环，不在 12 条里
                    initGeCost.add(b.branchName() + s.slot() + "(" + s.initialSp() + "/" + s.cost() + ")");
                }
                if (s.durationKind() == UnitBranch.DurationKind.AMMO) {
                    ammoKinds++;
                }
                if (Math.abs(s.durationSeconds() - Math.round(s.durationSeconds())) > 1.0E-9D) {
                    nonIntSeconds.add(b.branchName() + s.slot() + " = " + s.durationSeconds()
                            + " 秒 → " + Math.round(s.durationSeconds() * 20.0D) + " tick");
                }
            }
        }
        check(sb, "A1 技能条目 = 212 条（设计文档：68 分支 × 3 + 4 分支 × 2）", present == 212);
        check(sb, "A2 分支数 = 72", branches == 72);

        UnitBranch[] twoSkillBranches = {UnitBranch.GUARD_MERCENARY, UnitBranch.GUARD_PRIMAL,
                UnitBranch.SNIPER_SKYBREAKER, UnitBranch.SUPPORTER_SUPPORTIVE_RANGER};
        StringBuilder two = new StringBuilder();
        boolean twoOk = true;
        for (UnitBranch b : twoSkillBranches) {
            int n = 0;
            for (UnitBranch.Skill s : b.skills()) {
                if (s.present()) {
                    n++;
                }
            }
            // 第 3 条必须是**补位空条目**（present() == false），否则槽位校验就没依据
            boolean pad = !b.skill(2).present();
            boolean count = Skills.skillCountOf(new BuiltinBranch(b)) == 2;
            twoOk = twoOk && n == 2 && pad && count;
            two.append(b.branchName()).append('(').append(n).append("条) ");
        }
        check(sb, "A3 ★ 只有 2 条技能的分支 4 个（近卫·佣兵 / 近卫·本源近卫 / 狙击·裂空炮手 / "
                + "辅助·游击手）：" + two.toString().trim(), twoOk);

        check(sb, "A4 回复类型（表里原值）= 173 自动 / 28 攻击 / 8 初始触发 / 2 受击 / 1 被动 → 实际 "
                + byRecovery,
                byRecovery.getOrDefault("自动回复", 0) == 173
                        && byRecovery.getOrDefault("攻击回复", 0) == 28
                        && byRecovery.getOrDefault("初始触发", 0) == 8
                        && byRecovery.getOrDefault("受击回复", 0) == 2
                        && byRecovery.getOrDefault("被动", 0) == 1);
        check(sb, "A5 回复类型（语义档）= AUTO 173 / ON_ATTACK 28 / ON_HURT 2 / PASSIVE 9 "
                + "（初始触发 8 + 被动 1）→ 实际 " + byKind,
                byKind.getOrDefault(UnitBranch.RecoveryKind.AUTO, 0) == 173
                        && byKind.getOrDefault(UnitBranch.RecoveryKind.ON_ATTACK, 0) == 28
                        && byKind.getOrDefault(UnitBranch.RecoveryKind.ON_HURT, 0) == 2
                        && byKind.getOrDefault(UnitBranch.RecoveryKind.PASSIVE, 0) == 9);
        check(sb, "A6 持续类型 = TIMED 119（116 有限 + 3 可主动关闭）/ INSTANT 46 / AMMO 18 / "
                + "INFINITE 20（14 永续 + 6 永续可关闭）/ PASSIVE 9 → 实际 " + byDuration,
                byDuration.getOrDefault(UnitBranch.DurationKind.TIMED, 0) == 119
                        && byDuration.getOrDefault(UnitBranch.DurationKind.INSTANT, 0) == 46
                        && byDuration.getOrDefault(UnitBranch.DurationKind.AMMO, 0) == 18
                        && byDuration.getOrDefault(UnitBranch.DurationKind.INFINITE, 0) == 20
                        && byDuration.getOrDefault(UnitBranch.DurationKind.PASSIVE, 0) == 9);
        check(sb, "A7 ★ 「初动 ≥ 消耗」（消耗 > 0）= 12 条：" + String.join("、", initGeCost),
                initGeCost.size() == 12);

        check(sb, "A8 点名：尖兵技能1 = 初动 8 / 消耗 4（≥ ⇒ 落地即可放）",
                UnitBranch.VANGUARD_PIONEER.skill(0).initialSp() == 8
                        && UnitBranch.VANGUARD_PIONEER.skill(0).cost() == 4);
        check(sb, "A8 点名：情报官技能1 = 3 / 3",
                UnitBranch.VANGUARD_AGENT.skill(0).initialSp() == 3
                        && UnitBranch.VANGUARD_AGENT.skill(0).cost() == 3);
        check(sb, "A8 点名：重射手技能3 = 30 / 25",
                UnitBranch.SNIPER_HEAVYSHOOTER.skill(2).initialSp() == 30
                        && UnitBranch.SNIPER_HEAVYSHOOTER.skill(2).cost() == 25);
        check(sb, "A8 点名：巫役技能1 = 2 / 2",
                UnitBranch.SUPPORTER_RITUALIST.skill(0).initialSp() == 2
                        && UnitBranch.SUPPORTER_RITUALIST.skill(0).cost() == 2);

        UnitBranch.Skill spread = UnitBranch.SNIPER_SPREADSHOOTER.skill(2);
        UnitBranch.Skill geek = UnitBranch.SPECIALIST_GEEK.skill(2);
        check(sb, "A9 弹药类 = 18 条", ammoKinds == 18);
        check(sb, "A9 点名：散射手技能3 = 32 发 / 每击 2 发（原文「攻击装有32发弹药，"
                        + "每次攻击消耗2发」）→ 实际 " + spread.ammo() + " 发 / 每击 "
                        + spread.ammoPerAttack() + " 发",
                spread.ammo() == 32 && spread.ammoPerAttack() == 2
                        && spread.durationKind() == UnitBranch.DurationKind.AMMO);
        check(sb, "A9 点名：怪杰技能3 = 50 发 / 每击 5 发（原文「攻击装有50发弹药，"
                        + "每次攻击消耗5发」）→ 实际 " + geek.ammo() + " 发 / 每击 "
                        + geek.ammoPerAttack() + " 发",
                geek.ammo() == 50 && geek.ammoPerAttack() == 5
                        && geek.durationKind() == UnitBranch.DurationKind.AMMO);

        // ★ 非整数秒只有一条（尾条 2）：4.2 秒 —— 它是 durationSeconds 用 double 的唯一理由。
        //   84 = 4.2 × 20 四舍五入（手算），不是调 durationTicks() 得来的。
        UnitBranch.Skill fly = UnitBranch.SNIPER_HEAVYSHOOTER.skill(1);
        check(sb, "A10 ★ 非整数秒只有 1 条：重射手技能2「飞翔瞪射」= 4.2 秒 → 84 tick（4.2 × 20）"
                        + " → 实际 " + nonIntSeconds,
                nonIntSeconds.size() == 1
                        && Math.abs(fly.durationSeconds() - 4.2D) < 1.0E-9D
                        && fly.durationTicks() == 84);
    }

    /**
     * <b>B 行为层</b>：用真分支、真状态机跑技力 → 触发 → 激活 / 弹药。
     *
     * <p>夹具之间<b>各自一格场地</b>（相隔 8 格，出手范围都够不着），每个用例开始前
     * {@code setSlot(..., 0/满)} + {@code resetDebugCounters()} 归一化，避免上一个用例的状态
     * 串进下一个（治疗场景第一版就是被夹具串场误报成 8 条 FAIL，踩坑记录）。</p>
     */
    private static void skillsBehaviorGroup(StringBuilder sb, ServerLevel level, int bx, int bz, int by,
                                            List<Entity> cleanup) {
        sb.append("   [B] 行为层（手动心跳推进技能状态机；**不用 getGameTime()**，踩坑记录）\n");

        // ---- B1/B2 部署：默认装技能 1 + 技力 = clamp(初动, 0, 消耗) ----
        PixelUnit pioneer = spawnPawnOnGround(level, bx + 40, bz, by, UnitBranch.VANGUARD_PIONEER);
        cleanup.add(pioneer);
        PixelUnit heavy = spawnPawnOnGround(level, bx, bz, by, UnitBranch.SNIPER_HEAVYSHOOTER);
        cleanup.add(heavy);
        check(sb, "B2 ★ 部署：槽位 0 → 自动装**技能 1**（设计口径「棋子放下自动装技能 1」）→ 尖兵 "
                        + pioneer.getSkillSlot() + " / 重射手 " + heavy.getSkillSlot(),
                pioneer.getSkillSlot() == 1 && heavy.getSkillSlot() == 1);
        check(sb, "B1 ★ 部署：技力 = clamp(初动, 0, 消耗) —— 尖兵技能1 min(8,4) = **4**，实际 "
                        + pioneer.getSkillPoints(),
                pioneer.getSkillPoints() == 4);
        check(sb, "B1 部署：重射手技能1 min(14,4) = **4**，实际 " + heavy.getSkillPoints(),
                heavy.getSkillPoints() == 4);

        // ---- B3 「初动 ≥ 消耗」的 12 条：拿两个真分支验「落地第一 tick 就放」 ----
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B3 ★ 初动 ≥ 消耗（12 条之一）：尖兵技能1（8/4）落地**第一 tick** 就触发一次"
                        + "（castCount = " + Skills.debugCastCount(pioneer) + "）",
                Skills.debugCastCount(pioneer) == 1);
        check(sb, "B3 触发后技力扣满消耗 → 0（实际 " + pioneer.getSkillPoints() + "）",
                pioneer.getSkillPoints() == 0);

        PixelUnit hunter = spawnPawnOnGround(level, bx + 16, bz, by, UnitBranch.SNIPER_HUNTER);
        cleanup.add(hunter);
        check(sb, "B1 部署：猎手技能1（10/10）技力 = **10**，实际 " + hunter.getSkillPoints(),
                hunter.getSkillPoints() == 10);
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B3 ★ 初动 ≥ 消耗（12 条之一）：猎手技能1（10/10）落地第一 tick 就触发"
                        + "（castCount = " + Skills.debugCastCount(hunter) + "，技力 = "
                        + hunter.getSkillPoints() + "）",
                Skills.debugCastCount(hunter) == 1 && hunter.getSkillPoints() == 0);

        // ---- B4 自动回复：每 20 tick 恰好 +1（连测 3 次）----
        //   重射手技能2「飞翔瞪射」= 自动回复 / 消耗 20 ⇒ 60 tick 内最多涨到 3，不会触发
        Skills.setSlot(heavy, 2, 0);
        Skills.resetDebugCounters();
        StringBuilder auto = new StringBuilder();
        boolean autoOk = true;
        for (int window = 1; window <= 3; window++) {
            for (int t = 0; t < 20; t++) {
                Skills.onServerTick(level.getServer());
            }
            auto.append("第 ").append(window).append(" 个 20 tick → ").append(heavy.getSkillPoints())
                    .append(" 点；");
            autoOk = autoOk && heavy.getSkillPoints() == window;
        }
        check(sb, "B4 ★ 自动回复：每 20 tick（= 1 秒）恰好 +1，连测 3 次（" + auto.toString().trim() + "）",
                autoOk);

        // ---- B5 上限夹取：技力永不 > 消耗 ----
        heavy.setSkillPoints(999);
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B5 ★ 上限夹取：写 999 之后一个 tick 内夹到消耗（20）并触发，技力回到 "
                        + heavy.getSkillPoints() + "（≤ 20）",
                heavy.getSkillPoints() <= 20 && Skills.debugCastCount(heavy) == 1);

        // ---- B6 满即放：到消耗那一 tick 触发 1 次、技力归 0 ----
        Skills.setSlot(heavy, 2, 20);
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B6 ★ 满即放：技力到消耗（20）那一 tick 触发 1 次（castCount = "
                        + Skills.debugCastCount(heavy) + "）、技力归 " + heavy.getSkillPoints(),
                Skills.debugCastCount(heavy) == 1 && heavy.getSkillPoints() == 0);

        // ---- B8 有限持续：剩余 == 秒 × 20 = 4.2 × 20 = 84，逐 tick −1，归 0 结束 ----
        check(sb, "B8 ★ 有限持续：触发时激活剩余 == 秒 × 20 = 4.2 × 20 = **84** tick，实际 "
                        + heavy.getSkillActiveTicks(),
                heavy.getSkillActiveTicks() == 84);
        Skills.onServerTick(level.getServer());
        check(sb, "B8 逐 tick −1：84 → " + heavy.getSkillActiveTicks(),
                heavy.getSkillActiveTicks() == 83);

        // ---- B7 阻回：激活期间一律不回技力 ----
        for (int t = 0; t < 40; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "B7 ★ 阻回：激活期间跑 40 tick，技力仍是 0（实际 " + heavy.getSkillPoints()
                        + "）；激活剩余 " + heavy.getSkillActiveTicks() + " tick（还没结束）",
                heavy.getSkillPoints() == 0 && heavy.getSkillActiveTicks() == 84 - 1 - 40);
        for (int t = 0; t < 43; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "B8 ★ 有限持续：剩余归 0 就结束（激活 = " + Skills.isActive(heavy)
                        + "，结束原因 = " + Skills.endReasonOf(heavy).displayName() + "）",
                !Skills.isActive(heavy) && Skills.endReasonOf(heavy) == Skills.EndReason.EXPIRED);

        // ---- B9 永续：跑 200 tick 仍未结束 ----
        Skills.setSlot(hunter, 2, 16);
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B9 ★ 永续：触发后进激活期（哨兵 " + PixelUnit.SKILL_FOREVER_TICKS + "），实际 "
                        + hunter.getSkillActiveTicks(),
                hunter.getSkillActiveTicks() == PixelUnit.SKILL_FOREVER_TICKS);
        for (int t = 0; t < 200; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "B9 ★ 永续：跑 200 tick 仍未结束（激活 = " + Skills.isActive(hunter)
                        + "，技力 = " + hunter.getSkillPoints() + "）",
                Skills.isActive(hunter) && hunter.getSkillPoints() == 0);
        Skills.setSlot(hunter, 2, 0);         // 收尾：关掉这个永续窗口，别影响后面的用例

        // ---- B10 瞬发：触发后未激活、持续秒 = 0 ----
        PixelUnit gunner = spawnPawnOnGround(level, bx + 8, bz, by, UnitBranch.SNIPER_ARTILLERYMAN);
        cleanup.add(gunner);
        Skills.setSlot(gunner, 2, 7);          // 炮手技能2「你须愧悔」：攻击回复 / 瞬发 / 消耗 7
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B10 ★ 瞬发：触发后**不进激活期**（激活剩余 = " + gunner.getSkillActiveTicks()
                        + " = 未激活），结束原因 = " + Skills.endReasonOf(gunner).displayName(),
                gunner.getSkillActiveTicks() == PixelUnit.SKILL_NOT_ACTIVE
                        && Skills.endReasonOf(gunner) == Skills.EndReason.INSTANT);
        check(sb, "B10 瞬发：表里这一条的持续秒 = 0（设计文档：瞬发 46 条的持续时间列全空）",
                UnitBranch.SNIPER_ARTILLERYMAN.skill(1).durationSeconds() == 0.0D);

        // ---- B11 弹药：真实出手 −M、打空之后退出（含「不足也照扣」）----
        PixelUnit spreader = spawnPawnOnGround(level, bx + 72, bz, by, UnitBranch.SNIPER_SPREADSHOOTER);
        cleanup.add(spreader);
        Skills.setSlot(spreader, 3, 55);       // 散射手技能3：自动回复 / 弹药 / 消耗 55 / 32 发 / 每击 2 发
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "B11 ★ 弹药：触发时装满 **32** 发（表里「攻击装有32发弹药」），实际 "
                        + spreader.getSkillAmmo() + " 发",
                spreader.getSkillAmmo() == 32);
        BlockPos spreadCell = firstAttackCellWorld(spreader);
        Mob spreadVictim = spawn(level, EntityType.ZOMBIE, spreadCell.getX(), spreadCell.getY(),
                spreadCell.getZ(), "弹药靶");
        makeTanky(spreadVictim, 500.0D);
        cleanup.add(spreadVictim);
        PawnCombatManager.onServerTick(level.getServer());
        boolean spreadShot = !PawnCombatManager.debugLastTargets(spreader).isEmpty();
        check(sb, "B11 ★ 弹药：**真实出手**一次扣 **2** 发（32 → " + spreader.getSkillAmmo()
                        + "；出手证据 = 本 tick 选中 " + PawnCombatManager.debugLastTargets(spreader).size()
                        + " 个目标）",
                spreadShot && spreader.getSkillAmmo() == 30);
        //   剩下的 14 次出手用**同一个挂钩**（Skills.onAttack）直接推：它是唯一入口，
        //   真实出手那一条已经证明接线通了；这里只验算术，所以用确定的调用次数而不是等冷却。
        for (int i = 0; i < 14; i++) {
            Skills.onAttack(spreader);
        }
        check(sb, "B11 弹药：14 次出手各扣 2 发（30 → " + spreader.getSkillAmmo() + " 发），仍未退出",
                spreader.getSkillAmmo() == 2 && Skills.isActive(spreader));
        Skills.onAttack(spreader);
        check(sb, "B11 ★ 弹药：再出手一次（2 − 2 = 0）⇒ **这一次出手之后**退出技能（激活 = "
                        + Skills.isActive(spreader) + "，结束原因 = "
                        + Skills.endReasonOf(spreader).displayName() + "）",
                !Skills.isActive(spreader) && Skills.endReasonOf(spreader) == Skills.EndReason.AMMO_OUT);
        sb.append("   ～ 口径说明：这是 设计文档 / 场景口径「出手时弹量 ≤ 0 ⇒ **该次出手后**退出技能」"
                + "（先扣、扣完判），所以 32 发 / 每击 2 发在第 **16** 次出手后退出；"
                + "PRTS 原句「下次普通攻击时弹药不高于0则退出」若按字面读是第 17 次出手之后 —— "
                + "本轮取前者（不需要为「弹药已打光」再留一次哑火出手），差异写在这里，不静默。\n");
        // 不足一次消耗：**必定消耗**（PRTS「必定消耗相应数额…可能出现负数弹药」）
        Skills.setSlot(spreader, 3, 55);
        Skills.onServerTick(level.getServer());
        spreader.setSkillAmmo(1);              // 夹具：只剩 1 发，而每击要消耗 2 发
        Skills.onAttack(spreader);
        check(sb, "B11 ★ 弹药：只剩 1 发而每击要 2 发时**照扣**（不拒绝消耗）并退出技能"
                        + "（激活 = " + Skills.isActive(spreader) + "，弹药 = " + spreader.getSkillAmmo() + "）",
                !Skills.isActive(spreader) && spreader.getSkillAmmo() == PixelUnit.SKILL_NO_AMMO);
        sb.append("   ～ 说明：中间值 −1 会被弹药字段的下界夹到哨兵 "
                + PixelUnit.SKILL_NO_AMMO + "（「不适用」），所以「负弹药」在本 mod 里**观察不到**；"
                + "能观察到的是「不足也照扣、并退出技能」。\n");

        // ---- B12 被动(常驻生效)：不消耗技力、永不触发 ----
        PixelUnit duelist = spawnPawnOnGround(level, bx + 24, bz, by, UnitBranch.DEFENDER_DUELIST);
        cleanup.add(duelist);
        Skills.setSlot(duelist, 1, 0);         // 决战者技能1「轻型挂斧」：初始触发 / 被动 / 消耗 0
        Skills.resetDebugCounters();
        for (int t = 0; t < 400; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "B12 ★ 被动(常驻生效)：跑 400 tick 一次都不触发（castCount = "
                        + Skills.debugCastCount(duelist) + "）、技力恒 " + duelist.getSkillPoints(),
                Skills.debugCastCount(duelist) == 0 && duelist.getSkillPoints() == 0);

        // ---- B14 受击回复：只有挨打才 +1（心跳不算）----
        PixelUnit tank = spawnPawnOnGround(level, bx + 32, bz, by,
                UnitBranch.DEFENDER_ARTS_PROTECTOR);
        cleanup.add(tank);
        Skills.setSlot(tank, 1, 0);            // 驭法铁卫技能1「恶业苦果」：受击回复 / 永续 / 消耗 15
        Skills.resetDebugCounters();
        for (int t = 0; t < 40; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "B14 受击回复：光跑心跳 40 tick **不自涨**（技力 = " + tank.getSkillPoints() + "）",
                tank.getSkillPoints() == 0);
        boolean wounded = tank.hurt(level.damageSources().generic(), 1.0F);
        check(sb, "B14 ★ 受击回复：**挨一次打就 +1**（hurt 返回 " + wounded + "，技力 = "
                        + tank.getSkillPoints() + "）",
                wounded && tank.getSkillPoints() == 1);
        for (int t = 0; t < 40; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "B14 受击回复：再跑 40 tick 心跳仍是 1（只有挨打才涨）",
                tank.getSkillPoints() == 1);

        // ---- B13 攻击回复：只有出手才 +1；**医疗分支的治疗出手也算一次出手** ----
        PixelUnit binder = spawnPawnOnGround(level, bx + 64, bz, by,
                UnitBranch.SUPPORTER_DECEL_BINDER);
        cleanup.add(binder);
        Skills.setSlot(binder, 2, 0);          // 凝滞师技能2「星束引力」：攻击回复 / 瞬发 / 消耗 3
        BlockPos bindCell = firstAttackCellWorld(binder);
        Mob bindVictim = spawn(level, EntityType.ZOMBIE, bindCell.getX(), bindCell.getY(),
                bindCell.getZ(), "出手靶");
        makeTanky(bindVictim, 500.0D);
        cleanup.add(bindVictim);
        Skills.resetDebugCounters();
        PawnCombatManager.onServerTick(level.getServer());
        int bindTargets = PawnCombatManager.debugLastTargets(binder).size();
        check(sb, "B13 ★ 攻击回复：**出手一次 +1**（本 tick 真的出手了，选中 " + bindTargets
                        + " 个目标；技力 = " + binder.getSkillPoints() + "）",
                bindTargets > 0 && binder.getSkillPoints() == 1);
        for (int t = 0; t < 5; t++) {
            PawnCombatManager.onServerTick(level.getServer());
        }
        check(sb, "B13 ★ 攻击回复：冷却期内的 5 次心跳**没有**再加技力（仍是 "
                        + binder.getSkillPoints() + "）",
                binder.getSkillPoints() == 1);

        PixelUnit medic = spawnPawnOnGround(level, bx + 56, bz, by, UnitBranch.MEDIC_CHAIN);
        cleanup.add(medic);
        Skills.setSlot(medic, 1, 0);           // 链愈师技能1「策略：超压链接」：攻击回复 / 瞬发 / 消耗 2
        List<PixelUnit> woundedAllies = placeAlliesInRange(level, cleanup, medic, 1, 5.0F);
        Skills.resetDebugCounters();
        PawnCombatManager.onServerTick(level.getServer());
        int medicTargets = PawnCombatManager.debugLastTargets(medic).size();
        check(sb, "B13 ★ 医疗分支的**治疗出手**也算一次出手（设计文档）：链愈师技能1（攻击回复 / 消耗 2）"
                        + "给伤员发治疗弹出技力（选中 " + medicTargets + " 名友方，技力 = "
                        + medic.getSkillPoints() + "）",
                medicTargets > 0 && medic.getSkillPoints() == 1);
        check(sb, "B13 治疗出手确实是治疗（治疗弹在飞，不是伤害）",
                !woundedAllies.isEmpty() && !projectilesOwnedBy(level, medic).isEmpty());

        // ---- B15 2 技能分支：槽位 3 被明确拒绝（不静默夹取）----
        PixelUnit mercenary = spawnPawnOnGround(level, bx + 48, bz, by, UnitBranch.GUARD_MERCENARY);
        cleanup.add(mercenary);
        String why3 = Skills.slotRejectReason(mercenary.getBranch(), 3);
        check(sb, "B15 ★ 2 技能分支（佣兵）装 3 号技能被**明确拒绝**（理由：" + why3 + "）",
                why3 != null && !why3.isEmpty());
        check(sb, "B15 越界槽位 9 被拒绝",
                Skills.slotRejectReason(mercenary.getBranch(), 9) != null);
        check(sb, "B15 槽位 0 = 卸下技能（合法）",
                Skills.slotRejectReason(mercenary.getBranch(), 0) == null);
        int slotBefore = mercenary.getSkillSlot();
        int pointsBefore = mercenary.getSkillPoints();
        check(sb, "B15 ★ 拒绝时**什么都不改**（槽位仍是 " + slotBefore + "、技力仍是 " + pointsBefore + "）",
                !Skills.setSlot(mercenary, 3, 999).isEmpty()
                        && mercenary.getSkillSlot() == slotBefore
                        && mercenary.getSkillPoints() == pointsBefore);

        // ---- B16 存档往返：技力 / 激活剩余 / 弹药 三字段 ----
        PixelUnit saver = spawnPawnOnGround(level, bx + 80, bz, by, UnitBranch.SNIPER_HEAVYSHOOTER);
        cleanup.add(saver);
        check(sb, "B16 装 2 号技能（存档往返用）成功", Skills.setSlot(saver, 2, 0).isEmpty());
        saver.setSkillPoints(2);
        saver.setSkillActiveTicks(37);
        saver.setSkillAmmo(4);
        CompoundTag tag = new CompoundTag();
        saver.saveWithoutId(tag);
        PixelUnit reloaded = spawnPawnOnGround(level, bx + 88, bz, by, UnitBranch.GUARD_FIGHTER);
        cleanup.add(reloaded);
        reloaded.load(tag);
        check(sb, "B16 存档往返：槽位 = 2", reloaded.getSkillSlot() == 2);
        check(sb, "B16 存档往返：技力 = 2", reloaded.getSkillPoints() == 2);
        check(sb, "B16 存档往返：激活剩余 = 37", reloaded.getSkillActiveTicks() == 37);
        check(sb, "B16 存档往返：弹药 = 4", reloaded.getSkillAmmo() == 4);

        // ---- B17 老存档：带占位冷却键的实体照常加载（不报错、不迁移）----
        //   ★ 键名用**字符串字面量**：那个常量已经删掉（尾条 3「不留不被读的符号」），
        //     这条断言就是「老存档带 SkillCooldown 也照常加载」的活标本。
        CompoundTag legacy = new CompoundTag();
        saver.saveWithoutId(legacy);
        legacy.putInt("SkillCooldown", 123);
        PixelUnit oldSave = spawnPawnOnGround(level, bx + 96, bz, by, UnitBranch.GUARD_FIGHTER);
        cleanup.add(oldSave);
        oldSave.load(legacy);
        check(sb, "B17 ★ 老存档：带 \"SkillCooldown\" 键的实体照常加载（忽略该键，不迁移、不报错）",
                oldSave.getSkillSlot() == 2 && oldSave.getSkillPoints() == 2
                        && oldSave.getSkillActiveTicks() == 37 && oldSave.getSkillAmmo() == 4);

        sb.append("   ").append(Skills.describe(heavy)).append('\n');
        sb.append("   ").append(Skills.describe(spreader)).append('\n');
        sb.append("   ").append(Skills.describe(mercenary)).append('\n');
    }

    /**
     * <b>C 反例</b>：防「静默通过」—— 把「不该触发」的两种情形各跑一遍。
     *
     * <p>只有正面断言时，「每 tick 都放」这种错误会**碰巧**全绿（每个 tick 都触发 ≥ 1 次，
     * 于是「至少触发一次」成立）；这两条反例就是为此存在。</p>
     */
    private static void skillsCounterGroup(StringBuilder sb, ServerLevel level, int bx, int bz, int by,
                                           List<Entity> cleanup) {
        sb.append("   [C] 反例（防「静默通过」）\n");

        // C1：未激活且 技力 < 消耗 ⇒ 不触发
        //   ★ 场地另开一列（bx + 112）：B 段的夹具要到 finally 才清理，两段共用一列会让两个棋子
        //     叠在同一格 —— 断言本身是 per-unit 的，但叠着摆没有任何好处，还容易看错报告。
        PixelUnit gunner = spawnPawnOnGround(level, bx + 112, bz, by, UnitBranch.SNIPER_ARTILLERYMAN);
        cleanup.add(gunner);
        Skills.setSlot(gunner, 2, 6);           // 炮手技能2：消耗 7 ⇒ 6 < 7
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "C1 ★ 反例：未激活且技力（6）< 消耗（7）⇒ 一次都不触发（castCount = "
                        + Skills.debugCastCount(gunner) + "）",
                Skills.debugCastCount(gunner) == 0);
        Skills.setSlot(gunner, 2, 7);
        Skills.resetDebugCounters();
        Skills.onServerTick(level.getServer());
        check(sb, "C1 对照（正例）：写到消耗（7）就触发一次 ⇒ 上一条不是「路径根本没通」"
                        + "（castCount = " + Skills.debugCastCount(gunner) + "）",
                Skills.debugCastCount(gunner) == 1);

        // C2：槽位 0（没有技能）且技力拉满 ⇒ 也不触发
        PixelUnit none = spawnPawnOnGround(level, bx + 104, bz, by, UnitBranch.GUARD_FIGHTER);
        cleanup.add(none);
        check(sb, "C2 卸下技能（槽位 0）成功", Skills.setSlot(none, 0, 0).isEmpty());
        none.setSkillPoints(999);
        Skills.resetDebugCounters();
        for (int t = 0; t < 30; t++) {
            Skills.onServerTick(level.getServer());
        }
        check(sb, "C2 ★ 反例：槽位 0（没有技能）跑了 30 tick，技力写着 999 也一次都不触发"
                        + "（castCount = " + Skills.debugCastCount(none) + "，技力 = "
                        + none.getSkillPoints() + "）",
                Skills.debugCastCount(none) == 0);
        sb.append("   ").append(Skills.describe(none)).append('\n');
    }

    /**
     * <b>D 只读入口</b>（★ 本轮新增，2026-10）：{@code /guardianprotocol skill}（**不带参数**）
     * 必须做到三件事 —— ① 可被解析、② 一个字段都不改、③ 绝不触发技能。
     *
     * <h3>为什么这三条都要断言</h3>
     * <p>只读入口最危险的失败模式是「悄悄变成了写入口」：{@code Skills.describe} 多读一个字段不会
     * 出事，但如果哪天有人把 {@code setSlot} 或者一次 {@code onServerTick} 挪进这条路径，
     * 玩家的「看着技力涨」立刻变成「一看就清零」—— 而这在编译期、跑起来都毫无提示。
     * 所以这里把槽位 / 技力 / 激活剩余 / 弹药 / 放过几次**五项**在调用前后逐个比一遍。</p>
     *
     * <h3>★ 断言独立性（本项目硬纪律）</h3>
     * <p>期望值是**手写字面量**（槽位 2 / 技力 3 / 激活 41 / 弹药 5 / 已放 0 次），
     * 不是「先调实现算一遍再比对」；也没有一条用 {@code getGameTime()} 计时
     * —— 自验台不推进游戏刻（踩坑记录），本场景跑在 {@code ServerStartedEvent} 里。</p>
     *
     * <h3>为什么夹具摆在 bx + 140，且命令源就摆在棋子身上</h3>
     * <p>命令认的是「8 格内**最近**的棋子」，而 B/C 两段的夹具要到 {@code scenarioSkills} 的
     * {@code finally} 才清理、全都还在场上。所以这里单开一列（离最近的 C 段夹具 28 格），
     * 再把命令源放在这颗棋子<b>自己身上</b>（距离 0）——「最近的那颗」因此只可能是它，
     * 断言不会被别的夹具抢走目标。</p>
     */
    private static void skillsReadOnlyGroup(StringBuilder sb, ServerLevel level, int bx, int bz, int by,
                                            List<Entity> cleanup) {
        sb.append("   [D] 只读入口 /guardianprotocol skill（不带参数）\n");

        PixelUnit reader = spawnPawnOnGround(level, bx + 140, bz, by, UnitBranch.SNIPER_ARTILLERYMAN);
        cleanup.add(reader);
        // 炮手技能 2「你须愧悔」：攻击回复 / 瞬发 / 消耗 7（与 B10 同一个技能）。
        // 技力写 3 < 7 ⇒ 就算这条路径偷偷推进了状态机，也**不该**触发；再手动摆上激活剩余与弹药，
        // 让五个字段全都不是默认值，「改没改」一眼看得出。
        boolean slotOk = Skills.setSlot(reader, 2, 3).isEmpty();
        reader.setSkillActiveTicks(41);
        reader.setSkillAmmo(5);
        Skills.resetDebugCounters();          // 计数从 0 起步（字面量期望）

        int slot0 = reader.getSkillSlot();
        int points0 = reader.getSkillPoints();
        int active0 = reader.getSkillActiveTicks();
        int ammo0 = reader.getSkillAmmo();
        int cast0 = Skills.debugCastCount(reader);
        check(sb, "D1 前置夹具（手写字面量：槽位 2 / 技力 3 / 激活 41 / 弹药 5 / 已放 0 次）—— 实际 "
                        + slot0 + " / " + points0 + " / " + active0 + " / " + ammo0 + " / " + cast0,
                slotOk && slot0 == 2 && points0 == 3 && active0 == 41 && ammo0 == 5 && cast0 == 0);

        // 命令源摆在棋子自己身上：8 格内最近的那颗只可能是它
        CommandSourceStack console = level.getServer().createCommandSourceStack()
                .withPosition(reader.position());
        CommandDispatcher<CommandSourceStack> disp = level.getServer().getCommands().getDispatcher();
        int rc = 0;
        String cmdErr = "";
        try {
            rc = disp.execute("guardianprotocol skill", console);   // 走 brigadier 解析，不直接调方法
        } catch (Exception ex) {
            cmdErr = String.valueOf(ex.getMessage());
        }
        check(sb, "D2 ★ 只读入口真的跑到了（返回值 = " + rc + "，即不是「附近没有棋子」那条失败分支）"
                        + (cmdErr.isEmpty() ? "" : "｜异常=" + cmdErr), rc == 1);
        check(sb, "D3 ★ 只读不改状态：槽位 / 技力 / 激活剩余 / 弹药 / 放过几次**五项全不变**（"
                        + slot0 + "/" + points0 + "/" + active0 + "/" + ammo0 + "/" + cast0 + " → "
                        + reader.getSkillSlot() + "/" + reader.getSkillPoints() + "/"
                        + reader.getSkillActiveTicks() + "/" + reader.getSkillAmmo() + "/"
                        + Skills.debugCastCount(reader) + "）",
                reader.getSkillSlot() == slot0 && reader.getSkillPoints() == points0
                        && reader.getSkillActiveTicks() == active0 && reader.getSkillAmmo() == ammo0
                        && Skills.debugCastCount(reader) == cast0);

        // 连读若干次：技力不满的棋子照旧一次都不放（castCount 恒 0，与 D3 一起排除「读了才发现没跑」）
        int repeats = 5;
        int rcSum = rc;
        for (int i = 1; i < repeats; i++) {
            try {
                rcSum += disp.execute("guardianprotocol skill", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
        }
        check(sb, "D4 ★ 只读不会触发技能：连读 " + repeats + " 次后放过次数仍是 0（castCount = "
                        + Skills.debugCastCount(reader) + "）｜技力 = " + reader.getSkillPoints()
                        + "（手写字面量期望 3）",
                Skills.debugCastCount(reader) == 0 && reader.getSkillPoints() == 3);
        check(sb, "D4 对照：每一次都返回 1（" + repeats + " 次合计 " + rcSum + "）"
                        + "—— 证明上一条不是「命令压根没跑」" + (cmdErr.isEmpty() ? "" : "｜异常=" + cmdErr),
                rcSum == repeats);

        // 命令树：照 spawnpoint 场景的写法直接查节点，并确认无参那条**可被解析**
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> rootNode =
                disp.getRoot().getChild("guardianprotocol");
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> skillNode =
                rootNode == null ? null : rootNode.getChild("skill");
        check(sb, "D5 ★ 命令节点 /guardianprotocol skill 已注册（命令树）", skillNode != null);
        boolean parsed = false;
        String parseErr = "";
        try {
            // ★ brigadier 的 parse **不用「返回 null」表达失败**：解析不掉的输入被收进
            //   ParseResults 的异常表里（不同版本上也可能直接抛 CommandSyntaxException）。
            //   所以这里两种失败都认：异常表非空、或者 throw 出来的异常都算**没解析过** ——
            //   只判 `结果 != null` 等于没判（ParseResults 永远不是 null）。
            // ★ 用 var 而不是写 ParseResults 的全限定名：brigadier 的 ParseResults 在不同版本里
            //   位置/形状都动过，写死名字等于给自己埋一个编译期坑。
            var pr = skillNode == null ? null : disp.parse("guardianprotocol skill", console);
            parsed = pr != null && pr.getContext() != null && pr.getExceptions().isEmpty();
            if (pr != null && !pr.getExceptions().isEmpty()) {
                // 不按下标取：getExceptions() 在不同 brigadier 版本上是 List / Map 两种形状，
                // 只走「转字符串」这一条与形状无关的路
                parseErr = String.valueOf(pr.getExceptions());
            }
        } catch (Exception ex) {
            parseErr = String.valueOf(ex.getMessage());
        }
        check(sb, "D5 ★ 无参那条**可被解析**（走 brigadier parse，照 spawnpoint 场景的写法）"
                        + (parseErr.isEmpty() ? "" : "｜解析异常=" + parseErr), parsed);
        check(sb, "D5 skill <槽位> 分支仍在（只读入口没把切槽那半顶掉）",
                skillNode != null && skillNode.getChild("slot") != null);
        sb.append("   ").append(Skills.describe(reader)).append('\n');
    }

    // ------------------------------------------------------------------
    // 场景 3.5：攻击方式（投掷物 / 近战武器）
    // ------------------------------------------------------------------

    /**
     * 攻击方式的自验：<b>世界上真的多出一颗投掷物、目标隔空掉血、近战出手后场上没有弹体</b>。
     *
     * <h3>为什么不能只断言 {@code branch.attackMethod()}</h3>
     * <p>枚举值对了，也可能「发射那条路根本没接上」—— 出生点算错让弹体一出生就被回收、
     * 命中没回调结算、弹体永远追不到目标。这些都不会抛异常，只会「静静地打不到人」。
     * 所以这里三件事都数实物：场上的弹体数量、目标的血量、近战出手后场上有没有弹。</p>
     *
     * <h3>★ 为什么手动 tick 弹体，而不是等服端自己 tick</h3>
     * <p>弹体的 {@code tick()} 是「取目标位置 → 朝它挪一步 → 判命中」的纯位置迭代，
     * 手动推进与按真实节奏跑等价，但<b>结果是确定的</b>（不必担心这一 tick 里服务端还干了别的）。
     * 这与场景 {@code live} 的口径<b>相反</b> —— 那边观察的是寻路/嘲讽这类必须按真实时间跑的行为，
     * 所以那边坚决不手动 tick。判据：被观察的东西是不是「纯位置迭代」，是就手动推。</p>
     */
    private static void scenarioAttack(StringBuilder sb, ServerLevel level, BlockPos base) {
        sb.append("-- attack：攻击方式（投掷物 / 近战武器）\n");
        // ★ 近战「墙」分支（斗士…）只打**被挡住**的敌人，而被挡名单来自配置白名单
        //   （默认空 = 谁都不挡）。所以本场景临时把白名单开成僵尸，否则近战那一段
        //   会因为「一个候选都没有」而失败 —— 那不是攻击方式坏了。
        List<String> oldWhitelist = BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
        try {
            scenarioAttackBody(sb, level, base);
        } finally {
            BlockCandidates.setWhitelistOverride(oldWhitelist);
        }
    }

    /** {@link #scenarioAttack} 的实现体（白名单覆盖的收尾由调用方负责）。 */
    private static void scenarioAttackBody(StringBuilder sb, ServerLevel level, BlockPos base) {

        // ---- 0) 设计点名的那 6 个「近战位但发射投掷物」的分支，在运行时的口径 ----
        StringBuilder named = new StringBuilder();
        boolean namedOk = true;
        for (UnitBranch b : new UnitBranch[]{UnitBranch.VANGUARD_AGENT, UnitBranch.GUARD_LORD,
                UnitBranch.DEFENDER_SENTRY_PROTECTOR, UnitBranch.DEFENDER_FORTRESS,
                UnitBranch.SPECIALIST_AMBUSHER, UnitBranch.SPECIALIST_HOOKMASTER}) {
            boolean ok = b.attackMethod() == UnitBranch.AttackMethod.PROJECTILE;
            namedOk = namedOk && ok;
            named.append(b.branchName()).append(ok ? "✓" : "✗").append(' ');
        }
        check(sb, "点名 6 分支都是投掷物：" + named.toString().trim(), namedOk);

        int bx = base.getX() + 8;
        int bz = base.getZ() + 150;
        int by = groundYAt(level, bx, bz, base.getY());

        // ---- 1) 投掷物：速射手打 2 格外的靶子 ----
        sb.append("   [投掷物] 速射手（投掷物分支）\n");
        List<Entity> cleanup = new ArrayList<>();
        PixelUnit shooter = spawnPawnOnGround(level, bx, bz, by, UnitBranch.SNIPER_MARKSMAN);
        cleanup.add(shooter);
        try {
            check(sb, "速射手的攻击方式 = PROJECTILE",
                    shooter.getBranch().attackMethod() == UnitBranch.AttackMethod.PROJECTILE);
            sb.append("   攻击方式依据：").append(shooter.getBranch().attackMethodSource()).append('\n');

            Mob victim = spawn(level, EntityType.ZOMBIE, bx, by, bz, "投掷靶");
            cleanup.add(victim);
            // ★ 以棋子的**实际**落点为基准摆靶子（同 scenarioBlock 的教训：别按地面扫描的猜测值摆）。
            // ★ 距离取 2 格：**要在它的攻击范围里**。第一版写的是 6 格，结果速射手根本够不着，
            //   报告里表现为「出手后 0 颗弹」—— 看着像发射链路坏了，实际是测试台把靶子摆太远。
            //   下面「本 tick 选中目标」那条断言就是为区分这两种原因加的。
            place(victim, shooter.blockPosition().getX(), shooter.blockPosition().getY(),
                    shooter.blockPosition().getZ(), 2.0D, 0.0D);
            float hpBefore = victim.getHealth();

            PawnCombatManager.onServerTick(level.getServer());
            // ★★ 冷却必须活过一整次服务端心跳（含「跨维度 prune」那一轮）。
            //   这条断言是本轮最值钱的一条：修掉之前，主世界棋子的冷却会在同一次 tick 里
            //   被下界那次 prune 清掉 ⇒ 棋子**每个 tick 都出手**，攻击间隔与倍率完全失效，
            //   而报告里一句异常都没有（详见 PawnCombatManager.prune 的注释）。
            int intervalTicks = PawnCombatManager.debugIntervalTicks(shooter);
            check(sb, "★ 出手后冷却 = 间隔 " + intervalTicks + " tick",
                    PawnCombatManager.debugCooldown(shooter) == intervalTicks);
            int shotsBefore = projectilesNear(level, shooter).size();
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "★ 冷却期内不会再出手（弹体仍是 " + shotsBefore + " 颗）",
                    projectilesNear(level, shooter).size() == shotsBefore);
            List<LivingEntity> picked = PawnCombatManager.debugLastTargets(shooter);
            check(sb, "本 tick 选中目标 " + picked.size() + " 个（0 个 ⇒ 靶子不在范围里）",
                    !picked.isEmpty());
            if (picked.isEmpty()) {
                // 为什么没选中：把逐条判据打出来（不在格子里 / 垂直判定挡掉 / 算不算敌人…）
                sb.append(PawnCombatManager.debugTrace(shooter));
                return;
            }
            List<PawnProjectile> shots = projectilesNear(level, shooter);
            check(sb, "出手后世界上出现投掷物 " + shots.size() + " 颗", !shots.isEmpty());
            check(sb, "★ 伤害不是瞬时的（弹体还在飞，靶子未掉血）",
                    Math.abs(victim.getHealth() - hpBefore) < 1.0E-3F);
            if (shots.isEmpty()) {
                return;         // 已经 FAIL 过了，后面的飞行断言没有意义
            }
            PawnProjectile shot = shots.get(0);
            sb.append("   弹体：出生点 ").append(shortVec(shot.position()))
                    .append(" 追踪目标=").append(name(shot.getTargetEntity()))
                    .append(" 伤害=").append(r2(shot.getDamage()))
                    .append(" 距目标=").append(r2(shot.position().distanceTo(victim.position())))
                    .append('\n');

            int flown = 0;
            while (!shot.isRemoved() && flown < 40) {
                shot.tick();
                flown++;
            }
            check(sb, "弹体在 " + flown + " tick 内命中并消散", shot.isRemoved());
            check(sb, "★ 命中后靶子掉血（伤害确实发生在命中那一刻）",
                    victim.getHealth() < hpBefore - 1.0E-3F);
            sb.append("   靶子血量 ").append(r2(hpBefore)).append(" → ")
                    .append(r2(victim.getHealth())).append("（飞行 ").append(flown).append(" tick）\n");
            check(sb, "命中后场上没有残留弹体", projectilesNear(level, shooter).isEmpty());
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }

        // ---- 2) 弹道：侧向靶 + **会跑的**靶 ⇒ 追踪才验得出来 ----
        //   ★ 为什么靶子必须动：目标静止时，「追踪」与「沿直线飞过去」是**同一个结果**
        //     （第一版就是拿静止靶去断言「弹道是弯的」，结果最大转向 0.0° 直接 FAIL ——
        //     错的是断言不是代码，同 踩坑记录那一族）。所以这一条改成两问：
        //     ① 起手方向是否指向目标（而不是沿棋子朝向平射）；
        //     ② 目标横移时弹道会不会跟着拐。
        sb.append("   [弹道] 追踪与步长（侧向移动靶）\n");
        List<Entity> flightCleanup = new ArrayList<>();
        PixelUnit gunner = spawnPawnOnGround(level, bx + 20, bz, by, UnitBranch.CASTER_CORE);
        flightCleanup.add(gunner);
        try {
            Mob runner = spawn(level, EntityType.ZOMBIE, bx + 20, by, bz, "侧向靶");
            flightCleanup.add(runner);
            place(runner, gunner.blockPosition().getX(), gunner.blockPosition().getY(),
                    gunner.blockPosition().getZ(), 8.0D, 3.0D);

            // 直接造一颗弹（绕开出手冷却），逐 tick 记位置
            PawnProjectile probe = PawnProjectile.fire(level, gunner, runner, 1.0F);
            flightCleanup.add(probe);
            Vec3 prev = probe.position();
            double firstStep = -1.0D;
            double firstAngle = Double.NaN;
            double lastAngle = Double.NaN;
            boolean monotonic = true;
            boolean overspeed = false;
            double prevDist = Double.MAX_VALUE;
            int ticks = 0;
            while (!probe.isRemoved() && ticks < 60) {
                probe.tick();
                ticks++;
                if (probe.isRemoved()) {
                    break;
                }
                Vec3 now = probe.position();
                Vec3 step = now.subtract(prev);
                double len = step.length();
                if (firstStep < 0.0D) {
                    firstStep = len;
                }
                if (len > ProjectileFlight.SPEED_PER_TICK + 1.0E-6D) {
                    overspeed = true;
                }
                // 位移方向（水平面内的角度，0° = 正东）
                double angle = Math.atan2(step.z, step.x);
                if (Double.isNaN(firstAngle)) {
                    firstAngle = angle;
                }
                lastAngle = angle;
                double dist = now.distanceTo(runner.position());
                if (dist > prevDist + 1.0E-6D) {
                    monotonic = false;      // 追踪不该「越追越远」
                }
                prevDist = dist;
                prev = now;
                // ★ 靶子每 tick 横移 0.35 格（模拟逃跑的敌人）：静止靶下追踪与直线无法区分
                runner.setPos(runner.getX(), runner.getY(), runner.getZ() + 0.35D);
            }
            double offAxis = Math.toDegrees(Math.abs(Math.atan2(
                    Math.sin(firstAngle), Math.cos(firstAngle))));
            double swing = Math.toDegrees(Math.abs(Math.atan2(
                    Math.sin(lastAngle - firstAngle), Math.cos(lastAngle - firstAngle))));
            check(sb, "弹体在 " + ticks + " tick 内命中移动靶", probe.isRemoved());
            check(sb, "每 tick 位移不超过弹速 " + ProjectileFlight.SPEED_PER_TICK + " 格",
                    !overspeed);
            check(sb, "起手那一步真的在飞（位移 > 0）", firstStep > 0.0D);
            check(sb, "★ 起手方向指向目标（偏离棋子朝向 " + r1(offAxis) + "°，平射会是 0°）",
                    offAxis > 5.0D);
            check(sb, "★ 目标横移时弹道跟着拐（首段→末段偏转 " + r1(swing) + "°）",
                    swing > 5.0D);
            check(sb, "全程距离单调减小（没有绕远）", monotonic);
        } finally {
            discardAll(flightCleanup);
            sb.append("   清理生成物 ").append(flightCleanup.size()).append(" 个\n");
        }

        // ---- 3) 近战：斗士打 1 格外的靶子 ⇒ 一个 tick 就掉血，且场上没有弹体 ----
        sb.append("   [近战] 斗士（近战武器分支）\n");
        List<Entity> meleeCleanup = new ArrayList<>();
        PixelUnit fighter = spawnPawnOnGround(level, bx + 40, bz, by, UnitBranch.GUARD_FIGHTER);
        meleeCleanup.add(fighter);
        try {
            check(sb, "斗士的攻击方式 = MELEE",
                    fighter.getBranch().attackMethod() == UnitBranch.AttackMethod.MELEE);
            Mob dummy = spawn(level, EntityType.ZOMBIE, bx + 40, by, bz, "近战靶");
            meleeCleanup.add(dummy);
            // ★ 摆进**棋子脚下那一格**：斗士是「只打被挡的」那道墙，而被挡=默认只找脚下那一格。
            place(dummy, fighter.blockPosition().getX(), fighter.blockPosition().getY(),
                    fighter.blockPosition().getZ(), 0.35D, 0.0D);
            float hpBefore = dummy.getHealth();
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "★ 一次 tick 内就掉血（伤害即时结算，不等飞行）",
                    dummy.getHealth() < hpBefore - 1.0E-3F);
            sb.append("   靶子血量 ").append(r2(hpBefore)).append(" → ")
                    .append(r2(dummy.getHealth())).append('\n');
            check(sb, "近战出手后场上没有任何投掷物",
                    projectilesNear(level, fighter).isEmpty());
        } finally {
            discardAll(meleeCleanup);
            sb.append("   清理生成物 ").append(meleeCleanup.size()).append(" 个\n");
        }
    }

    /** 棋子周围 24 格内的投掷物（自验用；范围给足是为了看见「本该消失却没消失」的弹体）。 */
    private static List<PawnProjectile> projectilesNear(ServerLevel level, PixelUnit unit) {
        return level.getEntitiesOfClass(PawnProjectile.class,
                new AABB(unit.blockPosition()).inflate(24.0D));
    }

    /**
     * 手动把场上所有投掷物各推一 tick（自验台专用），返回推了几颗。
     *
     * <h3>★ 为什么需要它（改攻击方式时踩到的一个真问题）</h3>
     * <p>自验里的伤害观察原本只推 {@link PawnCombatManager#onServerTick} 的手动心跳，
     * 而投掷物的飞行是<b>实体自己的 tick</b>（服务端自然节奏）。测试台是在一条心跳里
     * 连推 30 步，这期间服务端**不会**去 tick 实体 —— 于是「攻击方式改成投掷物」之后，
     * 那些分支在旧场景里会表现成「选中了目标、却没有伤害」，
     * 而报告看起来像「索敌坏了」。把弹体一起推，旧场景的观测量就恢复原义。</p>
     *
     * <p>边界给 ±256 格：自验场地铺在两三百格内（各场景的 z 偏移 60~150），
     * 一次查询全覆盖，省得每个场景自己算窗口。</p>
     */
    private static int tickProjectiles(ServerLevel level, BlockPos base) {
        List<PawnProjectile> shots = level.getEntitiesOfClass(PawnProjectile.class,
                new AABB(base).inflate(256.0D));
        for (PawnProjectile shot : shots) {
            if (!shot.isRemoved()) {
                shot.tick();
            }
        }
        return shots.size();
    }

    // ------------------------------------------------------------------
    // 场景 4.5：出怪点（方块 + NBT 配置 + 服务出怪）
    // ------------------------------------------------------------------

    /**
     * 出怪点模块的自验：方块放得下、配置存得住且会夹取、服务按配置出怪、
     * 关掉就不出、纯坐标入口（{@code spawnOnce}）与延迟/间隔排期都能用。
     *
     * <h3>为什么要验到「世界上真的多了几只怪」</h3>
     * <p>只断言 {@code spawnBatch} 返回 true 是没用的：它返回 true 只代表「受理了」。
     * 出怪链路上真正会坏的地方在<b>后面</b> —— 实体类型解析不出来、
     * {@code finalizeSpawn} 的参数不对导致 {@code create} 返回 null、
     * 坐标算错让怪出在别处、{@code addFreshEntity} 被区块拒收。
     * 这些都不会抛异常，只会「静静地少几只」。所以每条都数一遍场上实际有几只。</p>
     *
     * <h3>隔离性（测试世界是设计的真实存档）</h3>
     * <p>只碰<b>一块</b>临时方块：放在用 {@link #groundYAt} 找到的平地空气格里，
     * 跑完 {@code removeBlock} 抹掉（不搬运流体、不掉落物品）。
     * 生成的每一只怪都记进 {@code cleanup} 后 {@code discard()}；
     * 排期表也一并清空（它是静态的，不清会溢到下一个场景）。</p>
     */
    private static void scenarioSpawnPoint(StringBuilder sb, ServerLevel level, BlockPos base) {
        sb.append("-- spawnpoint：出怪点（方块 / NBT 配置 / 服务）\n");
        int bx = base.getX() + 8;
        int bz = base.getZ() + 140;
        BlockPos spot = flatSpotNear(level, bx, bz, base.getY());
        sb.append("   场地：基准 ").append(fmt(base)).append(" → 出怪点格 ").append(fmt(spot)).append('\n');

        List<Entity> cleanup = new ArrayList<>();
        BlockPos placed = null;
        com.guardianprotocol.block.SpawnPointBlockEntity placedPoint = null;
        try {
            // ---- 1) 放方块，拿方块实体 ----
            level.setBlock(spot,
                    com.guardianprotocol.block.ModBlocks.SPAWN_POINT.get().defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
            placed = spot;
            var blockEntity = level.getBlockEntity(spot);
            check(sb, "放下后拿得到 SpawnPointBlockEntity", blockEntity instanceof com.guardianprotocol.block.SpawnPointBlockEntity);
            if (!(blockEntity instanceof com.guardianprotocol.block.SpawnPointBlockEntity point)) {
                return;
            }
            placedPoint = point;
            check(sb, "activePoints() 已经登记本出怪点",
                    com.guardianprotocol.spawn.SpawnPointService.activePoints().contains(point));

            // ---- 2) 默认值 + 越界夹取（与界面按钮走的是同一条 applyEdit）----
            var dflt = com.guardianprotocol.block.SpawnPointBlockEntity.DEFAULT_ENTRY;
            check(sb, "默认 1 条", point.entryCount() == 1);
            check(sb, "默认怪物类型 = " + dflt.entityId(), dflt.equals(point.entry(0)));
            check(sb, "默认启用", point.isEnabled());
            check(sb, "默认解析得出来（husk 是生物）", point.resolveEntry(0).type() == EntityType.HUSK);

            for (int i = 0; i < 25; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_COUNT_UP, 0, "");
            }
            check(sb, "数量连点 25 次夹到 " + com.guardianprotocol.block.SpawnPointBlockEntity.MAX_COUNT
                            + "（实际 " + point.entry(0).count() + "）",
                    point.entry(0).count() == com.guardianprotocol.block.SpawnPointBlockEntity.MAX_COUNT);
            for (int i = 0; i < 60; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_INTERVAL_UP, 0, "");
            }
            check(sb, "间隔连点到上限 "
                            + com.guardianprotocol.block.SpawnPointBlockEntity.MAX_INTERVAL_TICKS
                            + "（实际 " + point.entry(0).intervalTicks() + "）",
                    point.entry(0).intervalTicks()
                            == com.guardianprotocol.block.SpawnPointBlockEntity.MAX_INTERVAL_TICKS);
            for (int i = 0; i < 3; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_DELAY_DOWN, 0, "");
            }
            check(sb, "延迟 - 到底夹到 0", point.entry(0).delayTicks() == 0);

            // 非法实体 id 必须解析成 null（服务层据此跳过这条），而不是抛异常、
            // 更不是「悄悄替换成注册表默认值」（Forge 的 getValue 对未知 key 会回默认值 = 猪）
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, 0,
                    "minecraft:definitely_not_a_mob");
            var bogus = point.resolveEntry(0).type();
            check(sb, "乱写的实体 id 解析为 null（实际 "
                            + (bogus == null ? "null" : bogus.getDescription().getString()) + "）",
                    bogus == null);
            check(sb, "乱写的实体 id 会让 spawnBatch 返回 false",
                    !com.guardianprotocol.spawn.SpawnPointService.spawnBatch(level, point));
            check(sb, "乱写的实体 id 不会留下排期（实际 "
                            + com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() + " 批）",
                    com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() == 0);

            // ---- 3) 出怪组：两种怪，种类/数量/延迟/间隔各自独立 ----
            // ★ 先把上一步「夹到上限」的残留清掉：数量 20 / 间隔 200 留着会让下面
            //   全走排期路径、当场一只都不出（这一条是自验自己踩出来的，不是产品行为）。
            point.resetToDefaults();
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, 0,
                    "  Minecraft:Zombie  ");
            check(sb, "实体 id 规范化（去空白 + 转小写）",
                    "minecraft:zombie".equals(point.entry(0).entityId()));
            check(sb, "解析成僵尸类型", point.resolveEntry(0).type() == EntityType.ZOMBIE);

            check(sb, "ADD_ENTRY 受理", point.applyEdit(
                    com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ADD_ENTRY, -1, ""));
            check(sb, "现在是 2 条", point.entryCount() == 2);
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, 1,
                    "minecraft:skeleton");
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_COUNT_UP, 1, "");
            // 第 0 条：3 只；第 1 条：2 只（默认 1 + 一次 +）
            for (int i = 0; i < 2; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_COUNT_UP, 0, "");
            }
            check(sb, "第 0 条 = 僵尸 ×3", point.entry(0).count() == 3);
            check(sb, "第 1 条 = 骷髅 ×2", point.entry(1).count() == 2);
            check(sb, "两条的类型互不影响",
                    "minecraft:zombie".equals(point.entry(0).entityId())
                            && "minecraft:skeleton".equals(point.entry(1).entityId()));
            sb.append("   配置：").append(point.debugLine()).append('\n');

            // ---- 4) 一次出完：间隔 0 时两种怪各自按自己的数量出 ----
            boolean accepted = com.guardianprotocol.spawn.SpawnPointService.spawnBatch(level, point);
            List<Entity> zombies = mobsNear(level, spot, EntityType.ZOMBIE, 8.0D);
            List<Entity> skeletons0 = mobsNear(level, spot, EntityType.SKELETON, 8.0D);
            cleanup.addAll(zombies);
            cleanup.addAll(skeletons0);
            check(sb, "spawnBatch 返回 true", accepted);
            check(sb, "★ 僵尸 3 只（实际 " + zombies.size() + "）", zombies.size() == 3);
            check(sb, "★ 骷髅 2 只（实际 " + skeletons0.size() + "）", skeletons0.size() == 2);

            double maxDist = 0.0D;
            double maxHeight = 0.0D;
            Set<String> footprints = new HashSet<>();
            for (Entity e : zombies) {
                maxDist = Math.max(maxDist, Math.sqrt(distanceToSqr(e,
                        spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D)));
                maxHeight = Math.max(maxHeight, Math.abs(e.getY() - spot.getY()));
                footprints.add(String.format(Locale.ROOT, "%.2f,%.2f", e.getX(), e.getZ()));
            }
            for (Entity e : skeletons0) {
                footprints.add(String.format(Locale.ROOT, "%.2f,%.2f", e.getX(), e.getZ()));
            }
            check(sb, "都散在出怪点 2.5 格内（最远 " + r2(maxDist) + "）", maxDist <= 2.5D);
            check(sb, "都站在出怪点那一层（最大高度差 " + r2(maxHeight) + "）", maxHeight <= 1.0D);
            check(sb, "★ 两条的落点互不重叠（5 只 → " + footprints.size() + " 个落点）",
                    footprints.size() == 5);

            // ---- 5) 存档往返 + 旧格式迁移（都是「重启后配置还在吗」这个问题的两半）----
            CompoundTag saved = point.saveWithoutMetadata();
            var savedList = saved.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
            sb.append("   NBT：Entries=").append(savedList.size()).append(" 条")
                    .append(" Enabled=").append(saved.getBoolean("Enabled")).append('\n');
            var reloaded = new com.guardianprotocol.block.SpawnPointBlockEntity(spot, level.getBlockState(spot));
            reloaded.load(saved);
            check(sb, "往返后仍是 2 条", reloaded.entryCount() == 2);
            check(sb, "往返后第 0 条 = 僵尸 ×3",
                    reloaded.entry(0).count() == 3 && "minecraft:zombie".equals(reloaded.entry(0).entityId()));
            check(sb, "往返后第 1 条 = 骷髅 ×2",
                    reloaded.entry(1).count() == 2 && "minecraft:skeleton".equals(reloaded.entry(1).entityId()));
            check(sb, "往返后启用仍是 true", reloaded.isEnabled());
            reloaded.setRemoved();

            // 旧格式（单条目）迁移：设计的存档里已经摆着这种出怪点，不认它就会变成「默认的 4 只 husk」
            CompoundTag legacy = new CompoundTag();
            legacy.putString("EntityId", "minecraft:creeper");
            legacy.putInt("Count", 4);
            legacy.putInt("DelayTicks", 40);
            legacy.putInt("IntervalTicks", 15);
            legacy.putBoolean("Enabled", false);
            var migrated = new com.guardianprotocol.block.SpawnPointBlockEntity(spot, level.getBlockState(spot));
            migrated.load(legacy);
            check(sb, "★ 旧格式迁移成 1 条", migrated.entryCount() == 1);
            check(sb, "★ 迁移后种类/数量/延迟/间隔都对",
                    migrated.entry(0) != null
                            && "minecraft:creeper".equals(migrated.entry(0).entityId())
                            && migrated.entry(0).count() == 4
                            && migrated.entry(0).delayTicks() == 40
                            && migrated.entry(0).intervalTicks() == 15);
            check(sb, "迁移后启用状态也保留（false）", !migrated.isEnabled());
            migrated.setRemoved();

            // ---- 6) 快照编解码往返（界面显示走的正是这条，必须自己验一遍）----
            var snapshot = point.snapshot();
            var syncPacket = new com.guardianprotocol.net.SpawnPointSyncPacket(spot, snapshot.enabled(),
                    snapshot.entries());
            var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            com.guardianprotocol.net.SpawnPointSyncPacket.encode(syncPacket, buf);
            var decoded = com.guardianprotocol.net.SpawnPointSyncPacket.decode(buf);
            check(sb, "★ 快照编解码往返：坐标一致", spot.equals(decoded.pos()));
            check(sb, "★ 快照编解码往返：2 条且逐字段一致",
                    decoded.entries().size() == 2
                            && decoded.entries().get(0).equals(point.entry(0))
                            && decoded.entries().get(1).equals(point.entry(1)));
            // 越界值进解码器要被夹回去（客户端不能因为对面发来脏数据而显示非法组合）
            var hostile = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            hostile.writeBlockPos(spot);
            hostile.writeBoolean(true);
            hostile.writeVarInt(1);
            hostile.writeUtf("minecraft:zombie");
            hostile.writeVarInt(9999);
            hostile.writeVarInt(-5);
            hostile.writeVarInt(99999);
            var clamped = com.guardianprotocol.net.SpawnPointSyncPacket.decode(hostile);
            check(sb, "★ 脏快照被夹回合法区间（实际 "
                            + clamped.entries().get(0).count() + "/" + clamped.entries().get(0).delayTicks()
                            + "/" + clamped.entries().get(0).intervalTicks() + "）",
                    clamped.entries().get(0).count() == com.guardianprotocol.block.SpawnPointBlockEntity.MAX_COUNT
                            && clamped.entries().get(0).delayTicks() == 0
                            && clamped.entries().get(0).intervalTicks()
                            == com.guardianprotocol.block.SpawnPointBlockEntity.MAX_INTERVAL_TICKS);

            // ---- 7) 关掉：一只都不出 ----
            discardAll(zombies);
            discardAll(skeletons0);
            point.setEnabled(false);
            boolean disabledAccepted = com.guardianprotocol.spawn.SpawnPointService.spawnBatch(level, point);
            List<Entity> afterDisabled = mobsNear(level, spot, EntityType.ZOMBIE, 8.0D);
            cleanup.addAll(afterDisabled);
            check(sb, "关掉后 spawnBatch 返回 false", !disabledAccepted);
            check(sb, "关掉后附近 0 只僵尸（实际 " + afterDisabled.size() + "）", afterDisabled.isEmpty());
            point.setEnabled(true);

            // ---- 8) 纯坐标入口 spawnOnce（上层波次管理器用的那条，不碰任何方块）----
            Vec3 oncePos = new Vec3(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 3.5D);
            int once = com.guardianprotocol.spawn.SpawnPointService.spawnOnce(
                    level, oncePos, EntityType.SKELETON, 2, null);
            List<Entity> skeletons = mobsNear(level, spot, EntityType.SKELETON, 8.0D);
            cleanup.addAll(skeletons);
            check(sb, "spawnOnce 返回 2（实际 " + once + "）", once == 2);
            check(sb, "附近出现 2 只骷髅（实际 " + skeletons.size() + "）", skeletons.size() == 2);

            // extraNbt：既验名字/血量真的写进去，也验「tag 里的 Pos 不许把怪挪走」
            CompoundTag extra = new CompoundTag();
            extra.putString("CustomName", "{\"text\":\"自验靶子\"}");
            extra.putFloat("Health", 7.0F);
            // 故意塞一个错误坐标：实现必须先 load 再钉回出怪点，否则这条会 FAIL
            extra.putString("Pos", "[9999.0d, 9999.0d, 9999.0d]");
            int withNbt = com.guardianprotocol.spawn.SpawnPointService.spawnOnce(
                    level, oncePos, EntityType.ZOMBIE, 1, extra);
            List<Entity> tagged = mobsNear(level, spot, EntityType.ZOMBIE, 8.0D);
            cleanup.addAll(tagged);
            check(sb, "spawnOnce 带 extraNbt 返回 1（实际 " + withNbt + "）", withNbt == 1);
            check(sb, "extraNbt 的自定义名生效",
                    tagged.size() == 1 && tagged.get(0).getCustomName() != null
                            && "自验靶子".equals(tagged.get(0).getCustomName().getString()));
            check(sb, "extraNbt 的血量 7 生效",
                    tagged.size() == 1 && tagged.get(0) instanceof LivingEntity le
                            && Math.abs(le.getHealth() - 7.0F) < 1.0E-3F);
            check(sb, "extraNbt 里的错误 Pos 没有把怪挪走（仍在出怪点附近）",
                    tagged.size() == 1
                            && Math.abs(tagged.get(0).getZ() - oncePos.z) < 1.0D
                            && Math.abs(tagged.get(0).getX() - oncePos.x) < 1.5D);

            // ---- 9) ★ 每条各排各的：僵尸 3 只（延迟 5 / 间隔 4），骷髅 1 只（延迟 12）----
            discardAll(skeletons);
            discardAll(tagged);
            point.resetToDefaults();
            // 排期表是静态的，上一段可能留下在途批次 —— 清空后再排，数目才对得上
            com.guardianprotocol.spawn.SpawnPointService.clearAll();
            check(sb, "排期表已清空（进入本节前）",
                    com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() == 0);
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ADD_ENTRY, -1, "");
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, 0,
                    "minecraft:zombie");
            point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, 1,
                    "minecraft:skeleton");
            for (int i = 0; i < 2; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_COUNT_UP, 0, "");
            }
            for (int i = 0; i < 4; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_DELAY_UP, 0, "");
            }
            // 第 0 条再给一个非 0 的「自己的间隔」（2 步 × 5 = 10 tick）：
            // 没有它的话这一条会「整批一次出完」，验不出「每条自己的节奏」。
            for (int i = 0; i < 2; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_INTERVAL_UP, 0, "");
            }
            for (int i = 0; i < 12; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_DELAY_UP, 1, "");
            }
            // 延迟步长 20 tick：第 0 条 = 4×20 = 80，第 1 条 = 12×20 = 240
            check(sb, "第 0 条延迟 = " + point.entry(0).delayTicks() + " tick", point.entry(0).delayTicks() == 80);
            check(sb, "第 0 条间隔 = " + point.entry(0).intervalTicks() + " tick",
                    point.entry(0).intervalTicks() == 10);
            check(sb, "第 1 条延迟 = " + point.entry(1).delayTicks() + " tick", point.entry(1).delayTicks() == 240);
            check(sb, "第 1 条间隔 = 0（它自己一次出完）", point.entry(1).intervalTicks() == 0);

            boolean scheduled = com.guardianprotocol.spawn.SpawnPointService.spawnBatch(level, point);
            check(sb, "带延迟的组被受理（返回 true）", scheduled);
            check(sb, "当场一只都没出", mobsNear(level, spot, EntityType.ZOMBIE, 8.0D).isEmpty()
                    && mobsNear(level, spot, EntityType.SKELETON, 8.0D).isEmpty());
            check(sb, "排期表：2 条各自排期（实际 "
                            + com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() + " 批 / "
                            + com.guardianprotocol.spawn.SpawnPointService.pendingMobCount() + " 只）",
                    com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() == 2
                            && com.guardianprotocol.spawn.SpawnPointService.pendingMobCount() == 4);

            for (int t = 0; t < 79; t++) {
                com.guardianprotocol.spawn.SpawnPointService.tickPending(level.getServer());
            }
            check(sb, "推进 79 tick：第 0 条的延迟还没到（僵尸 0 只）",
                    mobsNear(level, spot, EntityType.ZOMBIE, 8.0D).isEmpty());
            com.guardianprotocol.spawn.SpawnPointService.tickPending(level.getServer());
            List<Entity> firstOut = mobsNear(level, spot, EntityType.ZOMBIE, 8.0D);
            cleanup.addAll(firstOut);
            check(sb, "★ 第 80 tick：僵尸出第 1 只（实际 " + firstOut.size() + "）", firstOut.size() == 1);
            check(sb, "★ 此时骷髅仍是 0 只（它自己的延迟是 240）",
                    mobsNear(level, spot, EntityType.SKELETON, 8.0D).isEmpty());
            // 第 0 条的间隔是 10 tick：再推进 10 tick 出第 2 只、再 10 tick 出第 3 只
            for (int t = 0; t < 10; t++) {
                com.guardianprotocol.spawn.SpawnPointService.tickPending(level.getServer());
            }
            List<Entity> second = mobsNear(level, spot, EntityType.ZOMBIE, 8.0D);
            check(sb, "★ 第 90 tick：按自己的间隔出第 2 只（实际 " + second.size() + " 只）",
                    second.size() == 2);
            for (int t = 0; t < 10; t++) {
                com.guardianprotocol.spawn.SpawnPointService.tickPending(level.getServer());
            }
            List<Entity> zombiesAfter = mobsNear(level, spot, EntityType.ZOMBIE, 8.0D);
            cleanup.addAll(zombiesAfter);
            check(sb, "★ 第 100 tick：3 只出完（实际 " + zombiesAfter.size() + " 只）",
                    zombiesAfter.size() == 3);
            for (int t = 0; t < 200; t++) {
                com.guardianprotocol.spawn.SpawnPointService.tickPending(level.getServer());
            }
            List<Entity> skeletonsLater = mobsNear(level, spot, EntityType.SKELETON, 8.0D);
            cleanup.addAll(skeletonsLater);
            check(sb, "★ 骷髅按自己的延迟 240 出现（实际 " + skeletonsLater.size() + " 只）",
                    skeletonsLater.size() == 1);
            check(sb, "排期表已清空（实际 "
                            + com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() + " 批）",
                    com.guardianprotocol.spawn.SpawnPointService.pendingBatchCount() == 0);

            // ---- 10) 条目数上限与「至少留一条」----
            point.resetToDefaults();
            for (int i = 0; i < 10; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ADD_ENTRY, -1, "");
            }
            check(sb, "ADD_ENTRY 加不超上限（实际 " + point.entryCount() + " 条）",
                    point.entryCount() == com.guardianprotocol.block.SpawnPointBlockEntity.MAX_ENTRIES);
            for (int i = 0; i < 10; i++) {
                point.applyEdit(com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.REMOVE_ENTRY, 0, "");
            }
            check(sb, "REMOVE_ENTRY 至少留 1 条（实际 " + point.entryCount() + " 条）",
                    point.entryCount() == 1);
            check(sb, "越界下标不会崩（entry(99) = " + point.entry(99) + "）", point.entry(99) == null);
            check(sb, "越界下标的编辑被拒绝", !point.applyEdit(
                    com.guardianprotocol.block.SpawnPointBlockEntity.EditOp.ENTRY_COUNT_UP, 99, ""));

            // ---- 11) 界面布局几何：不打开客户端也能验的那一半 ----
            // 连续两轮反馈的都是界面问题（下拉框压不住底下的控件 / id 与中文名叠字）。
            // 这些是纯算术，而 SpawnPointScreen 是 @OnlyIn(Dist.CLIENT)、专用服务器根本不加载，
            // 所以算术被挪进了双端都加载的 SpawnPointLayout，断言打在这里。
            // ★ 这些断言刻意不去复述实现：验的是「两列会不会撞」「哪几个控件该被盖住」这类
            //   关于结果的命题，而不是「函数返回了什么」。
            java.util.List<String> geomBad = new java.util.ArrayList<>();
            int ddW = com.guardianprotocol.menu.SpawnPointLayout.dropdownW();
            int nameCap = com.guardianprotocol.menu.SpawnPointLayout.nameMax(ddW);
            int idX0 = com.guardianprotocol.menu.SpawnPointLayout.ID_DY_X;

            // 11a) 两列<b>永不</b>重叠：把「名字宽度」从 0 扫到比面板还宽（含极端值），
            //      按客户端的口径（先截断到 nameMax 再排版）逐一验「id 列尾 + 空隙 ≤ 名字列头」。
            for (int nameW = 0; nameW <= ddW + 100; nameW++) {
                int shown = Math.min(nameW, nameCap);
                int idMax = com.guardianprotocol.menu.SpawnPointLayout.idMax(0, ddW, shown);
                int nameX = com.guardianprotocol.menu.SpawnPointLayout.nameX(0, ddW, shown);
                // id 一定被截断到 idMax（最坏情况：id 长到顶满），所以右端就是 idX0 + idMax
                if (idX0 + idMax + com.guardianprotocol.menu.SpawnPointLayout.GAP > nameX) {
                    geomBad.add("名字宽 " + nameW + " 时两列重叠");
                }
            }
            check(sb, "★ 候选两列永不重叠（扫了 0~" + (ddW + 100) + " 像素的名字宽度，实际 "
                    + geomBad.size() + " 处重叠：" + geomBad + "）", geomBad.isEmpty());
            check(sb, "★ 长名字被截断（nameMax=" + nameCap + " < 面板内宽 " + ddW + "），截断后 id 列仍有 "
                    + com.guardianprotocol.menu.SpawnPointLayout.MIN_ID_WIDTH + " 像素",
                    nameCap < ddW
                            && com.guardianprotocol.menu.SpawnPointLayout.idMax(0, ddW, nameCap)
                            >= com.guardianprotocol.menu.SpawnPointLayout.MIN_ID_WIDTH);

            // 11b) 行数被「候选条数 / 上限 / 面板下沿」三者夹住，绝不越出面板
            boolean fitOk = true;
            StringBuilder fitWhy = new StringBuilder();
            for (int row = 0; row < 4; row++) {
                for (int cand = 0; cand <= 10; cand++) {
                    int lines = com.guardianprotocol.menu.SpawnPointLayout.dropdownLines(row, cand);
                    boolean ok = lines <= Math.min(cand, com.guardianprotocol.menu.SpawnPointLayout.MAX_LINES)
                            && com.guardianprotocol.menu.SpawnPointLayout.dropdownBottom(row, lines)
                            + com.guardianprotocol.menu.SpawnPointLayout.BORDER
                            <= com.guardianprotocol.menu.SpawnPointLayout.PANEL_HEIGHT - 1
                            && (cand == 0 ? lines == 0 : lines > 0);
                    if (!ok) {
                        fitOk = false;
                        fitWhy.append(" 第").append(row).append("行/").append(cand).append("条→")
                                .append(lines).append("行");
                    }
                }
            }
            check(sb, "★ 下拉框永远不出面板（4 行 × 0~10 条全扫，越界处：" + fitWhy + "）", fitOk);

            // 11c) 覆盖判定的边界口径：差一像素，要么漏出底下的东西，要么误藏按钮
            int yId0 = com.guardianprotocol.menu.SpawnPointLayout.Y_FIRST_ENTRY
                    + com.guardianprotocol.menu.SpawnPointLayout.ROW_ID_DY;
            int yId1 = yId0 + com.guardianprotocol.menu.SpawnPointLayout.ENTRY_BLOCK;
            int yId2 = yId1 + com.guardianprotocol.menu.SpawnPointLayout.ENTRY_BLOCK;
            int yStep0 = com.guardianprotocol.menu.SpawnPointLayout.Y_FIRST_ENTRY
                    + com.guardianprotocol.menu.SpawnPointLayout.ROW_STEP_DY;
            int iw = com.guardianprotocol.menu.SpawnPointLayout.ID_WIDTH;
            int ih = com.guardianprotocol.menu.SpawnPointLayout.ID_HEIGHT;
            boolean covFocus = com.guardianprotocol.menu.SpawnPointLayout.coveredRect(
                    com.guardianprotocol.menu.SpawnPointLayout.ID_X, yId0, iw, ih, 0, 6);
            boolean covStep = com.guardianprotocol.menu.SpawnPointLayout.coveredRect(
                    com.guardianprotocol.menu.SpawnPointLayout.ID_X, yStep0, 20, ih, 0, 6);
            boolean covNext = com.guardianprotocol.menu.SpawnPointLayout.coveredRect(
                    com.guardianprotocol.menu.SpawnPointLayout.ID_X, yId1, iw, ih, 0, 6);
            boolean covThird = com.guardianprotocol.menu.SpawnPointLayout.coveredRect(
                    com.guardianprotocol.menu.SpawnPointLayout.ID_X, yId2, iw, ih, 0, 6);
            // 焦点那一行自己的输入框与「应用/删除」必须<b>不算</b>被盖 —— 否则打字后点不到「应用」
            check(sb, "★ 第 0 行开下拉时，它自己的输入框/应用/删除不算被盖（" + "输入框=" + covFocus + "）",
                    !covFocus);
            check(sb, "★ 同一行那排「数量 − 值 +」算被盖（截图里透出来的 0t 就是它）", covStep);
            check(sb, "★ 第 1 行整排算被盖（截图里透出来的 应用/删除/18t 就是它）", covNext);
            check(sb, "★ 第 2 行不受影响（下拉框只盖到第 1 行）", !covThird);
            // 边界：贴在下边框外侧一像素的控件不算被盖；压进边框一像素就算
            int justBelow = com.guardianprotocol.menu.SpawnPointLayout.dropdownBottom(0, 6)
                    + com.guardianprotocol.menu.SpawnPointLayout.BORDER;
            check(sb, "★ 边框外侧一像素不算被盖（差一像素就会误藏按钮）",
                    !com.guardianprotocol.menu.SpawnPointLayout.coveredRect(10, justBelow, 20, 16, 0, 6));
            check(sb, "★ 压进下边框一像素就算被盖（差一像素就会漏出内容）",
                    com.guardianprotocol.menu.SpawnPointLayout.coveredRect(10, justBelow - 1, 20, 16, 0, 6));
            check(sb, "★ 横向在面板右侧之外的不算被盖",
                    !com.guardianprotocol.menu.SpawnPointLayout.coveredRect(ddW + 40, yStep0, 20, 16, 0, 6));

            // 11d) 豁免粒度：只能豁免「正在打字的那个输入框」，绝不能整行豁免。
            //      上一版写成「这一行有输入框在打字 → 整行不藏」，于是那一行 6 个「−/+」按钮
            //      的灰盒子被黑底盖住、文字却浮在最上层，实机看到的就是「没有底的 − + 幽灵」。
            boolean granularOk = com.guardianprotocol.menu.SpawnPointLayout.shouldHide(true, false)
                    && !com.guardianprotocol.menu.SpawnPointLayout.shouldHide(true, true)
                    && !com.guardianprotocol.menu.SpawnPointLayout.shouldHide(false, false)
                    && !com.guardianprotocol.menu.SpawnPointLayout.shouldHide(false, true);
            check(sb, "★ 豁免粒度：被盖的按钮要藏、只有『正在打字的输入框』免藏（整行豁免会漏出 − + 幽灵）",
                    granularOk);
            check(sb, "★ 焦点那一行的按钮仍然算『该藏』（输入框本身永远落不到被盖区间里）",
                    com.guardianprotocol.menu.SpawnPointLayout.shouldHide(covStep, false)
                            && !com.guardianprotocol.menu.SpawnPointLayout.shouldHide(covFocus, true));

            // ---- 12) 调试出怪命令：注册、真的出得来、真的清得掉 ----
            // 设计口径：「客户端验不了出怪」—— 相位机没做、方块也不出兵。这条命令是唯一的验证通道，
            // 所以它自己更得先被验：**注册漏一个子命令**这类错在游戏外看不出来（敲下去只是「未知的命令」）。
            CommandDispatcher<CommandSourceStack> disp = level.getServer().getCommands().getDispatcher();
            com.mojang.brigadier.tree.CommandNode<CommandSourceStack> rootNode =
                    disp.getRoot().getChild("guardianprotocol");
            check(sb, "命令根 /guardianprotocol 已注册", rootNode != null);
            com.mojang.brigadier.tree.CommandNode<CommandSourceStack> spawnNode =
                    rootNode == null ? null : rootNode.getChild("spawn");
            check(sb, "子命令 /guardianprotocol spawn 已注册", spawnNode != null);
            check(sb, "spawn here <entity> [count] 三层参数都在",
                    spawnNode != null && spawnNode.getChild("here") != null
                            && spawnNode.getChild("here").getChild("entity") != null
                            && spawnNode.getChild("here").getChild("entity").getChild("count") != null);
            check(sb, "spawn at <x> <y> <z> <entity> [count] 都在",
                    spawnNode != null && spawnNode.getChild("at") != null
                            && spawnNode.getChild("at").getChild("x") != null
                            && spawnNode.getChild("at").getChild("x").getChild("y").getChild("z")
                            .getChild("entity").getChild("count") != null);
            check(sb, "spawn fire / fire n / fire at x y z [n] 都在",
                    spawnNode != null && spawnNode.getChild("fire") != null
                            && spawnNode.getChild("fire").getChild("n") != null
                            && spawnNode.getChild("fire").getChild("at").getChild("x")
                            .getChild("y").getChild("z").getChild("n") != null);
            check(sb, "spawn list / spawn clear 都在",
                    spawnNode != null && spawnNode.getChild("list") != null
                            && spawnNode.getChild("clear") != null);

            // 真的敲一遍（走 brigadier 解析，不是直接调方法）—— 顺带验「参数顺序/类型没写错」
            BlockPos here = flatSpotNear(level, base.getX(), base.getZ(), base.getY());
            CommandSourceStack console = level.getServer().createCommandSourceStack()
                    .withPosition(Vec3.atBottomCenterOf(here));
            int zombiesBefore = mobsNear(level, here, EntityType.ZOMBIE, 24.0D).size();
            int rcHere = -1;
            String cmdErr = "";
            try {
                rcHere = disp.execute("guardianprotocol spawn here minecraft:zombie 2", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            List<Entity> fromCmd = mobsNear(level, here, EntityType.ZOMBIE, 24.0D);
            check(sb, "★ 命令真的出了 2 只（原有 " + zombiesBefore + " 只，现在 " + fromCmd.size()
                    + " 只；返回 " + rcHere + " " + cmdErr + "）", fromCmd.size() - zombiesBefore == 2);
            int taggedCount = 0;
            for (Entity z : fromCmd) {
                if (z.getTags().contains(DEBUG_SPAWN_TAG)) {
                    taggedCount++;
                }
            }
            check(sb, "★ 生成的怪都带调试 tag = " + DEBUG_SPAWN_TAG + "（实际 " + taggedCount + " 只）",
                    taggedCount == 2);

            // 解析不出来时必须**明确失败**，而不是静默出猪（踩坑记录：Forge 未注册 id 返回默认值）
            int rcBad = -1;
            try {
                rcBad = disp.execute("guardianprotocol spawn here minecraft:not_a_mob 5", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            check(sb, "★ 乱写的 id 明确失败、返回 0（不静默出猪）", rcBad == 0);

            // spawn clear 只清带 tag 的
            int rcClear = -1;
            try {
                rcClear = disp.execute("guardianprotocol spawn clear", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            check(sb, "★ spawn clear 清了 2 只（返回 " + rcClear + "）", rcClear == 2);
            check(sb, "★ 清完之后附近没有僵尸了（实际 " + mobsNear(level, here, EntityType.ZOMBIE, 24.0D).size()
                    + " 只）", mobsNear(level, here, EntityType.ZOMBIE, 24.0D).isEmpty());

            // spawn fire：按出怪点配置出怪（忽略启用 / 延迟 / 间隔）
            // ★ 坐标必须用**方块自己的位置**（point.getBlockPos()）：fire 找的是「出怪点方块实体」，
            //   而不是随便一块空地 —— 第一次写这条断言时我传的是 `here`（另一块平地），
            //   命令如实回了「此处没有出怪点」，断言就红了。这个「红」是对的：命令没错，夹具错了。
            BlockPos pointAt = point.getBlockPos();
            CreatureRow fireRow = new CreatureRow();
            fireRow.setEntity("minecraft:zombie");
            fireRow.setSpawnCnt(3);
            point.invasion().rows().add(fireRow);
            point.invasion().clearPositions();
            point.invasion().addPosition(pointAt.above());
            point.invasion().setEnabled(false);
            // ★ 必须断言**增量**：本场景前面几节也在这个点附近出过僵尸（它们在 finally 里才清），
            //   直接数「附近有几只」会把它们一起数进来（第一次写这条就被这个坑咬了一口：3 只报成 6 只）。
            int zombiesBeforeFire = mobsNear(level, pointAt, EntityType.ZOMBIE, 24.0D).size();
            int rcFire = -1;
            try {
                rcFire = disp.execute("guardianprotocol spawn fire at " + pointAt.getX() + " "
                        + pointAt.getY() + " " + pointAt.getZ(), console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            int afterFire = mobsNear(level, pointAt, EntityType.ZOMBIE, 24.0D).size();
            check(sb, "★ fire 忽略「已关闭」照出 3 只（返回 " + rcFire + "，附近 " + zombiesBeforeFire
                    + " → " + afterFire + " 只）", afterFire - zombiesBeforeFire == 3);
            int rcFire2 = -1;
            try {
                rcFire2 = disp.execute("guardianprotocol spawn fire at " + pointAt.getX() + " "
                        + pointAt.getY() + " " + pointAt.getZ() + " n 1", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            int afterFire2 = mobsNear(level, pointAt, EntityType.ZOMBIE, 24.0D).size();
            check(sb, "★ fire ... n 1 覆盖数量：又出 1 只（" + afterFire + " → " + afterFire2 + " 只）",
                    afterFire2 - afterFire == 1);
            int rcFireBad = -1;
            try {
                rcFireBad = disp.execute("guardianprotocol spawn fire at 0 -1000 0", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            check(sb, "★ 对着没有出怪点的坐标 fire 会明确报错（返回 0）", rcFireBad == 0);
            cleanup.addAll(mobsNear(level, pointAt, EntityType.ZOMBIE, 24.0D));
            int rcClear2 = -1;
            try {
                rcClear2 = disp.execute("guardianprotocol spawn clear", console);
            } catch (Exception ex) {
                cmdErr = String.valueOf(ex.getMessage());
            }
            check(sb, "★ fire 出的怪同样能被 clear 清掉（清了 " + rcClear2 + " 只）", rcClear2 == 4);
            point.invasion().setEnabled(true);
            point.invasion().clearPositions();

        } finally {
            // ★ 先清怪、再拆方块：出怪点方块实体被移除后会从静态活跃表注销，
            //   顺序反过来虽然也能跑，但「先清生成物」这条顺序更不容易留尾巴。
            discardAll(cleanup);
            if (placed != null) {
                // isMoving=false：不搬运流体；removeBlock 本身也不掉落物品
                // —— 测试世界是设计的真实存档，连一个掉落物都不该留下。
                level.removeBlock(placed, false);
            }
            com.guardianprotocol.spawn.SpawnPointService.clearAll();
            check(sb, "拆掉后 activePoints() 不再含它",
                    placedPoint == null
                            || !com.guardianprotocol.spawn.SpawnPointService.activePoints().contains(placedPoint));
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个，出怪点方块已移除\n");
        }
    }

    /**
     * 治疗（医疗职业的出手改为治友方）的自验 —— 2026-10 第三轮。
     *
     * <h3>为什么分成「选择」与「结算」两段验</h3>
     * <ul>
     *   <li><b>选择</b>（这一手治谁）走**端到端**：摆好棋子 → 推一次心跳 → 读
     *       {@code debugLastTargets} 与 {@code debugSteps}。这样验的是真正的索敌路径，
     *       而不是我另写一份「应该选谁」的算术；</li>
     *   <li><b>结算</b>（治了多少、连锁几跳、内圈打折）直接调 {@link PawnCombatManager#applyHeal}：
     *       投掷物要飞几 tick 才落地，而治疗量是确定的 —— 把两件事混在一起，
     *       失败时就分不清「选错了」还是「还没飞到」。</li>
     * </ul>
     *
     * <p>被治疗的「友方」一律用**非医疗分支**的棋子（斗士）：它们不会互相治疗，
     * 也就不会把掉血/回血的账搅在一起。</p>
     */
    private static void scenarioHeal(StringBuilder sb, ServerLevel level, BlockPos base) {
        List<Entity> cleanup = new ArrayList<>();
        String section = "";
        try {
            // ---- 0) 数据层：7 个医疗分支各自的治疗规格（对照表原文写进 healSource）----
            check(sb, "医师 = 单体治疗",
                    UnitBranch.MEDIC_MEDIC.heal().kind() == UnitBranch.Heal.Kind.SINGLE);
            check(sb, "群愈师 = 范围内全体（★ 设计口径；对照表写「三个」，见 设计文档）",
                    UnitBranch.MEDIC_MULTI_TARGET.heal().kind() == UnitBranch.Heal.Kind.ALL);
            check(sb, "链愈师 = 连锁 3 目标、每跳 ×0.75（对照表原文）",
                    UnitBranch.MEDIC_CHAIN.heal().kind() == UnitBranch.Heal.Kind.CHAIN
                            && UnitBranch.MEDIC_CHAIN.heal().targets() == 3
                            && Math.abs(UnitBranch.MEDIC_CHAIN.heal().chainDecay() - 0.75D) < 1.0E-6D);
            check(sb, "疗养师 = 单体 + 内圈外 ×0.8（内圈取医师那一档的范围键，键="
                            + UnitBranch.MEDIC_THERAPIST.heal().innerRangeKey() + "）",
                    UnitBranch.MEDIC_THERAPIST.heal().farFactor() == 0.8D
                            && UnitBranch.MEDIC_THERAPIST.heal().hasInnerRange());
            check(sb, "行医 / 守望者 = 单体治疗",
                    UnitBranch.MEDIC_WANDERING.heal().kind() == UnitBranch.Heal.Kind.SINGLE
                            && UnitBranch.MEDIC_WATCHMAN.heal().kind() == UnitBranch.Heal.Kind.SINGLE);
            check(sb, "咒愈师 = 先打敌人、再治一名友方（伤害的 50%）",
                    UnitBranch.MEDIC_INCANTATION.heal().kind() == UnitBranch.Heal.Kind.POST_HIT
                            && UnitBranch.MEDIC_INCANTATION.heal().postHitFactor() == 0.5D);
            check(sb, "非医疗分支（斗士）不治疗",
                    UnitBranch.GUARD_FIGHTER.heal().kind() == UnitBranch.Heal.Kind.NONE);
            check(sb, "吟游者 = **被动**回血（不攻击，每 20 tick 给范围内全体回 10% 攻击力）",
                    UnitBranch.SUPPORTER_BARD.heal().kind() == UnitBranch.Heal.Kind.REGEN
                            && !UnitBranch.SUPPORTER_BARD.canAttack()
                            && UnitBranch.SUPPORTER_BARD.heal().regenInterval() == 20
                            && Math.abs(UnitBranch.SUPPORTER_BARD.heal().regenFactor() - 0.10D) < 1.0E-6D);

            // ★★ 每个用例**一块自己的场地**（相隔 16 格），跑完立刻清场。
            //    第一版没这么做：上一个用例的伤员还站在旁边，于是「满血不出手」查到的是上一轮的
            //    5 血友方、「群愈师全体」数到了 5 个、「连锁」跳到了别处的目标 ——
            //    8 条 FAIL 全是夹具串场，实现其实是对的（同踩坑记录那一族：夹具污染下一个用例）。
            //    ★ 另外**不**断言 debugSteps()：它是全服共享的一个 volatile，
            //      同一 tick 里旁边的斗士棋子也会写它（「候选为空（只打被挡住的）」就是这么读到的）。
            //      把全局追踪当成「这个棋子的追踪」正是踩坑记录那一族的错误 —— 这里只断言行为。

            // ---- 1) 选择：单体治疗挑「同一检测时段血量最低的」 ----
            section = "医师（单体，挑血量最低）";
            List<Entity> c1 = new ArrayList<>();
            PixelUnit medic = spawnPawn(level, caseSpot(level, base, 0), UnitBranch.MEDIC_MEDIC);
            c1.add(medic);
            PixelUnit hurtMore = allyPawn(level, c1, medic, 1, 0, 5.0F);
            PixelUnit hurtLess = allyPawn(level, c1, medic, 0, 1, 20.0F);
            float atk = (float) medic.getBranch().attackDamage();
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> picked = PawnCombatManager.debugLastTargets(medic);
            sb.append("   医师选中：").append(picked.size()).append(" 名（").append(desc(picked))
                    .append("）；攻击值 ").append(r2(atk)).append('\n');
            check(sb, section + "：只选一名", picked.size() == 1);
            check(sb, "★ " + section + "：选中的是**血量最低**那只（" + r2(hurtMore.getHealth())
                            + " 血），不是 " + r2(hurtLess.getHealth()) + " 血那只",
                    picked.size() == 1 && picked.get(0) == hurtMore);
            check(sb, section + "：出手变成了**治疗弹**（投掷物在飞）",
                    !projectilesOwnedBy(level, medic).isEmpty());
            float healed = healDirectly(level, medic, hurtMore, atk);
            check(sb, section + "：治疗量 = 攻击值（回复 " + r2(healed) + "）",
                    Math.abs(healed - atk) < 0.05F);
            discardAll(c1);

            // ---- 2) 全都在满血时不出手（本 mod 口径）----
            section = "医师（全员满血不出手）";
            List<Entity> c2 = new ArrayList<>();
            PixelUnit medic2 = spawnPawn(level, caseSpot(level, base, 1), UnitBranch.MEDIC_MEDIC);
            c2.add(medic2);
            allyPawn(level, c2, medic2, 1, 0, -1.0F);   // -1 = 显式置满血
            allyPawn(level, c2, medic2, 0, 1, -1.0F);
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "★ " + section + "：一个目标都没选（没浪费这次冷却）",
                    PawnCombatManager.debugLastTargets(medic2).isEmpty());
            check(sb, section + "：也没有发射治疗弹（按主人过滤）",
                    projectilesOwnedBy(level, medic2).isEmpty());
            discardAll(c2);

            // ---- 3) 群愈师：范围内全体各治一次（设计口径）----
            section = "群愈师（范围内全体）";
            List<Entity> c3 = new ArrayList<>();
            PixelUnit group = spawnPawn(level, caseSpot(level, base, 2), UnitBranch.MEDIC_MULTI_TARGET);
            c3.add(group);
            List<PixelUnit> wounded = new ArrayList<>();
            wounded.add(allyPawn(level, c3, group, 1, 0, 5.0F));
            wounded.add(allyPawn(level, c3, group, 0, 1, 8.0F));
            wounded.add(allyPawn(level, c3, group, 1, 1, 12.0F));
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> got3 = PawnCombatManager.debugLastTargets(group);
            sb.append("   群愈师选中 ").append(got3.size()).append(" 名（").append(desc(got3))
                    .append("）\n");
            check(sb, "★ " + section + "：一名出手选中了 3 个目标（实际 " + got3.size() + "）",
                    got3.size() == 3);
            float gAtk = (float) group.getBranch().attackDamage();
            int healedCount = 0;
            for (PixelUnit ally : wounded) {
                if (healDirectly(level, group, ally, gAtk) > 0.0F) {
                    healedCount++;
                }
            }
            check(sb, section + "：三只都治到了（治到 " + healedCount + " 只）", healedCount == 3);
            discardAll(c3);

            // ---- 4) 链愈师：3 目标、每跳 ×0.75 ----
            section = "链愈师（连锁 3 目标，每跳 ×0.75）";
            List<Entity> c4 = new ArrayList<>();
            PixelUnit chain = spawnPawn(level, caseSpot(level, base, 3), UnitBranch.MEDIC_CHAIN);
            c4.add(chain);
            // ★ 摆在**该分支自己的范围格**里：链愈师的治疗范围跟医师不一样（模板干员不同），
            //   第一版按「正前方 1~4 格」摆，结果 2~4 格落在范围外 —— 连锁只跳到 1 个。
            //   范围是数据，夹具就得**问数据要位置**（placeAlliesInRange），不能凭想象摆。
            List<PixelUnit> chainAllies = placeAlliesInRange(level, c4, chain, 4, 5.0F);
            float cAtk = (float) chain.getBranch().attackDamage();
            PawnCombatManager.debugResetHealCounters();
            float[] before = new float[chainAllies.size()];
            for (int i = 0; i < chainAllies.size(); i++) {
                before[i] = chainAllies.get(i).getHealth();
            }
            // 起点固定为列表第一个（他就是「血量最低那名」，四只同血、按距离取最近）
            PawnCombatManager.applyHeal(level, chain, chainAllies.get(0), cAtk);
            float[] gain = new float[chainAllies.size()];
            for (int i = 0; i < chainAllies.size(); i++) {
                gain[i] = chainAllies.get(i).getHealth() - before[i];
            }
            // ★★ 断言必须**与顺序无关**：连锁的后续跳是「从上一个目标出发找最近的没治过的」——
            //    那是一条**贪心最近邻路径**，不一定等于我摆放的顺序。第一版按列表下标断言，
            //    结果实测 10.00 / 7.50 / 0.00 / 5.63（第 3 跳落到了列表第 4 只）就红了 ——
            //    而实现完全正确。所以这里改成「排序后逐个比」：治到 3 只、三个量正好是
            //    ×1 / ×0.75 / ×0.5625，第四只 0。
            java.util.Arrays.sort(gain);
            float g3 = gain[3];          // 最大（第一个目标）
            float g2 = gain[2];
            float g1 = gain[1];
            float g0 = gain[0];          // 最小（该是 0：四只里只治了三只）
            sb.append("   连锁回复量（降序）：").append(r2(g3)).append(" / ").append(r2(g2))
                    .append(" / ").append(r2(g1)).append(" / ").append(r2(g0))
                    .append("（攻击值 ").append(r2(cAtk)).append("）\n");
            check(sb, "★ " + section + "：一共治到 3 个（观测 "
                    + PawnCombatManager.debugLastChainHeals() + "）",
                    PawnCombatManager.debugLastChainHeals() == 3);
            check(sb, section + "：四只里恰好三只被治到（没被治到的那只 = " + r2(g0) + "）",
                    g0 == 0.0F && g1 > 0.0F);
            check(sb, "★ " + section + "：第一跳 = 攻击值（" + r2(g3) + "）",
                    Math.abs(g3 - Math.min(cAtk, 17.0F)) < 0.05F);
            check(sb, "★ " + section + "：第二跳 ×0.75（" + r2(g2) + "，期望 " + r2(g3 * 0.75F) + "）",
                    Math.abs(g2 - g3 * 0.75F) < 0.05F);
            check(sb, section + "：第三跳再 ×0.75（" + r2(g1) + "，期望 " + r2(g2 * 0.75F) + "）",
                    Math.abs(g1 - g2 * 0.75F) < 0.05F);
            // ★ 斜角也要能连（2026-10 设计口径）＋「链」的连线看得见：
            //   四只摆在棋子的**四个正交邻格**上 ⇒ 从第一跳出发，「周围 8 格」里只剩**斜角**那两只
            //   （另两只正交邻格与它相距 2 格）⇒「第二跳拿到 ×0.75」这件事本身就证明斜角跳成立；
            //   这里把它显式钉住，并顺手钉住「画了 2 段连线」（连线是表现，只能靠它自己记的那一笔）。
            BlockPos firstAt = chainAllies.get(0).blockPosition();
            int hopDx = 0;
            int hopDz = 0;
            for (int i = 0; i < chainAllies.size(); i++) {
                if (i == 0 || Math.abs(gain[i] - g2) > 0.05F) {
                    continue;
                }
                BlockPos p = chainAllies.get(i).blockPosition();
                hopDx = p.getX() - firstAt.getX();
                hopDz = p.getZ() - firstAt.getZ();
            }
            sb.append("   第二跳落点相对第一跳：dx=").append(hopDx).append(" dz=").append(hopDz).append('\n');
            check(sb, "★ " + section + "：第二跳走的是**斜角**（dx=±1 且 dz=±1）—— 「周围 8 格」含斜角，不是只连正交",
                    Math.abs(hopDx) == 1 && Math.abs(hopDz) == 1);
            check(sb, "★ " + section + "：连锁画了 " + PawnCombatManager.debugLastChainLinks()
                            + " 段连线（2 跳 = 2 段）",
                    PawnCombatManager.debugLastChainLinks() == 2);
            discardAll(c4);

            // ---- 5) 疗养师：内圈全额、最远格 ×0.8 ----
            section = "疗养师（内圈外 ×0.8）";
            List<Entity> c5 = new ArrayList<>();
            PixelUnit ther = spawnPawn(level, caseSpot(level, base, 4), UnitBranch.MEDIC_THERAPIST);
            c5.add(ther);
            PixelUnit near = placeAlliesInRange(level, c5, ther, 1, 5.0F).get(0);
            BlockPos farCell = farthestCell(ther);
            PixelUnit far = spawnPawn(level, new BlockPos(farCell.getX(), ther.getBlockY(),
                    farCell.getZ()), UnitBranch.GUARD_FIGHTER);
            c5.add(far);
            far.setHealth(5.0F);
            float tAtk = (float) ther.getBranch().attackDamage();
            float nearBefore = near.getHealth();
            float farBefore = far.getHealth();
            float nearGain = healDirectly(level, ther, near, tAtk);
            float farGain = healDirectly(level, ther, far, (float) (tAtk * 0.8D));
            sb.append("   内圈目标 ").append(r2(nearBefore)).append("→").append(r2(near.getHealth()))
                    .append("；最远格 ").append(farCell.toShortString()).append(' ')
                    .append(r2(farBefore)).append("→").append(r2(far.getHealth()))
                    .append("（攻击值 ").append(r2(tAtk)).append("）\n");
            check(sb, "★ " + section + "：内圈全额（回复 " + r2(nearGain) + "，期望 "
                            + r2(Math.min(tAtk, near.getMaxHealth() - nearBefore)) + "）",
                    Math.abs(nearGain - Math.min(tAtk, near.getMaxHealth() - nearBefore)) < 0.05F);
            check(sb, "★ " + section + "：最远那格按 80%（回复 " + r2(farGain) + "，期望 "
                            + r2(Math.min(tAtk * 0.8F, far.getMaxHealth() - farBefore)) + "）",
                    Math.abs(farGain - Math.min(tAtk * 0.8F, far.getMaxHealth() - farBefore)) < 0.05F);
            discardAll(c5);

            // ---- 6) 咒愈师：先打敌人，再把伤害的 50% 治给一名友方 ----
            section = "咒愈师（打完敌人再治友方）";
            List<Entity> c6 = new ArrayList<>();
            BlockPos spot6 = caseSpot(level, base, 5);
            PixelUnit incan = spawnPawn(level, spot6, UnitBranch.MEDIC_INCANTATION);
            c6.add(incan);
            PixelUnit patient = allyPawn(level, c6, incan, 1, 0, 5.0F);
            Zombie victim = EntityType.ZOMBIE.create(level);
            level.addFreshEntity(victim);
            victim.setPos(spot6.getX() + 0.5D, spot6.getY(), spot6.getZ() + 3.5D);
            setArmor(victim, 0.0D);
            makeTanky(victim, 5000.0D);
            victim.setNoAi(true);
            c6.add(victim);
            float patientBefore = patient.getHealth();
            float victimBefore = victim.getHealth();
            PawnCombatManager.debugResetHealCounters();
            PawnCombatManager.applyHit(level, incan, victim, 20.0F, PawnCombatManager.HitKind.MELEE);
            float dealt = victimBefore - victim.getHealth();
            float cured = patient.getHealth() - patientBefore;
            sb.append("   对敌伤害 ").append(r2(dealt)).append(" → 友方回复 ").append(r2(cured))
                    .append("（50% = ").append(r2(dealt * 0.5F)).append("）\n");
            check(sb, section + "：敌人确实挨了打（" + r2(dealt) + "）", dealt > 0.0F);
            check(sb, "★ " + section + "：友方被治了伤害的 50%（实际 " + r2(cured) + "）",
                    Math.abs(cured - Math.min(dealt * 0.5F,
                            patient.getMaxHealth() - patientBefore)) < 0.05F);
            check(sb, section + "：观测到「打完顺手治了」标记（"
                    + PawnCombatManager.debugLastPostHitHeal() + "）",
                    PawnCombatManager.debugLastPostHitHeal() == 1);
            discardAll(c6);

            // ---- 7) 吟游者：**被动**回血 —— 不攻击，每 20 tick 给范围内全体回 10% 攻击力 ----
            section = "吟游者（被动回血，每秒 10% 攻击力）";
            List<Entity> c7 = new ArrayList<>();
            PixelUnit bard = spawnPawn(level, caseSpot(level, base, 6), UnitBranch.SUPPORTER_BARD);
            c7.add(bard);
            List<PixelUnit> bardAllies = placeAlliesInRange(level, c7, bard, 2, 5.0F);
            StringBuilder cellDump = new StringBuilder();
            for (AttackRange.Cell c : bard.worldCells()) {
                cellDump.append(c.x()).append(',').append(c.z()).append(' ');
            }
            sb.append("   吟游者范围格 ").append(bard.worldCells().size()).append(" 个：")
                    .append(cellDump).append('\n');
            sb.append("   范围扫描看见的友方数 = ").append(PawnCombatManager.debugAlliesInRange(bard))
                    .append('\n');
            for (PixelUnit ally : bardAllies) {
                sb.append("   友方 ").append(ally.blockPosition().toShortString())
                        .append("（相对棋子 ").append(ally.blockPosition().getX() - bard.blockPosition().getX())
                        .append(',').append(ally.blockPosition().getZ() - bard.blockPosition().getZ())
                        .append("）血 ").append(r2(ally.getHealth())).append('\n');
            }
            float bAtk = (float) bard.getBranch().attackDamage();
            float perPulse = bAtk * 0.10F;
            bard.setHealth(bard.getMaxHealth());      // 自己满血：验「只回不满血的」
            float a0 = bardAllies.get(0).getHealth();
            float a1 = bardAllies.get(1).getHealth();
            PawnCombatManager.debugResetHealCounters();
            // ★★ 断言必须**与相位无关**：节拍用的是本 mod 的**全局** tickCounter，
            //    「第 20 tick」并不等于「这个棋子出生后第 20 tick」—— 第一版按后者写，
    //    结果前 19 tick 里就已经回了一次（相位早在别的用例里走过了）。
            //    40 tick 的窗口里**恰好**有两个 20 的倍数 ⇒ 必然是 2 次脉冲，与起点无关。
            for (int t = 0; t < 40; t++) {
                PawnCombatManager.onServerTick(level.getServer());
            }
            float g0b = bardAllies.get(0).getHealth() - a0;
            float g1b = bardAllies.get(1).getHealth() - a1;
            float perSecond = perPulse * 2.0F;      // 40 tick = 2 秒 ⇒ 两次脉冲
            sb.append("   攻击值 ").append(r2(bAtk)).append(" → 每秒 ").append(r2(perPulse))
                    .append("；40 tick（2 秒）内两名友军各回 ").append(r2(g0b)).append(" / ")
                    .append(r2(g1b)).append("（期望 ").append(r2(perSecond)).append("）\n");
            check(sb, "★ " + section + "：40 tick 内恰好 2 次脉冲（两次各 10% 攻击力 ⇒ 共 "
                            + r2(perSecond) + "；若是每 tick 回血这里会是 " + r2(perPulse * 40) + "）",
                    Math.abs(g0b - perSecond) < 0.05F && Math.abs(g1b - perSecond) < 0.05F);
            check(sb, "★ " + section + "：满血的自己一次都没被治（它是满血，被跳过）",
                    Math.abs(bard.getHealth() - bard.getMaxHealth()) < 0.01F);
            check(sb, section + "：它不出手（没有选中任何敌人，也没发射弹体）",
                    PawnCombatManager.debugLastTargets(bard).isEmpty()
                            && projectilesOwnedBy(level, bard).isEmpty());
            discardAll(c7);

            // ---- 8) 归属与队伍：A 摆的 = A 队、B 摆的 = B 队，但同属玩家阵营 ----
            // 设计口径：「将玩家摆放的棋子统一挂到玩家所属下视为同一队……
            // 不同玩家放的棋子在不同玩家所属下，但不为同一队只算同一阵营」＋
            // 「棋子类似于被驯服的生物，也是『有主』的」。
            section = "归属与队伍（A 队 / B 队 / 玩家阵营）";
            List<Entity> c8 = new ArrayList<>();
            String ownerA = "11111111-2222-3333-4444-555555555555";
            String ownerB = "99999999-8888-7777-6666-555555555555";
            PixelUnit medicA = spawnPawn(level, caseSpot(level, base, 7), UnitBranch.MEDIC_MEDIC);
            c8.add(medicA);
            com.guardianprotocol.combat.PawnTeams.assign(level, medicA, ownerA, "玩家A");
            // A 的伤员 + B 的伤员，两只都在 A 的医疗范围内、都缺 17 点血。
            // ★ 2026-10 修掉一处**抽奖式夹具**（踩坑记录那一族）：原来分两次调 placeAlliesInRange，
            //   而它**不知道格子已经被占** —— 第二次会把两名干员摆在「与第一次相同的最近两格」，
            //   于是两枚棋子叠在同一格里互相推挤（一枚 PUSH_OWN_TEAM 的队内成员 + 一枚无队的
            //   ALWAYS），**谁被挤出范围格是随机的** ⇒ 实测出现过「A 的医疗选中为空」这种**假红**
            //   （实现没问题：同一只伤员 healDirectly 结算 +2.00）。现在**一次要 3 格**，
            //   取第 1、3 格分别当 A / B 的伤员，格子互不重叠；中间那格的无主伤员不参与断言
            //   （队不同 ⇒ 治不到）。
            List<PixelUnit> three = placeAlliesInRange(level, c8, medicA, 3, 5.0F);
            PixelUnit hurtA = three.get(0);
            com.guardianprotocol.combat.PawnTeams.assign(level, hurtA, ownerA, "玩家A");
            PixelUnit hurtB = three.get(2);
            com.guardianprotocol.combat.PawnTeams.assign(level, hurtB, ownerB, "玩家B");
            String teamA = com.guardianprotocol.combat.PawnTeams.teamNameOf(medicA);
            String teamB = com.guardianprotocol.combat.PawnTeams.teamNameOf(hurtB);
            sb.append("   A 的棋子队伍 ").append(teamA).append("；B 的棋子队伍 ").append(teamB)
                    .append("；同队? ").append(com.guardianprotocol.combat.PawnTeams
                            .isSameTeam(medicA, hurtA))
                    .append("；A/B 同队? ").append(com.guardianprotocol.combat.PawnTeams
                            .isSameTeam(medicA, hurtB)).append('\n');
            check(sb, "★ " + section + "：有主棋子进了「按主人算」的队伍（A=" + teamA + "，B=" + teamB + "）",
                    teamA != null && teamB != null && !teamA.equals(teamB));
            check(sb, "★ " + section + "：同一玩家摆的棋子是同队",
                    com.guardianprotocol.combat.PawnTeams.isSameTeam(medicA, hurtA));
            check(sb, "★ " + section + "：不同玩家摆的棋子**不是**同队",
                    !com.guardianprotocol.combat.PawnTeams.isSameTeam(medicA, hurtB));
            net.minecraft.world.scores.Team tA = level.getScoreboard().getPlayerTeam(teamA);
            net.minecraft.world.scores.Team tB = level.getScoreboard().getPlayerTeam(teamB);
            check(sb, "★ " + section + "：两队同属**玩家阵营**（阵营由本 mod 显式判定："
                            + "不分队都是 PLAYER；不依赖原版 isAlliedTo 的实现细节）",
                    com.guardianprotocol.combat.PawnTeams.isSameSide(medicA, hurtB)
                            && com.guardianprotocol.combat.PawnTeams.sideOf(medicA)
                            == com.guardianprotocol.combat.PawnTeams.Side.PLAYER);
            check(sb, section + "：队伍颜色统一（让原版也把玩家棋子当同盟看，只是顺手）",
                    tA != null && tB != null && tA.getColor() == tB.getColor()
                            && tA.getColor() == com.guardianprotocol.combat.PawnTeams.SIDE_COLOR);
            // 行为：A 的医疗只挑 A 的伤员，B 的伤员不许被治
            // ★ 先让**夹具自证**（踩坑记录的规矩）：范围扫描真的看得见那名同队伤员，
            //   后面的「选中为空」才可能被判成功能问题，而不是夹具把伤员摆丢了。
            int seenA = PawnCombatManager.debugAlliesInRange(medicA);
            check(sb, section + "：夹具成立 —— A 的医疗范围内看得见 " + seenA + " 名同队伤员（应为 1）",
                    seenA == 1);
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> picked8 = PawnCombatManager.debugLastTargets(medicA);
            check(sb, "★ " + section + "：A 的医疗只选中 A 的伤员（选中 "
                            + desc(picked8) + "）",
                    picked8.size() == 1 && picked8.get(0) == hurtA);
            float beforeB = hurtB.getHealth();
            float gainA = healDirectly(level, medicA, hurtA, (float) medicA.getBranch().attackDamage());
            float gainB = healDirectly(level, medicA, hurtB, (float) medicA.getBranch().attackDamage());
            sb.append("   手动对两只各治一次：A 的伤员 +").append(r2(gainA))
                    .append("，B 的伤员 +").append(r2(gainB)).append('\n');
            check(sb, "★ " + section + "：B 的伤员**治不动**（+0.00，队不同）",
                    gainB == 0.0F && Math.abs(hurtB.getHealth() - beforeB) < 0.001F);
            // 怪永远不治：范围内放一只僵尸，确认它一点血都没回
            Zombie mobInRange = EntityType.ZOMBIE.create(level);
            level.addFreshEntity(mobInRange);
            mobInRange.setPos(medicA.getX() + 1.5D, medicA.getY(), medicA.getZ() + 0.5D);
            mobInRange.setNoAi(true);
            makeTanky(mobInRange, 5000.0D);
            mobInRange.setHealth(100.0F);
            c8.add(mobInRange);
            float mobBefore = mobInRange.getHealth();
            PawnCombatManager.applyHeal(level, medicA, mobInRange,
                    (float) medicA.getBranch().attackDamage());
            check(sb, "★ " + section + "：怪不会被治（僵尸 " + r2(mobBefore) + " → "
                            + r2(mobInRange.getHealth()) + "）",
                    Math.abs(mobInRange.getHealth() - mobBefore) < 0.001F);
            discardAll(c8);

            // ---- 9) ★ 自疗：医疗能治自己（2026-10 实测提问「医疗怎么不治疗自身」后补的口径）----
            //   出处（唯一一处）：arknights.wiki.gg 的 Attribute/HP → Healing 逐字 ——
            //   「Units capable of healing can usually heal themselves, unless their healing
            //    ability explicitly states that it excludes the user themselves, such as
            //    Blemishine's Divine Avatar.」
            //   本 mod 七个医疗分支的特性原文（PRTS）都没有排除自己；凯尔希天赋原文更写着
            //   「优先治疗**自身**和Mon3tr」。旧实现里 alliesInRange 的 `e != unit` 是一条
            //   **没有出处**的排除（踩坑记录）。
            //   ★ 这里刻意分两层断言：「选中了自己」与「结算时真的治得上自己」是两件事 ——
            //     后者还要过 applyHeal 里那道 isFriendly 闸门（它也是「只治同队」的唯一实现）。
            section = "自疗（医师，孤身）";
            PixelUnit solo = spawnPawn(level, caseSpot(level, base, 0), UnitBranch.MEDIC_MEDIC);
            cleanup.add(solo);
            float soloMax = solo.getMaxHealth();
            int neighbours = PawnCombatManager.debugAlliesInRange(solo);
            check(sb, section + "：夹具成立 —— 孤身一人（范围内其它友方 " + neighbours + " 名，场上无其它棋子）",
                    neighbours == 0);
            // 对照①：满血 ⇒ 连自己也不治（本项目口径「只治不满血的」）
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> fullPick = PawnCombatManager.debugLastTargets(solo);
            check(sb, section + "：对照·满血时不出手（选中 " + desc(fullPick) + "）", fullPick.isEmpty());
            // 主断言：受伤 ⇒ 把自己选成治疗目标
            solo.setHealth(soloMax * 0.5F);
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> selfPick = PawnCombatManager.debugLastTargets(solo);
            sb.append("   孤身医疗（").append(r2(solo.getHealth())).append('/').append(r2(soloMax))
                    .append(" 血）选中：").append(desc(selfPick))
                    .append("；攻击值 ").append(r2((float) solo.getBranch().attackDamage())).append('\n');
            check(sb, "★ " + section + "：孤身受伤时**选中自己**（" + desc(selfPick) + "）",
                    selfPick.size() == 1 && selfPick.get(0) == solo);
            float selfGain = healDirectly(level, solo, solo, (float) solo.getBranch().attackDamage());
            check(sb, "★ " + section + "：结算点也治得上自己（回复 " + r2(selfGain)
                            + "；过的是 applyHeal 的 isFriendly 闸门）",
                    selfGain > 0.0F);

            // ---- 9b) 群愈师：自己也在「范围内全体」里 ----
            section = "自疗（群愈师全体含自身）";
            PixelUnit groupSelf = spawnPawn(level, caseSpot(level, base, 2), UnitBranch.MEDIC_MULTI_TARGET);
            cleanup.add(groupSelf);
            PixelUnit groupAlly = allyPawn(level, cleanup, groupSelf, 1, 0, 5.0F);
            groupSelf.setHealth(groupSelf.getMaxHealth() * 0.5F);
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> groupPick = PawnCombatManager.debugLastTargets(groupSelf);
            sb.append("   群愈师选中 ").append(groupPick.size()).append(" 名：").append(desc(groupPick)).append('\n');
            check(sb, "★ " + section + "：选中 " + groupPick.size() + " 名里**含自己与友方**",
                    groupPick.contains(groupSelf) && groupPick.contains(groupAlly));

            // ---- 9c) 链愈师：自己血量最低 ⇒ 第一跳是自己（后续跳仍只在友方里找）----
            section = "自疗（链愈师第一跳）";
            PixelUnit chainSelf = spawnPawn(level, caseSpot(level, base, 3), UnitBranch.MEDIC_CHAIN);
            cleanup.add(chainSelf);
            List<PixelUnit> chainAlliesSelf = placeAlliesInRange(level, cleanup, chainSelf, 2, 15.0F);
            chainSelf.setHealth(3.0F);
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> chainPick = PawnCombatManager.debugLastTargets(chainSelf);
            sb.append("   链愈师（自己 3.0 血，友方 ").append(chainAlliesSelf.size()).append(" 名各 15.0 血）第一跳：")
                    .append(desc(chainPick)).append('\n');
            check(sb, "★ " + section + "：第一跳是血量最低的**自己**（" + desc(chainPick) + "）",
                    chainPick.size() == 1 && chainPick.get(0) == chainSelf);

            // ---- 9d) 咒愈师：自己也参与「血量最低」的挑选（且是血量、不是距离）----
            //   这条同时钉住一处顺手修掉的名不符实：旧实现按**距离**挑，自己距离恒为 0 ⇒ 永远抢不到。
            section = "自疗（咒愈师按血量挑）";
            List<Entity> c9 = new ArrayList<>();
            BlockPos spot9 = caseSpot(level, base, 5);
            PixelUnit incan2 = spawnPawn(level, spot9, UnitBranch.MEDIC_INCANTATION);
            c9.add(incan2);
            cleanup.add(incan2);
            PixelUnit nearPatient = allyPawn(level, c9, incan2, 1, 0, 16.0F);   // 更近但血更多
            cleanup.add(nearPatient);
            Zombie victim2 = EntityType.ZOMBIE.create(level);
            level.addFreshEntity(victim2);
            victim2.setPos(spot9.getX() + 0.5D, spot9.getY(), spot9.getZ() + 3.5D);
            setArmor(victim2, 0.0D);
            makeTanky(victim2, 5000.0D);
            victim2.setNoAi(true);
            cleanup.add(victim2);
            incan2.setHealth(4.0F);                       // 自己最低血（友方 16.0）
            float nearBeforeSelf = nearPatient.getHealth();
            float selfBefore = incan2.getHealth();
            float victim2Before = victim2.getHealth();
            PawnCombatManager.debugResetHealCounters();
            PawnCombatManager.applyHit(level, incan2, victim2, 20.0F, PawnCombatManager.HitKind.MELEE);
            float selfCured = incan2.getHealth() - selfBefore;
            float nearCured = nearPatient.getHealth() - nearBeforeSelf;
            sb.append("   敌伤 ").append(r2(victim2Before - victim2.getHealth()))
                    .append(" → 自己回复 ").append(r2(selfCured))
                    .append("、更近的友方回复 ").append(r2(nearCured)).append('\n');
            check(sb, "★ " + section + "：治的是血量更低的**自己**（自己 +" + r2(selfCured)
                            + "，更近的友方 +" + r2(nearCured) + "）",
                    selfCured > 0.0F && nearCured == 0.0F);
            discardAll(c9);

            // ---- 9e) 链愈师跳法（原作口径，2026-10 修正）：周围 8 格 / 最低血优先 / 可跳满血 / 能治到范围外 ----
            //   出处：wiki.gg Medic/Chain_Medic —— "jumps to another friendly unit **in the
            //   surrounding 8 tiles of the target**, prioritizing those with **the lowest HP** ...
            //   and **can jump to those with full HP**"；同页 Strategies 还有一句
            //   "Chain Medics can heal operators **outside their usual range**"。
            section = "链愈师跳法（周围 8 格 / 最低血 / 可跳满血 / 能治到范围外）";
            List<Entity> c10 = new ArrayList<>();
            PixelUnit cjMedic = spawnPawn(level, caseSpot(level, base, 5), UnitBranch.MEDIC_CHAIN);
            c10.add(cjMedic);
            cleanup.add(cjMedic);
            BlockPos cjOrigin = cjMedic.blockPosition();
            List<AttackRange.Cell> cjCells = cjMedic.worldCells();
            // 第一跳的目标：取一个**在范围内、最靠边**的格 —— 这样「再往外一格」必定落在范围之外
            AttackRange.Cell edge = null;
            for (AttackRange.Cell c : cjCells) {
                if (c.x() == 0 && c.z() == 0) {
                    continue;
                }
                if (edge == null || Math.abs(c.x()) > Math.abs(edge.x())) {
                    edge = c;
                }
            }
            int away = edge.x() >= 0 ? 1 : -1;
            PixelUnit hopFirst = spawnPawn(level, cjOrigin.offset(edge.x(), 0, edge.z()),
                    UnitBranch.GUARD_FIGHTER);
            cleanup.add(hopFirst);
            hopFirst.setHealth(5.0F);
            // ② 范围外那只：与第一跳**斜角**相邻（既验「斜角也连」，又验「能治到范围外」）
            PixelUnit hopOutside = spawnPawn(level, cjOrigin.offset(edge.x() + away, 0, edge.z() - 1),
                    UnitBranch.GUARD_FIGHTER);
            cleanup.add(hopOutside);
            hopOutside.setHealth(5.0F);
            // ③ 满血那只：紧贴**第二跳**（否则第三跳没得跳），血比第二跳多 ⇒ 只可能当第三跳
            PixelUnit hopFullHp = spawnPawn(level, cjOrigin.offset(edge.x() + away, 0, edge.z()),
                    UnitBranch.GUARD_FIGHTER);
            cleanup.add(hopFullHp);                    // 不动血量 = 满血
            java.util.function.BiPredicate<LivingEntity, LivingEntity> near8 = (a, b) -> {
                BlockPos pa = a.blockPosition();
                BlockPos pb = b.blockPosition();
                int dx = Math.abs(pa.getX() - pb.getX());
                int dz = Math.abs(pa.getZ() - pb.getZ());
                return dx <= 1 && dz <= 1 && (dx + dz) > 0;
            };
            boolean outsideInRange = false;
            for (AttackRange.Cell c : cjCells) {
                if (c.x() == edge.x() + away && c.z() == edge.z() - 1) {
                    outsideInRange = true;
                }
            }
            check(sb, section + "：夹具成立 —— 「范围外」那只确实不在攻击范围里（落格 dx="
                            + (edge.x() + away) + " dz=" + (edge.z() - 1) + "）", !outsideInRange);
            check(sb, section + "：夹具成立 —— 两只候选都在第一跳的周围 8 格里（一只斜角、一只正交）",
                    near8.test(hopFirst, hopOutside) && near8.test(hopFirst, hopFullHp));
            check(sb, section + "：夹具成立 —— 满血那只也在第二跳的周围 8 格里（第三跳才有得跳）",
                    near8.test(hopOutside, hopFullHp));
            float cjAtk = (float) cjMedic.getBranch().attackDamage();
            float firstBefore = hopFirst.getHealth();
            float outBefore = hopOutside.getHealth();
            float fullBefore = hopFullHp.getHealth();
            PawnCombatManager.debugResetHealCounters();
            PawnCombatManager.applyHeal(level, cjMedic, hopFirst, cjAtk);
            float firstGain = hopFirst.getHealth() - firstBefore;
            float outGain = hopOutside.getHealth() - outBefore;
            float fullGain = hopFullHp.getHealth() - fullBefore;
            sb.append("   第一跳 +").append(r2(firstGain))
                    .append("｜范围外那只 +").append(r2(outGain))
                    .append("｜满血那只 +").append(r2(fullGain))
                    .append("｜共 ").append(PawnCombatManager.debugLastChainHeals()).append(" 跳、")
                    .append(PawnCombatManager.debugLastChainLinks()).append(" 段连线\n");
            check(sb, "★ " + section + "：第二跳落在**攻击范围之外**那只（+" + r2(outGain)
                            + "）—— 原作「can heal operators outside their usual range」",
                    outGain > 0.0F
                            && Math.abs(outGain - Math.min(cjAtk * 0.75F,
                                    hopOutside.getMaxHealth() - outBefore)) < 0.05F);
            check(sb, "★ " + section + "：第三跳跳到**满血**那只（+" + r2(fullGain)
                            + " ⇒ 加了 0 血但确实跳到了）⇒ 一共 3 跳",
                    Math.abs(fullGain) < 0.001F && PawnCombatManager.debugLastChainHeals() == 3);
            check(sb, "★ " + section + "：两跳画了 " + PawnCombatManager.debugLastChainLinks()
                            + " 段连线（斜角那段也在内）",
                    PawnCombatManager.debugLastChainLinks() == 2);
            discardAll(c10);

            // ---- 9) 禁疗（2026-10 第四轮，审计报告 N1）----
            //  PRTS 精二特性原文：武者「不成为其他角色的治疗目标」、
            //  收割者 / 不屈者「无法被友方角色治疗」。
            //  ★ 本轮只做「别人的治疗不生效」；「自身的回血无视禁疗」属于特性被动，没做。
            section = "禁疗（武者 / 收割者 / 不屈者）";
            check(sb, "★ 数据层：武者 不可被友方治疗", !UnitBranch.GUARD_SOLOBLADE.healable());
            check(sb, "★ 数据层：收割者 不可被友方治疗", !UnitBranch.GUARD_REAPER.healable());
            check(sb, "★ 数据层：不屈者 不可被友方治疗", !UnitBranch.DEFENDER_JUGGERNAUT.healable());
            check(sb, "数据层对照：斗士 / 医师 **可以**被治疗",
                    UnitBranch.GUARD_FIGHTER.healable() && UnitBranch.MEDIC_MEDIC.healable());
            int bannedCount = 0;
            for (UnitBranch b : UnitBranch.values()) {
                if (!b.healable()) {
                    bannedCount++;
                }
            }
            // 计数断言防的是「漏登记 / 多登记」（与 pause 那条同一个抓法，踩坑记录）。
            check(sb, "★ 全枚举扫一遍：禁疗分支正好 3 个（实际 " + bannedCount + "）",
                    bannedCount == 3);
            // ★ 抓「BuiltinBranch 忘了转发」：漏转发会静默退回默认 true ⇒ 武者又能被治好，
            //   而上面那条数据层断言读的是枚举、照样是绿的（同族坑：踩坑记录）。
            check(sb, "★ BuiltinBranch 转发没丢（漏转发 ⇒ 武者又变成可以被治好）",
                    !new com.guardianprotocol.data.BuiltinBranch(UnitBranch.GUARD_SOLOBLADE)
                            .healable());

            // 行为层：一块自己的场地。
            // ★ 场地不能挑太远：`caseSpot(..., 12)` 是 base.X + 296 格 —— 实测那里
            //   **区块没被加载**（自验台手动推心跳，不像真实玩家那样加载周围区块），
            //   于是「范围内找友方」恒为空，②③ 两条会变成**假绿/假红**（同 踩坑记录
            //   那一族，以及 skills 场景「stride 32 × 6 = 200 一带查不到」的记录）。
            //   这里改用 base.X + 176（= 已跑通的 caseSpot 7 那一带）再往 +Z 挪 48 格，
            //   与既有用例相隔约 40 格、又落在已加载区里。
            List<Entity> cBan = new ArrayList<>();
            BlockPos spotBan = flatSpotNear(level, base.getX() + 176, base.getZ() + 56, base.getY());
            PixelUnit medicBan = spawnPawn(level, spotBan, UnitBranch.MEDIC_MEDIC);
            cBan.add(medicBan);
            // 问数据要两个范围格（不凭想象摆 —— 同 placeAlliesInRange 的教训）
            List<AttackRange.Cell> cellsBan = new ArrayList<>(medicBan.worldCells());
            cellsBan.sort(java.util.Comparator.comparingDouble(c -> c.x() * c.x() + c.z() * c.z()));
            List<BlockPos> spotsBan = new ArrayList<>();
            for (AttackRange.Cell c : cellsBan) {
                if (c.x() == 0 && c.z() == 0) {
                    continue;
                }
                spotsBan.add(spotBan.offset(c.x(), 0, c.z()));
                if (spotsBan.size() >= 2) {
                    break;
                }
            }
            check(sb, "夹具：医师的攻击范围里至少取到 2 格（实际 " + spotsBan.size() + " 格）",
                    spotsBan.size() >= 2);
            PixelUnit soloBan = spawnPawn(level, spotsBan.get(0), UnitBranch.GUARD_SOLOBLADE);
            cBan.add(soloBan);
            soloBan.setHealth(5.0F);
            // ① 结算点：直接治它 ⇒ 一点都回不去
            float gainBan = healDirectly(level, medicBan, soloBan, 999.0F);
            check(sb, "★ " + section + "：直接结算治疗时武者**一点没回**（实际 +"
                            + r2(gainBan) + "）", gainBan == 0.0F);
            // ② 挑目标：全场只有这名禁疗伤员时，医师**不出手**
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> pickedBan = PawnCombatManager.debugLastTargets(medicBan);
            check(sb, "★ " + section + "：只有禁疗友方受伤时，医师不出手（选中 "
                            + pickedBan.size() + " 名）", pickedBan.isEmpty());
            // ③ 对照：把一名**可治疗**的伤员放进来 ⇒ 立刻选它（证明上一条不是「谁都不治」）
            PixelUnit fighterBan = spawnPawn(level, spotsBan.get(1), UnitBranch.GUARD_FIGHTER);
            cBan.add(fighterBan);
            fighterBan.setHealth(5.0F);
            PawnCombatManager.onServerTick(level.getServer());
            List<LivingEntity> pickedBan2 = PawnCombatManager.debugLastTargets(medicBan);
            check(sb, "★ " + section + "：放进一名可治疗的伤员 ⇒ 医师立刻选它（选中 "
                            + pickedBan2.size() + " 名）—— 证明上一条不是「谁都不治」",
                    pickedBan2.size() == 1 && pickedBan2.get(0) == fighterBan);
            discardAll(cBan);

        } catch (Exception ex) {
            sb.append("   !! 场景异常（本场景的断言未执行完）：").append(ex).append('\n');
            check(sb, "场景 " + section + " 跑完没有抛异常", false);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * 每个用例一块自己的平地（**相隔 24 格**），并且落在区块中间。
     *
     * <p>★ 24 格足够避免用例互相串场（治疗范围最长也就 10 格出头），同时把最远的用例
     * 压在**实体索引可见的范围内**：实测 stride 32 × 6 = 200 那一带的区块里，
     * 生成出来的棋子查不到（自验台手动推心跳，不像真实玩家那样加载周围区块）——
     * 现象是「范围扫描看见 0 个友方」，而实现本身没问题。</p>
     */
    private static BlockPos caseSpot(ServerLevel level, BlockPos base, int index) {
        return flatSpotNear(level, base.getX() + index * 24 + 8, base.getZ() + 8, base.getY());
    }

    /**
     * 在**这个棋子自己的攻击范围格**里摆 N 名友方（血量按缺多少设）。
     *
     * <p>为什么不按「正前方几格」摆：各分支的治疗范围形状与长度都不一样（链愈师与医师就不是同一个
     * 模板干员），凭想象摆位置会让夹具**悄悄摆到范围外**，现象是「功能只生效一半」——
     * 第一版链愈师连锁只跳到 1 个就是这么来的。范围是数据，夹具就问数据要位置。</p>
     */
    private static List<PixelUnit> placeAlliesInRange(ServerLevel level, List<Entity> cleanup,
                                                      PixelUnit medic, int count, float health) {
        List<AttackRange.Cell> cells = new ArrayList<>(medic.worldCells());
        // 从近到远（保证「连锁的第一跳」是离治疗者最近的那个，断言的顺序才可预期）
        cells.sort(java.util.Comparator.comparingDouble(c -> c.x() * c.x() + c.z() * c.z()));
        BlockPos origin = medic.blockPosition();
        List<PixelUnit> out = new ArrayList<>();
        for (AttackRange.Cell c : cells) {
            if (out.size() >= count) {
                break;
            }
            if (c.x() == 0 && c.z() == 0) {
                continue;       // 自己那一格不放人：免得和棋子叠在一起
            }
            BlockPos at = origin.offset(c.x(), 0, c.z());
            PixelUnit ally = spawnPawn(level, at, UnitBranch.GUARD_FIGHTER);
            cleanup.add(ally);
            ally.setHealth(health < 0.0F ? ally.getMaxHealth() : health);
            out.add(ally);
        }
        return out;
    }

    /** 场上属于**这个棋子**的投掷物（按主人过滤：{@link #projectilesNear} 是 24 格范围，会跨用例串味）。 */
    private static List<PawnProjectile> projectilesOwnedBy(ServerLevel level, PixelUnit unit) {
        List<PawnProjectile> out = new ArrayList<>();
        for (PawnProjectile p : projectilesNear(level, unit)) {
            if (p.getOwnerUnit() == unit) {
                out.add(p);
            }
        }
        return out;
    }

    /** 在治疗者前方/侧面的格子里放一名**非医疗**友方棋子（{@code health < 0} = 置满血）。 */
    private static PixelUnit allyPawn(ServerLevel level, List<Entity> cleanup, PixelUnit medic,
                                      int forward, int lateral, float health) {
        int[] off = medic.getFacing().toWorldOffset(forward, lateral);
        BlockPos at = medic.blockPosition().offset(off[0], 0, off[1]);
        PixelUnit ally = spawnPawn(level, at, UnitBranch.GUARD_FIGHTER);
        cleanup.add(ally);
        ally.setHealth(health < 0.0F ? ally.getMaxHealth() : health);
        return ally;
    }

    /** 攻击范围里**最远**的那一格（用来验疗养师的「内圈之外」）。 */
    private static BlockPos farthestCell(PixelUnit unit) {
        BlockPos origin = unit.blockPosition();
        BlockPos best = origin;
        double bestD = -1.0D;
        for (AttackRange.Cell c : unit.worldCells()) {
            double d = Math.sqrt((double) (c.x() * c.x() + c.z() * c.z()));
            if (d > bestD) {
                bestD = d;
                best = origin.offset(c.x(), 0, c.z());
            }
        }
        return best;
    }

    /** 直接结算一次治疗并返回实际回复量（自验里把「选择」与「结算」分开验）。 */
    private static float healDirectly(ServerLevel level, PixelUnit medic, PixelUnit ally, float amount) {
        float before = ally.getHealth();
        PawnCombatManager.applyHeal(level, medic, ally, amount);
        return ally.getHealth() - before;
    }

    /** 目标列表的可读描述（报告里读得懂）。 */
    private static String desc(List<LivingEntity> list) {
        StringBuilder b = new StringBuilder();
        for (LivingEntity e : list) {
            if (b.length() > 0) {
                b.append('、');
            }
            b.append(e.getType().getDescription().getString())
                    .append(' ').append(r2(e.getHealth())).append('/').append(r2(e.getMaxHealth()));
        }
        return b.toString();
    }

    /**
     * 群伤（落点溅射 / 连锁 / 多段余震）的自验 —— 2026-10 第二轮。
     *
     * <h3>为什么直接调 {@code applyHit} 而不是「摆好阵型让棋子自己打」</h3>
     * <p>要验的是「伤害**扩散**到谁」，不是「棋子会不会出手」——后者已经由 {@code attack} /
     * {@code multi} 两个场景钉住了。直接给一次已知伤害，就能把「谁掉血、掉多少」变成算术：
     * 世界里的时间冻结、AI 不乱走、伤害不随机（受击者护甲一律设 0），结论可复现。</p>
     *
     * <p><b>几何全部按球判</b>：受击者的<b>身体中心</b>到命中点（= 主目标身体中心）的欧氏距离。
     * 每个用例都放了两只「半径内 / 半径外」的对照，且半径外的距离只比半径大 60%，
     * 确保「打到了」不是靠碰巧落进 AABB 的方角。</p>
     */
    private static void scenarioAoe(StringBuilder sb, ServerLevel level, BlockPos base) {
        List<Entity> cleanup = new ArrayList<>();
        BlockPos spot = flatSpotNear(level, base.getX(), base.getZ(), base.getY());
        double y = spot.getY();
        double z0 = spot.getZ() + 0.5D;
        double x0 = spot.getX() + 0.5D;
        String section = "";
        try {
            // ---- 0) 数据层：生成链有没有把群伤数值写进去 ----
            check(sb, "炮手 溅射 r=1.0 全额可对空",
                    UnitBranch.SNIPER_ARTILLERYMAN.aoe().radius() == 1.0D
                            && UnitBranch.SNIPER_ARTILLERYMAN.aoe().factor() == 1.0D
                            && UnitBranch.SNIPER_ARTILLERYMAN.aoe().canHitAir());
            check(sb, "扩散术师 溅射 r=1.1（PRTS 特性表）",
                    UnitBranch.CASTER_SPLASH.aoe().radius() == 1.1D);
            check(sb, "撼地者 溅射 r=1.0 但系数 0.5（原文：周围其他敌人受 50%）",
                    UnitBranch.GUARD_EARTHSHAKER.aoe().radius() == 1.0D
                            && UnitBranch.GUARD_EARTHSHAKER.aoe().factor() == 0.5D);
            check(sb, "要塞 溅射 r=1.0 且**不可对空**（PRTS 备注）",
                    UnitBranch.DEFENDER_FORTRESS.aoe().radius() == 1.0D
                            && !UnitBranch.DEFENDER_FORTRESS.aoe().canHitAir());
            check(sb, "链术师：4 个目标 / 每跳 ×0.85 / 搜索半径 1.7",
                    UnitBranch.CASTER_CHAIN.aoe().chainTargets() == 4
                            && Math.abs(UnitBranch.CASTER_CHAIN.aoe().chainDecay() - 0.85D) < 1.0E-6D
                            && UnitBranch.CASTER_CHAIN.aoe().radius() == 1.7D);
            check(sb, "投掷手：两段 + 余震 ×0.5 + 小范围溅射 0.9 不可对空",
                    UnitBranch.SNIPER_FLINGER.attackCount() == 2
                            && UnitBranch.SNIPER_FLINGER.tailFactor() == 0.5D
                            && UnitBranch.SNIPER_FLINGER.aoe().radius() == 0.9D
                            && !UnitBranch.SNIPER_FLINGER.aoe().canHitAir());
            check(sb, "★ 轰击术师已纠正为「直线群体」= 一次打范围内全体（不是落点溅射）",
                    UnitBranch.CASTER_BLAST.targetCount() == UnitBranch.TargetCount.MULTI_ALL
                            && UnitBranch.CASTER_BLAST.aoe().kind() == UnitBranch.Aoe.Kind.NONE);
            check(sb, "普通分支（斗士）没有任何群伤",
                    UnitBranch.GUARD_FIGHTER.aoe().kind() == UnitBranch.Aoe.Kind.NONE
                            && UnitBranch.GUARD_FIGHTER.attackCount() == 1);
            check(sb, "剑豪：两段、各 100%、**没有**溅射（「普通攻击连续造成两次伤害」）",
                    UnitBranch.GUARD_SWORDMASTER.attackCount() == 2
                            && UnitBranch.GUARD_SWORDMASTER.tailFactor() == 1.0D
                            && UnitBranch.GUARD_SWORDMASTER.aoe().kind() == UnitBranch.Aoe.Kind.NONE);

            // ---- 1) 落点溅射：半径内该掉、半径外不该掉、主目标不二次结算 ----
            section = "炮手（r=1.0，全额）";
            PixelUnit gunner = spawnPawn(level, spot, UnitBranch.SNIPER_ARTILLERYMAN);
            cleanup.add(gunner);
            List<Mob> row = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.8D, 1.6D});
            Mob gunPrimary = row.get(0);
            Mob gunIn = row.get(1);
            Mob gunOut = row.get(2);
            double[] d = hitOnce(level, gunner, row, 20.0F);
            check(sb, section + "：半径内那只掉血了（" + r2(d[1]) + "）", d[1] > 0.0D);
            check(sb, section + "：半径外那只**一点没掉**（" + r2(d[2]) + "）", d[2] == 0.0D);
            check(sb, section + "：溅射全额 = 与主目标同量（" + r2(d[0]) + " vs " + r2(d[1]) + "）",
                    Math.abs(d[1] - d[0]) < 0.01D);
            check(sb, section + "：主目标只结算一次（" + r2(d[0]) + "，期望 20）",
                    Math.abs(d[0] - 20.0D) < 0.01D);
            check(sb, section + "：溅射命中数被记录下来（" + PawnCombatManager.debugLastSplashHits() + "）",
                    PawnCombatManager.debugLastSplashHits() == 1);

            // ---- 2) 撼地者：溅射**半数** ----
            section = "撼地者（r=1.0，系数 0.5）";
            PixelUnit shaker = spawnPawn(level, spot, UnitBranch.GUARD_EARTHSHAKER);
            cleanup.add(shaker);
            List<Mob> row2 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.8D});
            double[] d2 = hitOnce(level, shaker, row2, 20.0F);
            check(sb, section + "：主目标 20、旁观者 10（实际 " + r2(d2[0]) + " / " + r2(d2[1]) + "）",
                    Math.abs(d2[0] - 20.0D) < 0.01D && Math.abs(d2[1] - 10.0D) < 0.01D);

            // ---- 3) 要塞：不可对空 —— 半径内的幻翼不该挨打 ----
            section = "要塞（不可对空）";
            PixelUnit fortress = spawnPawn(level, spot, UnitBranch.DEFENDER_FORTRESS);
            cleanup.add(fortress);
            List<Mob> row3 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.8D});
            Phantom air = level.getEntitiesOfClass(Phantom.class,
                    new net.minecraft.world.phys.AABB(spot).inflate(8.0D)).stream()
                    .findFirst().orElse(null);
            if (air == null) {
                air = EntityType.PHANTOM.create(level);
                level.addFreshEntity(air);
                cleanup.add(air);
            }
            air.setPos(x0 + 3.8D, y + 5.0D, z0);
            air.setNoGravity(true);
            air.setDeltaMovement(Vec3.ZERO);
            air.setNoAi(true);
            makeTanky(air, 5000.0D);
            boolean flying = com.guardianprotocol.combat.Targeting.isFlying(air);
            check(sb, "夹具：幻翼此刻被判为「空中单位」（否则这条断言等于没验）", flying);
            double airBefore = air.getHealth();
            double[] d3 = hitOnce(level, fortress, row3, 20.0F);
            check(sb, section + "：地面的那只挨了溅射（" + r2(d3[1]) + "）", d3[1] > 0.0D);
            check(sb, section + "：半径内的**空中**那只一点没掉（" + r2(airBefore - air.getHealth()) + "）",
                    Math.abs(airBefore - air.getHealth()) < 0.01D);

            // ---- 4) 链术师：恰好 4 个目标，每跳 ×0.85，第 5 只不挨打 ----
            section = "链术师（4 目标 / 每跳 ×0.85）";
            PixelUnit chainer = spawnPawn(level, spot, UnitBranch.CASTER_CHAIN);
            cleanup.add(chainer);
            List<Mob> line = aoeRow(level, cleanup, x0 + 6.0D, y, z0,
                    new double[]{0.0D, 1.0D, 2.0D, 3.0D, 4.0D});
            double[] d4 = hitOnce(level, chainer, line, 20.0F);
            check(sb, section + "：打中 4 个（观测 " + PawnCombatManager.debugLastChainHits() + "）",
                    PawnCombatManager.debugLastChainHits() == 4);
            check(sb, section + "：第 5 只**不在**连锁里（" + r2(d4[4]) + "）", d4[4] == 0.0D);
            // ★ 链的连线（2026-10 补）：4 个目标之间一共 3 段（3 跳）；粒子是表现、不进世界，
            //   所以断言的是画连线那一处自己记的计数（斜角也走同一条线段采样路径）。
            check(sb, "★ " + section + "：4 个目标之间画了 " + PawnCombatManager.debugLastChainLinks()
                            + " 段连线（3 跳 = 3 段；伤害链用 ELECTRIC_SPARK）",
                    PawnCombatManager.debugLastChainLinks() == 3);
            double[] expect = {20.0D, 20.0D * 0.85D, 20.0D * 0.85D * 0.85D,
                    20.0D * 0.85D * 0.85D * 0.85D, 0.0D};
            boolean chainOk = true;
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < expect.length; i++) {
                detail.append(r2(d4[i])).append(i < expect.length - 1 ? " / " : "");
                if (Math.abs(d4[i] - expect[i]) > 0.05D) {
                    chainOk = false;
                }
            }
            check(sb, section + "：逐跳递减正确（实际 " + detail + "，期望 "
                    + r2(expect[0]) + " / " + r2(expect[1]) + " / " + r2(expect[2]) + " / "
                    + r2(expect[3]) + " / 0）", chainOk);
            Mob chainFourth = line.get(3);
            boolean paused = chainFourth.getEffect(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) != null;
            check(sb, section + "：被连锁到的目标带上了「短暂停顿」（短时强减速的近似）", paused);

            // ---- 5) 投掷手：两段（第二段余震 ×0.5）+ 每段都溅射 ----
            section = "投掷手（两段 + 余震 0.5）";
            PixelUnit flinger = spawnPawn(level, spot, UnitBranch.SNIPER_FLINGER);
            cleanup.add(flinger);
            List<Mob> row5 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.7D});
            double[] d5 = hitOnce(level, flinger, row5, 20.0F);
            check(sb, section + "：主目标吃了两段 = 20 + 10（实际 " + r2(d5[0]) + "）",
                    Math.abs(d5[0] - 30.0D) < 0.05D);
            check(sb, section + "：旁观者两段的溅射都吃到 = 30（实际 " + r2(d5[1]) + "）",
                    Math.abs(d5[1] - 30.0D) < 0.05D);

            // ---- 6) 剑豪：同一目标两段、各 100%、没有溅射 ----
            // ★ 这一节还是「无敌帧必须清零」那条的**行为证据**：1.20.1 的 hurt 在
            //   invulnerableTime > 10 时要求「新伤害 > 上一次伤害」才生效，否则直接 return false。
            //   两段伤害数值相同 ⇒ 第二段本来会被**静默吞掉**（掉血 20 而不是 40）。
            //   所以断言 40 就等于在验清零那一步，而不是在验一个常数。
            section = "剑豪（同一目标两段 ×100%）";
            PixelUnit sword = spawnPawn(level, spot, UnitBranch.GUARD_SWORDMASTER);
            cleanup.add(sword);
            List<Mob> row7 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.8D});
            double[] d7 = hitOnce(level, sword, row7, 20.0F);
            check(sb, section + "：主目标掉了两段 = 40（实际 " + r2(d7[0])
                    + "；若为 20 则第二段被无敌帧吞了）", Math.abs(d7[0] - 40.0D) < 0.05D);
            check(sb, section + "：旁边那只一点没掉（剑豪**没有**溅射，实际 " + r2(d7[1]) + "）",
                    d7[1] == 0.0D);
            check(sb, section + "：压根没有发生溅射（观测 "
                    + PawnCombatManager.debugLastSplashHits() + "）",
                    PawnCombatManager.debugLastSplashHits() == -1);

            // ---- 7) 对照：没有群伤的分支只打一个 ----
            section = "对照 · 斗士（无群伤）";
            PixelUnit fighter = spawnPawn(level, spot, UnitBranch.GUARD_FIGHTER);
            cleanup.add(fighter);
            List<Mob> row6 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.8D});
            double[] d6 = hitOnce(level, fighter, row6, 20.0F);
            check(sb, section + "：只掉了主目标那一下（" + r2(d6[0]) + " / 旁观 " + r2(d6[1]) + "）",
                    Math.abs(d6[0] - 20.0D) < 0.01D && d6[1] == 0.0D);
            check(sb, section + "：压根没有发生溅射（观测 "
                    + PawnCombatManager.debugLastSplashHits() + "）",
                    PawnCombatManager.debugLastSplashHits() == -1);

        } catch (Exception ex) {
            sb.append("   !! 场景异常（本场景的断言未执行完）：").append(ex).append('\n');
            check(sb, "场景 " + section + " 跑完没有抛异常", false);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    // ------------------------------------------------------------------
    // 场景：停顿（特性自带的「短暂停顿」）—— 2026-10 第四轮
    // ------------------------------------------------------------------

    /**
     * 停顿：**凝滞师**（0.8 秒 = 16 tick，管每次普攻的**主目标**）与
     * **链术师**（0.5 秒 = 10 tick，只管**连锁跳到的**目标）。
     *
     * <h3>数值与出处（**没有暂定值**，全部来自 PRTS）</h3>
     * <ul>
     *   <li>凝滞师：精二特性「攻击造成法术伤害，并对敌人造成短暂的停顿」；
     *       《分支特性信息》「<b>特性停顿时间为 0.8 秒</b>」⇒ 16 tick；</li>
     *   <li>链术师：精二特性「…每次跳跃伤害降低 15% 并造成短暂停顿<b>(0.5s)</b>」⇒ 10 tick。</li>
     * </ul>
     * <p>旧版代码里 {@code CHAIN_PAUSE_TICKS = 10} 的注释自己写着「暂定值：PRTS 只写
     * 『造成短暂停顿』、没给时长」—— 本轮 PRTS 给了数，值恰好没变，但现在**有出处**了。</p>
     *
     * <h3>为什么「主目标不暂停」是断言而不是漏做</h3>
     * <p>链术师的原文是「每次<b>跳跃</b>…造成短暂停顿」—— 主目标那一击不是「跳跃」，
     * 所以 {@link UnitBranch.Pause.Kind#CHAIN} 档只作用于跳到的目标。这是本工程对原文的读法，
     * 写死在这里备查（与 {@code aoe} 场景里那条「被连锁到的目标带上了停顿」是同一个行为的
     * 两个侧面：那条只看了被跳到的目标，这条补上「主目标不算」）。</p>
     *
     * <h3>无头台能验到什么</h3>
     * <p>停顿是「挂一次短时强减速」，**不依赖游戏刻推进**（挂上去立刻读得到），
     * 所以这一节不含任何「需要眼睛看」的东西 —— 与「真出手（挥臂）」那种验不了的项目不同。</p>
     */
    private static void scenarioPause(StringBuilder sb, ServerLevel level, BlockPos base) {
        List<Entity> cleanup = new ArrayList<>();
        BlockPos spot = flatSpotNear(level, base.getX(), base.getZ(), base.getY());
        double y = spot.getY();
        double z0 = spot.getZ() + 0.5D;
        double x0 = spot.getX() + 0.5D;
        String section = "";
        try {
            // ---- 0) 数据层：生成链有没有把停顿写进去 ----
            check(sb, "凝滞师：停顿 = HIT 档 / 16 tick（0.8 秒，PRTS《分支特性信息》）",
                    UnitBranch.SUPPORTER_DECEL_BINDER.pause().kind() == UnitBranch.Pause.Kind.HIT
                            && UnitBranch.SUPPORTER_DECEL_BINDER.pause().ticks() == 16);
            check(sb, "链术师：停顿 = CHAIN 档 / 10 tick（0.5 秒，PRTS 精二特性原文）",
                    UnitBranch.CASTER_CHAIN.pause().kind() == UnitBranch.Pause.Kind.CHAIN
                            && UnitBranch.CASTER_CHAIN.pause().ticks() == 10);
            check(sb, "对照 · 斗士：没有任何停顿",
                    !UnitBranch.GUARD_FIGHTER.pause().present());
            int pauseBranches = 0;
            for (UnitBranch b : UnitBranch.values()) {
                if (b.pause().present()) {
                    pauseBranches++;
                }
            }
            // 计数断言防的是「漏登记 / 多登记」——这与「全枚举扫常态阻挡为 0 的分支正好 2 个」
            // 是同一种抓法（踩坑记录：手维护清单漏一条不会报错、只会静默少一条数据）。
            check(sb, "★ 全枚举扫一遍：特性自带停顿的分支正好 2 个（实际 " + pauseBranches + "）",
                    pauseBranches == 2);
            // ★ 这一条抓的是「BuiltinBranch 忘了转发」：漏转发会**静默退回** BranchDef 的默认实现
            //   （不停顿），而数据层那条断言读的是枚举、照样是绿的 —— 同族坑见 踩坑记录。
            check(sb, "★ BuiltinBranch 转发没丢（漏转发会静默退回默认 0）",
                    new com.guardianprotocol.data.BuiltinBranch(UnitBranch.SUPPORTER_DECEL_BINDER)
                            .pause().ticks() == 16);

            // ---- 1) 凝滞师：普攻主目标被停顿，时长 = 16 ----
            section = "凝滞师（HIT / 16 tick）";
            PixelUnit slower = spawnPawn(level, spot, UnitBranch.SUPPORTER_DECEL_BINDER);
            cleanup.add(slower);
            List<Mob> row = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D, 0.8D});
            Mob hitTarget = row.get(0);
            Mob bystander = row.get(1);
            check(sb, "夹具：出手前两只都没有减速效果（否则下面读到的是残留）",
                    hitTarget.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) == null
                            && bystander.getEffect(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) == null);
            hitOnce(level, slower, row, 20.0F);
            net.minecraft.world.effect.MobEffectInstance eff = hitTarget.getEffect(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
            check(sb, section + "：主目标被停顿了", eff != null);
            check(sb, section + "：时长 = 16 tick（实际 " + (eff == null ? -1 : eff.getDuration()) + "）",
                    eff != null && eff.getDuration() == 16);
            check(sb, section + "：幅度 = " + PawnCombatManager.PAUSE_AMPLIFIER
                            + "（实际 " + (eff == null ? -1 : eff.getAmplifier()) + "）",
                    eff != null && eff.getAmplifier() == PawnCombatManager.PAUSE_AMPLIFIER);
            check(sb, section + "：旁边那只**没有**被停顿（凝滞师是单体、没有群伤）",
                    bystander.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) == null);

            // ---- 2) 对照：同样是远程法术分支、特性却不带停顿（中坚术师）----
            section = "对照 · 中坚术师（特性无停顿）";
            PixelUnit core = spawnPawn(level, spot, UnitBranch.CASTER_CORE);
            cleanup.add(core);
            List<Mob> row2 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D});
            double[] d2 = hitOnce(level, core, row2, 20.0F);
            check(sb, section + "：确实挨了一发（掉血 " + r2(d2[0]) + "）", d2[0] > 0.0D);
            check(sb, section + "：但**没有**减速 —— 证明上面那条不是「谁挨打都减速」",
                    row2.get(0).getEffect(
                            net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) == null);

            // ---- 3) 链术师：只有「跳跃到的」目标被停顿 ----
            section = "链术师（CHAIN / 10 tick）";
            PixelUnit chainer = spawnPawn(level, spot, UnitBranch.CASTER_CHAIN);
            cleanup.add(chainer);
            List<Mob> line = aoeRow(level, cleanup, x0 + 6.0D, y, z0,
                    new double[]{0.0D, 1.0D, 2.0D, 3.0D, 4.0D});
            hitOnce(level, chainer, line, 20.0F);
            check(sb, "夹具：这一枪真的连锁到了 4 个（观测 "
                            + PawnCombatManager.debugLastChainHits() + "）",
                    PawnCombatManager.debugLastChainHits() == 4);
            check(sb, section + "：**主目标不停顿**（原文写的是「每次**跳跃**…造成短暂停顿」，"
                            + "主目标那一击不算跳跃 —— 本工程的读法，写死备查）",
                    line.get(0).getEffect(
                            net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) == null);
            boolean jumpsPaused = true;
            StringBuilder dur = new StringBuilder();
            for (int i = 1; i <= 3; i++) {
                net.minecraft.world.effect.MobEffectInstance e = line.get(i).getEffect(
                        net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
                dur.append(i + 1).append('=').append(e == null ? "无" : String.valueOf(e.getDuration()))
                        .append(' ');
                if (e == null || e.getDuration() != 10) {
                    jumpsPaused = false;
                }
            }
            check(sb, section + "：第 2/3/4 只都被停顿且时长 = 10 tick（实际 "
                            + dur.toString().trim() + "）", jumpsPaused);
            check(sb, section + "：第 5 只（不在连锁里）没有减速",
                    line.get(4).getEffect(
                            net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) == null);

        } catch (Exception ex) {
            sb.append("   !! 场景异常（本场景的断言未执行完）：").append(ex).append('\n');
            check(sb, "场景 " + section + " 跑完没有抛异常", false);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * 分支机制：<b>伤害乘区</b>（N9）+ <b>优先打被挡的</b>（N2）—— 2026-10 第五轮。
     *
     * <h3>设计口径（原话）</h3>
     * <blockquote>
     * 「N2 就是挡人时攻击被阻挡的敌人，N9 伤害乘区做一下，包括教官未阻挡时造成的伤害 120%，
     * 领主攻击 1 格外（不含一格）的敌人时伤害 80%，解放者未开技能时攻击力逐步提升的这些都做一下……
     * 此外<b>造成伤害就是指在进行伤害计算时的最终伤害，而不是攻击力</b>。」
     * </blockquote>
     *
     * <h3>四组断言各验什么</h3>
     * <ul>
     *   <li><b>A 数据层</b>：8 个分支的乘区规格对不对（含「哪几条来自专属模组」）、
     *       3 个分支的 BLOCKED_FIRST 有没有写进生成物、`BuiltinBranch` 转发没丢；</li>
     *   <li><b>B 纯判据真值表</b>：{@link DamageMods#factor} 是纯函数 ⇒ **不碰世界**就能逐条验
     *       （六个 Kind × 条件满足/不满足 + ramp 的 0/20/40 秒 + 越界 + null）；</li>
     *   <li><b>C 解放者 ramp 状态</b>：真实棋子上「技能未激活才累积、激活期间归零、
     *       上限夹取、NBT 往返」；</li>
     *   <li><b>D 行为层</b>：真打一发，读 {@code debugLastDamageFactor()} 看倍率
     *       （教官对未阻挡 1.2 / 领主近处 1.0 远处 0.8 / 速射手对空 1.1 / 攻城手对重 1.15），
     *       并**同时**断言攻击力面板没被改（设计口径：「不是攻击力」）。</li>
     * </ul>
     *
     * <p>★ 没验到的两处，如实写在这里：① 「打**我挡住的**敌人加伤」（强攻手/无畏者/要塞）
     * 需要真的把怪挡进锚点，行为层夹具成本高 —— 由 B 组的真值表覆盖；
     * ② N2 的 BLOCKED_FIRST 只验到**数据层**（排序行为要一只真被挡的怪，
     * 与上一条同一个夹具问题）。</p>
     */
    private static void scenarioTraits(StringBuilder sb, ServerLevel level, BlockPos base) {
        List<Entity> cleanup = new ArrayList<>();
        BlockPos spot = flatSpotNear(level, base.getX(), base.getZ(), base.getY());
        double y = spot.getY();
        double z0 = spot.getZ() + 0.5D;
        double x0 = spot.getX() + 0.5D;
        String section = "";
        try {
            // ---- A) 数据层 ----
            sb.append("-- A) 数据层：乘区规格 / 优先级 / 转发\n");
            check(sb, "教官 = 打「不是我挡住的」×1.20（精二特性，非模组）",
                    UnitBranch.GUARD_INSTRUCTOR.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.TARGET_NOT_BLOCKED
                            && Math.abs(UnitBranch.GUARD_INSTRUCTOR.damageMod().factor() - 1.20D) < 1e-9
                            && !UnitBranch.GUARD_INSTRUCTOR.damageMod().fromModule());
            check(sb, "领主 = 打「格距 > 1」×0.80（精二特性，非模组）",
                    UnitBranch.GUARD_LORD.damageMod().kind() == UnitBranch.DamageMod.Kind.TARGET_FAR
                            && Math.abs(UnitBranch.GUARD_LORD.damageMod().factor() - 0.80D) < 1e-9
                            && !UnitBranch.GUARD_LORD.damageMod().fromModule());
            check(sb, "解放者 = ramp，上限 ×3.00（+200%）（精二特性，非模组）",
                    UnitBranch.GUARD_LIBERATOR.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.LIBERATOR_RAMP
                            && Math.abs(UnitBranch.GUARD_LIBERATOR.damageMod().factor() - 3.00D) < 1e-9
                            && !UnitBranch.GUARD_LIBERATOR.damageMod().fromModule());
            // ★ 2026-10-07 第六轮补的两条：设计点名「撼地者、猎手、散射手为什么不做他们的倍率」
            //   原文一直在「分支机制（干员特性原文）」列里；上一轮把猎手/散射手记成「未做」，
            //   还错把撼地者记成「模组 HAM-X 115%」（那个 115% 出自后来被删掉的模组列）。
            check(sb, "★ 猎手 = 攻击消耗子弹时 ×1.20（精二特性「攻击时需要消耗子弹且攻击力提升至120%」）",
                    UnitBranch.SNIPER_HUNTER.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.CONSUMES_BULLET
                            && Math.abs(UnitBranch.SNIPER_HUNTER.damageMod().factor() - 1.20D) < 1e-9
                            && !UnitBranch.SNIPER_HUNTER.damageMod().fromModule());
            check(sb, "★ 散射手 = 前方一横排 ×1.50（精二特性「对自己前方一横排的敌人攻击力提升至150%」）",
                    UnitBranch.SNIPER_SPREADSHOOTER.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.TARGET_FRONT_ROW
                            && Math.abs(UnitBranch.SNIPER_SPREADSHOOTER.damageMod().factor() - 1.50D) < 1e-9
                            && !UnitBranch.SNIPER_SPREADSHOOTER.damageMod().fromModule());
            check(sb, "★ 撼地者**不用再补**：它的 50% 是**溅射系数**（已实现 `Aoe.splash(1.0, 0.5)`），"
                            + "不是伤害乘区",
                    UnitBranch.GUARD_EARTHSHAKER.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.NONE
                            && UnitBranch.GUARD_EARTHSHAKER.aoe().kind()
                            == UnitBranch.Aoe.Kind.SPLASH);
            check(sb, "★ 强攻手 / 无畏者 / 要塞 / 速射手 / 攻城手 的乘区**已摘出、不再默认生效**"
                            + "（设计口径：「模组只需要像天赋一样留一个待定槽即可」）",
                    UnitBranch.GUARD_CENTURION.damageMod().kind() == UnitBranch.DamageMod.Kind.NONE
                            && UnitBranch.GUARD_DREADNOUGHT.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.NONE
                            && UnitBranch.DEFENDER_FORTRESS.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.NONE
                            && UnitBranch.SNIPER_MARKSMAN.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.NONE
                            && UnitBranch.SNIPER_BESIEGER.damageMod().kind()
                            == UnitBranch.DamageMod.Kind.NONE);
            int withMod = 0;
            int fromModule = 0;
            for (UnitBranch b : UnitBranch.values()) {
                if (b.damageMod().present()) {
                    withMod++;
                }
                if (b.damageMod().fromModule()) {
                    fromModule++;
                }
            }
            check(sb, "★ 全枚举扫一遍：带伤害乘区的分支正好 5 个"
                            + "（教官/领主/解放者/猎手/散射手；实际 " + withMod + "）"
                            + "，且**没有一条带模组标记**（实际 " + fromModule + "）",
                    withMod == 5 && fromModule == 0);
            com.guardianprotocol.data.BuiltinBranch lordDef =
                    new com.guardianprotocol.data.BuiltinBranch(UnitBranch.GUARD_LORD);
            check(sb, "★ BuiltinBranch 转发没丢（漏转发 ⇒ 乘区静默消失，实测伤害不变）",
                    lordDef.damageMod().kind() == UnitBranch.DamageMod.Kind.TARGET_FAR);
            // ---- A3) ★ 设计口径：天赋槽全空 + 模组只留待定槽 ----
            //   原话：「天赋不填，目前应该是 72 个分支的天赋槽全空，模组只需要像天赋一样
            //   留一个待定槽即可」⇒ 三个槽（天赋1 / 天赋2 / 模组）**都必须没有内容**。
            //   ★ 这条防的是「生成链又把参考表里的内容抄回枚举」——上一轮它就是这么抄进去的，
            //     那种抄法会让人误以为「这个分支自带这条天赋」。
            sb.append("-- A3) ★ 设计口径：天赋槽全空 / 模组只留待定槽\n");
            {
                int filledTalents = 0;
                int filledModules = 0;
                for (UnitBranch b : UnitBranch.values()) {
                    if (b.talent1().present() || b.talent2().present()) {
                        filledTalents++;
                    }
                    if (b.module().present()) {
                        filledModules++;
                    }
                }
                sb.append("   72 个分支：有内容的天赋槽 ").append(filledTalents)
                        .append(" 个 / 有内容的模组槽 ").append(filledModules).append(" 个\n");
                check(sb, "★ 72 个分支的天赋槽**全空**（有内容的 = 0，实际 " + filledTalents + "）",
                        filledTalents == 0);
                check(sb, "★ 72 个分支的模组槽**一律待定**（有内容的 = 0，实际 " + filledModules + "）",
                        filledModules == 0);
                // BuiltinBranch 也要转发模组槽（漏转发 = 将来导入脚本填了、下游读到「待定」）
                check(sb, "★ BuiltinBranch 转发了模组槽（现在是空槽位，但转发链必须在）",
                        !lordDef.module().present()
                                && lordDef.module().describe().contains("待定")
                                && lordDef.moduleSource().contains("待定"));
                check(sb, "★ Talents 门面：两个槽都空 ⇒ present() = false（等干员导入填）",
                        !com.guardianprotocol.api.Talents.present(lordDef)
                                && !com.guardianprotocol.api.Talents.first(lordDef).present());
                // ★ 参考原文没丢：仍在 生成链参考表 里（表是表、代码是代码）
                check(sb, "★ 模组待定槽的出处写明了内容去向（含 MODULE_DAMAGE_MODS_PENDING）",
                        lordDef.moduleSource().contains("MODULE_DAMAGE_MODS_PENDING"));
            }

            // ---- N2 优先级：3 个真的改、2 个只登记 ----
            check(sb, "★ N2：领主 / 哨戒铁卫 / 钩索师 的优先级 = BLOCKED_FIRST",
                    UnitBranch.GUARD_LORD.targetPriority() == UnitBranch.TargetPriority.BLOCKED_FIRST
                            && UnitBranch.DEFENDER_SENTRY_PROTECTOR.targetPriority()
                            == UnitBranch.TargetPriority.BLOCKED_FIRST
                            && UnitBranch.SPECIALIST_HOOKMASTER.targetPriority()
                            == UnitBranch.TargetPriority.BLOCKED_FIRST);
            check(sb, "★ N2：裂空炮手仍是 AIR_ONLY、解放者仍不出手（这两条有原文，"
                            + "但改优先级会打架 —— 见 branch_meta.BLOCKED_FIRST_OVERRIDES）",
                    UnitBranch.SNIPER_SKYBREAKER.targetPriority() == UnitBranch.TargetPriority.AIR_ONLY
                            && !UnitBranch.GUARD_LIBERATOR.canAttack());
            int blockedFirst = 0;
            for (UnitBranch b : UnitBranch.values()) {
                if (b.targetPriority() == UnitBranch.TargetPriority.BLOCKED_FIRST) {
                    blockedFirst++;
                }
            }
            check(sb, "★ 全枚举扫一遍：BLOCKED_FIRST 正好 3 个（实际 " + blockedFirst + "）",
                    blockedFirst == 3);

            // ---- A2) ★ 设计口径 拍板：攻击力侧只有解放者 ----
            //   原话：「目前而言，仅有解放者一职为攻击力乘区，其他直接给倍率的基本可以算
            //   伤害乘区、溅射系数与治疗口径」——《乘区归类表》表 1 的 11 条**一次判完**，
            //   所以那条口径在这里钉成断言（否则它只是文档里的一句话）。
            //   ① 攻击力侧**有且只有**解放者；
            //   ② 被点名的 4 条**根本不是乘区** ⇒ damageMod 必须是 NONE；
            //   ③ 但「不是乘区」≠「不做」：那 4 条里**已实现的两条**要真的在
            //      （撼地者的溅射系数 0.5、吟游者的每秒 10% 回血），另两条缺机制
            //      （行医缺元素损伤、护佑者缺技能效果）如实留在未实现清单里。
            sb.append("-- A2) ★ 设计口径：攻击力侧只有解放者 / 4 条「不是乘区」\n");
            {
                int attackSide = 0;
                UnitBranch onlyAttackSide = null;
                for (UnitBranch b : UnitBranch.values()) {
                    if (DamageMods.isAttackSide(b.damageMod())) {
                        attackSide++;
                        onlyAttackSide = b;
                    }
                }
                sb.append("   全枚举：isAttackSide = true 的有 ").append(attackSide).append(" 个")
                        .append(onlyAttackSide == null ? "" : "（" + onlyAttackSide.branchName() + "）")
                        .append('\n');
                check(sb, "★ 全枚举扫一遍：**攻击力侧正好 1 个**，且就是解放者"
                                + "（设计口径：「仅有解放者一职为攻击力乘区」）",
                        attackSide == 1 && onlyAttackSide == UnitBranch.GUARD_LIBERATOR);
                check(sb, "★ 4 条「不是乘区」的分支 damageMod 必须是 NONE"
                                + "（撼地者=溅射系数 / 行医·护佑者·吟游者=治疗口径）",
                        UnitBranch.GUARD_EARTHSHAKER.damageMod().kind()
                                == UnitBranch.DamageMod.Kind.NONE
                                && UnitBranch.MEDIC_WANDERING.damageMod().kind()
                                == UnitBranch.DamageMod.Kind.NONE
                                && UnitBranch.SUPPORTER_ABJURER.damageMod().kind()
                                == UnitBranch.DamageMod.Kind.NONE
                                && UnitBranch.SUPPORTER_BARD.damageMod().kind()
                                == UnitBranch.DamageMod.Kind.NONE);
                check(sb, "★ 撼地者那 50% 是**溅射系数**（已实现）：Aoe = 落点溅射 r=1.0 ×0.50",
                        UnitBranch.GUARD_EARTHSHAKER.aoe().kind() == UnitBranch.Aoe.Kind.SPLASH
                                && Math.abs(UnitBranch.GUARD_EARTHSHAKER.aoe().factor() - 0.50D) < 1e-9);
                check(sb, "★ 吟游者那 10% 是**治疗口径**（已实现）：Heal = REGEN，每 20 tick 回 10% 攻击力",
                        UnitBranch.SUPPORTER_BARD.heal().kind() == UnitBranch.Heal.Kind.REGEN
                                && Math.abs(UnitBranch.SUPPORTER_BARD.heal().regenFactor() - 0.10D) < 1e-9
                                && UnitBranch.SUPPORTER_BARD.heal().regenInterval() == 20);
            }

            // ---- B) 纯判据真值表（不碰世界）----
            sb.append("-- B) 纯判据真值表：DamageMods.factor / liberatorRamp\n");
            UnitBranch.DamageMod notBlocked = UnitBranch.GUARD_INSTRUCTOR.damageMod();
            check(sb, "教官：目标**不是**我挡的 ⇒ ×1.20",
                    Math.abs(DamageMods.factor(notBlocked, false, 9, false, 2) - 1.20D) < 1e-9);
            check(sb, "教官：目标**是**我挡的 ⇒ ×1.00（不乘）",
                    Math.abs(DamageMods.factor(notBlocked, true, 1, false, 2) - 1.00D) < 1e-9);
            UnitBranch.DamageMod far = UnitBranch.GUARD_LORD.damageMod();
            check(sb, "领主：格距 0 / 1 ⇒ ×1.00（含 1 格）",
                    Math.abs(DamageMods.factor(far, false, 0, false, 2) - 1.0D) < 1e-9
                            && Math.abs(DamageMods.factor(far, false, 1, false, 2) - 1.0D) < 1e-9);
            check(sb, "领主：格距 2 / 5 ⇒ ×0.80（1 格外）",
                    Math.abs(DamageMods.factor(far, false, 2, false, 2) - 0.80D) < 1e-9
                            && Math.abs(DamageMods.factor(far, false, 5, false, 2) - 0.80D) < 1e-9);
            // ★ 模组那三条（TARGET_BLOCKED / TARGET_AIR / TARGET_HEAVY）**现在已经没有分支用它们**
            //   （设计口径 把模组乘区摘进了待定槽）—— 所以这里用
            //   `DamageMod.of(kind, factor, false)` **造合成规格**来验真值表：
            //   真值表是纯函数，验的是「规则对不对」，不必依赖某个分支恰好带着它。
            UnitBranch.DamageMod blocked =
                    UnitBranch.DamageMod.of(UnitBranch.DamageMod.Kind.TARGET_BLOCKED, 1.15D, false);
            check(sb, "（合成规格）打「我挡住的」⇒ ×1.15；不是我挡的 ⇒ ×1.00",
                    Math.abs(DamageMods.factor(blocked, true, 1, false, 2) - 1.15D) < 1e-9
                            && Math.abs(DamageMods.factor(blocked, false, 1, false, 2) - 1.0D) < 1e-9);
            UnitBranch.DamageMod air =
                    UnitBranch.DamageMod.of(UnitBranch.DamageMod.Kind.TARGET_AIR, 1.10D, false);
            check(sb, "（合成规格）对空 ⇒ ×1.10；对地 ⇒ ×1.00",
                    Math.abs(DamageMods.factor(air, false, 3, true, 2) - 1.10D) < 1e-9
                            && Math.abs(DamageMods.factor(air, false, 3, false, 2) - 1.0D) < 1e-9);
            UnitBranch.DamageMod heavy =
                    UnitBranch.DamageMod.of(UnitBranch.DamageMod.Kind.TARGET_HEAVY, 1.15D, false);
            check(sb, "（合成规格）重量等级 3 / 4 ⇒ ×1.15；1 / 2 ⇒ ×1.00",
                    Math.abs(DamageMods.factor(heavy, false, 3, false, 3) - 1.15D) < 1e-9
                            && Math.abs(DamageMods.factor(heavy, false, 3, false, 4) - 1.15D) < 1e-9
                            && Math.abs(DamageMods.factor(heavy, false, 3, false, 1) - 1.0D) < 1e-9
                            && Math.abs(DamageMods.factor(heavy, false, 3, false, 2) - 1.0D) < 1e-9);
            check(sb, "解放者 ramp：0 秒 ×1.00 / 20 秒 ×2.00 / 40 秒 ×3.00 / 越界仍 ×3.00",
                    Math.abs(DamageMods.liberatorRamp(3.0D, 0) - 1.00D) < 1e-9
                            && Math.abs(DamageMods.liberatorRamp(3.0D, 400) - 2.00D) < 1e-9
                            && Math.abs(DamageMods.liberatorRamp(3.0D, 800) - 3.00D) < 1e-9
                            && Math.abs(DamageMods.liberatorRamp(3.0D, 5000) - 3.00D) < 1e-9);
            check(sb, "解放者 ramp：**每秒一档**（第 19 tick 仍属第 0 秒、第 20 tick 进第 1 秒）",
                    Math.abs(DamageMods.liberatorRamp(3.0D, 19) - 1.00D) < 1e-9
                            && Math.abs(DamageMods.liberatorRamp(3.0D, 20) - 1.05D) < 1e-9);
            // ★ 2026-10-05 口径修正：解放者原文写的是「**攻击力**逐渐提升至最高 +200%」，
            //   不是「伤害」⇒ 它归**攻击力**那一侧（走 attackMultiplier），
            //   **不再**在伤害乘区里乘一遍（否则会乘两次）。
            check(sb, "★ 解放者归「攻击力」那一侧：isAttackSide = true",
                    DamageMods.isAttackSide(UnitBranch.GUARD_LIBERATOR.damageMod()));
            check(sb, "★ 解放者在**伤害乘区**里恒 ×1.00（不重复乘）",
                    Math.abs(DamageMods.factor(UnitBranch.GUARD_LIBERATOR.damageMod(),
                            false, 9, true, 4) - 1.0D) < 1e-9);
            check(sb, "★ 攻击力倍率：0 秒 1.00 / 20 秒 2.00 / 40 秒 3.00",
                    Math.abs(DamageMods.attackMultiplier(
                            UnitBranch.GUARD_LIBERATOR.damageMod(), 0) - 1.00D) < 1e-9
                            && Math.abs(DamageMods.attackMultiplier(
                            UnitBranch.GUARD_LIBERATOR.damageMod(), 400) - 2.00D) < 1e-9
                            && Math.abs(DamageMods.attackMultiplier(
                            UnitBranch.GUARD_LIBERATOR.damageMod(), 800) - 3.00D) < 1e-9);
            check(sb, "★ 其余各条（真分支 + 合成规格）都是「伤害」那一侧（isAttackSide = false），倍率恒 1.00",
                    !DamageMods.isAttackSide(UnitBranch.GUARD_INSTRUCTOR.damageMod())
                            && !DamageMods.isAttackSide(UnitBranch.GUARD_LORD.damageMod())
                            && !DamageMods.isAttackSide(air)
                            && !DamageMods.isAttackSide(blocked)
                            && Math.abs(DamageMods.attackMultiplier(
                            UnitBranch.GUARD_INSTRUCTOR.damageMod(), 800) - 1.0D) < 1e-9);
            // 面板那一行的文案（设计口径：「解放者的效果从面板上看不到」）
            String libPanel = DamageMods.panelText(UnitBranch.GUARD_LIBERATOR.damageMod(), 400);
            // 面板文案·速射手那条原本读的是真分支的模组乘区（已摘出）⇒ 改用合成规格，
            // 这样「★模组 标记怎么画」这条文案逻辑仍然被验着。
            String airPanel = DamageMods.panelText(
                    UnitBranch.DamageMod.of(UnitBranch.DamageMod.Kind.TARGET_AIR, 1.10D, true), 0);
            String nonePanel = DamageMods.panelText(UnitBranch.GUARD_FIGHTER.damageMod(), 0);
            sb.append("   解放者那行面板文案：").append(libPanel).append('\n');
            sb.append("   速射手那行面板文案：").append(airPanel).append('\n');
            check(sb, "★ 面板文案·解放者：措辞写「攻击力」不写「伤害」，且带当前倍率 ×2.00",
                    libPanel.startsWith("攻击力爬升：") && libPanel.contains("×2.00")
                            && !libPanel.contains("伤害"));
            check(sb, "★ 面板文案·速射手：写「伤害乘区」，且带 ★模组 标记",
                    airPanel.startsWith("伤害乘区：") && airPanel.contains("×1.10")
                            && airPanel.contains("★模组"));
            check(sb, "★ 面板文案·无乘区分支（斗士）：空串（界面整行不画）", nonePanel.isEmpty());

            // ---- B2) ★ 判据本身：面板可见性（设计口径 给白金那个例子定的口径）----
            //   「面板上能不能直接看见」是**唯一判据**：
            //     · 攻击力侧 ⇒ 面板攻击力那个数字本身就跟着变（effectiveAttackDamage ≠ 面板）；
            //     · 伤害侧   ⇒ 面板数字**不动**，只有命中时的倍率 ≠ 1。
            //   ★ 这条比「计算时机」硬：白金的蓄力只看自己的状态（像解放者），
            //     但它面板上看不见 ⇒ 伤害侧。时机不是判据。
            sb.append("-- B2) 判据本身：面板可见性（攻击力侧面板会变 / 伤害侧面板不动）\n");
            {
                // ★ 攻击力在**战斗数据类**上（枚举只存分支数据，没有 attackDamage()）
                double libBase = com.guardianprotocol.combat.CombatStats
                        .attackDamage(UnitBranch.GUARD_LIBERATOR);
                double rampMult = DamageMods.attackMultiplier(
                        UnitBranch.GUARD_LIBERATOR.damageMod(), 800);
                check(sb, "★ 攻击力侧（解放者，爬满）：面板会变 —— 面板 "
                                + r2(libBase) + " ⇒ 有效 " + r2(libBase * rampMult)
                                + "（×" + r2(rampMult) + "）",
                        Math.abs(libBase * rampMult - libBase) > 1e-6D
                                && Math.abs(rampMult - 3.00D) < 1e-9);
                check(sb, "★ 伤害侧（教官）：面板**不动** —— 倍率 ×1.20 只出现在命中时",
                        Math.abs(DamageMods.attackMultiplier(
                                UnitBranch.GUARD_INSTRUCTOR.damageMod(), 800) - 1.0D) < 1e-9
                                && Math.abs(DamageMods.factor(
                                UnitBranch.GUARD_INSTRUCTOR.damageMod(),
                                false, 1, false, 2) - 1.20D) < 1e-9);
            }
            check(sb, "无乘区分支（斗士）⇒ 恒 ×1.00",
                    Math.abs(DamageMods.factor(UnitBranch.GUARD_FIGHTER.damageMod(),
                            true, 0, true, 4) - 1.0D) < 1e-9);
            check(sb, "负控：乘区为 null ⇒ ×1.00（不抛异常）",
                    Math.abs(DamageMods.factor(null, true, 0, true, 4) - 1.0D) < 1e-9);

            // ---- C) 解放者 ramp 状态（真实棋子）----
            sb.append("-- C) 解放者 ramp 状态：只在技能未激活时累积 / 激活期间归零 / 上限 / NBT\n");
            PixelUnit liberator = spawnPawn(level, spot, UnitBranch.GUARD_LIBERATOR);
            cleanup.add(liberator);
            check(sb, "刚放下 ⇒ ramp = 0", liberator.getRampTicks() == 0);
            for (int i = 0; i < 20; i++) {
                PawnCombatManager.onServerTick(level.getServer());
            }
            sb.append("   推 20 tick 后 ramp = ").append(liberator.getRampTicks())
                    .append("（倍率 ").append(r2(DamageMods.liberatorRamp(3.0D,
                            liberator.getRampTicks()))).append("）\n");
            check(sb, "技能未激活：推 20 tick ⇒ ramp = 20（倍率 1.05）",
                    liberator.getRampTicks() == 20
                            && Math.abs(DamageMods.liberatorRamp(3.0D, 20) - 1.05D) < 1e-9);
            liberator.setSkillActiveTicks(100);
            for (int i = 0; i < 5; i++) {
                PawnCombatManager.onServerTick(level.getServer());
            }
            check(sb, "★ 技能激活期间 ⇒ ramp 归零（所以技能一结束它就是 0 = 原文「技能结束时重置」）",
                    liberator.getRampTicks() == 0);
            liberator.setSkillActiveTicks(PixelUnit.SKILL_NOT_ACTIVE);
            PawnCombatManager.onServerTick(level.getServer());
            check(sb, "技能结束 ⇒ 从 0 重新开始爬（推 1 tick ⇒ 1）", liberator.getRampTicks() == 1);
            // ★ 攻击力那一侧的**唯一口径**：effectiveAttackDamage = 面板 × 倍率。
            //   面板那一行读它、伤害结算也读它（同一个数，不会出现「显示 ×3、打出来 ×1」）。
            double baseAtk = liberator.getBranch().attackDamage();
            liberator.setRampTicks(800);
            sb.append("   面板攻击力 ").append(r2(baseAtk)).append("｜ramp 到顶后有效攻击力 ")
                    .append(r2(liberator.effectiveAttackDamage())).append("（倍率 ")
                    .append(r2(liberator.attackMultiplier())).append("）\n");
            check(sb, "★ 有效攻击力 = 面板 × 倍率（爬满 ⇒ ×3.00）",
                    Math.abs(liberator.attackMultiplier() - 3.00D) < 1e-9
                            && Math.abs(liberator.effectiveAttackDamage() - baseAtk * 3.0D) < 1e-6D);
            liberator.setRampTicks(0);
            check(sb, "ramp 归零 ⇒ 有效攻击力退回面板值",
                    Math.abs(liberator.effectiveAttackDamage() - baseAtk) < 1e-9);
            liberator.setRampTicks(99999);
            check(sb, "★ 夹取：设 99999 ⇒ " + PixelUnit.LIBERATOR_RAMP_MAX_TICKS,
                    liberator.getRampTicks() == PixelUnit.LIBERATOR_RAMP_MAX_TICKS);
            liberator.setRampTicks(123);
            CompoundTag rampTag = liberator.saveWithoutId(new CompoundTag());
            check(sb, "NBT：写下了 " + PixelUnit.TAG_RAMP_TICKS + " = "
                            + rampTag.getInt(PixelUnit.TAG_RAMP_TICKS),
                    rampTag.getInt(PixelUnit.TAG_RAMP_TICKS) == 123);
            PixelUnit rampReload = spawnPawn(level, spot.offset(1, 0, 0), UnitBranch.GUARD_LIBERATOR);
            cleanup.add(rampReload);
            rampReload.load(rampTag);
            check(sb, "NBT 往返后 ramp 仍是 123", rampReload.getRampTicks() == 123);
            discardAll(cleanup);
            cleanup.clear();

            // ---- D) 行为层：真打一发，读倍率 ----
            sb.append("-- D) 行为层：真打一发，读「这一击乘了多少」\n");
            // 教官：攻击力面板 vs 实际掉血
            section = "教官（打未阻挡的 ×1.2）";
            PixelUnit sensei = spawnPawn(level, spot, UnitBranch.GUARD_INSTRUCTOR);
            cleanup.add(sensei);
            List<Mob> row1 = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D});
            double panel = sensei.getBranch().attackDamage();
            PawnCombatManager.debugResetDamageFactor();
            double[] d1 = hitOnce(level, sensei, row1, (float) panel);
            sb.append("   攻击力面板 ").append(r2(panel)).append("｜掉血 ").append(r2(d1[0]))
                    .append("｜倍率 ").append(r2(PawnCombatManager.debugLastDamageFactor())).append('\n');
            check(sb, "★ " + section + "：倍率 = 1.20",
                    Math.abs(PawnCombatManager.debugLastDamageFactor() - 1.20D) < 1e-9);
            check(sb, "★ " + section + "：实际掉血 = 面板 × 1.2（" + r2(panel * 1.2D) + "）",
                    Math.abs(d1[0] - panel * 1.2D) < 0.05D);
            check(sb, "★ 设计口径「不是攻击力」：打完**面板没变**（仍是 " + r2(panel) + "）",
                    Math.abs(sensei.getBranch().attackDamage() - panel) < 1e-9);

            // 领主：**同一只怪**先近后远 ⇒ 倍率跟着变。
            // ★ 这一条钉的是**计算时机**（设计口径 给的理由：「他们是在攻击这个动作
            //   **进行时**计算的，而不是在攻击前」）：伤害乘区按**命中那一刻**的现场重算，
            //   不是出手前算死。换两只怪各打一次证明不了这一点（那只能证明「按目标算」），
            //   必须是**同一只**、位置变了、倍率跟着变。
            section = "领主（格距 > 1 才降到 0.8）";
            PixelUnit lord = spawnPawn(level, spot, UnitBranch.GUARD_LORD);
            cleanup.add(lord);
            List<Mob> victimRow = aoeRow(level, cleanup, x0 + 1.0D, y, z0, new double[]{0.0D});
            Mob victim = victimRow.get(0);
            PawnCombatManager.debugResetDamageFactor();
            hitOnce(level, lord, victimRow, 20.0F);
            double nearFactor = PawnCombatManager.debugLastDamageFactor();
            int nearTiles = Targeting.chebyshevTiles(lord, victim);
            // 把**同一只**挪到 4 格外，再打一次
            victim.setPos(x0 + 4.0D, y, z0);
            int farTiles = Targeting.chebyshevTiles(lord, victim);
            sb.append("   同一只怪：格距 ").append(nearTiles).append(" ⇒ ×").append(r2(nearFactor))
                    .append("；挪到格距 ").append(farTiles).append(" 再打 ⇒ ×");
            PawnCombatManager.debugResetDamageFactor();
            hitOnce(level, lord, victimRow, 20.0F);
            double farFactor = PawnCombatManager.debugLastDamageFactor();
            sb.append(r2(farFactor)).append('\n');
            check(sb, "★ " + section + "：近处（格距 1）⇒ ×1.00", Math.abs(nearFactor - 1.0D) < 1e-9);
            check(sb, "★ " + section + "：远处（格距 4）⇒ ×0.80", Math.abs(farFactor - 0.80D) < 1e-9);
            check(sb, "★ " + section + "：**同一只怪**两次倍率不同 ⇒ 乘区在**命中那一刻**算"
                            + "（不是出手前算死）—— 设计口径 的口径：伤害乘区"
                            + "「在攻击这个动作进行时计算，而不是在攻击前」",
                    nearTiles == 1 && farTiles == 4
                            && Math.abs(nearFactor - farFactor) > 1e-6D);

            // 速射手：**模组乘区摘出之后的回归** —— 原先这里验「对空 ×1.1」（MAR-X），
            // 但设计口径 把模组乘区摘进了待定槽 ⇒ 现在**对地 / 对空都必须是 ×1.00**。
            // ★ 夹具（幻翼）保留：它给下面那条「模组已摘出」的断言当对照 ——
            //   幻翼确实被判成空中单位、倍率却仍是 1.0，才说明是「乘区没了」而不是「幻翼没飞」。
            section = "速射手（模组摘出后：对空也 ×1.00）";
            PixelUnit shooter = spawnPawn(level, spot, UnitBranch.SNIPER_MARKSMAN);
            cleanup.add(shooter);
            List<Mob> groundRow = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D});
            PawnCombatManager.debugResetDamageFactor();
            hitOnce(level, shooter, groundRow, 20.0F);
            double groundFactor = PawnCombatManager.debugLastDamageFactor();
            Phantom airMob = EntityType.PHANTOM.create(level);
            level.addFreshEntity(airMob);
            cleanup.add(airMob);
            airMob.setPos(x0 + 3.5D, y + 6.0D, z0);
            airMob.setNoGravity(true);
            airMob.setDeltaMovement(Vec3.ZERO);
            airMob.setNoAi(true);
            makeTanky(airMob, 5000.0D);
            setArmor(airMob, 0.0D);
            boolean flying = Targeting.isFlying(airMob);
            check(sb, "夹具：幻翼此刻被判为「空中单位」（否则下面那条等于没验）", flying);
            PawnCombatManager.debugResetDamageFactor();
            hitOnce(level, shooter, List.of(airMob), 20.0F);
            sb.append("   对地 ").append(r2(groundFactor)).append("｜对空 ")
                    .append(r2(PawnCombatManager.debugLastDamageFactor())).append('\n');
            check(sb, "★ " + section + "：对地 ⇒ ×1.00", Math.abs(groundFactor - 1.0D) < 1e-9);
            check(sb, "★ " + section + "：对空（夹具已自证是空中单位）⇒ **×1.00** —— "
                            + "MAR-X「对空 ×1.10」已进模组待定槽、不再默认生效",
                    Math.abs(PawnCombatManager.debugLastDamageFactor() - 1.0D) < 1e-9);

            // 攻城手：同上 —— SIE-X「对重 ×1.15」也进了待定槽 ⇒ 2 级 / 4 级都应是 ×1.00。
            section = "攻城手（模组摘出后：对重也 ×1.00）";
            PixelUnit besieger = spawnPawn(level, spot, UnitBranch.SNIPER_BESIEGER);
            cleanup.add(besieger);
            List<Mob> zombieRow = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D});
            IronGolem golem = EntityType.IRON_GOLEM.create(level);
            level.addFreshEntity(golem);
            cleanup.add(golem);
            golem.setPos(x0 + 3.5D, y, z0);
            golem.setNoAi(true);
            makeTanky(golem, 5000.0D);
            setArmor(golem, 0.0D);
            int lvZombie = Targeting.approxWeightLevel(zombieRow.get(0));
            int lvGolem = Targeting.approxWeightLevel(golem);
            sb.append("   僵尸重量等级 ").append(lvZombie).append("｜铁傀儡 ")
                    .append(lvGolem).append('\n');
            check(sb, "夹具：僵尸被判 2 级、铁傀儡至少 3 级（否则下面两条分不出差别）"
                            + "—— 铁傀儡抗击退 1.0 ⇒ 体积 3 级再 +1 = 4 级",
                    lvZombie == 2 && lvGolem >= DamageMods.HEAVY_LEVEL);
            PawnCombatManager.debugResetDamageFactor();
            hitOnce(level, besieger, zombieRow, 20.0F);
            double lightFactor = PawnCombatManager.debugLastDamageFactor();
            PawnCombatManager.debugResetDamageFactor();
            hitOnce(level, besieger, List.of(golem), 20.0F);
            check(sb, "★ " + section + "：对 2 级 ⇒ ×1.00", Math.abs(lightFactor - 1.0D) < 1e-9);
            check(sb, "★ " + section + "：对 4 级（铁傀儡，夹具已自证 ≥3 级）⇒ **×1.00** —— "
                            + "SIE-X「对重 ×1.15」已进模组待定槽、不再默认生效",
                    Math.abs(PawnCombatManager.debugLastDamageFactor() - 1.0D) < 1e-9);

            // 猎手：攻击消耗子弹 ⇒ ×1.20（2026-10-07 第六轮补；子弹系统未做 ⇒ 每击都算）
            section = "猎手（消耗子弹 ×1.20）";
            PixelUnit hunter = spawnPawn(level, spot, UnitBranch.SNIPER_HUNTER);
            cleanup.add(hunter);
            List<Mob> hunterRow = aoeRow(level, cleanup, x0 + 3.0D, y, z0, new double[]{0.0D});
            PawnCombatManager.debugResetDamageFactor();
            double[] hunterHit = hitOnce(level, hunter, hunterRow, 20.0F);
            sb.append("   倍率 ").append(r2(PawnCombatManager.debugLastDamageFactor()))
                    .append("｜掉血 ").append(r2(hunterHit[0])).append('\n');
            check(sb, "★ " + section + "：打任何目标 ⇒ ×1.20",
                    Math.abs(PawnCombatManager.debugLastDamageFactor() - 1.20D) < 1e-9);
            check(sb, "★ " + section + "：掉血 = 20.00 × 1.20 = 24.00（" + r2(hunterHit[0]) + "）",
                    Math.abs(hunterHit[0] - 24.0D) < 0.05D);

            // 散射手：**前方一横排** ×1.50，其余位置 ×1.00（2026-10-07 第六轮补）
            //   ★ 三个靶子刻意摆在**同名距离**的不同方向上：正前(forward=1) / 侧前(forward=0)
            //     / 正后(forward=-1) —— 只有「正前」那一排吃加成，这样才算验到「一横排」这条判据，
            //     而不是验到「距离近」。
            section = "散射手（前方一横排 ×1.50）";
            PixelUnit spreader = spawnPawn(level, spot, UnitBranch.SNIPER_SPREADSHOOTER);
            cleanup.add(spreader);
            List<Mob> frontRow = aoeRow(level, cleanup, x0 + 1.0D, y, z0, new double[]{0.0D});
            List<Mob> sideRow = aoeRow(level, cleanup, x0, y, z0 + 1.0D, new double[]{0.0D});
            List<Mob> backRow = aoeRow(level, cleanup, x0 - 1.0D, y, z0, new double[]{0.0D});
            boolean frontIsFront = Targeting.isInFrontRow(spreader, frontRow.get(0));
            boolean sideIsFront = Targeting.isInFrontRow(spreader, sideRow.get(0));
            boolean backIsFront = Targeting.isInFrontRow(spreader, backRow.get(0));
            sb.append("   朝向=").append(spreader.getFacing().displayName())
                    .append("｜正前 是前方一横排=").append(frontIsFront)
                    .append("／侧前=").append(sideIsFront)
                    .append("／正后=").append(backIsFront).append('\n');
            check(sb, "夹具·朝向坐标系：正前格 = 前方一横排，侧前/正后 = 不是"
                            + "（否则下面三条分不出差别）",
                    frontIsFront && !sideIsFront && !backIsFront);
            PawnCombatManager.debugResetDamageFactor();
            double[] frontHit = hitOnce(level, spreader, frontRow, 20.0F);
            double frontFactor = PawnCombatManager.debugLastDamageFactor();
            PawnCombatManager.debugResetDamageFactor();
            double[] sideHit = hitOnce(level, spreader, sideRow, 20.0F);
            double sideFactor = PawnCombatManager.debugLastDamageFactor();
            PawnCombatManager.debugResetDamageFactor();
            double[] backHit = hitOnce(level, spreader, backRow, 20.0F);
            double backFactor = PawnCombatManager.debugLastDamageFactor();
            sb.append("   正前 倍率 ").append(r2(frontFactor)).append(" 掉血 ").append(r2(frontHit[0]))
                    .append("｜侧前 ").append(r2(sideFactor)).append(" 掉血 ").append(r2(sideHit[0]))
                    .append("｜正后 ").append(r2(backFactor)).append(" 掉血 ").append(r2(backHit[0]))
                    .append('\n');
            check(sb, "★ " + section + "：正前方那一排 ⇒ ×1.50", Math.abs(frontFactor - 1.50D) < 1e-9);
            check(sb, "★ " + section + "：侧前（同一排距、但不在前方一横排）⇒ ×1.00",
                    Math.abs(sideFactor - 1.0D) < 1e-9);
            check(sb, "★ " + section + "：正后 ⇒ ×1.00", Math.abs(backFactor - 1.0D) < 1e-9);
            check(sb, "★ " + section + "：掉血真的差 1.50 倍（" + r2(sideHit[0] * 1.50D)
                            + " ≈ " + r2(frontHit[0]) + "）",
                    sideHit[0] > 0.0D && Math.abs(frontHit[0] - sideHit[0] * 1.50D) < 0.05D);

            // ---- E) 「isHeldBy 这条线真的通」的**行为层**（N2/N9 的另一半）----
            //   B 组验的是**纯判据**（factor(..., targetHeld=…, ...) 的取值）；这一组验的是
            //   **真棋子 + 真被挡的怪**走完 isHeldBy 这条路 —— 判据对了但连线接错，
            //   在 B 组里看不出来（那正是「乘区静默消失」的经典形态）。
            //
            //   ★ 2026-10-07 换了被测分支：原来用的是**要塞**（FOR-X「打我被挡住的 ×1.10」），
            //     但这轮把 5 条模组乘区摘进了待定槽 ⇒ 那条已经不再生效，用了它会变成
            //     「验一个不存在的东西」。改挑**教官** —— 它的条件是「目标**不是**我挡住的」
            //     （×1.20），**读的是同一个 isHeldBy**，而且是精二特性、不依赖模组。
            //     于是两个状态给出的答案正好**相反**（没被挡 ×1.20 / 被挡 ×1.00），
            //     判别力比原来那条还强。
            //
            //   ★ 顺序不能反：被挡名单是**粘滞**的（updateBlocking 的容量判定会保留已经挡住的），
            //     所以必须先取「谁都不挡」的基线，再打开白名单 ——
            //     反过来的话第二半拿到的仍然是「被挡」（清空白名单不会把它放掉）。
            sb.append("-- E) 行为层：isHeldBy 这条线真的通吗（教官：没被挡 ×1.20 / 被挡 ×1.00）\n");
            section = "教官（打「不是我挡住的」×1.20 / 打「我挡住的」×1.00）";
            {
                // 单开一块场地：D 组所有棋子都摆在同一个 spot 上，共用场地会让
                // 「被挡名单 / 上一击倍率」这两个读数被别的棋子搅进来（读数只有一个）。
                BlockPos espot = flatSpotNear(level, base.getX() + 16, base.getZ() + 16, base.getY());
                double ex0 = espot.getX() + 0.5D;
                double ez0 = espot.getZ() + 0.5D;
                double ey = espot.getY();
                PixelUnit sensei2 = spawnPawn(level, espot, UnitBranch.GUARD_INSTRUCTOR);
                cleanup.add(sensei2);
                // 与 /guardianprotocol block 的 E 组同一套几何：摆在 0.70 格 ⇒
                // 碰撞箱与棋子所在格重叠 0.1 格 ⇒ 进得了阻挡候选盒（FOOT）
                List<Mob> heldRow = aoeRow(level, cleanup, ex0 + 0.70D, ey, ez0, new double[]{0.0D});
                Mob heldVictim = heldRow.get(0);
                List<String> oldBlockWhite = BlockCandidates.setWhitelistOverride(List.of());
                try {
                    PawnCombatManager.onServerTick(level.getServer());
                    boolean heldOff = PawnCombatManager.debugBlocked(sensei2).contains(heldVictim);
                    PawnCombatManager.debugResetDamageFactor();
                    double[] off = hitOnce(level, sensei2, heldRow, 20.0F);
                    double offFactor = PawnCombatManager.debugLastDamageFactor();
                    // 放行僵尸 ⇒ 下一 tick 它就进被挡名单（粘滞规则不影响「从未挡过 ⇒ 新抓」）
                    BlockCandidates.setWhitelistOverride(List.of("minecraft:zombie"));
                    PawnCombatManager.onServerTick(level.getServer());
                    boolean heldOn = PawnCombatManager.debugBlocked(sensei2).contains(heldVictim);
                    PawnCombatManager.debugResetDamageFactor();
                    double[] on = hitOnce(level, sensei2, heldRow, 20.0F);
                    double onFactor = PawnCombatManager.debugLastDamageFactor();
                    sb.append("   同一只怪、同一个棋子：谁都不挡 ⇒ 被挡=").append(heldOff)
                            .append(" 倍率 ").append(r2(offFactor)).append(" 掉血 ").append(r2(off[0]))
                            .append("；白名单放行 ⇒ 被挡=").append(heldOn)
                            .append(" 倍率 ").append(r2(onFactor)).append(" 掉血 ").append(r2(on[0]))
                            .append('\n');
                    check(sb, "E1 夹具·谁都不挡 ⇒ 僵尸**不在**被挡名单（否则基线不是基线）", !heldOff);
                    check(sb, "E1 夹具·白名单放行 + 推一 tick ⇒ 僵尸**进了**被挡名单", heldOn);
                    check(sb, "★ " + section + "：目标**没**被我挡住 ⇒ ×1.20（isHeldBy 这条线真的通）",
                            Math.abs(offFactor - 1.20D) < 1e-9);
                    check(sb, "★ " + section + "：目标被我挡住 ⇒ ×1.00（加成立刻失效）",
                            Math.abs(onFactor - 1.0D) < 1e-9);
                    check(sb, "★ " + section + "：掉血真的差 1.20 倍（" + r2(on[0] * 1.20D)
                                    + " ≈ " + r2(off[0]) + "）",
                            on[0] > 0.0D && Math.abs(off[0] - on[0] * 1.20D) < 0.05D);
                } finally {
                    BlockCandidates.setWhitelistOverride(oldBlockWhite);
                }
            }

        } catch (Exception ex) {
            sb.append("   !! 场景异常（本场景的断言未执行完）：").append(ex).append('\n');
            check(sb, "场景 " + section + " 跑完没有抛异常", false);
        } finally {
            discardAll(cleanup);
            sb.append("   清理生成物 ").append(cleanup.size()).append(" 个\n");
        }
    }

    /**
     * 摆一排「受击者」（沿 +x 排开），护甲归零、血给足。
     *
     * <p>护甲必须归零：本工程的伤害走 {@code mobAttack}（物理），原版会按护甲<b>做减法</b>，
     * 于是「溅射系数 0.5」在结算后会变成 (0.5D - 护甲) 而不是 0.5×已减伤伤害 ——
     * 断言就变成在验护甲公式而不是验群伤。血给足的理由同踩坑记录：受击者不能被打死，
     * 否则「掉血多少」永远读不到。</p>
     *
     * @param offsets 每只相对第一个的 x 偏移（第一只 = 主目标，偏移 0）
     */
    private static List<Mob> aoeRow(ServerLevel level, List<Entity> cleanup, double x, double y,
                                    double z, double[] offsets) {
        List<Mob> out = new ArrayList<>();
        for (double off : offsets) {
            Zombie m = EntityType.ZOMBIE.create(level);
            if (m == null) {
                throw new IllegalStateException("无法创建僵尸");
            }
            level.addFreshEntity(m);
            m.setPos(x + off, y, z);
            setArmor(m, 0.0D);
            makeTanky(m, 5000.0D);
            m.setNoAi(true);          // 别让它自己走开：位移会改变距离，断言就飘了
            cleanup.add(m);
            out.add(m);
        }
        return out;
    }

    /**
     * 打一次并返回**这一排受击者**各自掉了多少血（下标与传入的 row 一一对应）。
     *
     * <p>★ 受击者名单必须由调用方给（而不是「去附近捞一遍」）：捞回来的顺序是 AABB 查询顺序，
     * 断言就会变成「押顺序」—— 换个查询实现就红，而且红得莫名其妙。</p>
     */
    private static double[] hitOnce(ServerLevel level, PixelUnit attacker, List<Mob> row, float damage) {
        PawnCombatManager.debugResetAoeCounters();
        double[] before = new double[row.size()];
        for (int i = 0; i < row.size(); i++) {
            before[i] = row.get(i).getHealth();
        }
        PawnCombatManager.applyHit(level, attacker, row.get(0), damage, PawnCombatManager.HitKind.MELEE);
        double[] out = new double[row.size()];
        for (int i = 0; i < row.size(); i++) {
            out[i] = before[i] - row.get(i).getHealth();
        }
        return out;
    }

    /**
     * 在基准格附近找一块「平地」当脚手架场地。
     *
     * <p>为什么不直接用 {@link #groundYAt} 的第一根柱子：真实存档里那一格可能是台阶边缘、
     * 一格水、或者紧贴着墙。出怪点放在那种地方，怪一出来就被地形挤住，
     * 报告里表现为「数量对、但位置奇怪」，排查起来要绕一大圈。
     * 这里多花一次 3x3 邻域的地面高度比较，换来「场地是可解释的」。</p>
     *
     * <p>候选点按由近到远排列，第一个「3x3 地面同高 + 目标格及上一格是空气」的格子即选中；
     * 一个都挑不出来时退回基准格（宁可场地差一点，也不要静默不测）。</p>
     */
    private static BlockPos flatSpotNear(ServerLevel level, int bx, int bz, int fromY) {
        int[][] offsets = {{0, 0}, {4, 0}, {0, 4}, {-4, 0}, {0, -4}, {8, 0}, {0, 8}, {8, 8}, {-8, 0}, {0, -8}};
        for (int[] off : offsets) {
            int x = bx + off[0];
            int z = bz + off[1];
            int y = groundYAt(level, x, z, fromY);
            boolean flat = true;
            for (int dx = -1; dx <= 1 && flat; dx++) {
                for (int dz = -1; dz <= 1 && flat; dz++) {
                    if (groundYAt(level, x + dx, z + dz, fromY) != y) {
                        flat = false;
                    }
                }
            }
            if (flat
                    && level.getBlockState(new BlockPos(x, y, z)).isAir()
                    && level.getBlockState(new BlockPos(x, y + 1, z)).isAir()
                    && !level.getBlockState(new BlockPos(x, y - 1, z)).isAir()) {
                return new BlockPos(x, y, z);
            }
        }
        return new BlockPos(bx, groundYAt(level, bx, bz, fromY), bz);
    }

    /** 出怪点附近指定类型的实体（只按「类型 + 半径」过滤，不猜其它条件）。 */
    private static List<Entity> mobsNear(ServerLevel level, BlockPos center, EntityType<?> type,
                                         double radius) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(center).inflate(radius);
        return new ArrayList<>(level.getEntitiesOfClass(Entity.class, box, e -> e.getType() == type));
    }

    // ------------------------------------------------------------------
    // 场景 5：在**真实存档**里打一场（用保护目标当嘲讽漏斗）
    // ------------------------------------------------------------------
    /**
     * 生成式入侵 <b>S1</b>（数据模型 / NBT 往返 / 校验 / 生命值钩子）的自验。
     *
     * <h3>为什么这一层值得单独一轮断言</h3>
     * <p>相位机（S2）与界面（S3）都建立在「这份配置读出来跟写进去一样」之上。这一层出错的表现
     * 极其隐蔽：<b>不报错、不崩</b>，只是「配好的 BOSS 血量倍率变成 1.0」「隐藏挑战永远不触发」——
     * 而这两种症状都会被误判成「相位机坏了」。所以先把存档往返与两条硬规则钉死。</p>
     *
     * <h3>两条硬规则（设计口径 原话）</h3>
     * <ul>
     *     <li>隐藏挑战限时 <b>≤ 最终 BOSS 战时间 / 3</b>；</li>
     *     <li>没有 BOSS 战相位（{@code bossTicks == 0}）时隐藏挑战必须是 0 ——
     *         它是「在 BOSS 战内并行」的，没有窗口可依附（否则表现为「静默永不触发」）。</li>
     * </ul>
     */
    private static void scenarioInvasion(StringBuilder sb, ServerLevel level, BlockPos base) {
        sb.append("-- invasion：生成式入侵配置（数据模型 / NBT 往返 / 校验 / 生命值钩子）\n");

        // ---- A) 配一份「什么都有」的配置：往返逐字段比对 ----
        InvasionConfig cfg = new InvasionConfig();
        CreatureRow row = new CreatureRow();
        row.setEntity("minecraft:husk");
        row.setStrategy(SpawnStrategy.LIMIT);
        row.setKillAmt(30);
        row.setCreateAmt(40);
        row.setExistingAmt(6);
        row.setSpawnCnt(3);
        row.setFirstSpawn(40);
        row.setInterval(60);
        // ★ 半径已按设计口径取消（`range` 字段删除）；落点由配置级的「出怪位置」决定
        cfg.addPosition(new BlockPos(base.getX() + 101, base.getY(), base.getZ()));
        cfg.addPosition(new BlockPos(base.getX() + 101, base.getY(), base.getZ() + 1));
        row.attrs().add(new CreatureRow.AttrMod("minecraft:generic.max_health", "ADDITION", 20.0D));
        row.potions().add(new CreatureRow.PotionMod("minecraft:strength", 2, 1200, true));
        row.setTargetOverride("minecraft:sheep");
        cfg.rows().add(row);
        BossEntry boss = new BossEntry();
        boss.setEntity("minecraft:ravager");
        boss.setName("测试BOSS");
        boss.setHealthMultiplier(4.0D);
        boss.attrs().add(new CreatureRow.AttrMod("minecraft:generic.attack_damage", "MULTIPLY_TOTAL", 1.5D));
        cfg.bosses().add(boss);
        cfg.setFinalBossIndex(0);
        cfg.setHiddenBossIndex(0);
        cfg.setHiddenBoost(2.0D);
        cfg.rewards().add(new ItemStack(Items.DIAMOND, 3));
        cfg.rewards().add(new ItemStack(Items.NETHERITE_INGOT, 1));
        cfg.setDisplayName("测试入侵");
        cfg.setRarity(4);
        cfg.setEntityColor(0xFF00FF);
        cfg.setPrepTicks(600);
        cfg.setCombatTicks(2400);
        cfg.setRewardTicks(600);
        cfg.setBossTicks(1800);
        cfg.setHiddenTicks(600);                 // 1800/3 = 600，正好压在边界上
        cfg.setTargetPos(new BlockPos(base.getX() + 100, base.getY(), base.getZ()));
        List<String> clean = cfg.validate();
        check(sb, "合法配置不该有校验提示（实际 " + clean.size() + " 条：" + clean + "）", clean.isEmpty());
        check(sb, "隐藏挑战正好 = BOSS战/3 时被接受（" + cfg.hiddenTicks() + " = " + cfg.bossTicks() + "/3）",
                cfg.hiddenTicks() == 600);

        InvasionConfig back = InvasionConfig.load(cfg.save());
        CreatureRow r2 = back.rows().isEmpty() ? new CreatureRow() : back.rows().get(0);
        check(sb, "★ NBT 往返：生物行 " + back.rows().size() + " 行 / BOSS " + back.bosses().size()
                        + " 只 / 奖励 " + back.rewards().size() + " 项",
                back.rows().size() == 1 && back.bosses().size() == 1 && back.rewards().size() == 2);
        check(sb, "★ NBT 往返：实体 id 与策略（" + r2.entity() + " / " + r2.strategy().display + "）",
                "minecraft:husk".equals(r2.entity()) && r2.strategy() == SpawnStrategy.LIMIT);
        check(sb, "★ NBT 往返：杀/生/在场 = " + r2.killAmt() + '/' + r2.createAmt() + '/' + r2.existingAmt(),
                r2.killAmt() == 30 && r2.createAmt() == 40 && r2.existingAmt() == 6);
        check(sb, "★ NBT 往返：单次/首次/间隔 = " + r2.spawnCnt() + '/' + r2.firstSpawn() + '/' + r2.interval(),
                r2.spawnCnt() == 3 && r2.firstSpawn() == 40 && r2.interval() == 60);
        check(sb, "★ NBT 往返：出怪位置 " + back.positions().size() + " 个（内容一致）",
                back.positions().size() == 2 && back.positions().equals(cfg.positions()));
        check(sb, "★ NBT 往返：属性 " + r2.attrs().size() + " 条 / 药水 " + r2.potions().size()
                        + " 条 / 改目标=" + r2.targetOverride(),
                r2.attrs().size() == 1 && r2.potions().size() == 1
                        && "minecraft:sheep".equals(r2.targetOverride()));
        check(sb, "★ NBT 往返：BOSS 血量×" + back.bosses().get(0).healthMultiplier()
                        + "，名字=" + back.bosses().get(0).name(),
                back.bosses().get(0).healthMultiplier() == 4.0D && "测试BOSS".equals(back.bosses().get(0).name()));
        check(sb, "★ NBT 往返：奖励首项 " + back.rewards().get(0).getCount() + " 个钻石",
                back.rewards().get(0).getCount() == 3 && back.rewards().get(0).is(Items.DIAMOND));
        check(sb, "★ NBT 往返：五个计时字段（备战/战斗/奖励/BOSS/隐藏）",
                back.prepTicks() == 600 && back.combatTicks() == 2400 && back.rewardTicks() == 600
                        && back.bossTicks() == 1800 && back.hiddenTicks() == 600);
        check(sb, "★ NBT 往返：显示三项（名字/稀有度/颜色）",
                "测试入侵".equals(back.displayName()) && back.rarity() == 4 && back.entityColor() == 0xFF00FF);
        check(sb, "★ NBT 往返：支路绑定（保护目标坐标一致）",
                back.targetPos() != null && back.targetPos().equals(cfg.targetPos()));
        check(sb, "★ 最终BOSS 解析到配置的那一只",
                back.finalBoss() != null && "minecraft:ravager".equals(back.finalBoss().entity()));
        check(sb, "★ 隐藏挑战 BOSS 与增强倍率（" + back.hiddenBoost() + "×）",
                back.hiddenBoss() != null && back.hiddenBoost() == 2.0D);

        // ---- B) 隐藏挑战的两条硬规则 ----
        InvasionConfig over = new InvasionConfig();
        over.setBossTicks(600);
        over.setHiddenTicks(400);                // 超过 600/3 = 200
        List<String> notesOver = over.validate();
        sb.append("   隐藏挑战越界 400 → 提示：").append(notesOver).append('\n');
        check(sb, "★ 隐藏挑战超 1/3 被夹到 200（实际 " + over.hiddenTicks() + "）", over.hiddenTicks() == 200);
        check(sb, "★ 夹取留下了原因（提到 1/3）",
                notesOver.stream().anyMatch(s -> s.contains("1/3")));

        InvasionConfig noBoss = new InvasionConfig();
        noBoss.setBossTicks(0);
        noBoss.setHiddenTicks(100);
        List<String> notesNoBoss = noBoss.validate();
        check(sb, "★ 没有 BOSS 战相位时隐藏挑战被清零（实际 " + noBoss.hiddenTicks() + "）",
                noBoss.hiddenTicks() == 0);
        check(sb, "★ 并说明了原因（提及 BOSS）",
                notesNoBoss.stream().anyMatch(s -> s.contains("BOSS")));

        // ---- C) 越界一律夹取（不是抛异常）----
        InvasionConfig wild = new InvasionConfig();
        for (int i = 0; i < InvasionConfig.MAX_ROWS + 3; i++) {
            CreatureRow extra = new CreatureRow();
            extra.setEntity("minecraft:zombie");
            wild.rows().add(extra);
        }
        CreatureRow first = wild.rows().get(0);
        first.setSpawnCnt(0);
        first.setInterval(0);
        first.setFirstSpawn(-100);
        first.setKillAmt(-5);
        wild.setRarity(99);
        List<String> notesWild = wild.validate();
        check(sb, "★ 生物行裁到上限 " + InvasionConfig.MAX_ROWS + "（实际 " + wild.rows().size() + "）",
                wild.rows().size() == InvasionConfig.MAX_ROWS);
        check(sb, "★ 单次生成数 0 → 1（实际 " + first.spawnCnt() + "）", first.spawnCnt() == 1);
        check(sb, "★ 间隔 0 → 1（实际 " + first.interval() + "）", first.interval() == 1);
        check(sb, "★ 首次延迟 -100 → 0（实际 " + first.firstSpawn() + "）", first.firstSpawn() == 0);
        check(sb, "★ 击杀数 -5 → 0（实际 " + first.killAmt() + "）", first.killAmt() == 0);
        check(sb, "★ 稀有度 99 → " + InvasionConfig.MAX_RARITY + "（实际 " + wild.rarity() + "）",
                wild.rarity() == InvasionConfig.MAX_RARITY);
        check(sb, "★ 越界都留下了原因（实际 " + notesWild.size() + " 条）", !notesWild.isEmpty());

        // ---- D) 坏输入只降级 ----
        // 出怪位置：最少 1 个、最多 4 个（设计口径）
        InvasionConfig posCfg = new InvasionConfig();
        for (int i = 0; i < InvasionConfig.MAX_POSITIONS + 2; i++) {
            posCfg.addPosition(new BlockPos(base.getX() + i, base.getY(), base.getZ() + 200));
        }
        check(sb, "★ 出怪位置裁到上限 " + InvasionConfig.MAX_POSITIONS + "（实际 " + posCfg.positions().size() + "）",
                posCfg.positions().size() == InvasionConfig.MAX_POSITIONS);
        check(sb, "★ 重复标记同一格被拒绝", !posCfg.addPosition(posCfg.positions().get(0)));
        InvasionConfig noPos = new InvasionConfig();
        CreatureRow onlyRow = new CreatureRow();
        onlyRow.setEntity("minecraft:zombie");
        noPos.rows().add(onlyRow);
        List<String> notesNoPos = noPos.validate();
        check(sb, "★ 有生物行却一个出怪位置都没有 → 报「不会出任何怪」（最少 "
                        + InvasionConfig.MIN_POSITIONS + " 个）",
                notesNoPos.stream().anyMatch(s -> s.contains("出怪位置")));
        CompoundTag badTag = new CompoundTag();
        badTag.putString("strategy", "某种不存在的策略");
        CreatureRow badRow = CreatureRow.load(badTag);
        check(sb, "★ 认不出的策略退回默认档（" + badRow.strategy().display + "）",
                badRow.strategy() == SpawnStrategy.KILL);
        InvasionConfig empty = new InvasionConfig();
        empty.rows().add(new CreatureRow());
        empty.setFinalBossIndex(7);
        empty.setHiddenBossIndex(-9);
        List<String> notesEmpty = empty.validate();
        check(sb, "★ 空行不算「已配置」（configuredRows=" + empty.configuredRows().size() + "）",
                empty.configuredRows().isEmpty());
        check(sb, "★ 开着却一行都没配 → 明确报出来（不静默）",
                notesEmpty.stream().anyMatch(s -> s.contains("不会出任何怪")));
        check(sb, "★ 无效的 BOSS 下标回退（final=" + empty.finalBossIndex()
                        + " hidden=" + empty.hiddenBossIndex() + "）",
                empty.finalBossIndex() == -1 && empty.hiddenBossIndex() == -1);

        // ---- E) 出怪位置标定器（物品逻辑写成静态方法，所以不需要真人玩家就能测）----
        ItemStack marker = new ItemStack(com.guardianprotocol.item.ModItems.SPAWN_MARKER.get());
        BlockPos markA = new BlockPos(base.getX() + 300, base.getY(), base.getZ());
        BlockPos markB = new BlockPos(base.getX() + 301, base.getY(), base.getZ());
        check(sb, "★ 未绑定时不能标出怪位置",
                !com.guardianprotocol.item.SpawnMarkerItem.addMarkedPosition(marker, markA));
        check(sb, "★ 首次绑定成功", com.guardianprotocol.item.SpawnMarkerItem.bindOnce(marker, markA));
        check(sb, "★ 不能重复绑定（第二次被拒绝，且绑定不变）",
                !com.guardianprotocol.item.SpawnMarkerItem.bindOnce(marker, markB)
                        && markA.equals(com.guardianprotocol.item.SpawnMarkerItem.boundPos(marker)));
        check(sb, "★ 绑定后能标记位置",
                com.guardianprotocol.item.SpawnMarkerItem.addMarkedPosition(marker, markA));
        check(sb, "★ 同一格重复标记被拒绝",
                !com.guardianprotocol.item.SpawnMarkerItem.addMarkedPosition(marker, markA));
        for (int i = 1; i < InvasionConfig.MAX_POSITIONS; i++) {
            com.guardianprotocol.item.SpawnMarkerItem.addMarkedPosition(marker,
                    new BlockPos(base.getX() + 300 + i, base.getY(), base.getZ()));
        }
        check(sb, "★ 标满 " + InvasionConfig.MAX_POSITIONS + " 个（实际 "
                        + com.guardianprotocol.item.SpawnMarkerItem.markedPositions(marker).size() + "）",
                com.guardianprotocol.item.SpawnMarkerItem.markedPositions(marker).size()
                        == InvasionConfig.MAX_POSITIONS);
        check(sb, "★ 满员后再标第 " + (InvasionConfig.MAX_POSITIONS + 1) + " 个被拒绝",
                !com.guardianprotocol.item.SpawnMarkerItem.addMarkedPosition(marker,
                        new BlockPos(base.getX() + 400, base.getY(), base.getZ())));
        com.guardianprotocol.item.SpawnMarkerItem.clearMarkedPositions(marker);
        check(sb, "★ 潜行清空后位置归零、绑定仍在",
                com.guardianprotocol.item.SpawnMarkerItem.markedPositions(marker).isEmpty()
                        && com.guardianprotocol.item.SpawnMarkerItem.isBound(marker));

        // ---- F) 生命值钩子（后续塔防循环项目的唯一入口）----
        int[] got = new int[3];
        Invasions.LifeListener old = Invasions.setLifeListener(new Invasions.LifeListener() {
            @Override
            public void onLaneLeak(ServerLevel lv, BlockPos lane, int leaked) {
                got[0]++;
                got[1] = leaked;
            }

            @Override
            public void onTimeout(ServerLevel lv, BlockPos pos, int leaked) {
                got[2]++;
            }
        });
        try {
            Invasions.notifyLaneLeak(level, cfg.targetPos(), 7);
            Invasions.notifyTimeout(level, base, 3);
            check(sb, "★ 支路漏怪回调被调用且带上数量（次数 " + got[0] + "，漏 " + got[1] + " 只）",
                    got[0] == 1 && got[1] == 7);
            check(sb, "★ 超时回调被调用（次数 " + got[2] + "）", got[2] == 1);
            Invasions.setLifeListener(new Invasions.LifeListener() {
                @Override
                public void onLaneLeak(ServerLevel lv, BlockPos lane, int leaked) {
                    throw new IllegalStateException("测试：监听器故意抛异常");
                }

                @Override
                public void onTimeout(ServerLevel lv, BlockPos pos, int leaked) {
                    throw new IllegalStateException("测试：监听器故意抛异常");
                }
            });
            boolean survived;
            try {
                Invasions.notifyLaneLeak(level, cfg.targetPos(), 1);
                survived = true;
            } catch (Throwable t) {
                survived = false;
            }
            check(sb, "★ 外部监听器抛异常被吞掉（不牵连服务端 tick）", survived);
        } finally {
            Invasions.setLifeListener(old);
        }
    }

    /**
     * 实机场景：直接利用存档里已有的**保护目标**（自带嘲讽）当漏斗，
     * 把棋子摆在它的正东侧、也就是被嘲讽的敌人必经之路上，然后跑真 tick 观察。
     *
     * <h3>为什么这条比前几个场景更有价值</h3>
     * <p>前四个场景是「我摆好、读内部状态」的合成环境。这一条是<b>真实通路</b>：
     * 敌人被嘲讽 → 朝保护目标走 → 走进棋子攻击范围 → 被挡 / 被打 → 漏过去的到目标处被抹杀。
     * 全程只靠 {@code ProtectTargetManager} 与 {@code PawnCombatManager} 自己跑，
     * 所以「嘲讽 + 阻挡 + 索敌 + 攻击 + 抹杀」之间的相互作用也一起验了。</p>
     *
     * <p>找不到保护目标就明确说「跳过」，不假装通过。</p>
     *
     * <h3>关于棋子摆在哪</h3>
     * <p>不猜「哪边是东」：存档里保护目标的位置从方块实体读，棋子摆在目标的<b>正东</b>
     * （+X，世界方向约定见 {@code PieceFacing}）8 格处、朝西对着目标 ——
     * 这样被嘲讽的敌人从东边过来时会先撞上棋子。若那格被建筑占住，
     * 用 {@code -Dguardianprotocol.selftest.base=x,y,z} 指定一个空位即可。</p>
     */
    private static void scenarioLive(StringBuilder sb, ServerLevel level, BlockPos base) {
        List<com.guardianprotocol.block.ProtectTargetBlockEntity> targets =
                com.guardianprotocol.block.ProtectTargetBlockEntity.activeTargets();
        List<com.guardianprotocol.block.ProtectTargetBlockEntity> here = new ArrayList<>();
        for (var t : targets) {
            if (t.getLevel() == level) {
                here.add(t);
            }
        }
        sb.append("-- live: 本世界保护目标 " + here.size() + " 个 / 静态表 " + targets.size() + " 个\n");
        if (here.isEmpty()) {
            sb.append("   → 跳过：这个世界里没有已加载的保护目标\n");
            return;
        }
        var target = here.get(0);
        BlockPos tp = target.getBlockPos();
        sb.append("   保护目标 ").append(fmt(tp))
                .append(" 血量 ").append(target.getHealth()).append('/').append(target.getMaxHealth())
                .append(" 嘲讽档=").append(target.getTauntRadius().display()).append('\n');

        // 棋子摆在保护目标的必经之路上（默认正东 4 格、朝西）：
        // 出怪点在东边，敌人被嘲讽后往西走，先撞上棋子。
        int[] off = liveOffset();
        BlockPos pawnPos = new BlockPos(tp.getX() + off[0], tp.getY() + off[1], tp.getZ() + off[2]);
        pawnPos = new BlockPos(pawnPos.getX(),
                groundYAt(level, pawnPos.getX(), pawnPos.getZ(), tp.getY()), pawnPos.getZ());
        sb.append("   棋子摆位 ").append(fmt(pawnPos)).append("（保护目标偏移 ")
                .append(off[0]).append(',').append(off[1]).append(',').append(off[2])
                .append("，朝西）\n");
        sb.append(describeCommandBlocks(level, tp, 32));

        List<Entity> cleanup = new ArrayList<>();
        PixelUnit pawn = spawnPawn(level, pawnPos, UnitBranch.GUARD_CENTURION);
        pawn.setFacing(PieceFacing.WEST);
        pawn.setFacing(PieceFacing.WEST);
        cleanup.add(pawn);

        // ★ 这里**不手动推进 tick**：敌人的移动、嘲讽 AI、寻路都依赖服务端自己的 tick，
        //   而手动 tick 会把这些跑成「一次心跳里连做 200 步」，落点与真实情况不同。
        //   所以把「观察 + 写报告 + 关服」交给服务端 tick 事件按真实时间推进
        //   （见 ModEvents.onServerTick 对 tickLiveObservation 的调用）。
        LIVE = new LiveObservation(level, pawn, target, cleanup, liveTicks());
        sb.append("   已启动实机观察：").append(liveTicks())
                .append(" tick（约 ").append(liveTicks() / 20).append(" 秒）内按真实节奏跑；\n")
                .append("   结束时服务端会再追加一份「观察结果」到本报告，然后自行关闭。\n");
    }

    /**
     * 把保护目标附近的**命令方块**读出来：位置、指令、触发条件。
     *
     * <p>为什么值得专门做：live 场景第一次跑时，10 秒里「附近敌人=0」，
     * 而报告里没有任何信息能区分「命令方块没触发」「指令是 /kill 而不是 /summon」
     * 「出怪点在 24 格外」。命令方块的内容是**服务端权威事实**，直接读出来就不用猜。
     * 用原版的 {@code CommandBlockEntity} 读，不自己解析 NBT。</p>
     */
    /**
     * 实机演示：**不依赖命令方块**，自己生成一只「测试沙包」朝保护目标走，看棋子挡不挡得住。
     *
     * <p>为什么要有这一条：实机存档里的出怪命令方块**需要红石、且未通电**（报告里读出来了），
     * 所以 live 场景 10 秒里「附近敌人=0」。而「嘲讽把敌人拉过来 → 棋子挡在必经之路上」
     * 这条真实通路必须验：它是「阻挡 + 索敌 + 攻击 + 抹杀」四者相互作用的唯一入口。</p>
     *
     * <p>做法：在保护目标正东 {@code 12} 格生成一只**和实机命令方块同款**的沙包
     * （husk，500 血、攻击 2），让它自己寻路走向保护目标，棋子在正东 4 格等着。
     * 全程不碰存档里的任何方块，跑完 discard。</p>
     *
     * <p>读三个数就够判：沙包最终停在离棋子多远（期望 ≈ 阻挡距离 1.3）、
     * 它掉了多少血（说明棋子在打它）、保护目标有没有掉血（说明漏过去了）。</p>
     */
    private static void scenarioLivedemo(StringBuilder sb, ServerLevel level) {
        List<com.guardianprotocol.block.ProtectTargetBlockEntity> here = new ArrayList<>();
        for (var t : com.guardianprotocol.block.ProtectTargetBlockEntity.activeTargets()) {
            if (t.getLevel() == level) {
                here.add(t);
            }
        }
        sb.append("-- livedemo: 本世界保护目标 ").append(here.size()).append(" 个\n");
        if (here.isEmpty()) {
            sb.append("   → 跳过：这个世界里没有已加载的保护目标\n");
            return;
        }
        var target = here.get(0);
        BlockPos tp = target.getBlockPos();
        int[] off = liveOffset();
        // ★ 用**保护目标自己那一层**作为地面基准，不做地面扫描。
        //
        // 实测踩过的两个坑，都出在「扫描找地面」上：
        //   ① 世界最高处扫描：专用服务器的 `getHeight(MOTION_BLOCKING)` 在未完全
        //      生成的柱子上会返回建筑高度上限，实体被摆到天上；
        //   ② 往下扫描找实心：设计的超平坦存档在保护目标西北一侧**只有基岩一层**
        //      （y=-64 Bedrock，上面全是空气，玻璃地坪从别处才开始），于是扫到基岩
        //      就返回 y=-64 —— 而基岩上面那格放不了实体，husk 一生成就**坠入虚空**：
        //      报告里 y 从 -64 一路掉到 -592、delta.y 越来越负、onGround=false，
        //      看起来像「怪物越走越远」，其实是掉下去了（这个是靠逐 tick 轨迹才认出来的）。
        // 结论：**保护目标所在的那一层就是玩家用来走的地面**，直接用它最稳。
        int floorY = tp.getY() + 1;
        BlockPos pawnPos = new BlockPos(tp.getX() + off[0], floorY + off[1], tp.getZ() + off[2]);
        sb.append("   保护目标 ").append(fmt(tp)).append(" 血 ").append(target.getHealth())
                .append(" 嘲讽档=").append(target.getTauntRadius().display())
                .append("（地面基准 y=").append(floorY).append("）\n");
        sb.append("   棋子 ").append(fmt(pawnPos)).append(" 朝西；沙包从正东 12 格走来\n");

        int strays = cleanupStragglers(level, tp, 64);
        if (strays > 0) {
            sb.append("   清理历史自验遗留实体 ").append(strays).append(" 个（防污染存档）\n");
        }

        List<Entity> cleanup = new ArrayList<>();
        PixelUnit pawn = spawnPawn(level, pawnPos, UnitBranch.GUARD_CENTURION);
        pawn.setFacing(PieceFacing.WEST);
        pawn.setFacing(PieceFacing.WEST);
        cleanup.add(pawn);
        try {
            // 沙包：husk + 500 血 + 攻击 2（与实机命令方块里的 NBT 一致）。
            // ★ 位置必须**显式**setPos 到棋子正东 12 格、同一层；不能只靠 spawn() 里
            //   那个「按基准方块中心 + 偏移」的默认摆位（第一版就是那样把 X/Z 摆错了一格）。
            Mob husk = spawn(level, EntityType.HUSK, pawnPos.getX(), floorY, pawnPos.getZ(),
                    "沙包(husk 500血)");
            var hp = husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(500.0D);
                husk.setHealth(500.0F);
            }
            var atk = husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            if (atk != null) {
                atk.setBaseValue(2.0D);
            }
            cleanup.add(husk);
            husk.setNoAi(false);                    // 要它自己走
            husk.setPos(pawnPos.getX() + 12.0D, floorY, pawnPos.getZ() + 0.5D);

            int targetHp0 = target.getHealth();
            LIVE = new LiveObservation(level, pawn, target, cleanup, liveTicks());
            LIVE.watch = husk;
            LIVE.watchHp0 = 500.0D;
            LIVE.targetHp0 = targetHp0;
            sb.append("   已启动实机演示：").append(liveTicks())
                    .append(" tick 内观察（沙包起始血 500，保护目标血 ").append(targetHp0).append("）\n");
        } catch (Exception ex) {
            discardAll(cleanup);
            sb.append("   !! 生成沙包失败：").append(ex).append('\n');
        }
    }

    private static String describeCommandBlocks(ServerLevel level, BlockPos center, int radius) {
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -8, -radius),
                center.offset(radius, 8, radius))) {
            var be = level.getBlockEntity(p);
            if (be instanceof net.minecraft.world.level.block.entity.CommandBlockEntity cb) {
                found++;
                sb.append("   命令方块").append(fmt(p.immutable()))
                        .append(" 触发=").append(cb.isAutomatic() ? "自动" : "需要红石")
                        .append(cb.isPowered() ? "(已通电)" : "(未通电)")
                        .append(" 指令=").append(cb.getCommandBlock().getCommand())
                        .append('\n');
            }
        }
        if (found == 0) {
            sb.append("   附近 ").append(radius).append(" 格内没有命令方块\n");
        }
        return sb.toString();
    }

    /**
     * 清理历史自验留下的实体：**所有非我方敌人 + 所有棋子**，只要在保护目标附近。
     *
     * <p>为什么需要：早先几次失败的运行（服务器在半途崩掉/被 halt）没走到清理那一步，
     * 于是把测试用的 husk 与棋子**留在了实机存档里**。虽然后来改成「先复制存档再跑」，
     * 已经污染的那一份还得自己擦干净。只删「敌人 + 本 mod 棋子」，不碰任何方块与玩家物品。</p>
     */
    private static int cleanupStragglers(ServerLevel level, BlockPos center, int radius) {
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(center).inflate(radius);
        List<LivingEntity> victims = new ArrayList<>(level.getEntitiesOfClass(
                LivingEntity.class, box,
                e -> (e instanceof PixelUnit) || com.guardianprotocol.combat.Targeting.isEnemy(e)));
        for (LivingEntity e : victims) {
            e.discard();
        }
        return victims.size();
    }

    /** 实机观察跑多少 tick（默认 200 tick ≈ 10 秒，可用系统属性覆盖）。 */
    private static int liveTicks() {
        int v = intProp(PROP_LIVE_TICKS, 200);
        return v <= 0 ? 200 : v;
    }

    /** 棋子在保护目标一侧的偏移，默认正东 4 格。 */
    private static int[] liveOffset() {
        String raw = System.getProperty(PROP_LIVE_OFFSET, "").trim();
        if (!raw.isEmpty()) {
            String[] p = raw.split(",");
            if (p.length == 3) {
                try {
                    return new int[]{Integer.parseInt(p[0].trim()),
                            Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
                } catch (NumberFormatException ignored) {
                    // 落到默认值
                }
            }
        }
        return new int[]{4, 0, 0};
    }

    private static int intProp(String key, int def) {
        try {
            return Integer.parseInt(System.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /**
     * 观察行里附上「附近有什么敌方实体」——这是判断**出怪点在哪**的直接证据。
     *
     * <p>没有它就只能猜：实测第一次把棋子摆在保护目标正东 8 格，10 秒里一个敌人都没来，
     * 而报告里没有任何信息能区分「命令方块没触发」和「敌人在别处就被抹杀了」。
     * 现在每次观察都列出 24 格内的敌对实体及其到棋子/目标的距离。</p>
     */
    private static String nearbyReport(LiveObservation live) {
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(live.target.getBlockPos()).inflate(24.0D);
        List<LivingEntity> near = live.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> !(e instanceof PixelUnit)
                        && com.guardianprotocol.combat.Targeting.isEnemy(e));
        if (near.isEmpty()) {
            return " 附近敌人=0";
        }
        StringBuilder sb = new StringBuilder(" 附近敌人=").append(near.size()).append(':');
        int shown = 0;
        for (LivingEntity e : near) {
            if (shown++ >= 6) {
                sb.append(" ...");
                break;
            }
            sb.append(' ').append(e.getType().getDescription().getString())
                    .append("@").append(String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)",
                            e.getX(), e.getY(), e.getZ()))
                    .append(" 距棋子=").append(r1(Math.sqrt(e.distanceToSqr(live.pawn))))
                    .append(" 距目标=").append(r1(Math.sqrt(distanceToSqr(e,
                            live.target.getBlockPos().getX() + 0.5D,
                            live.target.getBlockPos().getY() + 0.5D,
                            live.target.getBlockPos().getZ() + 0.5D))));
        }
        return sb.toString();
    }

    /** 沙包（livedemo）专用：它离棋子多远、掉了多少血、保护目标掉了多少。 */
    private static String watchReport(LiveObservation live) {
        if (live.watch == null) {
            return "";
        }
        Entity w = live.watch;
        // 注意：Entity 上没有 isDeadOrDying()（那是 LivingEntity 的），先判类型
        LivingEntity living = w instanceof LivingEntity le ? le : null;
        if (w.isRemoved() || (living != null && living.isDeadOrDying())) {
            return String.format(Locale.ROOT,
                    " | 沙包已消失(被抹杀/击杀) 血 %.1f/%.1f 保护目标血 %d/%d",
                    living == null ? 0.0D : living.getHealth(),
                    live.watchHp0, live.target.getHealth(), live.target.getMaxHealth());
        }
        // 带上导航状态：这是判断「嘲讽 goal 有没有把它指过去」的直接证据
        String nav = "?";
        if (living instanceof net.minecraft.world.entity.Mob m) {
            var path = m.getNavigation().getPath();
            nav = path == null
                    ? (m.getNavigation().isDone() ? "无路径(done)" : "无路径")
                    // ★ PathNavigation.getTarget() 返回 BlockPos（整数格），所以必须用 %d。
                    //   早先写成 %.1f：跑到观察第一行就抛 IllegalFormatConversionException
                    //   (f != Integer)，把整个 ServerTickEvent 打断 —— **服务器当场崩**。
                    //   教训：日志格式化也会崩服务端，String.format 的占位符要对着实参类型核。
                    : String.format(Locale.ROOT, "路径→(%d,%d,%d) 目标节点%d",
                            path.getTarget().getX(), path.getTarget().getY(),
                            path.getTarget().getZ(), path.getNextNodeIndex());
        }
        return String.format(Locale.ROOT,
                " | 沙包 距棋子=%.2f 距目标=%.2f 血=%.1f/%.1f nav=%s 保护目标血=%d/%d",
                Math.sqrt(w.distanceToSqr(live.pawn)), Math.sqrt(distanceToSqr(w,
                        live.target.getBlockPos().getX() + 0.5D,
                        live.target.getBlockPos().getY() + 0.5D,
                        live.target.getBlockPos().getZ() + 0.5D)),
                living == null ? 0.0D : living.getHealth(), live.watchHp0,
                nav, live.target.getHealth(), live.target.getMaxHealth());
    }

    /** 每 tick 采样沙包与棋子的接触情况（整段的极值才有意义，见 {@link #watchVerdict}）。 */
    private static void sampleWatchContact(LiveObservation live) {
        Entity watch = live.watch;
        if (watch == null || watch.isRemoved() || live.pawn.isRemoved()) {
            return;
        }
        live.watchMinGap = Math.min(live.watchMinGap, horizontalGap(watch, live.pawn));
        if (watch.getBoundingBox().intersects(live.pawn.getBoundingBox())) {
            live.watchTouched = true;
        }
    }

    /**
     * 「这次实机观察到底验到了什么」的结论行。
     *
     * <p><b>为什么专门写它</b>：第一次拿实机存档跑 {@code livedemo} 时，沙包的路线被地形带偏
     * （棋子 z=-4，沙包一路走到 z=-1.5），<b>全程离棋子 1.5 格以上、一次都没碰上</b>，
     * 而报告里只有一行「→ 实机观察结束」，读起来像「一切正常」—— 实际是<b>什么都没验到</b>。
     * 更糟的是那一轮连「挡住=0」都长得像结论，而真正的原因是白名单默认是空的。
     * 所以这里把「碰没碰上」写成显式结论（同族教训：踩坑记录）。</p>
     */
    private static String watchVerdict(LiveObservation live) {
        if (live.watch == null) {
            return "";
        }
        double touchGap = (live.pawn.getBbWidth() + live.watch.getBbWidth()) * 0.5D;
        if (!live.watchTouched) {
            return String.format(Locale.ROOT,
                    "   ⚠ 这次观察**没验到阻挡/碰撞**：沙包与棋子中心最近只有 %.2f 格"
                            + "（要贴住需要 ≤ %.2f），它压根没碰到棋子 —— 多半是地形把它的路线带偏了。\n"
                            + "      要真的验到：用 -Dguardianprotocol.selftest.liveOffset=x,y,z "
                            + "把棋子挪到沙包实际走的那条线上，或换一块平坦场地。\n",
                    live.watchMinGap, touchGap);
        }
        boolean penetrated = live.watchMinGap < touchGap - 0.05D;
        return String.format(Locale.ROOT,
                "   %s 沙包顶上了棋子：与棋子中心最近 %.2f 格（贴住距离 %.2f）%s\n",
                penetrated ? "✘ 钻进去了（碰撞没生效，是 bug）：" : "✔ 顶住了、没钻进去：",
                live.watchMinGap, touchGap,
                penetrated ? "" : " —— 实心碰撞生效");
    }

    private static double distanceToSqr(Entity e, double x, double y, double z) {
        double dx = e.getX() - x;
        double dy = e.getY() - y;
        double dz = e.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 正在进行的实机观察（null = 没有）。只由服务端主线程读写。 */
    private static LiveObservation LIVE;

    /**
     * 一次实机观察的状态。
     *
     * <p>写成可变字段的类而不是 record：观察要跨 tick 递减计数。
     * 早先我把它写成 record + 一个静态计数，读起来像「两个地方各存一份剩余量」，
     * 一个 tick 之内就要在两处同步 —— 这种「同一状态两处存放」正是本项目
     * 反复踩的坑（同源/双份数据，见 踩坑记录），所以改成单一来源。</p>
     */
    private static final class LiveObservation {
        final ServerLevel level;
        final PixelUnit pawn;
        final com.guardianprotocol.block.ProtectTargetBlockEntity target;
        final List<Entity> cleanup;
        final int totalTicks;
        int remaining;
        /** 可选：被观察的「沙包」（livedemo 用），null = 没有。 */
        Entity watch;
        double watchHp0;
        int targetHp0;
        /** 沙包与棋子中心最近到过多少格（整段极值；判「这次观察到底有没有碰上棋子」）。 */
        double watchMinGap = Double.MAX_VALUE;
        /** 沙包与棋子的碰撞箱是否相交过（= 真的顶上了）。 */
        boolean watchTouched;

        LiveObservation(ServerLevel level, PixelUnit pawn,
                        com.guardianprotocol.block.ProtectTargetBlockEntity target,
                        List<Entity> cleanup, int totalTicks) {
            this.level = level;
            this.pawn = pawn;
            this.target = target;
            this.cleanup = cleanup;
            this.totalTicks = totalTicks;
            this.remaining = totalTicks;
        }
    }

    /** 服务端每 tick 调用（由 {@code ModEvents} 转发）：推进实机观察。 */
    public static void tickLiveObservation() {
        LiveObservation live = LIVE;
        if (live == null) {
            return;
        }
        int elapsed = live.totalTicks - live.remaining;
        // ★ 每 tick 采样接触情况（**不是**每 40 tick 记那行时才算）：判「这次观察有没有真的碰到棋子」
        //   靠的是整段的极值，而接触往往只发生在中间某几 tick（见 watchVerdict）。
        sampleWatchContact(live);
        // 每 40 tick 记一行；最后一行总是记
        boolean last = live.remaining <= 1;
        if (elapsed % 40 == 0 || last) {
            List<PathfinderMob> held = PawnCombatManager.debugBlocked(live.pawn);
            List<LivingEntity> picked = PawnCombatManager.debugLastTargets(live.pawn);
            String line = "--- live 观察 t=" + elapsed + '/' + live.totalTicks
                    // 注意：这两个数在直接调用 onServerTick 的合成场景里可能被**后续 tick 覆盖**，
                    // 所以它们只是参考；真实判据看 [判据]（selectTargets 自己写下的那一步）
                    + " 挡住=" + held.size()
                    + " 选中=" + picked.size()
                    + describe(picked)
                    + " 冷却=" + PawnCombatManager.debugCooldown(live.pawn)
                    + " 棋子血=" + r1(live.pawn.getHealth()) + '/' + r1(live.pawn.getMaxHealth())
                    + " 目标血=" + live.target.getHealth() + '/' + live.target.getMaxHealth()
                    + nearbyReport(live)
                    + watchReport(live)
                    + '\n';
            appendReport(line);
            GuardianProtocol.LOGGER.info("[selftest] {}", line.trim());
        }
        live.remaining--;
        if (live.remaining <= 0) {
            // ★ 先出「这次到底验到了什么」的结论，再清场（结论要用到沙包的碰撞箱）
            appendReport(watchVerdict(live));
            appendReport("   → 实机观察结束，清理生成物 " + live.cleanup.size() + " 个\n");
            discardAll(live.cleanup);
            LIVE = null;
            if (enabledByProperty()) {
                // 无头模式：观察结束就关服，不保存
                live.level.getServer().halt(false);
            }
        }
    }

    private static String describe(List<LivingEntity> picked) {
        if (picked.isEmpty()) {
            return " 选中=()";
        }
        StringBuilder sb = new StringBuilder(" 选中=");
        for (LivingEntity e : picked) {
            sb.append('[').append(name(e)).append(" 血").append(r1(e.getHealth())).append(']');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 生成 / 工具
    // ------------------------------------------------------------------

    /**
     * 找一根柱子上「能站人的那一格」——从 {@code fromY} 往下找第一个实心方块，站到它上面。
     *
     * <p><b>★ 为什么不用 {@code getHeight(MOTION_BLOCKING, x, z)}</b>：那是「从世界最高处
     * 往下找第一个挡住运动的方块」，在专用服务器上世界高度上限很高，
     * 而区块没完全加载/未生成的柱子上它会把 <b>建筑高度上限</b> 当成地面返回 ——
     * 实测踩过：自验把敌人生成在 y=319（世界顶端），报告里所有候选都「不在范围内」，
     * 看着像是索敌坏了，实际是**测试台自己把实体摆到了天上**。
     * 这里改成「从给定 Y 往下找实心方块」，只依赖目标列的方块状态，稳得多。</p>
     *
     * @param fromY 从哪里开始往下找（一般给世界出生点或指定场地的 Y）
     * @return 站在地面上的实体 Y 坐标（实心方块上方那一格）
     */
    private static int groundYAt(ServerLevel level, int x, int z, int fromY) {
        // 扫描窗口给得宽一点：超平坦（基岩地平线）的地面在 y≈-60，
        // 而玩家给的基准可能是 y=64，8 格窗口够不到。
        int top = Math.min(level.getMaxBuildHeight() - 2, Math.max(fromY, 64) + 8);
        int bottom = Math.max(level.getMinBuildHeight() + 1, Math.min(fromY, 64) - 96);
        for (int y = top; y > bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getBlockState(p).isAir()) {
                return y + 1;            // 实心方块上面那一格
            }
        }
        return fromY;
    }

    /**
     * 把棋子摆到「基准列附近、按方块状态找到的地面」上，返回摆好之后的棋子。
     *
     * <p>★ 关键：**返回后要用 {@code pawn.blockPosition()} 当唯一基准**去摆敌人与判断落点。
     * 早先我按「地面扫描算出来的 y」直接摆敌人，而棋子 spawn 之后会被碰撞/落地修正，
     * 两者一旦差一格，整套判定就全落空 —— 报告看起来像「索敌坏了」，
     * 实际是测试台自己把两边摆到了不同高度（实测踩过，见 踩坑记录）。</p>
     */
    private static PixelUnit spawnPawnOnGround(ServerLevel level, int x, int z, int fromY,
                                               UnitBranch branch) {
        int y = groundYAt(level, x, z, fromY);
        PixelUnit pawn = spawnPawn(level, new BlockPos(x, y, z), branch);
        // 以实际落点再钉一次：棋子有 noGravity，但 spawn 期间仍可能被修正 y
        return pawn;
    }

    private static PixelUnit spawnPawn(ServerLevel level, BlockPos at, UnitBranch branch) {
        return spawnPawn(level, at, new BuiltinBranch(branch));
    }

    /** 生成一个指定分支的棋子（分支可以是数据包分支）。 */
    private static PixelUnit spawnPawn(ServerLevel level, BlockPos at, BranchDef branch) {
        PixelUnit unit = ModEntities.PIXEL_UNIT.get().create(level);
        if (unit == null) {
            throw new IllegalStateException("无法创建棋子实体");
        }
        unit.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        unit.assign(branch);
        unit.setFacing(PieceFacing.EAST);
        level.addFreshEntity(unit);
        // addFreshEntity 会跑 finalizeSpawn，随后再 assign 一次确保分支没被随机掉
        unit.assign(branch);
        unit.setFacing(PieceFacing.EAST);
        return unit;
    }

    private static Mob spawn(ServerLevel level, EntityType<?> type, int x, int y, int z, String label) {
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) {
            throw new IllegalStateException(type + " 不是 Mob");
        }
        mob.moveTo(x + 0.5D, y, z + 0.5D, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        mob.setNoAi(true);          // 不让它自己乱跑，保证落点可控
        level.addFreshEntity(mob);
        mob.setCustomName(Component.literal(label).withStyle(ChatFormatting.GRAY));
        return mob;
    }

    /**
     * 同上，但**保留 AI**（不 setNoAi）。
     *
     * <p>阻挡场景专用：新的阻挡是「只下发寻路目标、让敌人自己走」，生物被 {@code setNoAi(true)}
     * 之后根本不会动 —— 第一版阻挡场景就踩了这个：所有「它自己走过去 / 被击退后走回来」
     * 的断言全都在**原地不动**的情况下假通过或假失败（见 踩坑记录）。</p>
     */
    private static Mob spawnWithAi(ServerLevel level, EntityType<?> type, int x, int y, int z, String label) {
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) {
            throw new IllegalStateException(type + " 不是 Mob");
        }
        mob.moveTo(x + 0.5D, y, z + 0.5D, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        mob.setCustomName(Component.literal(label).withStyle(ChatFormatting.GRAY));
        return mob;
    }

    /**
     * 把测试用生物调成「血厚到打不死」。
     *
     * <p>为什么必须：阻挡场景里棋子会打被挡住的目标，而僵尸只有 20 血、斗士一下就能打死 ——
     * 第一个被挡的死了之后，粘滞名额会顺延给第二个，于是「挡住的是最近那只」「放行者被交还原版」
     * 这些断言会莫名其妙地失败（第一版就是这样，看着像阻挡逻辑坏了）。</p>
     */
    private static void makeTanky(Mob mob, double health) {
        var attr = mob.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(health);
        }
        mob.setHealth((float) health);
    }

    /** 摆到「棋子格 + 朝东前方 forward 格、横向 lateral」的位置。 */
    private static void place(Mob mob, int bx, int by, int bz, double forward, double lateral) {
        mob.setPos(bx + 0.5D + forward, by, bz + 0.5D + lateral);
    }

    private static void setArmor(Mob mob, double armor) {
        var attr = mob.getAttribute(Attributes.ARMOR);
        if (attr != null) {
            attr.setBaseValue(armor);
        }
    }

    private static double armor(LivingEntity e) {
        var attr = e.getAttribute(Attributes.ARMOR);
        return attr == null ? -1.0D : attr.getValue();
    }

    private static String cells(PixelUnit unit) {
        StringBuilder sb = new StringBuilder();
        for (var c : unit.worldCells()) {
            sb.append('(').append(c.x()).append(',').append(c.z()).append(") ");
        }
        return sb.toString().trim();
    }

    private static String name(Entity e) {
        return e.getCustomName() != null ? e.getCustomName().getString() : e.getType().toString();
    }

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static String shortVec(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static String r1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String r2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void discardAll(List<Entity> entities) {
        for (Entity e : entities) {
            if (e != null && !e.isRemoved()) {
                e.discard();
            }
        }
    }

    /** 报告路径：服务端运行目录（{@code 自验运行目录}/ 等）下的固定文件名。 */
    public static Path reportPath() {
        return Paths.get(System.getProperty("user.dir", "."), REPORT_FILE);
    }

    /** 追加写报告（多跑几次不会互相覆盖，每一段自带 scenario 头）。 */
    private static void appendReport(String text) {
        Path p = reportPath();
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            GuardianProtocol.LOGGER.error("[{}] 自验报告写入失败：{}",
                    GuardianProtocol.MODID, p.toAbsolutePath(), ex);
        }
    }
}
