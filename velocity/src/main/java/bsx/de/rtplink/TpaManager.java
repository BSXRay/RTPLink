package bsx.de.rtplink;

import com.velocitypowered.api.proxy.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TpaManager {

    private final RTPLink plugin;
    private final Map<String, TpaRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT_SECONDS = 60;

    public TpaManager(RTPLink plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().buildTask(plugin, this::cleanupExpiredRequests)
            .repeat(10, TimeUnit.SECONDS)
            .schedule();
    }

    public boolean sendTpaRequest(Player sender, Player target, boolean isHere) {
        String key = getRequestKey(sender.getUsername(), target.getUsername());
        
        if (pendingRequests.containsKey(key)) {
            return false;
        }

        TpaRequest request = new TpaRequest();
        request.senderName = sender.getUsername();
        request.senderServer = getCurrentServer(sender);
        request.targetName = target.getUsername();
        request.targetServer = getCurrentServer(target);
        request.timestamp = System.currentTimeMillis();
        request.requestId = UUID.randomUUID().toString();
        request.isHere = isHere;

        pendingRequests.put(key, request);

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            sendRequestToTarget(request);
        }).delay(500, TimeUnit.MILLISECONDS).schedule();

        return true;
    }

    private String getCurrentServer(Player player) {
        return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
    }

    private void sendRequestToTarget(TpaRequest request) {
        String targetServer = request.targetServer;
        
        if (targetServer.isEmpty()) {
            plugin.getLogger().warn("Could not determine target server for TPA request");
            return;
        }

        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
            
            out.writeByte(4);
            
            byte[] senderBytes = request.senderName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(senderBytes.length);
            out.write(senderBytes);
            
            byte[] targetBytes = request.targetName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(targetBytes.length);
            out.write(targetBytes);
            
            byte[] idBytes = request.requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(idBytes.length);
            out.write(idBytes);
            
            out.writeLong(request.timestamp);
            out.writeBoolean(request.isHere);
            
            String messageKey = request.isHere ? "tpa-request-here" : "tpa-request-to";
            byte[] messageKeyBytes = messageKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(messageKeyBytes.length);
            out.write(messageKeyBytes);
            
            byte[] senderNameForMsg = request.senderName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(senderNameForMsg.length);
            out.write(senderNameForMsg);
            
            out.flush();

            plugin.sendTpaRequestToServer(targetServer, baos.toByteArray());
            plugin.debugLogDirect("Sent TPA request from %s to %s (ID: %s)", request.senderName, request.targetName, request.requestId);
        } catch (Exception e) {
            plugin.debugLogDirect("Error sending TPA request: " + e.getMessage());
        }
    }

    public void handleTpaResponse(String requestId, boolean accepted) {
        TpaRequest request = null;
        String requestKey = null;
        
        for (Map.Entry<String, TpaRequest> entry : pendingRequests.entrySet()) {
            if (entry.getValue().requestId.equals(requestId)) {
                request = entry.getValue();
                requestKey = entry.getKey();
                break;
            }
        }

        if (request == null) {
            plugin.debugLogDirect("TPA request not found: %s", requestId);
            return;
        }

        pendingRequests.remove(requestKey);

        if (accepted) {
            plugin.debugLogDirect("TPA accepted: %s -> %s (isHere=%s)", request.senderName, request.targetName, request.isHere);
            if (request.isHere) {
                plugin.debugLogDirect("  Teleporting %s to %s on server %s", request.targetName, request.senderName, request.senderServer);
                teleportPlayerTo(request.targetName, request.targetServer, request.senderName, request.senderServer);
            } else {
                plugin.debugLogDirect("  Teleporting %s to %s on server %s", request.senderName, request.targetName, request.targetServer);
                teleportPlayerTo(request.senderName, request.senderServer, request.targetName, request.targetServer);
            }
        } else {
            plugin.debugLogDirect("TPA declined: %s -> %s", request.senderName, request.targetName);
        }
    }

    private void teleportPlayerTo(String playerName, String fromServer, String targetName, String targetServer) {
        plugin.debugLogDirect("teleportPlayerTo: player=%s, fromServer=%s, target=%s, targetServer=%s", 
            playerName, fromServer, targetName, targetServer);
        
        Player targetPlayer = plugin.getServer().getPlayer(targetName).orElse(null);
        if (targetPlayer == null) {
            plugin.debugLogDirect("Target player %s not found", targetName);
            return;
        }

        Player senderPlayer = plugin.getServer().getPlayer(playerName).orElse(null);
        if (senderPlayer == null) {
            plugin.debugLogDirect("Sender player %s not found", playerName);
            return;
        }

        String currentServer = getCurrentServer(senderPlayer);
        plugin.debugLogDirect("Player %s current server: %s", playerName, currentServer);
        
        if (!currentServer.equals(targetServer)) {
            senderPlayer.sendMessage(plugin.getMessageManager().component("tpa-connecting", targetServer));
            
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                var serverOpt = plugin.getServer().getServer(targetServer);
                if (serverOpt.isPresent()) {
                    senderPlayer.createConnectionRequest(serverOpt.get()).connect().thenAccept(result -> {
                        if (result.isSuccessful()) {
                            plugin.debugLogDirect("Successfully connected %s to %s", playerName, targetServer);
                            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                                executeTpaTeleport(playerName, fromServer, targetName, targetServer);
                            }).delay(1, java.util.concurrent.TimeUnit.SECONDS).schedule();
                        } else {
                            Player failPlayer = plugin.getServer().getPlayer(playerName).orElse(null);
                            if (failPlayer != null) {
                                failPlayer.sendMessage(plugin.getMessageManager().component("tpa-connection-failed", targetServer));
                            }
                        }
                    });
                } else {
                    Player failPlayer = plugin.getServer().getPlayer(playerName).orElse(null);
                    if (failPlayer != null) {
                        failPlayer.sendMessage(plugin.getMessageManager().component("tpa-server-not-found", targetServer));
                    }
                }
            }).schedule();
        } else {
            plugin.debugLogDirect("Player already on target server, executing teleport");
            executeTpaTeleport(playerName, fromServer, targetName, targetServer);
        }
    }

    private void executeTpaTeleport(String playerName, String fromServer, String targetName, String targetServer) {
        plugin.debugLogDirect("executeTpaTeleport: player=%s, target=%s, targetServer=%s", 
            playerName, targetName, targetServer);
        
        String currentServer = getCurrentServerByName(playerName);
        plugin.debugLogDirect("Player %s current server: %s (target server: %s)", playerName, currentServer, targetServer);
        
        if (currentServer == null || !currentServer.equals(targetServer)) {
            plugin.debugLogDirect("Player not on target server, aborting teleport");
            Player p = plugin.getServer().getPlayer(playerName).orElse(null);
            if (p != null) {
                p.sendMessage(plugin.getMessageManager().component("tpa-teleport-failed"));
            }
            return;
        }
        
        boolean sent = plugin.sendToServer(targetServer, "GETLOC:" + targetName + ":" + playerName);
        plugin.debugLogDirect("Sent GETLOC to %s: %s", targetServer, sent);
    }
    
    private String getCurrentServerByName(String playerName) {
        for (Player player : plugin.getServer().getAllPlayers()) {
            if (player.getUsername().equalsIgnoreCase(playerName)) {
                return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            }
        }
        return null;
    }

    public void removeRequest(String senderName, String targetName) {
        String key = getRequestKey(senderName, targetName);
        pendingRequests.remove(key);
    }

    public boolean hasPendingRequest(String senderName, String targetName) {
        String key = getRequestKey(senderName, targetName);
        return pendingRequests.containsKey(key);
    }

    public TpaRequest getRequestForTarget(String targetName) {
        for (TpaRequest request : pendingRequests.values()) {
            if (request.targetName.equalsIgnoreCase(targetName)) {
                return request;
            }
        }
        return null;
    }

    private String getRequestKey(String sender, String target) {
        return sender.toLowerCase() + ":" + target.toLowerCase();
    }

    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        long timeout = REQUEST_TIMEOUT_SECONDS * 1000;
        
        pendingRequests.entrySet().removeIf(entry -> {
            TpaRequest request = entry.getValue();
            if (now - request.timestamp > timeout) {
                plugin.debugLogDirect("TPA request expired: %s -> %s", request.senderName, request.targetName);
                return true;
            }
            return false;
        });
    }

    public static class TpaRequest {
        public String senderName;
        public String senderServer;
        public String targetName;
        public String targetServer;
        public long timestamp;
        public String requestId;
        public boolean isHere;
    }
}
