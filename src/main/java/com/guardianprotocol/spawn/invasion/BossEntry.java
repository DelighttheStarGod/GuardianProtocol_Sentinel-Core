package com.guardianprotocol.spawn.invasion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条 <b>BOSS 配置</b>（设计确定：BOSS 是**单独一组配置**，不是给生物行打标记）。
 *
 * <p>一组 BOSS 里会被指定两只：一只是 {@link InvasionPhase#BOSS 最终 BOSS 战}要打的那只，
 * 另一只是 {@link InvasionPhase#HIDDEN 隐藏挑战}里<b>被全面增强</b>的那只
 * （见 {@link InvasionConfig#hiddenBoost()}）。同一条配置可以同时担任两者
 * —— 那就等于「BOSS 战里打它，隐藏挑战里打增强版的它」。</p>
 *
 * <p>字段刻意比 {@link CreatureRow} 少：BOSS 不进「生成单元」那套节奏（不配间隔/半径/策略），
 * 它由相位直接生成一只。</p>
 */
public final class BossEntry {

    /** 血量倍率上限：再高就该调属性修正，而不是把倍率写成天文数字。 */
    public static final double MAX_HEALTH_MULTIPLIER = 100.0D;

    /** 名字长度上限（界面输入框给不了太长，顺手挡一下诡异 NBT）。 */
    public static final int MAX_NAME_LEN = 48;

    private String entity = "";
    private String name = "";
    private double healthMultiplier = 1.0D;
    private final List<CreatureRow.AttrMod> attrs = new ArrayList<>();
    private final List<CreatureRow.PotionMod> potions = new ArrayList<>();

    public String entity() {
        return entity;
    }

    public void setEntity(@Nullable String id) {
        this.entity = id == null ? "" : id.trim();
    }

    public String name() {
        return name;
    }

    public void setName(@Nullable String n) {
        String v = n == null ? "" : n.trim();
        this.name = v.length() > MAX_NAME_LEN ? v.substring(0, MAX_NAME_LEN) : v;
    }

    public double healthMultiplier() {
        return healthMultiplier;
    }

    public void setHealthMultiplier(double v) {
        this.healthMultiplier = v;
    }

    public List<CreatureRow.AttrMod> attrs() {
        return attrs;
    }

    public List<CreatureRow.PotionMod> potions() {
        return potions;
    }

    /** 配好了没有（界面与相位推进都问它；空 BOSS 组 = 这一轮没有 BOSS 战）。 */
    public boolean isConfigured() {
        return !entity.isEmpty();
    }

    /** 夹取到合法区间，返回是否改动过。 */
    public boolean clamp() {
        boolean changed = false;
        double m = Math.max(0.0D, Math.min(MAX_HEALTH_MULTIPLIER, healthMultiplier));
        if (m != healthMultiplier) {
            healthMultiplier = m;
            changed = true;
        }
        while (attrs.size() > CreatureRow.MAX_ATTRS) {
            attrs.remove(attrs.size() - 1);
            changed = true;
        }
        while (potions.size() > CreatureRow.MAX_POTIONS) {
            potions.remove(potions.size() - 1);
            changed = true;
        }
        return changed;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("entity", entity);
        tag.putString("name", name);
        tag.putDouble("hpMul", healthMultiplier);
        ListTag attrList = new ListTag();
        for (CreatureRow.AttrMod a : attrs) {
            attrList.add(a.save());
        }
        tag.put("attrs", attrList);
        ListTag potionList = new ListTag();
        for (CreatureRow.PotionMod p : potions) {
            potionList.add(p.save());
        }
        tag.put("potions", potionList);
        return tag;
    }

    public static BossEntry load(CompoundTag tag) {
        BossEntry boss = new BossEntry();
        boss.setEntity(tag.getString("entity"));
        boss.setName(tag.getString("name"));
        boss.healthMultiplier = tag.contains("hpMul") ? tag.getDouble("hpMul") : 1.0D;
        ListTag attrList = tag.getList("attrs", Tag.TAG_COMPOUND);
        for (int i = 0; i < attrList.size(); i++) {
            boss.attrs.add(CreatureRow.AttrMod.load(attrList.getCompound(i)));
        }
        ListTag potionList = tag.getList("potions", Tag.TAG_COMPOUND);
        for (int i = 0; i < potionList.size(); i++) {
            boss.potions.add(CreatureRow.PotionMod.load(potionList.getCompound(i)));
        }
        boss.clamp();
        return boss;
    }

    public String describe() {
        if (entity.isEmpty()) {
            return "（空 BOSS）";
        }
        return (name.isEmpty() ? entity : (name + "(" + entity + ")"))
                + " 血量×" + healthMultiplier
                + (attrs.isEmpty() && potions.isEmpty() ? ""
                : (" 修正=" + attrs.size() + "属性/" + potions.size() + "效果"));
    }
}
