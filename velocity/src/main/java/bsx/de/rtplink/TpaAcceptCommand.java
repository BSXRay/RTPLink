package bsx.de.rtplink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

public class TpaAcceptCommand implements SimpleCommand {

    private final RTPLink plugin;

    public TpaAcceptCommand(RTPLink plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!(source instanceof Player player)) {
            source.sendMessage(plugin.getMessageManager().component("general-player-only"));
            return;
        }

        if (!player.hasPermission("rtplink.admin")) {
            player.sendMessage(plugin.getMessageManager().component("general-no-permission"));
            return;
        }

        TpaManager.TpaRequest request = plugin.getTpaManager().getRequestForTarget(player.getUsername());
        
        if (request == null) {
            player.sendMessage(plugin.getMessageManager().component("tpa-no-requests"));
            return;
        }

        plugin.getTpaManager().handleTpaResponse(request.requestId, true);
        player.sendMessage(plugin.getMessageManager().component("tpa-accepted", request.senderName));
        player.sendMessage(plugin.getMessageManager().component("tpa-teleporting"));
    }
}
