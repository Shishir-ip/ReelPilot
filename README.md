# ReelPilot 🚀

**Send a video link to your phone → it becomes an Instagram reel, automatically.**

ReelPilot is a self-hosted Android app that turns a spare phone into a hands-free Instagram posting machine:

- 📥 Send any **video link** (YouTube, TikTok, Twitter/X, Reddit...) to your Telegram bot — ReelPilot downloads it with **yt-dlp** (the engine behind Seal).
- 📹 Or send a **video file** (up to 20 MB, Telegram's bot limit) — it's used directly.
- ⏰ Videos queue up and get posted **one reel every N hours** (you pick the interval: 1, 2, 3, 4... up to 72).
- 🤖 Posting is done through the **real Instagram app** — ReelPilot taps it with Android's Accessibility Service, exactly like a person would. No passwords shared with third-party libraries, no risky APIs.
- 🌙 Works with the **phone locked and screen off**, fully in the background.
- 📴 **No internet?** It waits, then posts the queue when you're back online — and the interval keeps its rhythm.

## How it works

```
You → Telegram bot → download (yt-dlp) → queue → wait interval →
wake screen → share video to Instagram → accessibility taps Share → done → next in N hours
```

1. A tiny foreground service long-polls Telegram (so nothing is missed, even offline).
2. Links are downloaded by an embedded Python engine (yt-dlp via [Chaquopy](https://chaquo.com/chaquopy/)).
3. Videos land in the phone gallery (`Movies/ReelPilot`) and the app's queue (Room DB).
4. Every interval, the app wakes the screen, hands the video to Instagram through Android's share sheet, and fills in the caption + taps Share via the Accessibility Service.

## Setup (once, ~5 minutes)

1. In Telegram, message **@BotFather** → `/newbot` → copy the **token**.
2. Install the APK, paste the token in **Settings → Save**.
3. Enable the **Accessibility permission** for ReelPilot and grant **Display over other apps**.
4. Remove the screen lock (no PIN/pattern) on the posting phone — this lets the automation run cleanly.
5. **Disable battery optimization** for ReelPilot.
6. Open Instagram once and stay logged in.
7. Send your first video link to your bot. The first person to message the bot is automatically set as the only allowed user.

That's it. Check progress any time in the app's **Queue** and **Logs** tabs.

## Notes

- Instagram sometimes redesigns its app; if posting starts failing, check the **Logs** tab — automation steps are logged in detail. Updates to match new layouts may be needed occasionally.
- Interval-based, human-paced posting is deliberate — it keeps your account looking normal.
- The bot can only download **video files ≤ 20 MB** (Telegram Bot API rule). Links have no size limit.
- Built for personal use on a device you own. Post content you have the rights to.

## Building

```bash
./gradlew assembleRelease
```

The signed APK lands in `app/build/outputs/apk/release/`. GitHub Actions builds and signs releases automatically — the signing keystore is **not** in this repo; it's stored encrypted in the repository's Actions secrets.

> ℹ️ Forking? Replace the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` secrets with your own signing key.

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · DataStore · OkHttp · Telegram Bot API · yt-dlp (via Chaquopy) · Android Accessibility Service

## License

MIT
