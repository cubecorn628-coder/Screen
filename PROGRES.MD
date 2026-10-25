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

---

## Phase 6 — Ide Fitur Masa Depan (UI/UX & AOD Advanced)

### A. Advanced Always-On Display (AOD) Mode
- [x] **Pixel Shift & Anti Burn-In Engine**
  - Pergeseran piksel periodik (5 offset ±3px, bergeser tiap 60 detik dengan animasi halus) untuk seluruh klaster jam AOD guna mencegah *OLED image retention*.
- [x] **Ultra-Low Power AMOLED Ambient Profile**
  - `AodOverlay`: background #000000 pekat (0% konsumsi piksel hitam AMOLED), jam monokrom, brightness window dipaksa ke level ambient minimum selama AOD aktif.
- [x] **Night-Vision Red Light Ambient**
  - Palet merah redup otomatis pada jam AOD selama jendela malam 23:00–07:00 (dapat dimatikan dari Settings).
- [x] **Smart Contextual AOD Overlay & Widgets**
  - Jam, tanggal, nama perangkat, status WiFi/Bluetooth, dan persentase baterai di overlay AOD; timeout idle dapat diatur 15–300 detik; tap untuk wake, foto baru otomatis membangunkan layar.
- [ ] **Proximity & Motion Wake-Up**
  - Memanfaatkan sensor jarak / akselerometer / mikrofon / BLE beacon presence dari Controller untuk meningkatkan kecerahan atau menampilkan animasi transisi foto penuh ketika pengguna mendekati perangkat Display.

### A2. Perbaikan Koneksi, QR & BLE (sesuai audit LocalSend-style)
- [x] **Bug fatal transfer diperbaiki**: server HTTP membaca header & body dari satu stream byte mentah (sebelumnya `BufferedReader` menelan byte body → "Incomplete payload" / gagal kirim gambar).
- [x] **QR code sungguhan (ISO/IEC 18004)**: encoder QR murni Kotlin (Reed-Solomon GF(256), 8 mask + penalty scoring, BCH format/version, versi 1–9 L/M) menggantikan pola QR palsu; divalidasi bit-for-bit dengan implementasi referensi.
- [x] **QR scanner kamera nyata**: CameraX + ZXing (`QrScannerScreen`), izin kamera runtime, torch, fallback entri manual.
- [x] **BLE/Bluetooth fungsional**: BLE id 6-byte stabil (tidak terpotong), advert + scan-response berisi port, siklus scan 25/8 detik, GATT server (Display) & client (Controller) untuk tukar pairing token tanpa WiFi.
- [x] **Auto-connection trusted device**: device ber-pairing otomatis di-handshake ulang dan di-refresh endpoint-nya via mDNS/BLE/GATT; status online dari ping WiFi atau sighting BLE.
- [x] **Keandalan transfer**: retry 2×, `retryOnConnectionFailure`, timeout 60 detik, verifikasi HMAC, antrian resolve mDNS (perbaikan ERROR_MAX_LIMIT), multicast lock ref-count.
- [x] **Token pairing universal**: satu payload JSON untuk QR dan BLE GATT, memuat IP/port, shared key, dan BLE id.

### B. Advanced UI/UX & Interactive Canvas
- [ ] **Dynamic Color Scheme (Monet Extraction)**
  - Ekstraksi warna otomatis dari gambar yang ditampilkan menggunakan *Palette API / Material You Monet Engine*, lalu secara dinamis menyesuaikan aksen warna jam, widget, dan blur background secara harmonis.
- [ ] **Multi-Layer Depth & Parallax Motion Effect**
  - Efek visual kedalaman 3D (*gyroscope-based parallax*) pada gambar dan clock overlay yang merespons gerak halus perangkat.
- [ ] **Remote Live Canvas & Interactive Annotations (Controller to Display)**
  - Fitur di Controller untuk menggambar, memberikan tulisan tangan, menambahkan sticker digital, atau membubuhi caption langsung di atas foto secara real-time sebelum atau saat ditayangkan di Display.
- [ ] **Fluid Morphing Transitions & Custom Animation Engine**
  - Transisi foto tingkat lanjut seperti *Glassmorphism Dissolve*, *Ken Burns Pan & Zoom Effect*, *Ink Splash*, dan *E-Ink Page Flip*.
- [ ] **Multi-Display Synchronized Wall & Grid Layout**
  - Kemampuan Controller untuk membagi satu foto resolusi tinggi ke beberapa Display sekaligus (*video wall arrangement*) atau menyinkronkan rotasi slideshow secara simultan di banyak frame.
