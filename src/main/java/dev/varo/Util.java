package dev.varo;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;

public final class Util {
    private Util() {}

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String fmt(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    public static void sound(Player pl, String name, float vol, float pitch) {
        if (name == null || name.isEmpty()) return;
        try {
            pl.playSound(pl.getLocation(), Sound.valueOf(name.toUpperCase()), vol, pitch);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public static void soundAll(String name, float vol, float pitch) {
        for (Player pl : Bukkit.getOnlinePlayers()) sound(pl, name, vol, pitch);
    }

    /** entries like "GOLDEN_APPLE" or "GOLDEN_APPLE:1" */
    public static boolean matches(ItemStack is, List<String> entries) {
        if (is == null || is.getType() == Material.AIR) return false;
        for (String e : entries) {
            String[] parts = e.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || m != is.getType()) continue;
            if (parts.length == 1) return true;
            try {
                if (is.getDurability() == Short.parseShort(parts[1].trim())) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    public static UUID findUuid(VaroPlugin p, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> en : p.data.names.entrySet()) {
            if (en.getValue() != null && en.getValue().equalsIgnoreCase(name)) return en.getKey();
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op != null && op.hasPlayedBefore()) return op.getUniqueId();
        return null;
    }

    public static String nameOf(VaroPlugin p, UUID id) {
        String n = p.data.names.get(id);
        if (n == null) {
            Player o = Bukkit.getPlayer(id);
            n = o != null ? o.getName() : "Unknown";
        }
        return n;
    }

    /** The player behind a damager (direct hit or projectile), or null. */
    public static Player shooter(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile) {
            Object s = ((Projectile) damager).getShooter();
            if (s instanceof Player) return (Player) s;
        }
        return null;
    }

    public static int[] parseHm(String s, int defH, int defM) {
        try {
            String[] a = s.trim().split(":");
            return new int[] { Integer.parseInt(a[0]), Integer.parseInt(a[1]) };
        } catch (Exception e) {
            return new int[] { defH, defM };
        }
    }

    public static String join(String[] a, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
