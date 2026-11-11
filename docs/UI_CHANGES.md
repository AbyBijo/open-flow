# UI / UX changes (on top of v2.3.0)

## v2.4.3

- Cancel X on the recording waveform: a small X sits at the right end of the bubble's bars (and next to the demo bubble) — one tap cancels the dictation, discards the audio, and transcribes nothing. It never counts as a tap-to-stop or a drag start.
- Faster insert outside the app: the insert used to wait 2–5 s in other apps because right after the overlay tap the system still reported our own window as active, wave 1 burned its attempts on it, and wave 2 waited out its schedule. The insert now resolves the host app's window directly from the window list, wave 1 retries at 100 ms, and wave 2 runs 100/250/500/900/1400 ms — a successful insert lands in well under a second. Paste safety is unchanged: password/banking guard, hint handling, post-write verification, clipboard restore.
- Waveform is more reactive: quiet speech lifts the bars visibly (square-root height mapping, faster attack) while loud speech still stops at the same peak — the cap never moved. No left-to-right fade any more; all bars share one alpha.
- Start chime: two short "du du" tones on the music stream when a dictation starts, in the bubble and in the demo. Silent mode mutes it.
- Loading is three dots now, not the big breathing circle: a loading screen cannot fit the small overlay, and opening one would cover the app being dictated into. The dots animate alpha only — nothing is scaled past the window bounds, which is what used to clip the breathing round at the bubble's edges. The bubble stays a 2.0x capsule while loading so the dots have room.
- Drag-to-dismiss: the whole dismiss bar slides up from the bottom when a drag starts and slides down out of view when it ends. The X icon itself never animates. The target is a 96 dp circle so the bubble fits inside it at every size, and while the bubble is over it the solid disc becomes a ring so the bubble stays visible inside. The bar is measured from the start (invisible, not gone) so the full red circle is never clipped at the top, and the magnetise aims at the circle's real resting centre.

## v2.4.2

- Speed is the headline again: the encoder context is now sized to the actual clip instead of always running the full 30 seconds (about twice as fast on the encoder for typical short dictations), silence is trimmed off both ends before the model sees it, and the local path transcribes the in-memory samples in one call — no WAV read-back, no 30-second chunk files on disk. Temperature 0 and non-speech suppression cut wasted decode steps. The release build's RelWithDebInfo configuration now gets -O3 as well.
- Long-pause auto-stop: if you fall silent for 5 seconds during a tap-started recording, it stops and transcribes by itself, exactly like a tap-stop. Normal gaps between sentences never trigger it, and it only arms once you have started talking. Settings has a "Stop on long pause" toggle (on by default); hold-to-talk is unaffected.
- Groq behaviour is unchanged: it still uploads the WAV file, still chunks long audio under its 25 MB limit, and still falls back the same way when on-device fails.

## v2.4.1

- Speed is the headline: the native Whisper engine is now compiled with full optimisation (-O3, armv8.2-a+fp16, link-time optimisation) in every build type, including Debug — the shipped APK never runs it unoptimised again. Thread count follows the phone's fast cores (up to 6, never below 4) instead of a flat cap of 4, and English dictation decodes in one segment.
- A slow press on the bubble to stop a recording no longer does nothing: any clean press-release counts as a tap, however long the press lasted.
- The loading state is one complete big round breathing on and off — no more three dots inside a visible box. Same in the Settings preview and the demo.
- The waveform is a little shorter and eases smoothly instead of jumping; the recording capsule is smaller still (2.0x).
- The stat tabs are normalised: equal thirds, one indicator pill that slides with the finger mid-swipe, spring animation on tap.
- Model downloads retry automatically on HTTP errors and dropped connections (3 rounds over both mirrors, with backoff), resuming from the partial file; the list shows "Retrying…" honestly instead of a failure. Only a genuinely corrupt file restarts from scratch.
- Models that do not fit the phone are hidden from the list, with a "See more" row that reveals them with the reason each does not fit. "See less" hides them again.
- Lifecycle: killing the bubble service mid-recording now stops the microphone, and leaving the demo mid-recording stops it too.

