# UI / UX changes (on top of v2.3.0)

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
