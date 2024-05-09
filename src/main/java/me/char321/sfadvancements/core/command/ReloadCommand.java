package me.char321.sfadvancements.core.command;

import me.char321.sfadvancements.SFAdvancements;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public class ReloadCommand implements SubCommand {
    @Override
    public boolean onExecute(CommandSender sender, Command command, String label, String[] args) {
        SFAdvancements.info("正在重载配置...");
        sender.sendMessage(ChatColor.YELLOW + "重载配置是一个实验性功能。如果你遇到了任何问题，请重启服务器。");
        try {
            SFAdvancements.getAdvManager().save();
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "保存进度时出现错误，检查控制台获得更多信息。重载已中止。");
            SFAdvancements.logger().log(Level.SEVERE, e, () -> "重载保存进度时出现错误");
            return false;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (SFAdvancements.getGuiManager().isOpen(player)) {
                player.closeInventory();
            }
        }

        SFAdvancements.instance().reload();

        sender.sendMessage("已成功重载配置！");
        return true;
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "reload";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
