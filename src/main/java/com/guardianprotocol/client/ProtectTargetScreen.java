package com.guardianprotocol.client;

import com.guardianprotocol.block.TauntRadius;
import com.guardianprotocol.menu.ProtectTargetMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 保护目标的设置界面。
 *
 * <p>只有一个可调项（嘲讽半径），所以不用原版那种「一堆槽位」的布局，
 * 直接摆三个按钮：缩小 / 恢复默认 / 放大。当前值实时显示在标题下方。</p>
 *
 * <p>按钮点击走 {@link AbstractContainerScreen#minecraft} 的标准路径
 * （{@code Button} 内部会调 {@code menu.clickMenuButton}），
 * 由原版把点击事件发到服务端执行 —— 客户端无权直接改半径。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ProtectTargetScreen extends AbstractContainerScreen<ProtectTargetMenu> {

    /**
     * 界面面板尺寸。
     *
     * <p>高度必须够放下 4 行元素（标题 / 当前值 / ± 按钮 / 恢复默认 + 完成），
     * 否则按钮会画到面板外、或者互相重叠。</p>
     */
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_HEIGHT = 150;

    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 10;

    // ---- 纵向布局锚点（相对面板左上角；renderLabels 用的也是这个坐标系）----
    /** 标题。 */
    private static final int Y_TITLE = 10;
    /** 「当前嘲讽半径：xx 格」。 */
    private static final int Y_CURRENT = 30;
    /** ± 按钮所在行。 */
    private static final int Y_STEP_ROW = 50;
    /** 恢复默认按钮所在行。 */
    private static final int Y_RESET_ROW = Y_STEP_ROW + BUTTON_HEIGHT + GAP;
    /** 提示文字：必须在「恢复默认」下方留出空隙，否则会像之前那样压在按钮上。 */
    private static final int Y_HINT = Y_RESET_ROW + BUTTON_HEIGHT + 12;
    /** 完成按钮贴面板底部。 */
    private static final int Y_DONE = PANEL_HEIGHT - BUTTON_HEIGHT - 10;

    /** 当前半径的预览值；按钮点击后本地先更新，服务端方块状态回来后会覆盖成权威值。 */
    private TauntRadius previewRadius;

    public ProtectTargetScreen(ProtectTargetMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
        this.previewRadius = menu.currentRadius();
        // 本界面没有物品栏，不画玩家背包那一栏
        this.inventoryLabelY = -10000;
    }

    @Override
    protected void init() {
        super.init();

        // 用 leftPos/topPos（原版算好的面板左上角）而不是自己再算一遍居中，
        // 这样和 renderLabels 的坐标系天然一致。
        int left = this.leftPos;
        int top = this.topPos;

        int stepRowY = top + Y_STEP_ROW;
        int twoButtonsWidth = BUTTON_WIDTH * 2 + GAP;
        int stepLeftX = left + (PANEL_WIDTH - twoButtonsWidth) / 2;
        int centeredX = left + (PANEL_WIDTH - BUTTON_WIDTH) / 2;

        // 第一行：缩小 / 放大
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.protect_target.radius_down"),
                        b -> this.adjustRadius(false))
                .bounds(stepLeftX, stepRowY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.protect_target.radius_up"),
                        b -> this.adjustRadius(true))
                .bounds(stepLeftX + BUTTON_WIDTH + GAP, stepRowY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        // 第二行：恢复默认
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.protect_target.radius_reset"),
                        b -> {
                            this.previewRadius = TauntRadius.DEFAULT;
                            this.minecraft.gameMode.handleInventoryButtonClick(
                                    this.menu.containerId, ProtectTargetMenu.BUTTON_RADIUS_RESET);
                        })
                .bounds(centeredX, top + Y_RESET_ROW, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        // 底部：完成
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.guardian_protocol.protect_target.close"),
                        b -> this.onClose())
                .bounds(centeredX, top + Y_DONE, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    /** 点 ± 时：本地先变（界面立刻响应），同时把按钮 id 发给服务端执行。 */
    private void adjustRadius(boolean up) {
        this.previewRadius = up ? this.previewRadius.next() : this.previewRadius.previous();
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId,
                up ? ProtectTargetMenu.BUTTON_RADIUS_UP : ProtectTargetMenu.BUTTON_RADIUS_DOWN);
    }

    /**
     * 每帧同步一次：以服务端方块状态为准。
     *
     * <p>这样多人游戏里别人改了半径，你这边也会跟着变；本地预览只是让点击手感即时。</p>
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        TauntRadius authoritative = this.menu.currentRadius();
        if (authoritative != this.previewRadius) {
            this.previewRadius = authoritative;
        }
    }

    /** 画背景板（原版容器的灰色底），自绘一层半透明底 + 蓝色边框。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 直接铺一层半透明黑，避免为了一个自绘界面去打包一张 256x256 的 GUI 贴图。
        int left = this.leftPos;
        int top = this.topPos;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC0101010);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4A7FE0);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF4A7FE0);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, 0xFF4A7FE0);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF4A7FE0);
    }

    /**
     * 文字。
     *
     * <p>注意 {@code renderLabels} 的坐标是<b>相对面板左上角</b>（原版已经 translate 过了），
     * 所以这里直接用上面定义的 {@code Y_*} 常量即可，不要再加 leftPos/topPos。</p>
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.imageWidth / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, Y_TITLE, 0xFFFFFF);

        Component value = Component.translatable(
                "gui.guardian_protocol.protect_target.current", this.previewRadius.display());
        graphics.drawCenteredString(this.font, value, centerX, Y_CURRENT, 0x8CD8F0);

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.guardian_protocol.protect_target.hint"),
                centerX, Y_HINT, 0x9A9A9A);
    }

    /** 不画玩家物品栏（本界面没有物品交互）。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
