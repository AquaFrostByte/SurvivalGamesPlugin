package dev.varo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PlaytimeCommand implements CommandExecutor {
    private final VaroPlugin p;

    public PlaytimeCommand(VaroPlugin p) { this.p = p; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player)) { s.sendMessage(p.msg("player-only")); return true; }
        Player pl = (Player) s;
        if (p.data.state != GameState.RUNNING || !p.game.isActive(pl)) { pl.sendMessage(p.msg("not-running")); return true; }
        pl.sendMessage(p.msg("time-info", "time", Util.fmt(p.game.remaining(pl.getUniqueId())),
                "day", p.data.day, "reset", p.game.resetText()));
        return true;
    }
}
