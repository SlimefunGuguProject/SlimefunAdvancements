package me.char321.sfadvancements.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.char321.sfadvancements.SFAdvancements;
import me.char321.sfadvancements.api.Advancement;
import me.char321.sfadvancements.api.AdvancementGroup;
import me.char321.sfadvancements.api.criteria.Criterion;
import me.char321.sfadvancements.util.Utils;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Must be accessed from the main server thread only.
public class VanillaHook {
    private boolean initialized = false;
    private final Set<NamespacedKey> loadedKeys = ConcurrentHashMap.newKeySet();
    private BackgroundStyle backgroundStyle = BackgroundStyle.RESOURCE_LOCATION;

    public void init() {
        if (initialized)
            return;
        initialized = true;

        Utils.listen(new PlayerJoinListener());
        Utils.listen(new AdvancementListener());
        reload();
    }

    public void reload() {
        if (!initialized) {
            init();
        }
        removeExistingAdvancements();
        loadedKeys.clear();
        logVanillaRootBackground();
        registerGroups();
        registerAdvancements();

        for (Player p : Bukkit.getOnlinePlayers()) {
            syncProgress(p);
        }
    }

    private void removeExistingAdvancements() {
        Set<NamespacedKey> keysToRemove = new HashSet<>();
        for (AdvancementGroup group : SFAdvancements.getRegistry().getAdvancementGroups()) {
            keysToRemove.add(Utils.keyOf(group.getId()));
        }
        for (Advancement adv : SFAdvancements.getRegistry().getAdvancements().values()) {
            keysToRemove.add(adv.getKey());
        }
        Bukkit.advancementIterator().forEachRemaining(adv -> {
            NamespacedKey key = adv.getKey();
            if (Utils.keyIsSFA(key)) {
                keysToRemove.add(key);
            }
        });

        boolean removedAny = false;
        for (NamespacedKey key : keysToRemove) {
            try {
                if (Bukkit.getUnsafe().removeAdvancement(key)) {
                    removedAny = true;
                }
            } catch (Exception e) {
                SFAdvancements.warn("无法移除进度 " + key + ": " + e.getMessage());
            }
        }
        if (removedAny && SFAdvancements.getMainConfig().getBoolean("reload-data-on-adv-remove")) {
            Bukkit.reloadData();
        }
    }

    private void registerGroups() {
        for (AdvancementGroup group : SFAdvancements.getRegistry().getAdvancementGroups()) {
            NamespacedKey key = Utils.keyOf(group.getId());
            ItemStack item = safeDisplayItem(group.getDisplayItem());
            ItemMeta meta = item.getItemMeta();
            JsonElement title = meta != null && meta.hasDisplayName()
                    ? legacyToJson(meta.getDisplayName())
                    : componentToJson(Utils.getItemName(item));
            List<String> lore = meta != null && meta.getLore() != null ? meta.getLore() : new ArrayList<>();
            String description = String.join("\n", lore);
            String rawBackground = group.getBackground();
            String resolvedBackground = resolveBackground(rawBackground, backgroundStyle);
            JsonObject json = buildAdvancementJson(
                    null,
                    item,
                    title,
                    description,
                    group.getFrameType(),
                    false,
                    resolvedBackground,
                    false);
            logGroupDebug(group.getId(), rawBackground, resolvedBackground, key, json);
            loadAdvancement(key, json);
            logResolvedBackgroundFromServer(key);
        }
    }

    private void registerAdvancements() {
        for (Map.Entry<NamespacedKey, Advancement> entry : SFAdvancements.getRegistry().getAdvancements().entrySet()) {
            registerAdvancement(entry.getValue());
        }
    }

    private void registerAdvancement(Advancement advancement) {
        if (advancement == null)
            return;
        if (loadedKeys.contains(advancement.getKey()))
            return;
        NamespacedKey parentKey = advancement.getParent();
        if (parentKey != null && !loadedKeys.contains(parentKey)) {
            Advancement parent = Utils.fromKey(parentKey);
            if (parent != null) {
                registerAdvancement(parent);
            }
        }

        ItemStack item = safeDisplayItem(advancement.getDisplay());
        ItemMeta meta = item.getItemMeta();
        JsonElement titleComponent = meta != null && meta.hasDisplayName()
                ? legacyToJson(meta.getDisplayName())
                : componentToJson(Utils.getItemName(item));
        String description = getDescriptionFor(meta != null ? meta.getLore() : null, advancement);
        JsonObject json = buildAdvancementJson(
                parentKey,
                item,
                titleComponent,
                description,
                advancement.getFrameType(),
                advancement.isHidden(),
                null,
                true);
        loadAdvancement(advancement.getKey(), json);
    }

