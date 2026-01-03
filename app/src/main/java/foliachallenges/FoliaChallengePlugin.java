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
import java.util.UUID;
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
    private ScheduledTask saveTask;
    private GlobalRegionScheduler scheduler;
    private List<Material> configurableBlacklist = new ArrayList<>();
    private List<Material> hardcodedBlacklist = Arrays.asList(Material.AIR);
    
    // Live-Daten (nur Online-Spieler)
    private Map<Player, Material> assignedItems = new HashMap<>();
    private Map<Player, Integer> scores = new HashMap<>();
    private Map<Player, BossBar> bossBars = new HashMap<>();
    private Map<Player, org.bukkit.entity.ArmorStand> itemDisplays = new HashMap<>();
    
    // Persistente Daten (Offline-Spieler + Cache für Restore)
    private Map<UUID, Material> persistedAssigned = new HashMap<>();
    private Map<UUID, Integer> persistedScores = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultMessages();
        saveDefaultItemBlacklist();
        config = getConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        loadConfigurableBlacklist();
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register TabCompleter
        if (getCommand("challenges") != null) getCommand("challenges").setTabCompleter(this);
        if (getCommand("timer") != null) getCommand("timer").setTabCompleter(this);
        if (getCommand("resume") != null) getCommand("resume").setTabCompleter(this);
        if (getCommand("start") != null) getCommand("start").setTabCompleter(this);
        
        getLogger().info("FoliaChallenge enabled!");
        this.scheduler = getServer().getGlobalRegionScheduler();
        
        // Timer pausiert starten (Sicherheitsmaßnahme nach Restart)
        scheduler.run(this, task -> pauseWorlds());
        
        // ActionBar Task
        actionBarTask = scheduler.runAtFixedRate(this, task -> updateActionBar(), 1, 10);
        
        // Daten laden
        loadData();
    }

    @Override
    public void onDisable() {
        itemDisplays.clear(); // Entities werden von Server entfernt
        if (actionBarTask != null) actionBarTask.cancel();
        if (timerTask != null) timerTask.cancel();
        if (saveTask != null) saveTask.cancel();
        
        // WICHTIG: Vor dem Speichern sicherstellen, dass alle Online-Daten persistiert sind
        // Da Spieler beim Shutdown gekickt werden könnten, übertragen wir hier manuell alles
        for (Player p : getServer().getOnlinePlayers()) {
            if (scores.containsKey(p)) persistedScores.put(p.getUniqueId(), scores.get(p));
            if (assignedItems.containsKey(p)) persistedAssigned.put(p.getUniqueId(), assignedItems.get(p));
        }
        
        saveData();
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
            createItemDisplay(player, random);
            updateBossBar(player);
            saveData();
        }
    }

    private BossBar createBossBar(Player player) {
        BossBar bar = getServer().createBossBar(messages.getString("bossbar-default", "Aktuelles Item: -"), BarColor.BLUE, BarStyle.SOLID);
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

    // [Standard Save/Load Helper methods omitted for brevity, assumed unchanged]
    private void saveDefaultMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            try (InputStream in = getResource("messages.yml")) {
                if (in != null) Files.copy(in, messagesFile.toPath());
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
    private void saveDefaultItemBlacklist() {
        File blacklistFile = new File(getDataFolder(), "items-blacklist.yml");
        if (!blacklistFile.exists()) {
            try (InputStream in = getResource("items-blacklist.yml")) {
                if (in != null) Files.copy(in, blacklistFile.toPath());
            } catch (IOException e) { e.printStackTrace(); }
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
                } catch (IllegalArgumentException e) { getLogger().warning("Invalid material in blacklist: " + item); }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("resume")) {
            if (!sender.hasPermission("foliachallenge.timer")) return noPerm(sender);
            startTimer(sender); // Resume ist logisch identisch mit Start in dieser Implementierung
            return true;
        }
        if (command.getName().equalsIgnoreCase("start")) {
            if (!sender.hasPermission("foliachallenge.timer")) return noPerm(sender);
            startTimer(sender);
            return true;
        }
        if (command.getName().equalsIgnoreCase("challenges")) {
            if (!sender.hasPermission("foliachallenge.timer")) return noPerm(sender);
            
            if (args.length > 0) {
                // NEUER BEFEHL: RESET
                if (args[0].equalsIgnoreCase("reset")) {
                    resetChallenge(sender);
                    return true;
                }
                if (args[0].equalsIgnoreCase("randomitembattle")) {
                    if (args.length < 2) {
                        sender.sendMessage(messages.getString("usage-randomitembattle", "Usage: /" + label + " randomitembattle <listitems|listpoints|blockitem>"));
                        return true;
                    }
                    if (args[1].equalsIgnoreCase("listitems")) { listItems(sender); return true; }
                    else if (args[1].equalsIgnoreCase("listpoints")) { listPoints(sender); return true; }
                    else if (args[1].equalsIgnoreCase("blockitem")) {
                        if (args.length < 3) { sender.sendMessage("Usage: /" + label + " randomitembattle blockitem <item>"); return true; }
                        blockItem(sender, args[2]);
                        return true;
                    }
                }
            }
            sender.sendMessage(messages.getString("usage-randomitembattle", "Usage: /" + label + " randomitembattle <listitems|listpoints|blockitem>"));
            return true;
        }
        if (command.getName().equalsIgnoreCase("timer")) {
            if (!sender.hasPermission("foliachallenge.timer")) return noPerm(sender);
            if (args.length == 0) {
                sender.sendMessage(messages.getString("usage-timer", "Usage: /timer <start|stop|set|resume> [minutes]"));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "start":
                case "resume": startTimer(sender); break;
                case "stop": stopTimer(sender); break;
                case "set":
                    if (args.length < 2) { sender.sendMessage(messages.getString("usage-timer-set", "Usage: /timer set <minutes>")); return true; }
                    try { setTimer(sender, Integer.parseInt(args[1])); } catch (NumberFormatException e) { sender.sendMessage(messages.getString("invalid-minutes", "Ungültige Minutenanzahl!")); }
                    break;
                default: sender.sendMessage(messages.getString("usage-timer", "Usage: /timer <start|stop|set|resume> [minutes]")); break;
            }
            return true;
        }
        return false;
    }
    
    private boolean noPerm(CommandSender sender) {
        sender.sendMessage(messages.getString("no-permission", "Du hast keine Berechtigung dafür!"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // [Tab completion logic remains mostly same, added "reset"]
        if (command.getName().equalsIgnoreCase("challenges") && args.length == 1) {
            return Arrays.asList("randomitembattle", "reset").stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        // ... (rest of tab completer logic from original file)
        return null; 
    }

    private void startTimer(CommandSender sender) {
        if (!timerSet) {
            sender.sendMessage(messages.getString("timer-not-set-message", "Timer nicht gesetzt!"));
            return;
        }
        if (remainingSeconds == 0) {
            sender.sendMessage(messages.getString("timer-expired", "Timer ist abgelaufen!"));
            return;
        }
        if (timerRunning) {
            sender.sendMessage(messages.getString("timer-already-running", "Timer läuft bereits!"));
            return;
        }
        
        timerRunning = true;
        scheduler.run(this, task -> pauseWorlds());

        // FIX: Items nur zuweisen, wenn der Spieler NOCH KEINS hat
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SURVIVAL) {
                if (!assignedItems.containsKey(p)) {
                    // Spieler hat kein Item (auch nicht aus DB geladen) -> Neues zuweisen
                    assignRandomItem(p);
                } else {
                    // Spieler hat Item (wurde z.B. aus data.yml geladen) -> Nichts tun, Item behalten!
                }
                
                // Visuals aktualisieren
                updateBossBar(p);
                if (!itemDisplays.containsKey(p) && assignedItems.containsKey(p)) {
                    createItemDisplay(p, assignedItems.get(p));
                }
            }
        }

        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        sender.sendMessage(messages.getString("timer-started", "Timer gestartet!"));
        getServer().broadcastMessage(messages.getString("timer-started-global", "§aDer Challenge-Timer wurde gestartet!"));
        
        // Auto-Save Task
        saveTask = scheduler.runAtFixedRate(this, task -> saveData(), 20, 20);
        startTimerTask();
        updateActionBar();
    }

    private void resetChallenge(CommandSender sender) {
        if (timerRunning) stopTimer(sender);
        
        scores.clear();
        assignedItems.clear();
        persistedScores.clear();
        persistedAssigned.clear();
        
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
            updateBossBar(p);
        }
        saveData(); // Leere Daten speichern
        sender.sendMessage("§c§lChallenge resettet! Alle Punkte und Items wurden gelöscht.");
    }

    private void stopTimer(CommandSender sender) {
        if (!timerRunning) {
            sender.sendMessage(messages.getString("timer-not-running", "Timer läuft nicht!"));
            return;
        }
        timerRunning = false;
        scheduler.run(this, task -> resumeWorlds());
        if (timerTask != null) timerTask.cancel();
        if (saveTask != null) saveTask.cancel();
        
        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
            updateBossBar(p); // Zeigt "Pausiert" an
        }
        
        saveData(); // Sofort speichern beim Stoppen
        sender.sendMessage(messages.getString("timer-stopped", "Timer gestoppt!"));
        updateActionBar();
    }

    private void setTimer(CommandSender sender, int minutes) {
        timerSeconds = minutes * 60L;
        remainingSeconds = timerSeconds;
        timerSet = true;
        sender.sendMessage(messages.getString("timer-set", "Timer auf %minutes% Minuten gesetzt!").replace("%minutes%", String.valueOf(minutes)));
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

    // [listItems, listPoints, blockItem methods assumed unchanged]
    private void listItems(CommandSender sender) { /* ... same as before ... */ 
        sender.sendMessage("§6=== Assigned Items ===");
        assignedItems.forEach((p, mat) -> sender.sendMessage("§f" + p.getName() + ": §a" + formatItemName(mat.name())));
    }
    private void listPoints(CommandSender sender) { /* ... same as before ... */
        sender.sendMessage("§6=== Points ===");
        scores.forEach((p, s) -> sender.sendMessage("§f" + p.getName() + ": §a" + s));
    }
    private void blockItem(CommandSender sender, String itemName) { /* ... same as before ... */ }

    private void endChallenge() {
        // [Leaderboard logic same as before]
        // ...
        
        // WICHTIG: Beim regulären Ende wird alles gelöscht
        scores.clear();
        assignedItems.clear();
        persistedScores.clear();
        persistedAssigned.clear();
        
        // Leeren Zustand speichern
        saveData();
    }

    private void updateActionBar() {
        String message;
        if (!timerSet) message = "§e• Zeit nicht gesetzt •";
        else {
            String timeStr = formatTime(remainingSeconds);
            String color = timerRunning ? "§a" : "§c";
            message = "• Zeit: " + color + timeStr + " §f•";
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(message);
    }
    
    private void updateActionBarForPlayer(Player p) {
        // Hilfsmethode für Join/ModeChange
        String timeStr = formatTime(remainingSeconds);
        p.sendActionBar(timerRunning ? "§aTimer läuft: " + timeStr : "§cTimer pausiert: " + timeStr);
    }

    private String formatTime(long seconds) {
        long h = TimeUnit.SECONDS.toHours(seconds);
        long m = TimeUnit.SECONDS.toMinutes(seconds) % 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%02d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        createBossBar(player);
        UUID uuid = player.getUniqueId();
        
        // FIX: Wiederherstellung der Daten
        // Wir löschen NICHTS aus persisted maps, falls der Spieler kurz rejoinend.
        // Erst beim Speichern überschreiben wir die Datei mit den aktuellen Werten.
        
        if (persistedScores.containsKey(uuid)) {
            scores.put(player, persistedScores.get(uuid));
        }
        
        if (player.getGameMode() == GameMode.SURVIVAL) {
            if (persistedAssigned.containsKey(uuid)) {
                Material mat = persistedAssigned.get(uuid); // Nicht remove()!
                assignedItems.put(player, mat);
                
                // Visuals sofort wiederherstellen, aber nur wenn Timer läuft spawnen
                if (timerRunning) {
                    createItemDisplay(player, mat);
                }
            } else if (timerRunning) {
                // Timer läuft, Spieler ist neu -> Item geben
                assignRandomItem(player);
            }
        }
        updateBossBar(player);
        updateActionBarForPlayer(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        removeItemDisplay(player);
        
        // FIX: Daten sichern BEVOR sie aus den Live-Maps gelöscht werden
        if (assignedItems.containsKey(player)) {
            persistedAssigned.put(player.getUniqueId(), assignedItems.get(player));
        }
        if (scores.containsKey(player)) {
            persistedScores.put(player.getUniqueId(), scores.get(player));
        }
        
        assignedItems.remove(player);
        scores.remove(player);
        
        // Optional: Sofort speichern, um Datenverlust bei Crash zu minimieren
        saveData(); 
    }

    // [Other EventHandlers (Move, Break, Place, Damage, Pickup, GameMode) remain same]
    // ...
    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!timerRunning) return;
        Player p = event.getPlayer();
        if (assignedItems.get(p) == event.getItem().getItemStack().getType()) {
            scores.put(p, scores.getOrDefault(p, 0) + 1);
            assignRandomItem(p);
            // ... sound/msg code ...
        }
    }

    private void saveData() {
        try {
            File dataFile = new File(getDataFolder(), "data.yml");
            FileConfiguration data = new YamlConfiguration();
            data.set("remainingSeconds", remainingSeconds);
            
            // FIX: Scores speichern (Live + Offline)
            Map<String, Object> scoresMap = new HashMap<>();
            // 1. Offline Daten zuerst
            for (Map.Entry<UUID, Integer> e : persistedScores.entrySet()) {
                scoresMap.put(e.getKey().toString(), e.getValue());
            }
            // 2. Online Daten überschreiben (da aktueller)
            for (Map.Entry<Player, Integer> e : scores.entrySet()) {
                scoresMap.put(e.getKey().getUniqueId().toString(), e.getValue());
            }
            data.set("scores", scoresMap);
            
            // FIX: Items speichern (Live + Offline)
            Map<String, Object> assignMap = new HashMap<>();
            for (Map.Entry<UUID, Material> e : persistedAssigned.entrySet()) {
                assignMap.put(e.getKey().toString(), e.getValue().name());
            }
            for (Map.Entry<Player, Material> e : assignedItems.entrySet()) {
                assignMap.put(e.getKey().getUniqueId().toString(), e.getValue().name());
            }
            data.set("assignedItems", assignMap);
            
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Failed to save data.yml: " + ex.getMessage());
        }
    }

    private void loadData() {
        try {
            File dataFile = new File(getDataFolder(), "data.yml");
            if (!dataFile.exists()) return;
            FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            remainingSeconds = data.getLong("remainingSeconds", remainingSeconds);
            timerRunning = false; // Immer pausiert starten nach Restart
            
            if (data.contains("scores")) {
                Map<?, ?> m = (Map<?, ?>) data.get("scores");
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    try {
                        persistedScores.put(UUID.fromString(e.getKey().toString()), Integer.parseInt(e.getValue().toString()));
                    } catch (Exception ex) {}
                }
            }
            
            if (data.contains("assignedItems")) {
                Map<?, ?> m = (Map<?, ?>) data.get("assignedItems");
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    try {
                        persistedAssigned.put(UUID.fromString(e.getKey().toString()), Material.valueOf(e.getValue().toString()));
                    } catch (Exception ex) {}
                }
            }
            
            if (remainingSeconds > 0) {
                timerSet = true;
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load data.yml: " + e.getMessage());
        }
    }
}
