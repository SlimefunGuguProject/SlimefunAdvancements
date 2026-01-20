package me.char321.sfadvancements;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.libraries.dough.config.Config;
import me.char321.sfadvancements.api.AdvancementBuilder;
import me.char321.sfadvancements.api.AdvancementGroup;
import me.char321.sfadvancements.api.criteria.CriteriaTypes;
import me.char321.sfadvancements.core.AdvManager;
import me.char321.sfadvancements.core.AdvancementsItemGroup;
import me.char321.sfadvancements.core.command.SFACommand;
import me.char321.sfadvancements.core.criteria.completer.CriterionCompleter;
import me.char321.sfadvancements.core.criteria.completer.DefaultCompleters;
import me.char321.sfadvancements.core.gui.AdvGUIManager;
import me.char321.sfadvancements.core.registry.AdvancementsRegistry;
import me.char321.sfadvancements.core.tasks.AutoSaveTask;
import me.char321.sfadvancements.util.ConfigUtils;
import me.char321.sfadvancements.util.Utils;
import me.char321.sfadvancements.vanilla.VanillaHook;
import net.guizhanss.minecraft.guizhanlib.updater.GuizhanUpdater;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SFAdvancements extends JavaPlugin implements SlimefunAddon {
    private static SFAdvancements instance;
    private final AdvManager advManager = new AdvManager();
    private final AdvGUIManager guiManager = new AdvGUIManager();
    private final AdvancementsRegistry registry = new AdvancementsRegistry();
    private final VanillaHook vanillaHook = new VanillaHook();

    private Config config;
    private YamlConfiguration advancementConfig;
    private YamlConfiguration groupConfig;

    private boolean multiBlockCraftEvent = false;

    public SFAdvancements() {

    }

    @Override
    public void onEnable() {
        instance = this;

        if (!getServer().getPluginManager().isPluginEnabled("GuizhanLibPlugin")) {
            getLogger().log(Level.SEVERE, "本插件需要 鬼斩前置库插件(GuizhanLibPlugin) 才能运行!");
            getLogger().log(Level.SEVERE, "从此处下载: https://50l.cc/gzlib");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        config = new Config(this);

        detectCapabilities();

        autoUpdate();

        getCommand("sfadvancements").setExecutor(new SFACommand(this));

        // init gui
        Bukkit.getPluginManager().registerEvents(guiManager, this);

        // init sf
        AdvancementsItemGroup.init(this);

        // init core
        DefaultCompleters.registerDefaultCompleters();
        CriteriaTypes.loadDefaultCriteria();

        info("启动自动保存任务...");
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new AutoSaveTask(), 6000L, 6000L);

        Metrics metrics = new Metrics(this, 14130);
        metrics.addCustomChart(new SimplePie("AdvancementAPI enabled",
                () -> config.getBoolean("use-advancements-api") ? "true" : "false"));

        // allow other plugins to register their criteria completers
        info("等待服务器启动中...");
        Utils.runLater(() -> {
            info("正在从配置文件中加载进度组...");
            loadGroups();
            info("正在从配置文件中加载进度...");
            loadAdvancements();

            if (config.getBoolean("use-advancements-api")) {
                vanillaHook.init();
            }
        }, 0L);

    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        try {
            advManager.save();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, e, () -> "无法保存进度");
        }
    }

    private void detectCapabilities() {
        try {
            Class.forName("io.github.thebusybiscuit.slimefun4.api.events.MultiBlockCraftEvent");
            multiBlockCraftEvent = true;
        } catch (ClassNotFoundException e) {
            multiBlockCraftEvent = false;
        }
    }

    private void autoUpdate() {
        if (config.getBoolean("auto-update") && getDescription().getVersion().startsWith("Build")) {
            info("正在检查更新...");
            GuizhanUpdater.start(this, this.getFile(), "SlimefunGuguProject", "SlimefunAdvancements", "main");
        }
    }

    public void reload() {
        config.reload();
        advManager.getPlayerMap().clear();
        registry.getAdvancements().clear();
        registry.getAdvancementGroups().clear();
        registry.getCompleters().values().forEach(CriterionCompleter::reload);

        loadGroups();
        loadAdvancements();

        if (config.getBoolean("use-advancements-api")) {
            vanillaHook.reload();
        }
    }

    public void loadGroups() {
        File groupFile = new File(getDataFolder(), "groups.yml");
        if (!groupFile.exists()) {
            saveResource("groups.yml", false);
        }
        groupConfig = YamlConfiguration.loadConfiguration(groupFile);
        for (String key : groupConfig.getKeys(false)) {
            String background = groupConfig.getString(key + ".background", "SLIME_BLOCK");
            ItemStack display = ConfigUtils.getItem(groupConfig, key + ".display");
            String frameType = groupConfig.getString(key + ".frame_type", "GOAL");
            AdvancementGroup group = new AdvancementGroup(key, display, frameType, background);
            group.register();
        }
    }

    public void loadAdvancements() {
        File advancementsFile = new File(getDataFolder(), "advancements.yml");
        if (!advancementsFile.exists()) {
            saveResource("advancements.yml", false);
        }
        advancementConfig = YamlConfiguration.loadConfiguration(advancementsFile);
        for (String key : advancementConfig.getKeys(false)) {
            AdvancementBuilder builder = AdvancementBuilder.loadFromConfig(key,
                    advancementConfig.getConfigurationSection(key));
            if (builder != null) {
                builder.register();
            }
        }
    }

    @Nonnull
    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Nullable
    @Override
    public String getBugTrackerURL() {
        return null;
    }

    public static SFAdvancements instance() {
        return instance;
    }

    public static AdvManager getAdvManager() {
        return instance.advManager;
    }

    public static AdvGUIManager getGuiManager() {
        return instance.guiManager;
    }

    public static AdvancementsRegistry getRegistry() {
        return instance.registry;
    }

    public static VanillaHook getVanillaHook() {
        return instance.vanillaHook;
    }

    public static Config getMainConfig() {
        return instance.config;
    }

    public YamlConfiguration getAdvancementConfig() {
        return advancementConfig;
    }

    public YamlConfiguration getGroupsConfig() {
        return groupConfig;
    }

    public boolean isMultiBlockCraftEvent() {
        return multiBlockCraftEvent;
    }

    public static Logger logger() {
        return instance.getLogger();
    }

    public static void info(String msg) {
        instance.getLogger().info(msg);
    }

    public static void warn(String msg) {
        instance.getLogger().warning(msg);
    }

    public static void error(String msg) {
        instance.getLogger().severe(msg);
    }

}
