package com.guardianprotocol.data;

/**
 * 棋子朝向（东南西北四个正方向）。
 *
 * <h3>世界方向约定（按 Minecraft 实际天象标定，不要靠记忆猜）</h3>
 * <p>Minecraft 的太阳从 <b>+X</b> 方向升起（天空中的 {@code celestialAngle} 在 +X 处为 0），
 * 正午时太阳位于 <b>+Z</b> 方向。所以：</p>
 * <ul>
 *     <li><b>+X = 东</b>，<b>-X = 西</b></li>
 *     <li><b>+Z = 南</b>，<b>-Z = 北</b></li>
 * </ul>
 * <p>注意这与「看地图时上北下南」一致，但与 {@code getYRot()} 的 yaw 角不是一回事 ——
 * 本类只暴露<b>世界方向的偏移量</b>，需要角度时才由 {@link #yaw()} 换算，
 * 避免各处各写一遍三角函数。</p>
 *
 * <h3>为什么需要它</h3>
 * <p>攻击范围是一组相对格子 {@code (x, z)}（见 {@link AttackRange}），
 * 其中 {@code z} 轴的定义是「沿棋子朝向的前方为正」。所以同一个范围形状，
 * 棋子朝向不同，落到世界里的实际格子也不同。本类提供唯一的变换入口
 * {@link #toWorldOffset(int, int)}，避免「索敌用一套、阻挡用另一套」这种不一致。</p>
 *
 * <h3>枚举顺序即旋转顺序</h3>
 * <p>{@code EAST → SOUTH → WEST → NORTH → EAST} 是<b>顺时针</b>
 * （与 {@code +} 按钮语义一致），旋转实现直接依赖这个顺序，改动顺序会改变旋转方向。</p>
 */
public enum PieceFacing {

    /** 东：+X。方案的默认初始朝向。 */
    EAST("东", 1, 0),
    /** 南：+Z。与旧版本硬编码的「正前方 = +Z」一致。 */
    SOUTH("南", 0, 1),
    /** 西：-X。 */
    WEST("西", -1, 0),
    /** 北：-Z。 */
    NORTH("北", 0, -1);

    private final String displayName;
    private final int dx;
    private final int dz;

    PieceFacing(String displayName, int dx, int dz) {
        this.displayName = displayName;
        this.dx = dx;
        this.dz = dz;
    }

    /** 中文方向名（用于界面与命名牌）。 */
    public String displayName() {
        return this.displayName;
    }

    /** 前方在世界 X 轴上的单位偏移（-1 / 0 / 1）。 */
    public int dx() {
        return this.dx;
    }

    /** 前方在世界 Z 轴上的单位偏移（-1 / 0 / 1）。 */
    public int dz() {
        return this.dz;
    }

    /** 该朝向对应的 Minecraft yaw 角（度）。仅用于需要角度的场合，例如让实体真的转过去。 */
    public float yaw() {
        return switch (this) {
            case EAST -> -90.0F;
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
        };
    }

    /**
     * 顺时针旋转 {@code steps} 个 90°；{@code steps} 为负表示逆时针。
     *
     * <p>实现要点：Java 的 {@code %} 对负数返回负值，所以要再加一次长度取模
     * （{@code (i + n) % n}），否则「从东逆时针一下」会算成 -1 直接数组越界。</p>
     */
    public PieceFacing rotated(int steps) {
        PieceFacing[] all = values();
        int n = all.length;
        // 先用两步取模把 steps 收敛到 [0, n)，再叠加自身序号
        int index = ((this.ordinal() + (steps % n)) % n + n) % n;
        return all[index];
    }

    /** 顺时针 90°（界面上的 {@code +} 按钮）。 */
    public PieceFacing clockwise() {
        return this.rotated(1);
    }

    /** 逆时针 90°（界面上的 {@code -} 按钮）。 */
    public PieceFacing counterClockwise() {
        return this.rotated(-1);
    }