## v2.4.0

- Home header: "OpenFlow" is centred at the top with the app icon immediately to its left. The greeting is gone.
- The usage ring is replaced by swipeable stat tabs (Words today + lifetime total, Free usage left of the 8 Groq hours, Words per minute). Swipe or tap to switch.
- "Try the bubble" and the "How to dictate" card left Home. Home keeps only the header, bubble toggle, stat tabs and history. "How to dictate" lives in Settings; the demo is reachable from Settings instead.
- History is grouped by day at display time — Today, Yesterday, then dates. Tap-to-copy and per-entry times are unchanged.
- Onboarding is now an explicit fork: "Groq API key" or "On-device model". The on-device path has no key step at all. It shows the phone's RAM, recommends the largest model that fits safely, and greys out models that do not fit with the reason.
- The floating bubble morphs: tap once to expand into the smaller 2.2x recording capsule (~280 ms), tap again to collapse to the idle shape with the thinking dots inside. Hold-to-talk (500 ms) still works alongside. Dragging shows a "Turn off bubble" target at the bottom centre; dropping on it turns the bubble off without opening the app.
- Thinking dots are no longer clipped: the thinking row is taller than the dots and clipping is off on the row and the root.
- No route renders until the stored preferences have loaded, so returning users never see onboarding flash after the splash.
- Dictation has no 60-second cap any more. Long recordings are split into 30 s windows on-device (600 s for Groq) and the transcripts merged in order.
- Medium and Large models are downloadable options (Medium 1537 MB, Large Turbo 1623 MB, Large 3094 MB, plus .en and Q5_1 variants), on demand only, never bundled. The storage check still applies.
- The Settings bubble preview and the demo screen follow the same tap flow as the real bubble: idle -> recording capsule -> transcribing (collapsed shape + dots) -> inserted -> copied.

## v2.3.1

UI layer only. No change to dictation, Groq, on-device engine, key storage, the bubble service or permissions.

## Layout
- Fixed the root cause of cramped and overlapping screens: items inside an entrance animation were stacked on top of each other (the "Recent" heading drawing over its text, cards touching). Items now lay out vertically.
- Every card has a consistent 12 dp gap. Onboarding, Settings, Home and About all share the same rhythm.
- Edge-to-edge on every Android version (`enableEdgeToEdge`). Status and navigation bar icons follow the in-app theme, so they are never dark-on-dark.
- Landscape cutout safe. Content is capped to a readable width and centred on tablets, foldables and landscape.
- Buttons and segmented controls grow with large font sizes instead of clipping.

## Animation
- The staggered entrance animation now really plays (it never ran on first composition before) and is skipped when the system "Remove animations" setting is on.
- Home <-> Settings cross-fades instead of sliding the whole screen, bottom bar included.
- Bottom tabs animate their pill and colours and give press feedback. Segmented controls animate their thumb. The usage ring animates to its value. The accessibility help expands smoothly.

## Accessibility
- Cards that are not clickable are no longer announced as buttons by TalkBack.
- Roles and selected state on tabs, segmented options and colour swatches. The usage ring has a spoken description. Colour swatches are 48 dp touch targets with names.
- 48 dp minimum touch targets on text links and rows. Higher text contrast for secondary text.
- Fixed icons that were invisible in dark mode (external-link icons, usage ring track).

## Dictation into any app (Accessibility service)
- Clearer "Paste at cursor" card and a collapsed "Can't switch it on?" help in Home, onboarding and Settings.
- Covers phones with no three-dot menu in App info (some Samsung models): try the switch once, then look again, or run
  `adb shell appops set <package> ACCESS_RESTRICTED_SETTINGS allow` (shown with the real package name, selectable).
- Dictation still falls back to the clipboard when Accessibility is off.
