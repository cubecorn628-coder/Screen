# PROGRES.MD — Checklist Kemajuan

Status: `[ ]` belum · `[~]` in progress · `[x]` done

---

## Phase 0 — Setup Project

- [ ] Init repo + struktur multi-module Gradle
- [ ] Setup version catalog (`libs.versions.toml`)
- [ ] Setup Hilt di semua modul
- [ ] Setup baseline build config (minSdk, targetSdk, proguard)

---

## Phase 1 — Core & Pairing

- [ ] Definisi interface publik di `:core`
  - [ ] `DisplayDevice` data class
  - [ ] `PairedController` data class
  - [ ] `TransferPayload` sealed class
- [ ] QR Pairing — Display side (generate QR + public key)
- [ ] QR Pairing — Controller side (scan + simpan ke PairingStore)
- [ ] PairingStore (encrypted SharedPreferences / DataStore)
- [ ] Handshake & verifikasi key

---

## Phase 2 — P2P Transport

- [ ] mDNS Discovery (NsdManager)
  - [ ] Register service dari Display
  - [ ] Discover dari Controller
- [ ] BLE Presence
  - [ ] Advertise dari Display
  - [ ] Scan dari Controller
- [ ] TCP Transfer (kirim gambar via WiFi)
  - [ ] Ktor server di Display
  - [ ] OkHttp client di Controller
- [ ] WiFi Direct Transfer (fallback tanpa router)
- [ ] Enkripsi payload (AES-GCM)

---

## Phase 3 — Display Mode

- [ ] DisplayScreen (fullscreen Compose)
  - [ ] Keep screen on
  - [ ] Hide system bars
- [ ] ImageReceiveServer (Ktor, terima + decrypt)
- [ ] Animasi load: **Tile** (grid reveal kiri→kanan)
- [ ] Animasi load: **Paper Screen** (blank→grayscale→color)
- [ ] Brightness control (manual slider)
- [ ] Auto-dim by schedule
- [ ] Fit mode: Fill / Contain / Blur Background
- [ ] Clock overlay (opsional, toggle)

---

## Phase 4 — Controller Mode

- [ ] DeviceListScreen (daftar paired displays)
  - [ ] Status online/offline per device
- [ ] ShareTargetActivity (muncul di share sheet sistem)
  - [ ] Terima intent gambar dari share
  - [ ] Pilih device jika >1
  - [ ] Kirim via `:p2p`
- [ ] Picker bottom sheet (pilih display sebelum kirim)
- [ ] Rename device dari list

---

## Phase 5 — Polish

- [ ] Onboarding flow (mode selection saat pertama install)
- [ ] Empty state yang layak (belum ada paired device)
- [ ] Error handling: device offline, transfer gagal
- [ ] Notifikasi subtle saat gambar berhasil diterima (di display)
- [ ] Dark mode support penuh

---

## Catatan

<!-- Tambahkan catatan per sesi di sini -->

