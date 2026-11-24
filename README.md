# Duet Launcher

A minimal, list-based Android home screen built for the Surface Duo 2's two screens.

## Features
- **Single vs. dual detection:** Jetpack WindowManager reports the hinge as a separating
  `FoldingFeature` only when the window spans both screens. With no fold present
  (folded back, or on one screen) the app uses the single-screen layout. See `ScreenMode.kt`.
- **Single screen:** clock, optional widgets, favorites, and an A–Z bar along the edge.
  Touch or drag the bar to open the full list at that letter.
- **Spanned:** two layouts (Settings → Layout), optional swap, panes kept clear of the hinge.
- **Icon packs:** any ADW / Nova / Apex-style pack (`appfilter.xml`). Apps the pack
  doesn't cover fall back to system icons.
- **Duet widgets:** built-in Calendar, Upcoming events, Weather (Open-Meteo), Music,
  Battery and Next alarm widgets that follow your style, accent and font. Each has its own
  tap action and options (⚙ in Edit widgets).
- **Glance Bar:** pick extra apps and colours to light up the Glance Bar while the Duo is
  closed (Settings → Glance Bar; needs notification access).
- **Widgets:** long-press the clock → Add / Edit widgets. In edit mode you can reorder,
  resize, and remove them.
- **Apps on one screen:** when an app opens on one screen, the launcher switches to its
  single-screen layout on the other. The clock, favorites, alphabet bar, action button and
  widgets glide to their new spots.
- **Swipe to pick a screen:** swipe an app left or right to open it on that screen
  (Settings → Apps on one screen).
- **Other foldables (Pixel Fold, Galaxy Z Fold, ...):** opened flat, the home screen uses the
  two-pane layout. A tap opens an app full screen. A swipe opens it in split screen on that
  side, with a condensed launcher in the other half; apps you open from there replace the
  other half (it passes back through the home screen for about a second to do that). Needs
  Android 12L or later. Tested on an Oppo Find N5. On Oppo, OnePlus and realme phones the app
  always opens on the right, whichever way you swipe.
- **Alphabet bar from either edge:** the bar shows on one side, and you can slide along the
  other edge too. The apps for the letter under your finger sit in the middle of the screen.
- **Tapping apps:** opens on the left screen when both are free, or next to the app that's
  already open.
- **Styles:** Liquid glass, Frosted, Solid, Outline or Minimal, plus an accent colour
  (Settings → Style).
- **Fonts:** pick a built-in Android font or load your own .ttf / .otf (Settings → Font).
- Long-press an app to favorite, reorder, hide, open App info, or uninstall. Search sits at the top of the list.

## Build
    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk

## Install on the Duo
Download `DuetLauncher.apk` from the latest GitHub release and open it on the Duo, or:

    adb install -r app/build/outputs/apk/debug/app-debug.apk
Then open "Duet Settings" → *Set as default home app*. To span, drag the home screen
onto the hinge like any other app.
