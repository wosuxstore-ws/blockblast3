# Block Blast (Android)

Native Kotlin game, no third-party libraries.

## Build the APK with GitHub
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder!).
2. Open the **Actions** tab -> **Build Android APK** (it runs on every push, or press "Run workflow").
3. When the run turns green, open it and download the **BlockBlast-debug-apk** artifact (a zip containing `app-debug.apk`).
4. Copy `app-debug.apk` to your phone and install it (allow "install unknown apps").

## Build locally
Install Android Studio (or JDK 17 + Android SDK + Gradle 8.7), then run `gradle assembleDebug`.
