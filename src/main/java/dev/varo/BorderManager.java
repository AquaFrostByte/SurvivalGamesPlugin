package dev.varo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * 1.7.10 has no WorldBorder API, so the border is a custom square:
 * movement wall + damage outside + optional particles. Size depends on the VARO day.
 */
public class BorderManager {
    private final VaroPlugin p;
    private final Map<UUID, Integer> warnCounter = new HashMap<UUID, Integer>();

    public BorderManager(VaroPlugin p) { this.p = p; }

    private FileConfiguration c() { return p.getConfig(); }

    public boolean enabled() { return c().getBoolean("border.enabled", true); }
    public double centerX() { return c().getDouble("border.center-x", 0); }
    public double centerZ() { return c().getDouble("border.center-z", 0); }

    /** Side length of the border on the given VARO day. */
    public double sizeForDay(int day) {
        if (day < 1) day = 1;
        ConfigurationSection cs = c().getConfigurationSection("border.custom-sizes");
        if (cs != null && cs.contains(String.valueOf(day))) return cs.getDouble(String.valueOf(day));
        double start = c().getDouble("border.start-size", 2000);
        double shrink = c().getDouble("border.shrink-per-day", 150);
        double min = c().getDouble("border.min-size", 200);
        return Math.max(min, start - (day - 1) * shrink);
    }

    public double currentSize() { return sizeForDay(Math.max(1, p.data.day)); }
    public double nextSize() { return sizeForDay(Math.max(1, p.data.day) + 1); }

    /** coordinate scale of a world relative to the overworld; 0 = no border there */
    private double scale(World w) {
        switch (w.getEnvironment()) {
            case NORMAL: return 1.0;
            case NETHER:
                if (!c().getBoolean("border.apply-to-nether", true)) return 0;
                return 1.0 / Math.max(1, c().getDouble("border.nether-scale", 8));
            default: return 0;
        }
    }

    /** > 0 = number of blocks outside the border of the given overworld size; <= 0 = inside. */
    public double overshoot(Location l, double size) {
        if (!enabled()) return -1e9;
        double s = scale(l.getWorld());
        if (s <= 0) return -1e9;
        double half = size * s / 2.0;
        double cx = centerX() * s, cz = centerZ() * s;
        return Math.max(Math.abs(l.getX() - cx), Math.abs(l.getZ() - cz)) - half;
    }

    public boolean isOutside(Location l) { return overshoot(l, currentSize()) > 0; }

    public boolean blocksMove(Player pl, Location from, Location to) {
        if (!enabled() || !c().getBoolean("border.wall", true)) return false;
        if (p.data.state != GameState.RUNNING || !p.game.isActive(pl)) return false;
        double size = currentSize();
        double ot = overshoot(to, size);
        if (ot <= 0) return false;
        return ot >= overshoot(from, size);   // walking further out is blocked, walking in is fine
    }

    /** Nearest point inside the border (with a small margin). */
    public Location clamp(Location l) {
        double s = scale(l.getWorld());
        if (s <= 0) return l;
        double half = currentSize() * s / 2.0 - 3;
        double cx = centerX() * s, cz = centerZ() * s;
        double x = Math.max(cx - half, Math.min(cx + half, l.getX()));
        double z = Math.max(cz - half, Math.min(cz + half, l.getZ()));
        Location out = new Location(l.getWorld(), x, l.getY(), z, l.getYaw(), l.getPitch());
        if (l.getWorld().getEnvironment() == World.Environment.NORMAL) {
            out.setY(l.getWorld().getHighestBlockYAt((int) x, (int) z) + 0.5);
        }
        return out;
    }

    /** Called every second. */
    public void tick() {
        if (!enabled() || p.data.state != GameState.RUNNING) return;
        double size = currentSize();
        double dmg = c().getDouble("border.damage-per-second", 2.0);
        int interval = Math.max(1, c().getInt("border.message-interval-seconds", 5));
        boolean visual = c().getBoolean("border.visual.enabled", true);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (!p.game.isActive(pl)) continue;
            double o = overshoot(pl.getLocation(), size);
            if (o > 0) {
                if (dmg > 0) pl.damage(dmg);
                Integer n = warnCounter.get(pl.getUniqueId());
                int v = n == null ? 0 : n;
                if (v % interval == 0) pl.sendMessage(p.msg("border-warning"));
                warnCounter.put(pl.getUniqueId(), v + 1);
            } else {
                warnCounter.remove(pl.getUniqueId());
                if (visual) drawWalls(pl, size);
            }
        }
    }

    private void drawWalls(Player pl, double size) {
        Location l = pl.getLocation();
        World w = l.getWorld();
        double s = scale(w);
        if (s <= 0) return;
        Effect eff;
        try { eff = Effect.valueOf(c().getString("border.visual.effect", "MOBSPAWNER_FLAMES").toUpperCase()); }
        catch (IllegalArgumentException e) { return; }
        double half = size * s / 2.0;
        double cx = centerX() * s, cz = centerZ() * s;
        double dist = c().getDouble("border.visual.distance", 8);
        if ((cx + half) - l.getX() <= dist) plane(pl, w, eff, cx + half, l, true);
        if (l.getX() - (cx - half) <= dist) plane(pl, w, eff, cx - half, l, true);
        if ((cz + half) - l.getZ() <= dist) plane(pl, w, eff, cz + half, l, false);
        if (l.getZ() - (cz - half) <= dist) plane(pl, w, eff, cz - half, l, false);
    }

    private void plane(Player pl, World w, Effect eff, double fixed, Location l, boolean xWall) {
        for (int a = -6; a <= 6; a += 2) {
            for (int dy = -2; dy <= 4; dy += 2) {
                Location loc = xWall
                        ? new Location(w, fixed, l.getY() + dy, l.getZ() + a)
                        : new Location(w, l.getX() + a, l.getY() + dy, fixed);
                pl.playEffect(loc, eff, 0);
            }
        }
    }
}
