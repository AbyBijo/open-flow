# OpenFlow

Free, open-source Android voice dictation — a replacement for Wispr Flow, with no account system.

Speak into the mic, see live dictation on screen, copy it instantly, or delete it — and the deleted text is saved to History. Your total word count never resets.

- **Package:** `com.abybijo.openflow`
- **Min / target:** Android 8.0 (API 26) to Android 16 (API 36)
- **Build:** Kotlin, Jetpack Compose, Gradle Kotlin DSL
- **Engine:** whisper.cpp, on-device. Audio never leaves the phone.
- **Theme:** Black and white, simple blue (`#0A84FF`) accent, clean Apple-like feel. No emojis anywhere in the UI — every icon (mic, download, copy, delete, check, lock, write, arrow) is a custom-made vector drawable in `app/src/main/res/drawable/`.

## Models — pick one, or more

Download directly in the app from Hugging Face, public dataset `ggerganov/whisper.cpp`, no login:

| Group | Models | File size |
|---|---|---|
| Main | Tiny, Base, Small | 75 MB, 142 MB, 466 MB |
| English-only | Tiny.en, Base.en, Small.en | 75 MB, 142 MB, 466 MB |
| Quantized | Tiny Q5_1, Base Q5_1, Small Q5_1 | 32 MB, 60 MB, 190 MB |

Links are `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/<file>`, e.g. `ggml-tiny.bin`, `ggml-base.en.bin`, `ggml-small-q5_1.bin`. The `ggml.ggerganov.com` mirror is the fallback if Hugging Face fails.

Downloads go **only** to the app's internal storage (`filesDir/models/`), never to the Downloads folder, with an animated progress indicator, cancel, and SHA-256 verification where the catalogue pins one. Dictation stays locked until the selected model is downloaded and verified. Model download UI appears **only in the Models page** — the Dictate page shows the current model and a link to Models, but no download button or progress.

## Features in v1

- Model picker: Main, English-only, and Quantized Tiny / Base / Small
- Live dictation: mic recording at 16 kHz mono, transcribed in ~6-second chunks so text appears while you speak
- Copy button: copies the current dictation instantly
- Delete button: clears the current dictation **and** saves it as a History entry
- Total words spoken: a lifetime counter, shown on the main screen, never reduced by a delete
- History tab: saved dictations with copy and per-entry delete
- Onboarding and tutorial: a 4-step first-run walkthrough (welcome, privacy, download a model, dictate/copy/history), re-openable from Settings
- Settings page: developer info (Aby Bijo, GitHub, portfolio, LinkedIn), support link (GitHub Issues), and real settings — theme (System / Light / Dark), default model, clear history, storage info
- No account, no analytics, no server, no audio upload

## Honest v1 limits

- Live dictation is chunked-live (~6 seconds per chunk), not sample-by-sample streaming. Whisper transcribes finished chunks.
- The native whisper.cpp library is fetched at build time via CMake FetchContent (tag `v1.7.5`). If that tag or the whisper target changes upstream, the native build will need that tag updated.
- Quantized models are verified by file size only in v1 (no pinned SHA in the catalogue for q5_1 files).
- This workspace has no Android SDK, so v1 was statically checked here; the real proof is the GitHub Actions build in this repo.

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

Only account details are never collected — there is no account at all. Model files and history live in the app's private internal storage. The only network use is downloading a model file from Hugging Face (or its mirror).
