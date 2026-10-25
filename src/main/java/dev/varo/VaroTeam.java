package dev.varo;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.ChatColor;

public class VaroTeam {
    public final String name;
    public ChatColor color = ChatColor.WHITE;
    public final Set<UUID> members = new LinkedHashSet<UUID>();

    public VaroTeam(String name) { this.name = name; }

    /** The first member is the leader. */
    public UUID leader() { return members.isEmpty() ? null : members.iterator().next(); }
}
