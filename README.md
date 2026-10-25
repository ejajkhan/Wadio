# Wadio (native Kotlin port)

Native Kotlin + Jetpack Compose rewrite of the original Rust / egui Wadio app.

Every user-visible feature and every documented durability guarantee of the
original is preserved. See "Intentional differences" below for the three
internal implementation choices that differ (none are user-facing).

## Feature parity

- **Audio Manager** – Media / System / Alarm sliders bound to the live
  `AudioManager` streams. Matches `src/ui/audio_view.rs`.
- **Stealth trigger** – press and hold "Wadio" for **3.0 s**. Matches
  `AuthEngine::update_header_touch`.
- **4-digit PIN** – set on first run (with confirmation), verified with
  **Argon2id**, parameters matched to `argon2` crate defaults
  (`m=19456, t=2, p=1`). Verifier is stored as a PHC string in `meta.vault_pw`;
  the PIN itself is never persisted.
- **Exponential PIN lockout** – `2^min(attempts,6)` capped at 60 s,
  persisted via `meta.pin_fail_count` / `meta.pin_lock_until`. Matches
  `src/app.rs`.
- **Biometric unlock** – `BiometricPrompt` with `DEVICE_CREDENTIAL | BIOMETRIC_STRONG`,
  only shown when a PIN is already configured. Mirrors
  `MainActivity::launchBiometricPrompt`.
- **FLAG_SECURE** – applied on the PIN prompt, the vault, the image viewer,
  and on `onPause`/`onStop` so Recents never shows plaintext. On resume,
  re-applied to match the current auth state.
- **Background lock** – `ProcessLifecycleOwner.ON_STOP` → `requestLock()`.
  Suspended while a biometric prompt is active (matching
  `biometricPromptActive` in the Rust code).
- **60 s idle lock** – suspended while viewing a photo (parity with
  `viewing_photo` logic). `IDLE_RESET` fires on `onResume` so returning from
  the video task doesn't trigger a false lock.
- **Video playback** – separate `InternalVideoActivity` with
  `excludeFromRecents`, `taskAffinity=""`, `FLAG_ACTIVITY_NEW_TASK`, and
  `FLAG_SECURE`. Uses Media3 ExoPlayer (the original used ExoPlayer too).
- **Album system** – `default` album seeded on DB create, protected from
  deletion. Missing album folders are recreated on startup. When the DB
  reports more than one album, unknown folders are **moved** to `.orphaned/`
  — never deleted.
- **Import** – SAF multi-pick (`image/*, video/*`). Streams in 64 KB chunks,
  hashes with SHA-256, rejects duplicates by content hash, checks available
  disk space (`1.15× + 15 MB`), and validates image dimensions (≤ 50 megapixels,
  ≤ 30 000 px per side) using `BitmapFactory` header decode.
- **Durable pending markers** – before the staging→media rename, a JSON
  marker is written to `.pending/{id}.json`. Startup recovery:
  (1) attempts the rename if the staging file is still present,
  (2) attempts the DB insert,
  (3) removes the marker+file on duplicate-if-uncommitted,
  (4) leaves the marker on transient SQL failure for later retry.
- **Orphan recovery** – unreferenced files older than 24 h are **moved**
  into `.orphaned/` (never deleted). `.orphaned/` is purged only after 30 days.
  If the DB reports zero referenced paths, recovery is a no-op (fresh install
  or partial restore safety).
- **Interrupted import recovery** – stale `.tmp` files > 24 h old are removed
  from `.staging/`. Staging files whose IDs appear in a valid pending marker
  are protected.
- **Copy / Move / Delete**
  - Copy: independent file under destination album with a fresh UUID, DB
    insert bypasses the duplicate check (copies deliberately share hashes).
  - Move: transactional DB update + file rename; on filesystem failure, the
    DB is rolled back.
  - Delete: transactional DB delete; files are only removed when no row
    references them.
- **Sort** – Name / Size / Date created, ascending or descending.
- **Multi-select** – 2 s long-press in the gallery grid.

## Intentional differences (internal only)

These do not remove any user-visible feature, but they are not line-for-line
ports of the original implementation:

1. **Thumbnails** – the original spawned a worker thread and built a
   pre-sampled animated `.gif` per item. The Kotlin port generates a static
   96 px JPEG on import (used as album cover and grid fallback), and the
   Compose grid renders animated GIFs live via Coil-gif. Feature parity is
   preserved; the internal 20-frame pre-sampling is not.
2. **Exotic image formats** – DDS, EXR, HDR, ICO, QOI, TGA, TIFF are still
   *accepted on import* (validation uses `BitmapFactory` headers, so these
   formats with valid headers will import), but the viewer shows a
   "Preview not supported" card for them because Android has no built-in
   decoder. JPEG, PNG, WebP, GIF, BMP, HEIC/HEIF and AVIF all display
   correctly. This is an Android platform limitation, not a missing feature
   in the port.
3. **HEIC/HEIF** – the original Kotlin side already converted HEIC/HEIF to
   JPEG on Android 9+. That code is preserved here (`MainActivity` was
   already Kotlin in the original repo). If you import HEIC on Android 8.x,
   the import will fail the same way it does in the original app.

## Building

```bash
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug