package foliachallenges;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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

public class FoliaChallengePlugin extends JavaPlugin implements Listener {

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultMessages();
        saveDefaultItemBlacklist();
        config = getConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        loadConfigurableBlacklist();
        getServer().getPluginManager().registerEvents(this, this);
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
        }
    }

    private BossBar createBossBar(Player player) {
        BossBar bar = getServer().createBossBar("Aktuelles Item: -", BarColor.BLUE, BarStyle.SOLID);
        bar.addPlayer(player);
        bossBars.put(player, bar);
        return bar;
    }

    private void updateBossBar(Player player) {
        BossBar bar = bossBars.get(player);
        if (bar != null) {
            Material item = assignedItems.get(player);
            if (item != null) {
                String itemName = formatItemName(item.name());
                bar.setTitle("Aktuelles Item: " + itemName);
            } else {
                bar.setTitle("Timer pausiert");
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
        if (command.getName().equalsIgnoreCase("challenge") || command.getName().equalsIgnoreCase("start")) {
            if (!sender.hasPermission("foliachallenge.timer")) {
                sender.sendMessage(messages.getString("no-permission", "Du hast keine Berechtigung dafür!"));
                return true;
            }

            if (args.length == 0) {
                if (command.getName().equalsIgnoreCase("start")) {
                    startTimer(sender);
                } else {
                    sender.sendMessage(messages.getString("usage-timer", "Usage: /challenge <start|stop|setcountdown|resume> [minutes]"));
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
                        sender.sendMessage(messages.getString("usage-timer-set", "Usage: /challenge setcountdown <minutes>"));
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
                    sender.sendMessage(messages.getString("usage-timer", "Usage: /challenge <start|stop|setcountdown|resume> [minutes]"));
                    break;
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("challenge")) {
            if (args.length == 1) {
                return Arrays.asList("start", "stop", "setcountdown", "resume");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("setcountdown")) {
                return Collections.emptyList();
            }
        }
        return null;
    }

    private void startTimer(CommandSender sender) {
        if (!timerSet) {
            sender.sendMessage(messages.getString("timer-not-set-message", "Timer nicht gesetzt!"));
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
        sender.sendMessage(messages.getString("timer-started", "Timer gestartet!"));
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

    private void endChallenge() {
        // Sort scores descending
        List<Map.Entry<Player, Integer>> sortedScores = scores.entrySet().stream()
            .sorted(Map.Entry.<Player, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        // Send leaderboard to all players
        getServer().broadcastMessage("§6§l=== Challenge Ergebnisse ===");
        int rank = 1;
        for (int i = 0; i < sortedScores.size(); i++) {
            if (i > 0 && !sortedScores.get(i).getValue().equals(sortedScores.get(i-1).getValue())) {
                rank = i + 1;
            }
            getServer().broadcastMessage("§e#" + rank + " §f" + sortedScores.get(i).getKey().getName() + " §7- §a" + sortedScores.get(i).getValue() + " Punkte");
        }
        if (sortedScores.isEmpty()) {
            getServer().broadcastMessage("§7Keine Punkte erzielt.");
        }
        getServer().broadcastMessage("§6§l========================");

        // Clear scores and items
        scores.clear();
        assignedItems.clear();
        for (Player p : getServer().getOnlinePlayers()) {
            updateBossBar(p);
        }
    }

    private void updateActionBar() {
        String message;
        if (!timerSet) {
            message = messages.getString("timer-not-set", "• Zeit wurde nicht gesetzt •");
        } else {
            String timeStr = formatTime(remainingSeconds);
            String color = timerRunning ? "§a" : "§c"; // Green if running, red if paused
            message = messages.getString("timer-display", "• Zeit: %time% •").replace("%time%", color + timeStr);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(message);
        }
    }

    private void updateActionBarForPlayer(Player player) {
        String message;
        if (!timerSet) {
            message = messages.getString("timer-not-set", "• Zeit wurde nicht gesetzt •");
        } else {
            String timeStr = formatTime(remainingSeconds);
            String color = timerRunning ? "§a" : "§c"; // Green if running, red if paused
            message = messages.getString("timer-display", "• Zeit: %time% •").replace("%time%", color + timeStr);
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
}
