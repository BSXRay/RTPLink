package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;

public class VisitCommand implements SimpleCommand {

    private final RTPLink plugin;

    public VisitCommand(RTPLink plugin) {
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

        if (args.length == 0) {
            sendServerList(player);
            return;
        }

        if (args.length == 1) {
            String targetServer = args[0].toLowerCase();
            connectToServer(player, targetServer);
            return;
        }

        sendHelp(player);
    }

    private void sendServerList(Player player) {
        String currentServer = player.getCurrentServer()
            .map(s -> s.getServerInfo().getName())
            .orElse("");

        player.sendMessage(plugin.getMessageManager().component("visit-header"));
        player.sendMessage(Component.text(""));

        List<String> availableServers = new ArrayList<>();
        
        for (String serverName : plugin.getHelperServers()) {
            if (!serverName.equalsIgnoreCase(currentServer) && plugin.isServerEnabled(serverName)) {
                availableServers.add(serverName);
            }
        }

        if (availableServers.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("visit-no-servers"));
            return;
        }

        for (String serverName : availableServers) {
            int playerCount = 0;
            for (Player p : plugin.getServer().getAllPlayers()) {
                if (p.getCurrentServer().isPresent() && 
                    p.getCurrentServer().get().getServerInfo().getName().equalsIgnoreCase(serverName)) {
                    playerCount++;
                }
            }

            Component serverItem = Component.text("§8▪ §e" + serverName)
                .hoverEvent(HoverEvent.showText(Component.text(plugin.getMessageManager().get("visit-list-item", serverName, String.valueOf(playerCount)))))
                .clickEvent(ClickEvent.runCommand("/visit " + serverName));
            
            player.sendMessage(serverItem);
        }

        player.sendMessage(Component.text(""));
        player.sendMessage(plugin.getMessageManager().component("visit-tip"));
    }

    private void connectToServer(Player player, String targetServer) {
        String currentServer = player.getCurrentServer()
            .map(s -> s.getServerInfo().getName())
            .orElse("");

        if (currentServer.equalsIgnoreCase(targetServer)) {
            player.sendMessage(plugin.getMessageManager().component("visit-already-connected"));
            return;
        }

        if (!plugin.getHelperServers().contains(targetServer.toLowerCase())) {
            player.sendMessage(plugin.getMessageManager().component("visit-server-not-found", targetServer));
            return;
        }

        if (!plugin.isServerEnabled(targetServer)) {
            player.sendMessage(plugin.getMessageManager().component("visit-server-disabled", targetServer));
            return;
        }

        var serverOpt = plugin.getServer().getServer(targetServer);
        if (serverOpt.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("visit-server-not-found", targetServer));
            return;
        }

        player.sendMessage(plugin.getMessageManager().component("visit-connecting", targetServer));
        
        player.createConnectionRequest(serverOpt.get()).connect().thenAccept(result -> {
            if (!result.isSuccessful()) {
                player.sendMessage(plugin.getMessageManager().component("visit-connection-failed", targetServer));
            }
        });
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessageManager().component("visit-help-header"));
        player.sendMessage(plugin.getMessageManager().component("visit-help-usage"));
        player.sendMessage(plugin.getMessageManager().component("visit-help-usage-2"));
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
        String currentServer = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("");

        if (args.length == 0 || (args.length == 1 && args[0].isEmpty())) {
            List<String> suggestions = new ArrayList<>();
            
            for (String serverName : plugin.getHelperServers()) {
                if (!serverName.equalsIgnoreCase(currentServer) && 
                    plugin.isServerEnabled(serverName)) {
                    suggestions.add(serverName);
                }
            }
            return suggestions;
        }

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase();
            
            for (String serverName : plugin.getHelperServers()) {
                if (!serverName.equalsIgnoreCase(currentServer) && 
                    plugin.isServerEnabled(serverName) &&
                    serverName.toLowerCase().startsWith(input)) {
                    suggestions.add(serverName);
                }
            }
            return suggestions;
        }

        return List.of();
    }
}
