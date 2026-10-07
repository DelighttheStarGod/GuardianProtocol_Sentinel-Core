package com.guardianprotocol.menu;

/**
 * 出怪点界面的<b>布局表 + 候选下拉框几何</b>（纯算术，不含任何客户端类型）。
 *
 * <h3>为什么这些数字不写在 {@code SpawnPointScreen} 里</h3>
 * <p>它们全是算术（谁挨着谁、谁盖住谁），却一直住在 {@code @OnlyIn(Dist.CLIENT)} 的屏幕类里 ——
 * 专用服务器不加载那个类，自检也就断言不到它。结果就是「界面重叠」这类问题只能靠
 * 「玩家截图 → 开发者读代码」来回猜（连续反馈了两轮，第一轮我猜错了根因）。
 * 把布局与几何挪到这个<b>双端都加载</b>的类里之后，自检可以直接验
 * 「两列文字会不会撞」「哪几个控件被下拉框盖住」，不必等截图。坐标一律是<b>面板相对</b>，
 * 绝对屏幕坐标 = 面板相对 + (leftPos, topPos)。</p>
 */
public final class SpawnPointLayout {

    private SpawnPointLayout() {
    }

    // ---- 面板 ----
    public static final int PANEL_WIDTH = 300;
    public static final int PANEL_HEIGHT = 266;

    // ---- 纵向锚点（面板相对）----
    public static final int Y_FIRST_ENTRY = 30;
    public static final int ENTRY_BLOCK = 46;
    /** 行内：输入框 / 应用 / 删除 这一排的纵向偏移。 */
    public static final int ROW_ID_DY = 0;
    /** 行内：「数量 − 值 +」这一排的纵向偏移。 */
    public static final int ROW_STEP_DY = 27;

    // ---- 横向锚点（面板相对）----
    public static final int ID_X = 10;
    public static final int ID_WIDTH = 158;
    public static final int ID_HEIGHT = 16;
    public static final int STEP_Y_HEIGHT = 16;

    // ---- 候选下拉框 ----
    /** 候选行高（字号 9，留 3px 间隔）。 */
    public static final int LINE_H = 12;
    /** id 列与中文名列之间至少要留的空隙。 */
    public static final int GAP = 8;
    /** id 列的最小像素宽（≈ {@code minecraft:zombie} 的宽度）：名字再长也不许把 id 挤没。 */
    public static final int MIN_ID_WIDTH = 96;
    /** 下拉框最多画几行。 */
    public static final int MAX_LINES = 6;
    /** 边框厚度，也算进「盖住」的范围 —— 否则边框那一像素会漏出底下的东西。 */
    public static final int BORDER = 1;
    /** 候选行文字相对行顶的偏移。 */
    public static final int TEXT_DY = 2;
    /** id 文字相对下拉框左边的偏移。 */
    public static final int ID_DY_X = 3;

    /** 下拉框左边缘。 */
    public static int dropdownX() {
        return ID_X;
    }

    /** 下拉框上边缘：正好贴在那一行输入框的下边。 */
    public static int dropdownY(int row) {
        return Y_FIRST_ENTRY + row * ENTRY_BLOCK + ROW_ID_DY + ID_HEIGHT;
    }

    /** 下拉框宽度 = 面板内宽（窄了下场就是 id 与名字两列打架）。 */
    public static int dropdownW() {
        return PANEL_WIDTH - 2 * ID_X;
    }

    /**
     * 实际画几行：受候选条数、{@link #MAX_LINES}、以及<b>面板下沿</b>三者夹取。
     *
     * <p>最后一项保证下拉框永远不越出面板 —— 越出去的部分画在面板外的空白上，
     * 既难看又会被别的控件盖住。</p>
     */
    public static int dropdownLines(int row, int candidates) {
        if (candidates <= 0) {
            return 0;
        }
        int room = (PANEL_HEIGHT - 2 - dropdownY(row)) / LINE_H;
        return Math.max(0, Math.min(Math.min(candidates, MAX_LINES), room));
    }

    /** 下拉框下边缘（不含边框）。 */
    public static int dropdownBottom(int row, int lines) {
        return dropdownY(row) + lines * LINE_H;
    }

    /** 中文名列的 x：右对齐贴内边框，与名字多长无关，所以永远不会把 id 列挤跑。 */
    public static int nameX(int x0, int w, int nameWidth) {
        return x0 + w - 2 - nameWidth;
    }

