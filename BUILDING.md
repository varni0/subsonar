# Building Subsonar on your own computer

## 1. Install the tools
- **JDK 17** (e.g. Temurin 17 from adoptium.net). Check with `java -version`.
- **Android Studio** (easiest; it installs the Android SDK for you), or just the Android SDK command-line tools.
  The project needs Android SDK platform **37** and build-tools (Android Studio will offer to install what's missing).

## 2. Option A: Android Studio (easiest)
1. Unzip the source, then in Android Studio choose **File > Open** and select the project folder (`subpilot`; the folder name predates the rename to Subsonar).
2. Wait for the Gradle sync to finish (first time downloads dependencies; needs internet).
3. Plug in a phone with USB debugging on (or start an emulator) and press **Run**.

## 3. Option B: command line
Make sure `JAVA_HOME` points to JDK 17 and `ANDROID_HOME` (or `local.properties` with `sdk.dir=/path/to/Android/sdk`) points to your SDK. Then from the `subpilot` folder:

```bash
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lint   # run the 21 unit tests and lint
./gradlew assembleRelease          # release APK (unsigned unless you set up a key, see below)
./gradlew bundleRelease            # release AAB for Google Play
```
On Windows use `gradlew.bat` instead of `./gradlew`.

Install the debug APK on a connected phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 4. Signing a release build
Release builds are only signed if a key file is found. Create your own key (do NOT reuse anyone else's, and back it up; you need the same key for every Play Store update):

```bash
keytool -genkeypair -v -keystore my-release.jks -alias subsonar -keyalg RSA -keysize 2048 -validity 10000
```

Then create a file `keystore.properties` somewhere outside the repo:

```
storeFile=/absolute/path/to/my-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=subsonar
keyPassword=YOUR_KEY_PASSWORD
```

Build with it:

```bash
./gradlew bundleRelease -PsubsonarKeystoreProps=/absolute/path/to/keystore.properties
```
(By default the build looks for `../subsonar-keystore/keystore.properties`, then the pre-rename `../subpilot-keystore/keystore.properties`; the upload key did not change with the rename.)

Outputs: `app/build/outputs/bundle/release/app-release.aab` (upload to Google Play) and `app/build/outputs/apk/release/app-release.apk`.

## 5. More
- Project overview and structure: `README.md`
- Turning on payments or ads later: `MONETIZATION.md`
- Store listing and privacy policy drafts: `docs/`
