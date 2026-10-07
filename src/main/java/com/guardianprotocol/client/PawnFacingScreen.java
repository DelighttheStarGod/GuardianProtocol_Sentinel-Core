package com.guardianprotocol.client;

import com.guardianprotocol.data.BranchDef;
import com.guardianprotocol.data.PieceFacing;
import com.guardianprotocol.entity.PixelUnit;
import com.guardianprotocol.menu.PawnFacingMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;

/**
 * 棋子朝向界面。
 *
 * <h3>布局（2026-10-07 改版：加属性面板、旋转按钮挪到底部）</h3>
 * <pre>
 *        吟游者 30/30            ← 标题（分支名 + 血量，与命名牌同一口径）
 *        当前朝向：东             ← 转出来的方向名
 *   攻击力 7　生命值 30/30　护甲 4   ← 第 1 行：三项属性
 *        分支职业：辅助 · 吟游者     ← 第 2 行
 *   分支特性：不攻击，持续恢复范围内   ← 第 3 行（太长会折行，最多 3 行）
 *              所有友军生命…
 *   伤害乘区：打空中单位时 ×1.10      ← ★ 第 4 行（没乘区的分支整行不画）
 *     朝向决定攻击范围与阻挡方向      ← 说明
 *   [逆时针 90°] [完成] [顺时针 90°]  ← 三个按钮在**底部同一行**：旋转放完成的两侧
 * </pre>
 *
 * <p>★ <b>第 1 行的「攻击力」是当前**有效**攻击力</b>（面板值 × 攻击力倍率），
 * 倍率 ≠ 1 时括号里写出倍率 —— 解放者的「攻击力逐渐提升至 +200%」在这里**看着它涨**
 * （设计口径：原文写的是「**攻击力**」不是「伤害」，所以它归攻击力这一侧）。</p>
 * <p>★ <b>第 4 行</b>是「伤害乘区 / 攻击力爬升」：文本由
 * {@code DamageMods#panelText} 生成，没有乘区的分支整行不画。</p>
 *
 * <p>点击走原版 {@code Button → menu.clickMenuButton → 服务端} 这条链路，
 * 客户端只发固定按钮 id，具体转多少度由服务端算 —— 客户端无法伪造朝向。
 * 新增的几行只是**读取**棋子数据，不发任何包（解放者的爬升进度是**同步字段**，见下）。</p>
 *
 * <h3>★ 界面上这些数字从哪来</h3>
 * <ul>
 *     <li>攻击力：{@link PixelUnit#effectiveAttackDamage()} = 面板值 × 攻击力倍率
 *         （倍率判据只有 {@code DamageMods#attackMultiplier} 一处，与伤害结算**同一个数**）；</li>
 *     <li>护甲：{@link BranchDef#armor()}；</li>
 *     <li>生命值：棋子的**当前 / 最大值**（与头顶命名牌同一个口径）；</li>
 *     <li>分支职业：职业语言键（{@code unit.guardian_protocol.*}）· 分支名
 *         （{@link PixelUnit#branchName()}，与命名牌同一个方法，不另抄一份规则）；</li>
 *     <li>分支特性：{@link BranchDef#traitDisplayText()} —— 清洗（{@code |} → {@code ；}）
 *         只在那一处做，界面不再 replace 一遍；</li>
 *     <li>第 4 行：{@code DamageMods#panelText}（伤害乘区 / 攻击力爬升；没有就整行不画）。</li>
 * </ul>
 *
 * <p>★ 解放者的爬升进度（{@link PixelUnit#getRampTicks()}）是 **entityData 同步字段** ——
 * 实测反馈「解放者的效果从面板上看不到」，根因就是它原来只是个服务端普通字段，
 * 客户端恒读到 0（面板只能显示 ×1.00）。</p>
 *
 * <p>★ 实体在客户端可能**还没同步过来**（打开界面的那一两 tick），此时
 * {@link PawnFacingMenu#unit()} 返回 null：属性行三格都显示「—」而不是抛异常或显示 0。</p>
 */
