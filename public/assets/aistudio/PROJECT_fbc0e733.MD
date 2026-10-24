# PROJECT.MD — Project Overview

## PhotoStand

> Turn any Android device into a networked digital photo display.
> Control it from another Android device — no cloud, no account, no friction.

---

## Module Structure

```
photostand/
├── :app                  # entry point, mode selection (Display / Controller)
├── :core                 # shared models, interfaces, constants
├── :display              # fullscreen display mode
│   ├── DisplayViewModel
│   ├── DisplayScreen (Compose, fullscreen)
│   └── ImageReceiveServer (Ktor)
├── :controller           # controller mode
│   ├── DeviceListScreen
│   ├── PairingScreen (QR scan)
│   └── ShareTargetActivity
└── :p2p                  # transport layer (berdiri sendiri)
    ├── discovery/
    │   ├── MdnsDiscovery      (WiFi — NsdManager)
    │   └── BlePresence        (BLE — hanya presence beacon)
    ├── transfer/
    │   ├── TcpTransfer        (WiFi — Ktor/OkHttp)
    │   └── WifiDirectTransfer (tanpa WiFi router)
    └── pairing/
        ├── QrPairingGenerator (Display side)
        ├── QrPairingScanner   (Controller side)
        └── PairingStore       (simpan registered controllers)
```

---

## Pairing Flow

```
[Display]                        [Controller]
generate keypair
tampil QR ──────────────────────► scan QR
                                  simpan {deviceId, pubKey, label}
                ◄─── handshake (sign challenge) ───►
                      registered ✓
```

Selanjutnya: controller auto-discover display via mDNS/BLE.

---

## Image Transfer Flow

```
[Controller]
Share Sheet → pilih device dari list
  → encode gambar (JPEG/WebP, kompres jika >2MB)
  → encrypt payload (AES-GCM, key dari pairing)
  → kirim via TCP (mDNS) atau WiFi Direct

[Display]
  → terima → decrypt → decode
  → animasi load (Tile / Paper Screen)
  → tampil fullscreen
```

---

## Loading Animations

| Mode | Deskripsi |
|---|---|
| **Tile** | Gambar di-grid, render tile kiri→kanan, atas→bawah |
| **Paper Screen** | Blank → grayscale → full color (crossfade bertahap) |

---

## Brightness & Display Settings

- Manual brightness slider (bypass system)
- Auto-dim berdasarkan jam (misal: 23:00–07:00 → 20%)
- Opsi ambient light sensor
- Fit mode: Fill / Contain / Blur Background

---

## Key Android APIs Used

| API | Kegunaan |
|---|---|
| `NsdManager` | mDNS discovery |
| `WifiP2pManager` | WiFi Direct |
| `BluetoothLeAdvertiser / Scanner` | BLE presence |
| `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` | Jaga layar nyala |
| `Settings.System.SCREEN_BRIGHTNESS` | Override brightness |
| `ShareTarget` (shortcuts.xml) | Muncul di share sheet |
| `CameraX` | Scan QR pairing |
