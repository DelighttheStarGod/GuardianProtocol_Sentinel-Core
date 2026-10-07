package com.guardianprotocol.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.guardianprotocol.GuardianProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 读数据包里的职业分支定义。
 *
 * <p>文件位置：{@code data/<命名空间>/guardian_branches/<名字>.json}，
 * 字段说明见 {@link JsonBranch}。</p>
 *
 * <h3>三条实现决定</h3>
 * <ol>
 *     <li><b>一个文件写坏，只跳过它</b>（打 ERROR 日志），不炸整个重载 ——
 *         数据包是外部输入，一个笔误不该让玩家进不去世界。</li>
 *     <li><b>按 id 排序后再解析</b>：{@code "replaces"} 可以指向另一个数据包分支，
 *         而 {@code Map} 的遍历顺序不确定；排序让「A 覆盖 B、B 覆盖内置」这种链
 *         有确定的先后（按 id 字典序）。</li>
 *     <li><b>整体替换装载结果</b>（见 {@link BranchRegistry#applyDatapack}）：
 *         删文件 = 删分支。</li>
 * </ol>
 */
public final class BranchReloadListener extends SimpleJsonResourceReloadListener {

    /** 数据包目录名。 */
    public static final String DIRECTORY = "guardian_branches";

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    public BranchReloadListener() {
        super(GSON, DIRECTORY);
    }

    /** 挂到游戏事件总线（{@code GuardianProtocol} 构造器里调用）。 */
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BranchReloadListener());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        // 排序：以「文件名对应的 id」为准，保证 replaces 链的解析顺序确定
        Map<ResourceLocation, JsonElement> sorted = new TreeMap<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        sorted.putAll(files);

        Map<ResourceLocation, BranchDef> loaded = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : sorted.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException("根节点必须是 JSON 对象");
                }
                JsonObject json = entry.getValue().getAsJsonObject();

                // replaces：覆盖哪个分支（缺省 = 用文件名当新分支的 id）
                ResourceLocation targetId = fileId;
                BranchDef base = null;
                String replaces = readString(json, "replaces");
                if (replaces != null) {
                    ResourceLocation wanted = ResourceLocation.tryParse(replaces);
                    if (wanted == null) {
                        throw new IllegalArgumentException("replaces 不是合法 id：" + replaces);
                    }
                    targetId = wanted;
                    // ★ 先在「已装载的数据包层」里找，再找注册表 —— 否则 A 覆盖 B 时
                    //   拿到的 base 会是内置的 B，而不是「已经被 A 的前驱改过的 B」。
                    base = loaded.getOrDefault(wanted, BranchRegistry.getOrNull(wanted));
                    if (base == null) {
                        throw new IllegalArgumentException("replaces 指向不存在的分支：" + replaces);
                    }
                }
                String key = base != null ? base.key() : targetId.getPath();

                JsonBranch def = JsonBranch.parse(targetId, key, json, base);
                loaded.put(targetId, def);
                GuardianProtocol.LOGGER.info("[{}] 数据包分支 {}：{}（{}{}）",
                        GuardianProtocol.MODID, def.replaces() ? "覆盖" : "新增",
                        def.id(), def.branchName(), def.unitClass().displayName());
            } catch (RuntimeException ex) {
                skipped++;
                GuardianProtocol.LOGGER.error("[{}] 跳过数据包分支 {}：{}",
                        GuardianProtocol.MODID, fileId, ex.getMessage());
            }
        }
        BranchRegistry.applyDatapack(loaded);
        if (skipped > 0) {
            GuardianProtocol.LOGGER.warn("[{}] 有 {} 个分支定义文件被跳过（见上面的 ERROR）",
                    GuardianProtocol.MODID, skipped);
        }
    }

    @Nullable
    private static String readString(JsonObject json, String key) {
        JsonElement e = json.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        String v = e.getAsString().trim();
        return v.isEmpty() ? null : v;
    }

    /** 便于自验/文档：数据包里「有哪些分支文件」的目录名。 */
    public static List<String> directoryHint() {
        return List.of("data/<命名空间>/" + DIRECTORY + "/<名字>.json");
    }
}
