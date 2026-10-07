package com.guardianprotocol.client;

import com.guardianprotocol.block.SpawnPointBlockEntity;
import com.guardianprotocol.menu.SpawnPointLayout;
import com.guardianprotocol.menu.SpawnPointMenu;
import com.guardianprotocol.net.ModNetwork;
import com.guardianprotocol.net.SpawnPointEditPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 出怪组的配置界面：<b>每行一种怪，种类 / 数量 / 延迟 / 间隔各自独立</b>。
 *
 * <h3>布局（固定 4 行，永不重建控件）</h3>
 * <pre>
 *   ┌ 出怪点 ────────────────────────────────┐
 *   │ 提示：一组最多 4 种 …                    │
 *   │ [实体 id ............] [应用] [删除]     │  ← 第 0 行
 *   │   → 尸壳                                │
 *   │ 数 − 3 +   延 − 0 +   隔 − 0 +          │
 *   │ …（第 1~3 行同样结构，行数不足时整行隐藏）│
 *   │ [＋ 添加一种]      [启用:开][恢复默认][完成]│
 *   └────────────────────────────────────────┘
 * </pre>
 *
 * <h3>为什么固定行数而不是按需增删控件</h3>
 * <p>「条目数变了就重建控件」会带来一个经典麻烦：玩家正在输入框里打字时，
 * 服务端快照一到就把控件重建了，<b>输入内容与焦点一起丢</b>。固定 4 行后，
 * 增删条目只是「把某几行的 active/visible 换个值」，输入框实例始终存在，
 * 也就不存在丢焦点的问题。代价是最多 4 种（够用；要更多种类就再摆一个出怪点）。</p>
 *
 * <h3>数据从哪来</h3>
 * <p>只画 {@link ClientSpawnPointConfig} 里的<b>服务端快照</b>；还没收到快照时
 * 显示「读取中」并禁用编辑，而不是画客户端方块实体那份可能过期的值 ——
 * 后者正是上一版「服务端明明改了、界面却像被锁住」事故的根源。</p>
 */
@OnlyIn(Dist.CLIENT)
public class SpawnPointScreen extends AbstractContainerScreen<SpawnPointMenu> {

    // ---- 面板与行列锚点 ----
    // ★ 这些数字住在 SpawnPointLayout（双端都加载的纯算术类），这里只做别名：
    //   界面上的「谁挨着谁、谁盖住谁」必须能被自检断言，而不是只能等玩家截图（设计口径 两轮反馈）。
    private static final int PANEL_WIDTH = SpawnPointLayout.PANEL_WIDTH;
    private static final int PANEL_HEIGHT = SpawnPointLayout.PANEL_HEIGHT;

    /** 标题与提示的纵向位置（面板相对；renderLabels 同坐标系）。 */
    private static final int Y_TITLE = 6;
    private static final int Y_HINT = 18;
    private static final int Y_FIRST_ENTRY = SpawnPointLayout.Y_FIRST_ENTRY;
    private static final int ENTRY_BLOCK = SpawnPointLayout.ENTRY_BLOCK;
    private static final int Y_ADD = Y_FIRST_ENTRY + SpawnPointBlockEntity.MAX_ENTRIES * ENTRY_BLOCK;
    private static final int Y_ACTION = Y_ADD + 24;

    // ---- 条目行内的纵向偏移 ----
    private static final int ROW_ID_DY = SpawnPointLayout.ROW_ID_DY;
    private static final int ROW_INFO_DY = 18;
    private static final int ROW_STEP_DY = SpawnPointLayout.ROW_STEP_DY;

    // ---- 横向锚点 ----
    private static final int ID_X = SpawnPointLayout.ID_X;
    private static final int ID_WIDTH = SpawnPointLayout.ID_WIDTH;
    private static final int ID_HEIGHT = SpawnPointLayout.ID_HEIGHT;
    private static final int APPLY_X = ID_X + ID_WIDTH + 4;
    private static final int APPLY_WIDTH = 34;
    private static final int REMOVE_X = APPLY_X + APPLY_WIDTH + 4;
    private static final int REMOVE_WIDTH = 46;

    // 三组「标签 值 − +」。★ 2026-10 实测反馈「间隔分不清」：原来是
    // 「标签 − 值 +」——标签紧挨着**上一个**组的 + 按钮、值又被夹在两个按钮中间，
    // 于是三组连起来读成「数量 [−] 1 [+] 延迟 [−0 tick] [+] 间隔 …」，不知道哪个数属于哪个标签。
    // 现在改成「标签在前、值紧跟标签、两个按钮都放到值右边」，组与组之间留出明显间隔。
    private static final int STEP_Y_HEIGHT = SpawnPointLayout.STEP_Y_HEIGHT;
    private static final int STEP_BUTTON_WIDTH = 18;
    /** 每组内部：标签 +0、值居中 +34、− +52、+ +70（一组 88 宽，组起点 10/106/202 正好铺满 300 宽的面板）。 */
    private static final int[] STEP_LABEL_X = {10, 106, 202};
    private static final int[] STEP_VALUE_CENTER_X = {44, 140, 236};
    private static final int[] STEP_MINUS_X = {62, 158, 254};
    private static final int[] STEP_PLUS_X = {80, 176, 272};

