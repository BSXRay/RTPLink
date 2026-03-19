package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public class SetHomeCommand implements SimpleCommand {

    private final RTPLink plugin;
    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("rtplink", "main");

    public SetHomeCommand(RTPLink plugin) {
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

        if (!player.hasPermission("rtplink.admin")) {
            player.sendMessage(plugin.getMessageManager().component("general-no-permission"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(plugin.getMessageManager().component("sethome-usage"));
            return;
        }

        String homeName = args[0].toLowerCase();

        if (homeName.length() > 16) {
            player.sendMessage(plugin.getMessageManager().component("sethome-name-too-long"));
            return;
        }

        Optional<String> currentServer = player.getCurrentServer().map(s -> s.getServerInfo().getName());
        if (currentServer.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("sethome-not-connected"));
            return;
        }

        String serverName = currentServer.get();
        
        if (!plugin.hasHelper(serverName)) {
            player.sendMessage(plugin.getMessageManager().component("sethome-not-available"));
            return;
        }

        player.sendMessage(plugin.getMessageManager().component("sethome-sending"));

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
                out.writeByte(3);
                byte[] nameBytes = homeName.getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                byte[] serverBytes = serverName.getBytes(StandardCharsets.UTF_8);
                out.writeInt(serverBytes.length);
                out.write(serverBytes);
                byte[] playerNameBytes = player.getUsername().getBytes(StandardCharsets.UTF_8);
                out.writeInt(playerNameBytes.length);
                out.write(playerNameBytes);
                byte[] messageKeyBytes = "sethome-success".getBytes(StandardCharsets.UTF_8);
                out.writeInt(messageKeyBytes.length);
                out.write(messageKeyBytes);
                byte[] homeNameForMsg = homeName.getBytes(StandardCharsets.UTF_8);
                out.writeInt(homeNameForMsg.length);
                out.write(homeNameForMsg);
                out.flush();
                
                plugin.sendSethomeToServer(serverName, baos.toByteArray());
                player.sendMessage(plugin.getMessageManager().component("sethome-success", homeName));
            } catch (Exception e) {
                player.sendMessage(plugin.getMessageManager().component("sethome-error"));
            }
        }).delay(500, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.source() instanceof Player player && player.hasPermission("rtplink.admin")) {
            return List.of();
        }
        return List.of();
    }
}
