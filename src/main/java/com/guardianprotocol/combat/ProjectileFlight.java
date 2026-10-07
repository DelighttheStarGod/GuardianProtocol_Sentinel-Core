package com.guardianprotocol.combat;

import com.guardianprotocol.data.PieceFacing;

/**
 * 投掷物的弹道（<b>纯数据，不引用任何 Minecraft 类</b>）。
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>与 {@link BlockGeometry} 同一个理由：弹道是纯算术（出生点、追踪步长、命中半径、
 * 拖尾取样），写在实体里就只能跑起游戏才能验。抽到这里之后，
 * 离线核对工具 可以脱离 Minecraft 把
 * 「朝哪个方向飞、每 tick 走多远、什么时候算命中」全算一遍并断言。</p>
 *
 * <h3>口径（设计确定）</h3>
 * <ul>
 *     <li><b>无视重力</b>：每 tick 的速度向量完全由「朝目标」决定，不叠加任何 y 方向的下坠
 *         —— 所以这里看不到重力的项，<b>不是漏写</b>；</li>
 *     <li><b>追踪</b>：每 tick 重新朝目标当前位置取方向，因此目标移动时弹道会拐弯；</li>
 *     <li><b>命中即结算</b>：剩余距离 ≤ {@link #HIT_RADIUS} 判命中；单 tick 步长被夹到
 *         「不超过剩余距离」，所以弹体不会跨过目标（穿模/穿人）。</li>
 * </ul>
 *
 * <h3>坐标约定</h3>
 * <p>全部是<b>世界</b>坐标（+X 东、+Z 南、+Y 上），与 {@link PieceFacing} 一致。
 * 本类不返回 Minecraft 的 {@code Vec3}，一律用 {@code double[]}：
 * 保持纯净才能被离线脚本直接调用（同 {@link BlockGeometry}）。</p>
 */
public final class ProjectileFlight {

    /** 飞行速度（格 / tick）。1.2 ⇒ 约 24 格/秒，肉眼能看见过程但不会慢到「打不中」。 */
    public static final double SPEED_PER_TICK = 1.2D;

    /**
     * 命中判定半径（格）。
     *
     * <p>取 0.5：原版生物碰撞箱半宽大多在 0.3~0.5 之间，0.5 能保证「贴上去就算命中」，
     * 又不至于在本格内就打到自己身边的另一个目标。</p>
     */
    public static final double HIT_RADIUS = 0.5D;

    /** 弹体最长存活 tick 数（兜底：目标一直追不到时自然消散，不会永久留在世界上）。 */
    public static final int LIFETIME_TICKS = 100;

    /** 出生点沿棋子朝向向前推的距离（格）：避免弹体一出生就盖在棋子自己身上。 */
    public static final double SPAWN_FORWARD = 0.5D;

    /** 出生点相对棋子脚底的高度（格）。 */
    public static final double SPAWN_HEIGHT = 1.2D;

    /**
     * 瞄准点占目标高度的比例。
     *
     * <p>0.6 ≈ 胸口位置：瞄脚底会让弹体贴着地面飞，瞄头顶则会在目标蹲下/跳起时显得偏。</p>
     */
    public static final double AIM_HEIGHT_RATIO = 0.6D;

    private ProjectileFlight() {
    }

    /**
     * 出生点：棋子所在位置 + <b>朝向</b>正前方 {@link #SPAWN_FORWARD} 格，高度抬
     * {@link #SPAWN_HEIGHT} 格。
     *
     * @param unitX/unitY/unitZ 棋子的世界坐标（脚底）
     * @param facing            棋子朝向（不能为 null）
     * @return {@code [x, y, z]}
     */
    public static double[] spawnPoint(double unitX, double unitY, double unitZ, PieceFacing facing) {
        if (facing == null) {
            throw new IllegalArgumentException("facing 不能为 null");
        }
        return new double[]{
                unitX + facing.dx() * SPAWN_FORWARD,
                unitY + SPAWN_HEIGHT,
                unitZ + facing.dz() * SPAWN_FORWARD};
    }

    /**
     * 瞄准点：目标脚底往上 {@link #AIM_HEIGHT_RATIO} × 目标高度。
     *
     * @param targetHeight 目标碰撞箱高度（格）
     * @return {@code [x, y, z]}
     */
    public static double[] aimPoint(double targetX, double targetY, double targetZ, double targetHeight) {
        return new double[]{targetX, targetY + Math.max(0.0D, targetHeight) * AIM_HEIGHT_RATIO, targetZ};
    }

    /**
     * 这一 tick 的位移向量：朝目标方向、步长 = {@code min(speed, 剩余距离)}。
     *
     * <p><b>为什么把步长夹到剩余距离</b>：不夹的话，最后一步会越过目标中心，
     * 而 {@link #reached} 是在移动<b>之前</b>判的 —— 表现就是「穿过去了却没打中」
     * （步长 1.2 &gt; 命中半径 0.5 时必然发生）。夹住之后弹体最终会精确落在目标位置上，
     * 距离单调减小，命中判定不可能被跳过。</p>
     *
     * @param toX/toY/toZ 目标位置
     * @param speed       期望步长（格/tick，&gt; 0）
     * @return {@code [dx, dy, dz]}
     */
    public static double[] step(double fromX, double fromY, double fromZ,
                                double toX, double toY, double toZ, double speed) {
        if (!(speed > 0.0D)) {
            throw new IllegalArgumentException("弹道速度必须 > 0，收到 " + speed);
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= 1.0E-6D) {
            return new double[]{0.0D, 0.0D, 0.0D};
        }
        double k = Math.min(speed, d) / d;
        return new double[]{dx * k, dy * k, dz * k};
    }

    /** 剩余向量是否已在命中半径内。 */
    public static boolean reached(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= HIT_RADIUS * HIT_RADIUS;
    }

    /** 剩余距离（格）。 */
    public static double distance(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 拖尾取样：在 {@code from → to} 之间等距取 {@code count} 个点（含起点、不含终点）。
     *
     * <p>用途是<b>粒子拖尾</b>：一 tick 位移 1.2 格时只画一个点会变成断续的虚线，
     * 按位移取样才能连成一条轨迹。返回 {@code count × 3} 的数组。</p>
     *
     * @param count 取样点数（&lt; 1 视为 1）
     */
    public static double[][] trail(double[] from, double[] to, int count) {
        if (from == null || from.length < 3 || to == null || to.length < 3) {
            throw new IllegalArgumentException("from / to 必须是长度至少 3 的坐标数组");
        }
        int n = Math.max(1, count);
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;      // 不含终点：终点属于下一 tick 的起点
            out[i][0] = from[0] + (to[0] - from[0]) * t;
            out[i][1] = from[1] + (to[1] - from[1]) * t;
            out[i][2] = from[2] + (to[2] - from[2]) * t;
        }
        return out;
    }

    /**
     * 一 tick 位移按拖尾取样数换算：每 0.3 格画一个点，至少 1 个。
     *
     * <p>写成方法而不是常量：取样数取决于<b>实际位移</b>（起步那一下可能只走 0.1 格），
     * 固定值会让慢速段变稀、快速段变密。</p>
     */
    public static int trailSamplesFor(double distance) {
        return Math.max(1, (int) Math.ceil(Math.max(0.0D, distance) / 0.3D));
    }
}
