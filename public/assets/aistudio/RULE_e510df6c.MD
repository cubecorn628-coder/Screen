# RULE.MD — Project Rules

## Code Rules

1. **No boilerplate tanpa fungsi nyata** — jangan generate kode yang tidak langsung
   dipakai di sesi ini.
2. **Satu tanggung jawab per file** — ViewModel tidak boleh berisi logika jaringan,
   Repository tidak boleh berisi logika UI.
3. **State hanya mengalir ke bawah** — UI bereaksi ke state, bukan sebaliknya.
4. **Tidak ada hardcoded string di UI** — semua teks lewat `strings.xml`.
5. **Tidak ada magic number** — semua konstanta di `object Constants` atau
   companion object yang relevan.
6. **Semua operasi jaringan dan IO di luar Main thread** — wajib `Dispatchers.IO`.

## Arsitektur Rules

- Gunakan **Clean Architecture** (data / domain / ui layer)
- Modul `:p2p` harus berdiri sendiri — tidak bergantung ke `:display` atau `:controller`
- Semua interface publik antar modul didefinisikan di `:core`

## Security Rules

- Public key disimpan setelah QR scan — tidak ada pairing ulang otomatis
  tanpa verifikasi user
- Semua payload yang dikirim P2P harus terenkripsi (minimal AES-GCM)
- Tidak ada open server tanpa autentikasi device — koneksi baru harus
  ada di daftar `registered controllers`

## Git Rules

- Branch: `main` (production-ready), `dev` (aktif), `feat/*`, `fix/*`
- Commit message format: `type(scope): deskripsi singkat`
  - Contoh: `feat(p2p): add mDNS discovery via NsdManager`
- Jangan commit file `.idea/`, `local.properties`, `*.keystore`

## Review Rules

- Sebelum implement fitur baru, cek `PROGRES.MD` apakah dependensinya sudah done
- Kalau ada perubahan struktur modul, update `PROJECT.MD` dulu
