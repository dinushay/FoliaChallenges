package foliachallenges;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FoliaChallengePlugin extends JavaPlugin implements Listener, TabCompleter, CommandExecutor {

    // --- Constants & Config ---
    private static final String PREFIX = "§8§l┃ §bFoliaChallenges §8┃§7 ";
    private String settingsGUITitle;

    private FileConfiguration config;
    private FileConfiguration messages;

    // --- State Variables ---
    private long timerSeconds = 0;
    private long remainingSeconds = 0;
    private boolean timerRunning = false;
    private boolean timerSet = false;

    // --- Data Storage ---
    private final List<Material> configurableBlacklist = new ArrayList<>();
    private List<Material> cachedValidMaterials = new ArrayList<>(); // Cache for performance
    
    private final Map<UUID, Material> assignedItems = new HashMap<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> jokerCounts = new HashMap<>();
    private final Map<UUID, Integer> storedJokers = new HashMap<>();
    
    // --- Visuals ---
    private final Map<Player, BossBar> bossBars = new HashMap<>();
    private final Map<Player, ArmorStand> itemDisplays = new HashMap<>();

    // --- Settings ---
    private int defaultJokers = 0;
    private boolean allowDuplicateTargets = false;
    private boolean giveItemOnJoker = false;

    // --- Schedulers ---
    private ScheduledTask actionBarTask;
    private ScheduledTask timerTask;
    private ScheduledTask saveTask;
    private GlobalRegionScheduler scheduler;

    @Override
    public void onEnable() {
        // 1. Config & Resources
        saveDefaultConfig();
        saveResourceIfNotExists("messages.yml");
        saveResourceIfNotExists("items-blacklist.yml");
        
        reloadPluginData();

        // 2. Setup
        this.scheduler = getServer().getGlobalRegionScheduler();
        getServer().getPluginManager().registerEvents(this, this);
        
        registerCommands();

        // 3. World Cleanup logic
        cleanupOldWorlds();

        // 4. Tasks
        scheduler.run(this, task -> pauseWorlds()); // Ensure worlds are paused on startup
        actionBarTask = scheduler.runAtFixedRate(this, task -> updateActionBar(), 1, 10);
        
        // 5. Load Data
        loadData();
        getLogger().info(messages.getString("plugin-enabled", "FoliaChallenge enabled!"));
    }

    @Override
    public void onDisable() {
        // Cleanup displays
        for (ArmorStand stand : itemDisplays.values()) {
            if (stand != null && !stand.isDead()) stand.remove();
        }
        itemDisplays.clear();

        // Cancel tasks
        if (actionBarTask != null) actionBarTask.cancel();
        if (timerTask != null) timerTask.cancel();
        if (saveTask != null) saveTask.cancel();

        saveData();
        getLogger().info(messages.getString("plugin-disabled", "FoliaChallenge disabled!"));
    }

    private void reloadPluginData() {
        config = getConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        defaultJokers = config.getInt("default-jokers", 0);
        allowDuplicateTargets = config.getBoolean("allow-duplicate-targets", false);
        giveItemOnJoker = config.getBoolean("give-item-on-joker", false);
        
        settingsGUITitle = messages.getString("settings-gui-color", "§b§l") + 
                           messages.getString("settings-gui-title", "Random Item Battle Settings");
        
        loadConfigurableBlacklist();
        refreshMaterialCache();
    }

    private void saveResourceIfNotExists(String filename) {
        File file = new File(getDataFolder(), filename);
        if (!file.exists()) {
            saveResource(filename, false);
        }
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("challenges")).setExecutor(this);
        Objects.requireNonNull(getCommand("timer")).setExecutor(this);
        Objects.requireNonNull(getCommand("reset")).setExecutor(this);
        Objects.requireNonNull(getCommand("start")).setExecutor(this);
        Objects.requireNonNull(getCommand("settings")).setExecutor(this);
    }

    // =================================================================================
    //                               COMMAND LOGIC
    // =================================================================================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp() && !sender.hasPermission("foliachallenges.admin")) {
            sender.sendMessage(PREFIX + messages.getString("no-permission", "§cYou do not have permission!"));
            return true;
        }

        String cmdName = command.getName().toLowerCase();

        switch (cmdName) {
            case "start":
                startTimer(sender);
                return true;

            case "reset":
                return handleResetCommand(sender, args);

            case "settings":
                if (sender instanceof Player) {
                    openSettingsGUI((Player) sender);
                } else {
                    sender.sendMessage(PREFIX + "Only players can open settings.");
                }
                return true;

            case "timer":
                return handleTimerCommand(sender, args);

            case "challenges":
                return handleChallengesCommand(sender, args, label);
        }
        return false;
    }

    private boolean handleResetCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            resetChallengeData(sender);
            prepareWorldReset(sender);
        } else {
            sender.sendMessage(PREFIX + messages.getString("reset-warning-1", "§4§lWARNING: §cPlease confirm reset!"));
            sender.sendMessage(PREFIX + messages.getString("reset-warning-2", "§cThis clears data and generates a NEW WORLD!"));
            sender.sendMessage(PREFIX + messages.getString("reset-confirm-usage", "§7Use §c/reset confirm§7 to continue."));
        }
        return true;
    }

    private boolean handleTimerCommand(CommandSender sender, String[] args) {
        if (args.length == 0) return sendUsage(sender);
        
        switch (args[0].toLowerCase()) {
            case "start":
                startTimer(sender);
                break;
            case "stop":
                stopTimer(sender);
                break;
            case "set":
                if (args.length < 2) return sendUsage(sender);
                try {
                    setTimer(sender, Integer.parseInt(args[1]));
                } catch (NumberFormatException e) {
                    sender.sendMessage(PREFIX + messages.getString("invalid-minutes", "§4Invalid number!"));
                }
                break;
            default:
                sendUsage(sender);
        }
        return true;
    }

    private boolean handleChallengesCommand(CommandSender sender, String[] args, String label) {
        if (args.length == 0) return sendUsage(sender);

        String subCmd = args[0].toLowerCase();
        
        if (subCmd.equals("reload")) {
            reloadConfig();
            reloadPluginData();
            sender.sendMessage(PREFIX + "Configuration reloaded!");
            return true;
        }
        
        if (subCmd.equals("help")) {
            sendHelp(sender);
            return true;
        }

        if (subCmd.equals("randomitembattle")) {
            if (args.length < 2) return sendUsage(sender);
            switch (args[1].toLowerCase()) {
                case "listitems":
                    listItems(sender);
                    return true;
                case "listpoints":
                    listPoints(sender);
                    return true;
                case "settings":
                    if (sender instanceof Player) openSettingsGUI((Player) sender);
                    else sender.sendMessage(PREFIX + "Player only.");
                    return true;
                case "blockitem":
                    if (args.length < 3) return sendUsage(sender);
                    blockItem(sender, args[2]);
                    return true;
            }
        }
        return sendUsage(sender);
    }

    private boolean sendUsage(CommandSender sender) {
        sender.sendMessage(PREFIX + messages.getString("usage", "Use §6/challenges help"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("reset") && args.length == 1) return filter(args[0], Collections.singletonList("confirm"));
        
        if (cmdName.equals("timer") && args.length == 1) return filter(args[0], Arrays.asList("start", "stop", "set"));

        if (cmdName.equals("challenges")) {
            if (args.length == 1) return filter(args[0], Arrays.asList("randomitembattle", "reload", "help"));
            if (args.length == 2 && args[0].equalsIgnoreCase("randomitembattle")) {
                return filter(args[1], Arrays.asList("listitems", "listpoints", "blockitem", "settings"));
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("blockitem")) {
                return Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(Enum::name)
                        .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(String arg, List<String> options) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(arg.toLowerCase()))
                .collect(Collectors.toList());
    }

    // =================================================================================
    //                               GAME LOGIC
    // =================================================================================

    private void startTimer(CommandSender sender) {
        if (!timerSet) {
            sender.sendMessage(PREFIX + messages.getString("timer-not-set-message", "§cTimer not set!"));
            return;
        }
        if (remainingSeconds == 0) {
            sender.sendMessage(PREFIX + messages.getString("timer-expired", "§cTimer expired! Use /timer set."));
            return;
        }
        if (timerRunning) {
            sender.sendMessage(PREFIX + messages.getString("timer-already-running", "Timer already running!"));
            return;
        }

        timerRunning = true;
        scheduler.run(this, task -> resumeWorlds());

        // Assign items to players who don't have one
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SURVIVAL) {
                if (!assignedItems.containsKey(p.getUniqueId())) {
                    assignRandomItem(p);
                } else {
                    createItemDisplay(p, assignedItems.get(p.getUniqueId()));
                }
            }
            updateBossBar(p);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }

        getServer().broadcastMessage(PREFIX + messages.getString("timer-started-global", "§aTimer started!"));
        
        saveTask = scheduler.runAtFixedRate(this, task -> saveData(), 20, 20); // Autosave every second (safety)
        
        // Main Timer Loop
        timerTask = scheduler.runAtFixedRate(this, task -> {
            if (remainingSeconds > 0) {
                remainingSeconds--;
            } else {
                finishChallenge();
                task.cancel();
            }
            updateActionBar();
        }, 1, 20);
    }

    private void stopTimer(CommandSender sender) {
        if (!timerRunning) {
            sender.sendMessage(PREFIX + messages.getString("timer-not-running", "Timer is not running!"));
            return;
        }
        timerRunning = false;
        scheduler.run(this, task -> pauseWorlds());
        
        if (timerTask != null) timerTask.cancel();
        if (saveTask != null) saveTask.cancel();

        for (Player p : getServer().getOnlinePlayers()) {
            removeItemDisplay(p);
            updateBossBar(p);
        }

        getServer().broadcastMessage(PREFIX + messages.getString("timer-stopped-global", "§cTimer stopped!"));
        updateActionBar();
        saveData();
    }

    private void finishChallenge() {
        timerRunning = false;
        if (saveTask != null) saveTask.cancel();
        
        scheduler.run(this, t -> {
            pauseWorlds();
            for (Player p : getServer().getOnlinePlayers()) removeItemDisplay(p);
        });

        // Calculate rankings
        List<Map.Entry<UUID, Integer>> sortedScores = scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .collect(Collectors.toList());

        getServer().broadcastMessage(PREFIX + messages.getString("color-title", "§6§l") + "=== Challenge Results ===");
        if (sortedScores.isEmpty()) {
            getServer().broadcastMessage(PREFIX + messages.getString("no-results", "§7No results."));
        } else {
            int rank = 1;
            for (int i = 0; i < sortedScores.size(); i++) {
                if (i > 0 && !sortedScores.get(i).getValue().equals(sortedScores.get(i - 1).getValue())) rank = i + 1;
                
                String pName = Bukkit.getOfflinePlayer(sortedScores.get(i).getKey()).getName();
                String entry = messages.getString("leaderboard-entry", "#%rank% %player% - %points% Points")
                        .replace("%rank%", String.valueOf(rank))
                        .replace("%player%", pName != null ? pName : "Unknown")
                        .replace("%points%", String.valueOf(sortedScores.get(i).getValue()));
                getServer().broadcastMessage(PREFIX + "§e" + entry);
            }
        }
        getServer().broadcastMessage(PREFIX + "§6§l========================");

        for (Player p : getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
            updateBossBar(p);
        }
        saveData();
    }

    private void setTimer(CommandSender sender, int minutes) {
        timerSeconds = minutes * 60L;
        remainingSeconds = timerSeconds;
        timerSet = true;
        sender.sendMessage(PREFIX + messages.getString("timer-set", "Timer set to %minutes% min.").replace("%minutes%", String.valueOf(minutes)));
        updateActionBar();
        saveData();
    }

    private void assignRandomItem(Player player) {
        if (cachedValidMaterials.isEmpty()) refreshMaterialCache();

        if (!cachedValidMaterials.isEmpty()) {
            Material random = cachedValidMaterials.get(new Random().nextInt(cachedValidMaterials.size()));
            assignedItems.put(player.getUniqueId(), random);
            
            player.sendMessage(PREFIX + messages.getString("item-assigned", "Item to find: §e%item%").replace("%item%", formatItemName(random)));
            createItemDisplay(player, random);
            updateBossBar(player);
            saveData();
        } else {
            player.sendMessage(PREFIX + "§cNo items available to assign!");
        }
    }

    // =================================================================================
    //                               EVENTS
    // =================================================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SURVIVAL) {
            createBossBar(player);
            updateBossBar(player);
            
            // Re-assign item if needed or create display if existing
            if (assignedItems.containsKey(player.getUniqueId()) && timerRunning) {
                createItemDisplay(player, assignedItems.get(player.getUniqueId()));
            } else if (timerRunning) {
                assignRandomItem(player);
            }
        }

        // Initialize Jokers
        if (!jokerCounts.containsKey(player.getUniqueId())) {
            jokerCounts.put(player.getUniqueId(), defaultJokers);
        }
        updatePlayerJokers(player);
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
            player.sendMessage(PREFIX + messages.getString("item-found", "§aYou found §e%item%!").replace("%item%", formatItemName(assigned)));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            
            // Allow duplicates logic could be handled here if needed, 
            // currently assignRandomItem picks from full pool anyway.
            assignRandomItem(player);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        // Pause Movement Check
        if (!config.getBoolean("allow-movement-without-timer", false) && !timerRunning && player.getGameMode() == GameMode.SURVIVAL) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.setCancelled(true);
                player.sendTitle("§c§lSTOP!", messages.getString("timer-paused-subtitle", "Timer paused!"), 0, 40, 10);
            }
        }
        // Armor Stand Follow
        if (timerRunning && itemDisplays.containsKey(player)) {
            ArmorStand stand = itemDisplays.get(player);
            if (stand != null && stand.isValid()) {
                stand.teleportAsync(player.getLocation().add(0, 2.2, 0));
                // Optional: Velocity matching often looks glitchy with teleportAsync, keeping simple teleport is safer in Folia
            }
        }
    }

    @EventHandler
    public void onGMChange(PlayerGameModeChangeEvent e) {
        Player player = e.getPlayer();
        if (e.getNewGameMode() == GameMode.SURVIVAL) {
            createBossBar(player);
            updateBossBar(player);
            if (timerRunning) {
                if (assignedItems.containsKey(player.getUniqueId())) createItemDisplay(player, assignedItems.get(player.getUniqueId()));
                else assignRandomItem(player);
            }
            // Restore jokers
            if (storedJokers.containsKey(player.getUniqueId())) {
                jokerCounts.put(player.getUniqueId(), storedJokers.remove(player.getUniqueId()));
            }
            updatePlayerJokers(player);
        } else {
            bossBars.remove(player);
            removeItemDisplay(player);
            // Stash jokers
            storedJokers.put(player.getUniqueId(), jokerCounts.getOrDefault(player.getUniqueId(), 0));
            jokerCounts.put(player.getUniqueId(), 0);
            updatePlayerJokers(player);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // Joker Usage
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.BARRIER && 
           (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            
            Player player = event.getPlayer();
            if (timerRunning && assignedItems.containsKey(player.getUniqueId())) {
                int current = jokerCounts.getOrDefault(player.getUniqueId(), 0);
                if (current > 0) {
                    jokerCounts.put(player.getUniqueId(), current - 1);
                    updatePlayerJokers(player);
                    
                    player.sendMessage(PREFIX + messages.getString("joker-used", "§aJoker used!"));
                    
                    if (giveItemOnJoker) {
                        Material mat = assignedItems.get(player.getUniqueId());
                        player.getInventory().addItem(new ItemStack(mat));
                    }
                    
                    assignRandomItem(player);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    event.setCancelled(true);
                }
            } else if (!timerRunning) {
                player.sendMessage(PREFIX + "§cTimer not running!");
            }
        }
    }

    // Prevent interactions when paused
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) { if (shouldBlockAction(e.getPlayer())) e.setCancelled(true); }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) { 
        if (e.getItemInHand().getType() == Material.BARRIER || shouldBlockAction(e.getPlayer())) e.setCancelled(true); 
    }
    @EventHandler
    public void onDamage(EntityDamageEvent e) { 
        if (e.getEntity() instanceof Player && shouldBlockAction((Player)e.getEntity())) e.setCancelled(true); 
    }
    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) { 
        if (e.getTarget() instanceof Player && shouldBlockAction((Player)e.getTarget())) e.setCancelled(true); 
    }
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (e.getItemDrop().getItemStack().getType() == Material.BARRIER) e.setCancelled(true); }
    
    private boolean shouldBlockAction(Player p) {
        return !timerRunning && p.getGameMode() == GameMode.SURVIVAL;
    }

    // =================================================================================
    //                               SETTINGS GUI & UTILS
    // =================================================================================

    private void openSettingsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, settingsGUITitle);

        // Joker
        gui.setItem(2, createGuiItem(Material.BARRIER, 
            messages.getString("settings-joker-name", "§6Jokers"), 
            "§7Current Global: §e" + defaultJokers, "§7Left: +1 | Right: -1"));

        // Duplicate
        gui.setItem(4, createGuiItem(Material.PAPER, 
            messages.getString("settings-duplicate-name", "§cDuplicates"), 
            allowDuplicateTargets ? "§aEnabled" : "§cDisabled"));

        // Joker Gives Item
        gui.setItem(6, createGuiItem(Material.CHEST, 
            messages.getString("settings-joker-gives-item-name", "§bJoker Gives Item"), 
            giveItemOnJoker ? "§aEnabled" : "§cDisabled"));

        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(settingsGUITitle)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player)) return;
            Player player = (Player) event.getWhoClicked();

            switch (event.getSlot()) {
                case 2: // Joker
                    if (event.isLeftClick()) changeJokers(1);
                    else if (event.isRightClick()) changeJokers(-1);
                    openSettingsGUI(player);
                    break;
                case 4: // Duplicates
                    allowDuplicateTargets = !allowDuplicateTargets;
                    config.set("allow-duplicate-targets", allowDuplicateTargets);
                    saveConfig();
                    openSettingsGUI(player);
                    break;
                case 6: // Give Item
                    giveItemOnJoker = !giveItemOnJoker;
                    config.set("give-item-on-joker", giveItemOnJoker);
                    saveConfig();
                    openSettingsGUI(player);
                    break;
            }
        } else {
            // Prevent moving Joker (Barrier) in own inventory
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.BARRIER) {
                event.setCancelled(true);
            }
        }
    }

    private void changeJokers(int amount) {
        if (amount < 0 && defaultJokers <= 0) return;
        
        // Ensure we don't reduce below zero for any active player
        if (amount < 0) {
            boolean canReduce = jokerCounts.values().stream().allMatch(c -> c >= 1);
            if (!canReduce) return;
        }

        defaultJokers += amount;
        config.set("default-jokers", defaultJokers);
        saveConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            jokerCounts.put(p.getUniqueId(), jokerCounts.getOrDefault(p.getUniqueId(), 0) + amount);
            updatePlayerJokers(p);
        }
    }

    // =================================================================================
    //                               HELPERS (VISUALS)
    // =================================================================================

    private void createItemDisplay(Player player, Material item) {
        removeItemDisplay(player); // Cleanup old
        // Spawn Entity via Folia Scheduler
        player.getWorld().spawn(player.getLocation().add(0, 2.2, 0), ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setCustomNameVisible(false);
            stand.getEquipment().setHelmet(new ItemStack(item));
            itemDisplays.put(player, stand);
        });
    }

    private void removeItemDisplay(Player player) {
        ArmorStand stand = itemDisplays.remove(player);
        if (stand != null && stand.isValid()) stand.remove();
    }

    private BossBar createBossBar(Player player) {
        if (bossBars.containsKey(player)) return bossBars.get(player);
        BossBar bar = getServer().createBossBar(messages.getString("bossbar-default", "Current Item: -"), BarColor.BLUE, BarStyle.SOLID);
        bar.addPlayer(player);
        bossBars.put(player, bar);
        return bar;
    }

    private void updateBossBar(Player player) {
        BossBar bar = bossBars.get(player);
        if (bar != null) {
            Material item = assignedItems.get(player.getUniqueId());
            if (timerRunning && item != null) {
                bar.setTitle(messages.getString("bossbar-item", "Current Item: §e%item%").replace("%item%", formatItemName(item)));
            } else {
                bar.setTitle(messages.getString("bossbar-paused", "§cTimer paused"));
            }
        }
    }

    private void updateActionBar() {
        String msg;
        if (!timerSet) msg = messages.getString("timer-not-set", "• Time not set •");
        else {
            String time = String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60);
            String color = timerRunning ? "§a" : "§c";
            msg = messages.getString("timer-display", "• Time: %time% •").replace("%time%", color + time + "§f");
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(Component.text(msg));
    }

    private void updatePlayerJokers(Player player) {
        int count = jokerCounts.getOrDefault(player.getUniqueId(), 0);
        
        // Remove old barriers
        player.getInventory().remove(Material.BARRIER);
        
        if (count > 0) {
            ItemStack barrier = new ItemStack(Material.BARRIER, count);
            ItemMeta meta = barrier.getItemMeta();
            meta.setDisplayName(messages.getString("joker-item-name", "§6Joker"));
            meta.setLore(Collections.singletonList(messages.getString("joker-item-lore", "§7Right click to skip item")));
            barrier.setItemMeta(meta);
            player.getInventory().addItem(barrier);
        }
    }

    // =================================================================================
    //                               HELPERS (DATA/RESET)
    // =================================================================================

    private void blockItem(CommandSender sender, String itemName) {
        try {
            Material material = Material.valueOf(itemName.toUpperCase());
            if (configurableBlacklist.contains(material)) {
                sender.sendMessage(PREFIX + "§cItem already blacklisted!");
                return;
            }
            configurableBlacklist.add(material);
            
            // Reassign if anyone has this item
            for (Map.Entry<UUID, Material> entry : new HashMap<>(assignedItems).entrySet()) {
                if (entry.getValue() == material) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) {
                        assignRandomItem(p);
                        p.sendMessage(PREFIX + "§eYour item was blacklisted and changed.");
                    }
                }
            }

            // Save to file
            File f = new File(getDataFolder(), "items-blacklist.yml");
            FileConfiguration c = YamlConfiguration.loadConfiguration(f);
            List<String> list = c.getStringList("blacklisted-items");
            list.add(material.name());
            c.set("blacklisted-items", list);
            c.save(f);

            refreshMaterialCache();
            sender.sendMessage(PREFIX + "§aItem blacklisted!");
            
            if (config.getBoolean("share-blacklisted-items-to-developer", true)) {
                sendDiscordWebhookAsync("Item-blacklist: " + material.name());
            }

        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + "§cInvalid material!");
        } catch (IOException e) {
            sender.sendMessage(PREFIX + "§cError saving config.");
        }
    }

    private void refreshMaterialCache() {
        cachedValidMaterials.clear();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !m.isLegacy() && !configurableBlacklist.contains(m)) {
                // Basic check. In production, check Recipe or LootTables to verify obtainability
                if (!m.name().contains("SPAWN_EGG") && !m.name().contains("COMMAND_BLOCK")) {
                     cachedValidMaterials.add(m);
                }
            }
        }
    }

    private void loadConfigurableBlacklist() {
        configurableBlacklist.clear();
        File blacklistFile = new File(getDataFolder(), "items-blacklist.yml");
        if (blacklistFile.exists()) {
            FileConfiguration blacklistConfig = YamlConfiguration.loadConfiguration(blacklistFile);
            for (String item : blacklistConfig.getStringList("blacklisted-items")) {
                try {
                    configurableBlacklist.add(Material.valueOf(item.toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void sendDiscordWebhookAsync(String msg) {
        CompletableFuture.runAsync(() -> {
            try {
                // PLEASE NOTE: Hardcoding Webhook URLs is bad practice. Consider moving to config.
                URL url = new URL("https://discord.com/api/webhooks/1456737969581850684/YXYsctMK0K5a3m6eM65rp9WnFcddCTLmSIL9jjfQ2V1k8HOYBFuAxCKZTQs-xYjWGUMW");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                String json = "{\"content\":\"" + msg + "\"}";
                try (OutputStream os = conn.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
                conn.getResponseCode();
            } catch (Exception ignored) {}
        });
    }

    // --- World Reset Logic ---

    private void prepareWorldReset(CommandSender sender) {
        String kickMsg = messages.getString("reset-kick-message", "§cServer Reset! Restarting...");
        for (Player p : Bukkit.getOnlinePlayers()) p.kickPlayer(kickMsg);

        try {
            rotateWorldAndResetSeed();
            // Delay shutdown slightly to allow IO
            scheduler.runDelayed(this, task -> Bukkit.shutdown(), 20L);
        } catch (Exception e) {
            sender.sendMessage(PREFIX + "§cError preparing reset: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void rotateWorldAndResetSeed() throws IOException {
        File propFile = new File("server.properties");
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propFile)) { props.load(in); }

        String oldLevelName = props.getProperty("level-name", "world");
        String newLevelName = "world_" + (System.currentTimeMillis() / 1000);
        
        props.setProperty("level-name", newLevelName);
        props.setProperty("level-seed", ""); 

        try (FileOutputStream out = new FileOutputStream(propFile)) { props.store(out, null); }
        
        // Mark old world for deletion
        List<String> toDelete = config.getStringList("worlds-to-delete");
        if (!toDelete.contains(oldLevelName)) toDelete.add(oldLevelName);
        config.set("worlds-to-delete", toDelete);
        saveConfig();
    }

    private void cleanupOldWorlds() {
        List<String> worldsToDelete = config.getStringList("worlds-to-delete");
        if (worldsToDelete.isEmpty()) return;

        String currentLevel = getMainLevelName();
        List<String> remaining = new ArrayList<>();

        for (String worldName : worldsToDelete) {
            if (worldName.equals(currentLevel)) {
                remaining.add(worldName); // Cannot delete active world
                continue;
            }
            
            File worldFolder = new File(getServer().getWorldContainer(), worldName);
            File netherFolder = new File(getServer().getWorldContainer(), worldName + "_nether");
            File endFolder = new File(getServer().getWorldContainer(), worldName + "_the_end");

            deleteDirectory(worldFolder);
            deleteDirectory(netherFolder);
            deleteDirectory(endFolder);
            
            if (worldFolder.exists()) remaining.add(worldName); // Keep if failed
        }
        config.set("worlds-to-delete", remaining);
        saveConfig();
    }

    private void deleteDirectory(File folder) {
        if (!folder.exists()) return;
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            getLogger().warning("Could not delete " + folder.getName());
        }
    }

    private String getMainLevelName() {
        try (InputStream input = new FileInputStream("server.properties")) {
            Properties prop = new Properties();
            prop.load(input);
            return prop.getProperty("level-name", "world");
        } catch (IOException ex) { return "world"; }
    }

    private void resetChallengeData(CommandSender sender) {
        if (timerRunning) stopTimer(sender);
        scores.clear();
        assignedItems.clear();
        remainingSeconds = 0;
        timerSet = false;
        
        File dataFile = new File(getDataFolder(), "data.yml");
        if (dataFile.exists()) dataFile.delete();
    }

    // --- Persistency ---
    private void saveData() {
        try {
            File dataFile = new File(getDataFolder(), "data.yml");
            FileConfiguration data = new YamlConfiguration();
            data.set("remainingSeconds", remainingSeconds);
            data.set("defaultJokers", defaultJokers);
            
            Map<String, Integer> sMap = new HashMap<>();
            scores.forEach((k, v) -> sMap.put(k.toString(), v));
            data.set("scores", sMap);
            
            Map<String, String> aMap = new HashMap<>();
            assignedItems.forEach((k, v) -> aMap.put(k.toString(), v.name()));
            data.set("assignedItems", aMap);

            Map<String, Integer> jMap = new HashMap<>();
            jokerCounts.forEach((k, v) -> jMap.put(k.toString(), v));
            data.set("jokerCounts", jMap);

            data.save(dataFile);
        } catch (IOException e) { getLogger().severe("Could not save data.yml"); }
    }

    private void loadData() {
        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) return;
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        
        remainingSeconds = data.getLong("remainingSeconds", 0);
        if (remainingSeconds > 0) timerSet = true;
        defaultJokers = data.getInt("defaultJokers", defaultJokers);

        if (data.isConfigurationSection("scores"))
            data.getConfigurationSection("scores").getValues(false).forEach((k, v) -> scores.put(UUID.fromString(k), (Integer) v));
        if (data.isConfigurationSection("assignedItems"))
            data.getConfigurationSection("assignedItems").getValues(false).forEach((k, v) -> assignedItems.put(UUID.fromString(k), Material.getMaterial((String) v)));
        if (data.isConfigurationSection("jokerCounts"))
            data.getConfigurationSection("jokerCounts").getValues(false).forEach((k, v) -> jokerCounts.put(UUID.fromString(k), (Integer) v));
    }

    private void listItems(CommandSender sender) {
        sender.sendMessage(PREFIX + "§6=== Assigned Items ===");
        assignedItems.forEach((uuid, mat) -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            sender.sendMessage(PREFIX + "§e" + (name != null ? name : "Unknown") + " §r- §a" + formatItemName(mat));
        });
    }

    private void listPoints(CommandSender sender) {
        sender.sendMessage(PREFIX + "§6=== Points ===");
        scores.forEach((uuid, pts) -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            sender.sendMessage(PREFIX + "§e" + (name != null ? name : "Unknown") + " §r- §a" + pts);
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "§6=== Help ===");
        sender.sendMessage("§e/timer start/stop/set <min>");
        sender.sendMessage("§e/challenges randomitembattle listitems");
        sender.sendMessage("§e/challenges randomitembattle blockitem <item>");
        sender.sendMessage("§e/reset confirm");
    }

    private String formatItemName(Material mat) {
        return Arrays.stream(mat.name().toLowerCase().split("_"))
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining(" "));
    }
}
