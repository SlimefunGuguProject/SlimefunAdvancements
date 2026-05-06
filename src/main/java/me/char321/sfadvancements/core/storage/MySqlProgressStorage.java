package me.char321.sfadvancements.core.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

public class MySqlProgressStorage implements ProgressStorage {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String table;
    private final Properties properties = new Properties();
    private static boolean driverLoaded = false;

    public MySqlProgressStorage(ConfigurationSection config) {
        String host = getString(config, "host", "localhost");
        int port = getInt(config, "port", 3306);
        String database = getString(config, "database", "minecraft");
        String url = getString(config, "jdbc-url", "");
        if (url == null || url.isBlank()) {
            url = "jdbc:mysql://" + host + ":" + port + "/" + database;
        }
        jdbcUrl = url;
        username = getString(config, "username", "root");
        password = getString(config, "password", "");
        table = sanitizeTableName(getString(config, "table", "sfadvancements_progress"));

        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("useUnicode", "true");
        properties.setProperty("characterEncoding", "utf8");
        properties.setProperty("serverTimezone", getString(config, "server-timezone", "UTC"));
        properties.setProperty("useSSL", Boolean.toString(getBoolean(config, "use-ssl", false)));
        properties.setProperty("autoReconnect", "true");
        properties.setProperty("connectTimeout", Integer.toString(getInt(config, "connect-timeout", 10000)));
        properties.setProperty("socketTimeout", Integer.toString(getInt(config, "socket-timeout", 30000)));
    }

