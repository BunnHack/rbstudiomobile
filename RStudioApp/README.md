<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/efbbe87d-a989-4ba7-a4c3-054de1d580a9

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (or a local JDK 17+ and Android SDK)

> **Real 3D:** The viewport is rendered by **SceneView + Google Filament**
> (`io.github.sceneview:sceneview:4.25.0`) with GPU-lit primitives, decals, selection
> outlines, transform gizmos, hit testing, and touch orbit/pan/zoom. The implementation
> lives in `app/src/main/java/com/example/ui/viewport/`; no 3D engine source is vendored.

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
