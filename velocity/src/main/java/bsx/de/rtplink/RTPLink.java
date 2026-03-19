package bsx.de.rtplink;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Plugin(
    id = "rtplink",
    name = "RTPLink",
    version = "1.21.11",
    authors = {"bsxray"},
    description = "Random Teleport System for Velocity Proxy"
)
public class RTPLink implements Runnable {

    public static final String CHANNEL_ID = "rtplink:main";
    private static RTPLink instance;

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxyServer;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private int port = 25577;
    private boolean debug = false;
    private boolean geoEnabled = true;
    private boolean overrideExisting = true;
    private int pingInterval = 30;
    private ServerSocket serverSocket;
    private final Map<String, Socket> connectedServers = new ConcurrentHashMap<>();
    private final Map<String, PrintWriter> serverWriters = new ConcurrentHashMap<>();
    private final Set<String> serversWithHelper = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile boolean running = true;
    private Thread listenerThread;
    
    private RtpConfig defaultConfig;
    private final Map<String, ServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> serverWeights = new ConcurrentHashMap<>();
    private GeoLocationService geoService;
    private HomeManager homeManager;
    private TpaManager tpaManager;
    private MessageManager messageManager;
    private Map<String, List<String>> commandAliases = new HashMap<>();

    @Inject
    public RTPLink() {}

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        instance = this;
        loadConfigs();
        if (overrideExisting) {
            applyConfigToServers();
            saveServersConfig();
        }
        geoService = new GeoLocationService(this);
        homeManager = new HomeManager(this);
        tpaManager = new TpaManager(this);
        messageManager = new MessageManager(this);
        
        proxyServer.getEventManager().register(this, new PlayerJoinListener(this));
        
        registerCommands();
        
        listenerThread = new Thread(this);
        listenerThread.setName("RTPLink-Listener");
        listenerThread.start();
        
