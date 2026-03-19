package bsx.de.rtplink;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final RTPLink plugin;
    private final Map<String, String> messages = new HashMap<>();

    public MessageManager(RTPLink plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        Path messagesPath = plugin.getDataDirectory().resolve("messages.yml");
        
        if (!Files.exists(messagesPath)) {
            try (InputStream is = getClass().getResourceAsStream("/messages.yml")) {
                if (is != null) {
                    Files.copy(is, messagesPath);
                    plugin.getLogger().info("Created messages.yml");
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to create messages.yml: " + e.getMessage());
            }
        }

        try {
            messages.clear();
            String content = Files.readString(messagesPath);
            parseMessages(content);
            plugin.debugLogDirect("Loaded %d messages", messages.size());
        } catch (Exception e) {
            plugin.getLogger().error("Failed to load messages.yml: " + e.getMessage());
            loadHardcodedDefaults();
        }
    }

    private void parseMessages(String content) {
        for (String line : content.split("\n")) {
            line = line.trim();
            
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            if (line.contains(":")) {
                int colonIndex = line.indexOf(':');
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                
                if (!value.isEmpty()) {
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    messages.put(key, value);
                }
            }
        }
    }

    private void loadHardcodedDefaults() {
        messages.put("rtp-not-connected", "&cYou're not connected to a server!");
        messages.put("rtp-no-servers", "&cNo servers with RTP available!");
        messages.put("rtp-connecting", "&aConnecting to server &e{0}&a for RTP...");
        messages.put("rtp-success", "&aTeleported to &e{0}, {1}, {2}&a!");
        messages.put("home-no-homes", "&cYou don't have any homes set!");
        messages.put("tpa-request-sent", "&aTPA request sent!");
        messages.put("general-player-only", "&cThis command can only be executed as a player!");
    }

    public String get(String key) {
        return messages.getOrDefault(key, "&cMessage not found: " + key);
    }

    public String get(String key, Object... replacements) {
        String message = get(key);
        
        for (int i = 0; i < replacements.length; i++) {
            if (replacements[i] != null) {
                message = message.replace("{" + i + "}", String.valueOf(replacements[i]));
            }
        }
        
        return colorize(message);
    }

    public String colorize(String message) {
        if (message == null) return "";
        return message.replace("&", "§");
    }

    public net.kyori.adventure.text.Component component(String key) {
        return net.kyori.adventure.text.Component.text(colorize(get(key)));
    }

    public net.kyori.adventure.text.Component component(String key, Object... replacements) {
        return net.kyori.adventure.text.Component.text(colorize(get(key, replacements)));
    }
}
