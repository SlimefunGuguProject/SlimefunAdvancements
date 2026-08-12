package me.char321.sfadvancements.core.criteria.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import me.char321.sfadvancements.SFAdvancements;
import me.char321.sfadvancements.api.Advancement;
import me.char321.sfadvancements.api.criteria.Criterion;
import me.char321.sfadvancements.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * a per-player object that stores their advancement progress <br>
 *
 * json <br>
 *
 */
public class PlayerProgress {
    private final UUID player;
    private final Map<NamespacedKey, AdvancementProgress> progressMap = new HashMap<>();
    private boolean saveQueued = false;

    private PlayerProgress(UUID player) {
        this.player = player;
    }

    public static PlayerProgress get(Player player) {
        return get(player.getUniqueId());
    }

    public static PlayerProgress get(UUID player) {
        PlayerProgress res = new PlayerProgress(player);
        try {
            JsonObject object = SFAdvancements.getProgressStorage().load(player);
            res.loadFromObject(object);
        } catch (IOException | IllegalStateException e) {
            SFAdvancements.logger().log(Level.SEVERE, "读取进度时发生错误", e);
        }
        return res;
    }

    public synchronized void doCriterion(Criterion criterion) {
        NamespacedKey adv = criterion.getAdvancement();
        progressMap.computeIfAbsent(adv, AdvancementProgress::new);

        AdvancementProgress advProgress = progressMap.get(adv);
        if (advProgress.done) {
            return;
        }

        boolean changed = false;
        for (CriteriaProgress progress : advProgress.criteria) {
            if (!progress.id.equals(criterion.getId())) {
                continue;
            }

            if (progress.progress < criterion.getCount()) {
                progress.progress++;
                changed = true;
                if (progress.progress >= criterion.getCount()) {
                    progress.done = true;
                    advProgress.updateDone();
                }
            }
        }
        if (changed) {
            saveAsync();
        }
    }

    public synchronized void completeCriterion(Criterion criterion) {
        NamespacedKey adv = criterion.getAdvancement();
        AdvancementProgress progress = progressMap.computeIfAbsent(adv, AdvancementProgress::new);

        for (CriteriaProgress criteriaProgress : progress.criteria) {
            if (!criteriaProgress.id.equals(criterion.getId())) {
                continue;
            }

            if (criteriaProgress.done) {
                return;
            }

            criteriaProgress.done = true;
            criteriaProgress.progress = criterion.getCount();
            progress.updateDone();
            saveAsync();
        }
    }

    public synchronized int getCriterionProgress(Criterion cri) {
        NamespacedKey adv = cri.getAdvancement();
        if (!progressMap.containsKey(adv)) {
            return 0;
        }

        AdvancementProgress advProgress = progressMap.get(adv);
        for (CriteriaProgress progress : advProgress.criteria) {
            if (progress.id.equals(cri.getId())) {
                return progress.progress;
            }
        }
        throw new IllegalStateException();
    }

    public synchronized boolean revokeAdvancement(NamespacedKey adv) {
        if (!progressMap.containsKey(adv)) {
            return false;
        }
        progressMap.get(adv).done = false;
        for (CriteriaProgress progress : progressMap.get(adv).criteria) {
            progress.done = false;
            progress.progress = 0;
        }
        Utils.fromKey(adv).revoke(Bukkit.getPlayer(player));
        saveAsync();
        return true;
    }

    public synchronized List<NamespacedKey> getCompletedAdvancements() {
        List<NamespacedKey> res = new ArrayList<>();
        for (Map.Entry<NamespacedKey, AdvancementProgress> entry : progressMap.entrySet()) {
            if (entry.getValue().done) {
                res.add(entry.getKey());
            }
        }
        return res;
    }

