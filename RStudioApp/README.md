<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/efbbe87d-a989-4ba7-a4c3-054de1d580a9

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (or a local JDK 17+ and Android SDK)

> **Real 3D:** The viewport is now rendered by the **kool** engine
> (`de.fabmax.kool:kool-core:0.19.0`), a Kotlin GPU engine with an **OpenGL ES 3**
> backend on Android. Parts are rendered as solid, z-buffered, **PBR-lit** meshes
> (cube / icoSphere / cylinder / wedge) via `KslPbrShader`, with a native touch-orbit
> / pinch-zoom camera. The previous Canvas 2D painter's-algorithm viewport was
> replaced by `KoolViewport` in `app/src/main/java/com/example/ui/kool/`.
> kool is consumed as a single Maven Central dependency (no vendoring).

### Steps

1. Open Android Studio and choose **Open** on this project directory.
2. Create a file named `.env` in the project directory and set `GEMINI_API_KEY`
   to your Gemini API key (see `.env.example`).
3. Ensure `debug.keystore` exists at the project root (the build generates one if
   absent; or run `keytool -genkeypair -keystore debug.keystore -storepass android
   -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000
   -dname "CN=Android Debug,O=Android,C=US"`).
4. Run the app on an **OpenGL ES 3 capable** emulator or device (minSdk 28).

### Build the APK from the command line

```
# JDK 17+ and Android SDK (platform-36, build-tools 36) required.
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
./gradlew :app:assembleDebug
```

The debug APK is copied to `build_output/app-debug.apk`.
