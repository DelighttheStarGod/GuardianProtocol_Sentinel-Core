package com.guardianprotocol.data;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 卫戍协议的 8 大职业。
 *
 * <p>职业划分依据参考表「职业机制总览」：8 个职业、共 72 个分支。
 * 每个职业有自己的**贴图**（像素小人）与**默认攻击范围形状**；
 * 具体分支（冲锋手 / 剑豪 / 速射手 …）见 {@link UnitBranch}。</p>
 *
 * <p>第一阶段只做 8 大职业各 1 个代表分支，所以这里的
 * {@link #defaultRangeKey} 只在「没指定分支」时兜底。</p>
 *
 * <p><b>兜底范围键用游戏真实 rangeId</b>（见 {@link RangeShapes}）。
 * 取值口径是「该职业最常见的那个范围」——不是随便挑的：
 * 近战职业普遍是 {@code 1-1}（本格+正前 1 格），远程职业按各自代表范围给。
 * 它只在分支数据缺失时生效，正常路径都走 {@link UnitBranch#attackRangeKey()}。</p>
 */
public enum UnitClass {

    /** 先锋：近战位为主，阻挡 1–2，部署费用低，费用回复核心。 */
    VANGUARD("vanguard", "先锋", "1-1", 0xE0B070, 0x5A3A18),
    /** 近卫：全近战位，分支最多，输出主力。 */
    GUARD("guard", "近卫", "1-1", 0xD8D8E0, 0x4A4A56),
    /** 狙击：全远程位，物理输出核心，阻挡固定 1。 */
    SNIPER("sniper", "狙击", "3-3", 0x88A878, 0x2E3A28),
    /** 重装：全近战位，部署费用最高，防护与阻挡。 */
    DEFENDER("defender", "重装", "1-1", 0x8E9CAE, 0x2A323C),
    /** 术师：全远程位，法术伤害核心。 */
    CASTER("caster", "术师", "3-1", 0x8B7FE0, 0x2E2A5A),
    /** 医疗：全远程位，治疗覆盖。 */
    MEDIC("medic", "医疗", "3-10", 0xF0F0F0, 0xC03A3A),
    /** 辅助：远程位为主，支援 / 减速 / 召唤。 */
    SUPPORTER("support", "辅助", "y-6", 0x6FA8A6, 0x25403F),
    /** 特种：近战位为主，阻挡 0–2，机制最丰富（位移 / 陷阱 / 快速复活）。 */
    SPECIALIST("specialist", "特种", "1-1", 0xC07A9A, 0x40202E);

    /** 贴图文件名（assets/guardian_protocol/textures/entity/unit/<id>.png）。 */
    private final String spriteId;
    /** 中文职业名。 */
    private final String displayName;
    /** 默认攻击范围形状键（见 {@link AttackRange}）。 */
    private final String defaultRangeKey;
    /** 刷怪蛋主色。 */
    private final int eggPrimary;
    /** 刷怪蛋副色。 */
    private final int eggSecondary;

    UnitClass(String spriteId, String displayName, String defaultRangeKey,
              int eggPrimary, int eggSecondary) {
        this.spriteId = spriteId;
        this.displayName = displayName;
        this.defaultRangeKey = defaultRangeKey;
        this.eggPrimary = eggPrimary;
        this.eggSecondary = eggSecondary;
    }

    /** 刷怪蛋主色（ARGB 的 RGB 部分）。 */
    public int primaryColorForEgg() {
        return eggPrimary;
    }

    /** 刷怪蛋副色。 */
    public int secondaryColorForEgg() {
        return eggSecondary;
    }

    /** 贴图文件名。注意辅助职业用的是 {@code support} 而不是拼音。 */
    public String spriteId() {
        return spriteId;
    }

    public String displayName() {
        return displayName;
    }

    /** 默认攻击范围形状键。 */
    public String defaultRangeKey() {
        return defaultRangeKey;
    }

    /** 该职业的贴图资源路径。 */
    public net.minecraft.resources.ResourceLocation spriteTexture() {
        // 1.20.1 没有 fromNamespaceAndPath（1.21 才加），只能用构造器。
        @SuppressWarnings("removal")
        net.minecraft.resources.ResourceLocation loc = new net.minecraft.resources.ResourceLocation(
                com.guardianprotocol.GuardianProtocol.MODID,
                "textures/entity/unit/" + spriteId + ".png");
        return loc;
    }

    /** 翻译键，用于命名牌 / 物品名。 */
    public String translationKey() {
        return "unit.guardian_protocol." + name().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static UnitClass byOrdinal(int ordinal) {
        UnitClass[] v = values();
        return ordinal < 0 || ordinal >= v.length ? null : v[ordinal];
    }

    /** 按贴图 id 或中文名查找（大小写不敏感）。 */
    @Nullable
    public static UnitClass byName(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (UnitClass c : values()) {
            if (c.spriteId.equals(key) || c.displayName.equals(name.trim())
                    || c.name().toLowerCase(Locale.ROOT).equals(key)) {
                return c;
            }
        }
        return null;
    }
}
