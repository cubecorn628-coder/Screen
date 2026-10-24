# PROGRES.MD — Checklist Kemajuan

Status: `[ ]` belum · `[~]` in progress · `[x]` done

---

## Phase 0 — Setup Project

- [x] Init repo + arsitektur modular (`core`, `p2p`, `display`, `controller`, `ui`)
- [x] Setup version catalog (`libs.versions.toml`)
- [x] Setup baseline build config (minSdk 24, targetSdk 36, custom applicationId, adaptive icon)

---

## Phase 1 — Core & Pairing

- [x] Definisi interface publik di `:core`
  - [x] `DisplayDevice` data class & JSON serializer
  - [x] `PairedController` data class & JSON serializer
  - [x] `TransferResult` & `TransferMetadata`
- [x] QR Pairing — Display side (generate QR matrix bitmap + pairing token)
- [x] QR Pairing — Controller side (scan/paste token + simpan ke PairingStore)
- [x] PairingStore (persistent SharedPreferences + reactive StateFlow)
- [x] Handshake & verifikasi key (HMAC-SHA256 & AES-GCM challenge)

---

## Phase 2 — P2P Transport

- [x] mDNS Discovery (`NsdManager`)
  - [x] Register service dari Display (`_photostand._tcp.`)
  - [x] Discover dari Controller & resolve host/port
- [x] BLE Presence
  - [x] Advertise beacon dari Display dengan Service UUID
  - [x] Scan dari Controller dengan RSSI proximity
- [x] TCP Transfer (kirim gambar via WiFi)
  - [x] HTTP/TCP Server di Display (listening on port 9876, ping, handshake, transfer)
  - [x] OkHttp client di Controller dengan timeout 30 detik
- [x] WiFi Direct Transfer (fallback tanpa router via `WifiP2pManager`)
- [x] Enkripsi payload (AES-256-GCM + IV + Auth tag)

---

## Phase 3 — Display Mode

- [x] DisplayScreen (fullscreen Compose)
  - [x] Keep screen on (`FLAG_KEEP_SCREEN_ON`)
  - [x] Hide system bars (`WindowInsetsControllerCompat`)
- [x] ImageReceiveServer (terima + decrypt + verifikasi auth)
- [x] Animasi load: **Tile** (grid staggered reveal)
- [x] Animasi load: **Paper Screen** (blank → grayscale → full color)
- [x] Brightness control (manual slider + window attribute override)
- [x] Auto-dim by schedule (23:00–07:00 malam otomatis dim ke tingkat minimum)
- [x] Fit mode: Fill (Crop) / Contain (Letterbox) / Blur Background
- [x] Clock overlay (jam digital, tanggal, nama perangkat, status WiFi)

---

## Phase 4 — Controller Mode

- [x] DeviceListScreen (daftar paired displays)
  - [x] Status online/offline per device (periodic ping check)
- [x] Share sheet integration (`Intent.ACTION_SEND` image/*)
  - [x] Terima intent gambar dari aplikasi luar
  - [x] Pilih target display jika >1
  - [x] Kirim via P2P
- [x] Picker bottom sheet & Android Photo Picker (zero-permission)
- [x] Rename & forget device dari list

---

## Phase 5 — Polish

- [x] Onboarding flow (mode selection: Display vs Controller saat pertama buka)
- [x] Switch mode kapan saja dari top bar / settings
- [x] Empty state yang layak (belum ada paired device)
- [x] Error handling: device offline, transfer timeout 30s dengan pesan informatif
- [x] Dark mode support penuh dengan palette Slate & Sky M3 yang elegan
- [x] Custom adaptive app icon (PhotoStand display frame)
