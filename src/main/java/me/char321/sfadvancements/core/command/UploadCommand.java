package me.char321.sfadvancements.core.command;

import com.google.gson.JsonParser;
import me.char321.sfadvancements.SFAdvancements;
import me.char321.sfadvancements.core.storage.MySqlProgressStorage;
import me.char321.sfadvancements.core.storage.ProgressStorage;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class UploadCommand implements SubCommand {
    private boolean running = false;

    @Override
    public synchronized boolean onExecute(CommandSender sender, Command command, String label, String[] args) {
        ProgressStorage storage = SFAdvancements.getProgressStorage();
        if (!(storage instanceof MySqlProgressStorage)) {
            sender.sendMessage(ChatColor.RED + "当前 storage.yml 的 type 不是 mysql，无法上传本地进度。");
            return false;
        }

        if (running) {
            sender.sendMessage(ChatColor.YELLOW + "本地进度上传任务正在运行，请等待完成。");
            return true;
        }

        java.io.File folder = new java.io.File(SFAdvancements.instance().getDataFolder(), "advancements");
        if (!folder.exists() || !folder.isDirectory()) {
            sender.sendMessage(ChatColor.YELLOW + "本地 advancements 文件夹不存在，没有可上传的数据。");
            return true;
        }

        java.io.File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "本地 advancements 文件夹中没有 json 进度文件。");
            return true;
        }

        MySqlProgressStorage mySqlStorage = (MySqlProgressStorage) storage;
        running = true;
        sender.sendMessage(ChatColor.YELLOW + "正在异步上传本地进度文件到 MySQL，共 " + files.length + " 个文件...");
        Bukkit.getScheduler().runTaskAsynchronously(SFAdvancements.instance(), () -> runUpload(sender, mySqlStorage, files));
        return true;
    }

    private void runUpload(CommandSender sender, MySqlProgressStorage mySqlStorage, java.io.File[] files) {
        try {
            int uploaded = 0;
            int overwritten = 0;
            int skipped = 0;
            int failed = 0;

            for (java.io.File file : files) {
                String uuidString = file.getName().substring(0, file.getName().length() - ".json".length());
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidString);
                } catch (IllegalArgumentException e) {
                    failed++;
                    SFAdvancements.warn("跳过无效玩家进度文件: " + file.getName());
                    continue;
                }

                try {
                    String progress = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    JsonParser.parseString(progress).getAsJsonObject();
                    MySqlProgressStorage.UploadResult result = mySqlStorage.uploadIfLocalLarger(uuid, progress);
                    switch (result) {
                        case UPLOADED:
                            uploaded++;
                            break;
                        case OVERWRITTEN:
                            overwritten++;
                            break;
                        case SKIPPED:
                            skipped++;
                            break;
                        default:
                            break;
                    }
                } catch (IOException | IllegalStateException e) {
                    failed++;
                    SFAdvancements.logger().log(Level.SEVERE, e, () -> "上传本地进度失败: " + file.getName());
                }
            }

            int finalUploaded = uploaded;
            int finalOverwritten = overwritten;
            int finalSkipped = skipped;
            int finalFailed = failed;
            Bukkit.getScheduler().runTask(SFAdvancements.instance(), () -> {
                sender.sendMessage(ChatColor.GREEN + "本地进度上传完成: "
                        + "新增 " + finalUploaded
                        + ", 覆盖 " + finalOverwritten
                        + ", 跳过 " + finalSkipped
                        + ", 失败 " + finalFailed);
            });
        } finally {
            synchronized (this) {
                running = false;
            }
        }
    }

    @Override
    public @Nonnull String getCommandName() {
        return "upload";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
