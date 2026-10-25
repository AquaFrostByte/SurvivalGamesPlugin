# VaroPlugin (Minecraft 1.7.10, Bukkit/Spigot)

Note THIS PROJECT IS MAINLY MADE BY AI!!!
(I didnt had time to make it by hand so the project is ai! please keep that in mind. Its made with Claude Sonnet 5.5)

## Build
1. Put your server jar at `lib/spigot-1.7.10.jar` (CraftBukkit/Spigot/Paper 1.7.10 all work).
2. Use **JDK 8** and run `mvn package` -> `target/VaroPlugin.jar` -> drop into `plugins/`.
   (No Maven? `javac -source 1.7 -target 1.7 -cp lib/spigot-1.7.10.jar -d out $(find src/main/java -name '*.java')`,
   copy `src/main/resources/*` into `out/`, then `jar cf VaroPlugin.jar -C out .`)

## Typical flow
1. Edit `plugins/VaroPlugin/config.yml` (everything is configurable, messages too). `/varo reload` applies it.
2. Players form teams: `/team create <name>`, `/team invite <p>`, `/team accept`. Staff get `varo.bypass`.
3. `/varo scatter` (teleports teams, builds domes, freezes) then `/varo start [seconds]` (countdown, then release).
4. Strikes: `/strike <player> <reason>`, `/strikes [player]`, `/unstrike <player> [index]`.
5. Admin: `/varo status | nextday | setday <n> | revive <p> | time <get|add|set> <p> [min] | team ... | border | reset confirm [all]`.

## 1.7.10 limitations (and how they are handled)
* No WorldBorder API -> custom square border (wall + damage + particles), shrinks per day, sidebar shows "safe tomorrow".
* No spectator gamemode -> `death.mode: SPECTATOR` is emulated (creative flight, invisible, no interaction). Default is BAN.
* No NPC library -> combat-log NPC is a frozen, named villager carrying the player's loot; killing it eliminates the player.
* No anvil-prepare event -> over-cap enchants are blocked when taking the anvil result. Level-II potions are blocked at
  brewing (glowstone) and at use (drink/splash). Existing loot items are not rewritten.
* Scoreboard lines up to 32 chars (prefix/suffix trick). Keep config.yml ASCII, use \uXXXX escapes.


