package bsx.de.rtplink;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;

public class PlayerJoinListener {

    private final RTPLink plugin;

    public PlayerJoinListener(RTPLink plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();
        plugin.getGeoService().detectAndSetLocation(player);
    }
}
