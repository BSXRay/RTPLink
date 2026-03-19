package bsx.de.rtplink;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTPLinkHelper extends JavaPlugin {

    private static RTPLinkHelper instance;
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private String velocityHost = "127.0.0.1";
    private int velocityPort = 25577;
    private int reconnectDelay = 5;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean connected = false;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private String serverName;
    private ScheduledFuture<?> reconnectTask;
    private boolean debug = false;

    @Override
    public void onEnable() {
        instance = this;
        
        loadConfig();
        startConnection();
        
        getLogger().info("========================================");
        getLogger().info("   RTPLink Helper v1.21.11");
        getLogger().info("   Server: " + getServer().getName());
        getLogger().info("   Velocity: " + velocityHost + ":" + velocityPort);
        getLogger().info("   Reconnect delay: " + reconnectDelay + "s");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        connected = false;
        reconnecting.set(false);
        
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
        }
        
        scheduler.shutdown();
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}
        
        getLogger().info("RTPLink Helper disabled!");
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        
        velocityHost = getConfig().getString("velocity.host", "127.0.0.1");
        velocityPort = getConfig().getInt("velocity.port", 25577);
        reconnectDelay = getConfig().getInt("reconnect-delay", 5);
        serverName = getConfig().getString("server-name", getServer().getName());
        debug = getConfig().getBoolean("debug", false);
    }

    private void debugLog(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private void startConnection() {
        reconnecting.set(false);
        scheduler.execute(this::connectToVelocity);
    }

    private void scheduleReconnect() {
        if (reconnecting.compareAndSet(false, true)) {
            getLogger().info("Scheduling reconnect in " + reconnectDelay + " seconds...");
            reconnectTask = scheduler.schedule(() -> {
                reconnecting.set(false);
                startConnection();
            }, reconnectDelay, TimeUnit.SECONDS);
        }
    }

    private void connectToVelocity() {
        try {
            getLogger().info("Connecting to Velocity at " + velocityHost + ":" + velocityPort + "...");
            
            socket = new Socket(velocityHost, velocityPort);
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            writer.println("REGISTER:" + serverName);
            
            String response = reader.readLine();
            if ("REGISTERED".equals(response)) {
                connected = true;
                reconnecting.set(false);
                getLogger().info("Successfully connected to Velocity!");
                
                new Thread(this::listenForPackets).start();
            } else {
                getLogger().warning("Unexpected response from Velocity: " + response);
                socket.close();
                scheduleReconnect();
            }
        } catch (Exception e) {
            getLogger().warning("Failed to connect to Velocity: " + e.getMessage());
            closeSocket();
            scheduleReconnect();
        }
    }

    private void closeSocket() {
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}
        socket = null;
        writer = null;
        reader = null;
    }

    private void listenForPackets() {
        try {
            String line;
            while (connected && !Thread.currentThread().isInterrupted()) {
                try {
                    if (reader != null) {
                        line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        handleLine(line);
                    }
                } catch (java.net.SocketException e) {
                    getLogger().warning("Connection lost: " + e.getMessage());
                    break;
                } catch (java.io.IOException e) {
                    if (connected) {
                        getLogger().warning("IO Error: " + e.getMessage());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Listener error: " + e.getMessage());
        } finally {
            if (connected) {
                connected = false;
                closeSocket();
                scheduleReconnect();
            }
        }
    }

    private void handleLine(String line) {
        getLogger().info("Received message from Velocity: " + line);
        
        if (line.startsWith("RTP:")) {
            String dataStr = line.substring(4);
            byte[] rtpData = Base64.getDecoder().decode(dataStr);
            handleRtpPacket(rtpData);
        } else if (line.startsWith("HOME:")) {
            String dataStr = line.substring(5);
            byte[] homeData = Base64.getDecoder().decode(dataStr);
            handleRtpPacket(homeData);
        } else if (line.startsWith("SETHOME:")) {
            String dataStr = line.substring(8);
            byte[] sethomeData = Base64.getDecoder().decode(dataStr);
            handleRtpPacket(sethomeData);
        } else if (line.startsWith("TPA:")) {
            String dataStr = line.substring(4);
            byte[] tpaData = Base64.getDecoder().decode(dataStr);
            handleTpaPacket(tpaData);
        } else if (line.startsWith("GETLOC:")) {
            String data = line.substring(7);
            handleGetLoc(data);
        } else if (line.startsWith("TPATEL:")) {
            String data = line.substring(7);
            handleTpaTeleport(data);
        } else if (line.startsWith("MSG:")) {
            String data = line.substring(4);
            handleTextMessage(data);
        } else if (line.equals("RELOAD")) {
            getLogger().info("Received RELOAD command from Velocity");
            loadConfig();
            getLogger().info("Config reloaded!");
        } else if (line.startsWith("PONG:")) {
            getLogger().fine("Received PONG from Velocity");
        } else {
            getLogger().warning("Unknown message type: " + line);
        }
    }

    private void handleTextMessage(String data) {
        String[] parts = data.split(":", 3);
        if (parts.length >= 3) {
            String playerName = parts[0];
            String message = parts[1];
            String replacements = parts.length > 2 ? parts[2] : "";
            
            getServer().getScheduler().runTask(this, () -> {
                Player player = getServer().getPlayer(playerName);
                if (player != null && player.isOnline()) {
                    String formatted = message;
                    String[] replArray = replacements.split("\\|");
                    for (int i = 0; i < replArray.length; i++) {
                        formatted = formatted.replace("{" + i + "}", replArray[i]);
                    }
                    player.sendMessage(colorize(formatted));
                }
            });
        }
    }

    private void handleRtpPacket(byte[] message) {
        if (message.length < 1) return;
        
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(message);
            java.io.DataInputStream in = new java.io.DataInputStream(bais);
            
            byte packetType = in.readByte();
            
            if (packetType == 1) {
                int minX = in.readInt();
                int maxX = in.readInt();
                int minZ = in.readInt();
                int maxZ = in.readInt();
                int worldLen = in.readInt();
                byte[] worldBytes = new byte[worldLen];
                in.readFully(worldBytes);
                String worldName = new String(worldBytes);
                int playerNameLen = in.readInt();
                byte[] playerNameBytes = new byte[playerNameLen];
                in.readFully(playerNameBytes);
                String playerName = new String(playerNameBytes);
                
                int msgKeyLen = in.readInt();
                byte[] msgKeyBytes = new byte[msgKeyLen];
                in.readFully(msgKeyBytes);
                String messageKey = new String(msgKeyBytes);
                
                int replCount = in.readInt();
                List<String> replacements = new ArrayList<>();
                for (int i = 0; i < replCount; i++) {
                    int replLen = in.readInt();
                    byte[] replBytes = new byte[replLen];
                    in.readFully(replBytes);
                    replacements.add(new String(replBytes));
                }

                getLogger().info("RTP request for " + playerName + ": World=" + worldName + ", msg=" + messageKey);

                getServer().getScheduler().runTask(this, () -> {
                    Player player = getServer().getPlayer(playerName);
                    if (player == null || !player.isOnline()) {
                        getLogger().warning("Player " + playerName + " not found or offline");
                        return;
                    }
                    performRtp(player, minX, maxX, minZ, maxZ, worldName, messageKey, replacements);
                });
            } else if (packetType == 2) {
                int worldLen = in.readInt();
                byte[] worldBytes = new byte[worldLen];
                in.readFully(worldBytes);
                String worldName = new String(worldBytes);
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float yaw = in.readFloat();
                float pitch = in.readFloat();
                int playerNameLen = in.readInt();
                byte[] playerNameBytes = new byte[playerNameLen];
                in.readFully(playerNameBytes);
                String playerName = new String(playerNameBytes);
                
                int msgKeyLen = in.readInt();
                byte[] msgKeyBytes = new byte[msgKeyLen];
                in.readFully(msgKeyBytes);
                String messageKey = new String(msgKeyBytes);

                getLogger().info("Home teleport request for " + playerName);

                getServer().getScheduler().runTask(this, () -> {
                    Player player = getServer().getPlayer(playerName);
                    if (player == null || !player.isOnline()) {
                        getLogger().warning("Player " + playerName + " not found or offline");
                        return;
                    }
                    performHomeTeleport(player, worldName, x, y, z, yaw, pitch, messageKey);
                });
            } else if (packetType == 3) {
                int homeNameLen = in.readInt();
                byte[] homeNameBytes = new byte[homeNameLen];
                in.readFully(homeNameBytes);
                String homeName = new String(homeNameBytes);
                int serverNameLen = in.readInt();
                byte[] serverNameBytes = new byte[serverNameLen];
                in.readFully(serverNameBytes);
                int playerNameLen = in.readInt();
                byte[] playerNameBytes = new byte[playerNameLen];
                in.readFully(playerNameBytes);
                String playerName = new String(playerNameBytes);
                
                int msgKeyLen = in.readInt();
                byte[] msgKeyBytes = new byte[msgKeyLen];
                in.readFully(msgKeyBytes);
                String messageKey = new String(msgKeyBytes);
                
                int homeNameForMsgLen = in.readInt();
                byte[] homeNameForMsgBytes = new byte[homeNameForMsgLen];
                in.readFully(homeNameForMsgBytes);
                String homeNameForMsg = new String(homeNameForMsgBytes);

                getLogger().info("Set home request for " + playerName + ": " + homeName);

                getServer().getScheduler().runTask(this, () -> {
                    Player player = getServer().getPlayer(playerName);
                    if (player == null || !player.isOnline()) {
                        getLogger().warning("Player " + playerName + " not found or offline");
                        return;
                    }
                    handleSetHome(player, homeName, messageKey, homeNameForMsg);
                });
            }
        } catch (Exception e) {
            getLogger().severe("Error handling packet: " + e.getMessage());
        }
    }

    private void performRtp(Player player, int minX, int maxX, int minZ, int maxZ, String worldName, String messageKey, List<String> replacements) {
        try {
            World world = getServer().getWorld(worldName);
            if (world == null) {
                world = player.getWorld();
                getLogger().warning("World '" + worldName + "' not found, using " + player.getWorld().getName());
            }

            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            int y = world.getHighestBlockYAt(x, z) + 2;

            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            
            int attempts = 0;
            while (attempts < 100 && (loc.getBlock().getType().isSolid() || 
                    loc.getBlock().getType() == Material.LAVA || 
                    loc.getBlock().getType() == Material.WATER)) {
                y++;
                loc = new Location(world, x + 0.5, y, z + 0.5);
                attempts++;
            }

            if (attempts >= 100) {
                sendMessageToPlayer(player, "rtp-no-safe-location", "");
                return;
            }

            player.teleport(loc);
            
            String coordX = String.format("%.0f", loc.getX());
            String coordY = String.format("%.0f", loc.getY());
            String coordZ = String.format("%.0f", loc.getZ());
            debugLog("RTP coords: x=" + coordX + ", y=" + coordY + ", z=" + coordZ);
            
            String msg = formatMessage(messageKey, coordX, coordY, coordZ);
            debugLog("Formatted message: " + msg);
            player.sendMessage(colorize(msg));
            
            debugLog(player.getName() + " teleported to " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            
        } catch (Exception e) {
            getLogger().severe("Error during RTP: " + e.getMessage());
            sendMessageToPlayer(player, "rtp-error", "");
        }
    }

    private void performHomeTeleport(Player player, String worldName, double x, double y, double z, float yaw, float pitch, String messageKey) {
        try {
            World world = getServer().getWorld(worldName);
            if (world == null) {
                world = player.getWorld();
                getLogger().warning("World '" + worldName + "' not found, using " + player.getWorld().getName());
            }

            Location loc = new Location(world, x, y, z, yaw, pitch);
            player.teleport(loc);
            sendMessageToPlayer(player, messageKey, "");
            
            getLogger().info(player.getName() + " home teleported to " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            
        } catch (Exception e) {
            getLogger().severe("Error during home teleport: " + e.getMessage());
            sendMessageToPlayer(player, "home-error", "");
        }
    }

    private void handleSetHome(Player player, String homeName, String messageKey, String homeNameForMsg) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        
        if (world == null) {
            world = getServer().getWorlds().get(0);
        }

        try {
            writer.println("SETHOME:" + player.getName() + ":" + homeName + ":" + world.getName() + ":" + 
                loc.getX() + ":" + loc.getY() + ":" + loc.getZ() + ":" + loc.getYaw() + ":" + loc.getPitch());
            writer.flush();
            sendMessageToPlayer(player, messageKey, homeNameForMsg);
            getLogger().info("Player " + player.getName() + " set home '" + homeName + "' at " + 
                String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ()));
        } catch (Exception e) {
            getLogger().severe("Error sending SETHOME: " + e.getMessage());
            sendMessageToPlayer(player, "home-error", "");
        }
    }

    private void handleTpaPacket(byte[] message) {
        if (message.length < 1) return;
        
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(message);
            java.io.DataInputStream in = new java.io.DataInputStream(bais);
            
            byte packetType = in.readByte();
            
            if (packetType == 4) {
                int senderLen = in.readInt();
                byte[] senderBytes = new byte[senderLen];
                in.readFully(senderBytes);
                String senderName = new String(senderBytes);
                
                int targetLen = in.readInt();
                byte[] targetBytes = new byte[targetLen];
                in.readFully(targetBytes);
                String targetName = new String(targetBytes);
                
                int idLen = in.readInt();
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String requestId = new String(idBytes);
                
                long timestamp = in.readLong();
                boolean isHere = in.readBoolean();
                
                int msgKeyLen = in.readInt();
                byte[] msgKeyBytes = new byte[msgKeyLen];
                in.readFully(msgKeyBytes);
                String messageKey = new String(msgKeyBytes);
                
                int senderForMsgLen = in.readInt();
                byte[] senderForMsgBytes = new byte[senderForMsgLen];
                in.readFully(senderForMsgBytes);
                String senderForMsg = new String(senderForMsgBytes);
            
                getLogger().info("TPA request from " + senderName + " to " + targetName + " (here=" + isHere + ")");

                getServer().getScheduler().runTask(this, () -> {
                    Player targetPlayer = getServer().getPlayer(targetName);
                    if (targetPlayer == null || !targetPlayer.isOnline()) {
                        getLogger().warning("Target player " + targetName + " not found or offline");
                        return;
                    }
                    displayTpaRequest(targetPlayer, messageKey, senderForMsg);
                });
            }
        } catch (Exception e) {
            getLogger().severe("Error handling TPA packet: " + e.getMessage());
        }
    }

    private void displayTpaRequest(Player target, String messageKey, String senderName) {
        String message = formatMessage(messageKey, senderName);
        target.sendMessage(colorize(message));
        target.sendMessage(colorize("&7Use &a/tpaaccept &7or &c/tpadecline"));
    }

    private void handleGetLoc(String data) {
        getLogger().info("handleGetLoc called with data: " + data);
        
        String[] parts = data.split(":");
        if (parts.length >= 2) {
            String targetName = parts[0];
            String senderName = parts[1];
            
            getLogger().info("Getting location of " + targetName + " for " + senderName);
            
            getServer().getScheduler().runTask(this, () -> {
                Player targetPlayer = getServer().getPlayer(targetName);
                if (targetPlayer == null || !targetPlayer.isOnline()) {
                    getLogger().warning("Target player " + targetName + " not found");
                    return;
                }
                
                Location loc = targetPlayer.getLocation();
                World world = loc.getWorld();
                String worldName = world != null ? world.getName() : "world";
                
                String locMessage = "LOC:" + targetName + ":" + senderName + ":" + worldName + ":" + 
                    loc.getX() + ":" + loc.getY() + ":" + loc.getZ() + ":" + loc.getYaw() + ":" + loc.getPitch();
                
                getLogger().info("Sending LOC message: " + locMessage);
                
                try {
                    writer.println(locMessage);
                    writer.flush();
                    getLogger().info("Sent location of " + targetName + " to Velocity for TPA");
                } catch (Exception e) {
                    getLogger().severe("Error sending location: " + e.getMessage());
                }
            });
        }
    }

    private void handleTpaTeleport(String data) {
        getLogger().info("handleTpaTeleport called with data: " + data);
        
        String[] parts = data.split(":");
        if (parts.length >= 7) {
            String playerName = parts[0];
            String worldName = parts[1];
            double x = Double.parseDouble(parts[2]);
            double y = Double.parseDouble(parts[3]);
            double z = Double.parseDouble(parts[4]);
            float yaw = Float.parseFloat(parts[5]);
            float pitch = Float.parseFloat(parts[6]);
            
            getLogger().info("TPA teleport request for " + playerName + " to " + x + "," + y + "," + z + " in " + worldName);
            
            getServer().getScheduler().runTask(this, () -> {
                Player player = getServer().getPlayer(playerName);
                if (player == null || !player.isOnline()) {
                    getLogger().warning("Player " + playerName + " not found for TPA teleport");
                    return;
                }
                
                World world = getServer().getWorld(worldName);
                if (world == null) {
                    world = player.getWorld();
                    getLogger().warning("World '" + worldName + "' not found, using " + world.getName());
                }
                
                Location loc = new Location(world, x, y, z, yaw, pitch);
                player.teleport(loc);
                sendMessageToPlayer(player, "tpa-teleport-success", "");
                getLogger().info(playerName + " teleported via TPA to " + x + "," + y + "," + z);
            });
        } else {
            getLogger().warning("Invalid TPATEL data: " + data);
        }
    }

    private void sendMessageToPlayer(Player player, String messageKey, String replacement) {
        String message = formatMessage(messageKey, replacement);
        player.sendMessage(colorize(message));
    }

    private String formatMessage(String messageKey, String... replacements) {
        String template = getMessageTemplate(messageKey);
        String result = template;
        for (int i = 0; i < replacements.length; i++) {
            result = result.replace("{" + i + "}", replacements[i]);
        }
        return result;
    }

    private String getMessageTemplate(String key) {
        switch (key) {
            case "rtp-success":
                return "&aYou were teleported to &e{0}, {1}, {2}&a!";
            case "rtp-no-safe-location":
                return "&cNo safe location found after 100 attempts.";
            case "rtp-error":
                return "&cError during teleportation!";
            case "home-success":
                return "&aTeleported to home!";
            case "home-error":
                return "&cError during teleport!";
            case "sethome-success":
                return "&aHome '&e{0}&a' set!";
            case "sethome-error":
                return "&cError setting home!";
            case "tpa-request-here":
                return "&6&l[TPA] &f{0} &7wants you to teleport to them!";
            case "tpa-request-to":
                return "&6&l[TPA] &f{0} &7wants to teleport to you!";
            case "tpa-teleport-success":
                return "&aTeleported!";
            default:
                return "&cUnknown message: " + key;
        }
    }

    private String colorize(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public static RTPLinkHelper getInstance() {
        return instance;
    }
}
