package dev.varo;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

public class CombatListener implements Listener {
    private final VaroPlugin p;

    public CombatListener(VaroPlugin p) { this.p = p; }

    /** combat tag + protection of combat-log NPCs */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player damager = Util.shooter(e.getDamager());
        if (damager == null) return;
        Entity victim = e.getEntity();

        CombatManager.Npc npc = p.combat.npcFor(victim);
        if (npc != null) {
            boolean ff = p.getConfig().getBoolean("teams.friendly-fire", false);
            if ((!p.game.isPvpEnabled() && !p.game.isBypass(damager))
                    || (!ff && p.teams.sameTeam(damager.getUniqueId(), npc.owner))) {
                e.setCancelled(true);
            }
            return;
        }
        if (victim instanceof Player && !victim.equals(damager) && p.data.state == GameState.RUNNING) {
            p.combat.tag(damager, (Player) victim);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcInteract(PlayerInteractEntityEvent e) {
        if (p.combat.npcFor(e.getRightClicked()) != null) e.setCancelled(true);   // no villager trading
    }

    /** logging out while tagged */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player pl = e.getPlayer();
        UUID id = pl.getUniqueId();
        boolean timeKick = p.game.consumeTimeKick(id);
        if (p.data.state != GameState.RUNNING || !p.game.isActive(pl) || !p.combat.enabled() || !p.combat.isTagged(pl)) return;
        if (timeKick && !p.getConfig().getBoolean("combat.apply-on-time-expiry", true)) return;

        String mode = p.getConfig().getString("combat.mode", "NPC").toUpperCase();
        String attacker = p.combat.lastAttacker(id);
        e.setQuitMessage(null);
        if (mode.equals("KILL")) {
            p.combat.markCombatLogKill(id, attacker);
            Bukkit.broadcastMessage(p.msg("combatlog-broadcast", "player", pl.getName()));
            p.combat.untag(id);
            pl.setHealth(0.0);   // fires PlayerDeathEvent -> normal elimination
        } else if (mode.equals("NPC")) {
            p.combat.spawnNpc(pl);
            Bukkit.broadcastMessage(p.msg("combatlog-npc", "player", pl.getName(),
                    "seconds", p.getConfig().getInt("combat.npc-lifetime-seconds", 60)));
            p.combat.untag(id);
        }
    }

    /** NPC killed = the player is eliminated, loot drops, head drops */
    @EventHandler
    public void onNpcDeath(EntityDeathEvent e) {
        CombatManager.Npc npc = p.combat.npcFor(e.getEntity());
        if (npc == null) return;
        p.combat.forget(npc);
        Player killer = e.getEntity().getKiller();
        String kn = killer != null ? killer.getName() : null;

        e.getDrops().clear();
        for (ItemStack is : npc.items) if (is != null && is.getType() != Material.AIR) e.getDrops().add(is);
        e.setDroppedExp(npc.exp);
        if (p.getConfig().getBoolean("trophy.enabled", true) && (kn != null || !p.getConfig().getBoolean("trophy.only-pvp", true))) {
            e.getDrops().add(p.game.head(npc.ownerName, kn));
        }
        p.data.pendingClear.add(npc.owner);   // wipe inventory when he logs in again (spectator mode)
        Location loc = e.getEntity().getLocation();
        p.game.eliminate(npc.owner, npc.ownerName, p.raw("combatlog-npc-death", "player", npc.ownerName), loc);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player pl = e.getPlayer();
        if (!p.getConfig().getBoolean("combat.block-commands", true) || !p.combat.enabled()) return;
        if (!p.combat.isTagged(pl) || pl.hasPermission("varo.bypass")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        for (String a : p.getConfig().getStringList("combat.allowed-commands")) if (a.equalsIgnoreCase(cmd)) return;
        e.setCancelled(true);
        pl.sendMessage(p.msg("combat-command"));
    }
}
