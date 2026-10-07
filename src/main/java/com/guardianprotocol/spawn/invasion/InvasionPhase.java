package com.guardianprotocol.spawn.invasion;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 入侵的相位（设计口径：**计时器驱动**，见 设计文档 3.3）。
 *
 * <p>顺序：{@link #PREP 备战} → {@link #COMBAT 战斗} →（漏怪则 {@link #COOP 协防}）→
 * {@link #REWARD 奖励选取} → {@link #BOSS 最终 BOSS 战}（{@link #HIDDEN 隐藏挑战}在 BOSS 战内并行）。
 * <b>除最终 BOSS 战外，战斗结束都回备战</b>（战备里买卖棋子/升商店——那些属后续项目，本项目只写文档）。</p>
 *
 * <p>每个相位对应配置里一个计时字段（tick）。<b>配 0 = 跳过该相位</b>（不等待）。</p>
 */
public enum InvasionPhase {

    /** 备战：玩家布置。到点进战斗。 */
    PREP("备战"),

    /** 战斗：按生物行的策略生成怪；结束条件见 设计文档。 */
    COMBAT("战斗"),

    /** 协防战斗：多人组别有支路漏怪时进入，**沿用战斗计时**；最多 2 人（清怪最快的两位）。 */
    COOP("协防战斗"),

    /** 奖励选取：N 选 1 候选。 */
    REWARD("奖励选取"),

    /** 最终 BOSS 战：生成 BOSS 组里选定的那一只。 */
    BOSS("最终BOSS战"),

    /** 隐藏挑战：在 BOSS 战内并行，限时 ≤ BOSS 战 1/3。 */
    HIDDEN("隐藏挑战");

    /** 界面/报告里的中文名。 */
    public final String display;

    InvasionPhase(String display) {
        this.display = display;
    }

    /** 这个相位的计时字段名（配置里的键名，报告与界面共用一处，别处不许再拼字符串）。 */
    public String timerKey() {
        return switch (this) {
            case PREP -> "prepTicks";
            case COMBAT, COOP -> "combatTicks";      // ★ 协防沿用战斗计时
            case REWARD -> "rewardTicks";
            case BOSS -> "bossTicks";
            case HIDDEN -> "hiddenTicks";
        };
    }

    /** 按名字解析（大小写不敏感）；认不出来返回 {@code null}（外部输入只降级）。 */
    @Nullable
    public static InvasionPhase byName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String want = raw.trim().toUpperCase(Locale.ROOT);
        for (InvasionPhase p : values()) {
            if (p.name().equals(want)) {
                return p;
            }
        }
        return null;
    }
}
