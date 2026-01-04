package foliachallenges;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class WorldResetManager {

    private final FoliaChallengePlugin plugin;
    private final File resetFlagFile;

    public WorldResetManager(FoliaChallengePlugin plugin) {
        this.plugin = plugin;
        this.resetFlagFile = new File(plugin.getDataFolder(), "reset_pending.yml");
    }

    public void checkResetStatus() {
        if (resetFlagFile.exists()) {
            plugin.getLogger().info("Ein Welt-Reset wurde erkannt! Bereinige Flag...");
            resetFlagFile.delete();
        }
    }

    public void initiateReset(CommandSender requester) {
        // 1. Flag setzen
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(resetFlagFile);
            cfg.set("timestamp", System.currentTimeMillis());
            cfg.set("triggered-by", requester.getName());
            cfg.save(resetFlagFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        requester.sendMessage(plugin.getMessage("reset-success-sender", "§aReset eingeleitet. Server stoppt gleich..."));
        String broadcastMsg = plugin.getMessage("reset-broadcast-warning", "§4§lACHTUNG: §cDie Welt wird gelöscht und der Server stoppt!");
        plugin.getServer().broadcastMessage(broadcastMsg);

        // 2. Scheduler starten
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            
            // WICHTIG: Auto-Save deaktivieren!
            // Verhindert, dass der Server die gelöschten Dateien beim Shutdown wiederherstellt.
            plugin.getLogger().info("Deaktiviere Welt-Speicherung...");
            for (World world : Bukkit.getWorlds()) {
                world.setAutoSave(false);
            }

            // A. Alle Spieler kicken
            String kickMsg = plugin.getMessage("reset-kick-message", "§cWelt-Reset!\n§eDer Server startet gleich neu.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kickPlayer(kickMsg);
            }

            plugin.getLogger().info("Starte Löschvorgang der Welten...");

            // B. Welten löschen
            // Wir löschen "world", "world_nether", "world_the_end"
            deleteWorldContent("world");
            deleteWorldContent("world_nether");
            deleteWorldContent("world_the_end");

            plugin.getLogger().info("Welten bereinigt. Server fährt herunter.");

            // C. Server stoppen
            Bukkit.shutdown();

        }, 60L); 
    }

    private void deleteWorldContent(String worldName) {
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (!worldFolder.exists()) return;

        // Ordner/Dateien, die weg müssen für einen neuen Seed/Reset
        String[] contentsToDelete = {"region", "playerdata", "stats", "advancements", "poi", "entities", "DIM1", "DIM-1", "level.dat", "uid.dat"};
        
        for (String targetName : contentsToDelete) {
            File target = new File(worldFolder, targetName);
            if (target.exists()) {
                boolean deleted = false;
                if (target.isDirectory()) {
                    deleted = deleteDirectoryRecursively(target.toPath());
                } else {
                    try {
                        deleted = target.delete();
                    } catch (Exception ignored) {}
                }
                
                // Logging für Debugging (damit du in der Konsole siehst, was passiert)
                if (deleted) {
                    plugin.getLogger().info("Gelöscht: " + worldName + "/" + targetName);
                } else {
                    plugin.getLogger().warning("Konnte nicht löschen (evtl. gesperrt): " + worldName + "/" + targetName);
                }
            }
        }
    }

    private boolean deleteDirectoryRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    try {
                        file.delete();
                    } catch (Exception ignored) { }
                });
            return !Files.exists(path);
        } catch (IOException e) {
            plugin.getLogger().warning("Fehler beim Löschen von Ordner: " + path);
            return false;
        }
    }
}