    /**
     * 中文名最多占这么宽 —— 再多就只能靠吃 id 的宽度了。
     *
     * <p>调用方<b>必须先按这个宽度截断名字</b>再算 {@link #nameX}/{@link #idMax}，
     * 「两列不重叠」才是无条件成立的：截断后名字宽 ≤ nameMax ⇒ idMax 取到的就是
     * 「名字左边 − 空隙」而不是兜底的 {@link #MIN_ID_WIDTH}。谁不截断，谁就在名字特别长的那
     * 几行上重新制造叠字（踩坑记录）。</p>
     */
    public static int nameMax(int w) {
        return w - 2 - GAP - ID_DY_X - MIN_ID_WIDTH;
    }

    /** id 列可用像素宽：给名字左边留出 {@link #GAP}，且不低于 {@link #MIN_ID_WIDTH}（超出就截断）。 */
    public static int idMax(int x0, int w, int nameWidth) {
        return Math.max(MIN_ID_WIDTH, nameX(x0, w, nameWidth) - GAP - (x0 + ID_DY_X));
    }

    /** 面板相对的一段（{@code renderLabels} 的文字）是否落进下拉框 —— 纵向判断即可，文字横向铺满内宽。 */
    public static boolean coveredLine(int y, int h, int row, int lines) {
        if (lines <= 0) {
            return false;
        }
        return y + h > paintedTop(row) && y < dropdownBottom(row, lines) + BORDER;
    }

    /**
     * 下拉框<b>实际画出来</b>的上边界。
     *
     * <p>★ 就是 {@link #dropdownY}，<b>不向上再扩一像素</b>：上边框是画在黑底<span>之内</span>的
     * （见 {@code SpawnPointScreen#renderSuggestions}），不是画在黑底之上。这一像素很要命 ——
     * 焦点那一行的输入框底边正好压在 {@code dropdownY} 上，如果上边框画在它上面，
     * 「这一行有控件与下拉框相交」就会成立，于是整行的「应用 / 删除」按钮被判为「被盖住」而藏掉，
     * 玩家打完字就点不到「应用」了（为了藏掉 1 像素的边框，赔掉一整排按钮）。</p>
     */
    public static int paintedTop(int row) {
        return dropdownY(row);
    }

    /**
     * 被下拉框盖住的控件该不该藏。
     *
     * <p>★ 只有<b>一个</b>豁免：正在打字的那个输入框（藏了就丢焦点，键盘输入没人接）。
     * 其余一律藏 —— 包括同一个输入框所在那一行的按钮。</p>
     *
     * <p>这条规则单独抽出来、并由自检断言，是因为上一版把豁免写成了<b>整行</b>豁免
     * （{@code if (idBoxes[i].isFocused()) return;}）：玩家正在第 0 行打字，那一行 6 个
     * 「−/+」按钮就全部照旧绘制。它们的<b>灰盒子</b>落进 {@code guiTextured} 批次
     * （在黑底那块 {@code gui} 批次之前画完，于是被黑底盖住），而<b>文字</b>落进 {@code text}
     * 批次（整帧最后一批 → 浮在黑底之上），玩家看到的因此是一个没有底、只剩「− +」两个字的
     * 幽灵（设计口径 截图：候选行 axolotl 那一行莫名多出 − +）。</p>
     *
     * <p>换句话说：<b>「盖住」靠的是「不画」，而「不画」的粒度必须与被盖的东西一样细。</b></p>
     *
     * @param covered       这个控件/这一行是否被下拉框盖住
     * @param isFocusedBox  它是不是「正在打字的那个输入框」
     */
    public static boolean shouldHide(boolean covered, boolean isFocusedBox) {
        return covered && !isFocusedBox;
    }

    /**
     * 面板相对的控件矩形是否被下拉框盖住（含边框那一像素）。
     *
     * <p>边界口径要抠死：贴着边框外侧的控件<b>不算</b>被盖（差一像素就会把没被挡住的按钮藏掉），
     * 只要有一像素落进边框以内就<b>算</b>（差一像素就会漏出底下的东西）。</p>
     */
    public static boolean coveredRect(int x, int y, int w, int h, int row, int lines) {
        if (lines <= 0) {
            return false;
        }
        int x0 = dropdownX() - BORDER;
        int x1 = dropdownX() + dropdownW() + BORDER;
        int y0 = paintedTop(row);
        int y1 = dropdownBottom(row, lines) + BORDER;
        return x < x1 && x + w > x0 && y < y1 && y + h > y0;
    }
}
