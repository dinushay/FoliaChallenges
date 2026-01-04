package foliachallenges;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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

    /**
     * Prüft beim Start, ob ein Reset durchgeführt wurde (z.B. für Nachrichten).
     */
    public void checkResetStatus() {
        if (resetFlagFile.exists()) {
            plugin.getLogger().info("Ein Welt-Reset wurde erkannt! Bereinige Flag...");
            resetFlagFile.delete();
            // Optional: Hier könnte man eine Broadcast-Nachricht senden "Willkommen in Season X"
        }
    }

    /**
     * Startet den Reset-Prozess.
     */
    public void initiateReset(CommandSender requester) {
        // 1. Flag setzen (Markierung für den nächsten Start)
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(resetFlagFile);
            cfg.set("timestamp", System.currentTimeMillis());
            cfg.set("triggered-by", requester.getName());
            cfg.save(resetFlagFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Feedback an den Sender (bevor der Server stoppt)
        requester.sendMessage(plugin.getMessage("reset-success-sender", "§aReset eingeleitet. Server stoppt gleich..."));
        
        // Broadcast Warnung
        String broadcastMsg = plugin.getMessage("reset-broadcast-warning", "§4§lACHTUNG: §cDie Welt wird gelöscht und der Server stoppt!");
        plugin.getServer().broadcastMessage(broadcastMsg);

        // 2. Scheduler starten für Kick & Delete & Shutdown
        // Wir warten 3 Sekunden (60 Ticks), damit die Chatnachricht sicher ankommt
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            
            // A. Alle Spieler kicken
            String kickMsg = plugin.getMessage("reset-kick-message", "§cWelt-Reset!\n§eDer Server startet gleich neu.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kickPlayer(kickMsg);
            }

            plugin.getLogger().info("Starte Löschvorgang der Welten...");

            // B. Welten löschen (Inhalt)
            // Löscht "world", "world_nether", "world_the_end" (Standard-Namen)
            deleteWorldContent("world");
            deleteWorldContent("world_nether");
            deleteWorldContent("world_the_end");

            plugin.getLogger().info("Welten bereinigt. Server fährt herunter.");

            // C. Server stoppen
            Bukkit.shutdown();

        }, 60L); 
    }

    /**
     * Löscht den Inhalt der Welt, behält aber Ordnerstruktur bei, um File-Locks zu umgehen.
     */
    private void deleteWorldContent(String worldName) {
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (!worldFolder.exists()) return;

        // Ordner und Dateien, die gelöscht werden sollen, um eine neue Welt zu erzwingen
        // "region" = Chunks, "playerdata" = Inventare, "level.dat" = Seed/Zeit
        String[] contentsToDelete = {"region", "playerdata", "stats", "advancements", "poi", "entities", "DIM1", "DIM-1", "level.dat", "uid.dat"};
        
        for (String targetName : contentsToDelete) {
            File target = new File(worldFolder, targetName);
            if (target.exists()) {
                if (target.isDirectory()) {
                    deleteDirectoryRecursively(target.toPath());
                } else {
                    try {
                        target.delete();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void deleteDirectoryRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    try {
                        // Versuch zu löschen. Wenn gesperrt (Lock), ignorieren wir es.
                        // Wichtig ist, dass die .mca Dateien (Regionen) weg sind.
                        file.delete();
                    } catch (Exception ignored) {
                        // Ignorieren
                    }
                });
        } catch (IOException e) {
            plugin.getLogger().warning("Warnung beim Löschen von Ordner: " + path);
        }
    }
}