package com.guardianprotocol.combat;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 棋子的**归属队伍**：谁摆的棋子，就属于谁的队（设计口径）。
 *
 * <h3>设计原话</h3>
 * <blockquote>
 * 「将玩家摆放的棋子统一挂到玩家所属下视为同一队，联机时不同玩家放的棋子在不同玩家所属下，
 * 但不为同一队只算同一阵营，如玩家阵营，玩家A放置了X、Y、Z……，那么玩家A放置的棋子就属于A队，
 * 同理若是B也放了X、Y、Z……，那么这些就属于B队。以此类推」
 * </blockquote>
 * 也就是说这里有**两层**概念，别混：
 * <ul>
 *     <li><b>队伍（team）</b>＝ 一个玩家一支队（A 队 / B 队）—— <b>治疗只对同队生效</b>；</li>
 *     <li><b>阵营（side）</b>＝ 所有玩家棋子同属「玩家阵营」—— <b>互相不攻击</b>。</li>
 * </ul>
 *
 * <h3>怎么落地（用原版记分板队伍）</h3>
 * <ol>
 *     <li>每个玩家一支队，队名 {@code gp_p<玩家 UUID 前 8 位>}（原版队名上限 16 字符，12 位够用）；
 *         队里装的是棋子的 {@code UUID} 字符串（原版队伍的成员就是「记分板名字」，
 *         非玩家实体用它的 UUID 当名字，{@link Entity#getTeam()} 也是按这个名字查的）；</li>
 *     <li><b>阵营靠队伍颜色</b>：原版 {@code Team#isAlliedTo} 的判据是「颜色相同」，
 *         所以所有玩家队都用同一个颜色 ⇒ 它们在原版眼里互为同盟（既不互相伤害、
 *         也不会被原版的仇恨逻辑当成敌人）。<b>这就是「同一阵营、不同队」的实现</b>，
 *         不需要自己再维护一张同盟表；</li>
 *     <li>队名里带玩家 UUID 前 8 位 ⇒ **同一玩家重连、重摆都是同一支队**，
 *         不会因为重开一次就变成两支。</li>
 * </ol>
 *
 * <h3>生命周期</h3>
 * <p>队伍会随 {@code level.dat} 存档（和 {@link BlockTeams} 那两支一样），而且**必须**跟着存档走 ——
 * 棋子是持久实体，它属于哪支队得跨存档还原。所以这里<b>不</b>在停服时删队；
 * 只在开局时把**空队伍**（没有成员的 {@code gp_p*}）清掉，避免反复开档积出一堆空队
 * （见 {@link #cleanupEmptyTeams}）。</p>
 *
 * <p>所有方法对 null / 拿不到记分板一律安全返回，最多打日志，**不抛异常**
 * （它们跑在生成与加载路径上，抛出去会牵连整个 tick）。</p>
 */
public final class PawnTeams {

    /** 玩家棋子队伍的前缀（后面接玩家 UUID 前 8 位）。 */
    public static final String OWNER_TEAM_PREFIX = "gp_p";

    /**
     * 「玩家阵营」的统一队色。
     *
     * <p>★ 它的作用不是好看：原版 {@code PlayerTeam#isAlliedTo} 的判据之一就是
     * 「两个队颜色相同」，所以**同一个颜色 = 同一阵营**。换成别的颜色也是同一阵营，
     * 但**不能**用 {@code RESET}（原版把 RESET 视为「没有颜色」，那种情况不结盟）。</p>
     */
    public static final ChatFormatting SIDE_COLOR = ChatFormatting.AQUA;

    /**
     * 玩家队内部成员的碰撞规则：同队之间不互相推挤。
     *
     * <p>与 {@link BlockTeams#BLOCKED_TEAM} 同一套理由（那一支也用的是这个值，见它的注释）：
     * 棋子站在一格上时常被自己人挤开，而它们本来就该原地不动。</p>
     */
    private static final Team.CollisionRule PAWN_RULE = Team.CollisionRule.PUSH_OWN_TEAM;

    private PawnTeams() {
    }

    /** 归属为这个 UUID 的棋子，应该挂在哪支队里；{@code null} = 没有归属（无队）。 */
    @Nullable
    public static String teamNameFor(@Nullable UUID owner) {
        if (owner == null) {
            return null;
        }
        String id = owner.toString().replace("-", "").toLowerCase(Locale.ROOT);
        return OWNER_TEAM_PREFIX + id.substring(0, Math.min(8, id.length()));
    }

    /** 这个实体当前所在的队名（没队 = null）。 */
    @Nullable
    public static String teamNameOf(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        Team team = entity.getTeam();
        return team == null ? null : team.getName();
    }

    /**
     * 两个实体是不是**同一阵营**（阵营 = 队伍上面那一层，见类注释）。
     *
     * <p>★ 为什么不直接用原版的 {@code Team#isAlliedTo}：那条规则的实现细节（颜色相同时算不算同盟）
     * 我没能在源码里逐行确认，而**阵营是玩法判据**，不该建在一条我没验证过的原版规则上。
     * 所以这里显式定义：<b>队伍名带 {@link #OWNER_TEAM_PREFIX} 前缀的，全属「玩家阵营」</b>。
     * 队伍颜色仍然统一设成 {@link #SIDE_COLOR}（让原版自己也把玩家棋子当同盟看），
     * 但那只是**顺手**，判定不依赖它。</p>
     */
    public static boolean isSameSide(@Nullable Entity a, @Nullable Entity b) {
        Side sa = sideOf(a);
        Side sb = sideOf(b);
        return sa != Side.NONE && sa == sb;
    }

    /** 阵营：玩家棋子一种，被挡住的怪一种，其余（没队伍的实体）算 NONE。 */
    public enum Side {
        /** 玩家阵营：所有玩家摆的棋子（不分队）。 */
        PLAYER,
        /** 敌方阵营：{@link BlockTeams#BLOCKED_TEAM} 里的怪。 */
        ENEMY,
        /** 既不是棋子也没挂敌队（中性）。 */
        NONE
    }

    /** 这个实体属于哪一阵营。 */
    public static Side sideOf(@Nullable Entity entity) {
        if (entity instanceof PixelUnit) {
            // 棋子：不论有没有主、属于哪支队，都是玩家阵营（设计口径：只算同一阵营）
            return Side.PLAYER;
        }
        String team = teamNameOf(entity);
        if (team != null && BlockTeams.BLOCKED_TEAM.equals(team)) {
            return Side.ENEMY;
        }
        return Side.NONE;
    }

    /**
     * 两个实体是不是**同一支队**（治疗用的判据）。
     *
     * <p>「都没有队」也算同队（都属于「无主」这一队）—— 命令/数据包生成的棋子没有归属，
     * 它们之间仍然应该能互相治疗；而有主的棋子不会去治无主的（反之亦然）。</p>
     */
    public static boolean isSameTeam(@Nullable Entity a, @Nullable Entity b) {
        if (a == null || b == null) {
            return false;
        }
        String ta = teamNameOf(a);
        String tb = teamNameOf(b);
        return ta == null ? tb == null : ta.equals(tb);
    }

    /**
     * 把棋子挂进它归属的那支队（并记下归属）。
     *
     * @param ownerUuid 摆放者 UUID 字符串（空/null = 无主）
     */
    public static void assign(ServerLevel level, PixelUnit pawn, @Nullable String ownerUuid,
                              @Nullable String ownerName) {
        pawn.assignOwner(ownerUuid, ownerName);
        String team = teamNameFor(parse(ownerUuid));
        if (team == null) {
            return;     // 无主棋子不进队：它的「队」就是「无队」这一档
        }
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam playerTeam = ensureTeam(scoreboard, team);
        if (playerTeam == null) {
            return;
        }
        if (scoreboard.getPlayersTeam(pawn.getStringUUID()) != playerTeam) {
            // 原版要求先把成员从旧队摘掉，否则同一名字会在两支队伍里（判据只看第一条命中）
            scoreboard.removePlayerFromTeam(pawn.getStringUUID());
            if (!scoreboard.addPlayerToTeam(pawn.getStringUUID(), playerTeam)) {
                GuardianProtocol.LOGGER.warn("[{}] 棋子 {} 未能加入队伍 {}",
                        GuardianProtocol.MODID, pawn.getStringUUID(), team);
            }
        }
    }

    /** 棋子从存档还原后重新入队（队伍成员表在 {@code level.dat} 里，正常情况已经在队里）。 */
    public static void rejoin(ServerLevel level, PixelUnit pawn) {
        assign(level, pawn, pawn.ownerId(), pawn.ownerName());
    }

    /**
     * 开局清理**空队伍**：{@code gp_p*} 里一个成员都没有的，删掉。
     *
     * <p>为什么要清：队名是「玩家 UUID 前 8 位」，不同玩家会积出不同的队；反复开档/删档
     * 之后存档里会留一堆没有任何成员的队伍（原版队伍是写进 {@code level.dat} 的）。
     * 成员还在的队<b>不能删</b> —— 棋子是持久实体，它的归属跟着存档走。</p>
     *
     * @return 删掉的空队数
     */
    public static int cleanupEmptyTeams(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Scoreboard scoreboard = level.getScoreboard();
            List<String> doomed = new ArrayList<>();
            for (PlayerTeam team : scoreboard.getPlayerTeams()) {
                if (team.getName().startsWith(OWNER_TEAM_PREFIX)
                        && team.getPlayers().isEmpty()) {
                    doomed.add(team.getName());
                }
            }
            for (String name : doomed) {
                scoreboard.removePlayerTeam(scoreboard.getPlayerTeam(name));
                removed++;
            }
        }
        if (removed > 0) {
            GuardianProtocol.LOGGER.info("[{}] 清理空棋子队伍 {} 支", GuardianProtocol.MODID, removed);
        }
        return removed;
    }

    /** 报告用：现在有几支棋子队、各队几人。 */
    public static String describe(ServerLevel level) {
        Scoreboard scoreboard = level.getScoreboard();
        StringBuilder sb = new StringBuilder();
        int teams = 0;
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            if (!team.getName().startsWith(OWNER_TEAM_PREFIX)) {
                continue;
            }
            teams++;
            if (sb.length() > 0) {
                sb.append('；');
            }
            sb.append(team.getName()).append(' ').append(team.getPlayers().size()).append(" 个棋子");
        }
        return teams == 0 ? "（还没有任何玩家棋子队伍）" : sb.toString();
    }

    @Nullable
    private static PlayerTeam ensureTeam(Scoreboard scoreboard, String name) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team != null) {
            return team;
        }
        team = scoreboard.addPlayerTeam(name);
        if (team == null) {
            return null;
        }
        // 阵营 = 统一颜色（原版 isAlliedTo 按颜色判定同盟，见 SIDE_COLOR 的注释）
        team.setColor(SIDE_COLOR);
        // 同队不打自己人（原版规则；跨队靠颜色同盟，同样打不到）
        team.setAllowFriendlyFire(false);
        team.setSeeFriendlyInvisibles(false);
        team.setCollisionRule(PAWN_RULE);
        return team;
    }

    @Nullable
    private static UUID parse(@Nullable String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException ex) {
            return null;        // 存档/物品 NBT 是外部输入：写坏了只能降级，不能抛
        }
    }
}
