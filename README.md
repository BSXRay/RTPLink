# 🌐 RTPLink — Random Teleport System

> **Geo-aware random teleportation for Velocity Proxy networks.**  
> Connects Minecraft servers via TCP for a seamless, location-based RTP experience.

---

## 📋 Table of Contents

- [Overview](#-overview)
- [Architecture](#-architecture)
- [Installation](#-installation)
- [Configuration](#-configuration)
- [Commands](#-commands)
- [Permissions](#-permissions)
- [Geo-Location System](#-geo-location-system)
- [FAQ](#-faq)

---

## 🔍 Overview

**RTPLink** is an advanced random teleport plugin for **Velocity Proxy** that uses a custom **TCP communication protocol** to intelligently teleport players across multiple backend servers — based on geographic location data.

### ✨ Feature Highlights

| Feature | Description |
|---|---|
| 🌍 **Geo-based RTP** | Teleports players preferably to servers in their region |
| 🔗 **TCP Bridge** | Real-time communication between Velocity and backend servers |
| 🏠 **Home System** | Multiple named homes per player |
| 🤝 **TPA System** | Teleport requests with accept / decline |
| 🗺️ **Visit System** | Directly visit players on other servers |
| 🔧 **Fully Configurable** | Aliases, cooldowns, limits via `config.yml` |

---

## 🏗️ Architecture

RTPLink consists of **two components** that work together:

```
┌─────────────────────────────────────────────────────┐
│                   VELOCITY PROXY                    │
│                                                     │
│   ┌──────────────────────────────────────────┐      │
│   │         rtplink-*.jar (Plugin)           │      │
│   │   • Command Handling                     │      │
│   │   • Geo-Location Routing                 │      │
│   │   • TCP Server (Port 25577)              │      │
│   └─────────────────┬────────────────────────┘      │
└─────────────────────│───────────────────────────────┘
                      │  TCP Connection
          ┌───────────┼────────────┐
          │           │            │
┌─────────▼──┐  ┌─────▼──────┐  ┌─▼──────────┐
│  Server A  │  │  Server B  │  │  Server C  │
│  helper    │  │  helper    │  │  helper    │
│  *.jar     │  │  *.jar     │  │  *.jar     │
└────────────┘  └────────────┘  └────────────┘
   [EU-DE]         [US-East]       [GB-Lon]
```

> **Important:** Every backend server requires the `rtplink-helper-*.jar`. All communication runs through the configured TCP port.

---

## 🚀 Installation

### Step 1 — Velocity Plugin

```bash
# Copy the JAR into the Velocity plugins folder
cp rtplink-*.jar /path/to/velocity/plugins/
```

### Step 2 — Backend Helper

```bash
# Deploy the helper JAR to EVERY backend server
cp rtplink-helper-*.jar /path/to/server/plugins/
```

### Step 3 — Restart

```
1. Restart all backend servers
2. Restart the Velocity proxy
3. TCP connections are established automatically
```

### Step 4 — Verify

```
/vrtplink list
```

> Lists all connected servers and their players. Green entries = successfully connected. ✅

---

## ⚙️ Configuration

### `config.yml` — Main Configuration

```yaml
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
```

### `servers.yml` — Geo-Location Mapping

```yaml
# ──────────────────────────────────────────────
#  Server Geo-Location Configuration
# ──────────────────────────────────────────────

servers:
  survival-eu:
    location: DE         # ISO 3166-1 Alpha-2 code
    display_name: "EU Survival"
    priority: 1          # Higher = preferred when region matches

  survival-us:
    location: US
    display_name: "US Survival"
    priority: 1

  survival-gb:
    location: GB
    display_name: "UK Survival"
    priority: 2

# Fallback if no matching region is found
fallback_server: survival-eu
```

---

## 💬 Commands

### Player Commands

| Command | Description | Example |
|---|---|---|
| `/rtp` | Random teleport (geo-optimized) | `/rtp` |
| `/rtp local` | Random teleport on the current server | `/rtp local` |
| `/sethome <name>` | Save a home at your current position | `/sethome base` |
| `/home [name]` | Teleport to a saved home | `/home base` |
| `/delhome <name>` | Delete a saved home | `/delhome base` |
| `/tpa <player>` | Send a teleport request to a player | `/tpa Steve` |
| `/tpa <player> here` | Request a player to teleport to you | `/tpa Steve here` |
| `/tpaaccept` | Accept an incoming TPA request | `/tpaaccept` |
| `/tpadecline` | Decline an incoming TPA request | `/tpadecline` |
| `/visit [server]` | Visit a server directly | `/visit survival-eu` |
| `/location <code>` | Set your region | `/location DE` |
| `/getlocation` | Display your current region | `/getlocation` |

---

### Admin Commands (`/vrtplink`)

> Requires the `rtplink.admin` permission

| Command | Description | Example |
|---|---|---|
| `/vrtplink reload` | Reload all configuration files | `/vrtplink reload` |
| `/vrtplink list` | List all servers and connected players | `/vrtplink list` |
| `/vrtplink getlocation <player>` | Show a player's current location | `/vrtplink getlocation Steve` |
| `/vrtplink changelocation <player> <code>` | Change a player's location | `/vrtplink changelocation Steve US` |

---

## 🔐 Permissions

```
rtplink.*                    → All permissions (wildcard)
  │
  ├── rtplink.admin          → Access to /vrtplink admin commands
  │
  ├── rtplink.rtp            → /rtp and /rtp local
  ├── rtplink.location       → /location and /getlocation
  ├── rtplink.sethome        → /sethome
  ├── rtplink.home           → /home and /delhome
  ├── rtplink.tpa            → /tpa, /tpaaccept, /tpadecline
  └── rtplink.visit          → /visit
```

### Recommended LuckPerms Setup

```bash
# Default players
lp group default permission set rtplink.rtp true
lp group default permission set rtplink.location true
lp group default permission set rtplink.sethome true
lp group default permission set rtplink.home true
lp group default permission set rtplink.tpa true
lp group default permission set rtplink.visit true

# Admins
lp group admin permission set rtplink.admin true
```

---

## 🌍 Geo-Location System

The geo system uses **ISO 3166-1 Alpha-2** country codes and automatically routes players to the nearest/best-matching server.

### Supported Location Codes

| Code | Region |
|---|---|
| `DE` | Germany |
| `AT` | Austria |
| `CH` | Switzerland |
| `US` | United States |
| `GB` | United Kingdom |
| `FR` | France |
| `NL` | Netherlands |
| `PL` | Poland |
| *(all ISO 3166-1 codes)* | *Extendable via `servers.yml`* |

### Routing Logic

```
Player types /rtp
       │
       ▼
Location known?
   ├── YES → Find server with matching location code
   │          ├── Found?     → Teleport to random position on that server
   │          └── Not found? → Use fallback server from config.yml
   └── NO  → Use fallback server
```

> **Tip:** Players can set their region manually with `/location <code>`, or admins can override it with `/vrtplink changelocation <player> <code>`.

---

## ❓ FAQ

<details>
<summary><strong>TCP connection fails</strong></summary>

Check the following:
1. Port `25577` is open in your firewall
2. The same port is set in `config.yml` on **both** Velocity and the helper config
3. Backend servers are fully started before Velocity connects

</details>

<details>
<summary><strong>RTP always teleports to the same server</strong></summary>

Review your `servers.yml` — make sure multiple servers are configured and that each `location` code matches the player's set location.

</details>

<details>
<summary><strong>Commands are not recognized</strong></summary>

After changing aliases in `config.yml`, run `/vrtplink reload` or perform a full proxy restart for changes to take effect.

</details>

<details>
<summary><strong>Players can't see their homes on other servers</strong></summary>

Homes are stored **proxy-wide** and are accessible across all servers. If issues persist, run `/vrtplink reload`.

</details>

---

## 📦 Downloads & Resources

| Resource | Link |
|---|---|
| 📥 Plugin JAR | *(GitHub Releases)* |
| 📥 Helper JAR | *(GitHub Releases)* |
| 🐛 Bug Reports | *(GitHub Issues)* |
| 💬 Support | *(Discord / GitHub Discussions)* |

---

<div align="center">

**RTPLink** — Made with ❤️ by **bsxray**

*Version 1.21.1 · Velocity Proxy · TCP-powered Geo-RTP*

</div>
