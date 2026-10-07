package com.guardianprotocol.client;

import com.guardianprotocol.entity.PixelUnit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 2D 像素小人棋子渲染器 —— 公告板（billboard）方案。
 *
 * <p><b>核心思路</b>：不给实体任何几何模型，而是在渲染时临时拼一个四边形，
 * 让它的法线始终指向摄像机。于是无论玩家从哪个方向看，看到的都是同一张正面像素图。
 * 这与 Paper Mario / 许多塔防「纸片人」的做法一致。</p>
 *
 * <p><b>为什么不用 LivingEntityRenderer + EntityModel</b>：那条路要给实体建骨骼模型
 * （LayerDefinition / ModelPart），而我们的「模型」其实只是一张平面贴图，
 * 建骨骼纯属绕远路。直接继承 {@link EntityRenderer} 是最短的路径：
 * 原版的阴影、命名牌逻辑都在父类 {@code super.render} 里，照常可用。</p>
 *
 * <p><b>棋子是「钉死」的</b>（见 {@code PixelUnit}）：不做浮动动画，
 * 因为脚一旦离地就破坏了「摆件」的观感；改用一个短促的落地弹出（缩放）做反馈。</p>
 *
 * <p><b>朝向与位置约定</b>（和贴图美术约定绑定，改贴图时要一起改）：</p>
 * <ul>
 *     <li>四边形以实体脚底为原点，向上 1.8 格、左右各 0.6 格；</li>
 *     <li>贴图 UV：u 从左到右 0→1；<b>v=0 对应贴图文件顶部</b>（Minecraft 贴图 v 轴向下），
 *         所以「四边形的上边」取 v=0、「下边」取 v=1；</li>
 *     <li>像素小人的<b>脚底</b>画在 64x64 贴图底部、头顶在顶部。</li>
 * </ul>
 */
public class PixelUnitRenderer extends EntityRenderer<PixelUnit> {

    /** 四边形的半宽（格）。实体碰撞箱宽 0.6，这里取 0.3 让贴图与碰撞箱同宽。 */
    private static final float HALF_WIDTH = 0.3F;

    /** 四边形的高度（格）。与碰撞箱高度一致，画出来最自然。 */
    private static final float HEIGHT = 1.8F;

    /** 四边形所在的 z 平面（相对实体原点）。略微前移，避免和脚下方块 z-fighting。 */
    private static final float PLANE_Z = 0.0F;

    /** 受击闪红时，叠加的红色强度上限（0~1）。 */
    private static final float HURT_FLASH = 0.55F;

    /** 落地弹出动画时长（tick）。20 tick = 1 秒。 */
    private static final int SPAWN_ANIM_TICKS = 7;

    /**
     * 精炼棋子的金色光效参数。
     *
     * <p>颜色取 {@code 0xFFD700}（纯金）：R=1.0、G=215/255≈0.843、B=0。
     * alpha 在 {@link #GOLD_MIN_ALPHA}~{@code MIN+SPAN}（60~100／255）之间按正弦呼吸 ——
     * 最暗也看得见是一层金，最亮也不会糊住像素图本身。</p>
     */
    private static final float GOLD_R = 1.0F;
    private static final float GOLD_G = 0.843F;
    private static final float GOLD_B = 0.0F;
    private static final float GOLD_MIN_ALPHA = 60.0F;
    private static final float GOLD_ALPHA_SPAN = 40.0F;

    public PixelUnitRenderer(EntityRendererProvider.Context context) {
        super(context);
        // 阴影半径与强度：小人脚下有一圈柔和阴影，立体感和位置感会好很多。
        this.shadowRadius = 0.35F;
        this.shadowStrength = 0.8F;
    }

    @Override
    public ResourceLocation getTextureLocation(PixelUnit entity) {
        // 职业决定贴图。贴图不存在时会显示成紫黑格，正好一眼看出是资源缺失而不是渲染没跑。
        return entity.getSpriteTexture();
    }