    private static final int ADD_WIDTH = 80;
    private static final int ACTION_Y_HEIGHT = 18;
    private static final int ENABLED_X = 10;
    private static final int ENABLED_WIDTH = 96;
    private static final int RESET_X = 112;
    private static final int RESET_WIDTH = 66;
    private static final int DONE_X = 184;
    private static final int DONE_WIDTH = 46;

    private static final int COLOR_LABEL = 0x8CD8F0;
    private static final int COLOR_VALUE = 0xFFFFFF;
    private static final int COLOR_OK = 0x7CE07C;
    private static final int COLOR_ERROR = 0xE07C7C;
    private static final int COLOR_HINT = 0x9A9A9A;
    private static final int COLOR_ROW_INDEX = 0xF0D080;

    /** 顶部那条提示的语言键（截断与悬停提示共用一处，别处不许再拼字符串）。 */
    private static final String HINT_KEY = "gui.guardian_protocol.spawn_point.hint_group";

    private static final int ROWS = SpawnPointBlockEntity.MAX_ENTRIES;

    // ---- 控件（全量创建，按行数切换 active/visible）----
    private final EditBox[] idBoxes = new EditBox[ROWS];
    private final Button[] applyButtons = new Button[ROWS];
    private final Button[] removeButtons = new Button[ROWS];
    private final Button[] countDown = new Button[ROWS];
    private final Button[] countUp = new Button[ROWS];
    private final Button[] delayDown = new Button[ROWS];
    private final Button[] delayUp = new Button[ROWS];
    private final Button[] intervalDown = new Button[ROWS];
    private final Button[] intervalUp = new Button[ROWS];
    private Button addButton;
    private Button enabledButton;
    private Button resetButton;
    private Button doneButton;

    /** 玩家正在编辑的文本（按行）；「应用」时用它，服务端回推后再对齐。 */
    private final String[] drafts = new String[ROWS];
    /** 每行的即时校验结论（缓存：只在文本变化时重算，试造实体是有成本的）。 */
    private final Component[] rowInfo = new Component[ROWS];
    private final boolean[] rowInfoOk = new boolean[ROWS];
    private final String[] rowInfoFor = new String[ROWS];

    // ---- 关键字候选（设计口径：支持中文输入 + 关键字下拉选择）----
    /** 候选最多几条（再多会盖住下一行）。 */
    private static final int MAX_SUGGEST = SpawnPointLayout.MAX_LINES;
    /**
     * 界面构建号：只回答一个问题 —— <b>玩家实机看到的到底是哪一版 jar</b>。
     *
     * <p>开发环境没有客户端，界面改动一律只能靠「玩家截图 → 我读代码」闭环；上一轮把下拉框改成
     * 纯黑不透明 + 画在整帧最后，实机截图里底下的按钮/数字依旧清清楚楚，而我无法判断
     * 那是「旧 jar 还在跑」还是「绘制顺序真的不生效」。画个号就能一眼分辨，
     * 争议结束时删掉即可。改动界面渲染时<b>必须</b>自增。</p>
     */
    private static final int UI_BUILD = 5;
    /** 每行输入框当前的候选；只有**获得焦点**那一行的下拉会画出来。 */
    private final List<List<net.minecraft.world.entity.EntityType<?>>> suggestions = new java.util.ArrayList<>();
    /** 候选行的可点区域（x0,y0,x1,y1,行号,候选下标）：render 时重建、mouseClicked 时读。 */
    private final List<int[]> suggestRects = new java.util.ArrayList<>();

    /** 当前显示用的快照；null = 还没收到服务端的（界面显示「读取中」）。 */
    @Nullable
    private SpawnPointBlockEntity.Snapshot snapshot;
    /** 上次构建控件时的条目数，用来判断「行数变了」。 */
    private int builtRows = -1;

    public SpawnPointScreen(SpawnPointMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
        this.inventoryLabelY = -10000;
        for (int i = 0; i < ROWS; i++) {
            this.drafts[i] = "";
            this.rowInfo[i] = Component.empty();
            this.rowInfoFor[i] = "\u0000";
        }
    }

