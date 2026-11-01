# NOVA TV — Android TV app source

This folder contains the installable Android TV APK **source code**. Build an APK using `.github/workflows/build-apk.yml` on GitHub Actions, or Android Studio.

## Important

- Open the app and use **إعدادات بثي** to enter your HLS URL. For example, after configuring DNS and HTTPS: `https://tv.example.com/hls/live/index.m3u8`.
- Select `NOVA LIVE` and press OK/Play to watch the livestream produced by OBS on the media server.
- The address is saved on the device, so it can be changed without rebuilding APK.
- Uses Media3 ExoPlayer for HLS and remote-control navigation; no commercial channel lists included.
- Debug APK is for sideloading and testing, not Google Play distribution.

### GitHub build

1. Create a GitHub repository and upload the **whole NOVA-TV-Live-Platform project contents**. The workflow is in the **top-level** `.github/workflows` folder.
2. Open **Actions -> Build NOVA TV APK -> Run workflow**.
3. Download the successful build's **Artifacts -> NOVA-TV-Android-APK**, unzip it and install `app-debug.apk` on Android TV / Google TV.

To build a signed production APK, configure your own stable release signing key and deployment process. The APK was not compiled in the current environment (Android SDK/Gradle not available).