@OnlyIn(Dist.CLIENT)
public class PawnFacingScreen extends AbstractContainerScreen<PawnFacingMenu> {

    /**
     * 面板尺寸。
     *
     * <p>宽度从 200 加到 240：底部要放**三个**按钮（60×3 + 10×2 = 200），再加左右各 20 边距；
     * 同时给「分支特性」那一行留出约 24 个汉字的折行宽度。</p>
     */
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 190;

    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 10;

    // ---- 纵向布局锚点（相对面板左上角）----
    private static final int Y_TITLE = 10;
    private static final int Y_CURRENT = 30;
    /** 第 1 行：攻击力 / 生命值 / 护甲值。 */
    private static final int Y_STATS = 52;
    /** 第 2 行：分支职业。 */
    private static final int Y_CLASS = 70;
    /** 第 3 行：分支特性（本行起点；折行最多 {@link #TRAIT_MAX_LINES} 行）。 */
    private static final int Y_TRAIT = 88;
    /** 第 4 行：伤害乘区 / 攻击力爬升（没有乘区的分支这一行不画，位置留空）。 */
    private static final int Y_MOD = 118;
    /** 说明文字：留在特性那一块下面、按钮上面。 */
    private static final int Y_HINT = 136;
    /** 底部那一行按钮（旋转 ×2 + 完成）。 */
    private static final int Y_BUTTON = PANEL_HEIGHT - BUTTON_HEIGHT - 10;

    /**
     * 「分支特性」最多画几行。
     *
     * <p>实测 72 个分支的原文长度：46 条 1 行、24 条 2 行、2 条 3 行（按 224px 内宽算），
     * 所以 3 行够用；数据包分支若塞了更长的文本，第 3 行末尾补一个「…」（见
     * {@link #drawCenteredWrapped}）—— **截断要看得见**，不能悄悄吃字。</p>
     */
    private static final int TRAIT_MAX_LINES = 3;

    /** 折行用的内宽（面板宽 − 左右各 8 的留白）。 */
    private static final int TEXT_INNER_WIDTH = PANEL_WIDTH - 16;

    /** 数据还没同步过来时显示的占位符。 */
    private static final String PLACEHOLDER = "—";

    /** 本地预览的朝向；服务端同步回来后会被覆盖成权威值。 */
    private PieceFacing previewFacing;

    public PawnFacingScreen(PawnFacingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
        this.previewFacing = currentFacingOrDefault(menu);
        this.inventoryLabelY = -10000;   // 没有物品栏
    }

    /** 取当前朝向；棋子还没同步过来时兜底正东（与实体的默认一致）。 */
    private static PieceFacing currentFacingOrDefault(PawnFacingMenu menu) {
        var unit = menu.unit();
        return unit == null ? PieceFacing.EAST : unit.getFacing();
    }

    @Override
    protected void init() {
        super.init();

        int left = this.leftPos;
        int top = this.topPos;
        // 底部一行三个按钮：逆时针 / 完成 / 顺时针 —— 居中铺在面板里。
        int threeButtons = BUTTON_WIDTH * 3 + GAP * 2;
        int rowLeft = left + (PANEL_WIDTH - threeButtons) / 2;

        // 逆时针（完成键左侧）
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.pawn_facing.rotate_ccw"),
                        b -> this.rotate(false))
                .bounds(rowLeft, top + Y_BUTTON, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        // 完成（中间）
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.pawn_facing.close"),
                        b -> this.onClose())
                .bounds(rowLeft + BUTTON_WIDTH + GAP, top + Y_BUTTON, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        // 顺时针（完成键右侧）
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.pawn_facing.rotate_cw"),
                        b -> this.rotate(true))
                .bounds(rowLeft + (BUTTON_WIDTH + GAP) * 2, top + Y_BUTTON,
                        BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    /** 点 ± 时：本地先转（界面立刻有反馈），同时把按钮 id 发给服务端真正执行。 */
    private void rotate(boolean clockwise) {
        this.previewFacing = clockwise
                ? this.previewFacing.clockwise()
                : this.previewFacing.counterClockwise();
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId,
                clockwise ? PawnFacingMenu.BUTTON_ROTATE_CW : PawnFacingMenu.BUTTON_ROTATE_CCW);
    }

