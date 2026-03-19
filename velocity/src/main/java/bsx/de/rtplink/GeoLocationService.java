package bsx.de.rtplink;

import com.velocitypowered.api.proxy.Player;

import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeoLocationService {

    private final RTPLink plugin;
    private final Map<String, String> playerLocations = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private final long cacheDuration = 24 * 60 * 60 * 1000L;

    public GeoLocationService(RTPLink plugin) {
        this.plugin = plugin;
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
        plugin.debugLogDirect("Set location for {} to {}", key, location);
    }

    public void removePlayerLocation(Player player) {
        String key = player.getUsername().toLowerCase();
        playerLocations.remove(key);
        cacheTimestamps.remove(key);
    }

    public void detectAndSetLocation(Player player) {
        String existing = getPlayerLocation(player);
        if (existing != null) {
            return;
        }

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                String ip = getPlayerIP(player);
                if (ip == null) {
                    plugin.debugLogDirect("Could not get IP for player {}", player.getUsername());
                    return;
                }

                String country = lookupCountry(ip);
                if (country != null) {
                    setPlayerLocation(player, country);
                    plugin.debugLogDirect("Detected location for {}: {}", player.getUsername(), country);
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
