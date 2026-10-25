package dev.varo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** All persistent match state (data.yml). */
public class DataStore {
    public static class DeathRecord { public String name; public String reason; public long time; }
    public static class Strike { public String by; public String reason; public long time; }

    private final VaroPlugin plugin;
    private final File file;

    public GameState state = GameState.SETUP;
    public int day;
    public long dayKey;
    public long startMillis;
    public long graceEnd;
    public final Set<UUID> participants = new LinkedHashSet<UUID>();
    public final Map<UUID, String> names = new HashMap<UUID, String>();
    public final Map<UUID, Integer> used = new HashMap<UUID, Integer>();
    public final Map<UUID, DeathRecord> dead = new LinkedHashMap<UUID, DeathRecord>();
    public final Set<UUID> pendingClear = new HashSet<UUID>();
    public final Map<UUID, List<Strike>> strikes = new HashMap<UUID, List<Strike>>();
    public final Map<String, VaroTeam> teams = new LinkedHashMap<String, VaroTeam>();
    public final List<String> domes = new ArrayList<String>();

    public DataStore(VaroPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        try { state = GameState.valueOf(y.getString("state", "SETUP")); } catch (IllegalArgumentException e) { state = GameState.SETUP; }
        day = y.getInt("day");
        dayKey = y.getLong("dayKey");
        startMillis = y.getLong("startMillis");
        graceEnd = y.getLong("graceEnd");
        for (String s : y.getStringList("participants")) { UUID u = uuid(s); if (u != null) participants.add(u); }
        for (String s : y.getStringList("pendingClear")) { UUID u = uuid(s); if (u != null) pendingClear.add(u); }
        domes.addAll(y.getStringList("domes"));

        ConfigurationSection sec = y.getConfigurationSection("names");
        if (sec != null) for (String k : sec.getKeys(false)) { UUID u = uuid(k); if (u != null) names.put(u, sec.getString(k)); }
        sec = y.getConfigurationSection("used");
        if (sec != null) for (String k : sec.getKeys(false)) { UUID u = uuid(k); if (u != null) used.put(u, sec.getInt(k)); }
        sec = y.getConfigurationSection("dead");
        if (sec != null) for (String k : sec.getKeys(false)) {
            UUID u = uuid(k);
            if (u == null) continue;
            DeathRecord r = new DeathRecord();
            r.name = sec.getString(k + ".name");
            r.reason = sec.getString(k + ".reason");
            r.time = sec.getLong(k + ".time");
            dead.put(u, r);
        }
        sec = y.getConfigurationSection("strikes");
        if (sec != null) for (String k : sec.getKeys(false)) {
            UUID u = uuid(k);
            if (u == null) continue;
            List<Strike> list = new ArrayList<Strike>();
            for (String line : sec.getStringList(k)) {
                String[] pa = line.split(";", 3);
                if (pa.length < 3) continue;
                Strike st = new Strike();
                try { st.time = Long.parseLong(pa[0]); } catch (NumberFormatException e) { st.time = 0; }
                st.by = pa[1];
                st.reason = pa[2];
                list.add(st);
            }
            strikes.put(u, list);
        }
        sec = y.getConfigurationSection("teams");
        if (sec != null) for (String k : sec.getKeys(false)) {
            VaroTeam t = new VaroTeam(sec.getString(k + ".name", k));
            try { t.color = ChatColor.valueOf(sec.getString(k + ".color", "WHITE")); } catch (IllegalArgumentException e) { t.color = ChatColor.WHITE; }
            for (String m : sec.getStringList(k + ".members")) { UUID u = uuid(m); if (u != null) t.members.add(u); }
            teams.put(k, t);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("state", state.name());
        y.set("day", day);
        y.set("dayKey", dayKey);
        y.set("startMillis", startMillis);
        y.set("graceEnd", graceEnd);
        List<String> l = new ArrayList<String>();
        for (UUID u : participants) l.add(u.toString());
        y.set("participants", l);
        l = new ArrayList<String>();
        for (UUID u : pendingClear) l.add(u.toString());
        y.set("pendingClear", l);
        y.set("domes", new ArrayList<String>(domes));
        for (Map.Entry<UUID, String> e : names.entrySet()) y.set("names." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, Integer> e : used.entrySet()) y.set("used." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, DeathRecord> e : dead.entrySet()) {
            String b = "dead." + e.getKey();
            y.set(b + ".name", e.getValue().name);
            y.set(b + ".reason", e.getValue().reason);
            y.set(b + ".time", e.getValue().time);
        }
        for (Map.Entry<UUID, List<Strike>> e : strikes.entrySet()) {
            List<String> lines = new ArrayList<String>();
            for (Strike s : e.getValue()) lines.add(s.time + ";" + s.by + ";" + s.reason);
            y.set("strikes." + e.getKey(), lines);
        }
        for (Map.Entry<String, VaroTeam> e : teams.entrySet()) {
            String b = "teams." + e.getKey();
            VaroTeam t = e.getValue();
            y.set(b + ".name", t.name);
            y.set(b + ".color", t.color.name());
            List<String> m = new ArrayList<String>();
            for (UUID u : t.members) m.add(u.toString());
            y.set(b + ".members", m);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    /** Wipes the match (optionally also the teams). */
    public void clearGame(boolean alsoTeams) {
        state = GameState.SETUP;
        day = 0; dayKey = 0; startMillis = 0; graceEnd = 0;
        participants.clear(); used.clear(); dead.clear(); pendingClear.clear(); strikes.clear(); domes.clear();
        if (alsoTeams) teams.clear();
    }
}
