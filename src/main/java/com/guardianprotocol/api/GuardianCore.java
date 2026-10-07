package com.guardianprotocol.api;

import com.guardianprotocol.GuardianProtocol;
import net.minecraftforge.fml.ModList;

/**
 * 卫戍协议 core 的**公开 API 入口** —— 后续项目（塔防循环 / 商店 / 羁绊 / 角色导入）
 * 只应该通过这个包里的类访问本 mod。
 *
 * <h3>为什么单独开一个包</h3>
 * <p>本项目有 8 个包、几十个类，但「哪些是对外契约、哪些是内部实现」原本只写在 设计说明里。
 * 于是这里立一层**薄门面**：<b>只做转发，不含任何新逻辑</b>。好处有三：</p>
 * <ul>
 *     <li>下游 import 的是 {@code com.guardianprotocol.api.*}，内部类以后怎么搬家都不影响它；</li>
 *     <li>「哪些算公开」从文档约定变成**编译期可见的事实**（想看能调什么，看这个包的类就行）；</li>
 *     <li>契约要变时，只需改这一层并在这里写清版本口径，不必去翻全工程。</li>
 * </ul>
 *
 * <h3>为什么不直接把内部类标成 public 就完事</h3>
 * <p>因为「公开」这件事需要**写下来的边界**：内部类会为了自己的方便随意改签名，
 * 而门面层一旦定下就要求向后兼容（见 {@link #API_VERSION}）。</p>
 *
 * <h3>这一层刻意不做的事</h3>
 * <ul>
 *     <li><b>不加自定义事件</b>：现在还说不清下游需要哪些钩子（羁绊的 KILL/DEPLOY？
 *         塔防的失守？），**过早冻结事件类型比晚一点加更贵**。要事件时在这里加，
 *         并同时写清「什么时候触发、在哪个线程、能不能改状态」。</li>
 *     <li><b>不暴露内部管理器</b>：`PawnCombatManager`、`ProtectTargetManager` 这类
 *         带 tick 状态的东西不进这个包 —— 下游能读能改它们的话，core 就没法保证自己的行为。</li>
 * </ul>
 */
public final class GuardianCore {

    /** 本 mod 的 modId（下游在 mods.toml 里声明依赖时用）。 */
    public static final String MODID = GuardianProtocol.MODID;

    /**
     * 公开 API 的版本（与 mod 版本分开）。
     *
     * <p>口径：<b>只加不改</b>；但★ 设计口径 定了另一条更高优先级的规则：
     * <b>项目「未完成」期间，所有改动都算 0.1.0 —— 不论是否造成破坏性改变，都不抬版本号</b>。
     * 所以下面记的是「0.1.0 期间发生过的破坏性改动」与迁移方式，
     * 「抬 API_VERSION」这条动作要等项目宣布完成、正式发版之后才生效。</p>
     *
     * <h3>0.1.0 期间发生过的破坏性改动（未抬版本，按上条规则）</h3>
     * <p>占位冷却被<b>技力（SP）</b>机制取代，{@code api.Skills} 上有三处破坏性改动：</p>
     * <ul>
     *     <li>{@code cooldownOf(...)} / {@code setCooldown(...)} <b>删除</b>（占位冷却这个字段
     *         已经从 {@code PixelUnit} 里去掉，连带 NBT 键 {@code SkillCooldown} 也不再产生）；
     *         → 迁移：读技力用 {@code pointsOf(...)} / {@code activeTicksOf(...)} / {@code ammoOf(...)}；</li>
     *     <li>{@code setSlot(...)} 的返回语义变了：以前非法槽位静默夹取、返回 {@code void}，
     *         现在返回一句<b>中文拒绝理由</b>（空串 = 成功）—— 槽位 3 在「只有 2 条技能」的分支上
     *         会被明确拒绝（近卫·佣兵 / 近卫·本源近卫 / 狙击·裂空炮手 / 辅助·游击手）；</li>
     *     <li>{@code maxPointsOf(...)} 的语义从「占位冷却时长」变成「该技能的技力上限（= 消耗技力）」。</li>
     * </ul>
     * <p>下游若按旧签名写过代码，这几条会让它编译不过或读错数 —— 迁移方式见 设计说明。</p>
     */
    public static final String API_VERSION = "0.1";

    private GuardianCore() {
    }

    /**
     * 本 mod 的实际版本（从 mods.toml 的 mod 元数据读，避免把版本号抄第二份）。
     *
     * <p>在 mod 加载完成前调用会拿不到容器，那时返回 {@code "dev"} —— 不抛异常，
     * 免得「只是打印一行版本」把启动搞崩。</p>
     */
    public static String modVersion() {
        try {
            return ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("dev");
        } catch (RuntimeException ex) {
            return "dev";
        }
    }

    /** 一行版本描述，适合打进日志：{@code guardian_protocol 0.1.0 (api 0.1)}。 */
    public static String describe() {
        return MODID + " " + modVersion() + " (api " + API_VERSION + ")";
    }
}
