package me.char321.sfadvancements.core.command;

import me.char321.sfadvancements.SFAdvancements;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public class SaveCommand implements SubCommand {

    @Override
    public boolean onExecute(CommandSender sender, Command command, String label, String[] args) {
        try {
            SFAdvancements.getAdvManager().save();
            sender.sendMessage("已保存进度.");
            return true;
        } catch(IOException e) {
            sender.sendMessage("保存进度时发生错误!");
            sender.sendMessage("请检查控制台.");
            SFAdvancements.logger().log(Level.SEVERE, e, () -> "无法保存进度");
            return false;
        }
    }

    @Override
    public @Nonnull String getCommandName() {
        return "save";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
