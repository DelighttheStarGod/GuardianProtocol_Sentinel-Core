package com.guardianprotocol.spawn.invasion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一块「生成式入侵」方块的<b>全部配置</b>（设计确定：方块级配置，存方块实体 NBT）。
 *
 * <p>与 SpawnCursion 的关系见 设计文档第三节：**模型对齐、配置来源相反** ——
 * 它是数据包 JSON + 几个方块搬定义，我们是**游戏内界面直接编辑**，落在方块实体里。</p>
 *
 * <h3>装了什么</h3>
 * <ul>
 *     <li>{@link #rows()}：生物行（每种怪自己的策略与参数）；</li>
 *     <li>{@link #bosses()} + {@link #finalBossIndex()} + {@link #hiddenBossIndex()} + {@link #hiddenBoost()}：
 *         BOSS 组与「谁当最终 BOSS / 谁当隐藏挑战的增强版」；</li>
 *     <li>{@link #rewards()}：奖励选取阶段的 N 选 1 候选（就是物品本身，界面里放进去）；</li>
 *     <li>{@link #prepTicks()} 等六个计时字段：相位时长（{@link InvasionPhase}）；</li>
 *     <li>{@link #displayName()} / {@link #rarity()} / 四个颜色：显示与物品形态；</li>
 *     <li>{@link #targetPos()}：本块属于哪条支路（**一条支路 = 一个保护目标**，设计口径）。</li>
 * </ul>
 *
 * <h3>★ 校验（{@link #validate()}）里有两条硬规则</h3>
 * <ol>
 *     <li><b>隐藏挑战限时 ≤ 最终 BOSS 战时间 / 3</b>（设计口径 原话：「隐藏挑战时间必须要
 *         小于等于最终boss战时间的1/3」）。越界一律<b>夹取到 1/3</b> 并给出原因字符串 ——
 *         不抛异常：配置来自界面，写错只该被拉回边界。</li>
 *     <li><b>没有 BOSS 战相位（{@code bossTicks == 0}）时，隐藏挑战必须也是 0</b>：
 *         隐藏挑战是「在 BOSS 战内并行」的（见 {@link InvasionPhase#HIDDEN}），
 *         没有 BOSS 战就没有它可以依附的窗口。否则它会静默地永远不触发，
 *         而那看起来像「隐藏挑战坏了」。</li>
 * </ol>
 *
 * <p>{@link #validate()} 返回<b>原因清单</b>（空 = 一切正常），交给界面/指令/自验打印，
 * 与 {@code BlockCandidates.rejectReason} 是同一套「别让人靠猜」的做法。</p>
 */
public final class InvasionConfig {

    /** 生物行上限（界面按这个上限画列表；要比 SpawnCursion 的任意多收敛，好画也好读）。 */
    public static final int MAX_ROWS = 8;

    /** BOSS 组上限（要两只：最终 BOSS + 隐藏挑战的增强版，给到 4 只够挑）。 */
    public static final int MAX_BOSSES = 4;

    /** 奖励候选上限（N 选 1 的 N）。 */
    public static final int MAX_REWARDS = 6;

    /**
     * 出怪位置数量：**最少 1 个、最多 4 个**（设计口径 原话：
     * 「该物品最少需要设置一个出怪位置，最多能同时设置4个出怪位置」）。
     *
     * <p>★ 语义（答复）：「所有怪配置完了算一组，分别分发至出怪位置（子级联），
     * 最后要保证**每个出怪位置出的怪都是同一组配置**」+「每个位置都出一份」。
     * 也就是：<b>每个标记位置都会把同一组生物各出一份</b> ——
     * 4 个位置 + 单次 3 只 = 每个生成周期共 12 只。
     * 相位推进用的计数（击杀/生成/在场）按<b>整波总计</b>算
     * （见 {@link CreatureRow} 与 {@code SpawnStrategy}）。</p>
     */
    public static final int MAX_POSITIONS = 4;

    /** 出怪位置数量下限（≥1，否则这一轮没有出口）。 */
    public static final int MIN_POSITIONS = 1;

    /** 稀有度区间（对齐 SpawnCursion 的 1~6）。 */
    public static final int MIN_RARITY = 1;
    public static final int MAX_RARITY = 6;

    /** 相位计时上限（tick）＝ 6 小时；下限 0 = 跳过该相位。 */
    public static final int MAX_PHASE_TICKS = 20 * 60 * 60 * 6;

    /** 隐藏挑战的上限比例：BOSS 战时间的 1/3（设计口径，写死在这里，别处不许再写一份）。 */
    public static final int HIDDEN_MAX_DIVISOR = 3;

    private boolean enabled = true;
    private final List<CreatureRow> rows = new ArrayList<>();
    private final List<BossEntry> bosses = new ArrayList<>();
    private int finalBossIndex = -1;
    private int hiddenBossIndex = -1;
    private double hiddenBoost = 1.5D;
    private final List<ItemStack> rewards = new ArrayList<>();
    private String displayName = "";
    private int rarity = 1;
    private int entityColor = 0xFFFFFFFF;
    private int lineColor = 0xFFFFFFFF;
    private int headColor = 0xFFFFFFFF;
    private int legColor = 0xFFFFFFFF;
    private int prepTicks = 20 * 30;
    private int combatTicks = 20 * 120;
    private int rewardTicks = 20 * 30;
    private int bossTicks = 20 * 90;
    private int hiddenTicks = 20 * 20;
    @Nullable
    private BlockPos targetPos;
    /** 出怪位置（≥1、≤4）：**每个位置都会出同一组配置**，见 {@link #MAX_POSITIONS}。 */
    private final List<BlockPos> positions = new ArrayList<>();

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public List<CreatureRow> rows() {
        return rows;
    }

    public List<BossEntry> bosses() {
        return bosses;
    }

    public int finalBossIndex() {
        return finalBossIndex;
    }

    public void setFinalBossIndex(int i) {
        this.finalBossIndex = i;
    }

    public int hiddenBossIndex() {
        return hiddenBossIndex;
    }

    public void setHiddenBossIndex(int i) {
        this.hiddenBossIndex = i;
    }

    /** 隐藏挑战里那只 BOSS 的「全面增强」倍率（血量与攻击都乘它）。 */
    public double hiddenBoost() {
        return hiddenBoost;
    }

    public void setHiddenBoost(double v) {
        this.hiddenBoost = v;
    }

    public List<ItemStack> rewards() {
        return rewards;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(@Nullable String n) {
        this.displayName = n == null ? "" : n.trim();
    }

    public int rarity() {
        return rarity;
    }

    public void setRarity(int v) {
        this.rarity = v;
    }

    public int entityColor() {
        return entityColor;
    }

    public void setEntityColor(int v) {
        this.entityColor = v;
    }

    public int lineColor() {
        return lineColor;
    }

    public void setLineColor(int v) {
        this.lineColor = v;
    }

    public int headColor() {
        return headColor;
    }

    public void setHeadColor(int v) {
        this.headColor = v;
    }

    public int legColor() {
        return legColor;
    }

    public void setLegColor(int v) {
        this.legColor = v;
    }

    public int prepTicks() {
        return prepTicks;
    }

    public void setPrepTicks(int v) {
        this.prepTicks = v;
    }

    public int combatTicks() {
        return combatTicks;
    }

    public void setCombatTicks(int v) {
        this.combatTicks = v;
    }

    public int rewardTicks() {
        return rewardTicks;
    }

    public void setRewardTicks(int v) {
        this.rewardTicks = v;
    }

    public int bossTicks() {
        return bossTicks;
    }

    public void setBossTicks(int v) {
        this.bossTicks = v;
    }

    public int hiddenTicks() {
        return hiddenTicks;
    }

    public void setHiddenTicks(int v) {
        this.hiddenTicks = v;
    }

    /** 本块所属支路的保护目标（null = 还没绑定；S2 会按「最近」兜底）。 */
    @Nullable
    public BlockPos targetPos() {
        return targetPos;
    }

    public void setTargetPos(@Nullable BlockPos pos) {
        this.targetPos = pos == null ? null : pos.immutable();
    }

    /** 出怪位置（只读视图；编辑请用 {@link #addPosition} / {@link #clearPositions}）。 */
    public List<BlockPos> positions() {
        return List.copyOf(positions);
    }

    /**
     * 加一个出怪位置。
     *
     * @return {@code true} = 加进去了；{@code false} = 已经满 {@link #MAX_POSITIONS} 个
     *         （或者这个位置已经在列表里 —— 重复标记同一个格子没有意义，见 {@link #hasPosition}）
     */
    public boolean addPosition(@Nullable BlockPos pos) {
        if (pos == null || positions.size() >= MAX_POSITIONS) {
            return false;
        }
        BlockPos p = pos.immutable();
        if (positions.contains(p)) {
            return false;
        }
        positions.add(p);
        return true;
    }

    /** 这个格子被标记过了吗（重复标记要拒绝，否则 4 个名额会被同一个点占满）。 */
    public boolean hasPosition(@Nullable BlockPos pos) {
        return pos != null && positions.contains(pos.immutable());
    }

    /** 清空出怪位置（物品的「潜行右键」走它；清空后如果还启用着，校验会提醒「至少需要 1 个」）。 */
    public void clearPositions() {
        positions.clear();
    }

    /** 这一份配置有没有可用的出口（相位机开打前必须问它）。 */
    public boolean hasAnyPosition() {
        return !positions.isEmpty();
    }

    /** 某个相位的时长（tick）；{@link InvasionPhase#COOP} 沿用战斗计时。 */
    public int ticksOf(InvasionPhase phase) {
        return switch (phase) {
            case PREP -> prepTicks;
            case COMBAT, COOP -> combatTicks;
            case REWARD -> rewardTicks;
            case BOSS -> bossTicks;
            case HIDDEN -> hiddenTicks;
        };
    }

    /** 隐藏挑战允许的上限（tick）＝ BOSS 战 / 3（唯一实现，校验与界面都用它）。 */
    public int hiddenTicksLimit() {
        return bossTicks / HIDDEN_MAX_DIVISOR;
    }

    /** 已配置好的生物行（界面与相位推进都只看这些）。 */
    public List<CreatureRow> configuredRows() {
        List<CreatureRow> out = new ArrayList<>();
        for (CreatureRow r : rows) {
            if (r.isConfigured()) {
                out.add(r);
            }
        }
        return out;
    }

    /** 已配置好的 BOSS。 */
    public List<BossEntry> configuredBosses() {
        List<BossEntry> out = new ArrayList<>();
        for (BossEntry b : bosses) {
            if (b.isConfigured()) {
                out.add(b);
            }
        }
        return out;
    }

    /** 最终 BOSS 战要打的那只；没配返回 {@code null}。 */
    @Nullable
    public BossEntry finalBoss() {
        List<BossEntry> list = bosses;
        if (finalBossIndex >= 0 && finalBossIndex < list.size()
                && list.get(finalBossIndex).isConfigured()) {
            return list.get(finalBossIndex);
        }
        List<BossEntry> configured = configuredBosses();
        return configured.isEmpty() ? null : configured.get(0);
    }

    /** 隐藏挑战用的那只（被增强的）BOSS；没配返回 {@code null}。 */
    @Nullable
    public BossEntry hiddenBoss() {
        if (hiddenBossIndex >= 0 && hiddenBossIndex < bosses.size()
                && bosses.get(hiddenBossIndex).isConfigured()) {
            return bosses.get(hiddenBossIndex);
        }
        return finalBoss();
    }

    /**
     * 夹取 + 校验，返回<b>原因清单</b>（空 = 没问题）。
     *
     * <p>只报告「我改过什么 / 什么不成立」，<b>不抛异常</b>：配置来自界面、存档与将来的数据包，
     * 一律降级处理。</p>
     */
    public List<String> validate() {
        List<String> notes = new ArrayList<>();
        // ① 计时：0~上限；隐藏挑战额外两条硬规则
        int bossBefore = bossTicks;
        bossTicks = clampTicks(bossTicks, "最终BOSS战时间", notes);
        prepTicks = clampTicks(prepTicks, "备战时间", notes);
        combatTicks = clampTicks(combatTicks, "战斗时间", notes);
        rewardTicks = clampTicks(rewardTicks, "奖励选取时间", notes);
        int hiddenBefore = hiddenTicks;
        hiddenTicks = clampTicks(hiddenTicks, "隐藏挑战限时", notes);
        int limit = hiddenTicksLimit();
        if (bossTicks <= 0 && hiddenTicks > 0) {
            // 没有 BOSS 战就没有可以依附的窗口（隐藏挑战在 BOSS 战内并行）
            hiddenTicks = 0;
            notes.add("隐藏挑战限时被清零：没有配最终BOSS战时间（隐藏挑战在 BOSS 战内并行，没有窗口可依附）");
        } else if (hiddenTicks > limit) {
            hiddenTicks = limit;
            notes.add("隐藏挑战限时被夹到 " + limit + " tick（= 最终BOSS战 "
                    + bossTicks + " / " + HIDDEN_MAX_DIVISOR + "）：设计口径要求 ≤ BOSS 战时间的 1/3");
        }
        if (bossBefore != bossTicks) {
            notes.add("最终BOSS战时间被夹到 " + bossTicks + " tick");
        }
        if (hiddenBefore != hiddenTicks && bossTicks > 0 && hiddenBefore <= hiddenTicksLimit()) {
            // 只在「不是上面两条规则改的」时才提，免得同一件事报两遍
            notes.add("隐藏挑战限时被夹到 " + hiddenTicks + " tick");
        }
        // ② 各段时长全 0 也能跑（直接结束），但要提醒一句
        if (prepTicks == 0 && combatTicks == 0 && bossTicks == 0) {
            notes.add("备战/战斗/BOSS 三段都是 0：这一轮会立刻结束（确定不是手滑？）");
        }
        // ③ 行 / BOSS / 奖励：先裁数量，再逐条夹取
        while (rows.size() > MAX_ROWS) {
            rows.remove(rows.size() - 1);
            notes.add("生物行超过上限 " + MAX_ROWS + "，末尾的行被删掉了");
        }
        while (bosses.size() > MAX_BOSSES) {
            bosses.remove(bosses.size() - 1);
            notes.add("BOSS 超过上限 " + MAX_BOSSES + "，末尾的被删掉了");
        }
        while (rewards.size() > MAX_REWARDS) {
            rewards.remove(rewards.size() - 1);
            notes.add("奖励候选超过上限 " + MAX_REWARDS + "，末尾的被删掉了");
        }
        // ④b 出怪位置：**最少 1 个、最多 4 个**（设计口径）
        while (positions.size() > MAX_POSITIONS) {
            positions.remove(positions.size() - 1);
            notes.add("出怪位置超过上限 " + MAX_POSITIONS + " 个，末尾的被删掉了");
        }
        if (enabled && positions.size() < MIN_POSITIONS) {
            notes.add("已启用但一个出怪位置都没有：这一轮不会出任何怪"
                    + "（用「出怪位置标定器」物品先与出怪点绑定，再右键地面标记位置；最少 " + MIN_POSITIONS + " 个）");
        }
        for (CreatureRow r : rows) {
            if (r.clamp()) {
                notes.add("有一行生物的数值越界，已夹到合法区间：" + r.describe());
            }
        }
        for (BossEntry b : bosses) {
            if (b.clamp()) {
                notes.add("有一条 BOSS 的数值越界，已夹到合法区间：" + b.describe());
            }
        }
        int rarityBefore = rarity;
        rarity = Math.max(MIN_RARITY, Math.min(MAX_RARITY, rarity));
        if (rarity != rarityBefore) {
            notes.add("稀有度被夹到 " + rarity + "（合法区间 " + MIN_RARITY + "~" + MAX_RARITY + "）");
        }
        // ④ 索引：负数/越界/指向空条目就回退到「第一条已配置的」（-1 = 自动）
        if (finalBossIndex < -1 || finalBossIndex >= bosses.size()
                || (finalBossIndex >= 0 && !bosses.get(finalBossIndex).isConfigured())) {
            notes.add("最终BOSS 的选择下标无效（" + finalBossIndex + "），已回退到第一条已配置的 BOSS");
            finalBossIndex = -1;
        }
        if (hiddenBossIndex < -1 || hiddenBossIndex >= bosses.size()
                || (hiddenBossIndex >= 0 && !bosses.get(hiddenBossIndex).isConfigured())) {
            notes.add("隐藏挑战 BOSS 的选择下标无效（" + hiddenBossIndex + "），已回退到最终BOSS");
            hiddenBossIndex = -1;
        }
        double boost = Math.max(1.0D, Math.min(BossEntry.MAX_HEALTH_MULTIPLIER, hiddenBoost));
        if (boost != hiddenBoost) {
            notes.add("隐藏挑战增强倍率被夹到 " + boost);
            hiddenBoost = boost;
        }
        // ⑤ 开着一场入侵却没有任何生物行 —— 这是「静默无事发生」的经典来源，必须报出来
        if (enabled && configuredRows().isEmpty()) {
            notes.add("已启用但一条生物行都没配：这一轮不会出任何怪（相位照样会推进）");
        }
        if (bossTicks > 0 && finalBoss() == null) {
            notes.add("配了最终BOSS战时间，但 BOSS 组是空的：那一相位不会生成任何东西");
        }
        return notes;
    }

    private static int clampTicks(int v, String what, List<String> notes) {
        int c = Math.max(0, Math.min(MAX_PHASE_TICKS, v));
        if (c != v) {
            notes.add(what + "越界（" + v + "），已夹到 " + c + " tick");
        }
        return c;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enabled", enabled);
        ListTag rowList = new ListTag();
        for (CreatureRow r : rows) {
            rowList.add(r.save());
        }
        tag.put("rows", rowList);
        ListTag bossList = new ListTag();
        for (BossEntry b : bosses) {
            bossList.add(b.save());
        }
        tag.put("bosses", bossList);
        tag.putInt("finalBoss", finalBossIndex);
        tag.putInt("hiddenBoss", hiddenBossIndex);
        tag.putDouble("hiddenBoost", hiddenBoost);
        ListTag rewardList = new ListTag();
        for (ItemStack s : rewards) {
            rewardList.add(s.save(new CompoundTag()));
        }
        tag.put("rewards", rewardList);
        tag.putString("name", displayName);
        tag.putInt("rarity", rarity);
        tag.putInt("entityColor", entityColor);
        tag.putInt("lineColor", lineColor);
        tag.putInt("headColor", headColor);
        tag.putInt("legColor", legColor);
        tag.putInt("prepTicks", prepTicks);
        tag.putInt("combatTicks", combatTicks);
        tag.putInt("rewardTicks", rewardTicks);
        tag.putInt("bossTicks", bossTicks);
        tag.putInt("hiddenTicks", hiddenTicks);
        if (targetPos != null) {
            tag.putLong("targetPos", targetPos.asLong());
        }
        ListTag posList = new ListTag();
        for (BlockPos p : positions) {
            CompoundTag one = new CompoundTag();
            one.putLong("p", p.asLong());
            posList.add(one);
        }
        tag.put("positions", posList);
        return tag;
    }

    public static InvasionConfig load(CompoundTag tag) {
        InvasionConfig c = new InvasionConfig();
        c.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        ListTag rowList = tag.getList("rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < rowList.size(); i++) {
            c.rows.add(CreatureRow.load(rowList.getCompound(i)));
        }
        ListTag bossList = tag.getList("bosses", Tag.TAG_COMPOUND);
        for (int i = 0; i < bossList.size(); i++) {
            c.bosses.add(BossEntry.load(bossList.getCompound(i)));
        }
        c.finalBossIndex = tag.contains("finalBoss") ? tag.getInt("finalBoss") : -1;
        c.hiddenBossIndex = tag.contains("hiddenBoss") ? tag.getInt("hiddenBoss") : -1;
        c.hiddenBoost = tag.contains("hiddenBoost") ? tag.getDouble("hiddenBoost") : 1.5D;
        ListTag rewardList = tag.getList("rewards", Tag.TAG_COMPOUND);
        for (int i = 0; i < rewardList.size(); i++) {
            c.rewards.add(ItemStack.of(rewardList.getCompound(i)));
        }
        c.displayName = tag.getString("name");
        c.rarity = tag.contains("rarity") ? tag.getInt("rarity") : 1;
        c.entityColor = tag.contains("entityColor") ? tag.getInt("entityColor") : 0xFFFFFFFF;
        c.lineColor = tag.contains("lineColor") ? tag.getInt("lineColor") : 0xFFFFFFFF;
        c.headColor = tag.contains("headColor") ? tag.getInt("headColor") : 0xFFFFFFFF;
        c.legColor = tag.contains("legColor") ? tag.getInt("legColor") : 0xFFFFFFFF;
        c.prepTicks = tag.contains("prepTicks") ? tag.getInt("prepTicks") : 20 * 30;
        c.combatTicks = tag.contains("combatTicks") ? tag.getInt("combatTicks") : 20 * 120;
        c.rewardTicks = tag.contains("rewardTicks") ? tag.getInt("rewardTicks") : 20 * 30;
        c.bossTicks = tag.contains("bossTicks") ? tag.getInt("bossTicks") : 20 * 90;
        c.hiddenTicks = tag.contains("hiddenTicks") ? tag.getInt("hiddenTicks") : 20 * 20;
        if (tag.contains("targetPos")) {
            c.targetPos = BlockPos.of(tag.getLong("targetPos"));
        }
        ListTag posList = tag.getList("positions", Tag.TAG_COMPOUND);
        for (int i = 0; i < posList.size() && c.positions.size() < MAX_POSITIONS; i++) {
            c.positions.add(BlockPos.of(posList.getCompound(i).getLong("p")));
        }
        c.validate();
        return c;
    }

    /** 报告里的一行摘要。 */
    public String describe() {
        List<CreatureRow> cr = configuredRows();
        List<BossEntry> cb = configuredBosses();
        return "启用=" + enabled
                + " 行=" + cr.size() + '/' + rows.size()
                + " BOSS=" + cb.size()
                + " 奖励=" + rewards.size()
                + " 出怪位置=" + positions.size() + '/' + MAX_POSITIONS
                + " 计时[备战=" + prepTicks + " 战斗=" + combatTicks
                + " 奖励=" + rewardTicks + " BOSS=" + bossTicks
                + " 隐藏=" + hiddenTicks + "（上限 " + hiddenTicksLimit() + "）]"
                + " 支路=" + (targetPos == null ? "未绑定" : targetPos.toShortString());
    }
}
