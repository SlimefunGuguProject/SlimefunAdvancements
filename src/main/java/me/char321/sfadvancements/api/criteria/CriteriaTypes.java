package me.char321.sfadvancements.api.criteria;

import me.char321.sfadvancements.SFAdvancements;
import org.bukkit.configuration.ConfigurationSection;

import java.util.function.Function;

/**
 * utility class that contain methods for criteria types
 */
public class CriteriaTypes {
    private CriteriaTypes() {}

    public static void loadDefaultCriteria() {
        putType("consume", ConsumeCriterion::loadFromConfig);
        putType("interact", InteractCriterion::loadFromConfig);
        putType("inventory", InventoryCriterion::loadFromConfig);
        putType("mobkill", MobKillCriterion::loadFromConfig);
        putType("multiblock", MultiBlockCriterion::loadFromConfig);
        putType("place", PlaceCriterion::loadFromConfig);
        putType("research", ResearchCriterion::loadFromConfig);
        putType("none", Criterion::loadFromConfig);
        putType("search", SearchCriterion::loadFromConfig);
        putType("break", BlockBreakCriterion::loadFromConfig);
        putType("multiblockcraft", MultiBlockCraftCriterion::loadFromConfig);
    }

    /**
     * registers a criterion type in config
     * if a criterion is defined as this type, the function will be called to get a criterion from a section
     *
     * @param type the type specified in the config
     * @param criterionFromConfig the function that creates a criterion object from a configuration
     */
    public static void putType(String type, Function<ConfigurationSection, Criterion> criterionFromConfig) {
        SFAdvancements.getRegistry().getCriterionTypes().put(type, criterionFromConfig);
    }

    /**
     * loads a criterion from a config, assuming the criterion type was registered using putType()
     *
     * @param id the id of the criterion (should be config.getName())
     * @param config the configuration section that defines the criterion
     * @return the created criterion from the config
     */
    public static Criterion loadFromConfig(String id, ConfigurationSection config) {
        String type = config.getString("type");
        if (type == null) {
            SFAdvancements.warn("你必须在advancements.yml为 " + id + " 指定类型");
            return null;
        }

        Function<ConfigurationSection, Criterion> function = SFAdvancements.getRegistry().getCriterionTypes().get(type);
        if(function == null) {
            SFAdvancements.warn("未知的成就类型: " + type);
            return null;
        }

        return function.apply(config);
    }
}
