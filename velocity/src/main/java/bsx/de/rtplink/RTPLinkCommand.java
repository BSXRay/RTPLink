package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;

public class RTPLinkCommand implements SimpleCommand {

    private final RTPLink plugin;

    public RTPLinkCommand(RTPLink plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        if (!source.hasPermission("rtplink.admin")) {
            source.sendMessage(plugin.getMessageManager().component("general-no-permission"));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(source);
            case "getlocation" -> handleGetLocation(source, args);
            case "changelocation" -> handleChangeLocation(source, args);
            case "list" -> handleList(source);
            default -> sendHelp(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!source.hasPermission("rtplink.admin")) {
            return List.of();
        }

        if (args.length == 0) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("reload");
            suggestions.add("getlocation");
            suggestions.add("changelocation");
            suggestions.add("list");
            return suggestions;
        }

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase();
            
            if ("reload".startsWith(input)) suggestions.add("reload");
            if ("getlocation".startsWith(input)) suggestions.add("getlocation");
            if ("changelocation".startsWith(input)) suggestions.add("changelocation");
            if ("list".startsWith(input)) suggestions.add("list");
            
            return suggestions;
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase();
            
            if (subcommand.equals("getlocation") || subcommand.equals("changelocation")) {
                List<String> suggestions = new ArrayList<>();
                String input = args[1].toLowerCase();
                
                for (Player player : plugin.getServer().getAllPlayers()) {
                    if (player.getUsername().toLowerCase().startsWith(input)) {
                        suggestions.add(player.getUsername());
                    }
                }
                return suggestions;
            }
            
            if (subcommand.equals("list")) {
                List<String> suggestions = new ArrayList<>();
                String input = args[1].toLowerCase();
                
                if ("servers".startsWith(input)) suggestions.add("servers");
                if ("players".startsWith(input)) suggestions.add("players");
                
                return suggestions;
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("changelocation")) {
            List<String> suggestions = new ArrayList<>();
            String input = args[2].toUpperCase();
            
            String[] locations = {"DE", "AT", "CH", "US", "GB", "FR", "ES", "IT", "NL", "PL", "CZ", "DK", "SE", "NO", "FI", "BR", "RU", "CN", "JP", "KR", "AU", "CA", "MX", "ES"};
            for (String loc : locations) {
                if (loc.startsWith(input)) {
                    suggestions.add(loc);
                }
            }
            
            return suggestions;
        }

        return List.of();
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(plugin.getMessageManager().component("rtplink-help-header"));
        source.sendMessage(plugin.getMessageManager().component("rtplink-help-reload"));
        source.sendMessage(plugin.getMessageManager().component("rtplink-help-getlocation"));
        source.sendMessage(plugin.getMessageManager().component("rtplink-help-changelocation"));
        source.sendMessage(plugin.getMessageManager().component("rtplink-help-list"));
    }

    private void handleReload(CommandSource source) {
        source.sendMessage(plugin.getMessageManager().component("rtplink-reload-success"));

        plugin.reload();

        int sent = plugin.broadcastReload();
        source.sendMessage(plugin.getMessageManager().component("rtplink-reload-sent", sent));
    }

    private void handleGetLocation(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-getlocation-usage"));
            return;
        }

        String playerName = args[1];
        Player player = plugin.getServer().getPlayer(playerName).orElse(null);

        if (player == null) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-player-not-found", playerName));
            return;
        }

        String location = plugin.getGeoService().getPlayerLocation(player);
        
        source.sendMessage(plugin.getMessageManager().component("rtplink-location-header"));
        source.sendMessage(plugin.getMessageManager().component("rtplink-location-info", player.getUsername(), location != null ? location : "(not detected)"));
        
        if (location == null) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-location-tip"));
        }
    }

    private void handleChangeLocation(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-changelocation-usage"));
            source.sendMessage(plugin.getMessageManager().component("rtplink-changelocation-example"));
            source.sendMessage(plugin.getMessageManager().component("rtplink-changelocation-tip"));
            return;
        }

        String playerName = args[1];
        String location = args[2].toUpperCase();

        Player player = plugin.getServer().getPlayer(playerName).orElse(null);

        if (player == null) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-player-not-found", playerName));
            return;
        }

        if (location.length() != 2 && !location.startsWith("!")) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-location-invalid"));
            return;
        }

        plugin.getGeoService().setPlayerLocation(player, location);
        source.sendMessage(plugin.getMessageManager().component("rtplink-location-set", playerName, location));
    }

    private void handleList(CommandSource source) {
        if (plugin.getHelperServers().isEmpty()) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-servers-header"));
            source.sendMessage(plugin.getMessageManager().component("rtplink-no-servers"));
            return;
        }

        source.sendMessage(plugin.getMessageManager().component("rtplink-servers-header"));
        
        for (String server : plugin.getHelperServers()) {
            var cfg = plugin.getServerConfig(server);
            String location = cfg != null ? cfg.location : "";
            String locationDisplay = location.isEmpty() ? "all" : location;
            
            source.sendMessage(plugin.getMessageManager().component("rtplink-server-item", server, locationDisplay, cfg != null ? cfg.weight : 1, plugin.isServerEnabled(server) ? "online" : "disabled"));
        }
        
        source.sendMessage(net.kyori.adventure.text.Component.text(""));
        source.sendMessage(plugin.getMessageManager().component("rtplink-location-header"));
        
        int count = 0;
        for (Player player : plugin.getServer().getAllPlayers()) {
            String loc = plugin.getGeoService().getPlayerLocation(player);
            if (loc != null) {
                source.sendMessage(plugin.getMessageManager().component("rtplink-location-info", player.getUsername(), loc));
                count++;
            }
        }
        
        if (count == 0) {
            source.sendMessage(plugin.getMessageManager().component("rtplink-no-players"));
        }
    }
}
