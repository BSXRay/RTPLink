package bsx.de.rtplink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HomeManager {

    private final RTPLink plugin;
    private final Path homesFile;
    private final Map<String, Map<String, HomeLocation>> playerHomes = new ConcurrentHashMap<>();

    public HomeManager(RTPLink plugin) {
        this.plugin = plugin;
        this.homesFile = plugin.getDataDirectory().resolve("homes.yml");
        loadHomes();
    }

    public void setHome(String playerName, String homeName, String serverName, String world, double x, double y, double z, float yaw, float pitch) {
        playerName = playerName.toLowerCase();
        homeName = homeName.toLowerCase();

        playerHomes.computeIfAbsent(playerName, k -> new ConcurrentHashMap<>());

        HomeLocation home = new HomeLocation();
        home.serverName = serverName;
        home.world = world;
        home.x = x;
        home.y = y;
        home.z = z;
        home.yaw = yaw;
        home.pitch = pitch;

        playerHomes.get(playerName).put(homeName, home);
        saveHomes();
        plugin.debugLogDirect("Set home '%s' for player '%s' at %s,%s,%s on %s", homeName, playerName, x, y, z, serverName);
    }

    public HomeLocation getHome(String playerName, String homeName) {
        playerName = playerName.toLowerCase();
        homeName = homeName.toLowerCase();

        Map<String, HomeLocation> homes = playerHomes.get(playerName);
        if (homes == null) {
            return null;
        }
        return homes.get(homeName);
    }

    public boolean hasHome(String playerName, String homeName) {
        return getHome(playerName, homeName) != null;
    }

    public boolean deleteHome(String playerName, String homeName) {
        playerName = playerName.toLowerCase();
        homeName = homeName.toLowerCase();

        Map<String, HomeLocation> homes = playerHomes.get(playerName);
        if (homes == null) {
            return false;
        }

        HomeLocation removed = homes.remove(homeName);
        if (removed != null) {
            saveHomes();
            plugin.debugLogDirect("Deleted home '%s' for player '%s'", homeName, playerName);
            return true;
        }
        return false;
    }

    public Map<String, HomeLocation> getPlayerHomes(String playerName) {
        return playerHomes.getOrDefault(playerName.toLowerCase(), Collections.emptyMap());
    }

    public Set<String> getHomeNames(String playerName) {
        Map<String, HomeLocation> homes = playerHomes.get(playerName.toLowerCase());
        if (homes == null) {
            return Collections.emptySet();
        }
        return homes.keySet();
    }

    private void loadHomes() {
        if (!Files.exists(homesFile)) {
            return;
        }

        try {
            String content = Files.readString(homesFile);
            parseHomes(content);
            plugin.debugLogDirect("Loaded %d player home configurations", playerHomes.size());
        } catch (Exception e) {
            plugin.getLogger().error("Failed to load homes: " + e.getMessage());
        }
    }

    private void parseHomes(String content) {
        playerHomes.clear();

        String currentPlayer = null;
        Map<String, HomeLocation> currentHomes = null;
        String currentHome = null;
        HomeLocation home = null;

        for (String line : content.split("\n")) {
            line = line.trim();

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.contains(":") && !line.startsWith("  ")) {
                currentPlayer = line.substring(0, line.indexOf(':')).trim();
                currentHomes = new ConcurrentHashMap<>();
                playerHomes.put(currentPlayer, currentHomes);
                currentHome = null;
                home = null;
            } else if (line.startsWith("  ") && line.contains(":")) {
                String key = line.substring(2).trim();
                int colonIndex = key.indexOf(':');
                if (colonIndex > 0) {
                    String homeName = key.substring(0, colonIndex).trim();
                    if (!homeName.equals("homes")) {
                        currentHome = homeName;
                        home = new HomeLocation();
                        currentHomes.put(currentHome, home);
                    }
                }
            } else if (home != null && currentHome != null && line.startsWith("    ")) {
                String key = line.substring(4).trim();
                int colonIndex = key.indexOf(':');
                if (colonIndex > 0) {
                    String propName = key.substring(0, colonIndex).trim();
                    String value = key.substring(colonIndex + 1).trim();

                    switch (propName) {
                        case "server" -> home.serverName = value;
                        case "world" -> home.world = value;
                        case "x" -> home.x = Double.parseDouble(value);
                        case "y" -> home.y = Double.parseDouble(value);
                        case "z" -> home.z = Double.parseDouble(value);
                        case "yaw" -> home.yaw = Float.parseFloat(value);
                        case "pitch" -> home.pitch = Float.parseFloat(value);
                    }
                }
            }
        }
    }

    public synchronized void saveHomes() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# RTPLink Homes Configuration\n");
            sb.append("# Auto-generated - home entries are saved when players use /sethome\n\n");

            for (Map.Entry<String, Map<String, HomeLocation>> playerEntry : playerHomes.entrySet()) {
                if (playerEntry.getValue().isEmpty()) {
                    continue;
                }

                sb.append(playerEntry.getKey()).append(":\n");

                for (Map.Entry<String, HomeLocation> homeEntry : playerEntry.getValue().entrySet()) {
                    HomeLocation homeLoc = homeEntry.getValue();
                    sb.append("  ").append(homeEntry.getKey()).append(":\n");
                    sb.append("    server: \"").append(homeLoc.serverName).append("\"\n");
                    sb.append("    world: \"").append(homeLoc.world).append("\"\n");
                    sb.append("    x: ").append(homeLoc.x).append("\n");
                    sb.append("    y: ").append(homeLoc.y).append("\n");
                    sb.append("    z: ").append(homeLoc.z).append("\n");
                    sb.append("    yaw: ").append(homeLoc.yaw).append("\n");
                    sb.append("    pitch: ").append(homeLoc.pitch).append("\n");
                }
                sb.append("\n");
            }

            Files.writeString(homesFile, sb.toString());
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save homes: " + e.getMessage());
        }
    }

    public static class HomeLocation {
        public String serverName;
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
    }
}
