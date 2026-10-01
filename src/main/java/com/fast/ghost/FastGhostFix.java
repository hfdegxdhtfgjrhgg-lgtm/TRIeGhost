package com.fast.ghost;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FastGhostFix extends JavaPlugin implements Listener {

    // ===== Stats =====
    private final AtomicLong received = new AtomicLong(0);
    private final AtomicLong cancelled = new AtomicLong(0);
    private final AtomicLong fixed = new AtomicLong(0);
    private final AtomicLong logged = new AtomicLong(0);

    // ===== Per-player =====
    private final Map<UUID, int[]> playerStats = new ConcurrentHashMap<>();
    // int[0] = received, int[1] = cancelled, int[2] = fixed

    // ===== Duplicate Tracker =====
    private final Deque<HitRecord> history = new ArrayDeque<>();

    // ===== Ping Tiers =====
    private static final class PingTier {
        final int maxPing;
        final double extraReach;
        PingTier(int maxPing, double extraReach) {
            this.maxPing = maxPing;
            this.extraReach = extraReach;
        }
    }
    private final List<PingTier> pingTiers = new ArrayList<>();

    // ===== Config =====
    private boolean enabled;
    private boolean onlyPlayers;
    private double minDamage;
    private double maxDamage;
    private boolean pingCompEnabled;
    private int maxPing;
    private double baseReach;
    private double maxReach;
    private boolean duplicateEnabled;
    private long duplicateWindowMs;
    private int duplicateHistorySize;
    private boolean reachValidationEnabled;
    private boolean logConsole;
    private boolean logHitsFile;
    private boolean logGhostFile;
    private boolean debug;

    // ===== Files =====
    private File hitsLogFile;
    private File ghostLogFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // =========================================================
    // Enable
    // =========================================================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        // Create data folder
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        hitsLogFile = new File(getDataFolder(), "hits.log");
        ghostLogFile = new File(getDataFolder(), "ghost-hits.log");
        createFile(hitsLogFile);
        createFile(ghostLogFile);

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Register commands
        if (getCommand("fastghostfix") != null) {
            getCommand("fastghostfix").setExecutor(new FastGhostCommand());
        }

        getLogger().info("FastGhostFix v" + getDescription().getVersion() + " enabled.");
        getLogger().info("Ping Compensation: " + (pingCompEnabled ? "ON" : "OFF"));
        getLogger().info("Duplicate Protection: " + (duplicateEnabled ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        getLogger().info("FastGhostFix disabled. Received=" + received.get()
                + " Cancelled=" + cancelled.get()
                + " Fixed=" + fixed.get()
                + " Logged=" + logged.get());
    }

    // =========================================================
    // Config
    // =========================================================
    private void loadConfig() {
        enabled = getConfig().getBoolean("enabled", true);
        onlyPlayers = getConfig().getBoolean("ghost-hit-fixer.only-player-hits", true);
        minDamage = getConfig().getDouble("ghost-hit-fixer.min-damage", 0.01);
        maxDamage = getConfig().getDouble("ghost-hit-fixer.max-damage", 1000.0);

        pingCompEnabled = getConfig().getBoolean("ping-compensation.enabled", true);
        maxPing = getConfig().getInt("ping-compensation.max-ping", 250);
        baseReach = getConfig().getDouble("ping-compensation.base-reach", 3.0);
        maxReach = getConfig().getDouble("ping-compensation.max-reach", 4.5);

        pingTiers.clear();
        List<Map<?, ?>> rawList = getConfig().getMapList("ping-compensation.table");
        if (rawList != null) {
            for (Map<?, ?> m : rawList) {
                Object mp = m.get("max-ping");
                Object er = m.get("extra-reach");
                if (mp instanceof Number && er instanceof Number) {
                    pingTiers.add(new PingTier(((Number) mp).intValue(),
                            ((Number) er).doubleValue()));
                }
            }
        }
        if (pingTiers.isEmpty()) {
            pingTiers.add(new PingTier(50, 0.0));
            pingTiers.add(new PingTier(100, 0.3));
            pingTiers.add(new PingTier(150, 0.6));
            pingTiers.add(new PingTier(200, 0.9));
            pingTiers.add(new PingTier(250, 1.2));
        }
        pingTiers.sort(Comparator.comparingInt(t -> t.maxPing));

        duplicateEnabled = getConfig().getBoolean("duplicate-protection.enabled", true);
        duplicateWindowMs = getConfig().getLong("duplicate-protection.window-ms", 50L);
        duplicateHistorySize = getConfig().getInt("duplicate-protection.history-size", 200);

        reachValidationEnabled = getConfig().getBoolean("reach-validation.enabled", true);

        logConsole = getConfig().getBoolean("logging.console", false);
        logHitsFile = getConfig().getBoolean("logging.hits-file", true);
        logGhostFile = getConfig().getBoolean("logging.ghost-file", true);

        debug = getConfig().getBoolean("debug.enabled", false);
    }

    private void createFile(File f) {
        if (!f.exists()) {
            try { f.createNewFile(); }
            catch (IOException e) { getLogger().severe("Could not create " + f.getName() + ": " + e.getMessage()); }
        }
    }

    // =========================================================
    // Event — LOWEST: catch hit first
    // =========================================================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onHitLowest(EntityDamageByEntityEvent event) {
        if (!enabled) return;

        if (onlyPlayers) {
            if (!(event.getDamager() instanceof Player)) return;
            if (!(event.getEntity() instanceof Player)) return;
        }

        Player attacker = (Player) event.getDamager();
        received.incrementAndGet();
        int[] stats = playerStats.computeIfAbsent(attacker.getUniqueId(), k -> new int[3]);
        stats[0]++;

        // Duplicate check
        if (duplicateEnabled && isDuplicate(attacker.getUniqueId(),
                event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // Ping compensation / Reach validation
        if (pingCompEnabled && reachValidationEnabled) {
            double allowedReach = computeAllowedReach(attacker);
            double distance = attacker.getEyeLocation().distance(
                    event.getEntity().getLocation());
            if (distance > allowedReach) {
                if (debug) {
                    getLogger().info("[FastGhostFix] Reach exceeded: "
                            + attacker.getName() + " " + String.format("%.2f", distance)
                            + " > " + String.format("%.2f", allowedReach));
                }
                event.setCancelled(true);
                return;
            }
        }

        // Ghost Hit fix
        if (event.isCancelled()) {
            cancelled.incrementAndGet();
            stats[1]++;

            if (event.getDamage() < minDamage || event.getDamage() > maxDamage) return;

            event.setCancelled(false);
            fixed.incrementAndGet();
            stats[2]++;
            logGhostHit(event);
        }
    }

    // =========================================================
    // Event — HIGHEST: defensive check
    // =========================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHitHighest(EntityDamageByEntityEvent event) {
        if (!enabled) return;

        if (onlyPlayers) {
            if (!(event.getDamager() instanceof Player)) return;
            if (!(event.getEntity() instanceof Player)) return;
        }

        if (event.isCancelled() && event.getDamage() >= minDamage
                && event.getDamage() <= maxDamage) {
            event.setCancelled(false);
            fixed.incrementAndGet();
        }
    }

    // =========================================================
    // Event — MONITOR: log
    // =========================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHitMonitor(EntityDamageByEntityEvent event) {
        if (!enabled) return;

        if (onlyPlayers) {
            if (!(event.getDamager() instanceof Player)) return;
            if (!(event.getEntity() instanceof Player)) return;
        }

        logged.incrementAndGet();
        logHit(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Keep stats in memory for now (future DB)
    }

    // =========================================================
    // Duplicate
    // =========================================================
    private synchronized boolean isDuplicate(UUID attacker, UUID target) {
        long now = System.currentTimeMillis();
        while (!history.isEmpty() && now - history.peekFirst().time > duplicateWindowMs) {
            history.pollFirst();
        }
        for (HitRecord r : history) {
            if (r.attacker.equals(attacker) && r.target.equals(target)) return true;
        }
        history.addLast(new HitRecord(attacker, target, now));
        while (history.size() > duplicateHistorySize) history.pollFirst();
        return false;
    }

    // =========================================================
    // Ping Compensation
    // =========================================================
    private double computeAllowedReach(Player attacker) {
        int ping = 0;
        try { ping = attacker.getPing(); } catch (Throwable ignored) {}

        int clamped = Math.min(ping, maxPing);
        double extra = 0.0;
        for (PingTier t : pingTiers) {
            if (clamped <= t.maxPing) { extra = t.extraReach; break; }
        }
        double reach = baseReach + extra;
        return Math.min(reach, maxReach);
    }

    // =========================================================
    // Logging
    // =========================================================
    private void logHit(EntityDamageByEntityEvent event) {
        if (!logHitsFile && !logConsole) return;

        String attacker = event.getDamager() instanceof Player p
                ? p.getName() : event.getDamager().getType().name();
        String target = event.getEntity() instanceof Player p
                ? p.getName() : event.getEntity().getType().name();
        double damage = event.getFinalDamage();
        String world = event.getEntity().getWorld().getName();

        String line = "[" + dateFormat.format(new Date()) + "]"
                + " attacker=" + attacker
                + " target=" + target
                + " damage=" + String.format("%.2f", damage)
                + " world=" + world
                + " cancelled=" + event.isCancelled();

        if (logConsole) getLogger().info(line);
        if (logHitsFile) append(hitsLogFile, line);
    }

    private void logGhostHit(EntityDamageByEntityEvent event) {
        String attacker = event.getDamager() instanceof Player p
                ? p.getName() : event.getDamager().getType().name();
        String target = event.getEntity() instanceof Player p
                ? p.getName() : event.getEntity().getType().name();
        double damage = event.getFinalDamage();

        String line = "[FastGhostFix] " + dateFormat.format(new Date())
                + " | " + attacker + " -> " + target
                + " | Damage: " + String.format("%.2f", damage)
                + " | Fixed: true";

        if (logConsole) getLogger().info(line);
        if (logGhostFile) append(ghostLogFile, line);
    }

    private synchronized void append(File f, String line) {
        try (PrintWriter out = new PrintWriter(new FileWriter(f, true))) {
            out.println(line);
        } catch (IOException e) {
            getLogger().warning("Failed to write log: " + e.getMessage());
        }
    }

    // =========================================================
    // HitRecord
    // =========================================================
    private static final class HitRecord {
        final UUID attacker;
        final UUID target;
        final long time;
        HitRecord(UUID a, UUID t, long time) { this.attacker = a; this.target = t; this.time = time; }
    }

    // =========================================================
    // Command
    // =========================================================
    private final class FastGhostCommand implements org.bukkit.command.CommandExecutor,
            org.bukkit.command.TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(prefix() + ChatColor.YELLOW + "Usage: /fgf <status|reload|stats|top>");
                return true;
            }

            String sub = args[0].toLowerCase();

            if (sub.equals("status")) {
                if (!sender.hasPermission("fastghostfix.status")) {
                    sender.sendMessage(prefix() + ChatColor.RED + "No permission.");
                    return true;
                }
                for (String line : getConfig().getStringList("messages.status")) {
                    sender.sendMessage(color(line
                            .replace("%status%", enabled ? "&aENABLED" : "&cDISABLED")
                            .replace("%received%", String.valueOf(received.get()))
                            .replace("%cancelled%", String.valueOf(cancelled.get()))
                            .replace("%fixed%", String.valueOf(fixed.get()))
                            .replace("%logged%", String.valueOf(logged.get()))));
                }
                return true;
            }

            if (sub.equals("reload")) {
                if (!sender.hasPermission("fastghostfix.admin")) {
                    sender.sendMessage(prefix() + ChatColor.RED + "No permission.");
                    return true;
                }
                reloadConfig();
                loadConfig();
                sender.sendMessage(prefix() + color(getConfig().getString("messages.reload", "&aReloaded.")));
                return true;
            }

            if (sub.equals("stats")) {
                if (!sender.hasPermission("fastghostfix.status")) {
                    sender.sendMessage(prefix() + ChatColor.RED + "No permission.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(prefix() + ChatColor.YELLOW + "Usage: /fgf stats <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix() + ChatColor.RED + "Player not found.");
                    return true;
                }
                int[] s = playerStats.getOrDefault(target.getUniqueId(), new int[]{0,0,0});
                for (String line : getConfig().getStringList("messages.stats")) {
                    sender.sendMessage(color(line
                            .replace("%player%", target.getName())
                            .replace("%received%", String.valueOf(s[0]))
                            .replace("%cancelled%", String.valueOf(s[1]))
                            .replace("%fixed%", String.valueOf(s[2]))));
                }
                return true;
            }

            if (sub.equals("top")) {
                if (!sender.hasPermission("fastghostfix.status")) {
                    sender.sendMessage(prefix() + ChatColor.RED + "No permission.");
                    return true;
                }
                List<Map.Entry<UUID, int[]>> sorted = new ArrayList<>(playerStats.entrySet());
                sorted.sort((a, b) -> Integer.compare(b.getValue()[2], a.getValue()[2]));
                StringBuilder sb = new StringBuilder();
                int rank = 1;
                for (Map.Entry<UUID, int[]> e : sorted) {
                    if (rank > 10) break;
                    String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                    if (name == null) name = e.getKey().toString();
                    sb.append("&e#").append(rank).append(" &f")
                      .append(name).append(" &7- &a").append(e.getValue()[2]).append(" fixed\n");
                    rank++;
                }
                for (String line : getConfig().getStringList("messages.top")) {
                    sender.sendMessage(color(line.replace("%entries%", sb.toString().trim())));
                }
                return true;
            }

            sender.sendMessage(prefix() + ChatColor.YELLOW + "Usage: /fgf <status|reload|stats|top>");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                String typed = args[0].toLowerCase();
                List<String> opts = new ArrayList<>();
                for (String s : Arrays.asList("status", "reload", "stats", "top")) {
                    if (s.startsWith(typed)) opts.add(s);
                }
                return opts;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
                List<String> names = new ArrayList<>();
                String typed = args[1].toLowerCase();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(typed)) names.add(p.getName());
                }
                return names;
            }
            return Collections.emptyList();
        }

        private String prefix() {
            return color(getConfig().getString("messages.prefix", "&8[&6FastGhostFix&8] "));
        }

        private String color(String text) {
            return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
        }
    }
                  }
