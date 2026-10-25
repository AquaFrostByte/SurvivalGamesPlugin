package dev.varo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Per-player sidebar. Lines are drawn with the "team prefix/suffix" trick so that
 * they can be up to 32 chars long, do not flicker and do not leak entries.
 */
public class BoardManager {
    private final VaroPlugin p;
    private final Map<UUID, Sidebar> boards = new HashMap<UUID, Sidebar>();
    private int teamsVersion = 0;

    public BoardManager(VaroPlugin p) { this.p = p; }

    /** Call when teams or the scoreboard config changed. */
    public void bump() { teamsVersion++; }

    public void remove(Player pl) {
        boards.remove(pl.getUniqueId());
        pl.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void updateAll() {
        for (Player pl : Bukkit.getOnlinePlayers()) update(pl);
    }

    public void update(Player pl) {
        if (!p.getConfig().getBoolean("scoreboard.enabled", true)) {
            if (boards.containsKey(pl.getUniqueId())) remove(pl);
            return;
        }
        Sidebar sb = boards.get(pl.getUniqueId());
        if (sb == null) {
            sb = new Sidebar();
            boards.put(pl.getUniqueId(), sb);
            pl.setScoreboard(sb.board);
        }
        if (sb.version != teamsVersion) {
            sb.rebuild();
            sb.version = teamsVersion;
        }
        sb.setLines(buildLines(pl));
    }

    private List<String> buildLines(Player pl) {
        DataStore d = p.data;
        UUID id = pl.getUniqueId();
        GameState st = d.state;
        boolean active = p.game.isActive(pl);
        Map<String, String> v = new HashMap<String, String>();

        v.put("day", d.day > 0 ? String.valueOf(d.day) : "-");
        v.put("time", (st == GameState.RUNNING && active) ? Util.fmt(p.game.remaining(id)) : "-");
        v.put("reset", p.game.resetText());
        v.put("border", p.border.enabled() ? String.valueOf((int) p.border.currentSize()) : "-");
        v.put("nextborder", p.border.enabled() ? String.valueOf((int) p.border.nextSize()) : "-");
        v.put("players", String.valueOf(p.game.aliveCount()));
        v.put("teams", String.valueOf(p.game.aliveTeamCount()));

        String safe = "";
        if (p.border.enabled() && st == GameState.RUNNING && active) {
            double o = p.border.overshoot(pl.getLocation(), p.border.nextSize());
            if (o <= 0) safe = p.getConfig().getString("scoreboard.safe-text", "");
            else safe = p.getConfig().getString("scoreboard.unsafe-text", "").replace("%dist%", String.valueOf((int) Math.ceil(o)));
        }
        v.put("safe", safe);

        String grace = "";
        if (st == GameState.RUNNING) {
            if (p.game.isPvpEnabled()) grace = p.getConfig().getString("scoreboard.grace-over", "");
            else grace = p.getConfig().getString("scoreboard.grace-active", "").replace("%left%", p.game.graceLeftText());
        }
        v.put("grace", grace);

        String combat = "";
        if (p.combat.isTagged(pl)) combat = p.getConfig().getString("scoreboard.combat-text", "").replace("%time%", String.valueOf(p.combat.left(pl)));
        v.put("combat", combat);

        StringBuilder mates = new StringBuilder();
        VaroTeam t = p.teams.of(id);
        if (t != null && st != GameState.SETUP) {
            for (UUID mid : t.members) {
                if (mid.equals(id)) continue;
                String name = Util.nameOf(p, mid);
                String line;
                Player m = Bukkit.getPlayer(mid);
                if (p.data.dead.containsKey(mid)) line = p.getConfig().getString("scoreboard.mate-dead", "").replace("%name%", name);
                else if (m == null) line = p.getConfig().getString("scoreboard.mate-offline", "").replace("%name%", name);
                else line = p.getConfig().getString("scoreboard.mate-line", "").replace("%name%", name)
                        .replace("%health%", String.format("%.1f", m.getHealth()));
                if (mates.length() > 0) mates.append('\n');
                mates.append(line);
            }
        }
        v.put("mates", mates.toString());

        List<String> out = new ArrayList<String>();
        for (String raw : p.getConfig().getStringList("scoreboard.lines")) {
            if (raw.isEmpty()) { out.add(""); continue; }
            String s = raw;
            for (Map.Entry<String, String> e : v.entrySet()) s = s.replace("%" + e.getKey() + "%", e.getValue());
            if (s.trim().isEmpty()) continue;
            for (String part : s.split("\n")) out.add(Util.color(part));
        }
        return out;
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    class Sidebar {
        final Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        final Objective obj;
        int version = -1;
        final String[] cache = new String[15];
        int shown = 0;

        Sidebar() {
            obj = board.registerNewObjective("varo", "dummy");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            if (p.getConfig().getBoolean("scoreboard.below-name-health", true)) {
                Objective h = board.registerNewObjective("vhp", "health");
                h.setDisplaySlot(DisplaySlot.BELOW_NAME);
                h.setDisplayName(cut(Util.color(p.getConfig().getString("scoreboard.below-name-label", "&c\u2764")), 32));
            }
        }

        /** title + coloured name tags */
        void rebuild() {
            obj.setDisplayName(cut(Util.color(p.getConfig().getString("scoreboard.title", "&6VARO")), 32));
            for (Team t : new ArrayList<Team>(board.getTeams())) if (t.getName().startsWith("nt")) t.unregister();
            if (!p.getConfig().getBoolean("teams.nametag-colors", true)) return;
            int i = 0;
            for (VaroTeam vt : p.data.teams.values()) {
                Team t = board.registerNewTeam("nt" + i++);
                t.setPrefix(vt.color.toString());
                t.setCanSeeFriendlyInvisibles(true);
                for (UUID id : vt.members) t.addPlayer(Bukkit.getOfflinePlayer(Util.nameOf(p, id)));
            }
        }

        private String entry(int i) { return "\u00a7" + Integer.toHexString(i) + "\u00a7r"; }

        void setLines(List<String> lines) {
            int n = Math.min(15, lines.size());
            for (int i = 0; i < n; i++) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(entry(i));
                Team t = board.getTeam("ln" + i);
                if (t == null) {
                    t = board.registerNewTeam("ln" + i);
                    t.addPlayer(op);
                    cache[i] = null;
                }
                String line = lines.get(i);
                if (!line.equals(cache[i])) {
                    String[] ps = split(line);
                    t.setPrefix(ps[0]);
                    t.setSuffix(ps[1]);
                    cache[i] = line;
                }
                obj.getScore(op).setScore(n - i);
            }
            for (int i = n; i < shown; i++) board.resetScores(Bukkit.getOfflinePlayer(entry(i)));
            shown = n;
        }

        private String[] split(String line) {
            if (line.length() <= 16) return new String[] { line, "" };
            String pre = line.substring(0, 16);
            String rest = line.substring(16);
            if (pre.endsWith("\u00a7")) { pre = pre.substring(0, 15); rest = "\u00a7" + rest; }
            rest = ChatColor.getLastColors(pre) + rest;
            if (rest.length() > 16) rest = rest.substring(0, 16);
            if (rest.endsWith("\u00a7")) rest = rest.substring(0, rest.length() - 1);
            return new String[] { pre, rest };
        }
    }
}