    @Override
    protected void init() {
        super.init();
        int left = this.leftPos;
        int top = this.topPos;

        for (int i = 0; i < ROWS; i++) {
            final int row = i;
            int baseY = top + Y_FIRST_ENTRY + i * ENTRY_BLOCK;

            EditBox box = new EditBox(this.font, left + ID_X, baseY + ROW_ID_DY, ID_WIDTH, ID_HEIGHT,
                    Component.translatable("gui.guardian_protocol.spawn_point.entry_label", i + 1));
            box.setMaxLength(SpawnPointBlockEntity.MAX_ENTITY_ID_LENGTH);
            box.setHint(Component.literal(SpawnPointBlockEntity.DEFAULT_ENTITY_ID));
            // 打字即存草稿：控件永不重建，但草稿要能扛住「服务端快照一到就刷新显示」
            box.setResponder(text -> {
                this.drafts[row] = text;
                this.updateSuggestions(row);
            });
            this.idBoxes[i] = this.addRenderableWidget(box);

            int stepY = baseY + ROW_STEP_DY;
            this.applyButtons[i] = this.addRenderableWidget(Button.builder(
                            Component.translatable("gui.guardian_protocol.spawn_point.apply"),
                            b -> this.send(SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, row, this.resolveTyped(row)))
                    .bounds(left + APPLY_X, baseY + ROW_ID_DY, APPLY_WIDTH, ID_HEIGHT).build());
            this.removeButtons[i] = this.addRenderableWidget(Button.builder(
                            Component.translatable("gui.guardian_protocol.spawn_point.remove"),
                            b -> this.send(SpawnPointBlockEntity.EditOp.REMOVE_ENTRY, row, ""))
                    .bounds(left + REMOVE_X, baseY + ROW_ID_DY, REMOVE_WIDTH, ID_HEIGHT).build());

            this.countDown[i] = this.stepButton(left + STEP_MINUS_X[0], stepY, "-",
                    SpawnPointBlockEntity.EditOp.ENTRY_COUNT_DOWN, row);
            this.countUp[i] = this.stepButton(left + STEP_PLUS_X[0], stepY, "+",
                    SpawnPointBlockEntity.EditOp.ENTRY_COUNT_UP, row);
            this.delayDown[i] = this.stepButton(left + STEP_MINUS_X[1], stepY, "-",
                    SpawnPointBlockEntity.EditOp.ENTRY_DELAY_DOWN, row);
            this.delayUp[i] = this.stepButton(left + STEP_PLUS_X[1], stepY, "+",
                    SpawnPointBlockEntity.EditOp.ENTRY_DELAY_UP, row);
            this.intervalDown[i] = this.stepButton(left + STEP_MINUS_X[2], stepY, "-",
                    SpawnPointBlockEntity.EditOp.ENTRY_INTERVAL_DOWN, row);
            this.intervalUp[i] = this.stepButton(left + STEP_PLUS_X[2], stepY, "+",
                    SpawnPointBlockEntity.EditOp.ENTRY_INTERVAL_UP, row);
        }

        this.addButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.spawn_point.add_entry"),
                        b -> this.send(SpawnPointBlockEntity.EditOp.ADD_ENTRY, -1, ""))
                .bounds(left + ID_X, top + Y_ADD, ADD_WIDTH, ACTION_Y_HEIGHT).build());

        this.enabledButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.spawn_point.enabled_on"),
                        b -> this.send(SpawnPointBlockEntity.EditOp.TOGGLE_ENABLED, -1, ""))
                .bounds(left + ENABLED_X, top + Y_ACTION, ENABLED_WIDTH, ACTION_Y_HEIGHT).build());
        this.resetButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.spawn_point.reset"),
                        b -> this.send(SpawnPointBlockEntity.EditOp.RESET, -1, ""))
                .bounds(left + RESET_X, top + Y_ACTION, RESET_WIDTH, ACTION_Y_HEIGHT).build());
        this.doneButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.spawn_point.close"),
                        b -> this.onClose())
                .bounds(left + DONE_X, top + Y_ACTION, DONE_WIDTH, ACTION_Y_HEIGHT).build());

        this.refreshSnapshot();
        this.applyVisibility();
    }

    /** 造一个「±」按钮。 */
    private Button stepButton(int x, int y, String label, SpawnPointBlockEntity.EditOp op, int row) {
        return this.addRenderableWidget(Button.builder(Component.literal(label),
                        b -> this.send(op, row, ""))
                .bounds(x, y, STEP_BUTTON_WIDTH, STEP_Y_HEIGHT).build());
    }

    /** 把动作发给服务端（唯一的写入通道）。 */
    private void send(SpawnPointBlockEntity.EditOp op, int index, String text) {
        ModNetwork.CHANNEL.sendToServer(new SpawnPointEditPacket(this.menu.pos(), op, index, text));
    }

    /**
     * 每帧同步：从客户端缓存取服务端快照。
     *
     * <p>★ 这里<b>不</b>读客户端方块实体：那份数据要靠原版包同步，曾经因为发的是空包
     * 而导致界面永远显示旧值（见 {@code SpawnPointSyncPacket} 的注释）。</p>
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        this.refreshSnapshot();
        this.applyVisibility();
        // EditBox 的光标闪烁靠 tick()；Screen 的默认 tick 不保证转发到子控件。
        for (EditBox box : this.idBoxes) {
            if (box != null) {
                box.tick();
            }
        }
    }

    /** 取快照并把它对齐到输入框（只在对齐不会打断打字时写）。 */
    private void refreshSnapshot() {
        this.snapshot = ClientSpawnPointConfig.get(this.menu.pos());
        if (this.snapshot == null) {
            return;
        }
        List<SpawnPointBlockEntity.Entry> entries = this.snapshot.entries();
        for (int i = 0; i < ROWS; i++) {
            if (i >= entries.size() || this.idBoxes[i] == null) {
                continue;
            }
            String authoritative = entries.get(i).entityId();
            // ★ 只在失焦时刷新：否则玩家打一个字就被服务端回显顶掉。
            if (!this.idBoxes[i].isFocused() && !authoritative.equals(this.idBoxes[i].getValue())) {
                this.idBoxes[i].setValue(authoritative);
                this.drafts[i] = authoritative;
            }
        }
    }

    /** 按当前行数切换控件可见性/可用性（不重建控件，所以不会丢焦点）。 */
    private void applyVisibility() {
        int rows = this.snapshot == null ? 0 : this.snapshot.entries().size();
        boolean editable = this.snapshot != null;
        for (int i = 0; i < ROWS; i++) {
            boolean on = i < rows;
            setRowVisible(i, on);
        }
        if (this.addButton != null) {
            this.addButton.active = editable && rows < ROWS;
            this.addButton.visible = editable;
        }
        if (this.enabledButton != null) {
            this.enabledButton.active = editable;
            this.enabledButton.setMessage(Component.translatable(editable && this.snapshot.enabled()
                    ? "gui.guardian_protocol.spawn_point.enabled_on"
                    : "gui.guardian_protocol.spawn_point.enabled_off"));
        }
        if (this.resetButton != null) {
            this.resetButton.active = editable;
        }
        this.builtRows = rows;
        // 行级可见性摆好之后，再让下拉框把盖住的那几行压掉（每 tick 重算，见 applyCoverSuppression）
        this.applyCoverSuppression();
    }

    private void setRowVisible(int i, boolean on) {
        if (this.idBoxes[i] != null) {
            this.idBoxes[i].visible = on;
            this.idBoxes[i].active = on;
            // 行被隐藏时把焦点交回屏幕，免得隐藏的输入框继续吃键盘
            if (!on && this.idBoxes[i].isFocused()) {
                this.setFocused(null);
            }
        }
        for (Button b : new Button[]{this.applyButtons[i], this.countDown[i], this.countUp[i],
                this.delayDown[i], this.delayUp[i], this.intervalDown[i], this.intervalUp[i]}) {
            if (b != null) {
                b.visible = on;
                b.active = on;
            }
        }
        if (this.removeButtons[i] != null) {
            boolean canRemove = on && this.snapshot != null && this.snapshot.entries().size() > 1;
            this.removeButtons[i].visible = on;
            this.removeButtons[i].active = canRemove;
        }
    }

    // ------------------------------------------------------------------
    // 下拉框遮挡：被盖住的控件/文字一律不画（设计口径「你管这叫修复了？」）
    // ------------------------------------------------------------------

    /**
     * 下拉框的几何（绝对屏幕坐标）。
     *
     * <p>宽度取<b>整个面板内宽</b>而不是输入框那一小段：候选行里有 id 有中文名，
     * 窄了下场就是两列文字打架。行数同时被「候选条数」和「面板下沿」夹住，
     * 保证下拉框永远不越出面板。</p>
     *
     * @return null = 当前没有下拉框
     */
    @Nullable
    private Dropdown dropdown() {
        int row = this.focusedRow();
        if (row < 0 || row >= this.suggestions.size()) {
            return null;
        }
        int lines = SpawnPointLayout.dropdownLines(row, this.suggestions.get(row).size());
        if (lines <= 0) {
            return null;
        }
        return new Dropdown(row,
                this.leftPos + SpawnPointLayout.dropdownX(),
                this.topPos + SpawnPointLayout.dropdownY(row),
                SpawnPointLayout.dropdownW(),
                lines);
    }

    /** 下拉框的实际屏幕几何；字段全部来自 {@link SpawnPointLayout}（见那里的说明）。 */
    private record Dropdown(int row, int x0, int y0, int w, int lines) {
        int bottom() {
            return this.y0 + this.lines * SpawnPointLayout.LINE_H;
        }
    }

    /**
     * 把「被下拉框盖住的那一行」整行藏掉（只改 {@code visible}，不动 {@code active}）。
     *
     * <h4>为什么是「藏」而不是「后画」</h4>
     * <p>上一版的思路是「下拉框画在整帧最后，后画的必然盖住先画的」。实机反馈：
     * 下拉框确实是纯黑不透明、也确实画在最后，但底下的按钮 / 数字 / 中文名照旧透出来，
     * 还和候选行叠成糊字。这条依赖在实机上不成立，而开发环境没有客户端、无法逐帧定位
     * （可能是别的模组改了 GUI 状态，也可能是深度测试没关）。那就<b>不再依赖绘制顺序</b>：
     * 被盖住的控件直接 {@code visible=false}，它们根本不参与渲染，任何顺序问题都不再成立。
     * 同一块地界里的文字同理 —— 见 {@link #renderLabels} 的跳过逻辑。</p>
     *
     * <p>每 tick（{@code containerTick}）与每帧（{@code render}）都从零重算，不做增量，
     * 所以「下拉框关掉后控件回不来」这种状态残留不可能发生。</p>
     */
    private void applyCoverSuppression() {
        Dropdown dd = this.dropdown();
        for (int i = 0; i < ROWS; i++) {
            if (this.idBoxes[i] == null) {
                continue;
            }
            this.setCovered(i, dd != null && this.rowCovered(i, dd));
        }
    }

    /** 这一行是否与下拉框相交（只要有一个控件相交就算整行被盖）。 */
    private boolean rowCovered(int i, Dropdown dd) {
        EditBox box = this.idBoxes[i];
        if (box != null && covered(box.getX(), box.getY(), box.getWidth(), box.getHeight(), dd)) {
            return true;
        }
        for (Button b : new Button[]{this.applyButtons[i], this.removeButtons[i], this.countDown[i],
                this.countUp[i], this.delayDown[i], this.delayUp[i], this.intervalDown[i],
                this.intervalUp[i]}) {
            if (b != null && covered(b.getX(), b.getY(), b.getWidth(), b.getHeight(), dd)) {
                return true;
            }
        }
        return false;
    }

    /** 控件（绝对屏幕矩形）是否被下拉框盖住 —— 算术在 {@link SpawnPointLayout#coveredRect}。 */
    private boolean covered(int x, int y, int w, int h, Dropdown dd) {
        return SpawnPointLayout.coveredRect(x - this.leftPos, y - this.topPos, w, h, dd.row(), dd.lines());
    }

    private void setCovered(int i, boolean covered) {
        if (!covered) {
            // 恢复：可见性交还给「这一行到底存不存在」（幂等，顺便把 active 一起摆正）
            int rows = this.snapshot == null ? 0 : this.snapshot.entries().size();
            this.setRowVisible(i, i < rows);
            return;
        }
        // ★★ 豁免必须精确到「那一个控件」，绝不能整行豁免。
        //   上一版这里是 `if (idBoxes[i].isFocused()) return;` —— 玩家正在第 0 行打字，
        //   于是**整行**跳过隐藏：那一行 6 个「−/+」按钮照旧绘制。它们的灰盒子落进
        //   `guiTextured` 批次（在黑底那块 `gui` 批次之前画完 → 被黑底盖住），而按钮的
        //   **文字**落进 `text` 批次（整帧最后一批 → 浮在黑底之上），于是玩家看到的正是
        //   一个没有底、只剩「− +」两个字的幽灵（实机截图：axolotl 那一行莫名多出 − +）。
        //   自检里 `shouldHide` 三条断言钉死这条规则（覆盖率 true/false × 焦点 true/false）。
        for (Button b : new Button[]{this.applyButtons[i], this.removeButtons[i], this.countDown[i],
                this.countUp[i], this.delayDown[i], this.delayUp[i], this.intervalDown[i],
                this.intervalUp[i]}) {
            if (b != null && SpawnPointLayout.shouldHide(covered, false)) {
                b.visible = false;
            }
        }
        // 输入框只在「没在打字」时才藏：藏了正在打字的框就丢焦点、键盘输入没人接。
        // （顺带一提：按当前布局它永远不会被盖 —— 下拉框正好从它下沿开始，自检里 covFocus 断言了这点。）
        if (SpawnPointLayout.shouldHide(covered, this.idBoxes[i].isFocused())) {
            this.idBoxes[i].visible = false;
        }
    }

    /** 面板坐标下的一段是否落进下拉框（{@code renderLabels} 用它决定哪几行文字不画）。 */
    private static boolean coveredInPanel(int y, int h, Dropdown dd) {
        return dd != null && SpawnPointLayout.coveredLine(y, h, dd.row(), dd.lines());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC0101010);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFFE04A4A);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFFE04A4A);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, 0xFFE04A4A);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFFE04A4A);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(this.font, this.title, this.imageWidth / 2, Y_TITLE, COLOR_VALUE);
        // 构建号：右下角之外最不碍事的地方，专门用来分辨「实机跑的是哪一版 jar」（见 UI_BUILD）
        String build = "UI#" + UI_BUILD;
        graphics.drawString(this.font, Component.literal(build), PANEL_WIDTH - ID_X - this.font.width(build),
                Y_TITLE, 0x707070, false);
        // ★ 2026-10 实测反馈「描述超出界面UI」：这条提示是**一整句长文本**，直接 drawString
        //   会一路画到右边的按钮上。两道保险：
        //   ① 文本本身缩短（见语言键 hint_group）；② 画之前按面板宽度截断 ——
        //      换语言/将来改文案都不会再越界。完整的说明挂在这一行的悬停提示里（见 render）。
        String hint = this.font.plainSubstrByWidth(
                Component.translatable(HINT_KEY).getString(), PANEL_WIDTH - 2 * ID_X);
        graphics.drawString(this.font, Component.literal(hint), ID_X, Y_HINT, COLOR_HINT, false);

        if (this.snapshot == null) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.guardian_protocol.spawn_point.loading"),
                    this.imageWidth / 2, Y_FIRST_ENTRY + 20, COLOR_ERROR);
            return;
        }

        List<SpawnPointBlockEntity.Entry> entries = this.snapshot.entries();
        // 下拉框占的那一段：落进去的文字一律不画（控件那侧见 applyCoverSuppression）
        Dropdown dd = this.dropdown();
        for (int i = 0; i < entries.size() && i < ROWS; i++) {
            SpawnPointBlockEntity.Entry e = entries.get(i);
            int baseY = Y_FIRST_ENTRY + i * ENTRY_BLOCK;

            // 行号 + 即时校验结论（解析出的生物名 / 具体哪一步不过）
            // ★ 被下拉框盖住的行不画：控件已经藏了（applyCoverSuppression），
            //   但行号/名字/数量延迟间隔是 renderLabels 画的，得在这里一起让路。
            if (!coveredInPanel(baseY + ROW_INFO_DY, 9, dd)) {
                // 行号：让「第几条」在日志与界面里是同一个说法
                graphics.drawString(this.font, Component.literal("#" + (i + 1)),
                        ID_X, baseY + ROW_INFO_DY, COLOR_ROW_INDEX, false);

                Component info = this.infoFor(i, e.entityId());
                graphics.drawString(this.font, info, ID_X + 14, baseY + ROW_INFO_DY,
                        this.rowInfoOk[i] ? COLOR_OK : COLOR_ERROR, false);
            }

            int stepY = baseY + ROW_STEP_DY;
            if (coveredInPanel(stepY, 10, dd)) {
                continue;
            }
            drawStep(graphics, 0, stepY, "gui.guardian_protocol.spawn_point.count",
                    Component.literal(String.valueOf(e.count())));
            drawStep(graphics, 1, stepY, "gui.guardian_protocol.spawn_point.delay",
                    Component.translatable("gui.guardian_protocol.spawn_point.ticks_value", e.delayTicks()));
            drawStep(graphics, 2, stepY, "gui.guardian_protocol.spawn_point.interval",
                    Component.translatable("gui.guardian_protocol.spawn_point.ticks_value", e.intervalTicks()));
        }
    }

    // ------------------------------------------------------------------
    // 关键字候选：中文也能打、能选（设计口径）
    // ------------------------------------------------------------------

    /**
     * 按输入框里的关键字刷新候选。
     *
     * <p>★ 关键字既匹配 <b>id</b>（{@code zombie}）也匹配 <b>本地化名字</b>（{@code 僵尸}）。
     * 名字只有在<b>客户端</b>才拿得到（专用服务器没有玩家的语言文件），所以这套匹配只在客户端做，
     * 选中后写进框里的是 <b>id</b> —— 服务端永远只看到 id，它那份解析逻辑一行都不用改
     * （这也顺手绕开了「服务端拿中文名匹配会按服务端语言算」的坑）。</p>
     *
     * <p>只列「能刷怪」的类别：{@code MISC} 一律不列 —— 它里面既有村民/铁傀儡这类真生物，
     * 也有箭矢/掉落物这类非生物，而 1.20.1 的 {@code EntityType#getBaseClass()} 是个写死的桩
     * （恒返回 {@code Entity.class}，踩坑记录），没法便宜地判「是不是 Mob」。
     * 要生成 MISC 里的生物，直负责打 id 即可（服务端照样受理）。</p>
     */
    private void updateSuggestions(int row) {
        while (this.suggestions.size() <= row) {
            this.suggestions.add(new java.util.ArrayList<>());
        }
        List<net.minecraft.world.entity.EntityType<?>> out = new java.util.ArrayList<>();
        String q = this.drafts[row] == null ? "" : this.drafts[row].trim();
        if (!q.isEmpty()) {
            String lower = q.toLowerCase(java.util.Locale.ROOT);
            for (net.minecraft.world.entity.EntityType<?> type
                    : net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES) {
                if (type.getCategory() == net.minecraft.world.entity.MobCategory.MISC) {
                    continue;
                }
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
                if (key == null) {
                    continue;
                }
                String id = key.toString();
                if (id.contains(lower) || type.getDescription().getString().contains(q)) {
                    out.add(type);
                }
            }
            out.sort(java.util.Comparator.comparingInt(t -> suggestRank(t, lower, q)));
            if (out.size() > MAX_SUGGEST) {
                out = new java.util.ArrayList<>(out.subList(0, MAX_SUGGEST));
            }
        }
        this.suggestions.set(row, out);
    }

    /** 排序权重：id 前缀 > id 包含 > 名字完全相同 > 名字包含（越小越靠前）。 */
    private static int suggestRank(net.minecraft.world.entity.EntityType<?> type,
                                   String lowerQuery, String rawQuery) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
        String id = key == null ? "" : key.toString();
        if (id.startsWith(lowerQuery)) {
            return 0;
        }
        if (id.contains(lowerQuery)) {
            return 1;
        }
        return type.getDescription().getString().equals(rawQuery) ? 2 : 3;
    }

    /**
     * 点「应用」时把人打的字解析成 id：已经是合法 id 就原样用；否则按**唯一命中**解析。
     *
     * <p>这样「打中文名 → 应用」也能用（唯一命中时自动换成 id）；有歧义（多个命中）就原样送出去，
     * 由服务端照旧报「无效的实体 id」—— 歧义时替玩家猜一个更糟。</p>
     */
    private String resolveTyped(int row) {
        String raw = this.drafts[row] == null ? "" : this.drafts[row].trim();
        if (net.minecraft.resources.ResourceLocation.tryParse(raw) != null) {
            return raw;
        }
        this.updateSuggestions(row);
        List<net.minecraft.world.entity.EntityType<?>> list = this.suggestions.get(row);
        if (list.size() == 1) {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(list.get(0));
            if (key != null) {
                return key.toString();
            }
        }
        return raw;
    }

    /** 当前获得焦点的是第几行的输入框；没有则 -1（下拉只画焦点那一行）。 */
    private int focusedRow() {
        Object focused = this.getFocused();
        for (int i = 0; i < ROWS; i++) {
            if (this.idBoxes[i] == focused) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 画候选下拉。
     *
     * <p>三件事一起做才算「修好」（第二张截图）：</p>
     * <ol>
     *   <li><b>不透明纯黑底</b>（{@code 0xFF000000}）—— 上一版 94% 的黑，底下那行会透出来；</li>
     *   <li><b>画在整帧最后</b>（见 {@code render}）—— 但这条已经<b>不再被信任</b>，
     *       真正管用的是 {@link #applyCoverSuppression()} 把被盖住的控件藏掉；</li>
     *   <li><b>宽度铺满面板内宽、中文名右对齐并给 id 让位</b> —— 原来 id 列从 x0+3 起、
     *       名字列固定 x0+152，而 {@code minecraft:zombified_piglin} 这种长 id 正好画到 147，
     *       两列贴脸叠字。现在名字右对齐到边框内侧，id 用完剩下的宽度、超了就截断，
     *       <b>结构上不可能再撞</b>。</li>
     * </ol>
     */
    private void renderSuggestions(GuiGraphics graphics, int mouseX, int mouseY) {
        this.suggestRects.clear();
        Dropdown dd = this.dropdown();
        if (dd == null) {
            return;
        }
        int row = this.focusedRow();
        List<net.minecraft.world.entity.EntityType<?>> list = this.suggestions.get(row);
        int x0 = dd.x0();
        int y0 = dd.y0();
        int w = dd.w();
        int bottom = dd.bottom();

        // 纯黑不透明底 = 下拉框真正占的地方。边框全部画在这块底<span>之内</span>：
        // 上边框如果画在黑底之上，就会压到焦点那一行输入框的最后一像素，
        // 于是那一行的「应用/删除」也会被判为「被盖住」而藏掉（见 SpawnPointLayout#paintedTop）。
        graphics.fill(x0 - 1, y0, x0 + w + 1, bottom + 1, 0xFF000000);
        graphics.fill(x0 - 1, y0, x0 + w + 1, y0 + 1, 0xFFE04A4A);
        graphics.fill(x0 - 1, y0, x0, bottom + 1, 0xFFE04A4A);
        graphics.fill(x0 + w, y0, x0 + w + 1, bottom + 1, 0xFFE04A4A);
        graphics.fill(x0 - 1, bottom, x0 + w + 1, bottom + 1, 0xFFE04A4A);

        for (int i = 0; i < dd.lines(); i++) {
            net.minecraft.world.entity.EntityType<?> type = list.get(i);
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
            String id = key == null ? "?" : key.toString();
            // ★ 先按 nameMax 截断名字，再算两列位置 —— 这是「永不叠字」的前提条件（见 nameMax 的注释）
            String name = this.font.plainSubstrByWidth(type.getDescription().getString(),
                    SpawnPointLayout.nameMax(w));
            int nameWidth = this.font.width(name);
            int ly = dd.y0() + i * SpawnPointLayout.LINE_H;
            boolean hover = mouseX >= x0 && mouseX <= x0 + w && mouseY >= ly
                    && mouseY <= ly + SpawnPointLayout.LINE_H;
            if (hover) {
                graphics.fill(x0, ly, x0 + w, ly + SpawnPointLayout.LINE_H, 0x40FFFFFF);
            }
            // 名字右对齐贴右边框，id 从左起、宽度 = 名字左边 − 空隙，超长就截断（两列结构上不可能重叠）
            int nameX = SpawnPointLayout.nameX(x0, w, nameWidth);
            int idMax = SpawnPointLayout.idMax(x0, w, nameWidth);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(id, idMax),
                    x0 + SpawnPointLayout.ID_DY_X, ly + SpawnPointLayout.TEXT_DY,
                    hover ? COLOR_VALUE : 0xFFDDDDDD, false);
            graphics.drawString(this.font, name, nameX, ly + SpawnPointLayout.TEXT_DY, COLOR_LABEL, false);
            this.suggestRects.add(new int[]{x0, ly, x0 + w, ly + SpawnPointLayout.LINE_H, row, i});
        }
    }

    /** 点候选 = 选中它（写 id 进框 + 立刻应用；服务端只认 id）。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int[] r : this.suggestRects) {
            if (mouseX >= r[0] && mouseX <= r[2] && mouseY >= r[1] && mouseY <= r[3]) {
                int row = r[4];
                int index = r[5];
                if (row < this.suggestions.size() && index < this.suggestions.get(row).size()) {
                    net.minecraft.resources.ResourceLocation key =
                            net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(
                                    this.suggestions.get(row).get(index));
                    if (key != null) {
                        this.idBoxes[row].setValue(key.toString());
                        this.drafts[row] = key.toString();
                        this.updateSuggestions(row);
                        this.send(SpawnPointBlockEntity.EditOp.SET_ENTRY_ID, row, key.toString());
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 一行「标签 − 值 +」里的标签与值（按钮由控件画）。 */
    private void drawStep(GuiGraphics graphics, int slot, int y, String labelKey, Component value) {        graphics.drawString(this.font, Component.translatable(labelKey), STEP_LABEL_X[slot], y + 4,
                COLOR_LABEL, false);
        graphics.drawCenteredString(this.font, value, STEP_VALUE_CENTER_X[slot], y + 4, COLOR_VALUE);
    }

    /** 取（并缓存）第 i 行的即时校验结论。文本没变就直接返回缓存，避免每帧试造实体。 */
    private Component infoFor(int i, String id) {
        if (!id.equals(this.rowInfoFor[i])) {
            this.rowInfoFor[i] = id;
            SpawnPointBlockEntity.Resolution resolution = SpawnPointBlockEntity.inspect(
                    this.minecraft == null ? null : this.minecraft.level, id);
            this.rowInfo[i] = resolution.display();
            this.rowInfoOk[i] = resolution.spawnable();
        }
        return this.rowInfo[i];
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ★ 先按「这一帧有没有下拉框」把被盖住的控件/文字摆好可见性，再画。
        //   等 tick 再摆会晚一帧：焦点是靠点击瞬间变的，那一帧的下拉框底下还是旧布局。
        this.applyCoverSuppression();
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
        // ★ 下拉候选画在**整帧的最后**（设计口径：把它拉到最前）。
        //   注意：这只是第一道保险；真正保证「不重叠」的是上面那句 applyCoverSuppression ——
        //   实机已经证明「画在最后」不足以保证盖住（见 applyCoverSuppression 的注释）。
        this.renderSuggestions(graphics, mouseX, mouseY);
        // ★ 顶部提示被截断时，把**完整**说明挂在这一行的悬停提示里（既不越界，也不丢信息）。
        //   坐标用绝对屏幕坐标：renderLabels 里那套是「面板相对」的，别混用。
        if (mouseX >= this.leftPos + ID_X && mouseX <= this.leftPos + PANEL_WIDTH - ID_X
                && mouseY >= this.topPos + Y_HINT && mouseY <= this.topPos + Y_HINT + 10) {
            graphics.renderTooltip(this.font, Component.translatable(HINT_KEY), mouseX, mouseY);
        }
    }

    /** 供将来做「点击空白处取消编辑」等交互时用。 */
    @Nullable
    public EditBox idBox(int row) {
        return row >= 0 && row < ROWS ? this.idBoxes[row] : null;
    }
}