    /**
     * 把「相对棋子」的攻击范围格子 {@code (x, z)} 变换成世界方块偏移。
     *
     * <h3>★ 注意 {@code z} 的原点：{@code z = -1} 才是自身格（踩过的大坑）</h3>
     * <p>{@link AttackRange} 的约定是 <b>{@code z = -1} = 自己所在格、{@code z = 0} = 正前方一格、
     * {@code z = -2} = 身后一格</b>。所以「沿朝向往前走了几格」是 <b>{@code z + 1}</b>，不是 {@code z}。</p>
     *
     * <p>早先这里漏了那个 {@code +1}，等价于把自身格当成了 {@code z = 0}，
     * 于是**整个攻击范围朝身后偏了一格**：近战棋子打的是自己背后那一格，
     * 预览画出来的格子也整体偏一格、看起来左右不对称。更糟的是
     * 生成链脚本 里的断言按同一个错模型写的期望值，
     * 于是断言"通过"，问题一直藏着 —— 教训见 踩坑记录：
     * <b>期望值不能按被测代码的模型来写，要用「自身格必须落在脚下」这种语义句来钉。</b></p>
     *
     * <h3>方向映射</h3>
     * <p>因为「右手方向」恰好是「前方」顺时针转 90°，所以单位向量的映射正好是：</p>
     * <pre>
     * 右手方向 → (dx, dz) 顺时针 90°  = (-dz, dx)
     * 前方     → (dx, dz) 本身         = ( dx, dz)
     *
     * 令 f = z + 1（沿朝向的前进格数），则：
     * 世界偏移 X = x * (-dz) + f * dx
     * 世界偏移 Z = x * ( dx) + f * dz
     * </pre>
     *
     * <p>逐朝向验算（以「自身格」(0,-1)、「正前格」(0,0)、「右手格」(1,-1) 为例）：</p>
     * <ul>
     *     <li>东(dx=1,dz=0)：自身→(0,0)、正前→(1,0) 即 +X 东 ✓；右手→(0,1) 即 +Z 南 ✓</li>
     *     <li>南(dx=0,dz=1)：自身→(0,0)、正前→(0,1) 即 +Z 南 ✓；右手→(-1,0) 即 -X 西 ✓</li>
     *     <li>西(dx=-1,dz=0)：自身→(0,0)、正前→(-1,0) 即 -X 西 ✓；右手→(0,-1) 即 -Z 北 ✓</li>
     *     <li>北(dx=0,dz=-1)：自身→(0,0)、正前→(0,-1) 即 -Z 北 ✓；右手→(1,0) 即 +X 东 ✓</li>
     * </ul>
     *
     * @return 长度 2 的数组 {@code [worldDx, worldDz]}
     */
    public int[] toWorldOffset(int x, int z) {
        // ★ z=-1 是自身格 ⇒ 沿朝向的前进格数是 z+1。漏掉这个 +1 就会整体偏一格。
        int forward = z + 1;
        return new int[]{
                x * (-this.dz) + forward * this.dx,
                x * this.dx + forward * this.dz
        };
    }

    /**
     * 由玩家（或任意生物）的 yaw 角取最近的四个正方向。
     *
     * <p>用于「按放置时玩家所面向的方向设置朝向」那个方案。因为玩家能朝任意角度，
     * 这里把它吸附到最近的 90° 档位：</p>
     * <pre>
     * yaw 0    → 南(+Z)      yaw 90  → 西(-X)
     * yaw 180  → 北(-Z)      yaw -90 → 东(+X)
     * </pre>
     *
     * <p>纯 Java 实现（不引用 {@code Mth.wrapDegrees}）：一来这里只需要「吸附到 90° 整数倍」，
     * 二来保持本类是纯净的数据类，可以脱离 Minecraft 环境单独验证。</p>
     */
    public static PieceFacing fromYaw(float yaw) {
        // ① 把 yaw 归一到 [0, 360)：Java 的 % 对负数返回负值，所以要再 +360 兜一次
        float normalized = yaw % 360.0F;
        if (normalized < 0.0F) {
            normalized += 360.0F;
        }
        // ② 每 90° 一档四舍五入到 [0, 4)，4 视为 0（正好落在边界上归到「南」）
        int quarter = (int) ((normalized + 45.0F) / 90.0F) & 3;
        return switch (quarter) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }

    /** 按序号取（存档/同步用）；越界返回 null，由调用方决定兜底值。 */
    public static PieceFacing byOrdinal(int ordinal) {
        PieceFacing[] all = values();
        if (ordinal < 0 || ordinal >= all.length) {
            return null;
        }
        return all[ordinal];
    }

    /** 按枚举名取（存档用）；无法识别返回 null。 */
    public static PieceFacing byName(String name) {
        for (PieceFacing f : values()) {
            if (f.name().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }
}