    private synchronized void loadFromObject(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            NamespacedKey advkey = NamespacedKey.fromString(entry.getKey());
            if(advkey == null || !Utils.isValidAdvancement(advkey)) {
                SFAdvancements.warn("未知进度: " + advkey);
                continue;
            }
            AdvancementProgress newprogress = new AdvancementProgress(advkey);
            progressMap.put(advkey, newprogress);
            newprogress.loadFromObject(entry.getValue().getAsJsonObject());
        }
    }

    public synchronized void save() throws IOException {
        save(true);
    }

    public synchronized void save(boolean merge) throws IOException {
        SFAdvancements.getProgressStorage().save(player, toJsonObject(), merge);
        saveQueued = false;
    }

    public synchronized JsonObject toJsonObject() throws IOException {
        StringWriter stringWriter = new StringWriter();
        try(JsonWriter writer = new JsonWriter(stringWriter)) {
            writer.beginObject();
            for (Map.Entry<NamespacedKey, AdvancementProgress> entry : progressMap.entrySet()) {
                writer.name(entry.getKey().toString());
                writer.beginObject();
                writer.name("done").value(entry.getValue().done);
                writer.name("criteria");
                writer.beginObject();
                for (CriteriaProgress criterion : entry.getValue().criteria) {
                    writer.name(criterion.id).value(criterion.progress);
                }
                writer.endObject();
                writer.endObject();
            }
            writer.endObject();
        }
        return com.google.gson.JsonParser.parseString(stringWriter.toString()).getAsJsonObject();
    }

    private synchronized void saveAsync() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        Bukkit.getScheduler().runTaskAsynchronously(SFAdvancements.instance(), () -> {
            try {
                save(true);
            } catch (IOException e) {
                synchronized (PlayerProgress.this) {
                    saveQueued = false;
                }
                SFAdvancements.logger().log(Level.SEVERE, e, () -> "无法异步保存玩家进度");
            }
        });
    }

    /**
     * determines if a given advancement is completed for this player progress
     *
     * @param key the key of the advancement
     * @return if the advancement is completed
     */
    public synchronized boolean isCompleted(NamespacedKey key) {
        if (!progressMap.containsKey(key)) {
            return false;
        }
        AdvancementProgress prog = progressMap.get(key);
        return prog.done;
    }

    class AdvancementProgress {
        Advancement adv;
        boolean done = false;
        CriteriaProgress[] criteria;

        AdvancementProgress(NamespacedKey adv) {
            this(Utils.fromKey(adv));
        }

        AdvancementProgress(Advancement adv) {
            this.adv = adv;
            this.criteria = new CriteriaProgress[adv.getCriteria().length];
            for (int i = 0; i < adv.getCriteria().length; i++) {
                criteria[i] = new CriteriaProgress(adv.getCriteria()[i].getId());
            }
        }

        void updateDone() {
            for (CriteriaProgress criterion : criteria) {
                if (!criterion.done) {
                    return;
                }
            }
            this.done = true;

            adv.onComplete(Bukkit.getPlayer(player));
        }

        void loadFromObject(JsonObject object) {
            done = object.get("done").getAsBoolean();
            JsonObject jsonCriteria = object.get("criteria").getAsJsonObject();
            criteria = new CriteriaProgress[adv.getCriteria().length];
            int i = 0;
            for (Criterion criterion : adv.getCriteria()) {
                CriteriaProgress criteriaProgress;
                JsonElement element = jsonCriteria.get(criterion.getId());
                if (element == null || !element.isJsonPrimitive()) {
                    criteriaProgress = new CriteriaProgress(criterion.getId(), 0);
                } else {
                    int progress = element.getAsInt();
                    criteriaProgress = new CriteriaProgress(criterion.getId(), progress);
                    criteriaProgress.done = progress >= criterion.getCount();
                }
                criteria[i] = criteriaProgress;
                i++;
            }
        }
    }

    static class CriteriaProgress {
        String id;
        boolean done = false;
        //TODO make this easier to use so people can add their own criteria progress types like string
        int progress;

        CriteriaProgress(String id) {
            this(id, 0);
        }

        CriteriaProgress(String id, int progress) {
            this.id = id;
            this.progress = progress;
        }
    }
}
