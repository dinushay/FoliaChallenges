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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FoliaChallengePlugin extends JavaPlugin implements Listener, TabCompleter {

    private FileConfiguration config;
    private FileConfiguration messages;
    private long timerSeconds = 0;
    private long remainingSeconds = 0;
    private boolean timerRunning = false;
    private boolean timerSet = false;
    private ScheduledTask actionBarTask;
    private ScheduledTask timerTask;
    private GlobalRegionScheduler scheduler;
    private List<Material> configurableBlacklist = new ArrayList<>();
    private List<Material> hardcodedBlacklist = Arrays.asList(Material.AIR);
    private Map<Player, Material> assignedItems = new HashMap<>();
    private Map<Player, Integer> scores = new HashMap<>();
    private Map<Player, BossBar> bossBars = new HashMap<>();
    private Map<Player, org.bukkit.entity.ArmorStand> itemDisplays = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultMessages();
        saveDefaultItemBlacklist();
        config = getConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        loadConfigurableBlacklist();
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register TabCompleter for commands
        if (getCommand("challenges") != null) {
            getCommand("challenges").setTabCompleter(this);
        }
        if (getCommand("timer") != null) {
            getCommand("timer").setTabCompleter(this);
        }
        if (getCommand("resume") != null) {
            getCommand("resume").setTabCompleter(this);
        }
        
        getLogger().info("FoliaChallenge enabled!");
        // Setup scheduler
        this.scheduler = getServer().getGlobalRegionScheduler();
        // Pause worlds since timer is not running
        scheduler.run(this, task -> pauseWorlds());
        // Start action bar update task
        actionBarTask = scheduler.runAtFixedRate(this, task -> updateActionBar(), 1, 10); // Update every 0.5 seconds
    }

    @Override
    public void onDisable() {
        // Remove all floating item displays
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
        }
        if (actionBarTask != null) {
            actionBarTask.cancel();
        }
        if (timerTask != null) {
            timerTask.cancel();
        }
        getLogger().info("FoliaChallenge disabled!");
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
            assignedItems.put(player, random);
            // Create floating item display
            createItemDisplay(player, random);
        }
    }

    private BossBar createBossBar(Player player) {
        BossBar bar = getServer().createBossBar(messages.getString("bossbar-default", "Aktuelles Item: -"), BarColor.BLUE, BarStyle.SOLID);
        bar.addPlayer(player);
        bossBars.put(player, bar);
        return bar;
    }

    private void createItemDisplay(Player player, Material item) {
        // Remove existing display if any
        removeItemDisplay(player);
        
        // Create new ArmorStand above player's head
        org.bukkit.entity.ArmorStand armorStand = player.getWorld().spawn(player.getLocation().add(0, 2.2, 0), org.bukkit.entity.ArmorStand.class);
        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setMarker(true);
        armorStand.setSmall(true);
        armorStand.setCustomNameVisible(false);
        
        // Set the item in hand
        armorStand.setItemInHand(new org.bukkit.inventory.ItemStack(item));
        
        // Store reference
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
            // Update position to follow player above their head (Folia requires teleportAsync)
            armorStand.teleportAsync(player.getLocation().add(0, 2.2, 0));
        }
    }

    private void updateBossBar(Player player) {
        BossBar bar = bossBars.get(player);
        if (bar != null) {
            Material item = assignedItems.get(player);
            if (item != null) {
                String itemName = formatItemName(item.name());
                bar.setTitle(messages.getString("bossbar-item", "Aktuelles Item: %item%").replace("%item%", itemName));
            } else {
                bar.setTitle(messages.getString("bossbar-paused", "Timer pausiert"));
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

    private void saveDefaultMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            try (InputStream in = getResource("messages.yml")) {
                if (in != null) {
                    Files.copy(in, messagesFile.toPath());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void saveDefaultItemBlacklist() {
        File blacklistFile = new File(getDataFolder(), "items-blacklist.yml");
        if (!blacklistFile.exists()) {
            try (InputStream in = getResource("items-blacklist.yml")) {
                if (in != null) {
                    Files.copy(in, blacklistFile.toPath());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadConfigurableBlacklist() {
        File blacklistFile = new File(getDataFolder(), "items-blacklist.yml");
        if (blacklistFile.exists()) {
            FileConfiguration blacklistConfig = YamlConfiguration.loadConfiguration(blacklistFile);
            List<String> items = blacklistConfig.getStringList("blacklisted-items");
            for (String item : items) {
                try {
                    Material mat = Material.valueOf(item.toUpperCase());
                    configurableBlacklist.add(mat);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Invalid material in blacklist: " + item);
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("resume")) {
            if (!sender.hasPermission("foliachallenge.timer")) {
                sender.sendMessage(messages.getString("no-permission", "Du hast keine Berechtigung dafür!"));
                return true;
            }
            resumeTimer(sender);
            return true;
        }
        if (command.getName().equalsIgnoreCase("challenges") || command.getName().equalsIgnoreCase("start")) {
            if (!sender.hasPermission("foliachallenge.timer")) {
                sender.sendMessage(messages.getString("no-permission", "Du hast keine Berechtigung dafür!"));
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("randomitembattle")) {
                if (args.length < 2) {
                    sender.sendMessage(messages.getString("usage-randomitembattle", "Usage: /" + label + " randomitembattle <listitems|listpoints|blockitem>").replace("%command%", label));
                    return true;
                }
                if (args[1].equalsIgnoreCase("listitems")) {
                    listItems(sender);
                    return true;
                } else if (args[1].equalsIgnoreCase("listpoints")) {
                    listPoints(sender);
                    return true;
                } else if (args[1].equalsIgnoreCase("blockitem")) {
                    if (args.length < 3) {
                        sender.sendMessage("Usage: /" + label + " randomitembattle blockitem <item>");
                        return true;
                    }
                    blockItem(sender, args[2]);
                    return true;
                } else {
                    sender.sendMessage(messages.getString("usage-randomitembattle", "Usage: /" + label + " randomitembattle <listitems|listpoints|blockitem>").replace("%command%", label));
                    return true;
                }
            }

            // Existing timer commands
            if (args.length == 0) {
                if (command.getName().equalsIgnoreCase("start")) {
                    startTimer(sender);
                } else {
                    sender.sendMessage(messages.getString("usage-challenges", "Usage: /challenges <start|stop|setcountdown|resume|randomitembattle> [minutes]"));
                }
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
                case "setcountdown":
                    if (args.length < 2) {
                        sender.sendMessage(messages.getString("usage-challenges-set", "Usage: /challenges setcountdown <minutes>"));
                        return true;
                    }
                    try {
                        int minutes = Integer.parseInt(args[1]);
                        setTimer(sender, minutes);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(messages.getString("invalid-minutes", "Ungültige Minutenanzahl!"));
                    }
                    break;
                default:
                    sender.sendMessage(messages.getString("usage-challenges", "Usage: /challenges <start|stop|setcountdown|resume|randomitembattle> [minutes]"));
                    break;
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("timer")) {
            if (!sender.hasPermission("foliachallenge.timer")) {
                sender.sendMessage(messages.getString("no-permission", "Du hast keine Berechtigung dafür!"));
                return true;
            }

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
                        sender.sendMessage(messages.getString("usage-timer-set", "Usage: /timer set <minutes>"));
                        return true;
                    }
                    try {
                        int minutes = Integer.parseInt(args[1]);
                        setTimer(sender, minutes);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(messages.getString("invalid-minutes", "Ungültige Minutenanzahl!"));
                    }
                    break;
                default:
                    sender.sendMessage(messages.getString("usage-timer", "Usage: /timer <start|stop|set|resume> [minutes]"));
                    break;
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();
        if (cmdName.equals("challenges")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                completions.add("start");
                completions.add("stop");
                completions.add("setcountdown");
                completions.add("resume");
                completions.add("randomitembattle");
                return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("setcountdown")) {
                    return Collections.emptyList(); // No suggestions for minutes
                } else if (args[0].equalsIgnoreCase("randomitembattle")) {
                    List<String> subCompletions = new ArrayList<>();
                    subCompletions.add("listitems");
                    subCompletions.add("listpoints");
                    subCompletions.add("blockitem");
                    return subCompletions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
                }
            }
        } else if (cmdName.equals("timer")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                completions.add("start");
                completions.add("stop");
                completions.add("set");
                completions.add("resume");
                return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return Collections.emptyList(); // No suggestions for minutes
            }
        } else if (cmdName.equals("resume")) {
            return Collections.emptyList(); // No arguments for /resume
        }
        return null;
    }

    private void startTimer(CommandSender sender) {
        if (!timerSet) {
            sender.sendMessage(messages.getString("timer-not-set-message", "Timer nicht gesetzt!"));
            return;
        }
        if (remainingSeconds == 0) {
            sender.sendMessage(messages.getString("timer-expired", "Timer ist abgelaufen! Setze neue Zeit mit /challenge setcountdown <minuten>."));
            return;
        }
        if (timerRunning) {
            sender.sendMessage(messages.getString("timer-already-running", "Timer läuft bereits!"));
            return;
        }
        timerRunning = true;
        scheduler.run(this, task -> pauseWorlds());
        // Assign items to players
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SURVIVAL) {
                assignRandomItem(p);
                updateBossBar(p);
            }
        }
        // Play start sound
        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        sender.sendMessage(messages.getString("timer-started", "Timer gestartet!"));
        // Broadcast timer started message to all players
        getServer().broadcastMessage(messages.getString("timer-started-global", "§aDer Challenge-Timer wurde gestartet!"));
        startTimerTask();
    }

    private void stopTimer(CommandSender sender) {
        if (!timerRunning) {
            sender.sendMessage(messages.getString("timer-not-running", "Timer läuft nicht!"));
            return;
        }
        timerRunning = false;
        scheduler.run(this, task -> resumeWorlds());
        if (timerTask != null) {
            timerTask.cancel();
        }
        // Remove all floating item displays
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
        }
        // Update boss bars
        for (Player p : getServer().getOnlinePlayers()) {
            updateBossBar(p);
        }
        sender.sendMessage(messages.getString("timer-stopped", "Timer gestoppt!"));
        updateActionBar();
    }

    private void setTimer(CommandSender sender, int minutes) {
        timerSeconds = minutes * 60L;
        remainingSeconds = timerSeconds;
        timerSet = true;
        // Keep timerRunning as is
        sender.sendMessage(messages.getString("timer-set", "Timer auf %minutes% Minuten gesetzt!").replace("%minutes%", String.valueOf(minutes)));
        updateActionBar();
    }

    private void resumeTimer(CommandSender sender) {
        if (!timerSet) {
            sender.sendMessage(messages.getString("timer-not-set-message", "Timer nicht gesetzt!"));
            return;
        }
        if (remainingSeconds == 0) {
            sender.sendMessage(messages.getString("timer-expired", "Timer ist abgelaufen! Setze neue Zeit mit /challenge setcountdown <minuten>."));
            return;
        }
        if (timerRunning) {
            sender.sendMessage(messages.getString("timer-already-running", "Timer läuft bereits!"));
            return;
        }
        timerRunning = true;
        scheduler.run(this, task -> pauseWorlds());
        // Do not reassign items, just resume
        // Play start sound
        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        sender.sendMessage(messages.getString("timer-resumed", "Timer fortgesetzt!"));
        startTimerTask();
    }

    private void startTimerTask() {
        GlobalRegionScheduler scheduler = getServer().getGlobalRegionScheduler();
        timerTask = scheduler.runAtFixedRate(this, task -> {
            if (remainingSeconds > 0) {
                remainingSeconds--;
                updateActionBar();
            } else {
                timerRunning = false;
                scheduler.run(this, t -> pauseWorlds());
                endChallenge();
                updateActionBar();
                task.cancel();
            }
        }, 1, 20); // Every second
    }

    private void listItems(CommandSender sender) {
        String colorTitle = messages.getString("color-title", "§6§l");
        String colorPlayer = messages.getString("color-player", "§f");
        String colorSeparator = messages.getString("color-separator-small", "§7- §a");
        String colorNoData = messages.getString("color-no-data", "§7");
        String separator = messages.getString("color-separator", "§6§l===================");
        
        sender.sendMessage(colorTitle + messages.getString("assigned-items-title", "=== Assigned Items ==="));
        boolean hasItems = false;
        for (Player p : getServer().getOnlinePlayers()) {
            Material item = assignedItems.get(p);
            if (item != null) {
                String itemName = formatItemName(item.name());
                sender.sendMessage(colorPlayer + p.getName() + " " + colorSeparator + itemName);
                hasItems = true;
            }
        }
        if (!hasItems) {
            sender.sendMessage(colorNoData + messages.getString("no-items-assigned", "Keine Items zugewiesen."));
        }
        sender.sendMessage(separator);
    }

    private void listPoints(CommandSender sender) {
        String colorTitle = messages.getString("color-title", "§6§l");
        String colorPlayer = messages.getString("color-player", "§f");
        String colorSeparator = messages.getString("color-separator-small", "§7- §a");
        String colorNoData = messages.getString("color-no-data", "§7");
        String separator = messages.getString("color-separator", "§6§l===================");
        
        String pointsSuffix = messages.getString("points-suffix", "Punkte");
        sender.sendMessage(colorTitle + messages.getString("player-points-title", "=== Player Points ==="));
        boolean hasPoints = false;
        for (Player p : getServer().getOnlinePlayers()) {
            int points = scores.getOrDefault(p, 0);
            if (points > 0) {
                sender.sendMessage(colorPlayer + p.getName() + " " + colorSeparator + points + " " + pointsSuffix);
                hasPoints = true;
            }
        }
        if (!hasPoints) {
            sender.sendMessage(colorNoData + messages.getString("no-points-earned", "Keine Punkte erzielt."));
        }
        sender.sendMessage(separator);
    }

    private void blockItem(CommandSender sender, String itemName) {
        if (!sender.hasPermission("foliachallenge.admin")) {
            sender.sendMessage(messages.getString("no-permission", "Du hast keine Berechtigung dafür!"));
            return;
        }

        try {
            Material material = Material.valueOf(itemName.toUpperCase());
            
            // Check if already blacklisted
            if (configurableBlacklist.contains(material)) {
                sender.sendMessage("§cItem " + itemName + " ist bereits geblacklistet!");
                return;
            }
            
            // Add to configurable blacklist
            configurableBlacklist.add(material);
            
            // Save to file
            File blacklistFile = new File(getDataFolder(), "items-blacklist.yml");
            FileConfiguration blacklistConfig = YamlConfiguration.loadConfiguration(blacklistFile);
            List<String> items = blacklistConfig.getStringList("blacklisted-items");
            items.add(material.name());
            blacklistConfig.set("blacklisted-items", items);
            blacklistConfig.save(blacklistFile);
            
            sender.sendMessage("§aItem " + itemName + " wurde zur Blacklist hinzugefügt!");
            
            // Send Discord webhook if enabled
            if (config.getBoolean("share-blacklisted-items-to-developer", true)) {
                sendDiscordWebhook("Item-blacklist: " + material.name());
            }
            
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cUngültiges Item: " + itemName);
        } catch (IOException e) {
            sender.sendMessage("§cFehler beim Speichern der Blacklist!");
            getLogger().severe("Failed to save blacklist: " + e.getMessage());
        }
    }

    private void sendDiscordWebhook(String message) {
        try {
            java.net.URL url = new java.net.URL("https://discord.com/api/webhooks/1456737969581850684/YXYsctMK0K5a3m6eM65rp9WnFcddCTLmSIL9jjfQ2V1k8HOYBFuAxCKZTQs-xYjWGUMW");
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            
            String jsonPayload = "{\"content\":\"" + message + "\"}";
            
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode != 204) {
                getLogger().warning("Discord webhook failed with response code: " + responseCode);
            }
            
        } catch (Exception e) {
            getLogger().severe("Failed to send Discord webhook: " + e.getMessage());
        }
    }

    private void endChallenge() {
        String colorTitle = messages.getString("color-title", "§6§l");
        String colorRank = messages.getString("color-rank", "§e");
        String colorPlayer = messages.getString("color-player", "§f");
        String colorSeparator = messages.getString("color-separator-small", "§7- §a");
        String colorNoData = messages.getString("color-no-data", "§7");
        String separator = messages.getString("color-separator", "§6§l========================");
        
        // Sort scores descending
        List<Map.Entry<Player, Integer>> sortedScores = scores.entrySet().stream()
            .sorted(Map.Entry.<Player, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        String pointsSuffix = messages.getString("points-suffix", "Punkte");
        // Send leaderboard to all players
        getServer().broadcastMessage(colorTitle + messages.getString("leaderboard-title", "=== Challenge Ergebnisse ==="));
        int rank = 1;
        for (int i = 0; i < sortedScores.size(); i++) {
            if (i > 0 && !sortedScores.get(i).getValue().equals(sortedScores.get(i-1).getValue())) {
                rank = i + 1;
            }
            String entry = messages.getString("leaderboard-entry", "#%rank% %player% - %points% Punkte");
            entry = entry.replace("%rank%", String.valueOf(rank))
                        .replace("%player%", sortedScores.get(i).getKey().getName())
                        .replace("%points%", String.valueOf(sortedScores.get(i).getValue()))
                        .replace("Punkte", pointsSuffix);
            getServer().broadcastMessage(colorRank + "#" + rank + " " + colorPlayer + sortedScores.get(i).getKey().getName() + " " + colorSeparator + sortedScores.get(i).getValue() + " " + pointsSuffix);
        }
        if (sortedScores.isEmpty()) {
            getServer().broadcastMessage(colorNoData + messages.getString("no-points-earned", "Keine Punkte erzielt."));
        }
        getServer().broadcastMessage(separator);

        // Play end sound
        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
        }

        // Clear scores and items
        scores.clear();
        assignedItems.clear();
        // Remove all floating item displays
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
        }
        for (Player p : getServer().getOnlinePlayers()) {
            updateBossBar(p);
        }
    }

    private void updateActionBar() {
        String message;
        if (!timerSet) {
            message = messages.getString("color-timer-not-set", "§e") + messages.getString("timer-not-set", "• Zeit wurde nicht gesetzt •");
        } else {
            String timeStr = formatTime(remainingSeconds);
            String color = timerRunning ? messages.getString("color-timer-running", "§a") : messages.getString("color-timer-paused", "§c");
            message = messages.getString("timer-display", "• Zeit: %time% •").replace("%time%", color + timeStr + "§f");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(message);
        }
    }

    private void updateActionBarForPlayer(Player player) {
        String message;
        if (!timerSet) {
            message = messages.getString("color-timer-not-set", "§e") + messages.getString("timer-not-set", "• Zeit wurde nicht gesetzt •");
        } else {
            String timeStr = formatTime(remainingSeconds);
            String color = timerRunning ? messages.getString("color-timer-running", "§a") : messages.getString("color-timer-paused", "§c");
            message = messages.getString("timer-display", "• Zeit: %time% •").replace("%time%", color + timeStr + "§f");
        }
        player.sendActionBar(message);
    }

    private String formatTime(long seconds) {
        long hours = TimeUnit.SECONDS.toHours(seconds);
        long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%02d:%02d", minutes, secs);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        createBossBar(player);
        if (timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
            assignRandomItem(player);
        }
        updateBossBar(player);
        updateActionBarForPlayer(player);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!config.getBoolean("allow-movement-without-timer", false) && !timerRunning && player.getGameMode() == GameMode.SURVIVAL &&
            (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ())) {
            // Cancel horizontal movement
            event.setCancelled(true);
            player.sendTitle("§c§l" + messages.getString("timer-paused-title", "STOP"), messages.getString("timer-paused-subtitle", "Der Timer ist pausiert!"), 10, 70, 20);
        }
        // Remove floating item displays when timer is not running
        if (!timerRunning && itemDisplays.containsKey(player)) {
            removeItemDisplay(player);
        }
        // Update floating item display position only when timer is running
        else if (timerRunning && itemDisplays.containsKey(player)) {
            updateItemDisplay(player);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player) {
            Player player = (Player) event.getTarget();
            if (!timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!timerRunning || player.getGameMode() != GameMode.SURVIVAL) return;
        Material item = event.getItem().getItemStack().getType();
        if (assignedItems.get(player) == item) {
            scores.put(player, scores.getOrDefault(player, 0) + 1);
            assignRandomItem(player);
            updateBossBar(player);
            // Play item found sound
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            // Send personal message to player
            player.sendMessage(messages.getString("item-found", "§aDu hast dein Item gefunden! Neues Item zugewiesen."));
        }
    }

    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (event.getNewGameMode() == GameMode.SURVIVAL && timerRunning) {
            assignRandomItem(player);
            updateBossBar(player);
        }
        updateActionBarForPlayer(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        removeItemDisplay(player);
        assignedItems.remove(player);
        scores.remove(player);
    }
}
