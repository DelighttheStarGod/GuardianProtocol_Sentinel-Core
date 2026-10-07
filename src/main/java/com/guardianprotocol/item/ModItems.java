package com.guardianprotocol.item;

import com.guardianprotocol.GuardianConfig;
import com.guardianprotocol.GuardianProtocol;
import com.guardianprotocol.PawnFacingMode;
import com.guardianprotocol.data.PieceFacing;
import com.guardianprotocol.data.UnitBranch;
import com.guardianprotocol.entity.ModEntities;
import com.guardianprotocol.entity.PixelUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 物品注册表。
 *
 * <p>两类物品：</p>
 * <ul>
 *     <li><b>保护目标</b>方块物品 —— 塔防的失守条件（见 {@code block} 包）</li>
 *     <li><b>每个职业分支一个刷怪蛋</b> —— 用来摆放「棋子」。第一阶段是
 *         8 大职业各 1 个代表分支，与 {@link UnitBranch} 一一对应</li>
 * </ul>
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, GuardianProtocol.MODID);

    /** 标记职业所用的 NBT 键。 */
    public static final String TAG_UNIT_CLASS = "UnitClass";

    /** 标记分支所用的 NBT 键，必须与 {@code PixelUnit.addAdditionalSaveData} 的键名一致。 */
    public static final String TAG_BRANCH = "Branch";

    /** 保护目标方块的物品形式（放下方块用）。 */
    public static final RegistryObject<Item> PROTECT_TARGET =
            ITEMS.register("protect_target",
                    () -> new BlockItem(
                            com.guardianprotocol.block.ModBlocks.PROTECT_TARGET.get(),
                            new Item.Properties()));

    /** 出怪点方块的物品形式（放下方块用）。 */
    public static final RegistryObject<Item> SPAWN_POINT =
            ITEMS.register("spawn_point",
                    () -> new BlockItem(
                            com.guardianprotocol.block.ModBlocks.SPAWN_POINT.get(),
                            new Item.Properties()));

    /**
     * <b>出怪位置标定器</b>（设计口径）。
     *
     * <p>出怪点方块只当<b>配置器</b>，真正的出怪位置由这件物品标出来：
     * 先右键出怪点绑定（**只能绑一个、不能重复绑定**），再右键地面标记位置
     * （最少 1 个、最多 {@code InvasionConfig.MAX_POSITIONS}=4 个），潜行右键清空。
     * 详见 {@link SpawnMarkerItem} 的类注释。</p>
     *
     * <p>{@code stacksTo(1)}：它身上带着「绑定了哪块出怪点 + 标了哪几个位置」，
     * 堆叠会让两件不同的标定器挤在同一个槽里共用一份 NBT —— 那是必然出错的设计。</p>
     */
    public static final RegistryObject<Item> SPAWN_MARKER =
            ITEMS.register("spawn_marker",
                    () -> new SpawnMarkerItem(new Item.Properties().stacksTo(1)));

    /** 分支 -> 刷怪蛋物品。用 LinkedHashMap 保证创造栏里的顺序与枚举一致。 */
    private static final Map<UnitBranch, RegistryObject<Item>> PAWN_EGGS;

    static {
        Map<UnitBranch, RegistryObject<Item>> map = new LinkedHashMap<>();
        for (UnitBranch branch : UnitBranch.values()) {
            // 物品 id 用分支枚举名的小写形式，例如 vanguard_charger_spawn_egg。
            // 不用中文：Forge 资源命名空间只允许 [a-z0-9_/. -]。
            RegistryObject<Item> egg = ITEMS.register(eggId(branch), () -> buildEgg(branch));
            map.put(branch, egg);
        }
        PAWN_EGGS = Collections.unmodifiableMap(map);
    }

    private ModItems() {
    }

    /** 由分支算物品 id，例如 {@code vanguard_charger_spawn_egg}。 */
    private static String eggId(UnitBranch branch) {
        return branch.name().toLowerCase(Locale.ROOT) + "_spawn_egg";
    }

    private static Item buildEgg(UnitBranch branch) {
        return new PawnSpawnEggItem(ModEntities.PIXEL_UNIT, branch,
                branch.unitClass().primaryColorForEgg(),
                branch.unitClass().secondaryColorForEgg(),
                new Item.Properties());
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    /** 把保护目标、出怪点与各分支刷怪蛋依次塞进本 mod 创造模式标签页。 */
    public static void addToTab(CreativeModeTab.Output output) {
        // 保护目标放最前：它是塔防玩法的第一步（先把要守的东西放下来）。
        output.accept(PROTECT_TARGET.get());
        // 出怪点紧随其后：第二步是标出敌人从哪来。
        output.accept(SPAWN_POINT.get());
        // ★ 2026-10 实测反馈「没找到新建的那个物品」：物品注册了、但**没进创造标签页** ——
        //   标签页是按 addToTab 里逐个 accept 出来的，注册不会自动出现。
        //   凡是新物品，注册完必须在这里补一行（这就是那条漏掉的一行）。
        output.accept(SPAWN_MARKER.get());
        for (RegistryObject<Item> egg : PAWN_EGGS.values()) {
            output.accept(egg.get());
        }
    }

    /** 便于外部（例如指挥终端 UI）按分支取对应刷怪蛋。 */
    public static RegistryObject<Item> eggOf(UnitBranch branch) {
        return PAWN_EGGS.get(branch);
    }

    /**
     * 棋子刷怪蛋：放出来的棋子必定是指定分支。
     *
     * <p>继承 Forge 的 {@link ForgeSpawnEggItem}：它持有 {@code EntityType} 的
     * <b>延迟引用</b>（Supplier），不会在物品注册阶段就强制加载实体类型。</p>
     *
     * <p><b>实现要点</b>：Forge 47.x 的 {@code ForgeSpawnEggItem} <b>没有</b>
     * 「生成实体后回调」的钩子，所以用两条互补的路径保证分支正确：</p>
     * <ol>
     *     <li>把分支名写进<b>物品 NBT</b>。{@code SpawnEggItem.useOn} 会把整份 NBT
     *         交给新实体（{@code EntityType.loadEntityRecursive} → {@code entity.load}），
     *         实体的 {@code readAdditionalSaveData} 便读到分支并把自己标记为「已指定」。
     *         这条路径同时让发射器等其它生成方式自动正确。</li>
     *     <li>生成后再扫一次点击位置附近的棋子兜底修正，完全不依赖父类内部实现。
     *         <b>朝向也在这条路径里定</b>（见 {@link #applyPlacementToFreshUnits}）。</li>
     * </ol>
     */
    private static final class PawnSpawnEggItem extends ForgeSpawnEggItem {

        /** 兜底扫描半径（格）。一次右键只生成一只，2 格足够精确。 */
        private static final double FALLBACK_SCAN_RADIUS = 2.0D;

        private final UnitBranch branch;

        PawnSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> type, UnitBranch branch,
                         int primaryColor, int secondaryColor, Item.Properties properties) {
            super(type, primaryColor, secondaryColor, properties);
            this.branch = branch;
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            // 路径一：把分支写进物品 NBT，交给父类去生成。
            CompoundTag tag = context.getItemInHand().getOrCreateTag();
            tag.putString(TAG_BRANCH, this.branch.name());
            tag.putString(TAG_UNIT_CLASS, this.branch.unitClass().spriteId());
            // ★ 归属（有主，设计口径）：把摆放者写进物品 NBT —— 与分支走**同一条路**，
            //   新实体在 readAdditionalSaveData 里读到它、自动入队（见 combat/PawnTeams）。
            //   玩家为 null（发射器 / 命令生成）时不写：那种棋子就是「无主」。
            if (context.getPlayer() != null) {
                tag.putString(PixelUnit.TAG_OWNER, context.getPlayer().getUUID().toString());
                tag.putString(PixelUnit.TAG_OWNER_NAME, context.getPlayer().getName().getString());
            }

            InteractionResult result = super.useOn(context);

            // 路径二：生成已完成，扫一遍附近的棋子做兜底修正（分支 + 朝向）。
            if (result.consumesAction()) {
                applyPlacementToFreshUnits(context);
            }
            return result;
        }

        /**
         * 把分支与朝向补写到「刚刚由本次使用生成出来的」棋子上。
         *
         * <p>判定条件：{@code tickCount <= 1}（刚生成，还没活过一个 tick）
         * 且分支未指定 —— 二者合起来足以唯一锁定本次新生成的棋子。</p>
         *
         * <h3>朝向为什么在这里定，而不是在 {@code finalizeSpawn} 里</h3>
         * <p>方案2 需要「放置瞬间玩家所面向的方向」，而玩家是在
         * {@code EntityType.spawn(level, stack, player, ...)} 里传进去的，
         * <b>并不会</b>出现在 {@code finalizeSpawn} 的参数里。原版生成路径又会用
         * {@code Direction.getNearest(...)} 按玩家朝向「镜像」实体自身的 yaw，
         * 那个值不能直接拿来当棋子的东南西北。所以这里用唯一可靠的来源：
         * {@link UseOnContext#getPlayer()} 的 yaw，由 {@code PieceFacing.fromYaw} 吸附到四方向。</p>
         */
        private void applyPlacementToFreshUnits(UseOnContext context) {
            if (!(context.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }
            Player player = context.getPlayer();
            // 方案1：统一朝正东，与玩家站位无关；方案2：按玩家面向。
            // 两种情况下实体自身的默认朝向都已经是正东（见 PixelUnit.defineSynchedData），
            // 所以方案1 不需要额外做什么。
            boolean followPlayer = GuardianConfig.pawnFacingMode() == PawnFacingMode.FOLLOW_PLAYER;
            PieceFacing placed = (followPlayer && player != null)
                    ? PieceFacing.fromYaw(player.getYRot())
                    : PieceFacing.EAST;

            AABB area = new AABB(context.getClickedPos()).inflate(FALLBACK_SCAN_RADIUS);
            for (PixelUnit unit : serverLevel.getEntitiesOfClass(PixelUnit.class, area)) {
                if (unit.tickCount <= 1 && !unit.isClassAssigned()) {
                    unit.assign(this.branch);
                    // 老存档可能带上别人的朝向，这里对新放置的棋子一律重新定朝向
                    unit.setFacing(placed);
                }
            }
        }
    }
}
