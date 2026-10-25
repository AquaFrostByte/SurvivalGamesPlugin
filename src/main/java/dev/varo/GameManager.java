package dev.varo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/** Game state machine: scatter -> countdown -> running (days, time limits, deaths) -> ended. */
public class GameManager {
    private final VaroPlugin p;
    private BukkitTask countdownTask;
    private int countdownLeft;
    private boolean lastPvp;
    private int saveCounter;
    private final Set<UUID> timeKicked = new HashSet<UUID>();
    private final Map<UUID, Location> deathLocs = new HashMap<UUID, Location>();

    public GameManager(VaroPlugin p) { this.p = p; }

    private DataStore d() { return p.data; }
    private FileConfiguration c() { return p.getConfig(); }

    public void startTasks() {
        if (d().state == GameState.COUNTDOWN) d().state = GameState.FROZEN;
        lastPvp = isPvpEnabled();
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            public void run() { tick(); }
        }, 20L, 20L);
    }

    public void removeAllCountdown() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
    }

    // ================================================================= tick
    private void tick() {
        DataStore d = d();
        if (d.state == GameState.RUNNING) {
            checkDayChange();
            checkGrace();
            int limit = limitSeconds();
            List<Integer> warn = c().getIntegerList("time.warnings-seconds");
            boolean windowKick = c().getBoolean("time.play-window.enabled", false)
                    && c().getBoolean("time.play-window.kick-at-end", true) && !inWindow();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (!isActive(pl)) continue;
                UUID id = pl.getUniqueId();
                int u = used(id) + 1;
                d.used.put(id, u);
                int rem = limit - u;
                if (rem <= 0) { kickTimeOver(pl); continue; }
                if (windowKick) {
                    timeKicked.add(id);
                    pl.kickPlayer(p.raw("kick-window-closed", "start", c().getString("time.play-window.start"),
                            "end", c().getString("time.play-window.end")));
                    continue;
                }
                if (warn.contains(rem)) {
                    pl.sendMessage(p.msg("time-warning", "time", Util.fmt(rem)));
                    Util.sound(pl, c().getString("time.warning-sound", "NOTE_PLING"), 1f, 1f);
                }
            }
        }
        p.border.tick();
        p.boards.updateAll();
        p.combat.tick();
        if (++saveCounter >= Math.max(5, c().getInt("general.save-interval-seconds", 60))) {
            saveCounter = 0;
            d.save();
        }
    }

    private void kickTimeOver(Player pl) {
        timeKicked.add(pl.getUniqueId());
        pl.kickPlayer(p.raw("kick-time-over", "reset", resetText()));
    }

    /** true once if this player was just kicked by the time limit (used by the combat-log check) */
    public boolean consumeTimeKick(UUID id) { return timeKicked.remove(id); }

    // ================================================================= state helpers
    public boolean isBypass(Player pl) { return pl.hasPermission("varo.bypass"); }

    /** participant, alive, not staff */
    public boolean isActive(Player pl) {
        return d().participants.contains(pl.getUniqueId()) && !d().dead.containsKey(pl.getUniqueId()) && !isBypass(pl);
    }

    public boolean isFrozen(Player pl) {
        return (d().state == GameState.FROZEN || d().state == GameState.COUNTDOWN)
                && d().participants.contains(pl.getUniqueId());
    }

    public boolean isSpectator(Player pl) {
        return d().state == GameState.RUNNING && d().dead.containsKey(pl.getUniqueId())
                && "SPECTATOR".equalsIgnoreCase(c().getString("death.mode", "BAN"));
    }

    public boolean isAlive(UUID id) { return d().participants.contains(id) && !d().dead.containsKey(id); }

    public int aliveCount() {
        int n = 0;
        for (UUID id : d().participants) if (!d().dead.containsKey(id)) n++;
        return n;
    }

    public List<VaroTeam> aliveTeams() {
        List<VaroTeam> out = new ArrayList<VaroTeam>();
        for (VaroTeam t : d().teams.values()) {
            for (UUID id : t.members) if (isAlive(id)) { out.add(t); break; }
        }
        return out;
    }

    public int aliveTeamCount() { return aliveTeams().size(); }

    // ================================================================= time
    public int limitSeconds() { return c().getInt("time.daily-minutes", 15) * 60; }

    public int used(UUID id) { Integer u = d().used.get(id); return u == null ? 0 : u; }

    public int remaining(UUID id) { return limitSeconds() - used(id); }

    private TimeZone tz() {
        String s = c().getString("time.timezone", "SYSTEM");
        return "SYSTEM".equalsIgnoreCase(s) ? TimeZone.getDefault() : TimeZone.getTimeZone(s);
    }

    private long resetMs() {
        int[] hm = Util.parseHm(c().getString("time.reset-time", "00:00"), 0, 0);
        return (hm[0] * 3600L + hm[1] * 60L) * 1000L;
    }

    private boolean manualReset() { return "MANUAL".equalsIgnoreCase(c().getString("time.reset-mode", "FIXED_TIME")); }

    public long currentDayKey() {
        long now = System.currentTimeMillis();
        long local = now + tz().getOffset(now);
        return (local - resetMs()) / 86400000L;
    }

    public long secondsUntilReset() {
        if (manualReset()) return -1;
        long now = System.currentTimeMillis();
        long local = now + tz().getOffset(now);
        long next = ((local - resetMs()) / 86400000L + 1) * 86400000L + resetMs();
        return (next - local) / 1000;
    }

    public String resetText() {
        long s = secondsUntilReset();
        return s < 0 ? c().getString("time.manual-reset-text", "the next day") : Util.fmt(s);
    }

    public boolean inWindow() {
        if (!c().getBoolean("time.play-window.enabled", false)) return true;
        int[] a = Util.parseHm(c().getString("time.play-window.start", "18:00"), 18, 0);
        int[] b = Util.parseHm(c().getString("time.play-window.end", "22:00"), 22, 0);
        Calendar cal = Calendar.getInstance(tz());
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int s = a[0] * 60 + a[1], e = b[0] * 60 + b[1];
        return s <= e ? (now >= s && now < e) : (now >= s || now < e);
    }

    private void checkDayChange() {
        if (manualReset()) return;
        long key = currentDayKey();
        if (key != d().dayKey) {
            int diff = (int) Math.max(1, key - d().dayKey);
            d().dayKey = key;
            advanceDay(diff);
        }
    }

    public void setDay(int n) {
        d().day = Math.max(0, n - 1);
        advanceDay(1);
    }

    public void advanceDay(int add) {
        DataStore d = d();
        int old = d.day;
        d.day += add;
        d.used.clear();
        int size = (int) p.border.sizeForDay(d.day);
        int next = (int) p.border.sizeForDay(d.day + 1);
        Bukkit.broadcastMessage(p.msg("new-day", "day", d.day, "size", size, "next", next));
        int nether = c().getInt("dimensions.nether-unlock-day", 3);
        int end = c().getInt("dimensions.end-unlock-day", 6);
        if (old < nether && d.day >= nether) Bukkit.broadcastMessage(p.msg("nether-unlocked"));
        if (old < end && d.day >= end) Bukkit.broadcastMessage(p.msg("end-unlocked"));
        Util.soundAll("LEVEL_UP", 1f, 1f);
        checkGrace();
        d.save();
    }

    // ================================================================= pvp / grace
    public boolean isPvpEnabled() {
        GameState st = d().state;
        if (st == GameState.ENDED) return true;
        if (st != GameState.RUNNING) return false;
        if (!c().getBoolean("grace.enabled", true)) return true;
        if ("MINUTES".equalsIgnoreCase(c().getString("grace.unit", "DAYS"))) return System.currentTimeMillis() >= d().graceEnd;
        return d().day > c().getInt("grace.amount", 2);
    }

    public String graceLeftText() {
        if ("MINUTES".equalsIgnoreCase(c().getString("grace.unit", "DAYS"))) {
            return Util.fmt((d().graceEnd - System.currentTimeMillis()) / 1000);
        }
        return Math.max(0, c().getInt("grace.amount", 2) - d().day + 1) + "d";
    }

    private void checkGrace() {
        boolean now = isPvpEnabled();
        if (now && !lastPvp && d().state == GameState.RUNNING) {
            Bukkit.broadcastMessage(p.msg("pvp-enabled"));
            Util.soundAll("ENDERDRAGON_GROWL", 1f, 1f);
        }
        lastPvp = now;
    }

    // ================================================================= scatter / dome
    /** @return null on success, else an error message */
    public String scatter() {
        DataStore d = d();
        if (d.state != GameState.SETUP && d.state != GameState.FROZEN) return p.msg("scatter-bad-state");
        World w = Bukkit.getWorld(c().getString("general.world", "world"));
        if (w == null) return p.msg("scatter-no-world", "world", c().getString("general.world", "world"));
        if (d.state == GameState.FROZEN) removeDomes();

        // players without a team
        List<String> noTeam = new ArrayList<String>();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (isBypass(pl) || p.teams.of(pl.getUniqueId()) != null) continue;
            if (c().getBoolean("game.auto-solo-teams", true)) p.teams.createSolo(pl);
            else noTeam.add(pl.getName());
        }
        if (!noTeam.isEmpty()) return p.msg("scatter-no-team", "players", join(noTeam));

        // everybody must be online
        List<String> offline = new ArrayList<String>();
        for (VaroTeam t : d.teams.values())
            for (UUID id : t.members) if (Bukkit.getPlayer(id) == null) offline.add(Util.nameOf(p, id));
        if (!offline.isEmpty()) return p.msg("scatter-offline", "players", join(offline));
        if (d.teams.isEmpty()) return p.msg("scatter-no-teams");

        d.participants.clear();
        List<Location> points = new ArrayList<Location>();
        Random r = new Random();
        boolean dome = c().getBoolean("spawn.dome.enabled", true);
        for (VaroTeam t : d.teams.values()) {
            Location base = findSpawn(w, points, r);
            points.add(base);
            int n = t.members.size(), i = 0;
            for (UUID id : t.members) {
                Player pl = Bukkit.getPlayer(id);
                d.participants.add(id);
                double ang = 2 * Math.PI * i / Math.max(1, n);
                double off = n > 1 ? 1.5 : 0;
                Location l = base.clone().add(Math.cos(ang) * off, 0, Math.sin(ang) * off);
                pl.teleport(l);
                prepare(pl);
                i++;
            }
            if (dome) buildDome(base);
        }
        d.state = GameState.FROZEN;
        p.boards.bump();
        d.save();
        Bukkit.broadcastMessage(p.msg("scatter-done", "teams", d.teams.size()));
        return null;
    }

    private String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    private void prepare(Player pl) {
        pl.setGameMode(GameMode.SURVIVAL);
        pl.setAllowFlight(false);
        pl.setFlying(false);
        pl.setHealth(pl.getMaxHealth());
        pl.setFoodLevel(20);
        pl.setSaturation(20f);
        pl.setFireTicks(0);
        pl.setFallDistance(0f);
        for (PotionEffect ef : pl.getActivePotionEffects()) pl.removePotionEffect(ef.getType());
        if (c().getBoolean("spawn.clear-inventory", true)) {
            pl.getInventory().clear();
            pl.getInventory().setArmorContents(new ItemStack[4]);
            pl.setLevel(0);
            pl.setExp(0f);
        }
        pl.updateInventory();
    }

    private Location findSpawn(World w, List<Location> used, Random r) {
        double cx = p.border.centerX(), cz = p.border.centerZ();
        int rad = c().getInt("spawn.scatter-radius", 800);
        if (p.border.enabled()) rad = (int) Math.min(rad, p.border.sizeForDay(1) / 2 - 20);
        rad = Math.max(10, rad);
        int minDist = c().getInt("spawn.min-team-distance", 100);
        int attempts = Math.max(1, c().getInt("spawn.max-attempts", 60));
        Location best = null;
        for (int i = 0; i < attempts; i++) {
            int x = (int) cx + r.nextInt(rad * 2 + 1) - rad;
            int z = (int) cz + r.nextInt(rad * 2 + 1) - rad;
            w.loadChunk(x >> 4, z >> 4, true);
            int y = w.getHighestBlockYAt(x, z);
            if (y < 2) continue;
            Material below = w.getBlockAt(x, y - 1, z).getType();
            if (below == Material.WATER || below == Material.STATIONARY_WATER || below == Material.LAVA
                    || below == Material.STATIONARY_LAVA || below == Material.LEAVES || below == Material.LEAVES_2
                    || below == Material.CACTUS || below == Material.ICE) continue;
            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            best = loc;
            boolean ok = true;
            for (Location u : used) if (u.distanceSquared(loc) < (double) minDist * minDist) { ok = false; break; }
            if (ok) return loc;
        }
        return best != null ? best : w.getSpawnLocation();
    }

    private Material domeMaterial() {
        Material m = Material.matchMaterial(c().getString("spawn.dome.material", "GLASS"));
        return m == null ? Material.GLASS : m;
    }

    private void buildDome(Location center) {
        int r = Math.max(2, c().getInt("spawn.dome.radius", 4));
        Material mat = domeMaterial();
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY() + 1, cz = center.getBlockZ();
        double inner = (r - 0.5) * (r - 0.5), outer = (r + 0.5) * (r + 0.5);
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double dist = x * x + y * y + z * z;
                    if (dist < inner || dist > outer) continue;
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    Material t = b.getType();
                    boolean replace = t == Material.AIR
                            || (!t.isSolid() && t != Material.WATER && t != Material.STATIONARY_WATER
                                    && t != Material.LAVA && t != Material.STATIONARY_LAVA);
                    if (!replace) continue;
                    b.setType(mat);
                    d().domes.add(w.getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ());
                }
            }
        }
    }

    public void removeDomes() {
        Material mat = domeMaterial();
        for (String s : d().domes) {
            String[] a = s.split(";");
            if (a.length < 4) continue;
            World w = Bukkit.getWorld(a[0]);
            if (w == null) continue;
            try {
                Block b = w.getBlockAt(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                if (b.getType() == mat) b.setType(Material.AIR);
            } catch (NumberFormatException ignored) {
            }
        }
        d().domes.clear();
    }

    // ================================================================= start / abort / reset
    /** @return null on success, else error message */
    public String startCountdown(int secs) {
        DataStore d = d();
        if (d.state == GameState.RUNNING || d.state == GameState.ENDED) return p.msg("game-already-running");
        if (d.state == GameState.COUNTDOWN) return p.msg("countdown-running");
        if (d.state == GameState.SETUP) {
            if (!c().getBoolean("game.auto-scatter-on-start", true)) return p.msg("need-scatter");
            String err = scatter();
            if (err != null) return err;
        }
        d.state = GameState.COUNTDOWN;
        countdownLeft = Math.max(0, secs);
        final List<Integer> announce = c().getIntegerList("game.countdown-announce");
        countdownTask = new BukkitRunnable() {
            public void run() {
                if (countdownLeft <= 0) {
                    cancel();
                    countdownTask = null;
                    beginGame();
                    return;
                }
                if (announce.contains(countdownLeft)) {
                    Bukkit.broadcastMessage(p.msg("countdown", "time", countdownLeft));
                    Util.soundAll(c().getString("game.countdown-sound", "NOTE_PLING"), 1f, 1f);
                }
                countdownLeft--;
            }
        }.runTaskTimer(p, 0L, 20L);
        return null;
    }

    public boolean abort() {
        if (d().state != GameState.COUNTDOWN) return false;
        removeAllCountdown();
        d().state = GameState.FROZEN;
        Bukkit.broadcastMessage(p.msg("countdown-aborted"));
        return true;
    }

    private void beginGame() {
        DataStore d = d();
        removeDomes();
        d.state = GameState.RUNNING;
        d.day = 1;
        d.dayKey = currentDayKey();
        d.startMillis = System.currentTimeMillis();
        d.used.clear();
        if ("MINUTES".equalsIgnoreCase(c().getString("grace.unit", "DAYS"))) {
            d.graceEnd = d.startMillis + c().getLong("grace.amount", 2) * 60000L;
        }
        lastPvp = isPvpEnabled();
        p.boards.bump();
        Bukkit.broadcastMessage(p.msg("game-start"));
        Bukkit.broadcastMessage(p.msg("new-day", "day", 1, "size", (int) p.border.sizeForDay(1),
                "next", (int) p.border.sizeForDay(2)));
        Util.soundAll(c().getString("game.start-sound", "ENDERDRAGON_GROWL"), 1f, 1f);
        d.save();
    }

    public void reset(boolean alsoTeams) {
        removeAllCountdown();
        removeDomes();
        p.combat.removeAllNpcs();
        for (Player pl : Bukkit.getOnlinePlayers()) unSpectate(pl);
        d().clearGame(alsoTeams);
        p.boards.bump();
        d().save();
    }

    // ================================================================= eliminations
    public void eliminate(UUID id, String name, String message, Location where) {
        DataStore d = d();
        if (d.dead.containsKey(id)) return;
        DataStore.DeathRecord r = new DataStore.DeathRecord();
        r.name = name;
        r.reason = message;
        r.time = System.currentTimeMillis();
        d.dead.put(id, r);
        p.combat.untag(id);
        Bukkit.broadcastMessage(p.msg("death-broadcast", "message", message, "teams", aliveTeamCount(), "players", aliveCount()));
        if (where != null && c().getBoolean("death.lightning", true)) where.getWorld().strikeLightningEffect(where);
        Util.soundAll(c().getString("death.sound", "AMBIENCE_THUNDER"), 1f, 1f);
        p.boards.bump();
        checkWin();
        d.save();
    }

    /** After a death event: remember where, and kick (BAN mode). Spectators are set up on respawn. */
    public void afterDeath(final Player pl) {
        deathLocs.put(pl.getUniqueId(), pl.getLocation());
        if (!"SPECTATOR".equalsIgnoreCase(c().getString("death.mode", "BAN"))) {
            Bukkit.getScheduler().runTaskLater(p, new Runnable() {
                public void run() { kickDead(pl); }
            }, Math.max(1, c().getInt("death.kick-delay-ticks", 40)));
        }
    }

    private void kickDead(Player pl) {
        if (!pl.isOnline()) return;
        DataStore.DeathRecord r = d().dead.get(pl.getUniqueId());
        pl.kickPlayer(p.raw("kick-dead", "reason", r == null ? "" : r.reason));
    }

    /** Eliminated while alive and online (strikes): kick or make spectator. */
    public void applyElimination(Player pl) {
        if ("SPECTATOR".equalsIgnoreCase(c().getString("death.mode", "BAN"))) makeSpectator(pl);
        else kickDead(pl);
    }

    public Location deathLoc(Player pl) { return deathLocs.get(pl.getUniqueId()); }

    public void makeSpectator(Player pl) {
        pl.setGameMode(GameMode.CREATIVE);
        pl.setAllowFlight(true);
        pl.setFlying(true);
        if (c().getBoolean("death.spectator.invisible", true)) {
            pl.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true), true);
        }
        if (c().getBoolean("death.spectator.hide-from-players", true)) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(pl)) continue;
                if (isSpectator(o)) { o.showPlayer(pl); pl.showPlayer(o); }
                else o.hidePlayer(pl);
            }
        }
        pl.sendMessage(p.msg("spectator-info"));
    }

    public void unSpectate(Player pl) {
        pl.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (pl.getGameMode() == GameMode.CREATIVE && !pl.hasPermission("varo.admin")) pl.setGameMode(GameMode.SURVIVAL);
        for (Player o : Bukkit.getOnlinePlayers()) { o.showPlayer(pl); pl.showPlayer(o); }
    }

    public boolean revive(UUID id) {
        if (d().dead.remove(id) == null) return false;
        Player pl = Bukkit.getPlayer(id);
        if (pl != null) unSpectate(pl);
        d().pendingClear.remove(id);
        p.boards.bump();
        d().save();
        return true;
    }

    public void checkWin() {
        if (d().state != GameState.RUNNING) return;
        List<VaroTeam> alive = aliveTeams();
        if (alive.size() > 1) return;
        d().state = GameState.ENDED;
        if (alive.size() == 1) {
            VaroTeam t = alive.get(0);
            Bukkit.broadcastMessage(p.msg("win", "team", t.name, "members", p.teams.memberNames(t)));
            Util.soundAll(c().getString("game.win-sound", "LEVEL_UP"), 1f, 1f);
        } else {
            Bukkit.broadcastMessage(p.msg("win-none"));
        }
        d().save();
    }

    // ================================================================= trophy
    public ItemStack head(String owner, String killer) {
        ItemStack is = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta m = (SkullMeta) is.getItemMeta();
        m.setOwner(owner);
        m.setDisplayName(Util.color(c().getString("trophy.name", "&6%player%'s Head").replace("%player%", owner)));
        String k = killer != null ? killer : c().getString("trophy.unknown-killer", "unknown");
        List<String> lore = new ArrayList<String>();
        for (String s : c().getStringList("trophy.lore")) {
            lore.add(Util.color(s.replace("%player%", owner).replace("%killer%", k)));
        }
        m.setLore(lore);
        is.setItemMeta(m);
        return is;
    }
}
