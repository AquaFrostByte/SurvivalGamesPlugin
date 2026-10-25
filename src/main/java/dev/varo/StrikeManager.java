package dev.varo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class StrikeManager {
    private final VaroPlugin p;

    public StrikeManager(VaroPlugin p) { this.p = p; }

    public List<DataStore.Strike> list(UUID id) {
        List<DataStore.Strike> l = p.data.strikes.get(id);
        if (l == null) { l = new ArrayList<DataStore.Strike>(); p.data.strikes.put(id, l); }
        return l;
    }

    public int add(UUID id, String name, String by, String reason) {
        DataStore.Strike s = new DataStore.Strike();
        s.by = by; s.reason = reason.replace(";", ","); s.time = System.currentTimeMillis();
        List<DataStore.Strike> l = list(id);
        l.add(s);
        int count = l.size();
        int max = p.getConfig().getInt("strikes.max", 3);

        Bukkit.broadcastMessage(p.msg("strike-added", "player", name, "count", count, "max", max, "reason", reason));
        int penalty = p.getConfig().getInt("strikes.time-penalty-minutes", 0);
        if (penalty > 0) {
            Integer u = p.data.used.get(id);
            p.data.used.put(id, (u == null ? 0 : u) + penalty * 60);
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.sendMessage(p.msg("strike-player", "reason", reason, "count", count, "max", max));

        if (max > 0 && count >= max && p.data.state == GameState.RUNNING && p.data.participants.contains(id)
                && !p.data.dead.containsKey(id)) {
            p.game.eliminate(id, name, p.raw("strike-eliminated", "player", name), online != null ? online.getLocation() : null);
            if (online != null) p.game.applyElimination(online);
        }
        p.data.save();
        return count;
    }

    /** Removes strike #index (1-based) or the last one when index <= 0. */
    public boolean remove(UUID id, int index) {
        List<DataStore.Strike> l = list(id);
        if (l.isEmpty()) return false;
        int i = index <= 0 ? l.size() - 1 : index - 1;
        if (i < 0 || i >= l.size()) return false;
        l.remove(i);
        p.data.save();
        return true;
    }
}
