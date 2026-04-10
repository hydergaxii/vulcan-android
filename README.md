# 🔥 VULCAN — Android Self-Hosting Platform

> *"Your Android. Your Rules. Your Stack."*  
> The Greatest Self-Hosting Platform Ever Built for a Pocket Device.

---

## WHAT IS VULCAN?

Vulcan lets you run self-hosted web apps (LibreChat, n8n, Gitea, Vaultwarden, and 60+ more) directly on your Android phone — with beautiful home screen icons, zero root required, and data you can see and touch in any file manager.

No PC. No cloud. No subscriptions. Your phone IS the server.

---

## PROJECT STRUCTURE

```
app/src/main/kotlin/com/vulcan/app/
│
├── VulcanApp.kt                    Application class (@HiltAndroidApp)
├── MainActivity.kt                 Compose entry point + navigation
│
├── data/
│   ├── model/Models.kt             Domain models (AppManifest, InstalledApp, etc.)
│   ├── database/                   Room DB (entities, DAOs, VulcanDatabase)
│   └── repository/AppRepository.kt Unified data layer for UI
│
├── runtime/
│   ├── RuntimeCatalog.kt           All supported runtimes + download URLs
│   ├── RuntimeEngine.kt            Native + PRoot process launcher ← CORE
│   ├── RuntimeDownloader.kt        HTTP download + tar.gz/zip extraction
│   └── PortManager.kt + InternalMesh  Port authority + app-to-app DNS
│
├── security/
│   ├── SecretsVault.kt             AES-256-GCM secrets via Android Keystore
│   └── SecurityLayer.kt            ManifestVerifier + DashboardAuth + AuditLogger
│
├── network/
│   ├── VulcanProxy.kt              Reverse proxy (HTTP + WebSocket tunnel)
│   └── NetworkLayer.kt             mDNSAdvertiser + VulcanConnect (CF/WG)
│
├── monitoring/
│   └── MetricsCollector.kt         /proc/-based CPU/RAM + Room storage
│
├── service/
│   ├── VulcanCoreService.kt        Foreground service heartbeat
│   └── ServiceLayer.kt             BootReceiver + DozeWorkaround + Watchdog
│
├── icon/IconResolver.kt            7-tier icon pipeline (P1 manifest → P7 bundled)
├── launcher/VulcanLauncherBridge.kt Slot + shortcut + SlotActivity
├── storage/StorageManager.kt       All paths → /sdcard/Vulcan/
├── backup/BackupManager.kt         Local zip backup + restore
├── store/StoreRepository.kt        Registry fetch + verify + cache
├── web/VulcanWebServer.kt          NanoHTTPD dashboard on port 7777
├── di/VulcanModule.kt              Hilt bindings for all components
│
└── ui/
    ├── theme/VulcanTheme.kt        Material 3 + Vulcan colors (orange #FF6B35)
    ├── components/Components.kt    AppCard, StatusBadge, LogViewer, MetricsBar
    ├── screens/Screens.kt          Dashboard, Store, Network, Metrics, Settings, Wizard
    └── viewmodel/MainViewModel.kt  Central ViewModel
```

---

## BUILDING

### Prerequisites
- Android Studio Meerkat (2024.3+)
- JDK 17
- Android SDK 35

### First Build
```bash
git clone https://github.com/your-org/vulcan-android.git
cd vulcan-android
./gradlew assembleDebug
```

### Install to device
```bash
./gradlew installDebug
```

### Release build (signed APK)
```bash
./gradlew assembleRelease
```

---

## ARCHITECTURE

### The Three Permission Tiers

| Tier   | What it unlocks | How to activate |
|--------|----------------|-----------------|
| NORMAL | Run apps, view logs, web dashboard, backups | Nothing — default |
| ADB    | Silent home screen icons, OOM protection, slot mode | One-time wireless ADB pairing (Android 11+) |
| ROOT   | Everything + WireGuard, full system integration | Root binary |

### Storage Layout

```
/sdcard/Vulcan/
├── apps/{appId}/
│   ├── source/        App source code (re-downloadable)
│   ├── data/          App runtime data (backed up)
│   ├── logs/          Daily rotating logs
│   └── .env           Plain config (non-sensitive)
├── runtimes/
│   ├── native/        node, bun, python, go, java, php
│   └── proot/distros/ ubuntu-22.04, debian-12, alpine
├── vault/secrets.enc  AES-256-GCM encrypted secrets
├── backups/           Zip backups
└── logs/vulcan.log    System log
```

### Key Ports

| Port | Service |
|------|---------|
| 7777 | Vulcan Web Dashboard |
| 8080 | VulcanProxy (reverse proxy) |
| 3080 | LibreChat |
| 5678 | n8n |
| 3001 | Uptime Kuma |
| 3000 | Gitea |

---

## DEVELOPMENT PHASES (from Masterplan)

| Phase | Scope | Weeks |
|-------|-------|-------|
| 1 | Foundation: DB, Storage, Service, UI skeleton | 1-2 |
| 2 | Native Runtime: Node.js first | 3-4 |
| 3 | First apps: LibreChat, n8n | 5-6 |
| 4 | PRoot runtime | 7-8 |
| 5 | Network Forge: Proxy, mDNS, Cloudflare | 9-11 |
| 6 | Launcher Bridge: Slots + Shortcuts | 12-13 |
| 7 | Security Layer: Vault, Auth, Manifest Verify | 14-15 |
| 8 | Metrics, Monitoring, Alerts | 16-18 |
| 9 | Backup System: Local + Cloud | 19-21 |
| 10 | Web Dashboard + CLI | 22-25 |
| 11 | App Catalog Expansion (60+ apps) | 26-30 |
| 12 | Beta Launch + Docs + Product Hunt | 31-40 |

---

## THE SIX LAWS

1. **TRANSPARENCY** — Everything visible in /sdcard/Vulcan. No hidden folders.
2. **SELF-SUFFICIENCY** — Zero external app dependencies. Vulcan IS the runtime.
3. **PROGRESSIVE POWER** — Works at Normal. Better at ADB. Best at Root.
4. **CHOICE AT EVERY LAYER** — Native or PRoot. Slot or Shortcut. sdcard or SD card.
5. **THE APP IS THE PLATFORM** — Like Coolify, but in your pocket.
6. **SECURITY BY DEFAULT** — Vault-encrypted secrets. Auth-protected dashboard. Opt-in exposure.

---

## LICENSE

MIT — Open source, forever. See LICENSE file.

---

*The forge is ready. Start hammering.* 🔥
