package com.guardianprotocol.api;

import com.guardianprotocol.GuardianProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * 「生成式入侵」的公开入口（下游只碰 {@code api} 包，见 设计说明）。
 *
 * <h3>本项目的边界（设计约定）</h3>
 * <p>本项目负责：<b>相位推进</b>（备战→战斗→协防→奖励→BOSS→隐藏挑战）、<b>按生物行策略生成怪</b>、
 * <b>按支路记账</b>（生成了几只 / 杀了几只 / 漏了几只）。</p>
 *
 * <p>本项目<b>不做</b>：<b>总生命值</b>与<b>支路生命值</b>的实际扣减 —— 那属于后续塔防循环项目
 * （设计原话：「总生命值上限在后续项目中实现，不在此项目内，仅需保留 API 使后续可调用即可」）。
 * 所以这里给的是<b>回调</b>：本 mod 在「某条支路漏怪」与「整场超时」时喊一声，
 * 后续项目在自己的监听器里扣血、判负、播提示。</p>
 *
 * <h3>为什么用回调而不是自己存生命值</h3>
 * <p>生命值是「关卡」的概念，不是「刷怪装置」的概念：同一套出怪点可以被不同的关卡复用，
 * 关卡规则（几滴血、支路独立还是共享、扣多少）也该由关卡那一层决定。
 * 本 mod 只管把「发生了漏怪」这件事<b>如实、及时</b>地交出去。</p>
 */
public final class Invasions {

    /**
     * 漏怪 / 超时的监听器（由后续塔防循环项目实现并注册）。
     *
     * <p>两个回调都在<b>服务端主线程</b>触发；实现方不要在里面做重活，
     * 更不要抛异常 —— 抛出去会牵连整个服务端 tick（本 mod 的调用点会 catch 并记日志，
     * 但那只保证「不炸」，不保证「你的逻辑跑完了」）。</p>
     */
    public interface LifeListener {

        /**
         * 某条支路漏掉了 {@code leaked} 只怪（怪走进了该支路的保护目标）。
         *
         * @param level      维度
         * @param laneTarget 该支路的保护目标方块坐标（**一条支路 = 一个保护目标**，设计口径）
         * @param leaked     本次漏掉的数量
         */
        void onLaneLeak(ServerLevel level, BlockPos laneTarget, int leaked);

        /**
         * 整场超时漏怪（单人组别「战斗时间到但没清完」）。
         *
         * @param level    维度
         * @param invasion 触发这场入侵的方块坐标
         * @param leaked   超时时仍在场（视为漏掉）的数量
         */
        void onTimeout(ServerLevel level, BlockPos invasion, int leaked);
    }

    /** 当前监听器；{@code null} = 没人接（那是完全正常的：单机玩法不需要生命值）。 */
    @Nullable
    private static volatile LifeListener lifeListener;

    private Invasions() {
    }

    /**
     * 注册监听器（返回旧值便于还原）。
     *
     * <p>传 {@code null} = 注销。注册时机随意（服务端运行时），下一个事件即生效 —— 字段是 volatile 的。</p>
     */
    @Nullable
    public static LifeListener setLifeListener(@Nullable LifeListener listener) {
        LifeListener old = lifeListener;
        lifeListener = listener;
        return old;
    }

    /** 当前监听器（可能为 null）。 */
    @Nullable
    public static LifeListener lifeListener() {
        return lifeListener;
    }

    /**
     * 分发「某条支路漏怪」。
     *
     * <p>没有监听器时只记一行 debug 日志：那不代表出错（单机就是没人关心生命值），
     * 但排查「为什么没扣血」时需要能看出「事件确实发出来了，只是没人接」。</p>
     */
    public static void notifyLaneLeak(ServerLevel level, BlockPos laneTarget, int leaked) {
        LifeListener listener = lifeListener;
        if (listener == null) {
            GuardianProtocol.LOGGER.debug("[{}] 支路漏怪 {} 只（{}），但没有注册 LifeListener。",
                    GuardianProtocol.MODID, leaked, laneTarget.toShortString());
            return;
        }
        try {
            listener.onLaneLeak(level, laneTarget, leaked);
        } catch (Exception ex) {
            // 监听器是外部代码（后续项目）：它出错不能拖垮本 mod 的相位机
            GuardianProtocol.LOGGER.error("[{}] LifeListener.onLaneLeak 抛异常，已吞掉（支路 {}，漏 {} 只）",
                    GuardianProtocol.MODID, laneTarget.toShortString(), leaked, ex);
        }
    }

    /** 分发「整场超时漏怪」。语义与异常处理同 {@link #notifyLaneLeak}。 */
    public static void notifyTimeout(ServerLevel level, BlockPos invasion, int leaked) {
        LifeListener listener = lifeListener;
        if (listener == null) {
            GuardianProtocol.LOGGER.debug("[{}] 入侵超时漏怪 {} 只（{}），但没有注册 LifeListener。",
                    GuardianProtocol.MODID, leaked, invasion.toShortString());
            return;
        }
        try {
            listener.onTimeout(level, invasion, leaked);
        } catch (Exception ex) {
            GuardianProtocol.LOGGER.error("[{}] LifeListener.onTimeout 抛异常，已吞掉（{}，漏 {} 只）",
                    GuardianProtocol.MODID, invasion.toShortString(), leaked, ex);
        }
    }
}
