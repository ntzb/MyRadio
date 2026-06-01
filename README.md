# MyRadio

A native Android radio app. Station list and logos are reused from the
[idanplus Kodi addon](https://github.com/Fishenzon/repo); built to play Israeli stations
(including Kan's DASH streams that trip up most apps) plus a few extras.

## Features

- **Station list** with one-tap play, current-station highlight.
- **Like (♥)** stations — persisted; bottom tabs switch **Liked / All**.
- **Now-playing strip** above the tabs (play/pause/stop) → tap to open the **Now-Playing screen** (controls, volume, song title via ICY metadata).
- **24h auto-refresh** of the station list from the idanplus GitHub `channels.json`, with the bundled copy as a fallback and hardcoded **backup streams**.
- **Auto / light / dark** theming (Material You dynamic color).
- **Live-radio buffering** (Media3 ExoPlayer, ~2.5 s start, resilient rebuffer, plays **DASH + HLS + Icecast/MP3/AAC**).

## Build (no Android Studio needed — GitHub Actions)

1. Create a **private** GitHub repo and push this project (see commands below).
2. GitHub → **Actions** tab → the **Build APK** workflow runs on every push (or run it manually via *Run workflow*).
3. When it finishes (~3–5 min), open the run → **Artifacts** → download **myradio-debug** → unzip → `app-debug.apk`.
4. Copy the APK to the tablet and sideload (enable "install unknown apps" for your file manager).

```bash
cd MyRadio
git init && git add . && git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Toolchain (verified latest, June 2026): AGP 9.2.0 · Gradle 9.4.1 · Kotlin 2.3.21 (built-in) · compileSdk 36 ·
Media3 1.10.1 · Compose BOM 2026.05.00 · WorkManager 2.11.2 · DataStore 1.2.1 · Coil 3.4.0.

## On the tablet (Lenovo Xiaoxin / ZUI)

ZUI aggressively kills background apps. After installing, add MyRadio to the **battery / autostart
whitelist** so playback isn't killed, and allow notifications (for the media controls).

## Notes & known follow-ups

- **Extra stations** live in `app/src/main/assets/extra_stations.json` (KJazz 88.1, WEGE 104.9 The Eagle,
  רדיוס נוסטלגי 96.3) — **not hardcoded**. This file is bundled as a fallback *and* fetched from your repo every
  24h. **Set `EXTRA_STATIONS_URL` in `StationRepository.kt`** to your repo (replace `<USER>/<REPO>`); then editing
  `extra_stations.json` and pushing adds/updates stations **without rebuilding the app**. Each station takes an
  ordered `urls` list (primary + fallbacks). For a logo, drop a PNG into `assets/logos/` and set its filename on
  the station's `image` field (or use an `http` URL).
- **Kan** stations are clear MPEG-DASH (`.livx`); the player declares the DASH MIME explicitly and strips the
  `?dvr=` timeshift to play the live edge.
- **Dynamic stations**: the `radio`+regex (e.g. 103FM/104.5FM/89.1FM), `100fm`, and `1064fm` modules resolve a
  fresh URL at play time (mirroring the addon); `glz`/`sport5` use their static URL.
- Israeli CDN streams are likely **geo-restricted to Israel**.
- This is a large first cut — if the first CI build flags a version/import mismatch, it's a quick fix in the
  version catalog / that file, then re-push.