    private void logGroupDebug(String groupId, String rawBackground, String resolvedBackground, NamespacedKey key,
            JsonObject json) {
        if (!SFAdvancements.getMainConfig().getBoolean("debug")) {
            return;
        }
        SFAdvancements.info("进度组背景: " + groupId + " -> " + resolvedBackground);
        SFAdvancements.info("背景解析: raw=" + rawBackground + ", resolved=" + resolvedBackground);
        SFAdvancements.info("进度组JSON: " + key + " -> " + json);
    }

    private static String getDescriptionFor(List<String> lore, Advancement adv) {
        lore = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
        for (int i = lore.size() - 1; i >= 0; i--) {
            if ("%criteria%".equals(lore.get(i))) {
                lore.remove(i);
                lore.addAll(i, getCriteriaLore(adv));
                return String.join("\n", lore);
            }
        }
        return String.join("\n", lore);
    }

    private static List<String> getCriteriaLore(Advancement adv) {
        List<String> res = new ArrayList<>();
        for (Criterion criterion : adv.getCriteria()) {
            res.add("§7" + criterion.getName());
        }
        return res;
    }

    private void loadAdvancement(NamespacedKey key, JsonObject json) {
        try {
            org.bukkit.advancement.Advancement loaded = Bukkit.getUnsafe().loadAdvancement(key, json.toString());
            if (loaded != null) {
                loadedKeys.add(key);
            } else {
                SFAdvancements.warn("无法注册进度 " + key + ": 返回空对象");
            }
        } catch (Exception e) {
            SFAdvancements.warn("无法注册进度 " + key + ": " + e.getMessage());
        }
    }

    private void logVanillaRootBackground() {
        NamespacedKey key = NamespacedKey.minecraft("story/root");
        org.bukkit.advancement.Advancement adv = Bukkit.getAdvancement(key);
        if (adv == null) {
            SFAdvancements.warn("无法读取原版进度背景: 未找到 " + key);
            return;
        }
        String background = readBackgroundFromAdvancement(adv);
        backgroundStyle = detectBackgroundStyle(background);
        SFAdvancements.info("原版进度背景: " + key + " -> " + background);
    }

    private void logResolvedBackgroundFromServer(NamespacedKey key) {
        org.bukkit.advancement.Advancement adv = Bukkit.getAdvancement(key);
        if (adv == null) {
            SFAdvancements.warn("无法读取注册进度背景: 未找到 " + key);
            return;
        }
        String background = readBackgroundFromAdvancement(adv);
        SFAdvancements.info("注册进度背景(服务端): " + key + " -> " + background);
    }

    @Nullable
    private static String readBackgroundFromAdvancement(org.bukkit.advancement.Advancement adv) {
        try {
            Object handle = invokeOptional(adv, "getHandle", "getAdvancement");
            if (handle == null) {
                return null;
            }
            Object nmsAdv = invokeOptional(handle, "value");
            if (nmsAdv == null) {
                nmsAdv = handle;
            }
            Object displayOpt = invokeOptional(nmsAdv, "display", "getDisplay");
            Object display = unwrapOptional(displayOpt);
            if (display == null) {
                return null;
            }
            Object backgroundOpt = invokeOptional(display, "getBackground", "background");
            Object background = unwrapOptional(backgroundOpt);
            return background == null ? null : background.toString();
        } catch (Exception e) {
            logDebugException("read-background", e);
            return null;
        }
    }

