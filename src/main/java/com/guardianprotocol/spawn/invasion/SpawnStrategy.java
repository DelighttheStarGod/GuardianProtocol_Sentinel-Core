package com.guardianprotocol.spawn.invasion;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 一个生物生成单元的「生命周期策略」（设计确定：对齐 SpawnCursion 的四档）。
 *
 * <p>这个枚举决定一件事：<b>这一行什么时候算「清完」</b>。整套入侵的相位推进
 * （战斗阶段能不能进奖励）就看每行的达标结果，所以四个档位的语义必须写死在这里，
 * 不许在别处再判一次。</p>
 *
 * <table border="1">
 *     <caption>四档语义</caption>
 *     <tr><th>档位</th><th>SpawnCursion 的叫法</th><th>「清完」的判据</th></tr>
 *     <tr><td>{@link #KILL}</td><td>{@code kill}（待击杀）</td>
 *         <td>由这一行生成的怪<b>被击杀数</b>达到 {@code kill_amt}</td></tr>
 *     <tr><td>{@link #CREATE}</td><td>{@code create}（待生成）</td>
 *         <td>这一行<b>累计生成数</b>达到 {@code create_amt}</td></tr>
 *     <tr><td>{@link #LIMIT}</td><td>{@code limit}（待处理）</td>
 *         <td>在场数低于 {@code existing_amt} 才继续补，直到<b>击杀数</b>达到 {@code kill_amt}</td></tr>
 *     <tr><td>{@link #ONLY_SPAWN}</td><td>{@code delay}（仅生成）</td>
 *         <td><b>永远算达标</b>：它只负责刷怪，不参与相位推进（战斗结束不看它）</td></tr>
 * </table>
 *
 * <p><b>为什么「仅生成」要单独一档而不是配置里留空</b>：留下空档会让「没配」与「故意不参与」
 * 无法区分 —— 前者应该报错、后者是合法玩法（炮灰/氛围怪）。同 {@code PixelUnit} 里
 * 「出手形式覆盖」用 {@code -1} 而不是 {@code 0} 当哨兵是同一个理由。</p>
 */
public enum SpawnStrategy {

    /** 待击杀：杀够 {@code killAmt} 只才算清完。 */
    KILL("待击杀"),

    /** 待生成：生成够 {@code createAmt} 只就算清完（不看死活）。 */
    CREATE("待生成"),

    /** 待处理：在场数 < {@code existingAmt} 时补怪，直到击杀数达到 {@code killAmt}。 */
    LIMIT("待处理"),

    /** 仅生成：只刷怪，不参与相位推进（永远算达标）。 */
    ONLY_SPAWN("仅生成");

    /** 界面/报告里的中文名（设计口径的叫法，直接沿用 SpawnCursion 的翻译）。 */
    public final String display;

    SpawnStrategy(String display) {
        this.display = display;
    }

    /** 这一档是否需要 {@code killAmt}。 */
    public boolean usesKill() {
        return this == KILL || this == LIMIT;
    }

    /** 这一档是否需要 {@code createAmt}。 */
    public boolean usesCreate() {
        return this == CREATE;
    }

    /** 这一档是否需要 {@code existingAmt}。 */
    public boolean usesExisting() {
        return this == LIMIT;
    }

    /**
     * 按名字解析（大小写不敏感）；认不出来返回 {@code null}。
     *
     * <p>存档里的这一列是<b>外部输入</b>（老存档、手改的 NBT、将来数据包），
     * 所以一律「认不出来就降级」，绝不抛异常 —— 与 {@code BranchRegistry.byKey} 同一个口径。</p>
     */
    @Nullable
    public static SpawnStrategy byName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String want = raw.trim().toUpperCase(Locale.ROOT);
        for (SpawnStrategy s : values()) {
            if (s.name().equals(want)) {
                return s;
            }
        }
        return null;
    }
}
