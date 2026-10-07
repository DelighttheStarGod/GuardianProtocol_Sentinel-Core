package com.guardianprotocol.combat;

import com.guardianprotocol.GuardianProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「被挡住的敌人」与「所有玩家」两支<b>原版记分板队伍</b>的封装。
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>阻挡除了「服务端按住位置」之外，还想解决一件事：一群敌人被按在同一个锚点附近时
 * 会互相推挤（原版 {@code LivingEntity#pushEntities} 每 tick 把重叠的两个实体互相弹开），
 * 挤成一团时看着像「东倒西歪」。原版正好有一件现成的工具 —— 记分板队伍的
 * {@code collisionRule}（游戏里就是 {@code /team modify <队伍> collisionRule ...}）：
 * 它不需要我们写任何 AI，也不碰生物自己的 goal（见下）。</p>
 *
 * <p>但这件工具的用法里有三个坑，全部集中在<b>这一个类</b>里处理，别处不许再直接碰
 * {@link Scoreboard}：</p>
 * <ol>
 *     <li><b>成员名怎么写</b>（{@link #memberName}）：生物挂 UUID、玩家挂<b>玩家名</b>。
 *         写错了不报错，只是队伍规则<b>静默失效</b>（规则是按 {@code Entity#getScoreboardName()}
 *         查的，玩家的那个返回的是玩家名而不是 UUID）；</li>
 *     <li><b>两参的 {@code removePlayerFromTeam(String, PlayerTeam)} 会抛异常</b> ——
 *         原版在这个人不在该队时直接 {@code throw new IllegalStateException(...)}。
 *         所以摘人一律走 {@link #removeFrom} 那种「先比对、再摘」的写法，
 *         只调单参版（它只返回 boolean，且内部照样会同步给客户端）；</li>
 *     <li><b>幂等</b>：{@code addPlayerTeam} 对已存在的队会先打一条<b>原版 WARN</b>
 *         （"Requested creation of existing team"）再把老队还给你，所以建队前必须先查；
 *         已存在的队还要把碰撞规则<b>纠正</b>成我们的口径（存档里可能是别的值）。</li>
 * </ol>
 *
 * <h3>★ 不碰 goalSelector（踩坑记录的教训）</h3>
 * <p>本类只登记队伍，<b>不往生物里塞任何 goal、不碰寻路</b>。踩坑记录的结论是：
 * 往 {@code goalSelector} 里塞 goal 会把史莱姆 / 岩浆怪 / 幻翼自身的寻路与攻击一起搞坏
 * （它们重写了 {@code MoveControl#tick()} 且不调 super），那条路已经整条回退。
 * 队伍规则是原版本来就支持的入口，改动面只有「推挤」这一件事。</p>
 *
 * <h3>★ 两条碰撞规则在 1.20.1 的<b>实际</b>行为（名字与行为是反的）</h3>
 * <p>判据在 {@code EntitySelector#pushableBy}：一对实体要不要互相弹开，由<b>双方</b>队伍的
 * 规则共同决定。1.20.1 的实现（逐行读过反编译源码）等价于：</p>
 * <ul>
 *     <li>{@link Team.CollisionRule#NEVER}（{@code gp_players} 用的）：
 *         <b>完全不参与推挤</b> —— 既推不动别人，也不被别人推（判据直接返回 false，
 *         连被挡住的敌人也不会被玩家顶走）。玩家因此不受这套系统影响；</li>
 *     <li>{@link Team.CollisionRule#PUSH_OWN_TEAM}（{@code gp_blocked} 用的）：
 *         <b>同队成员之间不互相推挤</b>；对<b>队外</b>的实体照常互相推挤。</li>
 * </ul>
 * <p>⚠️ 枚举名读起来像「只推同队」，行为恰好相反（同队反而<b>不</b>推）—— 这是原版长期
 * 未修的问题：MC-87984《teams option &lt;team&gt; collisionRule values are not working
 * correctly》，1.20.1 仍在复现，复现步骤就是「队伍 A 设成 pushOwnTeam，队外的实体照样被推」。
 * 所以注释与报告都不要按名字推断行为，按上面两条写。</p>
 *
 * <h3>生命周期（不在存档里留东西）</h3>
 * <p>{@link #apply} 建队 + 把在线玩家挂进玩家队；{@link #shutdown} <b>先摘人再删队</b>。
 * 队伍会随 {@code ScoreboardSaveData} 写进 {@code level.dat}，所以停服必须删干净，
 * 否则玩家的存档里会长出两支本 mod 的队伍。{@link #apply} 幂等；且 {@link #tag} /
 * {@link #tagPlayer} 也会按需补建队伍（漏调 {@code apply} 只是少了一次「全体玩家入队」，
 * 不会炸）。</p>
 *
 * <p>所有方法对 null / 拿不到记分板的情况一律安全返回，最多打日志，<b>不抛异常</b>
 * （它们跑在服务端 tick 与登录/停服事件里，抛出去会牵连整个 tick）。</p>
 */
public final class BlockTeams {

    /** 被挡住的敌人所在的队伍。 */
    public static final String BLOCKED_TEAM = "gp_blocked";

    /** 所有玩家所在的队伍。 */
    public static final String PLAYERS_TEAM = "gp_players";

    /**
     * {@code gp_blocked} 的碰撞规则。
     *
     * <p>选它的<b>唯一理由</b>：原版判据对「同队 + 有一方是 PUSH_OWN_TEAM」直接判 false，
     * 于是被按在同一处的这一群敌人不再互相弹开（挤在一起时受控、不会把彼此推得东倒西歪）。
     * 它与枚举名反直觉的地方见类注释（MC-87984）。</p>
     *
     * <p><b>它管不住队外的推挤</b>：队外实体（没有队伍 = {@code ALWAYS}）照样能把被挡住的
     * 敌人顶开。若试玩后发现「漏过去的怪把挡住的怪顶走」不可接受，
     * <b>把这一个常量换成 {@link Team.CollisionRule#NEVER} 即可</b> ——
     * 判定逻辑一行都不用动（把规则提成常量就是为了这个）。</p>
     */
    private static final Team.CollisionRule BLOCKED_RULE = Team.CollisionRule.PUSH_OWN_TEAM;

    /**
     * {@code gp_players} 的碰撞规则：不与任何实体互相推挤（{@code NEVER} 是双向的，见类注释）。
     *
     * <p>玩家本来就该按自己的操作移动，被一群怪顶着走是另一回事；这条规则让「阻挡」
     * 这套系统完全不牵扯玩家。</p>
     */
    private static final Team.CollisionRule PLAYERS_RULE = Team.CollisionRule.NEVER;

    /** {@link #FOREIGN_WARNED} 的上限：满了就整体清空（见该字段的注释）。 */
    private static final int FOREIGN_WARN_LIMIT = 256;

    /**
     * 「这个实体已经在别的队伍里」这条 WARN 的<b>去重集合</b>。
     *
     * <p>为什么需要它：{@link #tag} 是<b>每 tick</b> 调的（阻挡逻辑逐 tick 驱动敌人），
     * 如果玩家的存档里正好有一只被别的系统挂了队伍的怪，这条警告会以 20 条/秒刷屏 ——
     * 而假报警会训练人忽略日志（本项目对这条有专门记录）。每个成员名只报一次；
     * 集合超过 {@link #FOREIGN_WARN_LIMIT} 时整体清空，重新允许报警。</p>
     *
     * <p>它<b>只是日志节流</b>，不参与任何判定：去重与否，返回值都是 false（不抢别人的队伍）。</p>
     */
    private static final Set<String> FOREIGN_WARNED = ConcurrentHashMap.newKeySet();

    private BlockTeams() {
    }

    /**
     * 服务端启动时调用：缺则建两队，并把在线玩家加入玩家队。
     *
     * <p><b>幂等</b>：重复调用不会重复建队（建队前先查 {@code getPlayerTeam}，
     * 免得触发原版那条 "existing team" WARN），也不会把已经在队里的玩家再挂一遍
     * （{@link #joinTeam} 先查再挂，已经在本队直接算成功）。</p>
     *
     * <p>队伍已存在时会把碰撞规则<b>纠正</b>成 {@link #BLOCKED_RULE} / {@link #PLAYERS_RULE} ——
     * 存档里可能是旧规则（上一版的口径、或者玩家自己用 {@code /team modify} 改过），
     * 不纠正的话「挤在一起不互相推」这条会静默失效。</p>
     */
    public static void apply(MinecraftServer server) {
        if (server == null) {
            GuardianProtocol.LOGGER.warn("[{}] BlockTeams.apply 收到 null 服务端，跳过建队。",
                    GuardianProtocol.MODID);
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        if (scoreboard == null) {
            // MinecraftServer 的 scoreboard 是 final 字段、构造时就建好了，正常不会走到这里。
            GuardianProtocol.LOGGER.warn("[{}] 拿不到记分板，跳过建队。", GuardianProtocol.MODID);
            return;
        }
        ensureTeam(scoreboard, BLOCKED_TEAM, BLOCKED_RULE);
        PlayerTeam players = ensureTeam(scoreboard, PLAYERS_TEAM, PLAYERS_RULE);

        int joined = 0;
        PlayerList playerList = server.getPlayerList();
        if (playerList != null && players != null) {
            for (ServerPlayer player : playerList.getPlayers()) {
                if (joinTeam(scoreboard, players, memberName(player))) {
                    joined++;
                }
            }
        }
        GuardianProtocol.LOGGER.info("[{}] 阻挡队伍已就绪：{}（被挡住）/ {}（玩家），本次确认 {} 名在线玩家在玩家队里。",
                GuardianProtocol.MODID, BLOCKED_TEAM, PLAYERS_TEAM, joined);
    }

    /**
     * 服务端停止时调用：把成员移出并删除两队（不在存档里留东西）。
     *
     * <h3>顺序：先摘人，再删队</h3>
     * <p>{@code Scoreboard#removePlayerTeam} 本身就会把这些成员从 {@code teamsByPlayer}
     * 里清掉，但<b>玩家必须先单独摘</b>：一是要按设计口径报出「处理了几个玩家」，
     * 二是原版删队只清「队 → 人」的索引，先逐个摘能让每一步都可核对。
     * 两步做完，队伍与成员归属都不再被 {@code ScoreboardSaveData#saveTeams} 写进存档。</p>
     *
     * <p>幂等：队伍不存在时计数为 0，不报错（重复停服、或者从没建过队都能安全走过）。</p>
     */
    public static void shutdown(MinecraftServer server) {
        if (server == null) {
            GuardianProtocol.LOGGER.warn("[{}] BlockTeams.shutdown 收到 null 服务端，跳过清理。",
                    GuardianProtocol.MODID);
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        if (scoreboard == null) {
            GuardianProtocol.LOGGER.warn("[{}] 拿不到记分板，跳过队伍清理。", GuardianProtocol.MODID);
            return;
        }
        int players = clearTeam(scoreboard, PLAYERS_TEAM);
        int blocked = clearTeam(scoreboard, BLOCKED_TEAM);
        GuardianProtocol.LOGGER.info("[{}] 阻挡队伍已清理：{} 移出 {} 名玩家、{} 移出 {} 个被挡实体，两队已删除（不写进存档）。",
                GuardianProtocol.MODID, PLAYERS_TEAM, players, BLOCKED_TEAM, blocked);
    }

    /**
     * 把实体放进「被挡住」队。
     *
     * @return {@code true} = 现在它在 {@code gp_blocked} 里（包括<b>本来就在</b>，幂等）；
     *         {@code false} = 没能入队（entity/level 为 null、拿不到记分板、
     *         或者它<b>已经在别的队伍里</b> —— 那种情况不抢，见下）
     *
     * <p><b>已经在别的队伍里怎么办</b>：不动它，打一条 WARN 后返回 false。玩家的存档里
     * 可能有自己的队伍（别的 mod / 数据包 / 玩家手敲的命令），把成员从别人的队里抢过来会
     * 破坏别人的功能，而这件事在日志里必须留痕，否则「为什么这只怪没被挡住」查不出来。</p>
     *
     * <p>队伍不存在时会按 {@link #BLOCKED_RULE} 补建（{@link #apply} 漏调也能用）。</p>
     */
    public static boolean tag(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return false;
        }
        Scoreboard scoreboard = level.getScoreboard();
        if (scoreboard == null) {
            return false;
        }
        PlayerTeam team = ensureTeam(scoreboard, BLOCKED_TEAM, BLOCKED_RULE);
        if (team == null) {
            return false;
        }
        return joinTeam(scoreboard, team, memberName(entity));
    }

    /**
     * 从「被挡住」队移出。
     *
     * @return {@code true} = 确实从 {@code gp_blocked} 里摘掉了；
     *         {@code false} = 它本来就不在这个队里（含 null 参数、拿不到记分板、
     *         以及<b>它属于别的队伍</b> —— 别人的队伍我们不碰）
     *
     * <p>这里<b>不</b>打 WARN：{@code release(...)} 会对「本来就没被挡住」的敌人也调用一次，
     * 每次都报警等于刷屏。返回值已经足够调用方判断。</p>
     */
    public static boolean untag(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return false;
        }
        Scoreboard scoreboard = level.getScoreboard();
        if (scoreboard == null) {
            return false;
        }
        PlayerTeam team = scoreboard.getPlayerTeam(BLOCKED_TEAM);
        if (team == null) {
            return false;
        }
        return removeFrom(scoreboard, team, memberName(entity));
    }

    /**
     * 玩家加入玩家队（玩家登录时用）。
     *
     * @return {@code true} = 现在它在 {@code gp_players} 里（含本来就在）；
     *         {@code false} = 没能入队（null 参数 / 拿不到记分板 / 已经在别的队伍里）
     *
     * <p>玩家退出时<b>不</b>摘：摘了只会多一条「离线再上线」的路径，而停服时
     * {@link #shutdown} 会统一清干净，失效窗口极小。所以这里也不需要在退出事件里设钩子。</p>
     */
    public static boolean tagPlayer(ServerLevel level, Player player) {
        if (level == null || player == null) {
            return false;
        }
        Scoreboard scoreboard = level.getScoreboard();
        if (scoreboard == null) {
            return false;
        }
        PlayerTeam team = ensureTeam(scoreboard, PLAYERS_TEAM, PLAYERS_RULE);
        if (team == null) {
            return false;
        }
        return joinTeam(scoreboard, team, memberName(player));
    }

    /**
     * 队伍状态一行描述（排查 / 自验用）：队名、成员数、碰撞规则。
     *
     * <p>返回形如
     * {@code 队伍 gp_blocked = 3 名成员, collisionRule=pushOwnTeam ｜ 队伍 gp_players = 1 名成员, collisionRule=never}。
     * 打印的是 {@code CollisionRule.name}（存档里写的就是这个字符串）而不是它的显示名，
     * 这样日志与 {@code level.dat} 可以直接对照。</p>
     */
    public static String describe(ServerLevel level) {
        if (level == null) {
            return "记分板不可用：level 为 null";
        }
        Scoreboard scoreboard = level.getScoreboard();
        if (scoreboard == null) {
            return "记分板不可用：level.getScoreboard() 为 null";
        }
        return describeTeam(scoreboard, BLOCKED_TEAM) + " ｜ " + describeTeam(scoreboard, PLAYERS_TEAM);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 确保队伍存在并把碰撞规则纠正成给定口径，返回这支队伍。
     *
     * <p>三个要点：</p>
     * <ol>
     *     <li><b>先查再建</b>：直接 {@code addPlayerTeam} 对已存在的队会打一条原版 WARN
     *         （"Requested creation of existing team"），日志会脏；</li>
     *     <li><b>规则每次都校正</b>，但只在<b>值不同</b>时才写 ——
     *         {@code setCollisionRule} 会触发原版的「队伍已变更」广播包，
     *         每 tick 白写一遍等于每 tick 发一次包（{@code TeamCommand} 也是这么比的）；</li>
     *     <li><b>颜色一律留 RESET</b>：队伍颜色会经 {@code PlayerTeam#formatNameForTeam}
     *         改变实体的<b>显示名</b>（聊天、死亡消息、Jade 之类的信息 mod 都读它），
     *         而成员里混着怪物 —— 给它们染色是设计未要求的副作用。所以只设一个
     *         便于排查的显示名，颜色不动。</li>
     * </ol>
     */
    private static PlayerTeam ensureTeam(Scoreboard scoreboard, String name, Team.CollisionRule rule) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            team = scoreboard.addPlayerTeam(name);
            team.setDisplayName(Component.literal(name));
        }
        if (team.getCollisionRule() != rule) {
            team.setCollisionRule(rule);
        }
        return team;
    }

    /**
     * 把成员挂进指定队伍，但<b>不抢别人的队</b>。
     *
     * @return {@code true} = 成功后它在 {@code team} 里（已经在本队也算成功，幂等）；
     *         {@code false} = 它在别的队伍里（打一条 WARN，节流见 {@link #FOREIGN_WARNED}）
     *
     * <p>原版的 {@code addPlayerToTeam} 会先把这个人从原队里摘掉再挂 —— 那个「先摘」正是
     * 我们要避免的（等于把别人队伍里的成员偷走）。所以这里自己先查。</p>
     */
    private static boolean joinTeam(Scoreboard scoreboard, PlayerTeam team, String member) {
        PlayerTeam existing = scoreboard.getPlayersTeam(member);
        if (existing != null) {
            if (existing.getName().equals(team.getName())) {
                return true;
            }
            warnForeignTeam(member, existing.getName(), team.getName());
            return false;
        }
        return scoreboard.addPlayerToTeam(member, team);
    }

    /**
     * 从指定队伍摘掉成员。
     *
     * <p><b>为什么不直接用两参的 {@code removePlayerFromTeam(String, PlayerTeam)}</b>：
     * 原版在这个人不在该队时<b>抛 {@code IllegalStateException}</b>（"Player is either on
     * another team or not on any team"），而我们的调用点（{@code release()}）无法保证状态。
     * 这里先比对再调单参版 —— 单参版只返回 boolean，并且内部转发到两参版时会走
     * {@code ServerScoreboard} 的重写，照样把「退队」包广播给客户端。</p>
     */
    private static boolean removeFrom(Scoreboard scoreboard, PlayerTeam team, String member) {
        if (scoreboard.getPlayersTeam(member) != team) {
            return false;
        }
        return scoreboard.removePlayerFromTeam(member);
    }

    /**
     * 清空一支队伍并删除它，返回移出的成员数。
     *
     * <p>★ 必须先<b>拷贝</b>成员集合再遍历：{@code removePlayerFromTeam} 会直接改
     * {@code PlayerTeam.players} 这个 Set，边遍历边删会抛 {@code ConcurrentModificationException}。</p>
     */
    private static int clearTeam(Scoreboard scoreboard, String name) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            return 0;
        }
        int removed = 0;
        for (String member : new ArrayList<>(team.getPlayers())) {
            if (removeFrom(scoreboard, team, member)) {
                removed++;
            }
        }
        // 队伍本身也从 teamsByName 移走 ⇒ ScoreboardSaveData 不会再把它写进存档。
        scoreboard.removePlayerTeam(team);
        return removed;
    }

    private static String describeTeam(Scoreboard scoreboard, String name) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            return "队伍 " + name + " = 不存在";
        }
        return "队伍 " + name + " = " + team.getPlayers().size() + " 名成员, collisionRule="
                + team.getCollisionRule().name;
    }

    /**
     * 这个实体挂在记分板上的<b>成员名</b>。
     *
     * <h3>★ 玩家与生物不是一套写法（写错就静默失效）</h3>
     * <p>规则是按 {@code Entity#getScoreboardName()} 查的：</p>
     * <ul>
     *     <li>{@code Entity} 的实现返回 {@code stringUUID}，所以生物（非玩家）用
     *         {@link Entity#getStringUUID()} 挂 —— 这也正是原版记分板挂生物的方式；</li>
     *     <li>{@code Player} 把它<b>重写</b>成游戏档案名（{@code getGameProfile().getName()}），
     *         <b>不是</b> UUID。所以玩家必须用玩家名挂，否则
     *         {@code player.getTeam()} 查不到自己，{@code NEVER} 这条规则看起来配好了却完全不生效，
     *         而且不报任何错。</li>
     * </ul>
     */
    private static String memberName(Entity entity) {
        return entity instanceof Player player ? player.getScoreboardName() : entity.getStringUUID();
    }

    /**
     * 「它已经在别的队伍里」的 WARN（同一个成员名只报一次，见 {@link #FOREIGN_WARNED}）。
     *
     * <p>报出来是为了让「为什么这个敌人没被挡住」有迹可循 —— 返回值 false 本身在日志里
     * 什么都不留，事后无从查起。</p>
     */
    private static void warnForeignTeam(String member, String foreignTeam, String wantedTeam) {
        if (FOREIGN_WARNED.size() > FOREIGN_WARN_LIMIT) {
            FOREIGN_WARNED.clear();
        }
        if (FOREIGN_WARNED.add(member)) {
            GuardianProtocol.LOGGER.warn("[{}] {} 已经属于队伍 {}，本 mod 不抢（它本该进 {}）；"
                            + "这条只报一次。",
                    GuardianProtocol.MODID, member, foreignTeam, wantedTeam);
        }
    }
}
