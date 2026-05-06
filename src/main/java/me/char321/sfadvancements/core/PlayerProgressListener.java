package me.char321.sfadvancements.core;

import me.char321.sfadvancements.SFAdvancements;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.util.logging.Level;

public class PlayerProgressListener implements Listener {
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            SFAdvancements.getAdvManager().unload(event.getPlayer().getUniqueId());
        } catch (IOException e) {
            SFAdvancements.logger().log(Level.SEVERE, e, () -> "无法保存并卸载玩家进度");
        }
    }
}
