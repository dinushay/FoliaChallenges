package foliachallenges;

import org.bukkit.Material;
import java.util.Arrays;
import java.util.List;

public class ItemBlacklist {
    public static final List<Material> HARDCODED_BLACKLIST = Arrays.asList(
        Material.AIR,
        Material.VOID_AIR,
        Material.CAVE_AIR,
        Material.BARRIER,
        Material.STRUCTURE_VOID,
        Material.BEDROCK,
        Material.COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.COMMAND_BLOCK_MINECART,
        Material.STRUCTURE_BLOCK,
        Material.JIGSAW,
        Material.LIGHT,
        Material.DEBUG_STICK,
        Material.KNOWLEDGE_BOOK,
        Material.REINFORCED_DEEPSLATE,
        Material.END_PORTAL_FRAME,
        Material.END_PORTAL,
        Material.NETHER_PORTAL,
        Material.END_GATEWAY,
        Material.SPAWNER,
        Material.BUDDING_AMETHYST,
        Material.FROGSPAWN,
        Material.FARMLAND,
        Material.DIRT_PATH,
        Material.INFESTED_STONE,
        Material.WATER,
        Material.LAVA,
        Material.BUBBLE_COLUMN,
        Material.FIRE,
        Material.SOUL_FIRE
    );
    
    public static boolean isObtainable(Material material) {
        if (HARDCODED_BLACKLIST.contains(material)) return false;
        String name = material.name();
        if (name.endsWith("_SPAWN_EGG")) return false;
        if (name.startsWith("LEGACY_")) return false;
        switch (name) {
            case "LARGE_FERN":
            case "TALL_GRASS":
            case "TALL_SEAGRASS":
            case "CHORUS_PLANT":
            case "PETRIFIED_OAK_SLAB":
            case "PLAYER_HEAD":
            case "GLOBE_BANNER_PATTERN":
            case "VAULT":
            case "AMETHYST_CLUSTER":
            case "POWDER_SNOW":
            case "BUNDLE":
            case "TEST":
                return false;
        }
        return true;
    }
} // # Items that are impossible to obtain for the challenge are added here from time to time. Please continue to contribute to this list.