    /**
     * 每帧与服务端对齐。
     *
     * <p>同步数据（{@code DATA_FACING}）是权威来源，这样多人环境下别人转了朝向，
     * 你这边也会跟着变；本地预览只负责点击的即时手感。</p>
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        PieceFacing authoritative = currentFacingOrDefault(this.menu);
        if (authoritative != this.previewFacing) {
            this.previewFacing = authoritative;
        }
    }

    /** 半透明底 + 蓝色描边，与保护目标界面同一套视觉。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC0101010);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4A7FE0);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF4A7FE0);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, 0xFF4A7FE0);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF4A7FE0);
    }

    /** 文字坐标相对面板左上角（原版已 translate），所以直接用上面的常量。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.imageWidth / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, Y_TITLE, 0xFFFFFF);

        // 当前朝向：把中文方向名直接显示出来，避免玩家自己数转了几次
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.guardian_protocol.pawn_facing.current",
                        this.previewFacing.displayName()),
                centerX, Y_CURRENT, 0x8CD8F0);

        // ---- 新增三行：属性 / 分支职业 / 分支特性 ----
        drawStats(graphics, centerX);
        drawClassAndBranch(graphics, centerX);
        drawTrait(graphics, centerX);
        drawDamageMod(graphics, centerX);

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.guardian_protocol.pawn_facing.hint"),
                centerX, Y_HINT, 0x9A9A9A);
    }

    /**
     * 第 1 行：攻击力 / 生命值 / 护甲值。
     *
     * <p>★ <b>攻击力给的是「当前有效攻击力」</b>（{@link PixelUnit#effectiveAttackDamage()}）
     * —— 也就是「面板值 × 攻击力倍率」。解放者的 ramp 正是**攻击力**倍率
     * （设计口径：「解放者是攻击力逐渐提升至 200%，它也没说是伤害啊」），
     * 所以这一行会随时间**看着它涨**；倍率 ≠ 1 时在括号里把倍率也写出来，
     * 免得玩家以为是别的什么在动。</p>
     */
    private void drawStats(GuiGraphics graphics, int centerX) {
        Component line;
        PixelUnit unit = this.menu.unit();
        if (unit == null) {
            line = Component.translatable("gui.guardian_protocol.pawn_facing.stats",
                    PLACEHOLDER, PLACEHOLDER, PLACEHOLDER);
        } else {
            BranchDef branch = unit.getBranch();
            double mult = unit.attackMultiplier();
            String atk = fmt(unit.effectiveAttackDamage());
            if (Math.abs(mult - 1.0D) > 1.0E-6D) {
                atk += String.format(Locale.ROOT, "（×%.2f）", mult);
            }
            line = Component.translatable("gui.guardian_protocol.pawn_facing.stats",
                    atk,
                    // 生命值给「当前 / 最大」：与头顶命名牌同一个口径，玩家不必自己换算
                    String.format(Locale.ROOT, "%.0f/%.0f",
                            unit.getHealth(), unit.getMaxHealth()),
                    fmt(branch.armor()));
        }
        graphics.drawCenteredString(this.font, line, centerX, Y_STATS, 0xE8E8E8);
    }

