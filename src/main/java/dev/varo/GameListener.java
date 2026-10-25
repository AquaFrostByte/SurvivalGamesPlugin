package dev.varo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class GameListener implements Listener {
    private final VaroPlugin p;
    private final Map<String, Long> cd = new HashMap<String, Long>();

    public GameListener(VaroPlugin p) { this.p = p; }

    private boolean cooldown(Player pl, String key, long ms) {
        String k = pl.getUniqueId() + key;
        long now = System.currentTimeMillis();
        Long l = cd.get(k);
        if (l != null && now - l < ms) return true;
        cd.put(k, now);
        if (cd.size() > 2000) cd.clear();
        return false;
    }

    private boolean staff(Player pl) { return pl.isOp() || pl.hasPermission("varo.admin") || pl.hasPermission("varo.bypass"); }

    // ================================================================ login / join / quit
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent e) {
        Player pl = e.getPlayer();
        UUID id = pl.getUniqueId();
        DataStore d = p.data;
        if (staff(pl) || d.state != GameState.RUNNING) return;

        if (!d.participants.contains(id)) {
            if (p.getConfig().getBoolean("game.deny-non-participants", true)) {
                e.disallow(PlayerLoginEvent.Result.KICK_OTHER, p.raw("login-not-participant"));
            }
            return;
        }
        DataStore.DeathRecord r = d.dead.get(id);
        if (r != null) {
            if (!"SPECTATOR".equalsIgnoreCase(p.getConfig().getString("death.mode", "BAN"))) {
                e.disallow(PlayerLoginEvent.Result.KICK_OTHER, p.raw("login-dead", "reason", r.reason));
            }
            return;
        }
        if (!p.game.inWindow()) {
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, p.raw("login-window-closed",
                    "start", p.getConfig().getString("time.play-window.start"), "end", p.getConfig().getString("time.play-window.end")));
            return;
        }
        if (p.game.remaining(id) <= 0) {
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, p.raw("login-time-over", "reset", p.game.resetText()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player pl = e.getPlayer();
        UUID id = pl.getUniqueId();
        p.data.names.put(id, pl.getName());
        p.combat.onOwnerJoin(pl);

        if (p.data.pendingClear.remove(id)) {
            pl.getInventory().clear();
            pl.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
            pl.setLevel(0);
            pl.setExp(0f);
        }
        if (p.data.state == GameState.RUNNING && p.game.isActive(pl)) {
            pl.sendMessage(p.msg("time-info", "time", Util.fmt(p.game.remaining(id)), "day", p.data.day, "reset", p.game.resetText()));
        }
        // hide spectators from the joiner / make a returning spectator invisible again
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(pl) && p.game.isSpectator(o) && !p.game.isSpectator(pl)
                    && p.getConfig().getBoolean("death.spectator.hide-from-players", true)) pl.hidePlayer(o);
        }
        if (p.game.isSpectator(pl)) {
            Bukkit.getScheduler().runTaskLater(p, new Runnable() {
                public void run() { if (pl.isOnline()) p.game.makeSpectator(pl); }
            }, 2L);
        }
        p.boards.bump();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player pl = e.getPlayer();
        if (p.data.state == GameState.RUNNING && p.getConfig().getBoolean("time.one-session-per-day", false)
                && p.game.isActive(pl) && !p.combat.isTagged(pl)) {
            p.data.used.put(pl.getUniqueId(), p.game.limitSeconds());
        }
        p.boards.remove(pl);
        p.data.save();
    }

    // ================================================================ death
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        if (p.data.state != GameState.RUNNING || !p.game.isActive(v)) return;
        UUID id = v.getUniqueId();

        String cl = p.combat.pollCombatLogKill(id);
        boolean combatLog = cl != null;
        Player killer = v.getKiller();
        String killerName = killer != null ? killer.getName() : (combatLog && !cl.isEmpty() ? cl : null);
        String message = combatLog ? p.raw("combatlog-death", "player", v.getName())
                : (e.getDeathMessage() != null ? e.getDeathMessage() : v.getName() + " died");
        e.setDeathMessage(null);

        if (p.getConfig().getBoolean("death.keep-inventory", false)) {
            e.setKeepInventory(true);
            e.getDrops().clear();
        }
        if (p.getConfig().getBoolean("trophy.enabled", true)
                && (killerName != null || !p.getConfig().getBoolean("trophy.only-pvp", true))) {
            e.getDrops().add(p.game.head(v.getName(), killerName));
        }
        p.game.eliminate(id, v.getName(), message, v.getLocation());
        p.game.afterDeath(v);
    }

    @EventHandler
    public void onRespawn(final PlayerRespawnEvent e) {
        final Player pl = e.getPlayer();
        if (!p.game.isSpectator(pl)) return;
        Location l = p.game.deathLoc(pl);
        if (l != null) e.setRespawnLocation(l);
        Bukkit.getScheduler().runTaskLater(p, new Runnable() {
            public void run() { if (pl.isOnline()) p.game.makeSpectator(pl); }
        }, 2L);
    }

    // ================================================================ movement / border / freeze
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player pl = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        if (p.game.isFrozen(pl)) {
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location f = from.clone();
                f.setYaw(to.getYaw());
                f.setPitch(to.getPitch());
                e.setTo(f);
            }
            return;
        }
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;
        if (p.border.blocksMove(pl, from, to)) {
            Location f = from.clone();
            f.setYaw(to.getYaw());
            f.setPitch(to.getPitch());
            e.setTo(f);
            if (!cooldown(pl, "wall", 2000)) pl.sendMessage(p.msg("border-wall"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player pl = e.getPlayer();
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        if (!p.getConfig().getBoolean("border.block-teleport-outside", true)) return;
        if (p.data.state != GameState.RUNNING || !p.game.isActive(pl) || e.getTo() == null) return;
        if (p.border.overshoot(e.getTo(), p.border.currentSize()) > 0) {
            e.setCancelled(true);
            pl.sendMessage(p.msg("teleport-border"));
        }
    }

    // ================================================================ damage / pvp
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player pl = (Player) e.getEntity();
        if (p.game.isFrozen(pl) || p.game.isSpectator(pl)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        Player damager = Util.shooter(e.getDamager());
        if (damager == null) return;
        if (p.game.isSpectator(damager) || p.game.isFrozen(damager)) { e.setCancelled(true); return; }
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        if (victim.equals(damager)) return;
        if (!p.game.isPvpEnabled() && !p.game.isBypass(damager)) {
            e.setCancelled(true);
            if (!cooldown(damager, "pvp", 2000)) damager.sendMessage(p.msg("grace-no-pvp"));
            return;
        }
        if (!p.getConfig().getBoolean("teams.friendly-fire", false)
                && p.teams.sameTeam(damager.getUniqueId(), victim.getUniqueId())) {
            e.setCancelled(true);
            if (!cooldown(damager, "ff", 2000)) damager.sendMessage(p.msg("friendly-fire"));
        }
    }

    private boolean harmful(Iterable<PotionEffect> effects) {
        for (PotionEffect ef : effects) {
            PotionEffectType t = ef.getType();
            if (t.equals(PotionEffectType.HARM) || t.equals(PotionEffectType.POISON) || t.equals(PotionEffectType.SLOW)
                    || t.equals(PotionEffectType.WEAKNESS) || t.equals(PotionEffectType.WITHER)
                    || t.equals(PotionEffectType.BLINDNESS) || t.equals(PotionEffectType.CONFUSION)
                    || t.equals(PotionEffectType.HUNGER) || t.equals(PotionEffectType.SLOW_DIGGING)) return true;
        }
        return false;
    }

    /** harmful splash potions obey grace period + friendly fire */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion tp = e.getPotion();
        Object src = tp.getShooter();
        if (!(src instanceof Player) || !harmful(tp.getEffects())) return;
        Player thrower = (Player) src;
        boolean pvp = p.game.isPvpEnabled() || p.game.isBypass(thrower);
        boolean ff = p.getConfig().getBoolean("teams.friendly-fire", false);
        for (LivingEntity le : new java.util.ArrayList<LivingEntity>(e.getAffectedEntities())) {
            if (!(le instanceof Player) || le.equals(thrower)) continue;
            if (!pvp || (!ff && p.teams.sameTeam(thrower.getUniqueId(), le.getUniqueId()))) e.setIntensity(le, 0);
        }
    }

    // ================================================================ spectators / frozen restrictions
    private boolean locked(Player pl) { return p.game.isSpectator(pl) || p.game.isFrozen(pl); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Entity t = e.getTarget();
        if (t instanceof Player && p.game.isSpectator((Player) t)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player pl = e.getPlayer();
        if (!p.game.isSpectator(pl)) return;
        // spectators only talk to spectators
        Iterator<Player> it = e.getRecipients().iterator();
        while (it.hasNext()) if (!p.game.isSpectator(it.next())) it.remove();
        e.setFormat(p.raw("spectator-chat", "player", "%1$s", "message", "%2$s"));
    }
}
