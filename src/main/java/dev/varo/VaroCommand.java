package dev.varo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public class VaroCommand implements TabExecutor {
    private static final List<String> SUBS = Arrays.asList("start", "scatter", "abort", "reset", "nextday", "setday",
            "status", "reload", "revive", "time", "team", "border", "help");
    private final VaroPlugin p;

    public VaroCommand(VaroPlugin p) { this.p = p; }

    private void err(CommandSender s, String e) { s.sendMessage(p.msg("admin-error", "error", e)); }

    private UUID uuid(CommandSender s, String name) {
        UUID id = Util.findUuid(p, name);
        if (id == null) s.sendMessage(p.msg("unknown-player", "player", name));
        return id;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("varo.admin")) { s.sendMessage(p.msg("no-permission")); return true; }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s); return true; }
        String sub = a[0].toLowerCase();
        DataStore d = p.data;

        if (sub.equals("start")) {
            int secs = p.getConfig().getInt("game.countdown-seconds", 30);
            if (a.length > 1) { try { secs = Integer.parseInt(a[1]); } catch (NumberFormatException e) { err(s, "Not a number: " + a[1]); return true; } }
            String r = p.game.startCountdown(secs);
            s.sendMessage(r != null ? r : p.msg("countdown-started"));
        } else if (sub.equals("scatter")) {
            String r = p.game.scatter();
            if (r != null) s.sendMessage(r);
        } else if (sub.equals("abort")) {
            if (!p.game.abort()) err(s, "No countdown is running.");
        } else if (sub.equals("reset")) {
            if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) { err(s, "Use /varo reset confirm [all]   (all = also delete teams)"); return true; }
            p.game.reset(a.length > 2 && a[2].equalsIgnoreCase("all"));
            s.sendMessage(p.msg("game-reset"));
        } else if (sub.equals("nextday")) {
            if (d.state != GameState.RUNNING) { s.sendMessage(p.msg("not-running")); return true; }
            p.game.advanceDay(1);
        } else if (sub.equals("setday")) {
            if (d.state != GameState.RUNNING) { s.sendMessage(p.msg("not-running")); return true; }
            if (a.length < 2) { err(s, "/varo setday <day>"); return true; }
            try { p.game.setDay(Integer.parseInt(a[1])); } catch (NumberFormatException e) { err(s, "Not a number: " + a[1]); return true; }
            s.sendMessage(p.msg("day-set", "day", d.day));
        } else if (sub.equals("status")) {
            status(s);
        } else if (sub.equals("reload")) {
            p.reloadConfig();
            p.boards.bump();
            s.sendMessage(p.msg("admin-done"));
        } else if (sub.equals("revive")) {
            if (a.length < 2) { err(s, "/varo revive <player>"); return true; }
            UUID id = uuid(s, a[1]);
            if (id == null) return true;
            s.sendMessage(p.game.revive(id) ? p.msg("revive-ok", "player", a[1]) : p.msg("revive-not-dead", "player", a[1]));
        } else if (sub.equals("time")) {
            time(s, a);
        } else if (sub.equals("team")) {
            team(s, a);
        } else if (sub.equals("border")) {
            int from = Math.max(1, d.day);
            for (int i = from; i < from + 8; i++) {
                s.sendMessage(p.msg("new-day", "day", i, "size", (int) p.border.sizeForDay(i), "next", (int) p.border.sizeForDay(i + 1)));
            }
        } else {
            help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("\u00a76/varo \u00a77start [sec] | scatter | abort | reset confirm [all] | nextday | setday <n> | status | reload");
        s.sendMessage("\u00a76/varo \u00a77revive <player> | time <get|add|set> <player> [min] | border");
        s.sendMessage("\u00a76/varo team \u00a77create <name> <players...> | delete <name> | add <name> <player> | remove <player> | list");
        s.sendMessage("\u00a76/strike /strikes /unstrike \u00a77- strike system");
    }

    private void status(CommandSender s) {
        DataStore d = p.data;
        s.sendMessage("\u00a76State: \u00a7f" + d.state + " \u00a76Day: \u00a7f" + d.day + " \u00a76Alive: \u00a7f" + p.game.aliveCount()
                + " players / " + p.game.aliveTeamCount() + " teams \u00a76PvP: \u00a7f" + p.game.isPvpEnabled());
        s.sendMessage("\u00a76Border: \u00a7f" + (int) p.border.currentSize() + " \u00a76(next " + (int) p.border.nextSize()
                + ")  Reset in: \u00a7f" + p.game.resetText());
    }

    private void time(CommandSender s, String[] a) {
        if (a.length < 3) { err(s, "/varo time <get|add|set> <player> [minutes]"); return; }
        UUID id = uuid(s, a[2]);
        if (id == null) return;
        String op = a[1].toLowerCase();
        if (!op.equals("get")) {
            if (a.length < 4) { err(s, "Missing minutes."); return; }
            int sec;
            try { sec = (int) Math.round(Double.parseDouble(a[3]) * 60); } catch (NumberFormatException e) { err(s, "Not a number: " + a[3]); return; }
            int used = p.game.used(id);
            if (op.equals("add")) p.data.used.put(id, used - sec);                  // add remaining time
            else if (op.equals("set")) p.data.used.put(id, p.game.limitSeconds() - sec);   // set remaining time
            else { err(s, "Unknown operation " + op); return; }
        }
        s.sendMessage(p.msg("time-set", "player", a[2], "time", Util.fmt(p.game.remaining(id))));
    }

    private void team(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        String op = a[1].toLowerCase();
        TeamManager tm = p.teams;
        if (op.equals("list")) {
            for (VaroTeam t : p.data.teams.values())
                s.sendMessage(p.msg("team-list-entry", "team", t.name, "size", t.members.size(), "members", tm.memberNames(t)));
        } else if (op.equals("create")) {
            if (a.length < 3) { err(s, "/varo team create <name> <players...>"); return; }
            if (tm.byName(a[2]) != null) { s.sendMessage(p.msg("team-exists")); return; }
            VaroTeam t = tm.create(a[2], null);
            for (int i = 3; i < a.length; i++) {
                UUID id = uuid(s, a[i]);
                if (id == null) continue;
                tm.remove(id);
                tm.add(t, id);
                p.data.names.put(id, a[i]);
            }
            s.sendMessage(p.msg("team-created", "team", t.name));
        } else if (op.equals("delete")) {
            VaroTeam t = a.length > 2 ? tm.byName(a[2]) : null;
            if (t == null) { s.sendMessage(p.msg("team-not-found")); return; }
            tm.delete(t);
            s.sendMessage(p.msg("admin-done"));
        } else if (op.equals("add")) {
            VaroTeam t = a.length > 3 ? tm.byName(a[2]) : null;
            if (t == null) { s.sendMessage(p.msg("team-not-found")); return; }
            UUID id = uuid(s, a[3]);
            if (id == null) return;
            tm.remove(id);
            VaroTeam again = tm.byName(a[2]);   // may have been deleted if it was his own empty team
            if (again == null) again = tm.create(a[2], null);
            tm.add(again, id);
            s.sendMessage(p.msg("admin-done"));
        } else if (op.equals("remove")) {
            UUID id = a.length > 2 ? uuid(s, a[2]) : null;
            if (id == null) return;
            tm.remove(id);
            s.sendMessage(p.msg("admin-done"));
        } else {
            help(s);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (!s.hasPermission("varo.admin")) return new ArrayList<String>();
        if (a.length == 1) {
            List<String> out = new ArrayList<String>();
            for (String x : SUBS) if (x.startsWith(a[0].toLowerCase())) out.add(x);
            return out;
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("revive")) return null;   // default: player names
        return new ArrayList<String>();
    }
}
