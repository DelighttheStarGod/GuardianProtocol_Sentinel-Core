package com.guardianprotocol.block;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 保护目标的嘲讽半径档位。
 *
 * <p><b>为什么做成离散档位而不是自由数值</b>：</p>
 * <ul>
 *     <li>半径要能让客户端知道（UI 要显示当前值、迷你地图要画圈）。若做成方块实体里的
 *         自由 int，就得自己写网络包同步。做成<b>方块状态属性</b>后，原版的方块更新
 *         免费帮我们同步到客户端，一行网络代码都不用写。</li>
 *     <li>方块状态属性只支持有限个值，所以半径按 8 格一档离散化 —— 对「嘲讽范围」
 *         这种手感参数来说，8 格档位完全够用。
 *         <b>顺带的好处</b>：档位是服务端权威的枚举序号，客户端没法伪造成「半径改成 999」。</li>
 * </ul>
 *
 * <p><b>订正（2026-10）</b>：这里原本写着「开发环境拿不到 {@code SimpleChannel}，所以只能这么做」。
 * 那句话<b>是错的</b> —— 复核方式：{@code jar tf forge-1.20.1-47.4.23-universal.jar} 里明确有
 * {@code net/minecraftforge/network/simple/SimpleChannel.class} 与
 * {@code net/minecraftforge/network/NetworkRegistry$ChannelBuilder.class}
 * （当初是搜错了 jar）。选方块状态是**因为它更省事**，不是因为做不到；新代码需要网络时
 * 可以正常用 SimpleChannel（见 踩坑记录的订正）。</p>
 *
 * <p>档位顺序即枚举声明顺序，{@link #radius()} 返回实际半径（格）。</p>
 */
public enum TauntRadius implements StringRepresentable {

    R16(16),
    R24(24),
    R32(32),
    R40(40),
    R48(48),
    R56(56),
    R64(64),
    R72(72),
    R80(80),
    R88(88),
    R96(96),
    R104(104),
    R112(112),
    R120(120),
    R128(128),
    R160(160);

    /** 方块状态属性名：guardian_protocol:protect_target 的 "taunt_radius"。 */
    public static final EnumProperty<TauntRadius> PROPERTY =
            EnumProperty.create("taunt_radius", TauntRadius.class);

    /** 默认档位：原先配置默认 32，实测反馈「有点小」，所以抬到 48。 */
    public static final TauntRadius DEFAULT = R48;

    /**
     * 方块状态里烘死的默认档位。
     *
     * <p><b>为什么不直接用配置值</b>：方块对象是在 {@code RegisterEvent} 期间构造的，
     * 而 Forge 的配置是在那之后才注册/加载。在方块构造器里读配置会拿到未初始化的 spec
     * 并抛异常，异常又被事件系统吞掉，最终表现为「方块注册成了 null」这种极难定位的现象。</p>
     *
     * <p>所以默认值用一个编译期常量；配置里的 {@code tauntRadius} 改由
     * {@code GuardianProtocol.commonSetup} 在配置加载完成后套用到已放置的目标上。</p>
     */
    public static final TauntRadius HARDCODED_DEFAULT = R48;

    /**
     * 从配置的默认半径推出最接近的档位。
     *
     * <p>配置（{@code config/guardian_protocol-common.toml} 的 {@code tauntRadius}）
     * 仍然生效，但会被量化到最近的档位 —— 因为半径存在方块状态里，只能是离散值。
     * 这样「改配置」与「右键改单个方块」两条路都能用。</p>
     */
    public static TauntRadius fromConfigRadius(double configured) {
        TauntRadius best = DEFAULT;
        double bestDiff = Double.MAX_VALUE;
        for (TauntRadius r : values()) {
            double diff = Math.abs(r.radius - configured);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = r;
            }
        }
        return best;
    }

    private final int radius;

    TauntRadius(int radius) {
        this.radius = radius;
    }

    /** 实际嘲讽半径（格）。 */
    public int radius() {
        return radius;
    }

    /** 下一档（到顶后停在最大档）。 */
    public TauntRadius next() {
        TauntRadius[] v = values();
        return v[Math.min(ordinal() + 1, v.length - 1)];
    }

    /** 上一档（到底后停在最小档）。 */
    public TauntRadius previous() {
        TauntRadius[] v = values();
        return v[Math.max(ordinal() - 1, 0)];
    }

    @Override
    public String getSerializedName() {
        return "r" + radius;
    }

    /** 便于调试与 UI 显示。 */
    public String display() {
        return radius + " 格";
    }
}
