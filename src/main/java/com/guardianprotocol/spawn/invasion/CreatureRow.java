package com.guardianprotocol.spawn.invasion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 入侵配置里的<b>一行生物</b>（= SpawnCursion 的一个 {@code ScCreature}）。
 *
 * <p>一行 = 一种怪 + 它自己的生成参数 + 它自己的实体修正。相位推进看的就是「这一行清完了没有」，
 * 判据在 {@link SpawnStrategy}。</p>
 *
 * <h3>★ 这一层只存「配置」，不存「进度」</h3>
 * <p>生成过几只、杀了几只、场上还剩几只 —— 那是<b>运行时状态</b>，属于入侵运行时（S2），
 * 不进 NBT、也不进本类。理由：同一份配置会被反复重开一轮（备战→战斗→…→结束→再来一轮），
 * 而配置不该在重开时被抹掉；把两者混在一起，就会出现「改一下配置把进度也清了」这种怪事
 * （同「同一份数据两处存放」的老坑，踩坑记录）。</p>
 *
 * <h3>字段边界（{@link #clamp()} 是唯一实现）</h3>
 * <ul>
 *     <li>数量类：{@code 0 ~ 1_000_000}；{@code spawnCnt} 另限 {@code 1 ~ }{@link #MAX_SPAWN_CNT}；</li>
 *     <li>时间类：{@code firstSpawn 0 ~ }{@link #MAX_TICKS}，{@code interval 1 ~ }{@link #MAX_TICKS}；</li>
 *     <li><b>落点</b>：★ 2026-10 设计口径「取消 range」—— 本行<b>没有半径</b>了。
 *         出怪位置由配置级的 {@link InvasionConfig#positions()}（≥1、≤4 个标记位置）决定，
 *         每个标记位置都会生成同一组配置（见 {@code InvasionConfig} 的类注释）；</li>
 *     <li>实体 id：<b>不在这里校验存在性</b>（那是「解析」的活，见 {@code SpawnPointService.resolveMobType}），
 *         本层只保证「是个合法字符串」——存档里可能留着别的整合包的 id，读的时候不许炸。</li>
 * </ul>
 */
public final class CreatureRow {

    /** 单次最多生成几只（配置上限；实际还受 {@code MobCategory} 刷怪上限等原版规则约束）。 */
    public static final int MAX_SPAWN_CNT = 64;

    /** 时间字段上限（tick）＝ 1 小时，防止手滑写 9 位数把相位卡死。 */
    public static final int MAX_TICKS = 20 * 60 * 60;

    /** 每行最多几条属性修正 / 药水效果（界面按这个上限画槽位）。 */
    public static final int MAX_ATTRS = 2;
    public static final int MAX_POTIONS = 2;

    private String entity = "";
    private SpawnStrategy strategy = SpawnStrategy.KILL;
    private int killAmt;
    private int createAmt;
    private int existingAmt;
    private int spawnCnt = 1;
    private int firstSpawn;
    private int interval = 100;
    private final List<AttrMod> attrs = new ArrayList<>();
    private final List<PotionMod> potions = new ArrayList<>();
    /** 改 AI 目标：让这一行生成的怪优先打谁（空 = 不改），对齐 SpawnCursion 的 {@code taradd}。 */
    private String targetOverride = "";

    public String entity() {
        return entity;
    }

    /** 设置实体 id（null / 空白一律存成空串 = 这一行没配好）。 */
    public void setEntity(@Nullable String id) {
        this.entity = id == null ? "" : id.trim();
    }

    /** 这一行配好了没有（界面与相位推进都要问它）。 */
    public boolean isConfigured() {
        return !entity.isEmpty() || !targetOverride.isEmpty();
    }

    public SpawnStrategy strategy() {
        return strategy;
    }

    public void setStrategy(@Nullable SpawnStrategy s) {
        // null 一律退回默认档：策略是相位推进的判据，缺了会退化成「永远不达标」
        this.strategy = s == null ? SpawnStrategy.KILL : s;
    }

    public int killAmt() {
        return killAmt;
    }

    public void setKillAmt(int v) {
        this.killAmt = v;
    }

    public int createAmt() {
        return createAmt;
    }

    public void setCreateAmt(int v) {
        this.createAmt = v;
    }

    public int existingAmt() {
        return existingAmt;
    }

    public void setExistingAmt(int v) {
        this.existingAmt = v;
    }

    public int spawnCnt() {
        return spawnCnt;
    }

    public void setSpawnCnt(int v) {
        this.spawnCnt = v;
    }

    public int firstSpawn() {
        return firstSpawn;
    }

    public void setFirstSpawn(int v) {
        this.firstSpawn = v;
    }

    public int interval() {
        return interval;
    }

    public void setInterval(int v) {
        this.interval = v;
    }

    public List<AttrMod> attrs() {
        return attrs;
    }

    public List<PotionMod> potions() {
        return potions;
    }

    public String targetOverride() {
        return targetOverride;
    }

    public void setTargetOverride(@Nullable String id) {
        this.targetOverride = id == null ? "" : id.trim();
    }

    /**
     * 把这一行的所有数值夹进合法区间，返回<b>是否改动过</b>。
     *
     * <p>「夹取」而不是「抛异常」：配置来自界面/存档/将来的数据包，玩家手滑写个越界值
     * 只该被拉回边界并告诉他，不该让服务端在 tick 里炸掉（与 {@code PixelUnit#setTier}
     * 同一个口径）。返回值让调用方能把「我改过你的值」回报出来。</p>
     */
    public boolean clamp() {
        boolean changed = false;
        if (killAmt < 0) {
            killAmt = 0;
            changed = true;
        }
        if (createAmt < 0) {
            createAmt = 0;
            changed = true;
        }
        if (existingAmt < 0) {
            existingAmt = 0;
            changed = true;
        }
        int cnt = Math.max(1, Math.min(MAX_SPAWN_CNT, spawnCnt));
        if (cnt != spawnCnt) {
            spawnCnt = cnt;
            changed = true;
        }
        int first = Math.max(0, Math.min(MAX_TICKS, firstSpawn));
        if (first != firstSpawn) {
            firstSpawn = first;
            changed = true;
        }
        int iv = Math.max(1, Math.min(MAX_TICKS, interval));
        if (iv != interval) {
            interval = iv;
            changed = true;
        }
        while (attrs.size() > MAX_ATTRS) {
            attrs.remove(attrs.size() - 1);
            changed = true;
        }
        while (potions.size() > MAX_POTIONS) {
            potions.remove(potions.size() - 1);
            changed = true;
        }
        for (AttrMod a : attrs) {
            changed |= a.clamp();
        }
        for (PotionMod p : potions) {
            changed |= p.clamp();
        }
        return changed;
    }

    /** 写进 NBT（键名短而稳定，见类注释：配置是存档的一部分，键名不能随手改）。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("entity", entity);
        tag.putString("strategy", strategy.name());
        tag.putInt("kill", killAmt);
        tag.putInt("create", createAmt);
        tag.putInt("exist", existingAmt);
        tag.putInt("cnt", spawnCnt);
        tag.putInt("first", firstSpawn);
        tag.putInt("interval", interval);
        tag.putString("target", targetOverride);
        ListTag attrList = new ListTag();
        for (AttrMod a : attrs) {
            attrList.add(a.save());
        }
        tag.put("attrs", attrList);
        ListTag potionList = new ListTag();
        for (PotionMod p : potions) {
            potionList.add(p.save());
        }
        tag.put("potions", potionList);
        return tag;
    }

    /** 从 NBT 读；缺键/写坏一律退默认值（老存档没有这些键是正常的）。 */
    public static CreatureRow load(CompoundTag tag) {
        CreatureRow row = new CreatureRow();
        row.setEntity(tag.getString("entity"));
        row.setStrategy(SpawnStrategy.byName(tag.getString("strategy")));
        row.killAmt = tag.getInt("kill");
        row.createAmt = tag.getInt("create");
        row.existingAmt = tag.getInt("exist");
        row.spawnCnt = tag.contains("cnt") ? tag.getInt("cnt") : 1;
        row.firstSpawn = tag.getInt("first");
        row.interval = tag.contains("interval") ? tag.getInt("interval") : 100;
        row.setTargetOverride(tag.getString("target"));
        ListTag attrList = tag.getList("attrs", Tag.TAG_COMPOUND);
        for (int i = 0; i < attrList.size(); i++) {
            row.attrs.add(AttrMod.load(attrList.getCompound(i)));
        }
        ListTag potionList = tag.getList("potions", Tag.TAG_COMPOUND);
        for (int i = 0; i < potionList.size(); i++) {
            row.potions.add(PotionMod.load(potionList.getCompound(i)));
        }
        row.clamp();
        return row;
    }

    /** 报告/界面里的一行摘要。 */
    public String describe() {
        if (entity.isEmpty()) {
            return "（空行）";
        }
        StringBuilder sb = new StringBuilder(entity).append(' ').append(strategy.display);
        if (strategy.usesKill()) {
            sb.append(" 杀=").append(killAmt);
        }
        if (strategy.usesCreate()) {
            sb.append(" 生=").append(createAmt);
        }
        if (strategy.usesExisting()) {
            sb.append(" 在场<").append(existingAmt);
        }
        sb.append(" 单次=").append(spawnCnt)
                .append(" 首=").append(firstSpawn).append("t")
                .append(" 隔=").append(interval).append("t");
        if (!attrs.isEmpty() || !potions.isEmpty() || !targetOverride.isEmpty()) {
            sb.append(" 修正=").append(attrs.size()).append("属性/")
                    .append(potions.size()).append("效果")
                    .append(targetOverride.isEmpty() ? "" : ("/改目标→" + targetOverride));
        }
        return sb.toString();
    }

    /**
     * 一条属性修正（对齐 SpawnCursion 的 {@code attr-*}）。
     *
     * @param attribute 属性 id（如 {@code minecraft:generic.max_health}；本层只存字符串，
     *                  解析成 {@code Attribute} 是 S4 的活）
     * @param op        运算：{@code ADDITION} / {@code MULTIPLY_BASE} / {@code MULTIPLY_TOTAL}
     * @param value     数值
     */
    public record AttrMod(String attribute, String op, double value) {

        /** 运算名白名单（写错就退回 {@code ADDITION}，别让一个错字把整行效果吞掉）。 */
        public static final List<String> OPS = List.of("ADDITION", "MULTIPLY_BASE", "MULTIPLY_TOTAL");

        public boolean clamp() {
            return false;   // 数值本身没有非法区间（负数加成是合法的减益）
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("attr", attribute == null ? "" : attribute);
            t.putString("op", normalizedOp());
            t.putDouble("val", value);
            return t;
        }

        public static AttrMod load(CompoundTag t) {
            String attr = t.getString("attr");
            String op = t.getString("op");
            return new AttrMod(attr, OPS.contains(op.toUpperCase(java.util.Locale.ROOT)) ? op.toUpperCase(java.util.Locale.ROOT) : "ADDITION",
                    t.getDouble("val"));
        }

        private String normalizedOp() {
            String up = op == null ? "" : op.toUpperCase(java.util.Locale.ROOT);
            return OPS.contains(up) ? up : "ADDITION";
        }
    }

    /**
     * 一条药水效果（对齐 SpawnCursion 的 {@code effc-*}）。
     *
     * @param effect   效果 id（如 {@code minecraft:strength}）
     * @param level    等级（1 起；原版内部用 amplifier = level-1）
     * @param duration 持续 tick
     * @param visible  粒子是否可见
     */
    public record PotionMod(String effect, int level, int duration, boolean visible) {

        public boolean clamp() {
            return false;
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("id", effect == null ? "" : effect);
            t.putInt("lvl", level);
            t.putInt("dur", duration);
            t.putBoolean("vis", visible);
            return t;
        }

        public static PotionMod load(CompoundTag t) {
            return new PotionMod(t.getString("id"), Math.max(1, t.getInt("lvl")),
                    Math.max(0, t.getInt("dur")), !t.contains("vis") || t.getBoolean("vis"));
        }
    }

    /** 把字符串当实体 id 试着解析一下（只判格式，不判存在性；{@code null} = 格式不合法）。 */
    @Nullable
    public static ResourceLocation parseId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ResourceLocation.tryParse(raw.trim());
    }
}
