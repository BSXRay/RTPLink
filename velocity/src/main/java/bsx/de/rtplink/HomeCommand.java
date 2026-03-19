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

public class HomeCommand implements SimpleCommand {

    private final RTPLink plugin;
    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("rtplink", "main");

    public HomeCommand(RTPLink plugin) {
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
            listHomes(player);
            return;
        }

        String homeName = args[0].toLowerCase();
        HomeManager.HomeLocation home = plugin.getHomeManager().getHome(player.getUsername(), homeName);

        if (home == null) {
            player.sendMessage(plugin.getMessageManager().component("home-not-found", homeName));
            listHomes(player);
            return;
        }

        Optional<String> currentServer = player.getCurrentServer().map(s -> s.getServerInfo().getName());

        if (currentServer.isPresent() && currentServer.get().equals(home.serverName)) {
            player.sendMessage(plugin.getMessageManager().component("home-teleporting", homeName));
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                teleportToHome(player, home);
            }).delay(500, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
        } else {
            player.sendMessage(plugin.getMessageManager().component("home-connecting", home.serverName, homeName));
            
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                var serverOpt = plugin.getServer().getServer(home.serverName);
                if (serverOpt.isPresent()) {
                    player.createConnectionRequest(serverOpt.get()).connect().thenAccept(result -> {
                        if (result.isSuccessful()) {
                            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                                player.sendMessage(plugin.getMessageManager().component("home-teleporting", homeName));
                                teleportToHome(player, home);
                            }).delay(1, java.util.concurrent.TimeUnit.SECONDS).schedule();
                        } else {
                            player.sendMessage(plugin.getMessageManager().component("home-connection-failed", home.serverName));
                        }
                    });
                } else {
                    player.sendMessage(plugin.getMessageManager().component("home-server-not-found", home.serverName));
                }
            }).schedule();
        }
    }

    private void teleportToHome(Player player, HomeManager.HomeLocation home) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
            out.writeByte(2);
            byte[] worldBytes = home.world.getBytes(StandardCharsets.UTF_8);
            out.writeInt(worldBytes.length);
            out.write(worldBytes);
            out.writeDouble(home.x);
            out.writeDouble(home.y);
            out.writeDouble(home.z);
            out.writeFloat(home.yaw);
            out.writeFloat(home.pitch);
            byte[] playerNameBytes = player.getUsername().getBytes(StandardCharsets.UTF_8);
            out.writeInt(playerNameBytes.length);
            out.write(playerNameBytes);
            byte[] messageKeyBytes = "home-success".getBytes(StandardCharsets.UTF_8);
            out.writeInt(messageKeyBytes.length);
            out.write(messageKeyBytes);
            out.flush();
            
            plugin.sendHomeToServer(home.serverName, baos.toByteArray());
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().component("home-error"));
        }
    }

    private void listHomes(Player player) {
        var homes = plugin.getHomeManager().getPlayerHomes(player.getUsername());
        if (homes.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("home-no-homes"));
            return;
        }
        
        player.sendMessage(plugin.getMessageManager().component("home-list-header"));
        for (String homeName : homes.keySet()) {
            HomeManager.HomeLocation home = homes.get(homeName);
            player.sendMessage(plugin.getMessageManager().component("home-list-item", homeName, home.serverName));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            return List.of();
        }

        if (!player.hasPermission("rtplink.admin")) {
            return List.of();
        }

        String[] args = invocation.arguments();
        if (args.length <= 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args.length == 1 ? args[0].toLowerCase() : "";
            
            for (String homeName : plugin.getHomeManager().getHomeNames(player.getUsername())) {
                if (homeName.startsWith(input)) {
                    suggestions.add(homeName);
                }
            }
            return suggestions;
        }
        return List.of();
    }
}
