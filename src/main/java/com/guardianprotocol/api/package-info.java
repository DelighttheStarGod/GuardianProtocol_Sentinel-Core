/**
 * 卫戍协议 core 的<b>对外契约层</b> —— 后续项目（塔防循环 / 商店买卖 / 羁绊 / 角色导入）
 * 只应该 import 这个包。
 *
 * <h2>这个包里有什么</h2>
 * <ul>
 *     <li>{@link com.guardianprotocol.api.GuardianCore}：版本、API 版本、使用约定；</li>
 *     <li>{@link com.guardianprotocol.api.Branches}：职业模板查询（含数据包分支）；</li>
 *     <li>{@link com.guardianprotocol.api.Pawns}：摆放棋子、读场上棋子与它的状态；</li>
 *     <li>{@link com.guardianprotocol.api.ProtectTargets}：保护目标（伤害 / 血量 / 嘲讽半径）；</li>
 *     <li>{@link com.guardianprotocol.api.SpawnPoints}：出怪点与「按定义生成一批敌人」。</li>
 *     <li>{@link com.guardianprotocol.api.Talents}：分支的**两个天赋槽位**（天赋名 / 天赋效果 /
 *         天赋作用对象）。★ <b>本工程只解析、不执行</b>，而且设计口径
 *         「<b>天赋不填</b>」⇒ <b>72 个分支的两个槽现在全是空的</b>，等干员导入脚本按玩家的
 *         导入表填。</li>
 *     <li>{@link com.guardianprotocol.api.Modules}：分支的**模组待定槽**（同天赋的三个字段）。
 *         ★ 设计口径：「模组只需要像天赋一样留一个待定槽即可」⇒ 现在也是空槽位；
 *         原先默认生效的 5 条模组伤害乘区已摘出（留档在 {@code branch_meta.py}）。</li>
 * </ul>
 *
 * <h2>三条约定</h2>
 * <ol>
 *     <li><b>这一层只转发，不含新逻辑</b>：这样内部包以后怎么重构都不影响下游。</li>
 *     <li><b>只加不改</b>：破坏性改动要在 设计说明写明迁移方式。
 *         ★ <b>但版本号不因此抬</b> —— 设计口径 定的口径：
 *         <b>项目「未完成」期间所有改动都算 0.1.0</b>（{@code API_VERSION} 保持 {@code "0.1"}）；
 *         「破坏性改动 ⇒ 抬 {@link com.guardianprotocol.api.GuardianCore#API_VERSION}」
 *         这条要等项目正式发版之后才生效。</li>
 *     <li><b>这里没有的东西，先往这里加</b>：不要绕过它去伸手进 {@code combat/}、{@code client/}
 *         或生成物 —— 那些东西没有兼容承诺。</li>
 * </ol>
 *
 * <p>完整的边界表（谁能做什么、后续项目各自负责什么）在项目 设计说明。</p>
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.guardianprotocol.api;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
