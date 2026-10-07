package com.guardianprotocol.entity;

import com.guardianprotocol.GuardianProtocol;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实体注册表。
 *
 * <p>第一阶段只注册一个实体类型 {@code guardian_protocol:pixel_unit}：
 * 8 个职业共用同一个 EntityType，靠同步数据里的 {@link UnitClass} 区分外观。
 * 这样做的直接好处是——</p>
 * <ul>
 *     <li>符合设计文档「角色模型复用，同角色不同阶位共用模型，换色区分」的负载控制思路；</li>
 *     <li>新增一个角色不需要新增实体类型，也不会污染注册表；</li>
 *     <li>刷怪蛋、渲染器、属性都只写一份。</li>
 * </ul>
 */
public final class ModEntities {

    /** 实体注册表。必须注册到 mod 事件总线（见 GuardianProtocol 构造器）。 */
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, GuardianProtocol.MODID);

    /**
     * 2D 像素小人单位。
     *
     * <p><b>sized(0.6F, 1.8F)：宽度 0.6（纸片感）、高度 1.8（与玩家一致）。</b>
     * ★ 曾经试过把尺寸放大到 <b>2.4 × 1.8</b>，想让「被挡住的怪够得着棋子」，<b>已回退</b>：
     * 按原版 reach 判据旧尺寸本来就近得够（僵尸 0.6 宽 ⇒ reach = √(1.44 + 0.6) = 1.4283 &gt; 锚点 1.3），
     * 放大只是把停靠点从 1.3 挪到 1.5，还牵出三个真缺陷 ——「放行者绕不过去」（侧步前量没随宽度缩放）、
     * 「已经重叠的实体会穿模」、「贴图仍是 0.6 宽的纸片，盒子和贴图对不上」（踩坑记录.3.7）。</p>
     *
     * <p>尺寸同时是<b>碰撞盒与受击盒</b>：{@code Entity#getBbWidth()} 是 {@code final}，
     * 只来自 {@code EntityType.Builder#sized(...)} 或 {@code getDimensions(Pose)}，没有
     * 「只放大受击盒」的钩子 —— 这是口径，不是副作用。</p>
     *
     * <p>clientTrackingRange(10)：单位是战术资产，追踪范围给足，避免远处看不见。</p>
     */
    public static final RegistryObject<EntityType<PixelUnit>> PIXEL_UNIT =
            ENTITY_TYPES.register("pixel_unit", () -> EntityType.Builder
                    .of(PixelUnit::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(GuardianProtocol.MODID + ":pixel_unit"));

    /**
     * 棋子投掷物（远程棋子常态攻击的弹体）。
     *
     * <p>sized(0.25F, 0.25F)：弹体只是一颗小弹丸，碰撞箱给到 0.25 就够 ——
     * 命中判定<b>不看</b>它（用的是 {@code ProjectileFlight.HIT_RADIUS}），
     * 这个尺寸只影响「客户端是否把它算进渲染与拾取范围」。</p>
     *
     * <p>MobCategory.MISC：它不是生物，不参与刷怪上限，也不该被当成生物统计。
     * clientTrackingRange 给 10（与棋子同档）：1.2 格/tick 飞得很快，
     * 追踪范围小了客户端会「弹体突然出现在面前」。</p>
     *
     * <p><b>它没有也没必要有属性</b>：{@code EntityAttributeCreationEvent} 里
     * <b>不要</b>登记它（漏了会报「no attributes」，但多登记一个非 Mob 实体同样没意义）。</p>
     */
    public static final RegistryObject<EntityType<PawnProjectile>> PAWN_PROJECTILE =
            ENTITY_TYPES.register("pawn_projectile", () -> EntityType.Builder
                    .of(PawnProjectile::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(10)
                    .build(GuardianProtocol.MODID + ":pawn_projectile"));

    /** 供主类统计用；同时也是「去重后的实体类型表」。 */
    public static final Map<String, RegistryObject<EntityType<PixelUnit>>> UNIT_TYPES;

    static {
        Map<String, RegistryObject<EntityType<PixelUnit>> > map = new LinkedHashMap<>();
        map.put("pixel_unit", PIXEL_UNIT);
        UNIT_TYPES = Collections.unmodifiableMap(map);
    }

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }

    /**
     * 把单位属性挂到刚注册的实体类型上。
     *
     * <p>这个方法由 {@code ModEvents} 在 {@link EntityAttributeCreationEvent} 上调用。
     * 注意：这个事件是 mod 事件总线上的事件，且**必须**在实体注册完成之后触发，
     * 所以不能写在静态块里。</p>
     */
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PIXEL_UNIT.get(), PixelUnit.createAttributes().build());
    }
}
