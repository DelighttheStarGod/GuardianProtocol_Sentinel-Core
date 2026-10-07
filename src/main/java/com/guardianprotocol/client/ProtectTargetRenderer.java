package com.guardianprotocol.client;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.block.ProtectTargetBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 保护目标的渲染器：把 P2 的蓝色线框立方体贴图，画成一个正对摄像机的公告板面片。
 *
 * <p>手法与 {@link PixelUnitRenderer} 完全一致（同一个「2D 贴图朝镜头」的约定），
 * 区别只在尺度：这里铺满 1x1x1 格，并且浮在方块上方一点点、带轻微上下浮动，
 * 让它看起来像一个「悬浮的能量立方体」，而不是一个普通方块。</p>
 *
 * <p>贴图 {@code textures/entity/protect_target.png} 里画的已经是一个等距立方体，
 * 所以面片必须是<b>正方形</b>，否则等距投影会被拉变形。</p>
 */
public class ProtectTargetRenderer implements BlockEntityRenderer<ProtectTargetBlockEntity> {

    /** 贴图路径。 */
    public static final ResourceLocation TEXTURE = new ResourceLocation(
            GuardianProtocol.MODID, "textures/entity/protect_target.png");

    /** 面片边长（格）。1.0 = 正好一个方块。 */
    private static final float SIZE = 1.0F;

    /** 面片底边相对方块底面的高度偏移（格）。0 表示正好贴在方块上。 */
    private static final float Y_OFFSET = 0.0F;

    /** 上下浮动幅度（格）。0 = 不浮动。 */
    private static final float BOB_AMPLITUDE = 0.04F;

    /** 浮动周期（tick）。 */
    private static final float BOB_PERIOD = 60.0F;

    private static final float HALF = SIZE / 2.0F;

    public ProtectTargetRenderer(BlockEntityRendererProvider.Context context) {
        // 目前不需要 context 里的任何东西；保留构造器签名以便将来加模型层/字体。
    }

    @Override
    public void render(ProtectTargetBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return;
        }
        Vec3 cameraPos = camera.getPosition();

        // 浮动：用世界时间 + 方块坐标错开相位，避免所有保护目标同步上下动（很怪）。
        double phase = (blockEntity.getLevel() == null ? 0.0D
                : blockEntity.getLevel().getGameTime() + partialTick)
                + (blockEntity.getBlockPos().getX() * 7 + blockEntity.getBlockPos().getZ() * 13);
        float bob = Mth.sin((float) (phase / BOB_PERIOD * Math.PI * 2.0D)) * BOB_AMPLITUDE;

        poseStack.pushPose();

        // 把渲染原点从「方块角落」挪到「方块中心 + 浮动」，
        // 这样面片是以方块中心为轴，视觉上不会偏向一角。
        poseStack.translate(0.5D, 0.5D + Y_OFFSET + bob, 0.5D);

        // 公告板：先绕 Y 轴正对摄像机，再绕 X 轴抵消摄像机俯仰。
        double dx = cameraPos.x() - (blockEntity.getBlockPos().getX() + 0.5D);
        double dz = cameraPos.z() - (blockEntity.getBlockPos().getZ() + 0.5D);
        float yawToCamera = (float) Mth.atan2(dz, dx) * (180F / (float) Math.PI) - 90.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-yawToCamera));
        poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

        // 用 entityTranslucent：贴图带半透明面片，必须走 alpha 混合，不能用 cutout。
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();

        // 四个顶点：左上 -> 右上 -> 右下 -> 左下。
        // UV 约定与像素小人一致：v=0 是贴图文件顶部。
        vertex(consumer, matrix, normal, -HALF, -HALF, 0.0F, 1.0F, packedLight, packedOverlay);
        vertex(consumer, matrix, normal, HALF, -HALF, 1.0F, 1.0F, packedLight, packedOverlay);
        vertex(consumer, matrix, normal, HALF, HALF, 1.0F, 0.0F, packedLight, packedOverlay);
        vertex(consumer, matrix, normal, -HALF, HALF, 0.0F, 0.0F, packedLight, packedOverlay);

        poseStack.popPose();
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float u, float v,
                               int light, int overlay) {
        consumer.vertex(matrix, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    /** 保护目标是发光体，即使在全黑处也应可见 —— 用满亮度而不是局部光照。 */
    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public boolean shouldRenderOffScreen(ProtectTargetBlockEntity blockEntity) {
        // 塔防里保护目标可能很远就要看得见（比如地图另一端的占领点）。
        return true;
    }
}
