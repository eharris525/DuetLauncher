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
- **Alphabet bar from either edge:** the bar shows on one side, and you can slide along the
  other edge too. The apps for the letter under your finger sit in the middle of the screen.
- **Tapping apps:** opens on the left screen when both are free, or next to the app that's
  already open.
- **Themes:** one-tap presets (Duet, Posterboard, Frost, Midnight, OLED, Neon, Sunset, Forest,
  Terminal, Paper) that set the panel style, accent colour, font, wallpaper dimming and icons
  together. Each has a matching built-in wallpaper, drawn on the phone to fit both screens, and
  a generated icon style that reworks every app's icon (or your icon pack's) in the theme's
  look while keeping each app's own colour.
- **Fonts:** pick a built-in Android font or load your own .ttf / .otf (Settings → Font).
- **App menu:** long-press an app for its shortcuts (e.g. "New chat"), which screen to open it
  on, and buttons to favorite, reorder, hide, open App info or uninstall. Search sits at the top of the list.
- **AI button:** the sparkle on the clock starts a voice chat with the AI app you choose, or with
  Claude right on the home screen (bring your own API key from console.anthropic.com).
- **Music widget:** shows what's playing, with controls and a seek bar.
- **Google feed:** swipe right on the home screen to open Google's Discover feed.
- **Desktop mode (experimental):** plug a monitor, TV or display glasses into the USB-C port and Duet puts a
  desktop on it: a taskbar (Start menu with search, pinned and open apps, clock), your
  favorites as desktop shortcuts, and apps in windows. The Duo becomes the touchpad (see below).
- **First-run setup guide:** shows which settings are on and opens each one. Reopen it from
  Duet Settings → *Setup guide*.

## Desktop mode (experimental)
This is an early, experimental desktop experience. Android gives ordinary apps very little
control over other apps' windows, so some things work around those limits and may be rough.
Read the notes below.

**Setup (once):**
1. Turn on *Duet screen detection* (Settings → Accessibility). The pointer, taskbar and
   window controls work through it.
2. For apps in movable, resizable windows: turn on Developer options (Settings → System →
   About → tap *Build number* seven times), then in Developer options turn on
   **Enable freeform windows** and **Force desktop mode**, and restart the Duo.

Without step 2 the desktop still works, but every app fills the screen.

**Using it:**
- The touchpad opens on the Duo when a display is connected. To get it back later, tap the
  *Desktop mode is on* notification.
- Touchpad: one finger moves the pointer and a tap clicks. Use the **Left / Right click**
  buttons, or tap with two fingers to right-click. Scroll with two fingers. To drag,
  double-tap and hold, or hold still for a moment.
- Buttons for Back, Start, Show desktop, and a keyboard that types into the selected text
  box (or the Start menu search).
- **Window controls** sit on each window's title bar:
  - move (drag the dots)
  - snap left or right
  - maximize or restore
  - full screen (hides the taskbar; touch the bottom edge to bring it back)

  Drag the round corner to resize.
- **Right-click** a taskbar icon, a Start menu app or a desktop shortcut for snap, maximize,
  full screen, close, pin to taskbar and app info. Right-click the desktop for more options.
- **Windows reopen when moved or resized.** Android doesn't let other apps move or resize a
  window that's already open. So Duet closes the window and reopens the app at the new size.
  Most apps return to where you were, but text that wasn't saved can be lost.

## Build
    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk

## Install on the Duo
Download `DuetLauncher.apk` from the latest GitHub release and open it on the Duo, or:

    adb install -r app/build/outputs/apk/debug/app-debug.apk
Then open "Duet Settings" → *Set as default home app*. To span, drag the home screen
onto the hinge like any other app.
