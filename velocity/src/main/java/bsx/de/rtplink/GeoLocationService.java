package bsx.de.rtplink;

import com.velocitypowered.api.proxy.Player;

import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeoLocationService {

    private final RTPLink plugin;
    private final Map<String, String> playerLocations = new ConcurrentHashMap<>();
    private final Map<String, String> originalIpLocations = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private final long cacheDuration = 24 * 60 * 60 * 1000L;
    private final Path saveFile;

    public GeoLocationService(RTPLink plugin) {
        this.plugin = plugin;
        this.saveFile = plugin.getDataDirectory().resolve("player-locations.json");
        loadLocations();
    }

    private void loadLocations() {
        try {
            if (Files.exists(saveFile)) {
                String data = Files.readString(saveFile);
                if (data == null || data.isEmpty()) {
                    return;
                }
                
                String content = decrypt(data);
                if (content == null) {
                    content = data;
                }
                
                if (content != null && content.contains(":")) {
                    playerLocations.clear();
                    originalIpLocations.clear();
                    String[] entries = content.split("\n");
                    for (String entry : entries) {
                        if (entry.contains(":")) {
                            int colonIdx = entry.indexOf(":");
                            String player = entry.substring(0, colonIdx).trim();
                            String rest = entry.substring(colonIdx + 1).trim();
                            
                            if (player.startsWith("original_")) {
                                String actualPlayer = player.substring(8);
                                originalIpLocations.put(actualPlayer.toLowerCase(), rest.toUpperCase());
                            } else if (!player.isEmpty() && !player.equals("cache") && rest.contains("|")) {
                                String[] parts = rest.split("\\|");
                                String location = parts[0].trim();
                                String originalIp = parts.length > 1 ? parts[1].trim() : location;
                                playerLocations.put(player.toLowerCase(), location.toUpperCase());
                                originalIpLocations.put(player.toLowerCase(), originalIp.toUpperCase());
                            } else if (!player.isEmpty() && !player.equals("cache")) {
                                playerLocations.put(player.toLowerCase(), rest.toUpperCase());
                            }
                        }
                    }
                    plugin.debugLogDirect("Loaded {} player locations from disk", playerLocations.size());
                    
                    if (!data.equals(content)) {
                        saveLocations();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to load player locations: " + e.getMessage());
        }
    }

    private String encrypt(String data) {
        byte[] key = "RTPLinkSecure2024!".getBytes();
        byte[] bytes = data.getBytes();
        byte[] encrypted = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            encrypted[i] = (byte) (bytes[i] ^ key[i % key.length]);
        }
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decrypt(String data) {
        try {
            byte[] key = "RTPLinkSecure2024!".getBytes();
            byte[] decoded = Base64.getDecoder().decode(data);
            byte[] decrypted = new byte[decoded.length];
            for (int i = 0; i < decoded.length; i++) {
                decrypted[i] = (byte) (decoded[i] ^ key[i % key.length]);
            }
            return new String(decrypted);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveLocations() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : playerLocations.entrySet()) {
                if (!entry.getKey().startsWith("cache_")) {
                    String originalIp = originalIpLocations.get(entry.getKey());
                    if (originalIp != null) {
                        sb.append(entry.getKey()).append(":").append(entry.getValue()).append("|").append(originalIp).append("\n");
                    } else {
                        sb.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
                    }
                }
            }
            Files.writeString(saveFile, encrypt(sb.toString()));
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to save player locations: " + e.getMessage());
        }
    }

    public String getPlayerLocation(Player player) {
        String key = player.getUsername().toLowerCase();
        if (playerLocations.containsKey(key)) {
            return playerLocations.get(key);
        }
        return null;
    }
    
    public String getPlayerLocationByName(String playerName) {
        String key = playerName.toLowerCase();
        if (playerLocations.containsKey(key)) {
            return playerLocations.get(key);
        }
        return null;
    }

    public void setPlayerLocation(Player player, String location) {
        String key = player.getUsername().toLowerCase();
        playerLocations.put(key, location.toUpperCase());
        saveLocations();
        plugin.debugLogDirect("Set location for {} to {}", key, location);
    }

    public void removePlayerLocation(Player player) {
        String key = player.getUsername().toLowerCase();
        playerLocations.remove(key);
        originalIpLocations.remove(key);
        cacheTimestamps.remove(key);
        saveLocations();
    }

    public void detectAndSetLocation(Player player) {
        if (!plugin.isGeoEnabled()) {
            return;
        }

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                String ip = getPlayerIP(player);
                if (ip == null) {
                    plugin.debugLogDirect("Could not get IP for player {}", player.getUsername());
                    return;
                }

                String realCountry = lookupCountry(ip);
                if (realCountry == null) {
                    plugin.debugLogDirect("Could not detect real location for {}", player.getUsername());
                    return;
                }

                String key = player.getUsername().toLowerCase();
                String existing = getPlayerLocation(player);
                String originalIpLoc = originalIpLocations.get(key);

                if (originalIpLoc == null) {
                    originalIpLocations.put(key, realCountry);
                    playerLocations.put(key, realCountry);
                    saveLocations();
                    plugin.debugLogDirect("First join for {}: Set initial location to {}", player.getUsername(), realCountry);
                } else if (!originalIpLoc.equalsIgnoreCase(realCountry)) {
                    originalIpLocations.put(key, realCountry);
                    playerLocations.put(key, realCountry);
                    saveLocations();
                    plugin.debugLogDirect("Player {} moved: Original IP location changed from {} to {}, updating location", 
                        player.getUsername(), originalIpLoc, realCountry);
                } else {
                    plugin.debugLogDirect("Original IP location unchanged for {}: {} (manual location kept)", 
                        player.getUsername(), existing);
                }
            } catch (Exception e) {
                plugin.debugLogDirect("Error detecting location for {}: {}", player.getUsername(), e.getMessage());
            }
        }).schedule();
    }

    private String getPlayerIP(Player player) {
        try {
            InetSocketAddress address = player.getRemoteAddress();
            if (address != null) {
                String ip = address.getAddress().getHostAddress();
                if (ip != null) {
                    if (ip.startsWith("/")) {
                        ip = ip.substring(1);
                    }
                    if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")) {
                        return null;
                    }
                    return ip;
                }
            }
        } catch (Exception e) {
            plugin.debugLogDirect("Error getting IP: {}", e.getMessage());
        }
        return null;
    }

    private String lookupCountry(String ip) {
        if (cacheTimestamps.containsKey(ip)) {
            if (System.currentTimeMillis() - cacheTimestamps.get(ip) < cacheDuration) {
                return playerLocations.get("cache_" + ip);
            }
        }

        try {
            URL url = new URL("http://ip-api.com/json/" + ip + "?fields=countryCode");
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            String response = reader.readLine();
            reader.close();

            if (response != null && response.contains("countryCode")) {
                int start = response.indexOf("\"countryCode\":\"") + 15;
                int end = response.indexOf("\"", start);
                if (start > 14 && end > start) {
                    String country = response.substring(start, end);
                    
                    playerLocations.put("cache_" + ip, country);
                    cacheTimestamps.put(ip, System.currentTimeMillis());
                    
                    return country;
                }
            }
        } catch (Exception e) {
            plugin.debugLogDirect("GeoIP lookup failed for {}: {}", ip, e.getMessage());
        }
        
        return null;
    }

    public boolean matchesLocation(String serverLocation, String playerLocation) {
        if (serverLocation == null || serverLocation.trim().isEmpty()) {
            return true;
        }
        if (playerLocation == null || playerLocation.trim().isEmpty()) {
            return false;
        }

        serverLocation = serverLocation.trim().toUpperCase();
        playerLocation = playerLocation.trim().toUpperCase();

        if (serverLocation.startsWith("!")) {
            String excluded = serverLocation.substring(1);
            return !playerLocation.equals(excluded);
        }

        if (serverLocation.contains(",")) {
            for (String loc : serverLocation.split(",")) {
                loc = loc.trim();
                if (loc.startsWith("!")) {
                    if (playerLocation.equals(loc.substring(1).trim())) {
                        return false;
                    }
                } else {
                    if (playerLocation.equals(loc)) {
                        return true;
                    }
                }
            }
            return false;
        }

        return playerLocation.equals(serverLocation);
    }
}
