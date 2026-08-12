package me.char321.sfadvancements.core.storage;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.UUID;

public interface ProgressStorage extends AutoCloseable {
    void init() throws IOException;

    JsonObject load(UUID player) throws IOException;

    void save(UUID player, JsonObject progress) throws IOException;

    default void save(UUID player, JsonObject progress, boolean merge) throws IOException {
        save(player, progress);
    }

    @Override
    default void close() throws IOException {
    }
}
