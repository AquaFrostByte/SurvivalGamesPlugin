package dev.varo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class TeamManager {
    public static class Invite {
        public final String team;
        public final long expires;
        public Invite(String team, long expires) { this.team = team; this.expires = expires; }
    }

    private final VaroPlugin p;
    public final Map<UUID, Invite> invites = new HashMap<UUID, Invite>();

    public TeamManager(VaroPlugin p) { this.p = p; }

    public VaroTeam byName(String n) { return p.data.teams.get(n.toLowerCase()); }

    public VaroTeam of(UUID id) {
        for (VaroTeam t : p.data.teams.values()) if (t.members.contains(id)) return t;
        return null;
    }

    public boolean sameTeam(UUID a, UUID b) {
        VaroTeam t = of(a);
        return t != null && t.members.contains(b);
    }

    public VaroTeam create(String name, UUID leader) {
        VaroTeam t = new VaroTeam(name);
        t.color = nextColor();
        if (leader != null) t.members.add(leader);
        p.data.teams.put(name.toLowerCase(), t);
        changed();
        return t;
    }

    public VaroTeam createSolo(Player pl) {
        String name = pl.getName();
        while (byName(name) != null) name = name + "_";
        return create(name, pl.getUniqueId());
    }

    public void delete(VaroTeam t) {
        p.data.teams.remove(t.name.toLowerCase());
        changed();
    }

    public void add(VaroTeam t, UUID id) {
        t.members.add(id);
        changed();
    }

    /** Removes a player from his team; deletes the team when it gets empty. */
    public void remove(UUID id) {
        VaroTeam t = of(id);
        if (t == null) return;
        t.members.remove(id);
        if (t.members.isEmpty()) p.data.teams.remove(t.name.toLowerCase());
        changed();
    }

    public void changed() {
        p.boards.bump();
        p.data.save();
    }

    private ChatColor nextColor() {
        List<String> names = p.getConfig().getStringList("teams.colors");
        List<ChatColor> cols = new ArrayList<ChatColor>();
        for (String n : names) {
            try { cols.add(ChatColor.valueOf(n.toUpperCase())); } catch (IllegalArgumentException ignored) { }
        }
        if (cols.isEmpty()) cols.add(ChatColor.WHITE);
        for (ChatColor c : cols) {
            boolean taken = false;
            for (VaroTeam t : p.data.teams.values()) if (t.color == c) { taken = true; break; }
            if (!taken) return c;
        }
        return cols.get(p.data.teams.size() % cols.size());
    }

    public void broadcast(VaroTeam t, String message) {
        for (UUID id : t.members) {
            Player o = Bukkit.getPlayer(id);
            if (o != null) o.sendMessage(message);
        }
    }

    public String memberNames(VaroTeam t) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : t.members) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Util.nameOf(p, id));
        }
        return sb.toString();
    }

    public void chat(Player from, String text) {
        VaroTeam t = of(from.getUniqueId());
        if (t == null) { from.sendMessage(p.msg("team-none")); return; }
        String fmt = p.getConfig().getString("teams.chat-format", "[%team%] %player%: %message%");
        String line = Util.color(fmt.replace("%team%", t.name).replace("%player%", from.getName())).replace("%message%", text);
        broadcast(t, line);
    }
}
