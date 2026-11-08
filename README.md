# OpenFlow

Free, open-source Android voice dictation — a replacement for Wispr Flow, with no account system.

Speak in any app with a floating bubble, and your words appear as text — powered by your own free Groq API key.

- **Package:** `com.abybijo.openflow`
- **Min / target:** Android 8.0 (API 26) to Android 16 (API 36)
- **Build:** Kotlin, Jetpack Compose, Gradle Kotlin DSL
- **Engine (v2):** Groq Cloud, `whisper-large-v3-turbo`, bring your own key (BYOK). No local model, no NDK.
- **Theme:** light `#F5F5FA` / dark `#0D0D18`, purple gradient `#5B5BF0` → `#8B5CF6`, rounded cards, no emojis anywhere — every icon is a custom-made vector drawable in `app/src/main/res/drawable/`.

## How it works (v2)

1. **Onboarding (first run only):** welcome → get your free Groq key (steps + link to the Groq Console) → audio-and-Groq consent (mandatory checkbox) → paste your key, **verified live** against `GET https://api.groq.com/openai/v1/models` (HTTP 200 = verified, 401 = invalid key) → permissions (Microphone, Display over other apps; Accessibility is optional).
2. **Floating bubble:** tap the bubble in any text field, speak, tap again. The recording (16 kHz mono WAV, max 60 s per utterance) is sent to Groq and the text is inserted at the cursor via the Accessibility Service. If there is no editable field, or the service is off, the text is copied to the clipboard instead. The bubble never inserts into password fields or banking / password-manager apps.
3. **Home:** greeting, Flow bubble toggle, free usage today (progress ring, minutes of 8 hours, tracked from real recorded audio and reset at midnight), "Try the bubble" in-app demo (a fake Messages chat where you dictate for real), Recent dictations (tap to copy, local only).
4. **Settings:** bubble size / opacity / colour (5 presets) / shape (Circle / Pill) / snap-to-edge / waveform, Groq API key (masked, Change re-runs live verification), auto-punctuation, haptic feedback, theme (System / Light / Dark), and a separate About page.

## Groq free tier

Groq's free plan covers Whisper at 20 requests/minute, 2,000 requests/day, 7,200 audio seconds/hour and **28,800 audio seconds/day — 8 hours of dictation a day**, no credit card. The key is stored only on the phone in EncryptedSharedPreferences and is never logged. Each user uses their own quota, so there is no server bill for the developer and no server at all for OpenFlow.

## About

Developer: Aby Bijo — GitHub https://github.com/AbyBijo, LinkedIn https://www.linkedin.com/in/aby-bijo-102359282, repository https://github.com/AbyBijo/open-flow, Buy Me a Coffee https://www.buymeacoffee.com/AbyBijo.

## v1 → v2

v1 ran Whisper on-device with whisper.cpp (native JNI, model downloads). v2 replaces that engine entirely: no local model, no NDK/CMake, no model downloading. Dictation now needs internet and a free Groq key, and in return it is faster, more accurate (Large v3 Turbo instead of Tiny), and works from a floating bubble in any app.

## Build

Open in Android Studio and run, or from the command line with Gradle 8.11.1:

```sh
gradle :app:assembleDebug
```

CI (`.github/workflows/android.yml`) builds the debug APK on every push to `main`, and uploads it as an artifact.

## Signing — key kept as a secret, never in the repo

Never commit a `.jks` / `.keystore` file. Generate one locally (see `docs/signing.md` — instructions only), then add these GitHub repository secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the keystore file
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

When those secrets are present, CI decodes the keystore to a temporary file, signs the release APK/AAB, and uploads it. When they are absent, CI still builds the debug APK and skips the signed release step.

## Privacy

There is no account and no OpenFlow server. Dictation audio is sent to Groq (under your own API key) to be turned into text; OpenFlow never stores your audio. History and usage live in the app's private internal storage. Your Groq key is stored encrypted on the device.