    @Nullable
    private static Object invokeOptional(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Expected on older versions, try next method name.
            } catch (Exception ignored) {
                logDebugException("reflection:" + name, ignored);
            }
        }
        return null;
    }

    @Nullable
    private static Object unwrapOptional(@Nullable Object value) {
        if (value instanceof java.util.Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private static JsonObject buildAdvancementJson(
            @Nullable NamespacedKey parent,
            ItemStack item,
            JsonElement title,
            String description,
            String frameType,
            boolean hidden,
            @Nullable String background,
            boolean showToast) {
        JsonObject root = new JsonObject();
        if (parent != null) {
            root.addProperty("parent", parent.toString());
        }

        JsonObject display = new JsonObject();
        display.add("title", title);
        display.add("description", legacyToJson(description));
        display.add("icon", buildIcon(item));
        display.addProperty("frame", normalizeFrame(frameType));
        display.addProperty("announce_to_chat", false);
        boolean showToastValue = showToast && !hidden;
        display.addProperty("show_toast", showToastValue);
        display.addProperty("hidden", hidden);
        if (background != null && !background.isBlank()) {
            display.addProperty("background", background);
        }

        JsonObject criteria = new JsonObject();
        JsonObject impossible = new JsonObject();
        impossible.addProperty("trigger", "minecraft:impossible");
        criteria.add("impossible", impossible);

        root.add("display", display);
        root.add("criteria", criteria);
        return root;
    }

    private static JsonObject buildIcon(ItemStack item) {
        ItemStack safeItem = safeDisplayItem(item);
        JsonObject icon = new JsonObject();
        icon.addProperty("id", safeItem.getType().getKey().toString());
        JsonObject components = buildIconComponents(safeItem);
        if (!components.isEmpty()) {
            icon.add("components", components);
        }
        return icon;
    }

    private static ItemStack safeDisplayItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return new ItemStack(Material.BARRIER);
        }
        return item;
    }

    private static String resolveBackground(@Nullable String background, BackgroundStyle style) {
        if (background == null || background.trim().isEmpty()) {
            return style == BackgroundStyle.TEXTURES_PATH
                    ? "minecraft:textures/block/slime_block.png"
                    : "block/slime_block";
        }

        String raw = background.trim().replace('\\', '/');
        if (style == BackgroundStyle.TEXTURES_PATH) {
            return normalizeToTexturesPath(raw);
        }
        return normalizeToResourceLocation(raw);
    }

    private static String normalizeToTexturesPath(String raw) {
        String namespace = "minecraft";
        String path = raw;
        if (raw.contains(":")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key != null) {
                namespace = key.getNamespace();
                path = key.getKey();
            }
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("textures/")) {
            normalized = normalized.substring("textures/".length());
        }
        if (!normalized.contains("/")) {
            normalized = "block/" + normalized;
        }
        String fullPath = "textures/" + normalized;
        if (!fullPath.endsWith(".png")) {
            fullPath = fullPath + ".png";
        }
        return namespace + ":" + fullPath;
    }

    private static String normalizeToResourceLocation(String raw) {
        String namespace = null;
        String path = raw;
        if (raw.contains(":")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key != null) {
                namespace = key.getNamespace();
                path = key.getKey();
            }
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("textures/")) {
            normalized = normalized.substring("textures/".length());
        }
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if (!normalized.contains("/")) {
            normalized = "block/" + normalized;
        }
        return namespace == null ? normalized : namespace + ":" + normalized;
    }

    private static BackgroundStyle detectBackgroundStyle(@Nullable String background) {
        if (background == null) {
            return BackgroundStyle.RESOURCE_LOCATION;
        }
        String value = background.toLowerCase(Locale.ROOT);
        if (value.contains("resourcetexture") || value.contains("texturepath=")) {
            return BackgroundStyle.RESOURCE_LOCATION;
        }
        return value.contains("textures/") ? BackgroundStyle.TEXTURES_PATH : BackgroundStyle.RESOURCE_LOCATION;
    }

    private enum BackgroundStyle {
        TEXTURES_PATH,
        RESOURCE_LOCATION
    }

    private static JsonObject buildIconComponents(ItemStack item) {
        JsonObject components = new JsonObject();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return components;
        }

        addCustomModelData(components, meta);
        addItemModel(components, meta);
        addSkullProfile(components, meta);

        return components;
    }

    private static void addCustomModelData(JsonObject components, ItemMeta meta) {
        if (tryAddCustomModelDataComponent(components, meta)) {
            return;
        }
        if (meta.hasCustomModelData()) {
            JsonObject custom = new JsonObject();
            JsonArray floats = new JsonArray();
            floats.add((float) meta.getCustomModelData());
            custom.add("floats", floats);
            components.add("minecraft:custom_model_data", custom);
        }
    }

    private static boolean tryAddCustomModelDataComponent(JsonObject components, ItemMeta meta) {
        try {
            Method hasMethod = meta.getClass().getMethod("hasCustomModelDataComponent");
            Object hasValue = hasMethod.invoke(meta);
            if (!(hasValue instanceof Boolean) || !((Boolean) hasValue)) {
                return false;
            }
            Method getMethod = meta.getClass().getMethod("getCustomModelDataComponent");
            Object component = getMethod.invoke(meta);
            if (component == null) {
                return false;
            }
            JsonObject custom = new JsonObject();
            JsonArray floats = toJsonArray(component, "getFloats");
            if (floats != null) {
                custom.add("floats", floats);
            }
            JsonArray flags = toJsonArray(component, "getFlags");
            if (flags != null) {
                custom.add("flags", flags);
            }
            JsonArray strings = toJsonArray(component, "getStrings");
            if (strings != null) {
                custom.add("strings", strings);
            }
            JsonArray colors = toColorJsonArray(component, "getColors");
            if (colors != null) {
                custom.add("colors", colors);
            }
            if (!custom.isEmpty()) {
                components.add("minecraft:custom_model_data", custom);
                return true;
            }
        } catch (NoSuchMethodException e) {
            logDebugException("custom-model-data-component:missing-method", e);
            return false;
        } catch (Exception e) {
            logDebugException("custom-model-data-component:failed", e);
            return false;
        }
        return false;
    }

    @Nullable
    private static JsonArray toJsonArray(Object component, String methodName) {
        try {
            Method method = component.getClass().getMethod(methodName);
            Object value = method.invoke(component);
            if (!(value instanceof List)) {
                return null;
            }
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                return null;
            }
            JsonArray array = new JsonArray();
            for (Object entry : list) {
                if (entry instanceof Number) {
                    array.add((Number) entry);
                } else if (entry instanceof Boolean) {
                    array.add((Boolean) entry);
                } else if (entry instanceof String) {
                    array.add((String) entry);
                }
            }
            return array.isEmpty() ? null : array;
        } catch (Exception e) {
            logDebugException("custom-model-data-component:list:" + methodName, e);
            return null;
        }
    }

    @Nullable
    private static JsonArray toColorJsonArray(Object component, String methodName) {
        try {
            Method method = component.getClass().getMethod(methodName);
            Object value = method.invoke(component);
            if (!(value instanceof List)) {
                return null;
            }
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                return null;
            }
            JsonArray array = new JsonArray();
            for (Object entry : list) {
                if (entry == null) {
                    continue;
                }
                Method asRgb = entry.getClass().getMethod("asRGB");
                Object rgb = asRgb.invoke(entry);
                if (rgb instanceof Number) {
                    array.add((Number) rgb);
                }
            }
            return array.isEmpty() ? null : array;
        } catch (Exception e) {
            logDebugException("custom-model-data-component:colors:" + methodName, e);
            return null;
        }
    }

    private static void addItemModel(JsonObject components, ItemMeta meta) {
        try {
            Method hasMethod = meta.getClass().getMethod("hasItemModel");
            Object hasValue = hasMethod.invoke(meta);
            if (!(hasValue instanceof Boolean) || !((Boolean) hasValue)) {
                return;
            }
            Method getMethod = meta.getClass().getMethod("getItemModel");
            Object key = getMethod.invoke(meta);
            if (key != null) {
                components.addProperty("minecraft:item_model", key.toString());
            }
        } catch (NoSuchMethodException e) {
            logDebugException("item-model:missing-method", e);
            return;
        } catch (Exception e) {
            logDebugException("item-model:failed", e);
            return;
        }
    }

    private static void logDebugException(String context, Exception e) {
        if (!SFAdvancements.getMainConfig().getBoolean("debug")) {
            return;
        }
        SFAdvancements.warn("调试信息(" + context + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static void addSkullProfile(JsonObject components, ItemMeta meta) {
        if (!(meta instanceof SkullMeta)) {
            return;
        }
        PlayerProfile profile = ((SkullMeta) meta).getOwnerProfile();
        if (profile == null) {
            return;
        }
        JsonObject profileJson = new JsonObject();
        if (profile.getUniqueId() != null) {
            JsonArray uuidArray = uuidToIntArray(profile.getUniqueId());
            if (uuidArray != null) {
                profileJson.add("id", uuidArray);
            }
        }
        if (profile.getName() != null && !profile.getName().isBlank()) {
            profileJson.addProperty("name", profile.getName());
        }

        String textureValue = encodeTextureValue(profile.getTextures());
        if (textureValue != null) {
            JsonObject textureProperty = new JsonObject();
            textureProperty.addProperty("name", "textures");
            textureProperty.addProperty("value", textureValue);
            JsonArray properties = new JsonArray();
            properties.add(textureProperty);
            profileJson.add("properties", properties);
        }

        if (!profileJson.isEmpty()) {
            components.add("minecraft:profile", profileJson);
        }
    }

    @Nullable
    private static String encodeTextureValue(PlayerTextures textures) {
        if (textures == null || textures.getSkin() == null) {
            return null;
        }
        JsonObject texturesJson = new JsonObject();
        JsonObject skinJson = new JsonObject();
        skinJson.addProperty("url", textures.getSkin().toString());
        if (textures.getSkinModel() == PlayerTextures.SkinModel.SLIM) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("model", "slim");
            skinJson.add("metadata", metadata);
        }
        texturesJson.add("SKIN", skinJson);
        if (textures.getCape() != null) {
            JsonObject capeJson = new JsonObject();
            capeJson.addProperty("url", textures.getCape().toString());
            texturesJson.add("CAPE", capeJson);
        }
        JsonObject root = new JsonObject();
        root.add("textures", texturesJson);
        String json = root.toString();
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static JsonArray uuidToIntArray(java.util.UUID uuid) {
        if (uuid == null) {
            return null;
        }
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        JsonArray array = new JsonArray();
        array.add((int) (most >> 32));
        array.add((int) most);
        array.add((int) (least >> 32));
        array.add((int) least);
        return array;
    }

    private static String normalizeFrame(String frameType) {
        // Null/unknown frames fall back to "task" to match vanilla default behavior.
        if (frameType == null) {
            return "task";
        }
        switch (frameType.toUpperCase(Locale.ROOT)) {
            case "GOAL":
                return "goal";
            case "CHALLENGE":
                return "challenge";
            default:
                return "task";
        }
    }

    private static JsonElement legacyToJson(String legacyText) {
        BaseComponent[] components = TextComponent.fromLegacyText(legacyText == null ? "" : legacyText);
        return JsonParser.parseString(ComponentSerializer.toString(components));
    }

    private static JsonElement componentToJson(BaseComponent component) {
        return JsonParser.parseString(ComponentSerializer.toString(component));
    }

    public void syncProgress(Player p) {
        for (AdvancementGroup group : SFAdvancements.getRegistry().getAdvancementGroups()) {
            complete(p, Utils.keyOf(group.getId()));
        }
        for (Advancement adv : SFAdvancements.getRegistry().getAdvancements().values()) {
            if (SFAdvancements.getAdvManager().isCompleted(p, adv)) {
                complete(p, adv.getKey());
            } else {
                revoke(p, adv.getKey());
            }
        }
    }

    public void complete(Player p, NamespacedKey key) {
        org.bukkit.advancement.Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) {
            SFAdvancements.warn("尝试完成未注册的成就 " + key);
            return;
        }
        Utils.runSync(() -> {
            AdvancementProgress progress = p.getAdvancementProgress(advancement);
            if (!progress.isDone()) {
                progress.awardCriteria("impossible");
            }
        });
    }

    public void revoke(Player p, NamespacedKey key) {
        org.bukkit.advancement.Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) {
            SFAdvancements.warn("尝试撤销未注册的成就 " + key);
            return;
        }
        Utils.runSync(() -> {
            AdvancementProgress progress = p.getAdvancementProgress(advancement);
            if (progress.isDone()) {
                progress.revokeCriteria("impossible");
            }
        });

    }
}
