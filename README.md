# MONDO Android

Native Android starter app for DJ playlist analysis.

- Paste a public Spotify playlist URL.
- Reads playlist tracks from Spotify's public embed page.
- Looks up tempo, Camelot key and energy metadata through Song Finder.
- Builds a debug APK through GitHub Actions.

## Important
This first Android build uses catalogue analysis data, not an embedded Essentia binary. Song Finder is a third-party service and applies rate limits; unmatched tracks remain blank rather than receiving invented values. Essentia local audio analysis and a fully self-contained audio acquisition/matching pipeline are not yet included.