        logger.info("========================================");
        logger.info("   RTPLink v1.21.11 enabled!");
        logger.info("   Listening on port: {}", port);
        logger.info("   Debug mode: {}", debug);
        logger.info("   Ping interval: {}s", pingInterval);
        logger.info("   Default world: {}", defaultConfig.world);
        logger.info("   Configured servers: {}", serverConfigs.size());
        logger.info("========================================");
    }
    
    public static RTPLink getInstance() {
        return instance;
    }
    
    public String getPlayerLocation(String playerName) {
        return geoService.getPlayerLocationByName(playerName);
    }
    
    private void registerCommands() {
        registerConfigCommand("vrtplink", new RTPLinkCommand(this));
        registerConfigCommand("rtp", new RtpCommand(this));
        registerConfigCommand("location", new LangChangeCommand(this));
        registerConfigCommand("getlocation", new GetLocationCommand(this));
        registerConfigCommand("sethome", new SetHomeCommand(this));
        registerConfigCommand("home", new HomeCommand(this));
        registerConfigCommand("delhome", new DelHomeCommand(this));
        registerConfigCommand("tpa", new TpaCommand(this));
        registerConfigCommand("tpaaccept", new TpaAcceptCommand(this));
        registerConfigCommand("tpadecline", new TpaDeclineCommand(this));
        registerConfigCommand("visit", new VisitCommand(this));
    }
    
    private void registerConfigCommand(String defaultName, SimpleCommand command) {
        List<String> aliases = commandAliases.get(defaultName);
        if (aliases != null && !aliases.isEmpty()) {
            for (String alias : aliases) {
                proxyServer.getCommandManager().register(alias, command);
            }
        } else {
            proxyServer.getCommandManager().register(defaultName, command);
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            debugLog("Server listening on port " + port);
            
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    handleConnection(client);
                } catch (Exception e) {
                    if (running) {
                        logger.warn("Connection error: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to start RTPLink server: " + e.getMessage());
        }
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            String line = in.readLine();
            
            if (line != null && line.startsWith("REGISTER:")) {
                String serverName = line.substring(9);
                
                registerServer(serverName);
                
                connectedServers.put(serverName, socket);
                serverWriters.put(serverName, out);
                serversWithHelper.add(serverName);
                
                out.println("REGISTERED");
                out.flush();
                
                if (pingInterval > 0) {
                    out.println("PINGINTERVAL:" + pingInterval);
                    out.flush();
                }
                
                logger.info("[RTPLink] Server '{}' connected", serverName);
                
                if (debug) {
                    ServerConfig cfg = serverConfigs.get(serverName);
                    if (cfg != null) {
                        logger.info("[RTPLink] Server '{}' config: world={}, weight={}", 
                            serverName, cfg.world, cfg.weight);
                    } else {
                        logger.info("[RTPLink] Server '{}' using default config", serverName);
                    }
                }
                
                socket.setSoTimeout(0);
                
                new Thread(() -> {
                    try {
                        while (running && !socket.isClosed()) {
                            String msg = in.readLine();
                            if (msg == null) break;
                            
                            if (msg.startsWith("PONG")) {
                                debugLog("Received PONG from {}", serverName);
                            } else if (msg.startsWith("SETHOME:")) {
                                handleSetHome(serverName, msg.substring(8));
                            } else if (msg.startsWith("LOC:")) {
                                handleLocationResponse(serverName, msg.substring(4));
                            } else if (msg.startsWith("GETLOC:")) {
                                handleGetLoc(serverName, msg.substring(7));
                            } else {
                                debugLog("Unknown message from %s: %s", serverName, msg);
                            }
                        }
                    } catch (Exception e) {
                    } finally {
                        disconnectServer(serverName);
                    }
                }).start();
            }
        } catch (Exception e) {
            logger.warn("Failed to handle connection: " + e.getMessage());
        }
    }

    private void disconnectServer(String serverName) {
        connectedServers.remove(serverName);
        serverWriters.remove(serverName);
        serversWithHelper.remove(serverName);
        logger.info("[RTPLink] Server '{}' disconnected", serverName);
    }

    private void handleSetHome(String serverName, String data) {
        String[] parts = data.split(":");
        if (parts.length >= 8) {
            String playerName = parts[0];
            String homeName = parts[1];
            String world = parts[2];
            double x = Double.parseDouble(parts[3]);
            double y = Double.parseDouble(parts[4]);
            double z = Double.parseDouble(parts[5]);
            float yaw = Float.parseFloat(parts[6]);
            float pitch = Float.parseFloat(parts[7]);
            
            homeManager.setHome(playerName, homeName, serverName, world, x, y, z, yaw, pitch);
            debugLog("Player %s set home '%s' at %s,%s,%s on %s", playerName, homeName, x, y, z, serverName);
        }
    }

    private void handleLocationResponse(String serverName, String data) {
        debugLog("handleLocationResponse called: server=%s, data=%s", serverName, data);
        
        String[] parts = data.split(":");
        if (parts.length >= 7) {
            String targetName = parts[0];
            String senderName = parts[1];
            String world = parts[2];
            double x = Double.parseDouble(parts[3]);
            double y = Double.parseDouble(parts[4]);
            double z = Double.parseDouble(parts[5]);
            float yaw = Float.parseFloat(parts[6]);
            float pitch = parts.length > 7 ? Float.parseFloat(parts[7]) : 0f;
            
            debugLog("Received location of %s for %s at %s,%s,%s in world %s", targetName, senderName, x, y, z, world);
            
            String senderCurrentServer = getPlayerCurrentServer(senderName);
            debugLog("Sender %s current server: %s", senderName, senderCurrentServer);
            
            if (senderCurrentServer != null) {
                sendTpaTeleport(senderCurrentServer, senderName, world, x, y, z, yaw, pitch);
            } else {
                debugLog("Could not find sender %s current server", senderName);
            }
        } else {
            debugLog("Invalid LOC data format: %s", data);
        }
    }
    
    private String getPlayerCurrentServer(String playerName) {
        for (Player player : proxyServer.getAllPlayers()) {
            if (player.getUsername().equalsIgnoreCase(playerName)) {
                return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            }
        }
        return null;
    }

    public void sendTpaTeleport(String serverName, String playerName, String world, double x, double y, double z, float yaw, float pitch) {
        debugLog("sendTpaTeleport: server=%s, player=%s, world=%s, x=%s, y=%s, z=%s", 
            serverName, playerName, world, x, y, z);
        
        PrintWriter writer = serverWriters.get(serverName);
        if (writer != null) {
            String message = "TPATEL:" + playerName + ":" + world + ":" + x + ":" + y + ":" + z + ":" + yaw + ":" + pitch;
            writer.println(message);
            writer.flush();
            debugLog("Sent TPA teleport: %s", message);
        } else {
            debugLog("No writer found for server %s", serverName);
        }
    }

    private void handleGetLoc(String serverName, String data) {
        String[] parts = data.split(":");
        if (parts.length >= 2) {
            String targetName = parts[0];
            String senderName = parts[1];
            
            debugLog("Requesting location of %s for %s", targetName, senderName);
            
            PrintWriter writer = serverWriters.get(serverName);
            if (writer != null) {
                writer.println("GETLOC:" + targetName + ":" + senderName);
                writer.flush();
            }
        }
    }

    private void registerServer(String serverName) {
        if (!serverConfigs.containsKey(serverName)) {
            ServerConfig cfg = new ServerConfig();
            cfg.world = defaultConfig.world;
            cfg.minX = defaultConfig.minX;
            cfg.maxX = defaultConfig.maxX;
            cfg.minZ = defaultConfig.minZ;
            cfg.maxZ = defaultConfig.maxZ;
            cfg.location = "";
            serverConfigs.put(serverName, cfg);
            saveServersConfig();
            debugLog("Created new config entry for server '{}'", serverName);
        }
    }

    private synchronized void saveServersConfig() {
        Path serversPath = dataDirectory.resolve("servers.yml");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# RTPLink Server Configuration\n");
            sb.append("# Auto-generated - server entries are created when helpers connect\n");
            sb.append("#\n");
            sb.append("# location options:\n");
            sb.append("#   \"DE\"        - Nur deutsche Spieler\n");
            sb.append("#   \"!DE\"       - Alle außer deutsche Spieler\n");
            sb.append("#   \"DE,AT,CH\"  - Deutsch, Österreich, Schweiz\n");
            sb.append("#   \"\"          - Alle Spieler (kein Filter)\n\n");
            sb.append("servers:\n");
            
            for (Map.Entry<String, ServerConfig> entry : serverConfigs.entrySet()) {
                ServerConfig cfg = entry.getValue();
                sb.append("  ").append(entry.getKey()).append(":\n");
                sb.append("    enabled: ").append(cfg.enabled).append("\n");
                sb.append("    weight: ").append(cfg.weight).append("\n");
                sb.append("    location: \"").append(cfg.location != null ? cfg.location : "").append("\"\n");
                sb.append("    world: \"").append(cfg.world).append("\"\n");
                sb.append("    min-x: ").append(cfg.minX).append("\n");
                sb.append("    max-x: ").append(cfg.maxX).append("\n");
                sb.append("    min-z: ").append(cfg.minZ).append("\n");
                sb.append("    max-z: ").append(cfg.maxZ).append("\n");
            }
            
            Files.writeString(serversPath, sb.toString());
            debugLog("Saved servers.yml with {} entries", serverConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to save servers config: " + e.getMessage());
        }
    }

    public boolean sendRtpToServer(String serverName, byte[] rtpData) {
        PrintWriter writer = serverWriters.get(serverName);
        if (writer != null) {
            String data = Base64.getEncoder().encodeToString(rtpData);
            writer.println("RTP:" + data);
            writer.flush();
            debugLog("Sent RTP to server: " + serverName);
            return true;
        }
        return false;
    }

    public boolean sendSethomeToServer(String serverName, byte[] sethomeData) {
        PrintWriter writer = serverWriters.get(serverName);
        if (writer != null) {
            String data = Base64.getEncoder().encodeToString(sethomeData);
            writer.println("SETHOME:" + data);
            writer.flush();
            debugLog("Sent SETHOME to server: " + serverName);
            return true;
        }
        return false;
    }

    public boolean sendHomeToServer(String serverName, byte[] homeData) {
        PrintWriter writer = serverWriters.get(serverName);
        if (writer != null) {
            String data = Base64.getEncoder().encodeToString(homeData);
            writer.println("HOME:" + data);
            writer.flush();
            debugLog("Sent HOME to server: " + serverName);
            return true;
        }
        return false;
    }

    public boolean sendTpaRequestToServer(String serverName, byte[] tpaData) {
        PrintWriter writer = serverWriters.get(serverName);
        if (writer != null) {
            String data = Base64.getEncoder().encodeToString(tpaData);
            writer.println("TPA:" + data);
            writer.flush();
            debugLog("Sent TPA request to server: " + serverName);
            return true;
        }
        return false;
    }

    public boolean sendToServer(String serverName, String message) {
        PrintWriter writer = serverWriters.get(serverName);
        if (writer != null) {
            writer.println(message);
            writer.flush();
            return true;
        }
        return false;
    }

    public boolean sendToBackend(String message) {
        for (PrintWriter writer : serverWriters.values()) {
            try {
                writer.println(message);
                writer.flush();
            } catch (Exception e) {
            }
        }
        return !serverWriters.isEmpty();
    }

    private void loadConfigs() {
        loadMainConfig();
        loadServersConfig();
    }

    private void loadMainConfig() {
        Path configPath = dataDirectory.resolve("config.yml");
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            if (!Files.exists(configPath)) {
                String defaultConfig = """
                    # RTPLink Configuration
                    # Main settings for the Random Teleport system

                    rtp:
                      world: "world"
                      min-x: -1000
                      max-x: 1000
                      min-z: -1000
                      max-z: 1000

                    # Override server-specific settings with config.yml values on reload
                    # true  = config.yml settings are applied to all servers on /vrtplink reload
                    # false = config.yml is only a template, servers.yml keeps its own values
                    override-existing: true

                    # Enable geo-location based server selection
                    geo-enabled: true

                    # Debug mode - enables verbose logging
                    debug: false

                    # Port for Helper plugin connections (default: 25577)
                    port: 25577

                    # Heartbeat ping interval in seconds (default: 30)
                    # Helpers will ping the proxy at this interval to detect dead connections
                    ping-interval: 30

                    # Command aliases - define which commands are registered
                    # Format: commandname: [alias1, alias2, ...]
                    # Example: tpa: [tpr] -> registers only /tpr (replaces /tpa)
                    # Example: tpa: [tpa, tpr] -> registers /tpa and /tpr
                    aliases:
                      vrtplink: [vrtplink]
                      rtp: [rtp]
                      sethome: [sethome]
                      home: [home]
                      delhome: [delhome]
                      tpa: [tpa]
                      tpaaccept: [tpaaccept]
                      tpadecline: [tpadecline]
                      visit: [visit]
                      location: [location]
                    """;
                Files.writeString(configPath, defaultConfig);
            }
            
            String content = Files.readString(configPath);
            defaultConfig = parseMainConfig(content);
            parseAliases(content);
            
            port = defaultConfig.port;
            debug = defaultConfig.debug;
            geoEnabled = defaultConfig.geoEnabled;
            overrideExisting = defaultConfig.overrideExisting;
            
            debugLog("Loaded main config - debug={}, port={}", debug, port);
        } catch (Exception e) {
            logger.error("Failed to load config: " + e.getMessage());
            defaultConfig = new RtpConfig();
        }
    }

    private RtpConfig parseMainConfig(String content) {
        RtpConfig cfg = new RtpConfig();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("debug:")) {
                cfg.debug = Boolean.parseBoolean(line.substring("debug:".length()).trim());
            } else if (line.startsWith("port:")) {
                cfg.port = Integer.parseInt(line.substring("port:".length()).trim());
            } else if (line.startsWith("geo-enabled:")) {
                cfg.geoEnabled = Boolean.parseBoolean(line.substring("geo-enabled:".length()).trim());
            } else if (line.startsWith("override-existing:")) {
                cfg.overrideExisting = Boolean.parseBoolean(line.substring("override-existing:".length()).trim());
            } else if (line.startsWith("ping-interval:")) {
                pingInterval = Integer.parseInt(line.substring("ping-interval:".length()).trim());
            } else if (line.startsWith("world:")) {
                cfg.world = line.substring("world:".length()).trim().replace("\"", "").replace("'", "");
            } else if (line.startsWith("min-x:")) {
                cfg.minX = Integer.parseInt(line.substring("min-x:".length()).trim());
            } else if (line.startsWith("max-x:")) {
                cfg.maxX = Integer.parseInt(line.substring("max-x:".length()).trim());
            } else if (line.startsWith("min-z:")) {
                cfg.minZ = Integer.parseInt(line.substring("min-z:".length()).trim());
            } else if (line.startsWith("max-z:")) {
                cfg.maxZ = Integer.parseInt(line.substring("max-z:".length()).trim());
            }
        }
        return cfg;
    }

    private void parseAliases(String content) {
        commandAliases.clear();
        boolean inAliasesSection = false;
        String currentCommand = "";
        
        for (String line : content.split("\n")) {
            line = line.trim();
            
            if (line.equals("aliases:")) {
                inAliasesSection = true;
                continue;
            }
            
            if (inAliasesSection) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                if (!line.contains(":") && !line.startsWith("-")) {
                    inAliasesSection = false;
                    continue;
                }
                
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    currentCommand = parts[0].trim();
                    String aliasesStr = parts[1].trim();
                    List<String> aliases = new ArrayList<>();
                    
                    aliasesStr = aliasesStr.replace("[", "").replace("]", "");
                    for (String alias : aliasesStr.split(",")) {
                        String a = alias.trim();
                        if (!a.isEmpty()) {
                            aliases.add(a);
                        }
                    }
                    
                    if (!aliases.isEmpty()) {
                        commandAliases.put(currentCommand, aliases);
                    }
                }
            }
        }
        
        debugLog("Loaded %d command aliases", commandAliases.size());
    }

    private void loadServersConfig() {
        Path serversPath = dataDirectory.resolve("servers.yml");
        try {
            if (!Files.exists(serversPath)) {
                Files.writeString(serversPath, "# RTPLink Server Configuration\n# Auto-generated - server entries are created when helpers connect\n\nservers:\n");
            }
            
            String content = Files.readString(serversPath);
            parseServersConfig(content);
            
            debugLog("Loaded {} server configurations", serverConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to load servers config: " + e.getMessage());
        }
    }

    private void parseServersConfig(String content) {
        serverConfigs.clear();
        serverWeights.clear();
        
        String currentServer = null;
        ServerConfig cfg = null;
        boolean inServersBlock = false;
        
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            
            if (trimmed.equals("servers:")) {
                inServersBlock = true;
                continue;
            }
            
            if (inServersBlock && trimmed.startsWith("#")) {
                continue;
            }
            
            if (inServersBlock && trimmed.contains(":") && !trimmed.startsWith("enabled:") && 
                !trimmed.startsWith("weight:") && !trimmed.startsWith("location:") &&
                !trimmed.startsWith("world:") &&
                !trimmed.startsWith("min-x:") && !trimmed.startsWith("max-x:") &&
                !trimmed.startsWith("min-z:") && !trimmed.startsWith("max-z:")) {
                
                if (cfg != null && currentServer != null) {
                    serverConfigs.put(currentServer, cfg);
                    serverWeights.put(currentServer, new AtomicInteger(cfg.weight));
                }
                
                currentServer = trimmed.substring(0, trimmed.indexOf(':')).trim();
                cfg = new ServerConfig();
            } else if (cfg != null && currentServer != null) {
                if (trimmed.startsWith("enabled:")) {
                    cfg.enabled = Boolean.parseBoolean(trimmed.substring("enabled:".length()).trim());
                } else if (trimmed.startsWith("weight:")) {
                    cfg.weight = Integer.parseInt(trimmed.substring("weight:".length()).trim());
                } else if (trimmed.startsWith("location:")) {
                    cfg.location = trimmed.substring("location:".length()).trim().replace("\"", "").replace("'", "");
                } else if (trimmed.startsWith("world:")) {
                    cfg.world = trimmed.substring("world:".length()).trim().replace("\"", "").replace("'", "");
                } else if (trimmed.startsWith("min-x:")) {
                    cfg.minX = Integer.parseInt(trimmed.substring("min-x:".length()).trim());
                } else if (trimmed.startsWith("max-x:")) {
                    cfg.maxX = Integer.parseInt(trimmed.substring("max-x:".length()).trim());
                } else if (trimmed.startsWith("min-z:")) {
                    cfg.minZ = Integer.parseInt(trimmed.substring("min-z:".length()).trim());
                } else if (trimmed.startsWith("max-z:")) {
                    cfg.maxZ = Integer.parseInt(trimmed.substring("max-z:".length()).trim());
                }
            }
        }
        
        if (cfg != null && currentServer != null) {
            serverConfigs.put(currentServer, cfg);
            serverWeights.put(currentServer, new AtomicInteger(cfg.weight));
        }
    }

    private void debugLog(String format, Object... args) {
        if (debug) {
            logger.info("[RTPLink-Debug] " + String.format(format, args));
        }
    }

    public void debugLogDirect(String format, Object... args) {
        if (debug) {
            logger.info("[RTPLink-Debug] " + String.format(format, args));
        }
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public GeoLocationService getGeoService() {
        return geoService;
    }

    public HomeManager getHomeManager() {
        return homeManager;
    }

    public TpaManager getTpaManager() {
        return tpaManager;
    }

    public Set<String> getHelperServers() {
        return serversWithHelper;
    }

    public boolean hasHelper(String serverName) {
        return serversWithHelper.contains(serverName);
    }

    public ServerConfig getServerConfig(String serverName) {
        return serverConfigs.getOrDefault(serverName, null);
    }

    public Map<String, ServerConfig> getServerConfigs() {
        return serverConfigs;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public RtpConfig getDefaultConfig() {
        return defaultConfig;
    }

    public RtpConfig getEffectiveConfig(String serverName) {
        ServerConfig serverCfg = serverConfigs.get(serverName);
        if (serverCfg != null && serverCfg.enabled) {
            return new RtpConfig(serverCfg);
        }
        return defaultConfig;
    }

    public boolean isServerEnabled(String serverName) {
        ServerConfig cfg = serverConfigs.get(serverName);
        if (cfg == null) {
            return true;
        }
        return cfg.enabled;
    }

    public List<String> getEnabledServersWithHelper() {
        List<String> enabled = new ArrayList<>();
        for (String server : serversWithHelper) {
            if (isServerEnabled(server)) {
                enabled.add(server);
            }
        }
        return enabled;
    }

    public List<String> getEnabledServersForLocation(String playerLocation) {
        List<String> matching = new ArrayList<>();
        debugLog("getEnabledServersForLocation called with playerLocation='%s', total servers with helper: %d", 
            playerLocation, serversWithHelper.size());
        
        for (String server : serversWithHelper) {
            debugLog("  Checking server: '%s'", server);
            if (!isServerEnabled(server)) {
                debugLog("    - disabled, skipping");
                continue;
            }
            ServerConfig cfg = serverConfigs.get(server);
            String serverLoc = cfg != null ? cfg.location : "";
            boolean matches = geoService.matchesLocation(serverLoc, playerLocation);
            debugLog("    - serverLoc='%s', matches=%s", serverLoc, matches);
            if (matches) {
                matching.add(server);
            }
        }
        debugLog("Found %d servers matching location '%s'", matching.size(), playerLocation);
        return matching;
    }

    public String getRandomServerForPlayer(Player player) {
        String playerLocation = geoService.getPlayerLocation(player);
        debugLog("Selecting server for player {} geoEnabled={}, playerLocation='{}'", 
            player.getUsername(), geoEnabled, playerLocation);
        
        List<String> candidates = new ArrayList<>();
        
        if (geoEnabled && playerLocation != null) {
            candidates = getEnabledServersForLocation(playerLocation);
        }
        
        if (candidates.isEmpty()) {
            candidates = getEnabledServersWithHelper();
            debugLog("Using {} enabled servers (fallback - geoEnabled={}, playerLocation='{}')", 
                candidates.size(), geoEnabled, playerLocation);
        }
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        int totalWeight = 0;
        for (String server : candidates) {
            ServerConfig cfg = serverConfigs.get(server);
            totalWeight += cfg != null ? cfg.weight : 1;
        }
        
        int randomValue = new Random().nextInt(totalWeight);
        int currentWeight = 0;
        
        for (String server : candidates) {
            ServerConfig cfg = serverConfigs.get(server);
            currentWeight += cfg != null ? cfg.weight : 1;
            if (randomValue < currentWeight) {
                debugLog("Selected server: {} (weight: {})", server, cfg != null ? cfg.weight : 1);
                return server;
            }
        }
        
        return candidates.get(0);
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isGeoEnabled() {
        return geoEnabled;
    }

    public Logger getLogger() {
        return logger;
    }

    public ProxyServer getServer() {
        return proxyServer;
    }

    public void reload() {
        loadConfigs();
        if (defaultConfig.overrideExisting) {
            applyConfigToServers();
            saveServersConfig();
        }
        if (messageManager != null) {
            messageManager.loadMessages();
        }
        broadcastPingInterval();
        debugLog("Configs and messages reloaded");
    }

    private void broadcastPingInterval() {
        if (pingInterval > 0) {
            for (Map.Entry<String, PrintWriter> entry : serverWriters.entrySet()) {
                try {
                    entry.getValue().println("PINGINTERVAL:" + pingInterval);
                    entry.getValue().flush();
                    debugLog("Sent PINGINTERVAL to {}", entry.getKey());
                } catch (Exception e) {
                    logger.warn("Failed to send PINGINTERVAL to server: " + entry.getKey());
                }
            }
        }
    }
    
    private void applyConfigToServers() {
        for (Map.Entry<String, ServerConfig> entry : serverConfigs.entrySet()) {
            ServerConfig cfg = entry.getValue();
            cfg.world = defaultConfig.world;
            cfg.minX = defaultConfig.minX;
            cfg.maxX = defaultConfig.maxX;
            cfg.minZ = defaultConfig.minZ;
            cfg.maxZ = defaultConfig.maxZ;
        }
        debugLog("Applied config.yml settings to all servers");
    }

    public int broadcastReload() {
        int sent = 0;
        for (Map.Entry<String, PrintWriter> entry : serverWriters.entrySet()) {
            try {
                entry.getValue().println("RELOAD");
                entry.getValue().flush();
                sent++;
                debugLog("Sent RELOAD to server: " + entry.getKey());
            } catch (Exception e) {
                logger.warn("Failed to send RELOAD to server: " + entry.getKey());
            }
        }
        return sent;
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }

    public static class RtpConfig {
        public boolean debug = false;
        public boolean geoEnabled = true;
        public boolean overrideExisting = true;
        public int port = 25577;
        public String world = "world";
        public int minX = -1000;
        public int maxX = 1000;
        public int minZ = -1000;
        public int maxZ = 1000;
        
        public RtpConfig() {}
        
        public RtpConfig(ServerConfig cfg) {
            this.world = cfg.world;
            this.minX = cfg.minX;
            this.maxX = cfg.maxX;
            this.minZ = cfg.minZ;
            this.maxZ = cfg.maxZ;
        }
    }

    public static class ServerConfig {
        public boolean enabled = true;
        public int weight = 1;
        public String location = "";
        public String world = "world";
        public int minX = -1000;
        public int maxX = 1000;
        public int minZ = -1000;
        public int maxZ = 1000;
    }
}