    @Override
    public void render(PixelUnit entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        // 先让父类处理命名牌（本 mod 用命名牌显示「分支 + 血量」）。
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        // 世界光照：直接借原版算法把方块光与天空光打包成一个 int。
        BlockPos lightPos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
        int light = LightTexture.pack(
                this.getBlockLightLevel(entity, lightPos),
                this.getSkyLightLevel(entity, lightPos));

        Camera camera = this.entityRenderDispatcher.camera;
        if (camera == null) {
            // 理论上不会发生（渲染时相机必定存在），但防御一下总比 NPE 崩客户端强。
            return;
        }
        Vec3 cameraPos = camera.getPosition();

        poseStack.pushPose();

        // 0) 落地弹出动画：刚放下的 0.35 秒里从 40% 放大到 100%。
        //    棋子是「钉死」的，不能上下浮动（脚会离地），所以用缩放做反馈。
        if (entity.tickCount < SPAWN_ANIM_TICKS) {
            float t = Mth.clamp((entity.tickCount + partialTick) / (float) SPAWN_ANIM_TICKS, 0.0F, 1.0F);
            // 缓出：起步快、收尾慢
            float eased = 1.0F - (1.0F - t) * (1.0F - t);
            float scale = 0.4F + 0.6F * eased;
            poseStack.scale(scale, scale, scale);
        }

        // 1) 让四边形绕 Y 轴正对相机，再绕 X 轴抵消相机的俯仰。
        float yawToCamera = (float) Mth.atan2(
                cameraPos.z() - entity.getZ(),
                cameraPos.x() - entity.getX()) * (180F / (float) Math.PI) - 90.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-yawToCamera));
        poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

        // 2) 受击闪红：实体进入受击无敌帧期间，整体叠一层红。
        float hurt = entity.hurtTime > 0
                ? (entity.hurtTime - partialTick) / (float) entity.hurtDuration
                : 0.0F;
        float red = 1.0F + Mth.clamp(hurt, 0.0F, 1.0F) * HURT_FLASH;
        float greenBlue = 1.0F - Mth.clamp(hurt, 0.0F, 1.0F) * HURT_FLASH;

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(this.getTextureLocation(entity)));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();

        // 3) 四个顶点，逆时针（从相机看）：左上 → 右上 → 右下 → 左下。
        //    注意：y 从 0 起，所以贴图底边正好压在实体脚下（脚不离地）。
        vertex(consumer, matrix, normal, -HALF_WIDTH, 0.0F, 0.0F, 1.0F, red, greenBlue, light);
        vertex(consumer, matrix, normal, HALF_WIDTH, 0.0F, 1.0F, 1.0F, red, greenBlue, light);
        vertex(consumer, matrix, normal, HALF_WIDTH, HEIGHT, 1.0F, 0.0F, red, greenBlue, light);
        vertex(consumer, matrix, normal, -HALF_WIDTH, HEIGHT, 0.0F, 0.0F, red, greenBlue, light);

        // 4) 精炼棋子：同尺寸再叠一层金色半透明面片。
        //    刻意不引 shader、不加新贴图 —— 顶点与 UV 和上面完全一致，只换颜色与 alpha，
        //    所以「金色只在像素小人的轮廓内发光」（贴图自身的 alpha 仍在起作用）。
        if (entity.isRefined()) {
            // 0.15 弧度/tick ≈ 2.1 秒一个完整呼吸周期，太快会闪得难受、太慢看不出在动。
            float pulse = (float) (0.5D + 0.5D * Math.sin((entity.tickCount + partialTick) * 0.15D));
            float alpha = (GOLD_MIN_ALPHA + GOLD_ALPHA_SPAN * pulse) / 255.0F;
            VertexConsumer glow = bufferSource.getBuffer(
                    RenderType.entityTranslucent(this.getTextureLocation(entity)));
            vertexRgba(glow, matrix, normal, -HALF_WIDTH, 0.0F, 0.0F, 1.0F,
                    GOLD_R, GOLD_G, GOLD_B, alpha, light);
            vertexRgba(glow, matrix, normal, HALF_WIDTH, 0.0F, 1.0F, 1.0F,
                    GOLD_R, GOLD_G, GOLD_B, alpha, light);
            vertexRgba(glow, matrix, normal, HALF_WIDTH, HEIGHT, 1.0F, 0.0F,
                    GOLD_R, GOLD_G, GOLD_B, alpha, light);
            vertexRgba(glow, matrix, normal, -HALF_WIDTH, HEIGHT, 0.0F, 0.0F,
                    GOLD_R, GOLD_G, GOLD_B, alpha, light);
        }

        poseStack.popPose();
    }

    /**
     * 输出一个顶点。
     *
     * @param x       四边形局部坐标 x（相对实体原点）
     * @param y       四边形局部坐标 y（相对实体脚底）
     * @param u       贴图横坐标 0~1
     * @param v       贴图纵坐标 0~1（0 = 贴图文件顶部）
     * @param red     颜色乘子 R（>1 表示提亮）
     * @param greenBlue 颜色乘子 G 与 B
     */
    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float u, float v,
                               float red, float greenBlue, int light) {
        vertexRgba(consumer, matrix, normal, x, y, u, v, red, greenBlue, greenBlue, 1.0F, light);
    }

    /**
     * 输出一个带 alpha 的顶点（精炼金色光效要用）。
     *
     * <p>与 {@link #vertex} 的差别只有颜色是三分量 + alpha：光效面片要半透明，
     * 而普通贴图面片是不透明的（alpha = 1）。</p>
     */
    private static void vertexRgba(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                                   float x, float y, float u, float v,
                                   float red, float green, float blue, float alpha, int light) {
        consumer.vertex(matrix, x, y, PLANE_Z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                // 法线朝向 +Z（四边形正面）。公告板已经正对相机，所以这个法线够用。
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
