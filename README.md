# WebWidget

An Android home-screen widget that shows a snapshot of any web page.

The widget renders the configured URL into a `Bitmap` in the background and
pushes that bitmap to the launcher via `RemoteViews.setImageViewBitmap`.
Tapping the widget opens the same URL in the system browser.

## Tech stack

- Kotlin 2.0.21, Android Studio
- minSdk 26, targetSdk 35, AGP 8.5.2
- Gradle Kotlin DSL
- Jetpack Compose (configuration screen)
- WorkManager (periodic snapshot worker)
- DataStore Preferences (per-widget config)
- No third-party libraries beyond AndroidX

## Building

Standard Android project layout:

```bash
./gradlew :app:assembleDebug      # build a debug APK
./gradlew :app:installDebug       # install on connected device
```

You'll need a working Gradle 8.9 wrapper jar — generate one with
`gradle wrapper --gradle-version 8.9` if your checkout doesn't include it.

## Adding the widget

1. Long-press an empty spot on the home screen.
2. Open the widget picker, find **WebWidget**, drag it to the home screen.
3. The configuration screen opens automatically:
   - Type the URL (must include `http://` or `https://`).
   - Pick a refresh interval: 15 min, 30 min, 1 h, 6 h, or 24 h.
   - Tap **Refresh preview** to render the page once and check the result.
   - Tap **Add to home screen** when you're happy.
4. The widget is placed and the first snapshot is rendered within a few
   seconds. After that it refreshes on the cadence you chose.

The widget supports three sizes: 2×2, 4×2 (default), and 4×4. Resize it
with the standard launcher gesture and the next snapshot will be regenerated
at the new dimensions.

## Architecture

```
com.lonewren.webwidget/
├── widget/   AppWidgetProvider, RemoteViews builder, snapshot disk cache,
│             WorkManager scheduler, widget-size resolver
├── worker/   CoroutineWorker that owns the offscreen WebView snapshot logic
├── config/   Compose configuration Activity + ViewModel
├── data/     DataStore wrapper, RefreshInterval enum, WidgetConfig model
└── di/       Manual DI container (no Hilt)
```

The widget is just an `ImageView` inside a `FrameLayout`. RemoteViews does
**not** support `WebView`, so we render in our own process and ship a
bitmap to the launcher.

## Known limitations

- **15-minute floor**. Android's WorkManager hard-caps periodic work at 15
  minutes. The "every 15 minutes" preset is the shortest cadence we can
  honor; in practice the system may delay further to batch work.
- **Login-gated content won't render.** Our offscreen WebView uses an
  isolated cookie store; it doesn't share cookies with Chrome or any other
  app. Pages behind authentication will render as a login screen.
- **Heavy-JS pages may time out.** The snapshot worker waits up to 20
  seconds for `onPageFinished` plus a 500 ms settle delay. SPAs that keep
  fetching content beyond that window may produce a partial snapshot or an
  error placeholder.
- **Some sites detect WebView as non-Chrome** and serve a degraded layout.
  We send a desktop Chrome User-Agent string to mitigate this, but it's
  not bulletproof.
- **Bitmap binder limit (~1 MB)**. The bitmap pushed to the launcher is
  scaled down to the widget's physical pixel size to stay well under the
  Binder transaction cap. Don't expect retina detail in a 2×2 cell.
- **No video/audio.** `mediaPlaybackRequiresUserGesture` is on, so
  autoplaying audio won't fire and animations are captured as a single
  frame at the moment of snapshot.

## Privacy

No analytics, no tracking, no network calls beyond the URLs you configure.
The DataStore file lives in the app's internal storage; bitmaps are cached
in the app's private `cacheDir` and removed when you remove the widget.
