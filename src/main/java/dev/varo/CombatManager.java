package dev.varo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Combat tag + combat-log NPC (a frozen, named villager that carries the player's loot). */
public class CombatManager {
    public static class Npc {
        public UUID owner;
        public String ownerName;
        public Villager entity;
        public ItemStack[] items;
        public int exp;
        public long expires;
    }

    private final VaroPlugin p;
    private final Map<UUID, Long> tagUntil = new HashMap<UUID, Long>();
    private final Map<UUID, String> lastAttacker = new HashMap<UUID, String>();
    private final Map<UUID, String> combatLogKills = new HashMap<UUID, String>();
    private final Map<UUID, Npc> byEntity = new HashMap<UUID, Npc>();
    private final Map<UUID, Npc> byOwner = new HashMap<UUID, Npc>();

    public CombatManager(VaroPlugin p) { this.p = p; }

    public boolean enabled() {
        return p.getConfig().getBoolean("combat.enabled", true)
                && !"NONE".equalsIgnoreCase(p.getConfig().getString("combat.mode", "NPC"));
    }

    // ------------------------------------------------------------------ tag
    public void tag(Player a, Player b) {
        if (!enabled()) return;
        long until = System.currentTimeMillis() + p.getConfig().getInt("combat.tag-seconds", 15) * 1000L;
        tagOne(a, b.getName(), until);
        tagOne(b, a.getName(), until);
    }

    private void tagOne(Player pl, String other, long until) {
        if (!p.game.isActive(pl)) return;
        boolean was = isTagged(pl);
        tagUntil.put(pl.getUniqueId(), until);
        lastAttacker.put(pl.getUniqueId(), other);
        if (!was) pl.sendMessage(p.msg("combat-tagged", "time", left(pl)));
    }

    public boolean isTagged(Player pl) {
        Long t = tagUntil.get(pl.getUniqueId());
        return t != null && t > System.currentTimeMillis();
    }

    public int left(Player pl) {
        Long t = tagUntil.get(pl.getUniqueId());
        if (t == null) return 0;
        return (int) Math.max(0, (t - System.currentTimeMillis() + 999) / 1000);
    }

    public String lastAttacker(UUID id) { return lastAttacker.get(id); }

    public void untag(UUID id) { tagUntil.remove(id); }

    public void markCombatLogKill(UUID id, String killer) { combatLogKills.put(id, killer == null ? "" : killer); }

    /** returns killer name ("" = unknown) if this death is a combat-log kill, else null; consumes the flag */
    public String pollCombatLogKill(UUID id) { return combatLogKills.remove(id); }

    // ------------------------------------------------------------------ npc
    public Npc npcFor(Entity e) { return byEntity.get(e.getUniqueId()); }

    public void spawnNpc(Player pl) {
        Location loc = pl.getLocation();
        Villager v = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        v.setCustomName("\u00a7c" + pl.getName());
        v.setCustomNameVisible(true);
        v.setRemoveWhenFarAway(false);
        v.setCanPickupItems(false);
        v.setMaxHealth(Math.max(1.0, pl.getMaxHealth()));
        v.setHealth(Math.max(1.0, Math.min(pl.getHealth(), v.getMaxHealth())));
        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 10, true), true);

        Npc n = new Npc();
        n.owner = pl.getUniqueId();
        n.ownerName = pl.getName();
        n.entity = v;
        ItemStack[] inv = pl.getInventory().getContents();
        ItemStack[] arm = pl.getInventory().getArmorContents();
        n.items = new ItemStack[inv.length + arm.length];
        for (int i = 0; i < inv.length; i++) n.items[i] = inv[i] == null ? null : inv[i].clone();
        for (int i = 0; i < arm.length; i++) n.items[inv.length + i] = arm[i] == null ? null : arm[i].clone();
        n.exp = Math.min(100, pl.getTotalExperience());
        n.expires = System.currentTimeMillis() + p.getConfig().getInt("combat.npc-lifetime-seconds", 60) * 1000L;
        byEntity.put(v.getUniqueId(), n);
        byOwner.put(n.owner, n);
    }

    /** NPC died: forget it (caller handles drops / elimination). */
    public void forget(Npc n) {
        byEntity.remove(n.entity.getUniqueId());
        byOwner.remove(n.owner);
    }

    private void despawn(Npc n) {
        forget(n);
        if (n.entity != null && !n.entity.isDead()) n.entity.remove();
    }

    /** Owner logs back in: remove NPC, keep the NPC's remaining health. */
    public void onOwnerJoin(Player pl) {
        Npc n = byOwner.get(pl.getUniqueId());
        if (n == null) return;
        if (n.entity != null && !n.entity.isDead()) {
            pl.setHealth(Math.max(1.0, Math.min(pl.getMaxHealth(), n.entity.getHealth())));
        }
        despawn(n);
    }

    public void removeAllNpcs() {
        for (Npc n : new java.util.ArrayList<Npc>(byOwner.values())) despawn(n);
    }

    /** every second */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Npc n : new java.util.ArrayList<Npc>(byOwner.values())) {
            if (n.expires <= now || n.entity == null || n.entity.isDead()) despawn(n);
        }
        Iterator<Map.Entry<UUID, Long>> it = tagUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                Player pl = Bukkit.getPlayer(e.getKey());
                if (pl != null) pl.sendMessage(p.msg("combat-ended"));
            }
        }
    }
}