    @Override
    public void init() throws IOException {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + table + "` ("
                    + "`uuid` VARCHAR(36) NOT NULL,"
                    + "`progress` MEDIUMTEXT NOT NULL,"
                    + "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (`uuid`)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (SQLException e) {
            throw new IOException("Unable to initialize MySQL progress storage", e);
        }
    }

    @Override
    public JsonObject load(UUID player) throws IOException {
        String sql = "SELECT `progress` FROM `" + table + "` WHERE `uuid` = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new JsonObject();
                }
                return JsonParser.parseString(result.getString("progress")).getAsJsonObject();
            }
        } catch (SQLException | IllegalStateException e) {
            throw new IOException("Unable to load MySQL progress for " + player, e);
        }
    }

    @Override
    public void save(UUID player, JsonObject progress) throws IOException {
        save(player, progress, true);
    }

    @Override
    public void save(UUID player, JsonObject progress, boolean merge) throws IOException {
        if (!merge) {
            saveExact(player, progress);
            return;
        }
        String selectSql = "SELECT `progress` FROM `" + table + "` WHERE `uuid` = ? FOR UPDATE";
        String insertSql = "INSERT INTO `" + table + "` (`uuid`, `progress`) VALUES (?, ?)";
        String updateSql = "UPDATE `" + table + "` SET `progress` = ? WHERE `uuid` = ?";
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            boolean exists;
            JsonObject merged = progress;

            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setString(1, player.toString());
                try (ResultSet result = statement.executeQuery()) {
                    exists = result.next();
                    if (exists) {
                        JsonObject stored = JsonParser.parseString(result.getString("progress")).getAsJsonObject();
                        merged = mergeProgress(stored, progress);
                    }
                }
            }

            if (exists) {
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setString(1, merged.toString());
                    statement.setString(2, player.toString());
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    statement.setString(1, player.toString());
                    statement.setString(2, merged.toString());
                    statement.executeUpdate();
                }
            }

            connection.commit();
        } catch (SQLException e) {
            throw new IOException("Unable to save MySQL progress for " + player, e);
        } catch (IllegalStateException e) {
            throw new IOException("Unable to merge MySQL progress for " + player, e);
        }
    }

    private void saveExact(UUID player, JsonObject progress) throws IOException {
        saveExact(player, progress.toString());
    }

    public UploadResult uploadIfLocalLarger(UUID player, String localProgress) throws IOException {
        String selectSql = "SELECT `progress` FROM `" + table + "` WHERE `uuid` = ? FOR UPDATE";
        String insertSql = "INSERT INTO `" + table + "` (`uuid`, `progress`) VALUES (?, ?)";
        String updateSql = "UPDATE `" + table + "` SET `progress` = ? WHERE `uuid` = ?";
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setString(1, player.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                            insert.setString(1, player.toString());
                            insert.setString(2, localProgress);
                            insert.executeUpdate();
                        }
                        connection.commit();
                        return UploadResult.UPLOADED;
                    }

                    String mysqlProgress = result.getString("progress");
                    if (byteLength(localProgress) <= byteLength(mysqlProgress)) {
                        connection.commit();
                        return UploadResult.SKIPPED;
                    }

                    try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                        update.setString(1, localProgress);
                        update.setString(2, player.toString());
                        update.executeUpdate();
                    }
                    connection.commit();
                    return UploadResult.OVERWRITTEN;
                }
            }
        } catch (SQLException e) {
            throw new IOException("Unable to upload local progress for " + player, e);
        }
    }

    private void saveExact(UUID player, String progress) throws IOException {
        String sql = "INSERT INTO `" + table + "` (`uuid`, `progress`) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE `progress` = VALUES(`progress`)";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, progress);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Unable to save MySQL progress for " + player, e);
        }
    }

    private static int byteLength(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public enum UploadResult {
        UPLOADED,
        OVERWRITTEN,
        SKIPPED
    }

    private static JsonObject mergeProgress(JsonObject stored, JsonObject incoming) {
        JsonObject merged = stored.deepCopy();
        for (String advancementKey : incoming.keySet()) {
            JsonObject incomingAdvancement = incoming.getAsJsonObject(advancementKey);
            if (!merged.has(advancementKey) || !merged.get(advancementKey).isJsonObject()) {
                merged.add(advancementKey, incomingAdvancement.deepCopy());
                continue;
            }

            JsonObject storedAdvancement = merged.getAsJsonObject(advancementKey);
            boolean done = getDone(storedAdvancement) || getDone(incomingAdvancement);
            storedAdvancement.addProperty("done", done);

            JsonObject storedCriteria = getCriteria(storedAdvancement);
            JsonObject incomingCriteria = getCriteria(incomingAdvancement);
            for (String criterionKey : incomingCriteria.keySet()) {
                int storedProgress = storedCriteria.has(criterionKey) ? storedCriteria.get(criterionKey).getAsInt() : 0;
                int incomingProgress = incomingCriteria.get(criterionKey).getAsInt();
                storedCriteria.addProperty(criterionKey, Math.max(storedProgress, incomingProgress));
            }
            storedAdvancement.add("criteria", storedCriteria);
        }
        return merged;
    }

    private static boolean getDone(JsonObject advancement) {
        return advancement.has("done") && advancement.get("done").isJsonPrimitive() && advancement.get("done").getAsBoolean();
    }

    private static JsonObject getCriteria(JsonObject advancement) {
        if (!advancement.has("criteria") || !advancement.get("criteria").isJsonObject()) {
            return new JsonObject();
        }
        return advancement.getAsJsonObject("criteria");
    }

    private Connection getConnection() throws SQLException {
        loadDriver();
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private static synchronized void loadDriver() throws SQLException {
        if (driverLoaded) {
            return;
        }
        DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
        driverLoaded = true;
    }

    private static String sanitizeTableName(String table) {
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            return "sfadvancements_progress";
        }
        return table;
    }

    private static String getString(ConfigurationSection config, String path, String def) {
        return config == null ? def : config.getString(path, def);
    }

    private static int getInt(ConfigurationSection config, String path, int def) {
        return config == null ? def : config.getInt(path, def);
    }

    private static boolean getBoolean(ConfigurationSection config, String path, boolean def) {
        return config == null ? def : config.getBoolean(path, def);
    }
}
