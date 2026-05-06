package me.char321.sfadvancements.core.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import me.char321.sfadvancements.SFAdvancements;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class LocalProgressStorage implements ProgressStorage {
    private final File advancementsFolder;

    public LocalProgressStorage() {
        advancementsFolder = new File(SFAdvancements.instance().getDataFolder(), "advancements");
    }

    @Override
    public void init() throws IOException {
        if (!advancementsFolder.exists() && !advancementsFolder.mkdirs()) {
            throw new IOException("Unable to create folder " + advancementsFolder.getPath());
        }
    }

    @Override
    public JsonObject load(UUID player) throws IOException {
        File file = getFile(player);
        if (!file.exists()) {
            return new JsonObject();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Override
    public void save(UUID player, JsonObject progress) throws IOException {
        save(player, progress, false);
    }

    @Override
    public void save(UUID player, JsonObject progress, boolean merge) throws IOException {
        File file = getFile(player);
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create folder " + parent.getPath());
            }
            if (!file.createNewFile()) {
                throw new IOException("Unable to create file " + file.getPath());
            }
        }

        try (JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)))) {
            writer.jsonValue(progress.toString());
        }
    }

    private File getFile(UUID player) {
        return new File(advancementsFolder, player + ".json");
    }
}
