# Pocket Upwatch

An Android HTTP(S) uptime monitor built with Kotlin, Compose and Room. Add endpoints with intervals from 30 seconds to 24 hours, which start monitoring when added. Pause or resume each service separately. Configure an expected HTTP code (or accept any 2xx) and optionally match a text, JSON, or XML response body. Alerts are sent when a known state changes. Grant notification permission to receive them.

Monitoring runs in a foreground service with an ongoing notification. Enabled endpoints resume when the app opens or after reboot; Android may also restart the service after reclaiming its process. A force-stop prevents automatic restart until the app is opened again. Unrestricted battery use may improve background reliability but can increase power consumption; configure it in the app’s system battery settings. Checks are scheduled after the previous check completes, so the interval is a minimum delay, not a precise cadence. Checks are skipped when Android reports no validated internet connection; this does not guarantee that all network failures can be distinguished from service failures. History reflects checks made by this device and network, not external availability. The displayed success percentage is sample-based and unknown gaps are excluded. History is stored locally and removed when its service is deleted.

Open in Android Studio with SDK 35 and JDK 17, or run `./gradlew assembleDebug`. The special-use foreground service requires an appropriate Google Play declaration and review before distribution.

The detail screen shows check availability and response times for selectable periods; blank availability graph areas represent no checks. Response-time graph points are ordered samples.

The service state shown by the app is held in the current process, not persisted: after process death it is considered stopped until the service starts again. A result is stale after the greater of twice the configured interval or two minutes. Offline skips are unknown gaps. Cards and detail views display at most the 500 most recent samples; older records are still stored. Response bodies used for matching are limited to 1 MiB and at most four checks execute concurrently.

## Android releases

Every push to `main`, including a merged pull request, builds a signed APK. Set `versionName` and `versionCode` in `app/build.gradle.kts` before merging a release. Increase the version code for every release so Android can install it as an update. If the `v<versionName>` tag does not already exist, the workflow creates it at the pushed commit and publishes a GitHub release. Pushes with an existing version still build but skip publication.

Configure these repository secrets under **Settings → Secrets and variables → Actions** before running the workflow:

- `ANDROID_KEYSTORE_BASE64`: the base64-encoded contents of the release keystore (for example, `base64 -w 0 release.keystore` on Linux).
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: the signing key alias.
- `ANDROID_KEY_PASSWORD`: the signing key password.

Keep the keystore and passwords safe and reuse the same key for future releases; Android requires the same signing key for app updates. To create a new keystore, use `keytool -genkeypair -v -keystore release.keystore -alias pocketupwatch -keyalg RSA -keysize 2048 -validity 10000`. Never commit the keystore or its passwords. Gradle includes these version values in the app manifest for both debug and release builds.
