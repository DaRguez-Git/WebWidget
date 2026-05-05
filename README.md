# WebWidget

An Android home-screen widget that shows snapshots of any web pages of your
choice and lets you swipe between them.

Each card is a snapshot of one URL rendered in the background; tapping a
card opens that URL in the system browser. The last card in the stack is
always a "+ Add URL" affordance that opens the configuration screen so you
can append another page without re-creating the widget.

## Tech stack

- Kotlin 2.0.21, Android Studio
- minSdk 26, targetSdk 35, AGP 8.5.2
- Gradle Kotlin DSL
- Jetpack Compose (configuration screen)
- WorkManager (periodic snapshot worker)
- DataStore Preferences (per-widget config)
- No third-party libraries beyond AndroidX

## Building

```bash
./gradlew :app:assembleDebug      # build a debug APK
./gradlew :app:installDebug       # install on connected device
```

You'll need a working Gradle 8.9 wrapper jar — generate one with
`gradle wrapper --gradle-version 8.9` if your checkout doesn't include it.
The simplest one-time setup is to open the project in Android Studio
once: it provisions JDK 17, Android SDK 35, and the Gradle wrapper for
you.

## Adding the widget

1. Long-press an empty spot on the home screen → Widgets → drag **WebWidget**
   to the home screen.
2. The configuration screen opens automatically:
   - Type one or more URLs (each must include `http://` or `https://`).
   - Press **+ Add URL** to add another row (up to 8).
   - Press the trash icon next to a row to remove it.
   - Pick a refresh interval: 15 min, 30 min, 1 h, 6 h, or 24 h.
   - Tap **Save**.
3. Within a few seconds the first snapshots land and the widget shows the
   first card. **Swipe** (the gesture is the same as the legacy Bookmarks
   widget) to flip between snapshots. The trailing card is "+ Add URL"
   which re-opens the configuration screen pre-filled with your existing
   URLs.
4. **Tap** any URL card to open that URL in the browser.

The widget supports any cell size from **2×2 up to 5×5** — drag the resize
handles after placing it. The next refresh regenerates every snapshot at
the new card dimensions.

## Architecture

```
com.lonewren.webwidget/
├── widget/   AppWidgetProvider, StackView builder, RemoteViewsService that
│             feeds the cards, snapshot disk cache, WorkManager scheduler,
│             widget-size resolver, click trampoline activity
├── worker/   CoroutineWorker that owns the offscreen WebView snapshot logic
├── config/   Compose configuration Activity + ViewModel (URL list + interval)
├── data/     DataStore wrapper, RefreshInterval enum, WidgetConfig model
└── di/       Manual DI container (no Hilt)
```

RemoteViews does **not** support `WebView` or `ScrollView`, so we render
each URL in our own process and feed the bitmaps to a `StackView`
populated by a `RemoteViewsService`. The launcher handles swipe gestures
natively; we only ship one PNG per card. Click handling goes through a
`Theme.NoDisplay` trampoline activity that dispatches taps to either the
browser (URL cards) or the configuration activity (the "+" card),
because RemoteViews fill-in intents cannot change the action/component
of the click template.

## Known limitations

- **15-minute floor**. Android's WorkManager hard-caps periodic work at 15
  minutes. The "every 15 minutes" preset is the shortest cadence we can
  honor; in practice the system may delay further to batch work.
- **All URLs refresh in serial**. With 8 URLs and a 20-second timeout
  each, a worst-case full refresh can take ~3 minutes. The widget still
  shows previously-cached snapshots while the new ones render.
- **Login-gated content won't render.** The offscreen WebView uses an
  isolated cookie store; it doesn't share cookies with Chrome or any other
  app. Pages behind authentication will render as a login screen.
- **Heavy-JS pages may time out.** The snapshot worker waits up to 20
  seconds for `onPageFinished` plus a 500 ms settle delay. SPAs that keep
  fetching content beyond that window may produce a partial snapshot or
  an error placeholder.
- **Some sites detect WebView as non-Chrome** and serve a degraded layout.
  We send a desktop Chrome User-Agent string to mitigate this, but it's
  not bulletproof.
- **Bitmap binder limit (~1 MB) per RemoteViews card**. Each card is
  scaled down to the widget's physical pixel size to stay well under
  the Binder transaction cap. Don't expect retina detail in a 2×2 cell.
- **No video/audio.** `mediaPlaybackRequiresUserGesture` is on, so
  autoplaying audio won't fire and animations are captured as a single
  frame at the moment of snapshot.
- **Cap of 8 URLs per widget instance** to keep memory bounded; place
  multiple widget instances if you need more.

## Privacy

No analytics, no tracking, no network calls beyond the URLs you configure.
The DataStore file lives in the app's internal storage; bitmaps are cached
in the app's private `cacheDir` and removed when you remove the widget.
