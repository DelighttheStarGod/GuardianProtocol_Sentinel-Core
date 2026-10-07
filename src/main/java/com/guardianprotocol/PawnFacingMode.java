package com.guardianprotocol;

/**
 * 棋子「布设朝向」方案。
 *
 * <p>对应配置 {@code pawn_facing.mode}，两个方案互斥：</p>
 *
 * <h3>{@link #FIXED_EAST}（方案1：统一初始朝向 + 界面旋转）</h3>
 * <ul>
 *     <li>所有棋子放下时一律朝<b>正东</b>（+X），与玩家站位无关；</li>
 *     <li>右键棋子打开朝向界面，{@code +} 顺时针、{@code -} 逆时针，每次 90°。</li>
 * </ul>
 *
 * <h3>{@link #FOLLOW_PLAYER}（方案2：按放置时玩家朝向）</h3>
 * <ul>
 *     <li>放下瞬间取玩家的 yaw，吸附到最近的东南西北；</li>
 *     <li><b>不提供旋转界面</b> —— 朝向放下即定，避免与「按我当时的面向布置」这个意图打架。</li>
 * </ul>
 *
 * <p>无论哪个方案，<b>旋转后的朝向都会存档</b>，重新进游戏仍然保持。</p>
 */
public enum PawnFacingMode {

    /** 方案1：统一朝正东，允许界面旋转。 */
    FIXED_EAST("方案1：统一朝正东 + 界面旋转"),

    /** 方案2：跟随放置时玩家朝向，不允许旋转。 */
    FOLLOW_PLAYER("方案2：跟随放置时玩家朝向");

    private final String displayName;

    PawnFacingMode(String displayName) {
        this.displayName = displayName;
    }

    /** 中文说明，用于日志与 设计说明。 */
    public String displayName() {
        return this.displayName;
    }

    /** 该方案下是否允许右键棋子旋转朝向。 */
    public boolean allowsManualRotation() {
        return this == FIXED_EAST;
    }
}
