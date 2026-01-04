package foliachallenges;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FoliaChallengePlugin extends JavaPlugin implements Listener, TabCompleter {

    private static final String PREFIX = "§8§l┃ §bFoliaChallenges §8┃§7 ";

    private FileConfiguration config;
    private FileConfiguration messages;
    private long timerSeconds = 0;
    private long remainingSeconds = 0;
    private boolean timerRunning = false;
    private boolean timerSet = false;
    private ScheduledTask actionBarTask;
    private ScheduledTask timerTask;
    private ScheduledTask saveTask;
    private GlobalRegionScheduler scheduler;
    
    private List<Material> configurableBlacklist = new ArrayList<>();
    private List<Material> hardcodedBlacklist = Arrays.asList(Material.AIR);
    
    private Map<UUID, Material> assignedItems = new HashMap<>();
    private Map<UUID, Integer> scores = new HashMap<>();
    
    private Map<Player, BossBar> bossBars = new HashMap<>();
    private Map<Player, org.bukkit.entity.ArmorStand> itemDisplays = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultMessages();
        saveDefaultItemBlacklist();
        config = getConfig();

        // --- CLEANUP LOGIC START ---
        // Delete old worlds that were marked for deletion during the last reset
        // Since we are now in a new world, the old folders are inactive and can be deleted.
        cleanupOldWorlds();
        // --- CLEANUP LOGIC END ---

        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        loadConfigurableBlacklist();
        getServer().getPluginManager().registerEvents(this, this);
        
        registerCommand("challenges");
        registerCommand("timer");
        registerCommand("reset");
        registerCommand("start");
        
        getLogger().info(messages.getString("plugin-enabled", "FoliaChallenge enabled!"));
        
        this.scheduler = getServer().getGlobalRegionScheduler();
        scheduler.run(this, task -> pauseWorlds());
        
        actionBarTask = scheduler.runAtFixedRate(this, task -> updateActionBar(), 1, 10);
        
        loadData();
    }

    // --- World Reset & Cleanup Methods ---
    
    private void cleanupOldWorlds() {
        List<String> worldsToDelete = config.getStringList("worlds-to-delete");
        if (worldsToDelete == null || worldsToDelete.isEmpty()) return;

        getLogger().info(messages.getString("cleanup-start", "Cleaning up old world folders..."));
        List<String> keptWorlds = new ArrayList<>();

        for (String worldName : worldsToDelete) {
            String currentLevelName = getMainLevelName();

            // FIX 1: If the world is active, we must KEEP it in the list
            if (worldName.equals(currentLevelName)) {
                getLogger().warning(messages.getString("cleanup-skip-active", "Skipping deletion of %world% because it is currently active! It will be queued for the next restart.").replace("%world%", worldName));
                keptWorlds.add(worldName); // <--- That was missing before!
                continue;
            }

            File worldFolder = new File(getServer().getWorldContainer(), worldName);
            File netherFolder = new File(getServer().getWorldContainer(), worldName + "_nether");
            File endFolder = new File(getServer().getWorldContainer(), worldName + "_the_end");

            // Löschversuche
            deleteWorldFolder(worldFolder);
            deleteWorldFolder(netherFolder);
            deleteWorldFolder(endFolder);
            
            // FIX 2: Check if the deletion was successful. 
            // If the folder still exists (e.g. Permission Error), keep it in the list!
            if (worldFolder.exists()) {
                getLogger().warning(messages.getString("cleanup-delete-failed", "Failed to fully delete %world%. Keeping it in queue.").replace("%world%", worldName));
                keptWorlds.add(worldName);
            }
        }

        // Aktualisierte Liste speichern
        config.set("worlds-to-delete", keptWorlds);
        saveConfig();
    }

    private void prepareWorldReset(CommandSender sender) {
        String kickMsg = messages.getString("reset-kick-message", "§cThe server is being reset!\n§eRestart shortly...");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.kickPlayer(kickMsg);
        }

        sender.sendMessage(PREFIX + messages.getString("reset-initiated", "§aReset initiated. World will be rotated and seed renewed..."));

        try {
            // IMPORTANT: Here we change the server.properties for the NEXT start
            rotateWorldAndResetSeed();
        } catch (Exception e) {
            sender.sendMessage(PREFIX + messages.getString("reset-error-properties", "§cError modifying server.properties: %error%").replace("%error%", e.getMessage()));
            e.printStackTrace();
            return;
        }

        // Shutdown after short delay
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            Bukkit.shutdown();
        }, 20L); 
    }

    private void rotateWorldAndResetSeed() throws IOException {
        File propFile = new File("server.properties");
        Properties props = new Properties();
        
        try (InputStream in = new FileInputStream(propFile)) {
            props.load(in);
        }

        String oldLevelName = props.getProperty("level-name", "world");
        
        // Generate new name (e.g. "world_1704382910")
        // This forces the server to create a new folder -> New map!
        String newLevelName = "world_" + (System.currentTimeMillis() / 1000);
        
        props.setProperty("level-name", newLevelName);
        props.setProperty("level-seed", ""); // Seed leeren, damit ein neuer zufälliger generiert wird

        try (FileOutputStream out = new FileOutputStream(propFile)) {
            props.store(out, "Minecraft server properties - Modified by FoliaChallenges Reset");
        }
        
        // Remember the old name to delete it on the next start
        List<String> toDelete = config.getStringList("worlds-to-delete");
        if (!toDelete.contains(oldLevelName)) {
            toDelete.add(oldLevelName);
        }
        config.set("worlds-to-delete", toDelete);
        saveConfig();
        
        getLogger().info("World rotation set: " + oldLevelName + " -> " + newLevelName);
    }

    private String getMainLevelName() {
        try (InputStream input = new FileInputStream("server.properties")) {
            Properties prop = new Properties();
            prop.load(input);
            return prop.getProperty("level-name", "world");
        } catch (IOException ex) {
            return "world";
        }
    }

    private void deleteWorldFolder(File folder) {
        if (!folder.exists()) return;
        
        getLogger().info(messages.getString("cleanup-deleting-folder", "Deleting inactive world folder: %folder%").replace("%folder%", folder.getName()));
        Path rootPath = folder.toPath();

        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.sorted(Comparator.reverseOrder()) 
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        getLogger().log(Level.WARNING, messages.getString("cleanup-delete-path-failed", "Failed to delete: %path%").replace("%path%", path.toString()));
                    }
                });
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, messages.getString("cleanup-walk-error", "Error walking directory: %folder%").replace("%folder%", folder.getName()), e);
        }
    }
    // ---------------------------

    private void registerCommand(String name) {
        if (getCommand(name) != null) {
            getCommand(name).setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        itemDisplays.clear();
        
        if (actionBarTask != null) actionBarTask.cancel();
        if (timerTask != null) timerTask.cancel();
        if (saveTask != null) saveTask.cancel();
        
        saveData();
        getLogger().info(messages.getString("plugin-disabled", "FoliaChallenge disabled!"));
    }

    private void pauseWorlds() {
        for (org.bukkit.World world : getServer().getWorlds()) {
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        }
    }

    private void resumeWorlds() {
        for (org.bukkit.World world : getServer().getWorlds()) {
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, true);
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, true);
        }
    }

    private void assignRandomItem(Player player) {
        List<Material> available = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !configurableBlacklist.contains(m) && !hardcodedBlacklist.contains(m)) {
                available.add(m);
            }
        }
        if (!available.isEmpty()) {
            Material random = available.get(new Random().nextInt(available.size()));
            assignedItems.put(player.getUniqueId(), random);
            
            createItemDisplay(player, random);
            updateBossBar(player);
            saveData();
        }
    }

    private BossBar createBossBar(Player player) {
        BossBar bar = getServer().createBossBar(messages.getString("bossbar-default", "Current Item: -"), BarColor.BLUE, BarStyle.SOLID);
        bar.addPlayer(player);
        bossBars.put(player, bar);
        return bar;
    }

    private void createItemDisplay(Player player, Material item) {
        removeItemDisplay(player);
        
        org.bukkit.entity.ArmorStand armorStand = player.getWorld().spawn(player.getLocation().add(0, 2.2, 0), org.bukkit.entity.ArmorStand.class);
        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setMarker(true);
        armorStand.setSmall(true);
        armorStand.setCustomNameVisible(false);
        armorStand.setItemInHand(new org.bukkit.inventory.ItemStack(item));
        
        itemDisplays.put(player, armorStand);
    }

    private void removeItemDisplay(Player player) {
        org.bukkit.entity.ArmorStand armorStand = itemDisplays.remove(player);
        if (armorStand != null && !armorStand.isDead()) {
            armorStand.remove();
        }
    }

    private void updateItemDisplay(Player player) {
        org.bukkit.entity.ArmorStand armorStand = itemDisplays.get(player);
        if (armorStand != null && !armorStand.isDead()) {
            armorStand.teleportAsync(player.getLocation().add(0, 2.2, 0));
        }
    }

    private void updateBossBar(Player player) {
        BossBar bar = bossBars.get(player);
        if (bar != null) {
            Material item = assignedItems.get(player.getUniqueId());
            if (item != null) {
                String itemName = formatItemName(item.name());
                bar.setTitle(messages.getString("bossbar-item", "Current Item: %item%").replace("%item%", itemName));
            } else {
                bar.setTitle(messages.getString("bossbar-paused", "Timer paused"));
            }
        }
    }

    private String formatItemName(String name) {
        String[] parts = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // --- Config & Resources Helpers ---
    private void saveDefaultMessages() {
        copyResource("messages.yml");
    }
    private void saveDefaultItemBlacklist() {
        copyResource("items-blacklist.yml");
    }
    private void copyResource(String filename) {
        File file = new File(getDataFolder(), filename);
        if (!file.exists()) {
            try (InputStream in = getResource(filename)) {
                if (in != null) Files.copy(in, file.toPath());
            } catch (IOException e) { getLogger().log(java.util.logging.Level.SEVERE, messages.getString("copy-resource-error", "Could not copy resource %filename%").replace("%filename%", filename), e); }
        }
    }

    private void loadConfigurableBlacklist() {
        File blacklistFile = new File(getDataFolder(), "items-blacklist.yml");
        if (blacklistFile.exists()) {
            FileConfiguration blacklistConfig = YamlConfiguration.loadConfiguration(blacklistFile);
            List<String> items = blacklistConfig.getStringList("blacklisted-items");
            for (String item : items) {
                try {
                    configurableBlacklist.add(Material.valueOf(item.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    getLogger().warning(messages.getString("invalid-blacklist-material", "Invalid material in blacklist: %item%").replace("%item%", item));
                }
            }
        }
    }

    // --- Commands ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();
        
        if (!sender.hasPermission("foliachallenge.timer")) {
            sender.sendMessage(PREFIX + messages.getString("no-permission", "You do not have permission for that!"));
            return true;
        }

        if (cmdName.equals("start")) {
            startTimer(sender);
            return true;
        }

        // --- RESET COMMAND START ---
        if (cmdName.equals("reset")) {
            if (!sender.hasPermission("foliachallenge.reset") && !sender.hasPermission("foliachallenge.admin")) {
                sender.sendMessage(PREFIX + messages.getString("no-permission", "You do not have permission for that!"));
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
                resetChallengeData(sender);
                prepareWorldReset(sender);
            } else {
                sender.sendMessage(PREFIX + messages.getString("reset-warning-1", "§4§lWARNING: §cThis command deletes all challenge data"));
                sender.sendMessage(PREFIX + messages.getString("reset-warning-2", "§cand §lgenerates a new world§c!"));
                sender.sendMessage(PREFIX + messages.getString("reset-confirm-usage", "§7Use §c/reset confirm §7to proceed."));
            }
            return true;
        }
        // --- RESET COMMAND END ---

        if (cmdName.equals("challenges")) {
            if (args.length > 0) {
                String subCmd = args[0].toLowerCase();
                
                if (subCmd.equals("randomitembattle")) {
                    if (args.length < 2) return sendUsage(sender, label);
                    
                    if (args[1].equalsIgnoreCase("listitems")) {
                        listItems(sender);
                        return true;
                    } else if (args[1].equalsIgnoreCase("listpoints")) {
                        listPoints(sender);
                        return true;
                    } else if (args[1].equalsIgnoreCase("blockitem")) {
                        if (args.length < 3) {
                            sender.sendMessage(PREFIX + messages.getString("usage-blockitem", "Usage: /%command% randomitembattle blockitem <item>").replace("%command%", label));
                            return true;
                        }
                        blockItem(sender, args[2]);
                        return true;
                    }
                } else if (subCmd.equals("reload")) {
                    reloadConfig();
                    messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
                    sender.sendMessage(PREFIX + "Configuration and messages reloaded!");
                    return true;
                }
            }
            return sendUsage(sender, label);
        }

        if (cmdName.equals("timer")) {
            if (args.length == 0) {
                sender.sendMessage(messages.getString("usage-timer", "Usage: /timer <start|stop|set|resume> [minutes]"));
                return true;
            }
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "start":
                case "resume":
                    startTimer(sender);
                    break;
                case "stop":
                    stopTimer(sender);
                    break;
                case "set":
                    if (args.length < 2) {
                        sender.sendMessage(PREFIX + messages.getString("usage-timer-set", "Usage: /timer set <minutes>"));
                        return true;
                    }
                    try {
                        setTimer(sender, Integer.parseInt(args[1]));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(PREFIX + messages.getString("invalid-minutes", "Ungültige Minutenanzahl!"));
                    }
                    break;
                default:
                    sender.sendMessage(PREFIX + messages.getString("usage-timer", "Usage: /timer <start|stop|set|resume> [minutes]"));
                    break;
            }
            return true;
        }
        return false;
    }

    private boolean sendUsage(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + messages.getString("usage-randomitembattle", "Usage: /" + label + " randomitembattle <listitems|listpoints|blockitem>").replace("%command%", label));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();
        
        if (cmdName.equals("reset")) {
            if (args.length == 1) return filter(args[0], Arrays.asList("confirm"));
        }

        if (cmdName.equals("challenges")) {
            if (args.length == 1) return filter(args[0], Arrays.asList("randomitembattle", "reload"));
            if (args.length == 2 && args[0].equalsIgnoreCase("randomitembattle")) return filter(args[1], Arrays.asList("listitems", "listpoints", "blockitem"));
            if (args.length == 3 && args[1].equalsIgnoreCase("blockitem")) {
                return Arrays.stream(Material.values()).filter(Material::isItem).map(Material::name).map(String::toLowerCase)
                        .filter(n -> n.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            }
        } else if (cmdName.equals("timer") && args.length == 1) {
            return filter(args[0], Arrays.asList("start", "stop", "set", "resume"));
        }
        return Collections.emptyList();
    }
    
    private List<String> filter(String arg, List<String> options) {
        return options.stream().filter(s -> s.toLowerCase().startsWith(arg.toLowerCase())).collect(Collectors.toList());
    }

    // --- Timer Logic ---
    private void startTimer(CommandSender sender) {
        if (!timerSet) {
            sender.sendMessage(PREFIX + messages.getString("timer-not-set-message", "Timer nicht gesetzt!"));
            return;
        }
        if (remainingSeconds == 0) {
            sender.sendMessage(PREFIX + messages.getString("timer-expired", "Timer ist abgelaufen! Setze neue Zeit mit /timer set <minuten>."));
            return;
        }
        if (timerRunning) {
            sender.sendMessage(PREFIX + messages.getString("timer-already-running", "Timer läuft bereits!"));
            return;
        }
        
        timerRunning = true;
        scheduler.run(this, task -> pauseWorlds());
        
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SURVIVAL) {
                if (!assignedItems.containsKey(p.getUniqueId())) {
                    assignRandomItem(p);
                } else {
                    Material existing = assignedItems.get(p.getUniqueId());
                    createItemDisplay(p, existing);
                }
            }
            updateBossBar(p);
        }

        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        sender.sendMessage(PREFIX + messages.getString("timer-started", "Timer gestartet!"));
        getServer().broadcastMessage(PREFIX + messages.getString("timer-started-global", "§aDer Challenge-Timer wurde gestartet!"));
        
        saveTask = scheduler.runAtFixedRate(this, task -> saveData(), 20, 20);
        startTimerTask();
        updateActionBar();
    }
    
    private void resetChallengeData(CommandSender sender) {
        if (timerRunning) stopTimer(sender);
        
        scores.clear();
        assignedItems.clear();
        
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
            updateBossBar(p);
        }
        saveData();
        sender.sendMessage(PREFIX + messages.getString("reset-data-cleared", "§cChallenge data (Scores & Items) have been reset!"));
    }

    private void stopTimer(CommandSender sender) {
        if (!timerRunning) {
            sender.sendMessage(PREFIX + messages.getString("timer-not-running", "Timer läuft nicht!"));
            return;
        }
        timerRunning = false;
        scheduler.run(this, task -> resumeWorlds());
        if (timerTask != null) timerTask.cancel();
        if (saveTask != null) saveTask.cancel();
        
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
            updateBossBar(p);
        }
        
        sender.sendMessage(PREFIX + messages.getString("timer-stopped", "Timer gestoppt!"));
        updateActionBar();
        saveData();
    }

    private void setTimer(CommandSender sender, int minutes) {
        timerSeconds = minutes * 60L;
        remainingSeconds = timerSeconds;
        timerSet = true;
        sender.sendMessage(PREFIX + messages.getString("timer-set", "Timer auf %minutes% Minuten gesetzt!").replace("%minutes%", String.valueOf(minutes)));
        updateActionBar();
        saveData();
    }

    private void startTimerTask() {
        timerTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (remainingSeconds > 0) {
                updateActionBar();
                remainingSeconds--;
            } else {
                timerRunning = false;
                if (saveTask != null) saveTask.cancel();
                scheduler.run(this, t -> {
                    resumeWorlds();
                    for (Player p : getServer().getOnlinePlayers()) removeItemDisplay(p);
                });
                endChallenge();
                updateActionBar();
                task.cancel();
            }
        }, 1, 20);
    }

    private void endChallenge() {
        itemDisplays.clear();
        
        List<Map.Entry<UUID, Integer>> sortedScores = scores.entrySet().stream()
            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        getServer().broadcastMessage(PREFIX + messages.getString("color-title", "§6§l") + messages.getString("leaderboard-title", "=== Challenge Ergebnisse ==="));
        
        int rank = 1;
        for (int i = 0; i < sortedScores.size(); i++) {
            if (i > 0 && !sortedScores.get(i).getValue().equals(sortedScores.get(i-1).getValue())) rank = i + 1;
            
            String pName = Bukkit.getOfflinePlayer(sortedScores.get(i).getKey()).getName();
            if (pName == null) pName = "Unknown";
            
            String entry = messages.getString("leaderboard-entry", "#%rank% %player% - %points% Punkte")
                .replace("%rank%", String.valueOf(rank))
                .replace("%player%", pName)
                .replace("%points%", String.valueOf(sortedScores.get(i).getValue()));
            getServer().broadcastMessage(PREFIX + messages.getString("color-rank", "§e") + entry);
        }
        
        getServer().broadcastMessage(PREFIX + messages.getString("color-separator", "§6§l========================"));

        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
            updateBossBar(p);
        }
        
        saveData();
    }

    private void listItems(CommandSender sender) {
        sender.sendMessage(PREFIX + messages.getString("assigned-items-title", "=== Assigned Items ==="));
        assignedItems.forEach((uuid, mat) -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                 sender.sendMessage(PREFIX + messages.getString("list-item-entry", "%player% - %item%").replace("%player%", p.getName()).replace("%item%", formatItemName(mat.name())));
            }
        });
        sender.sendMessage(PREFIX + messages.getString("color-separator", "§6§l==================="));
    }

    private void listPoints(CommandSender sender) {
        sender.sendMessage(PREFIX + messages.getString("player-points-title", "=== Player Points ==="));
        scores.forEach((uuid, points) -> {
             Player p = Bukkit.getPlayer(uuid);
             if (p != null && points > 0) {
                 sender.sendMessage(PREFIX + messages.getString("list-points-entry", "%player% - %points% Punkte").replace("%player%", p.getName()).replace("%points%", String.valueOf(points)));
             }
        });
        sender.sendMessage(PREFIX + messages.getString("color-separator", "§6§l==================="));
    }

    private void blockItem(CommandSender sender, String itemName) {
        if (!sender.hasPermission("foliachallenge.admin")) {
            sender.sendMessage(messages.getString("no-permission", "Keine Rechte!"));
            return;
        }
        try {
            Material material = Material.valueOf(itemName.toUpperCase());
            if (configurableBlacklist.contains(material)) {
                sender.sendMessage(PREFIX + messages.getString("item-already-blacklisted", "§cAlready blacklisted!"));
                return;
            }
            configurableBlacklist.add(material);
            
            for (Map.Entry<UUID, Material> entry : new HashMap<>(assignedItems).entrySet()) {
                if (entry.getValue() == material) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) {
                        assignRandomItem(p);
                        p.sendMessage(PREFIX + messages.getString("item-blacklisted-reassigned", "§eDas Item %item% wurde geblacklistet. Du hast ein neues Item zugewiesen bekommen!").replace("%item%", material.name()));
                    } else {
                        assignedItems.remove(entry.getKey());
                    }
                }
            }
            
            File f = new File(getDataFolder(), "items-blacklist.yml");
            FileConfiguration c = YamlConfiguration.loadConfiguration(f);
            List<String> list = c.getStringList("blacklisted-items");
            list.add(material.name());
            c.set("blacklisted-items", list);
            c.save(f);
            sender.sendMessage(PREFIX + messages.getString("item-blacklisted", "§aItem blacklisted!"));
            
            if (config.getBoolean("share-blacklisted-items-to-developer", true)) {
                sendDiscordWebhook("Item-blacklist: " + material.name());
            }
        } catch (Exception e) {
            sender.sendMessage(PREFIX + messages.getString("block-item-error", "§cError: %error%").replace("%error%", e.getMessage()));
        }
    }
    
    private void sendDiscordWebhook(String msg) {
         try {
            java.net.URL url = new java.net.URL("https://discord.com/api/webhooks/1456737969581850684/YXYsctMK0K5a3m6eM65rp9WnFcddCTLmSIL9jjfQ2V1k8HOYBFuAxCKZTQs-xYjWGUMW");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            String json = "{\"content\":\"" + msg + "\"}";
            try (java.io.OutputStream os = conn.getOutputStream()) { os.write(json.getBytes("utf-8")); }
            conn.getResponseCode();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void updateActionBar() {
        String msg;
        if (!timerSet) msg = messages.getString("timer-not-set", "• Zeit nicht gesetzt •");
        else {
            String time = formatTime(remainingSeconds);
            String color = timerRunning ? "§a" : "§c";
            msg = messages.getString("timer-display", "• Zeit: %time% •").replace("%time%", color + time + "§f");
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(msg);
    }

    private String formatTime(long s) {
        return (s >= 3600) ? String.format("%02d:%02d:%02d", s/3600, (s%3600)/60, s%60) : String.format("%02d:%02d", s/60, s%60);
    }

    // --- Events ---
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        createBossBar(player);
        
        if (assignedItems.containsKey(player.getUniqueId())) {
            createItemDisplay(player, assignedItems.get(player.getUniqueId()));
        } else if (timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
            assignRandomItem(player);
        }
        updateBossBar(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        removeItemDisplay(player);
        bossBars.remove(player);
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!timerRunning || player.getGameMode() != GameMode.SURVIVAL) return;
        
        Material assigned = assignedItems.get(player.getUniqueId());
        if (assigned != null && event.getItem().getItemStack().getType() == assigned) {
            scores.put(player.getUniqueId(), scores.getOrDefault(player.getUniqueId(), 0) + 1);
            assignRandomItem(player);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            player.sendMessage(PREFIX + messages.getString("item-found", "§aItem gefunden!"));
        }
    }
    
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        if (!config.getBoolean("allow-movement-without-timer", false) && !timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.setCancelled(true);
                player.sendTitle(
                    "§c§l" + messages.getString("timer-paused-title", "STOP!"), 
                    messages.getString("timer-paused-subtitle", "Der Timer ist pausiert!"), 
                    10, 70, 20
                );
            }
        }
        if (timerRunning) updateItemDisplay(player);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) { if (!timerRunning && e.getPlayer().getGameMode() == GameMode.SURVIVAL) e.setCancelled(true); }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) { if (!timerRunning && e.getPlayer().getGameMode() == GameMode.SURVIVAL) e.setCancelled(true); }
    @EventHandler
    public void onDmg(EntityDamageEvent e) { if (e.getEntity() instanceof Player && !timerRunning && ((Player)e.getEntity()).getGameMode() == GameMode.SURVIVAL) e.setCancelled(true); }
    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) { if (e.getTarget() instanceof Player && !timerRunning && ((Player)e.getTarget()).getGameMode() == GameMode.SURVIVAL) e.setCancelled(true); }
    @EventHandler
    public void onGMChange(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SURVIVAL && timerRunning && !assignedItems.containsKey(e.getPlayer().getUniqueId())) {
            assignRandomItem(e.getPlayer());
        }
    }

    // --- Persistenz ---
    private void saveData() {
        try {
            File dataFile = new File(getDataFolder(), "data.yml");
            FileConfiguration data = new YamlConfiguration();
            data.set("remainingSeconds", remainingSeconds);
            
            Map<String, Integer> scoreMap = new HashMap<>();
            scores.forEach((uuid, pts) -> scoreMap.put(uuid.toString(), pts));
            data.set("scores", scoreMap);
            
            Map<String, String> assignMap = new HashMap<>();
            assignedItems.forEach((uuid, mat) -> assignMap.put(uuid.toString(), mat.name()));
            data.set("assignedItems", assignMap);
            
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe(messages.getString("save-data-error", "Could not save data.yml"));
        }
    }

    private void loadData() {
        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) return;
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        
        remainingSeconds = data.getLong("remainingSeconds", 0);
        if (remainingSeconds > 0) timerSet = true;
        
        if (data.contains("scores")) {
            data.getConfigurationSection("scores").getValues(false).forEach((k, v) -> {
                try { scores.put(UUID.fromString(k), (Integer)v); } catch(Exception e){}
            });
        }
        if (data.contains("assignedItems")) {
            data.getConfigurationSection("assignedItems").getValues(false).forEach((k, v) -> {
                try { assignedItems.put(UUID.fromString(k), Material.valueOf((String)v)); } catch(Exception e){}
            });
        }
    }
}
