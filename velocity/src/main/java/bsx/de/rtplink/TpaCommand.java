package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;

public class TpaCommand implements SimpleCommand {

    private final RTPLink plugin;

    public TpaCommand(RTPLink plugin) {
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
            sendHelp(player);
            return;
        }

        String targetName = args[0];
        
        if (args.length == 2 && args[1].equalsIgnoreCase("here")) {
            handleTpa(player, targetName, true);
        } else if (args.length == 1) {
            handleTpa(player, targetName, false);
        } else {
            sendHelp(player);
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessageManager().component("tpa-help-header"));
        player.sendMessage(plugin.getMessageManager().component("tpa-help-usage"));
        player.sendMessage(plugin.getMessageManager().component("tpa-help-usage-here"));
        player.sendMessage(plugin.getMessageManager().component("tpa-help-accept"));
        player.sendMessage(plugin.getMessageManager().component("tpa-help-decline"));
    }

    private void handleTpa(Player sender, String targetName, boolean isHere) {
        Player target = plugin.getServer().getPlayer(targetName).orElse(null);

        if (target == null) {
            sender.sendMessage(plugin.getMessageManager().component("tpa-target-not-found", targetName));
            return;
        }

        if (target.equals(sender)) {
            sender.sendMessage(plugin.getMessageManager().component("tpa-self-request"));
            return;
        }

        if (plugin.getTpaManager().hasPendingRequest(sender.getUsername(), target.getUsername())) {
            sender.sendMessage(plugin.getMessageManager().component("tpa-request-already", targetName));
            return;
        }

        boolean success = plugin.getTpaManager().sendTpaRequest(sender, target, isHere);
        if (success) {
            sender.sendMessage(plugin.getMessageManager().component("tpa-request-sent", target.getUsername()));
        } else {
            sender.sendMessage(plugin.getMessageManager().component("tpa-error"));
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
        Player sourcePlayer = player;

        if (args.length == 0 || (args.length == 1 && args[0].isEmpty())) {
            List<String> suggestions = new ArrayList<>();
            for (Player targetPlayer : plugin.getServer().getAllPlayers()) {
                if (targetPlayer.equals(sourcePlayer)) continue;
                suggestions.add(targetPlayer.getUsername());
            }
            return suggestions;
        }

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase();
            
            for (Player targetPlayer : plugin.getServer().getAllPlayers()) {
                if (targetPlayer.equals(sourcePlayer)) continue;
                if (targetPlayer.getUsername().toLowerCase().startsWith(input)) {
                    suggestions.add(targetPlayer.getUsername());
                }
            }
            return suggestions;
        }

        if (args.length == 2) {
            String input = args[1].toLowerCase();
            if ("here".startsWith(input)) {
                return List.of("here");
            }
        }

        return List.of();
    }
}
