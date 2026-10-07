package com.guardianprotocol.client;

import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.entity.PawnProjectile;
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
 * 棋子投掷物渲染器 —— 公告板（billboard）面片，画法与 {@link PixelUnitRenderer} 同源。
 *
 * <p><b>为什么照抄棋子那套而不是另起一套</b>：这个 mod 的「美术」全是 2D 像素图，
 * 公告板是唯一能既不建骨骼模型、又保证「从任何角度看都是正面」的做法。
 * 两颗实体的差别只有尺寸与渲染类型，旋转那两行（atan2 水平角 + 抵消俯仰）
 * 是同一个公式，抄过来比抽象一层更省事、也更不容易抄错。</p>
 *
 * <p><b>旋转口径</b>（与 {@code PixelUnitRenderer} 逐字一致，改一处要一起改）：</p>
 * <ol>
 *     <li>先绕 Y 轴转到「面片法线指向摄像机」：水平角 = atan2(dz, dx)，
 *         因为四边形的正面是 +Z，而 Minecraft 的 yaw 0° 指向 +Z，所以整体再减 90°；</li>
 *     <li>再绕 X 轴<b>反向</b>抵消摄像机的俯仰（{@code camera.getXRot()}），
 *         这样玩家抬头/低头看时面片不会跟着躺下去。</li>
 * </ol>
 *
 * <p><b>为什么不引 shader、不自定义 RenderType</b>：像素单位渲染器也没那么做。
 * 弹体只要一层半透明面片就够了，加一层自定义管线只会多一个在无头环境里
 * <b>验不到</b>的变量（贴图/渲染的观感本来就只能靠实机目视，见 设计文档 的边界说明）。</p>
 */
@SuppressWarnings("removal")   // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath（1.21 才加）
public class PawnProjectileRenderer extends EntityRenderer<PawnProjectile> {

    /**
     * 贴图。不存在时会画成紫黑格 —— 那正好一眼看出是资源缺失，而不是渲染没跑。
     *
     * <p>刻意用 <b>1.20.1 的字符串构造器</b>：{@code ResourceLocation.fromNamespaceAndPath}
     * 是 1.21 才有的 API（{@code UnitClass#spriteTexture} 里有同样的注释）。</p>
     */
    private static final ResourceLocation TEXTURE = new ResourceLocation(
            GuardianProtocol.MODID, "textures/entity/pawn_projectile.png");

    /**
     * 面片边长（格）。
     *
     * <p>取 0.4：实体碰撞箱是 0.25（见 {@link com.guardianprotocol.entity.ModEntities}），
     * 面片比碰撞箱略大一圈，视觉上才有「一团能量」的体量；再大就会盖住棋子或目标。
     * 贴图本身四边是透明的，所以实际观感还会再小一点。</p>
     */
    private static final float SIZE = 0.4F;

    /** 半边长（格）。几个顶点都从它派生，避免手写四组 ±0.2 抄错一个符号。 */
    private static final float HALF = SIZE * 0.5F;

    /**
     * 面片所在的 z 平面（相对实体原点）。
     *
     * <p>0.0 = 正好穿过实体中心。因为这个面片跟随相机旋转、永远正对镜头，
     * 它<b>不会</b>和方块表面产生 z-fighting（那是棋子那种「贴地平面」才要担心的事）。</p>
     */
    private static final float PLANE_Z = 0.0F;

    public PawnProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
        // 弹体悬在空中，脚下一圈阴影只会变成「一团跟在弹后面的黑斑」—— 关掉。
        this.shadowRadius = 0.0F;
        this.shadowStrength = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(PawnProjectile entity) {
        // 目前所有职业共用一张能量弹贴图；按职业换图/染色的入口留在 getUnitClass() 上，
        // 需要时在这里分派即可（客户端拿得到，它是同步字段）。
        return TEXTURE;
    }

    @Override
    public void render(PawnProjectile entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        // 父类的 render 会处理命名牌等通用表现（弹体没有名字，但保持与棋子同一结构）
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        Camera camera = this.entityRenderDispatcher.camera;
        if (camera == null) {
            // 理论上不会发生（渲染时相机必定存在），但防御一下总比 NPE 崩客户端强
            return;
        }

        // 世界光照：借原版算法把方块光与天空光打包成一个 int ——
        // 「受光照」就是说这里必须用世界光照，而不是恒定满亮（LightTexture.FULL_BRIGHT）。
        BlockPos lightPos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
        int light = LightTexture.pack(
                this.getBlockLightLevel(entity, lightPos),
                this.getSkyLightLevel(entity, lightPos));

        Vec3 cameraPos = camera.getPosition();

        poseStack.pushPose();

        // 把面片中心挪到实体中心（实体原点在脚底，所以向上半个身高）
        poseStack.translate(0.0D, entity.getBbHeight() * 0.5D, 0.0D);

        // ① 绕 Y 轴正对相机（公式与 PixelUnitRenderer 相同）
        float yawToCamera = (float) Mth.atan2(
                cameraPos.z() - entity.getZ(),
                cameraPos.x() - entity.getX()) * (180F / (float) Math.PI) - 90.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-yawToCamera));
        // ② 绕 X 轴抵消相机俯仰
        poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

        // ③ 半径随 tick 呼吸（±6%）：静止的球看起来像「卡住了」，
        //    一点点脉动就能读成「能量在流动」。幅度刻意小 —— 弹体飞得快，
        //    脉动太大反而会让人觉得它在抖。
        float pulse = 1.0F + 0.06F * Mth.sin((entity.tickCount + partialTick) * 0.6F);
        poseStack.scale(pulse, pulse, pulse);

        // ④ 半透明渲染类型：弹体要有「发光」的透亮感（贴图自带 alpha 渐隐）。
        //    注意 entityTranslucent 是原版现成类型，不是我们自定义的 RenderType。
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();

        // ⑤ 四个顶点（从相机看：左上 → 右上 → 右下 → 左下）。
        //    UV 口径与棋子一致：v=0 是贴图文件顶部。
        vertex(consumer, matrix, normal, -HALF, HALF, 0.0F, 0.0F, light);
        vertex(consumer, matrix, normal, HALF, HALF, 1.0F, 0.0F, light);
        vertex(consumer, matrix, normal, HALF, -HALF, 1.0F, 1.0F, light);
        vertex(consumer, matrix, normal, -HALF, -HALF, 0.0F, 1.0F, light);

        poseStack.popPose();
    }

    /**
     * 输出一个顶点。
     *
     * @param x     面片局部坐标 x（相对实体中心）
     * @param y     面片局部坐标 y（相对实体中心）
     * @param u     贴图横坐标 0~1
     * @param v     贴图纵坐标 0~1（0 = 贴图文件顶部）
     * @param light 打包后的世界光照
     */
    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float u, float v, int light) {
        consumer.vertex(matrix, x, y, PLANE_Z)
                // 颜色全 1：白底 × 贴图本色，不额外染色（按职业染色在这里改）
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                // 法线朝向 +Z（面片正面）；公告板已经正对相机，这个法线够用
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
