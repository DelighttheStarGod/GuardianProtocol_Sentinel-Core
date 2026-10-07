package com.guardianprotocol;

import com.guardianprotocol.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本 mod 的创造模式标签页。
 *
 * <p>把 8 个职业刷怪蛋收进一个自己的页签，方便测试时快速取用。</p>
 *
 * <h3>★ 预留：干员棋子要单独的页签（设计口径，属**后续项目**）</h3>
 * <p>设计要求「棋子会新建一个标签页，用以和模板分开」——也就是**模板页**（本页签：
 * 72 个分支刷怪蛋 + 工具）与**干员页**分开。本项目**不做**，只把这个口子写在这里：</p>
 * <ul>
 *     <li>新页签在这里再加一个 {@code CREATIVE_MODE_TABS.register(...)}（<b>页签本身必须启动期注册</b>）；</li>
 *     <li>但<b>页签内容可以动态</b>：{@code displayItems} 里给的是 {@code ItemStack} 列表，
 *         可以在重建时按导入的干员生成多个条目（原版刷怪蛋就是这么做的：一个物品 + 每个蛋一份数据）；</li>
 *     <li><b>不能</b>「一个干员一个物品」——物品与实体类型在启动期注册完毕，运行期加不了。</li>
 * </ul>
 * <p>细节与硬约束见 {@code 设计文档} 的「干员导入」一节。</p>
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GuardianProtocol.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN_TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.guardian_protocol.main"))
                    // 图标用「冲锋手」棋子：它是最能代表「可部署单位」的那一个。
                    .icon(() -> new ItemStack(
                            ModItems.eggOf(com.guardianprotocol.data.UnitBranch.VANGUARD_CHARGER).get()))
                    .displayItems((parameters, output) -> ModItems.addToTab(output))
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
