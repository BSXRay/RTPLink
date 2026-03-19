package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RtpCommand implements SimpleCommand {

    private final RTPLink plugin;

    public RtpCommand(RTPLink plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        if (!(source instanceof Player player)) {
            source.sendMessage(plugin.getMessageManager().component("general-player-only"));
            return;
        }

        if (!player.hasPermission("rtplink.rtp")) {
            player.sendMessage(plugin.getMessageManager().component("general-no-permission"));
            return;
        }
        
        if (args.length > 0 && args[0].equalsIgnoreCase("local")) {
            executeLocal(player);
        } else {
            executeRemote(player);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        if (!(invocation.source() instanceof Player player)) {
            return List.of();
        }
        
        if (!player.hasPermission("rtplink.rtp") && !player.hasPermission("rtplink.admin")) {
            return List.of();
        }
        
        if (args.length == 0) {
            return List.of("local");
        }
        
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase();
            
            if ("local".startsWith(input)) {
                suggestions.add("local");
            }
            
            return suggestions;
        }
        return List.of();
    }

    private void executeLocal(Player player) {
        Optional<String> currentServerName = player.getCurrentServer().map(s -> s.getServerInfo().getName());
        
        if (currentServerName.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("rtp-not-connected"));
            return;
        }
        
        String serverName = currentServerName.get();
        if (!plugin.hasHelper(serverName)) {
            player.sendMessage(plugin.getMessageManager().component("rtp-server-not-available"));
            return;
        }

        player.sendMessage(plugin.getMessageManager().component("rtp-teleporting"));

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            sendRtpPacket(player, serverName);
        }).delay(500, TimeUnit.MILLISECONDS).schedule();
    }

    private void executeRemote(Player player) {
        String targetName = plugin.getRandomServerForPlayer(player);

        if (targetName == null) {
            player.sendMessage(plugin.getMessageManager().component("rtp-no-servers"));
            if (plugin.isDebug()) {
                plugin.getLogger().info("No servers with RTP support! Known: {}, Available: {}", 
                    plugin.getHelperServers(),
                    plugin.getServer().getAllServers().stream().map(s -> s.getServerInfo().getName()).collect(Collectors.toList()));
            }
            return;
        }
        
        Optional<String> currentServer = player.getCurrentServer().map(s -> s.getServerInfo().getName());
        if (currentServer.isPresent() && currentServer.get().equals(targetName)) {
            executeLocal(player);
            return;
        }
        
        Optional<RegisteredServer> targetServer = plugin.getServer().getServer(targetName);
        if (targetServer.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("rtp-server-not-found"));
            return;
        }

        player.sendMessage(plugin.getMessageManager().component("rtp-connecting", targetName));

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            player.createConnectionRequest(targetServer.get()).connect().thenAccept(result -> {
                if (result.isSuccessful()) {
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        sendRtpPacket(player, targetName);
                    }).delay(1, TimeUnit.SECONDS).schedule();
                } else {
                    player.sendMessage(plugin.getMessageManager().component("rtp-connection-failed", targetName));
                }
            });
        }).schedule();
    }

    private void sendRtpPacket(Player player, String serverName) {
        RTPLink.RtpConfig cfg = plugin.getEffectiveConfig(serverName);
        byte[] worldBytes = cfg.world.getBytes(StandardCharsets.UTF_8);
        byte[] playerNameBytes = player.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] messageKeyBytes = "rtp-success".getBytes(StandardCharsets.UTF_8);
        
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
        
        try {
            out.writeByte(1);
            out.writeInt(cfg.minX);
            out.writeInt(cfg.maxX);
            out.writeInt(cfg.minZ);
            out.writeInt(cfg.maxZ);
            out.writeInt(worldBytes.length);
            out.write(worldBytes);
            out.writeInt(playerNameBytes.length);
            out.write(playerNameBytes);
            out.writeInt(messageKeyBytes.length);
            out.write(messageKeyBytes);
            out.writeInt(3);
            byte[] coordX = "X".getBytes(StandardCharsets.UTF_8);
            byte[] coordY = "Y".getBytes(StandardCharsets.UTF_8);
            byte[] coordZ = "Z".getBytes(StandardCharsets.UTF_8);
            out.writeInt(coordX.length);
            out.write(coordX);
            out.writeInt(coordY.length);
            out.write(coordY);
            out.writeInt(coordZ.length);
            out.write(coordZ);
            out.flush();
        } catch (Exception e) {
            plugin.getLogger().error("Failed to create RTP packet: " + e.getMessage());
            return;
        }
        
        byte[] data = baos.toByteArray();
        
        if (!plugin.sendRtpToServer(serverName, data)) {
            plugin.getLogger().warn("Failed to send RTP to server: " + serverName);
            player.sendMessage(plugin.getMessageManager().component("rtp-error"));
        }
    }
}
