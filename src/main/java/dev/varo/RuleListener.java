package dev.varo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionEffect;

/** Item/enchant/potion nerfs, anti portal trapping, dimension locks. */
public class RuleListener implements Listener {
    private final VaroPlugin p;
    private final Map<UUID, Long> cd = new HashMap<UUID, Long>();

    public RuleListener(VaroPlugin p) { this.p = p; }

    private FileConfiguration c() { return p.getConfig(); }

    private boolean cooldown(Player pl, long ms) {
        long now = System.currentTimeMillis();
        Long l = cd.get(pl.getUniqueId());
        if (l != null && now - l < ms) return true;
        cd.put(pl.getUniqueId(), now);
        return false;
    }

    // ================================================================ crafting / consuming
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        if (Util.matches(e.getRecipe().getResult(), c().getStringList("nerfs.disabled-crafting"))) {
            e.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getRecipe() == null) return;
        if (Util.matches(e.getRecipe().getResult(), c().getStringList("nerfs.disabled-crafting"))) {
            e.setCancelled(true);
            HumanEntity h = e.getWhoClicked();
            if (h instanceof Player) ((Player) h).sendMessage(p.msg("nerf-crafting"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player pl = e.getPlayer();
        ItemStack it = e.getItem();
        if (Util.matches(it, c().getStringList("nerfs.disabled-consume"))) {
            e.setCancelled(true);
            pl.sendMessage(p.msg("nerf-consume"));
            return;
        }
        if (it != null && it.getType() == Material.POTION) {
            try {
                if (potionBlocked(Potion.fromItemStack(it).getEffects())) {
                    e.setCancelled(true);
                    pl.sendMessage(p.msg("nerf-potion"));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion tp = e.getPotion();
        if (!potionBlocked(tp.getEffects())) return;
        e.setCancelled(true);
        Object src = tp.getShooter();
        if (src instanceof Player) ((Player) src).sendMessage(p.msg("nerf-potion"));
    }

    private boolean potionBlocked(Collection<PotionEffect> effects) {
        if (!c().getBoolean("nerfs.potions.enabled", true)) return false;
        int maxAmp = c().getInt("nerfs.potions.max-amplifier", 0);
        for (PotionEffect ef : effects) {
            if (maxAmp >= 0 && ef.getAmplifier() > maxAmp) return true;
            for (String b : c().getStringList("nerfs.potions.blocked-effects")) {
                if (b.equalsIgnoreCase(ef.getType().getName())) return true;
            }
        }
        return false;
    }

    /** blocks brewing with e.g. glowstone (-> no level II potions) */
    @EventHandler(ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        if (!c().getBoolean("nerfs.potions.enabled", true)) return;
        if (Util.matches(e.getContents().getIngredient(), c().getStringList("nerfs.potions.blocked-brew-ingredients"))) {
            e.setCancelled(true);
        }
    }

    // ================================================================ enchantments
    private int cap(Enchantment en) {
        return c().getInt("nerfs.enchantments.max-levels." + en.getName(), c().getInt("nerfs.enchantments.default-max-level", 0));
    }

    private boolean disabled(Enchantment en) {
        for (String s : c().getStringList("nerfs.enchantments.disabled")) if (s.equalsIgnoreCase(en.getName())) return true;
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!c().getBoolean("nerfs.enchantments.enabled", true)) return;
        Map<Enchantment, Integer> m = e.getEnchantsToAdd();
        for (Enchantment en : new ArrayList<Enchantment>(m.keySet())) {
            if (disabled(en)) { m.remove(en); continue; }
            int cap = cap(en);
            if (cap > 0 && m.get(en) > cap) m.put(en, cap);
        }
    }

    private boolean violates(Map<Enchantment, Integer> map) {
        for (Map.Entry<Enchantment, Integer> en : map.entrySet()) {
            if (disabled(en.getKey())) return true;
            int cap = cap(en.getKey());
            if (cap > 0 && en.getValue() > cap) return true;
        }
        return false;
    }

    /** anvil: block taking a result that exceeds the caps */
    @EventHandler(ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent e) {
        if (!c().getBoolean("nerfs.enchantments.enabled", true)) return;
        if (e.getInventory().getType() != InventoryType.ANVIL || e.getRawSlot() != 2) return;
        ItemStack res = e.getCurrentItem();
        if (res == null || res.getType() == Material.AIR) return;
        boolean bad = violates(res.getEnchantments());
        ItemMeta meta = res.getItemMeta();
        if (!bad && meta instanceof EnchantmentStorageMeta) bad = violates(((EnchantmentStorageMeta) meta).getStoredEnchants());
        if (bad) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player) ((Player) e.getWhoClicked()).sendMessage(p.msg("nerf-enchant"));
        }
    }

    // ================================================================ anti portal trapping
    private String norm(Material m) {
        String n = m.name();
        if (n.equals("STATIONARY_LAVA") || n.equals("LAVA_BUCKET")) return "LAVA";
        if (n.equals("STATIONARY_WATER") || n.equals("WATER_BUCKET")) return "WATER";
        return n;
    }

    private boolean blockedBlock(Material m) {
        Set<String> set = new HashSet<String>();
        for (String s : c().getStringList("anti-portal-trap.blocked-blocks")) {
            Material mm = Material.matchMaterial(s);
            set.add(mm != null ? norm(mm) : s.toUpperCase());
        }
        return set.contains(norm(m));
    }

    private boolean nearPortal(Location l, int r) {
        World w = l.getWorld();
        int cx = l.getBlockX(), cy = l.getBlockY(), cz = l.getBlockZ();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++)
                    if (w.getBlockAt(cx + x, cy + y, cz + z).getType() == Material.PORTAL) return true;
        return false;
    }

    private boolean trap() { return c().getBoolean("anti-portal-trap.enabled", true); }
    private int radius() { return c().getInt("anti-portal-trap.radius", 4); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!trap() || e.getPlayer().hasPermission("varo.bypass")) return;
        if (blockedBlock(e.getBlockPlaced().getType()) && nearPortal(e.getBlockPlaced().getLocation(), radius())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(p.msg("anti-portal"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!trap() || e.getPlayer().hasPermission("varo.bypass")) return;
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        if (blockedBlock(e.getBucket()) && nearPortal(target.getLocation(), radius())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(p.msg("anti-portal"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!trap()) return;
        if (blockedBlock(e.getItem().getType()) && nearPortal(e.getBlock().getLocation(), radius() + 1)) e.setCancelled(true);
    }

    // ================================================================ dimension lock + border clamp
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        final Player pl = e.getPlayer();
        if (p.data.state != GameState.RUNNING || p.game.isBypass(pl)) return;
        World.Environment from = e.getFrom().getWorld().getEnvironment();
        if (from == World.Environment.NORMAL) {
            int need = 0;
            if (e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) need = c().getInt("dimensions.nether-unlock-day", 3);
            else if (e.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) need = c().getInt("dimensions.end-unlock-day", 6);
            if (p.data.day < need) {
                e.setCancelled(true);
                if (!cooldown(pl, 3000)) pl.sendMessage(p.msg("dimension-locked", "day", need));
                return;
            }
        }
        if (p.border.enabled() && p.game.isActive(pl)) {
            Bukkit.getScheduler().runTaskLater(p, new Runnable() {
                public void run() {
                    if (pl.isOnline() && p.border.isOutside(pl.getLocation())) pl.teleport(p.border.clamp(pl.getLocation()));
                }
            }, 10L);
        }
    }
}
