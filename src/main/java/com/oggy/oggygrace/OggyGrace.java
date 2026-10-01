package com.oggy.oggygrace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OggyGrace extends JavaPlugin implements Listener, TabExecutor {

    // grace period + end lock (saved in data.yml)
    private File dataFile;
    private YamlConfiguration data;
    private long graceEnd = 0L;     // epoch millis, 0 = no grace running
    private long graceTotal = 0L;   // total length in millis
    private boolean graceWarned = false;
    private BossBar graceBar;
    private boolean endLocked = false;
    private final Map<UUID, Long> lastLockMsg = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        applyAntiLag();
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("grace", "endlockmeow", "endunlockmeow")) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickGrace, 20L, 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "grace": return graceCommand(sender, args);
            case "endlockmeow": return endLock(sender, true);
            case "endunlockmeow": return endLock(sender, false);
            default: return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("grace")) {
            if (args.length == 1) return filter(List.of("start", "stop", "status"), args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("start")) return filter(List.of("1h", "2h", "5h", "10h", "20h"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }

    private void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text(text, color));
    }

    // -------------------------------------------------------------------- grace

    private String survivalWorld() {
        return getConfig().getString("survival-world", "survival");
    }

    private boolean inSurvival(Player p) {
        return p.getWorld().getName().equals(survivalWorld());
    }

    private boolean graceActive() {
        return graceEnd > System.currentTimeMillis();
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        graceEnd = data.getLong("grace.end", 0L);
        graceTotal = data.getLong("grace.total", 0L);
        endLocked = data.getBoolean("end-locked", false);
        if (graceEnd <= System.currentTimeMillis()) {
            graceEnd = 0L;
            graceTotal = 0L;
        } else {
            ensureBar();
        }
    }

    private void saveData() {
        data.set("grace.end", graceEnd);
        data.set("grace.total", graceTotal);
        data.set("end-locked", endLocked);
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void ensureBar() {
        if (graceBar == null) {
            graceBar = BossBar.bossBar(Component.text("Grace Period", NamedTextColor.GREEN), 1f,
                    BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        }
    }

    private void updateBarFor(Player p) {
        if (graceBar == null) return;
        if (graceActive() && inSurvival(p)) p.showBossBar(graceBar);
        else p.hideBossBar(graceBar);
    }

    /** Accepts 1h, 90m, 2h30m, 3600s, or a plain number meaning hours. Returns seconds or -1. */
    private static long parseSeconds(String in) {
        String s = in.toLowerCase(Locale.ROOT).trim();
        try {
            if (s.matches("\\d+(\\.\\d+)?")) return (long) (Double.parseDouble(s) * 3600);
            if (!s.matches("(\\d+[hms])+")) return -1;
            Matcher m = Pattern.compile("(\\d+)([hms])").matcher(s);
            long total = 0;
            while (m.find()) {
                long v = Long.parseLong(m.group(1));
                total += switch (m.group(2).charAt(0)) {
                    case 'h' -> v * 3600;
                    case 'm' -> v * 60;
                    default -> v;
                };
            }
            return total;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String formatTime(long secs) {
        return String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private void broadcastSurvival(String text, NamedTextColor color) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inSurvival(p)) msg(p, text, color);
        }
        getLogger().info(text);
    }

    private boolean graceCommand(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";

        if (sub.equals("status")) {
            if (graceActive()) {
                long secs = (graceEnd - System.currentTimeMillis() + 999) / 1000;
                sender.sendMessage(Component.text("Grace period is running. Time left: " + formatTime(secs), NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("No grace period is running.", NamedTextColor.YELLOW));
            }
            return true;
        }

        if (!sender.hasPermission("oggygrace.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "start" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /grace start <time>  (examples: 1h, 90m, 2h30m, 20h)", NamedTextColor.YELLOW));
                    return true;
                }
                long secs = parseSeconds(args[1]);
                if (secs < 3600 || secs > 20 * 3600) {
                    sender.sendMessage(Component.text("Time must be between 1 hour and 20 hours. Examples: 1h, 90m, 2h30m, 20h", NamedTextColor.RED));
                    return true;
                }
                if (graceActive()) {
                    sender.sendMessage(Component.text("A grace period is already running. Use /grace stop first.", NamedTextColor.RED));
                    return true;
                }
                graceEnd = System.currentTimeMillis() + secs * 1000L;
                graceTotal = secs * 1000L;
                graceWarned = false;
                saveData();
                ensureBar(); 
                tickGrace();
                for (Player p : Bukkit.getOnlinePlayers()) updateBarFor(p);
                broadcastSurvival("Grace period started for " + formatTime(secs)
                        + "! No PvP and no fall damage in survival.", NamedTextColor.GREEN);
            }
            case "stop" -> {
                if (!graceActive()) {
                    sender.sendMessage(Component.text("No grace period is running.", NamedTextColor.YELLOW));
                    return true;
                }
                endGrace(false);
            }
            default -> sender.sendMessage(Component.text("Usage: /grace start <time> | /grace stop | /grace status", NamedTextColor.YELLOW));
        }
        return true;
    }

    private void endGrace(boolean natural) {
        graceEnd = 0L;
        graceTotal = 0L;
        saveData();
        if (graceBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(graceBar);
        }
        broadcastSurvival(natural
                ? "Grace period is over! PvP and fall damage are now ON."
                : "Grace period was stopped by an admin. PvP and fall damage are now ON.", NamedTextColor.RED);
    }

    private void tickGrace() {
        if (graceEnd == 0L) return;
        long left = graceEnd - System.currentTimeMillis();
        if (left <= 0) {
            endGrace(true);
            return;
        }
        ensureBar();
        long secs = (left + 999) / 1000;
        boolean last = secs <= 10;
        NamedTextColor color = last ? NamedTextColor.RED : NamedTextColor.GREEN;
        graceBar.name(Component.text("Grace Period: " + formatTime(secs), color));
        graceBar.color(last ? BossBar.Color.RED : BossBar.Color.GREEN);
        graceBar.progress(graceTotal > 0 ? (float) Math.max(0.0, Math.min(1.0, left / (double) graceTotal)) : 1f);

        if (last) {
            boolean first = !graceWarned;
            graceWarned = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!inSurvival(p)) continue;
                if (first) msg(p, "Grace period ends in 10 seconds! PvP will be enabled.", NamedTextColor.RED);
                p.showTitle(Title.title(
                        Component.text(String.valueOf(secs), NamedTextColor.RED),
                        Component.text("Grace period ending", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100))));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, secs <= 3 ? 2f : 1f);
            }
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player pl) return pl;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player pl) return pl;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGraceDamage(EntityDamageEvent e) {
        if (!graceActive() || !(e.getEntity() instanceof Player victim) || !inSurvival(victim)) return;

        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = resolveAttacker(byEntity.getDamager());
            if (attacker != null && !attacker.equals(victim)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        updateBarFor(e.getPlayer());
    }

    // --------------------------------------------------------------- end lock

    private boolean endLock(CommandSender sender, boolean lock) {
        if (!sender.hasPermission("oggygrace.admin")) {
            sender.sendMessage(Component.text("Only admins can use this command.", NamedTextColor.RED));
            return true;
        }
        endLocked = lock;
        saveData();
        sender.sendMessage(Component.text(lock
                ? "The End portal is now LOCKED. Nobody can enter The End."
                : "The End portal is now UNLOCKED.", lock ? NamedTextColor.RED : NamedTextColor.GREEN));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEndPortal(PlayerPortalEvent e) {
        if (!endLocked || e.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) return;
        Location to = e.getTo();
        // only block going INTO the end, so nobody gets stuck inside it
        if (to == null || to.getWorld() == null || to.getWorld().getEnvironment() != World.Environment.THE_END) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (now - lastLockMsg.getOrDefault(p.getUniqueId(), 0L) > 3000L) {
            lastLockMsg.put(p.getUniqueId(), now);
            msg(p, "The End is locked right now.", NamedTextColor.RED);
        }
    }

    // --------------------------------------------------------------- anti lag

    private void applyAntiLag() {
        for (World w : Bukkit.getWorlds()) applyAntiLag(w);
    }

    private void applyAntiLag(World w) {
        if (!getConfig().getBoolean("anti-lag.enabled", true)) return;
        try {
            w.setViewDistance(getConfig().getInt("anti-lag.view-distance", 8));
            w.setSimulationDistance(getConfig().getInt("anti-lag.simulation-distance", 5));
        } catch (Throwable t) {
            getLogger().warning("Could not set chunk distances for " + w.getName() + ": " + t.getMessage());
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        applyAntiLag(e.getWorld());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        updateBarFor(e.getPlayer());
    }
}