    /**
     * 第 4 行：伤害乘区 / 攻击力爬升。
     *
     * <p>设计口径：「解放者的效果从面板上看不到啊，能让面板显示吗？」——
     * 这一行就是回答它：整行文本由 {@code DamageMods#panelText} 生成
     * （解放者是「攻击力爬升：当前 ×1.35（最高 ×3.00，未开技能 12/40 秒）」，
     * 其余 7 个是「伤害乘区：<条件> ×倍率」，模组来的还带「（★模组）」）。</p>
     *
     * <p><b>没有乘区的 64 个分支这一行整行不画</b>（面板大小固定，位置留空）——
     * 画一行「伤害乘区：无」只会让面板变吵。</p>
     */
    private void drawDamageMod(GuiGraphics graphics, int centerX) {
        PixelUnit unit = this.menu.unit();
        if (unit == null) {
            return;
        }
        String text = com.guardianprotocol.combat.DamageMods.panelText(
                unit.getBranch().damageMod(), unit.getRampTicks());
        if (text.isEmpty()) {
            return;
        }
        graphics.drawCenteredString(this.font, Component.literal(text), centerX, Y_MOD, 0xFFD98A);
    }

    /** 第 2 行：分支职业 = 职业 · 分支。 */
    private void drawClassAndBranch(GuiGraphics graphics, int centerX) {
        Component who;
        PixelUnit unit = this.menu.unit();
        if (unit == null) {
            who = Component.literal(PLACEHOLDER);
        } else {
            BranchDef branch = unit.getBranch();
            // ★ 分支名走 PixelUnit#branchName()（内置走语言键、数据包分支用字面量）——
            //   与命名牌/Jade 同一个方法，不在这里另抄一份「怎么取分支名」的规则。
            who = Component.empty()
                    .append(Component.translatable(branch.unitClass().translationKey()))
                    .append(Component.literal(" · "))
                    .append(unit.branchName());
        }
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.guardian_protocol.pawn_facing.class_branch", who),
                centerX, Y_CLASS, 0x8CD8F0);
    }

    /** 第 3 行：分支特性原文（折行，最多 {@link #TRAIT_MAX_LINES} 行）。 */
    private void drawTrait(GuiGraphics graphics, int centerX) {
        PixelUnit unit = this.menu.unit();
        // ★ 清洗只在 BranchDef#traitDisplayText() 里做一次（`|` → `；`），这里不再 replace。
        String text = unit == null ? PLACEHOLDER : unit.getBranch().traitDisplayText();
        if (text.isEmpty()) {
            text = PLACEHOLDER;
        }
        drawCenteredWrapped(graphics,
                Component.translatable("gui.guardian_protocol.pawn_facing.trait", text),
                centerX, Y_TRAIT, 0xB8B8B8, TRAIT_MAX_LINES);
    }

    /**
     * 居中 + 折行地画一段文字。
     *
     * <p>{@code drawCenteredString} 只吃单行，所以这里用 {@code font.split} 拿到折行结果
     * 再逐行手动居中。超过 {@code maxLines} 时在最后一行末尾补一个「…」——
     * <b>截断要看得见</b>（同项目一贯口径：宁可露出「这里被截了」，也不要静默吃字）。</p>
     */
    private void drawCenteredWrapped(GuiGraphics graphics, Component text, int centerX, int y,
                                     int color, int maxLines) {
        List<FormattedCharSequence> lines = this.font.split(text, TEXT_INNER_WIDTH);
        int shown = Math.min(lines.size(), maxLines);
        for (int i = 0; i < shown; i++) {
            FormattedCharSequence line = lines.get(i);
            int w = this.font.width(line);
            graphics.drawString(this.font, line, centerX - w / 2,
                    y + i * this.font.lineHeight, color, false);
            if (i == shown - 1 && lines.size() > maxLines) {
                graphics.drawString(this.font, "…", centerX + w / 2 + 1,
                        y + i * this.font.lineHeight, color, false);
            }
        }
    }

    /** 数值统一按「整数」显示（本工程的攻击力/护甲都是整数点，用 {@code Locale.ROOT} 避免区域小数点）。 */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
