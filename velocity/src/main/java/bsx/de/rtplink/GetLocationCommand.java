package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

public class GetLocationCommand implements SimpleCommand {

    private final RTPLink plugin;

    public GetLocationCommand(RTPLink plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        
        if (!(source instanceof Player player)) {
            source.sendMessage(plugin.getMessageManager().component("general-player-only"));
            return;
        }

        String location = plugin.getGeoService().getPlayerLocation(player);
        if (location != null) {
            player.sendMessage(plugin.getMessageManager().component("location-current", location));
        } else {
            player.sendMessage(plugin.getMessageManager().component("location-not-set"));
            player.sendMessage(plugin.getMessageManager().component("location-set-tip"));
        }
    }
}
