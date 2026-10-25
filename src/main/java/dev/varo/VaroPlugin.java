package dev.varo;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class VaroPlugin extends JavaPlugin {
    public DataStore data;
    public TeamManager teams;
    public GameManager game;
    public BorderManager border;
    public BoardManager boards;
    public CombatManager combat;
    public StrikeManager strikes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new DataStore(this);
        data.load();
        teams = new TeamManager(this);
        border = new BorderManager(this);
        boards = new BoardManager(this);
        combat = new CombatManager(this);
        strikes = new StrikeManager(this);
        game = new GameManager(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new GameListener(this), this);   // must be first (cancels before combat tagging)
        pm.registerEvents(new RuleListener(this), this);
        pm.registerEvents(new CombatListener(this), this);

        VaroCommand vc = new VaroCommand(this);
        getCommand("varo").setExecutor(vc);
        getCommand("varo").setTabCompleter(vc);
        TeamCommand tc = new TeamCommand(this);
        getCommand("team").setExecutor(tc);
        getCommand("tc").setExecutor(tc);
        StrikeCommand sc = new StrikeCommand(this);
        getCommand("strike").setExecutor(sc);
        getCommand("strikes").setExecutor(sc);
        getCommand("unstrike").setExecutor(sc);
        getCommand("playtime").setExecutor(new PlaytimeCommand(this));

        game.startTasks();
        getLogger().info("VARO enabled (state: " + data.state + ", day " + data.day + ")");
    }

    @Override
    public void onDisable() {
        if (combat != null) combat.removeAllNpcs();
        if (game != null) game.removeAllCountdown();
        if (data != null) data.save();
    }

    /** Message with prefix. Placeholders: key/value pairs, e.g. msg("x","player",name). */
    public String msg(String key, Object... kv) {
        return Util.color(getConfig().getString("general.prefix", "") + text(key, kv));
    }

    /** Message without prefix (kick screens etc.). */
    public String raw(String key, Object... kv) {
        return Util.color(text(key, kv));
    }

    private String text(String key, Object... kv) {
        String s = getConfig().getString("messages." + key);
        if (s == null) s = "&cMissing message: " + key;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        }
        return s;
    }
}
