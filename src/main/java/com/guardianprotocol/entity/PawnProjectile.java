package com.guardianprotocol.entity;

import com.guardianprotocol.combat.PawnCombatManager;
import com.guardianprotocol.combat.ProjectileFlight;
import com.guardianprotocol.data.UnitClass;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 棋子投掷物（远程棋子的常态攻击弹体）。
 *
 * <h3>它是什么 / 不是什么</h3>
 * <p>它<b>不是</b>生物：没有 AI、没有血量、没有属性（{@code EntityAttributeCreationEvent} 里
 * <b>不要</b>登记它），也不会被 {@code /kill} 以外的东西打掉。它就是一颗「追踪弹」——
 * 存在期间每 tick 朝目标挪一小步，碰到目标就把伤害交给
 * {@link PawnCombatManager#applyHit}（近战与投掷物共用的<b>唯一</b>结算入口）。</p>
 *
 * <h3>弹道数学不在本类里</h3>
 * <p>出生点、每 tick 步长、命中半径、拖尾取样全部来自 {@link ProjectileFlight}（纯数据）。
 * 本类<b>一行三角函数都不写</b>：那样就等于把同一套算术抄第二份，
 * 而抄出来的那一份离线自验（离线核对工具）验不到（同 踩坑记录）。
 * 所以这里只做三件事：<b>取位置 → 问 ProjectileFlight → 落地生效</b>。</p>
 *
 * <h3>为什么无视重力、也不做方块碰撞</h3>
 * <ul>
 *     <li>{@code setNoGravity(true)} + {@code noPhysics = true}：弹体不参与方块碰撞，
 *         所以它<b>不会撞墙消失</b>，也不会在半路掉到地上。设计口径是「无视重力、
 *         每 tick 速度完全由追踪方向决定」，<b>不叠加任何 y 下坠</b>；</li>
 *     <li>也正因为不碰方块，本类<b>不能</b>用 {@code move()} 位移（那会触发碰撞与卡位），
 *         见 {@link #tick()} 里的说明。</li>
 * </ul>
 *
 * <h3>同步什么、不同步什么</h3>
 * <p>只同步「职业序号」一件事（客户端渲染器要按它选贴图/染色）。
 * 主人与目标都<b>不</b>同步：它们是服务端的实体引用，客户端拿不到也不需要
 * （位置由原版追踪包下发，客户端只负责把公告板画在收到的地方）。</p>
 */
public class PawnProjectile extends Projectile {

    /**
     * 同步：职业序号（{@link UnitClass#ordinal()}），{@value #UNKNOWN_UNIT_CLASS} 表示未知。
     *
     * <p><b>为什么同步</b>：渲染器在客户端跑，它只能看同步数据。不同步的话，
     * 客户端就只能画一张「没有职业归属」的贴图 —— 而贴图/染色本来是按职业分的。</p>
     *
     * <p><b>为什么用 int + -1 兜底</b>：与 {@code PixelUnit} 的 DATA_UNIT_CLASS 同一口径，
     * 且 {@link UnitClass#byOrdinal(int)} 越界返回 null、不抛异常 ——
     * 存档/跨版本数据坏掉时最坏也只是「颜色不对」，不会崩客户端。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_UNIT_CLASS =
            SynchedEntityData.defineId(PawnProjectile.class, EntityDataSerializers.INT);

    /** 职业未知时的序号（必须 < 0：{@code UnitClass.byOrdinal} 会把它判成 null）。 */
    private static final int UNKNOWN_UNIT_CLASS = -1;

    /**
     * 同步：伤害值（float）。
     *
     * <p><b>为什么伤害也要同步</b>：命中结算在服务端做，那一步其实不需要客户端知道伤害。
     * 同步它的理由是<b>表现</b>：将来要按伤害大小调弹体尺寸/亮度（比如精英棋子的大弹）
     * 时，客户端手上有数；顺手同步一个 float 的代价可以忽略。</p>
     */
    private static final EntityDataAccessor<Float> DATA_DAMAGE =
            SynchedEntityData.defineId(PawnProjectile.class, EntityDataSerializers.FLOAT);

    /**
     * 同步：这一发是不是**治疗弹**（医疗职业的出手）。
     *
     * <p>用同步数据而不是 NBT：与伤害、职业同一套口径 —— NBT 只管存档，
     * 两端各自构建实体时由同步包把权威值下发（踩坑记录的反面教材就是两头都写）。
     * 命中时按这个标志决定走 {@code applyHit}（伤害）还是 {@code applyHeal}（治疗）。</p>
     */
    private static final EntityDataAccessor<Boolean> DATA_HEALING =
            SynchedEntityData.defineId(PawnProjectile.class, EntityDataSerializers.BOOLEAN);

    /**
     * 存档：伤害与目标 UUID 的 NBT 键。
     *
     * <p>键名照主代理冻结的口径（{@code Damage} / {@code Target}）。
     * 目标存 UUID 而不是实体 id：实体 id 只在单次运行内有效，跨存档会串到别的实体上。
     * 主人不用另存 —— {@link Projectile} 自己已经把 {@code ownerUUID} 写进 NBT 了。</p>
     */
    public static final String TAG_DAMAGE = "Damage";
    public static final String TAG_TARGET = "Target";

    /**
     * 追踪目标（服务端引用，不存档、不同步）。
     *
     * <p>平时为 null，目标失效时被清掉；用 {@link #getTargetEntity()} 取（带失效检查）。</p>
     */
    @Nullable
    private LivingEntity target;

    public PawnProjectile(EntityType<? extends PawnProjectile> type, Level level) {
        super(type, level);
        // ① 无视重力：不叠加任何 y 下坠（坑：加了下坠就变成「抛物线」，打不到移动目标）
        this.setNoGravity(true);
        // ② 不做方块碰撞：弹体不会撞墙消失，也不会被方块挤住
        this.noPhysics = true;
        // ★ 这里**不写** setPersistenceRequired()：那个方法是 Mob 上的（距离反生成只对 Mob 生效），
        //   Entity / Projectile 没有它 —— 写了编译不过「找不到符号」。弹体不是 Mob，
        //   本来就不会被「离玩家太远」那套回收；它的寿命由 ProjectileFlight.LIFETIME_TICKS 兜底。
    }

    // ------------------------------------------------------------------
    // 同步数据
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData() {
        // ★ 这里**不能**调 super.defineSynchedData()：1.20.1 里 Entity#defineSynchedData 是
        //   **抽象方法**，而 Projectile 没有实现它 —— 写 super 会得到
        //   「无法直接访问 Entity 中的抽象方法 defineSynchedData()」（实测编译错误）。
        //   ★ 潜在风险记在这里：将来若某个父类开始定义同步字段（例如把主人 UUID 改成同步的），
        //     本类这两个字段的序号会与它冲突，届时必须补上 super 调用并重新排号。
        this.entityData.define(DATA_UNIT_CLASS, UNKNOWN_UNIT_CLASS);
        this.entityData.define(DATA_DAMAGE, 0.0F);
        this.entityData.define(DATA_HEALING, false);
    }

    /** 已飞 tick 数（自验与排查用）。直接就是原版的 {@code tickCount}，不另立字段。 */
    public int getLifeTicks() {
        return this.tickCount;
    }

    public float getDamage() {
        return this.entityData.get(DATA_DAMAGE);
    }

    /** 这一发是治疗弹吗（医疗职业的出手）。 */
    public boolean isHealing() {
        return this.entityData.get(DATA_HEALING);
    }

    public void setHealing(boolean healing) {
        this.entityData.set(DATA_HEALING, healing);
    }

    public void setDamage(float damage) {
        this.entityData.set(DATA_DAMAGE, damage);
    }

    /**
     * 同步给客户端的职业序号；未知返回 null（渲染器按 null 走中性色）。
     *
     * <p>注意这里<b>不</b>像 {@code PixelUnit#getUnitClass()} 那样兜底成某个职业：
     * 那个兜底是为了「棋子一定有贴图」，而弹体没有这个约束 ——
     * 未知就是未知，交给渲染器决定画什么，别硬塞一个职业进去。</p>
     */
    @Nullable
    public UnitClass getUnitClass() {
        return UnitClass.byOrdinal(this.entityData.get(DATA_UNIT_CLASS));
    }

    /** 写职业序号（只在服务端调；客户端那份由同步包下发）。 */
    public void setUnitClass(@Nullable UnitClass unitClass) {
        this.entityData.set(DATA_UNIT_CLASS,
                unitClass == null ? UNKNOWN_UNIT_CLASS : unitClass.ordinal());
    }

    /**
     * 开火：定位到出生点、绑定主人与目标、写入伤害、加入世界，并返回它。
     *
     * <p><b>只在服务端调用</b>（签名收的是 {@link ServerLevel}，编译期就挡掉了客户端误用）。</p>
     *
     * <p>出生点由 {@link ProjectileFlight#spawnPoint} 算：棋子的位置 + <b>朝向</b>正前方
     * 一小段距离、抬到胸口高度 —— 这样弹体不会一出生就盖在棋子自己身上。</p>
     *
     * <p>出生点那一圈粒子是<b>枪口闪光</b>：只有一发弹、飞得又快，没有这圈光效的话
     * 玩家会看不清「谁打出来的」。</p>
     */
    public static PawnProjectile fire(ServerLevel level, PixelUnit owner, LivingEntity target,
                                      float damage) {
        return fire(level, owner, target, damage, false);
    }

    /**
     * 同上，但指定这一发是**治疗弹**（医疗职业的出手）。
     *
     * <p>治疗弹与伤害弹共用飞行、追踪、消散的全部代码，唯一区别是命中时调哪个结算入口 ——
     * 这样「远程出手不是瞬时结算」这条已经自验过的性质，对治疗同样成立。</p>
     */
    public static PawnProjectile fire(ServerLevel level, PixelUnit owner, LivingEntity target,
                                      float damage, boolean healing) {
        PawnProjectile projectile = new PawnProjectile(ModEntities.PAWN_PROJECTILE.get(), level);
        double[] spawn = ProjectileFlight.spawnPoint(
                owner.getX(), owner.getY(), owner.getZ(), owner.getFacing());
        projectile.setPos(spawn[0], spawn[1], spawn[2]);
        projectile.setOwner(owner);
        projectile.setTarget(target);
        projectile.setDamage(damage);
        projectile.setHealing(healing);
        // 职业只影响表现（贴图/染色），同步过去让客户端画得对
        projectile.setUnitClass(owner.getUnitClass());
        level.addFreshEntity(projectile);

        // 枪口闪光：一小圈 END_ROD。速度给 0 —— 这是「闪光」不是「喷射」，
        // 给了速度反而会拖出一条不自然的线。
        level.sendParticles(ParticleTypes.END_ROD,
                spawn[0], spawn[1], spawn[2], 4, 0.08D, 0.08D, 0.08D, 0.0D);
        return projectile;
    }

    // ------------------------------------------------------------------
    // 每 tick
    // ------------------------------------------------------------------

    /**
     * 追踪 + 命中 + 拖尾。
     *
     * <p><b>顺序为什么是「先判命中、再移动」</b>：{@link ProjectileFlight#step} 已经把单 tick
     * 步长夹到「不超过剩余距离」，而命中判定用的是<b>移动前</b>的剩余向量 ——
     * 这样距离单调减小，不可能出现「一步跨过目标却没打中」。</p>
     *
     * <p><b>客户端为什么整段跳过</b>：主人是服务端的实体引用，客户端的 {@code getOwner()}
     * 本来就是 null；在这里跑消散判断的话，弹体一到客户端就会自己消失。
     * 客户端的职责只有「把收到的位置画出来」。</p>
     */
    @Override
    public void tick() {
        // super.tick() 会往前走 tickCount、同步给追踪者；不调的话本体就是「钉」在原地的。
        super.tick();

        if (this.level().isClientSide) {
            // 客户端不跑任何权威逻辑（位置由原版追踪包下发，见类注释「同步什么」）
            return;
        }

        ServerLevel level = (ServerLevel) this.level();
        PixelUnit owner = this.getOwnerUnit();
        LivingEntity targetEntity = this.getTargetEntity();

        // ① 消散条件：超时 / 主人没了 / 目标没了或快死了
        if (this.getLifeTicks() > ProjectileFlight.LIFETIME_TICKS) {
            this.discard();     // 兜底：目标一直追不到时自然消散，不会永久留在世界上
            return;
        }
        if (owner == null || owner.isRemoved()) {
            this.discard();     // 棋子被拆了，它发出的弹也没有意义了
            return;
        }
        if (targetEntity == null || targetEntity.isRemoved() || targetEntity.isDeadOrDying()) {
            // ★ 用 isDeadOrDying() 而不是 isAlive()：原版死亡动画期间 isAlive() 仍是 true，
            //   用它会让弹体追着一具正在倒下的尸体飞（踩坑记录）。
            this.discard();
            return;
        }

        // ② 每 tick 重新取瞄准点 —— 这就是「追踪」：目标一移动，弹道下一 tick 就拐弯
        double[] aim = ProjectileFlight.aimPoint(
                targetEntity.getX(), targetEntity.getY(), targetEntity.getZ(),
                targetEntity.getBbHeight());
        double cx = this.getX();
        double cy = this.getY();
        double cz = this.getZ();
        double dx = aim[0] - cx;
        double dy = aim[1] - cy;
        double dz = aim[2] - cz;

        // ③ 命中即结算：判在移动<b>之前</b>（理由见方法注释）。
        //    结算入口由 PawnCombatManager 提供，近战与投掷物共用同一份实现。
        if (ProjectileFlight.reached(dx, dy, dz)) {
            if (this.isHealing()) {
                PawnCombatManager.applyHeal(level, owner, targetEntity, this.getDamage());
            } else {
                PawnCombatManager.applyHit(level, owner, targetEntity, this.getDamage(),
                        PawnCombatManager.HitKind.PROJECTILE);
            }
            this.discard();
            return;
        }

        // ④ 本 tick 的位移：方向与步长全部来自 ProjectileFlight，这里不再做任何算术
        double[] step = ProjectileFlight.step(cx, cy, cz, aim[0], aim[1], aim[2],
                ProjectileFlight.SPEED_PER_TICK);
        double nx = cx + step[0];
        double ny = cy + step[1];
        double nz = cz + step[2];

        // ⑤ 拖尾：位移有 1.2 格/tick，一 tick 只画一个点会连成断续的虚线，
        //    所以按位移长度取样画一串点（取样点数同样由 ProjectileFlight 决定）。
        //    ★ 只在服务端发：粒子是广播给附近玩家的，客户端再发一遍等于发两遍。
        int samples = ProjectileFlight.trailSamplesFor(
                ProjectileFlight.distance(step[0], step[1], step[2]));
        double[][] trail = ProjectileFlight.trail(
                new double[]{cx, cy, cz}, new double[]{nx, ny, nz}, samples);
        for (double[] point : trail) {
            level.sendParticles(ParticleTypes.END_ROD,
                    point[0], point[1], point[2], 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        // ⑥ 位移：用 setPos 而不是 move()。
        //    原因：move() 会做方块碰撞与「卡住」判定，而我们明确要「不参与方块碰撞」
        //    （noPhysics = true 的语义就是不撞墙），走 move() 等于把刚关掉的那套又请回来。
        this.setPos(nx, ny, nz);
        //    ★ 显式置 hasImpulse 是为了把位置<b>推给客户端</b>：
        //      hasImpulse = true 会让服务端实体追踪器本 tick 必发一次位置包（原版
        //      ServerEntity#sendChanges 里的 velocityDirty 分支），否则一颗 1.2 格/tick
        //      的弹体在客户端会一跳一跳地卡帧 —— 而这颗弹的位移<b>只有</b> setPos 一处，
        //      没有任何 setDeltaMovement 帮它把标记顶起来。
        this.hasImpulse = true;
    }

    /** 这颗弹是哪个棋子打出来的；主人已被移除 / 不是棋子时返回 null。 */
    @Nullable
    public PixelUnit getOwnerUnit() {
        return this.getOwner() instanceof PixelUnit unit && !unit.isRemoved() ? unit : null;
    }

    /** 绑定棋子主人（fire 里调；也可以用于自验台直接摆一颗弹）。 */
    public void setOwnerUnit(PixelUnit owner) {
        this.setOwner(owner);
    }

    /** 绑定追踪目标（同时记下 UUID，存档要用）。 */
    public void setTarget(LivingEntity target) {
        this.target = target;
    }

    /**
     * 追踪的目标；目标失效（移除 / 死亡 / 换维度）时返回 null。
     *
     * <p>失效时顺手把缓存清掉：否则「查一次写一次」的语义会让人误以为拿到的是旧值。</p>
     */
    @Nullable
    public LivingEntity getTargetEntity() {
        if (this.target == null) {
            return null;
        }
        if (this.target.isRemoved() || this.target.isDeadOrDying()) {
            this.target = null;
            return null;
        }
        // 换维度后 level 不再是同一个：留着它会让弹体去追一个「不在这个世界里」的位置
        if (this.target.level() != this.level()) {
            this.target = null;
            return null;
        }
        return this.target;
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /**
     * 存档。
     *
     * <p>注意这两个方法的可见性是 {@code protected}（跟 {@link Projectile} 一致，
     * 不是 {@code public}）—— 写成 public 会得到「方法不会覆盖或超类型的方法」。</p>
     *
     * <p>{@code Target} 是<i>追加</i>的键：主人的 UUID 由父类自己写（它已经有一份），
     * 这里不重复写第二份，免得两处口径以后对不上（踩坑记录的同族问题）。</p>
     */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat(TAG_DAMAGE, this.getDamage());
        if (this.target != null) {
            tag.putUUID(TAG_TARGET, this.target.getUUID());
        }
        // 职业序号<b>不</b>进 NBT：它走 SynchedEntityData，重载后由原版同步包下发。
        // 两头都写就会有两个来源，迟早对不上（而它只影响颜色，丢了也不影响判定）。
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_DAMAGE)) {
            this.setDamage(tag.getFloat(TAG_DAMAGE));
        }
        // ★ 只在服务端写权威数据：这个方法在<b>客户端也会跑</b>（同一份 NBT 要用于
        //   两端构建实体），不挡一道的话，客户端会拿着存档里的旧值去追一个它根本没有的目标，
        //   甚至把服务端刚下发的同步值覆盖掉（踩坑记录）。
        if (this.level().isClientSide) {
            return;
        }
        if (tag.hasUUID(TAG_TARGET)) {
            UUID id = tag.getUUID(TAG_TARGET);
            this.target = this.findTarget(level(), id);
            if (this.target == null) {
                // 读档后目标找不到（被打了 / 换了维度 / 存档里那个实体已经不在了）→ 直接消散。
                // 留在原地「悬着一颗不会再动的弹」比消失更难解释。
                this.discard();
            }
        } else {
            this.discard();
        }
    }

    /** 按 UUID 找回目标实体；找不到（或已经不是活物）返回 null。 */
    @Nullable
    private LivingEntity findTarget(Level level, UUID id) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity found = serverLevel.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }
}
