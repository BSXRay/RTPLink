package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;

public class DelHomeCommand implements SimpleCommand {

    private final RTPLink plugin;

    public DelHomeCommand(RTPLink plugin) {
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

        if (!plugin.getHomeManager().hasHome(player.getUsername(), homeName)) {
            player.sendMessage(plugin.getMessageManager().component("delhome-not-found", homeName));
            listHomes(player);
            return;
        }

        plugin.getHomeManager().deleteHome(player.getUsername(), homeName);
        player.sendMessage(plugin.getMessageManager().component("delhome-success", homeName));
    }

    private void listHomes(Player player) {
        var homes = plugin.getHomeManager().getPlayerHomes(player.getUsername());
        if (homes.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().component("delhome-no-homes"));
            return;
        }
        
        player.sendMessage(plugin.getMessageManager().component("home-list-header"));
        for (String homeName : homes.keySet()) {
            player.sendMessage(plugin.getMessageManager().component("delhome-list-item", homeName));
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
