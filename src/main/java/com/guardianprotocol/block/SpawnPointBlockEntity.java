package com.guardianprotocol.block;

import com.guardianprotocol.blockentity.ModBlockEntities;
import com.guardianprotocol.menu.SpawnPointMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 出怪点的方块实体：存「出什么怪 / 出几只 / 多快出 / 延迟多久 / 开不开」，
 * 维护活跃实例表，并作为右键配置界面的入口。
 *
 * <h3>本类只存配置，不决定「什么时候出怪」</h3>
 * <p>这是一个<b>核心库</b>模块：出怪点只回答「按这份配置出一批怪」，
 * 至于「准备 30 秒 → 出怪 60 秒 → 协防」这种节奏，由后来的塔防项目自己决定，
 * 通过 {@code spawn/SpawnPointService} 调用。所以这里<b>没有</b> ticker、
 * 没有波次计数、没有自动触发 —— 加了就变成「本模块偷偷替上层做决定」。</p>
 *
 * <h3>★ 为什么实体 id 存「原文」而不是「解析好的类型」</h3>
 * <p>看上去更聪明的做法是：玩家在界面里敲完，服务端校验，不合法就<b>不改</b>。
 * 但这样一来玩家永远看不到自己错在哪 —— 界面每 tick 从服务端同步真值，
 * 不合法的输入会被静默回滚成旧值，表现为「我打了字它自己变回去了」，
 * 玩家只会以为界面坏了。</p>
 *
 * <p>所以这里存<b>规范化后的原文</b>（trim + 转小写 + 截断），能不能解析是<b>派生</b>的：
 * {@link #resolveEntry(int)} 解析不出来就返回 null，界面据此显示「未知实体」红色错误态，
 * 出怪侧 {@code SpawnPointService.spawnBatch} 也据此返回 false（配置无效 = 不出怪）。
 * 「配置」与「这份配置当前是否可用」是两件事，分开存才看得见错误。</p>
 *
 * <h3>同步</h3>
 * <p>与服务端权威值的一致性靠原版的方块实体更新包（{@link #getUpdatePacket()}）。
 * ★ 注意 {@code ProtectTargetBlockEntity} 是<b>免费</b>拿到同步的：它的半径存在方块状态里，
 * 改半径走 {@code level.setBlock(..., UPDATE_ALL)}，方块更新顺带把方块实体也同步了。
 * 本实体改的是 NBT 字段，{@code setChanged()} 只标脏、<b>不发包</b>，
 * 所以每次写入都必须显式 {@code sendBlockUpdated(..., UPDATE_CLIENTS)}（见 {@link #sync()}）。</p>
 */
public class SpawnPointBlockEntity extends BlockEntity implements MenuProvider {

    /** 默认怪物：尸壳。与实机存档里命令方块刷的是同一种，方便对照。 */
    public static final String DEFAULT_ENTITY_ID = "minecraft:husk";

    // ---- 数量 ----
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 20;
    public static final int DEFAULT_COUNT = 1;
    /** 「数量」± 按钮一次改多少。 */
    public static final int COUNT_STEP = 1;

    // ---- 批次内间隔 ----
    /** 默认 0 = 整批一次性出，不拖时间。 */
    public static final int DEFAULT_INTERVAL_TICKS = 0;
    /** 上限 200 tick = 10 秒。再多就不是「同一批」了，该拆成两批。 */
    public static final int MAX_INTERVAL_TICKS = 200;
    public static final int INTERVAL_STEP = 5;

    // ---- 出怪延迟 ----
    public static final int DEFAULT_DELAY_TICKS = 0;
    /** 上限 1200 tick = 60 秒。 */
    public static final int MAX_DELAY_TICKS = 1200;
    public static final int DELAY_STEP = 20;

    /** 实体 id 的最大长度（NBT 与网络包共用同一个上限，避免两边不一致）。 */
    public static final int MAX_ENTITY_ID_LENGTH = 128;

    private static final String TAG_ENTITY_ID = "EntityId";
    private static final String TAG_COUNT = "Count";
    private static final String TAG_INTERVAL = "IntervalTicks";
    private static final String TAG_DELAY = "DelayTicks";
    private static final String TAG_ENABLED = "Enabled";
    /** 条目列表的键（新格式）。没有它时按旧格式迁移，见 {@code load}。 */
    private static final String TAG_ENTRIES = "Entries";

    /** 所有已加载的出怪点方块实体（与 {@code ProtectTargetBlockEntity} 同一套登记法）。 */
    private static final List<SpawnPointBlockEntity> ACTIVE = new CopyOnWriteArrayList<>();

    /** 当前活跃实例（只读视图）。 */
    public static List<SpawnPointBlockEntity> activePoints() {
        return Collections.unmodifiableList(ACTIVE);
    }

    /**
     * 一个出怪条目：<b>一种怪 + 数量 + 它自己的延迟与间隔</b>。
     *
     * <p>为什么做成不可变 record + {@code withXxx}：条目列表会被<b>快照</b>后发给客户端显示，
     * 可变对象会让「发出去的那份」跟着服务端后续改动一起变（界面看到的和实际存的对不上）。
     * 不可变之后，「某一时刻的配置」就是一个值，快照天然是深拷贝。</p>
     *
     * <p>构造器里就做夹取：这样无论从 NBT、从数据包还是从代码进来，<b>不变量只有一处维护</b>
     * （数量 1~20、延迟 0~1200、间隔 0~200）。</p>
     */
    public record Entry(String entityId, int count, int delayTicks, int intervalTicks) {

        public Entry {
            entityId = normalizeEntityId(entityId);
            count = clamp(count, MIN_COUNT, MAX_COUNT);
            delayTicks = clamp(delayTicks, 0, MAX_DELAY_TICKS);
            intervalTicks = clamp(intervalTicks, 0, MAX_INTERVAL_TICKS);
        }

        public Entry withEntityId(String id) {
            return new Entry(id, this.count, this.delayTicks, this.intervalTicks);
        }

        public Entry withCount(int value) {
            return new Entry(this.entityId, value, this.delayTicks, this.intervalTicks);
        }

        public Entry withDelayTicks(int value) {
            return new Entry(this.entityId, this.count, value, this.intervalTicks);
        }

        public Entry withIntervalTicks(int value) {
            return new Entry(this.entityId, this.count, this.delayTicks, value);
        }
    }

    /**
     * 一条配置的完整快照（服务端 → 客户端显示用）。
     *
     * <p>界面只画这个快照，不去读客户端的方块实体 —— 这一条是本轮最贵的教训：
     * 原来界面每 tick 从<b>客户端方块实体</b>取"权威值"，而那个值要靠原版方块实体包同步过来，
     * 结果包是空的（见 {@link #getUpdateTag()} 的注释），于是界面永远显示旧值、
     * 看起来像"按钮被锁住"。改成快照后，显示的权威性由我们自己控制。
     */
    public record Snapshot(boolean enabled, List<Entry> entries) {

        public Snapshot {
            entries = List.copyOf(entries);
        }

        public static Snapshot of(SpawnPointBlockEntity point) {
            return new Snapshot(point.enabled, point.entries);
        }
    }

    /** 界面上的一个动作。语义放在方块实体里（{@link #applyEdit}），数据包只负责搬运。 */
    public enum EditOp {
        SET_ENTRY_ID,
        ENTRY_COUNT_DOWN, ENTRY_COUNT_UP,
        ENTRY_DELAY_DOWN, ENTRY_DELAY_UP,
        ENTRY_INTERVAL_DOWN, ENTRY_INTERVAL_UP,
        ADD_ENTRY, REMOVE_ENTRY,
        TOGGLE_ENABLED, RESET
    }

    /** 一组怪最多几种（界面一屏放得下的行数；要更多种类就再摆一个出怪点）。 */
    public static final int MAX_ENTRIES = 4;

    /** 新条目的默认内容。 */
    public static final Entry DEFAULT_ENTRY = new Entry(DEFAULT_ENTITY_ID, DEFAULT_COUNT,
            DEFAULT_DELAY_TICKS, DEFAULT_INTERVAL_TICKS);

    /** 出怪组：条目列表（至少一条）+ 整组开关。 */
    private final List<Entry> entries = new ArrayList<>(List.of(DEFAULT_ENTRY));

    private boolean enabled = true;

    public SpawnPointBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPAWN_POINT.get(), pos, state);
        ACTIVE.add(this);
    }

    /** 被移除（挖掉 / 区块卸载 / 世界关闭）时注销，避免静态表持有失效引用。 */
    @Override
    public void setRemoved() {
        ACTIVE.remove(this);
        super.setRemoved();
    }

    // ------------------------------------------------------------------
    // 配置项（出怪组）
    // ------------------------------------------------------------------

    /** 全部条目（只读副本）。 */
    public List<Entry> entries() {
        return List.copyOf(this.entries);
    }

    public int entryCount() {
        return this.entries.size();
    }

    /** 第 index 条；越界返回 null（界面与数据包都可能传越界的下标）。 */
    @Nullable
    public Entry entry(int index) {
        return index >= 0 && index < this.entries.size() ? this.entries.get(index) : null;
    }

    /** 当前配置的完整快照（发给客户端显示）。 */
    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.entries);
    }

    /**
     * 设置某一条的怪物实体 id。
     *
     * <p>规范化只做三件事：去空白、转小写、截断到 {@link #MAX_ENTITY_ID_LENGTH}。
     * <b>不做</b>「校验失败就丢弃」—— 玩家需要看到自己的错误（界面据此显示错误态）。</p>
     *
     * <p>转小写是必须的：{@code ResourceLocation} 允许大写字母，但注册表里的键全是小写，
     * 于是 {@code Minecraft:Husk} 能解析成 ResourceLocation、却查不到任何实体类型，
     * 表现为「明明打对了却说未知」。</p>
     *
     * @return 是否受理（下标越界 = false）
     */
    public boolean setEntryEntityId(int index, @Nullable String raw) {
        Entry old = entry(index);
        if (old == null) {
            return false;
        }
        Entry next = old.withEntityId(raw);
        if (!next.equals(old)) {
            this.entries.set(index, next);
            this.sync();
        }
        return true;
    }

    /** 数量 ±（越界由 {@link Entry} 的构造器夹取）。 */
    public boolean stepEntryCount(int index, int delta) {
        return this.step(index, old -> old.withCount(old.count() + delta));
    }

    /** 延迟 ±。 */
    public boolean stepEntryDelay(int index, int delta) {
        return this.step(index, old -> old.withDelayTicks(old.delayTicks() + delta));
    }

    /** 间隔 ±。 */
    public boolean stepEntryInterval(int index, int delta) {
        return this.step(index, old -> old.withIntervalTicks(old.intervalTicks() + delta));
    }

    /** 三种「±」共用的骨架：越界检查 → 生成新条目 → 变了才写 + 同步。 */
    private boolean step(int index, java.util.function.UnaryOperator<Entry> change) {
        Entry old = entry(index);
        if (old == null) {
            return false;
        }
        Entry next = change.apply(old);
        if (!next.equals(old)) {
            this.entries.set(index, next);
            this.sync();
        }
        return true;
    }

    /** 追加一条（到 {@link #MAX_ENTRIES} 为止）。 */
    public boolean addEntry() {
        if (this.entries.size() >= MAX_ENTRIES) {
            return false;
        }
        this.entries.add(DEFAULT_ENTRY);
        this.sync();
        return true;
    }

    /** 删掉第 index 条；最后一条不允许删（一个空的出怪组没有意义）。 */
    public boolean removeEntry(int index) {
        if (index < 0 || index >= this.entries.size() || this.entries.size() <= 1) {
            return false;
        }
        this.entries.remove(index);
        this.sync();
        return true;
    }

    /** 整组开关。 */
    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean value) {
        if (value == this.enabled) {
            return;
        }
        this.enabled = value;
        this.sync();
    }

    /** 恢复出厂配置（界面上的「恢复默认」按钮）。 */
    public void resetToDefaults() {
        this.entries.clear();
        this.entries.add(DEFAULT_ENTRY);
        this.enabled = true;
        this.sync();
    }

    /**
     * 执行一个界面动作（数据包处理器唯一调用的入口）。
     *
     * <p>把语义放在这里而不是数据包里，有两个好处：① 自验可以<b>不依赖网络</b>地
     * 把每个动作跑一遍；② 「客户端能做什么」只有一份实现，不存在「包里的判断和方块实体里的
     * 判断不一致」这种双份数据（本项目反复踩过的坑）。</p>
     *
     * <p>不变量（数量/延迟/间隔的区间、最大条目数、至少留一条）全部由下面的 setter 保证，
     * 所以无论客户端发来什么，服务端状态都是合法的。</p>
     *
     * @return 这个动作是否被受理（下标越界 / 条目已满 / 想删最后一条 = false）
     */
    public boolean applyEdit(EditOp op, int index, @Nullable String text) {
        return switch (op) {
            case SET_ENTRY_ID -> setEntryEntityId(index, text == null ? "" : text);
            case ENTRY_COUNT_DOWN -> stepEntryCount(index, -COUNT_STEP);
            case ENTRY_COUNT_UP -> stepEntryCount(index, COUNT_STEP);
            case ENTRY_DELAY_DOWN -> stepEntryDelay(index, -DELAY_STEP);
            case ENTRY_DELAY_UP -> stepEntryDelay(index, DELAY_STEP);
            case ENTRY_INTERVAL_DOWN -> stepEntryInterval(index, -INTERVAL_STEP);
            case ENTRY_INTERVAL_UP -> stepEntryInterval(index, INTERVAL_STEP);
            case ADD_ENTRY -> addEntry();
            case REMOVE_ENTRY -> removeEntry(index);
            case TOGGLE_ENABLED -> {
                setEnabled(!this.enabled);
                yield true;
            }
            case RESET -> {
                resetToDefaults();
                yield true;
            }
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 规范化实体 id（界面里的即时校验与服务端写入共用这一份，避免两边判据漂移）。
     *
     * <p>去空白 + 转小写 + 截断。大小写必须统一：注册表里的键全是小写，
     * 而 {@code ResourceLocation} 允许大写，于是 {@code Minecraft:Husk} 会「格式合法但查不到」。</p>
     */
    public static String normalizeEntityId(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return text.length() > MAX_ENTITY_ID_LENGTH ? text.substring(0, MAX_ENTITY_ID_LENGTH) : text;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 把配置的实体 id 解析成一个「能当出怪用的生物类型」。
     *
     * <p>三道门，任何一道不过就返回 null（= 配置当前不可用）：</p>
     * <ol>
     *     <li>{@link ResourceLocation#tryParse} 能解析（挡掉乱码）；</li>
     *     <li>实体注册表里真的有这个 id（挡掉拼错的 {@code minecraft:huskk}）；</li>
     *     <li>{@code canSummon()} 为真、且<b>试造一只出来确实是 {@link Mob}</b>
     *         （挡掉 {@code minecraft:item}、{@code minecraft:arrow} 这类
     *         「注册得到、却出不了怪」的条目）。</li>
     * </ol>
     *
     * <p>第 3 条是必须的：出怪点如果放出一个掉落物实体，那不是「配置奇怪」，
     * 是「这个点坏了」，而且很难从现象上看出来。</p>
     *
     * <h3>★ 第 3 条为什么用「试造一只」而不是 {@code getBaseClass()}</h3>
     * <p>直觉写法是 {@code Mob.class.isAssignableFrom(type.getBaseClass())}，
     * 而且它能编译、能跑、永远返回 false —— <b>1.20.1 的原版
     * {@code EntityType.getBaseClass()} 是个写死的桩方法，恒返回 {@code Entity.class}</b>
     * （见 1.20.1 源码：{@code public Class<? extends Entity> getBaseClass() { return Entity.class; }}）。
     * 实测症状：默认值 {@code minecraft:husk} 都判成「无法解析」，出怪点一只怪都出不来，
     * 而所有 API 调用都不报错。本项目第一次跑 spawnpoint 自验就是被这个桩方法打回来的。</p>
     *
     * <p>另外一个看似可行的判据 {@code getCategory() != MobCategory.MISC} 也是错的：
     * 村民、铁傀儡、雪傀儡在原版里就是 {@code MISC}，它们完全可以是合法的塔防单位。
     * 所以只能真造一只来问 —— 造出来的实例不入世界，用完即弃，代价可以忽略。</p>
     *
     * @param level 用来试造实体的世界；为 null 时退化为「注册表里有且可召唤」（造不了就没法验第 3 条）
     */
    /**
     * 解析第 index 条能不能出怪（界面每行下面那行结论用它）。
     *
     * <p>下标越界时返回一条「空 id」的失败结论，而不是 null —— 界面统一按结论渲染，
     * 少一处判空就少一个 NPE。</p>
     */
    public Resolution resolveEntry(int index) {
        Entry e = entry(index);
        return inspect(this.level, e == null ? "" : e.entityId());
    }

    /**
     * 一次解析的全部结论（能不能出怪 + 界面该显示什么）。
     *
     * <p>做成一条结果而不是两个方法：界面要同时拿这两样，拆开就会把「试造实体」这种
     * 有成本的校验做两遍，而且两遍之间的判据一旦漂移就会显示与实践不符的结论。</p>
     *
     * @param type 解析出来的生物类型；null = 这份配置当前出不了怪
     * @param display 给界面用的一行结论（生物名 / 未知实体 / 不是生物 / 格式错）
     */
    public record Resolution(@Nullable EntityType<?> type, Component display) {

        /** 这份配置当前能不能出怪。 */
        public boolean spawnable() {
            return this.type != null;
        }
    }

    /** {@link #resolveEntry(int)} 的完整版；界面在「还没提交的输入」上做即时校验时用。 */
    public static Resolution inspect(@Nullable Level level, @Nullable String raw) {
        String text = normalizeEntityId(raw);
        if (text.isEmpty()) {
            return new Resolution(null,
                    Component.translatable("gui.guardian_protocol.spawn_point.invalid", text));
        }
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null) {
            return new Resolution(null,
                    Component.translatable("gui.guardian_protocol.spawn_point.invalid", text));
        }
        // ★★ 必须先 containsKey 再 getValue，不能只靠 getValue == null 判断「这个 id 不存在」。
        //    Forge 的 ForgeRegistry.getValue(未注册的 key) 会返回**注册表的默认值**，
        //    而实体注册表的默认值是 minecraft:pig（见 ForgeRegistry#getValue 的
        //    `return ret == null ? this.defaultValue : ret;`）。
        //    实测症状：随便乱敲一个 id，它被解析成「猪」，于是出怪点一本正经地开始出猪 ——
        //    不报错、不崩溃，只是出的怪不是你写的那个。这条是跑 spawnpoint 自验才抓出来的。
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            return new Resolution(null,
                    Component.translatable("gui.guardian_protocol.spawn_point.unknown", id.toString()));
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || !type.canSummon()) {
            return new Resolution(null,
                    Component.translatable("gui.guardian_protocol.spawn_point.unknown", id.toString()));
        }
        if (level != null && !(type.create(level) instanceof Mob)) {
            return new Resolution(null,
                    Component.translatable("gui.guardian_protocol.spawn_point.not_mob", id.toString()));
        }
        return new Resolution(type,
                Component.translatable("gui.guardian_protocol.spawn_point.resolved",
                        type.getDescription().getString()));
    }

    /** 只解析类型（不需要那行界面文字时用）。 */
    @Nullable
    public static EntityType<?> resolveMobType(@Nullable Level level, @Nullable String raw) {
        return inspect(level, raw).type();
    }

    /**
     * 给界面用的「人类可读结论」：解析成功显示生物名，失败显示具体是哪一步不过。
     *
     * <p>为什么要分三种失败：{@code "husk"}（少了命名空间）、
     * {@code "minecraft:huskk"}（拼错）、{@code "minecraft:item"}（不是生物）
     * 对玩家来说是三种完全不同的错，只回一句「无效」等于让他自己猜。</p>
     */
    public static Component describeEntityId(@Nullable Level level, @Nullable String raw) {
        return inspect(level, raw).display();
    }

    /** 一行配置摘要：自验报告与日志共用，避免两处各拼一份（本项目的双份数据老坑）。 */
    public String debugLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("pos=").append(this.worldPosition.toShortString())
                .append(" 启用=").append(this.enabled)
                .append(" 共 ").append(this.entries.size()).append(" 种：");
        for (int i = 0; i < this.entries.size(); i++) {
            Entry e = this.entries.get(i);
            Resolution r = resolveEntry(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append('[').append(i).append("] ").append(e.entityId())
                    .append(r.spawnable() ? "（" + r.type().getDescription().getString() + "）" : "（无法解析）")
                    .append(" ×").append(e.count())
                    .append(" 延迟").append(e.delayTicks()).append("t")
                    .append(" 间隔").append(e.intervalTicks()).append("t");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 界面（MenuProvider）
    // ------------------------------------------------------------------

    /** 界面标题。 */
    @Override
    public Component getDisplayName() {
        return Component.translatable("block.guardian_protocol.spawn_point");
    }

    /** 服务端打开界面时创建容器，并把「当前配置快照」推给这名玩家（界面只画快照）。 */
    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.guardianprotocol.net.ModNetwork.sendSnapshot(serverPlayer, this);
        }
        return new SpawnPointMenu(containerId, inventory, this);
    }

    // ------------------------------------------------------------------
    // 存档与同步
    // ------------------------------------------------------------------

    /**
     * 生成式入侵的配置（2026-10 起本方块是<b>配置器</b>）。
     *
     * <p>★ 它<b>不再自己出兵</b>：出怪位置由「出怪位置标定器」标定后写进
     * {@link com.guardianprotocol.spawn.invasion.InvasionConfig#positions()}，
     * 相位机（S2）按那些位置出怪。旧的 {@code entries}（出怪组）保留给
     * {@code SpawnPointService} 的老入口与自验，不再作为入侵的出怪来源。</p>
     */
    private com.guardianprotocol.spawn.invasion.InvasionConfig invasion =
            new com.guardianprotocol.spawn.invasion.InvasionConfig();

    /** 生成式入侵配置（可写：标定器与配置界面都通过它落地）。 */
    public com.guardianprotocol.spawn.invasion.InvasionConfig invasion() {
        return this.invasion;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (Entry e : this.entries) {
            CompoundTag one = new CompoundTag();
            one.putString(TAG_ENTITY_ID, e.entityId());
            one.putInt(TAG_COUNT, e.count());
            one.putInt(TAG_DELAY, e.delayTicks());
            one.putInt(TAG_INTERVAL, e.intervalTicks());
            list.add(one);
        }
        tag.put(TAG_ENTRIES, list);
        tag.putBoolean(TAG_ENABLED, this.enabled);
        // 入侵配置（老存档没有这个键 → load 里保持默认值，等于「还没配」）
        tag.put("Invasion", this.invasion.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // 优先读新格式（条目列表）；没有才按旧格式（单条目）迁移。
        // ★ 迁移必须留着：老存档里的出怪点是单条目写法，不认它会变成「4 只 husk 的默认组」。
        if (tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            this.entries.clear();
            for (int i = 0; i < list.size() && this.entries.size() < MAX_ENTRIES; i++) {
                CompoundTag one = list.getCompound(i);
                // 越界值由 Entry 的构造器夹回合法区间（老存档/被手改过的 NBT 都可能越界）
                this.entries.add(new Entry(one.getString(TAG_ENTITY_ID), one.getInt(TAG_COUNT),
                        one.getInt(TAG_DELAY), one.getInt(TAG_INTERVAL)));
            }
            if (this.entries.isEmpty()) {
                this.entries.add(DEFAULT_ENTRY);
            }
        } else if (tag.contains(TAG_ENTITY_ID) || tag.contains(TAG_COUNT)) {
            this.entries.clear();
            this.entries.add(new Entry(tag.getString(TAG_ENTITY_ID), tag.getInt(TAG_COUNT),
                    tag.getInt(TAG_DELAY), tag.getInt(TAG_INTERVAL)));
        }
        if (tag.contains(TAG_ENABLED)) {
            this.enabled = tag.getBoolean(TAG_ENABLED);
        }
        // ★ 入侵配置要**替换整个对象**（字段不是 final 就是为了这里）：load 出来的是一份新实例，
        //   就地复制字段既啰嗦又容易漏一个（漏了的表现是「改过的配置读回来少了半项」）。
        if (tag.contains("Invasion", Tag.TAG_COMPOUND)) {
            this.invasion = com.guardianprotocol.spawn.invasion.InvasionConfig.load(tag.getCompound("Invasion"));
        }
    }

    /**
     * 标脏 + 把新配置推给客户端。
     *
     * <p>★ 这里的 {@code sendBlockUpdated} 不是可选项：{@link #getUpdatePacket()} 只在
     * 「方块被更新」时才会被原版调用，{@code setChanged()} 仅仅是把区块标记为待存档。
     * 漏掉它的症状很典型：单人游戏里界面数字点了没反应（客户端读的是自己的旧 NBT），
     * 而退出重进又对了 —— 极难联想。</p>
     */
    private void sync() {
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * ★★★ 必须重写它，否则同步过去的是一个<b>空包</b>。
     *
     * <p>原版 {@code BlockEntity#getUpdateTag()} 的默认实现返回 {@code new CompoundTag()}
     * —— 一个空 tag（javap 反编译确认：{@code new CompoundTag(); areturn}）。
     * 而 {@code ClientboundBlockEntityDataPacket.create(this)} 用的正是它。
     * 所以「重写了 {@link #getUpdatePacket()} 却没重写这个」的后果是：
     * 服务端确实发了包、客户端确实收到了、{@code load(空tag)} 什么也没改 ——
     * <b>不报错、不崩溃，只是界面永远显示旧值</b>（实测症状：单人里点按钮“被锁住”，
     * 而 {@code /data get block} 看服务端又明明是新值）。</p>
     */
    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    /** 服务端 -> 客户端：方块实体内容同步。 */
    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        // 客户端只用来显示，直接把 NBT 读进来即可。
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            this.load(tag);
        }
    }
}
