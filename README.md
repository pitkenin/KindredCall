# KindredCall: A Personal Solution for Elderly Connection

Commercial communication apps (WhatsApp, FaceTime, Telegram) are built for digital natives. For my grandmother, menus, contact lists, and scrolling chat feeds were insurmountable technical barriers. When I couldn't find an app that was truly "zero-navigation," I decided to take matters into my own hands and build **KindredCall** from scratch.

KindredCall is a full-stack project (Android + Node.js) designed with one goal: making it as easy as humanly possible for an elderly person to video call and share life moments with their family, even in restrictive network environments.

## 🧠 The Philosophy: "Zero-Navigation"

The "Grandma" side of the app has exactly **zero** menus. No tabs, no settings, no contact lists.
- **One Screen**: The main interface is the only interface.
- **One Purpose**: Large, high-visibility buttons for the two core actions: Calling and Sharing.
- **High Visibility**: Massive typography (80sp+) and high-contrast colors ensure accessibility for users with vision impairments.

<img src="screenshot.png" alt="screenshot image" width="500">

## ✨ Core Features

### 1. Bulletproof Full-Screen Calling
Traditional notifications are easy to miss or accidentally swipe away. KindredCall uses "Full Screen Intents" and specialized logic to ensure that when a call comes in, it **takes over the screen immediately**, regardless of whether the phone is locked or unlocked.

- **Focus Hijack**: An "immediate cancellation" path kills the system notification the millisecond the Activity launches, preventing heads-up banners from overlapping the call UI.
- **OEM-Bypass**: Specialized handling for Xiaomi/Redmi devices, whose background-start restrictions and aggressive process management break the standard approach.

### 2. The "Latest-Only" Photo Frame
Unlike chat apps that bury photos in a feed, KindredCall features a persistent live photo frame.
- **Simplicity**: It only displays the **one most recent photo** shared by the other person.
- **Automatic Sync**: When a new photo is uploaded, a message over the signaling WebSocket tells the other device to refresh — no manual pull required.

### 3. One-Tap Share
Sharing a photo can be intimidating. The grandma flavor has a one-tap path: she presses a single large button, and the app pulls the **most recent photo from her camera roll** and sends it.

### 4. Network Resilience
Built for networks where standard VoIP is throttled or blocked by Deep Packet Inspection. Every hop is designed to be indistinguishable from ordinary HTTPS traffic:

- **Single-port surface**: signaling (WSS), the gallery API (HTTPS), and TURN relay (TURNS) all arrive on **port 443** behind one domain. nginx's `stream` module demultiplexes by TLS SNI, routing TURN traffic to coturn and everything else to the application backend.
- **Dual TURN transports**: `turn:` over UDP/3478 for media quality where it's available, `turns:` over TLS/TCP 443 as a DPI-resistant fallback. ICE selects whichever path survives.
- **Endpoint failover**: the client holds a list of signaling and TURN hostnames and rotates through them on connection failure.
- **Relocatable by design**: all endpoints are hostnames with a short DNS TTL, never baked-in IPs. If a host is blocked or has to move, the server relocates and deployed devices follow automatically — no reinstall, no physical access required. This constraint drove much of the architecture: the device is deployed abroad and cannot be serviced in person.

### 5. Operational Monitoring
The signaling server tracks per-device presence and pushes an alert (via [ntfy](https://ntfy.sh)) when a device has been unreachable beyond a threshold. For an unattended deployment, *knowing* something has broken is most of the problem.

### 6. External Automation (Intents)

KindredCall broadcasts global intents for integration with automation tools like MacroDroid or Tasker.

- **`com.kindredcall.CALL_ACTIVE`**: Broadcast when a call starts (outgoing) or when an incoming call is received (ringing).
- **`com.kindredcall.CALL_ENDED`**: Broadcast when a call is disconnected, declined, or finished.

## 🛠️ Technical Deep Dive

### Android (Kotlin / Jetpack Compose)
- **WebRTC**: native audio/video engine, relay-capable for restrictive NAT.
- **Signaling**: custom OkHttp WebSocket client with heartbeat, automatic reconnection, and endpoint rotation.
- **Product flavors**: `grandma` and `yulia` share one codebase, differing in UI affordances and role identity.
- **Modern stack**: 100% Jetpack Compose, Kotlin Coroutines and Flows.

### Backend (Node.js)
- **Signaling server**: `ws`-based relay with ping/pong liveness detection and dead-socket reaping.
- **Gallery API**: minimalist Express + multer REST API for multipart uploads and serving the latest image.
- **Auth**: shared bearer token on both the WebSocket upgrade and every REST call. Deliberately minimal — this is a two-device family deployment, not a multi-tenant service. The token's job is to keep internet background noise from reaching the devices.
- **Infrastructure**: Oracle Cloud VPS, nginx (TLS termination + SNI stream routing), coturn (TURN/STUN), PM2 for process supervision, Let's Encrypt with automated renewal hooks.

## 🚀 Setup

### Backend

```bash
cd backend
npm install
cp ecosystem.config.example.js ecosystem.config.js
# edit ecosystem.config.js: set KINDRED_TOKEN and NTFY_TOPIC
pm2 start ecosystem.config.js
pm2 save
```

Generate a token with `openssl rand -hex 32`. The server binds to `127.0.0.1` and expects a TLS-terminating reverse proxy in front of it.

### Android

Create `app/local.properties`:

```properties
SERVER_HOSTS=app.example.com,alt.example.com
TURN_HOSTS=turn.example.com,turn2.example.com
TURN_USER=your_turn_user
TURN_PASS=your_turn_password
SHARED_TOKEN=same_token_as_the_backend
```

Comma-separated lists are tried in order on connection failure. Then build the flavor you need:

```bash
./gradlew assembleGrandmaRelease
./gradlew assembleYuliaRelease
```

## 🤖 Built with AI Collaboration
This project was developed using **Android Studio with AI-assisted orchestration**. I focused on the UX architecture, OEM-specific bug hunting, and network strategy, while leveraging AI to rapidly iterate on lower-level protocols and localization.

---
*Developed as a labor of love to keep my family connected.*
