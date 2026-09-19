# PC → Android audio streamer (Opus, Compose UI)

Layout: `android/` (full Gradle project), `pc_sender/` (Python sender), `.github/workflows/build.yml`.

## Get the APK without installing anything locally
1. Create a new (private is fine) GitHub repo and push this folder to it.
2. Open the repo's **Actions** tab -> "Build APK and PC sender" -> wait for the green check.
3. Download the `pc-audio-apk` artifact, unzip, and copy `app-debug.apk` to your phone
   (enable "install unknown apps" for your file manager/browser). The same run also produces
   Windows and Linux sender binaries.

## Or build locally
Open `android/` in Android Studio (Ladybug or newer) and press Run, or: `gradle assembleDebug`
(Gradle 8.9, JDK 17). APK lands in `android/app/build/outputs/apk/debug/`.

## Use
Phone: open app -> **Start receiving**. PC: `pc-audio-sender <PHONE_IP>` (same Wi-Fi/LAN).
