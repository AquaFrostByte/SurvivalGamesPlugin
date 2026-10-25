package dev.varo;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TeamCommand implements CommandExecutor {
    private final VaroPlugin p;

    public TeamCommand(VaroPlugin p) { this.p = p; }

    private boolean locked() {
        return p.getConfig().getBoolean("teams.lock-after-scatter", true) && p.data.state != GameState.SETUP;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player)) { s.sendMessage(p.msg("player-only")); return true; }
        Player pl = (Player) s;
        if (!pl.hasPermission("varo.team")) { pl.sendMessage(p.msg("no-permission")); return true; }
        UUID id = pl.getUniqueId();
        TeamManager tm = p.teams;

        if (cmd.getName().equalsIgnoreCase("tc")) {
            if (a.length == 0) { pl.sendMessage(p.msg("usage", "usage", "/tc <message>")); return true; }
            tm.chat(pl, Util.join(a, 0));
            return true;
        }
        if (a.length == 0) { pl.sendMessage(p.msg("team-help")); return true; }
        String sub = a[0].toLowerCase();
        VaroTeam mine = tm.of(id);

        if (sub.equals("create")) {
            if (locked()) { pl.sendMessage(p.msg("team-locked")); return true; }
            if (mine != null) { pl.sendMessage(p.msg("team-already")); return true; }
            int max = p.getConfig().getInt("teams.max-name-length", 12);
            if (a.length < 2 || !a[1].matches("[A-Za-z0-9_]+") || a[1].length() > max) {
                pl.sendMessage(p.msg("team-bad-name", "max", max)); return true;
            }
            if (tm.byName(a[1]) != null) { pl.sendMessage(p.msg("team-exists")); return true; }
            VaroTeam t = tm.create(a[1], id);
            pl.sendMessage(p.msg("team-created", "team", t.name));
        } else if (sub.equals("invite")) {
            if (locked()) { pl.sendMessage(p.msg("team-locked")); return true; }
            if (mine == null) { pl.sendMessage(p.msg("team-none")); return true; }
            if (!id.equals(mine.leader())) { pl.sendMessage(p.msg("team-leader-only")); return true; }
            Player target = a.length > 1 ? Bukkit.getPlayer(a[1]) : null;
            if (target == null) { pl.sendMessage(p.msg("unknown-player", "player", a.length > 1 ? a[1] : "?")); return true; }
            if (tm.of(target.getUniqueId()) != null) { pl.sendMessage(p.msg("team-target-busy", "player", target.getName())); return true; }
            if (mine.members.size() >= p.getConfig().getInt("teams.max-size", 2)) { pl.sendMessage(p.msg("team-full")); return true; }
            long exp = System.currentTimeMillis() + p.getConfig().getInt("teams.invite-expire-seconds", 60) * 1000L;
            tm.invites.put(target.getUniqueId(), new TeamManager.Invite(mine.name, exp));
            pl.sendMessage(p.msg("team-invite-sent", "player", target.getName()));
            target.sendMessage(p.msg("team-invited", "leader", pl.getName(), "team", mine.name));
        } else if (sub.equals("accept")) {
            if (locked()) { pl.sendMessage(p.msg("team-locked")); return true; }
            if (mine != null) { pl.sendMessage(p.msg("team-already")); return true; }
            TeamManager.Invite inv = tm.invites.remove(id);
            VaroTeam t = inv == null ? null : tm.byName(inv.team);
            if (inv == null || t == null || inv.expires < System.currentTimeMillis()) { pl.sendMessage(p.msg("team-invite-none")); return true; }
            if (t.members.size() >= p.getConfig().getInt("teams.max-size", 2)) { pl.sendMessage(p.msg("team-full")); return true; }
            tm.add(t, id);
            tm.broadcast(t, p.msg("team-joined", "player", pl.getName()));
        } else if (sub.equals("leave")) {
            if (locked()) { pl.sendMessage(p.msg("team-locked")); return true; }
            if (mine == null) { pl.sendMessage(p.msg("team-none")); return true; }
            tm.remove(id);
            pl.sendMessage(p.msg("team-left", "player", pl.getName()));
            VaroTeam still = tm.byName(mine.name);
            if (still != null) tm.broadcast(still, p.msg("team-left", "player", pl.getName()));
        } else if (sub.equals("kick")) {
            if (locked()) { pl.sendMessage(p.msg("team-locked")); return true; }
            if (mine == null) { pl.sendMessage(p.msg("team-none")); return true; }
            if (!id.equals(mine.leader())) { pl.sendMessage(p.msg("team-leader-only")); return true; }
            UUID tid = a.length > 1 ? Util.findUuid(p, a[1]) : null;
            if (tid == null || tid.equals(id) || !mine.members.contains(tid)) { pl.sendMessage(p.msg("unknown-player", "player", a.length > 1 ? a[1] : "?")); return true; }
            tm.broadcast(mine, p.msg("team-kicked", "player", a[1]));
            tm.remove(tid);
        } else if (sub.equals("disband")) {
            if (locked()) { pl.sendMessage(p.msg("team-locked")); return true; }
            if (mine == null) { pl.sendMessage(p.msg("team-none")); return true; }
            if (!id.equals(mine.leader())) { pl.sendMessage(p.msg("team-leader-only")); return true; }
            tm.broadcast(mine, p.msg("team-disbanded"));
            tm.delete(mine);
        } else if (sub.equals("list")) {
            pl.sendMessage(p.msg("team-list-header"));
            for (VaroTeam t : p.data.teams.values())
                pl.sendMessage(p.msg("team-list-entry", "team", t.name, "size", t.members.size(), "members", tm.memberNames(t)));
        } else if (sub.equals("info")) {
            VaroTeam t = a.length > 1 ? tm.byName(a[1]) : mine;
            if (t == null) { pl.sendMessage(p.msg(a.length > 1 ? "team-not-found" : "team-none")); return true; }
            pl.sendMessage(p.msg("team-info", "team", t.name, "leader", Util.nameOf(p, t.leader()), "members", tm.memberNames(t)));
        } else if (sub.equals("chat")) {
            if (a.length < 2) { pl.sendMessage(p.msg("usage", "usage", "/team chat <message>")); return true; }
            tm.chat(pl, Util.join(a, 1));
        } else {
            pl.sendMessage(p.msg("team-help"));
        }
        return true;
    }
}
