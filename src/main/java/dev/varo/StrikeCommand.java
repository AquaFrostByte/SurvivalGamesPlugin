package dev.varo;

import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class StrikeCommand implements CommandExecutor {
    private final VaroPlugin p;

    public StrikeCommand(VaroPlugin p) { this.p = p; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("strikes")) {
            String target;
            if (a.length > 0) {
                if (!s.hasPermission("varo.strike") && !(s instanceof Player && ((Player) s).getName().equalsIgnoreCase(a[0]))) {
                    s.sendMessage(p.msg("no-permission")); return true;
                }
                target = a[0];
            } else if (s instanceof Player) target = s.getName();
            else { s.sendMessage(p.msg("usage", "usage", "/strikes <player>")); return true; }
            UUID id = Util.findUuid(p, target);
            if (id == null) { s.sendMessage(p.msg("unknown-player", "player", target)); return true; }
            List<DataStore.Strike> l = p.strikes.list(id);
            s.sendMessage(p.msg("strike-list-header", "player", target));
            if (l.isEmpty()) s.sendMessage(p.msg("strike-none"));
            for (int i = 0; i < l.size(); i++) {
                s.sendMessage(p.msg("strike-list-entry", "index", i + 1, "reason", l.get(i).reason, "by", l.get(i).by));
            }
            return true;
        }

        if (!s.hasPermission("varo.strike")) { s.sendMessage(p.msg("no-permission")); return true; }

        if (name.equals("strike")) {
            if (a.length < 2) { s.sendMessage(p.msg("usage", "usage", "/strike <player> <reason...>")); return true; }
            UUID id = Util.findUuid(p, a[0]);
            if (id == null) { s.sendMessage(p.msg("unknown-player", "player", a[0])); return true; }
            p.strikes.add(id, Util.nameOf(p, id), s.getName(), Util.join(a, 1));
        } else {   // unstrike
            if (a.length < 1) { s.sendMessage(p.msg("usage", "usage", "/unstrike <player> [index]")); return true; }
            UUID id = Util.findUuid(p, a[0]);
            if (id == null) { s.sendMessage(p.msg("unknown-player", "player", a[0])); return true; }
            int idx = 0;
            if (a.length > 1) { try { idx = Integer.parseInt(a[1]); } catch (NumberFormatException e) { idx = 0; } }
            s.sendMessage(p.strikes.remove(id, idx) ? p.msg("strike-removed", "player", a[0]) : p.msg("strike-none"));
        }
        return true;
    }
}
