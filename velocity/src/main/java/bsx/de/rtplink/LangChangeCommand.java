package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;

public class LangChangeCommand implements SimpleCommand {

    private static final String[] LOCATIONS = {
        "DE", "AT", "CH", "US", "GB", "FR", "ES", "IT", "NL", "PL",
        "CZ", "DK", "SE", "NO", "FI", "BR", "RU", "CN", "JP", "KR",
        "AU", "CA", "MX", "PT", "BE", "IE", "TR", "UA", "IN", "ID"
    };

    private final RTPLink plugin;

    public LangChangeCommand(RTPLink plugin) {
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

        if (!player.hasPermission("rtplink.location")) {
            player.sendMessage(plugin.getMessageManager().component("general-no-permission"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(plugin.getMessageManager().component("location-usage"));
            player.sendMessage(plugin.getMessageManager().component("location-example"));
            return;
        }

        String location = args[0].toUpperCase();
        
        if (location.length() != 2) {
            player.sendMessage(plugin.getMessageManager().component("location-invalid-format"));
            return;
        }

        plugin.getGeoService().setPlayerLocation(player, location);
        player.sendMessage(plugin.getMessageManager().component("location-set", location));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (!invocation.source().hasPermission("rtplink.location")) {
            return List.of();
        }

        if (args.length == 0) {
            return List.of(LOCATIONS);
        }
        
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toUpperCase();
            
            for (String loc : LOCATIONS) {
                if (loc.startsWith(input)) {
                    suggestions.add(loc);
                }
            }
            
            return suggestions;
        }

        return List.of();
    }
}
