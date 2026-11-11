# Pocket Upwatch

An Android uptime monitor built with Kotlin, Compose, and Room. Monitor HTTP(S), TCP ports, ICMP ping, DNS answers, and TLS certificate expiry from your phone. All configuration and history stay on the device unless you explicitly export them.

## Monitoring

Add a target, choose a check interval from 5 minutes to 24 hours with the interval slider, and optionally test it before saving. Monitors begin checking when saved; each can be paused independently. Search names, targets, or tags, sort by status/name/tag, or pause/resume the whole collection.

| Protocol | Example | What it checks |
| --- | --- | --- |
| HTTP(S) | `https://example.com/health` | Status and optional response-body assertion |
| TCP | `tcp://example.com:443` | Connection to a host and port |
| Ping | `ping://example.com` | ICMP echo; some networks block this |
| DNS | `dns://example.com` | System DNS resolution and optional expected IP answers |
| TLS | `tls://example.com:443` | Trusted certificate, hostname verification, and expiry warning |

HTTP checks support GET, HEAD, POST, PUT, PATCH, and DELETE; exact status codes or any 2xx; redirects; custom headers; basic/bearer authentication; and request bodies for POST/PUT/PATCH. Timeouts are configurable from 1 to 60 seconds. Headers and authentication are encrypted using Android Keystore-backed AES-GCM. URLs, request bodies, and expected bodies remain in the local database; avoid placing credentials in them. Target displays omit HTTP credentials and query strings. URL user information is converted to an encrypted Authorization header when saving.

Response assertions support exact text, structural JSON, and XML. JSON object key order is ignored, arrays remain ordered, scalar types remain distinct, and the entire document must be valid. An optional JSON path such as `$.status` or `$.items[0].ok` selects a value to compare with the expected JSON. For example, expected `"ok"` at `$.status` accepts `{"status":"ok","time":123}`. Wildcards and recursive JSON paths are not supported. XML does not allow doctypes or external entities. Response bodies used for matching are limited to 1 MiB. A maximum of four probes execute concurrently; displayed duration excludes time waiting for a probe slot and includes body validation when configured.

## Alerts, incidents, and history

Configure consecutive failure/recovery thresholds (default: two failures and one recovery), initial-down alerts, repeat reminders, and maintenance windows. Unknown/skipped checks break pending streaks and never count as endpoint failures or recovery. Confirmed incidents record observed start/end times, failure reasons, and failed/unknown check counts. Notifications open the affected monitor. Grant notification permission to receive alerts.

The detail screen shows availability and mean response times for 1 hour, 24 hours, 7 days, or 30 days. Statistics query the selected period; graphs aggregate samples into time buckets. Recent raw checks are paginated independently. Unknown and missing checks are excluded from success rates. A successful sample percentage does not establish continuous uptime. Open either graph in landscape fullscreen. Stale state refreshes while viewing a screen, even when no new checks arrive.

Raw-history retention is configurable in settings (30/90/180/365 days; default 90). Cleanup runs during monitoring and retains each monitor's latest result. Hourly summaries are retained for one year; incidents remain until their monitor is deleted. Deleting a monitor deletes its history, summaries, and incidents. Export checks or incidents as CSV from its detail screen.

## Background behavior

Monitoring uses a foreground service and ongoing notification. A single due-time scheduler coordinates active checks and wake-up alarms, using monotonic deadlines within a process. Enabled monitors resume when the app opens or after reboot; Android may restart the service after process reclamation. Pausing/deleting the last enabled monitor stops the service and alarms. A force-stop prevents automatic restart until the app is opened again.

Unrestricted battery use may improve background reliability but increase power use. Allow alarms and reminders for more timely wake-ups; otherwise inexact alarms are used. Android can throttle or defer alarms, especially during Doze, so intervals are not guaranteed. The special-use foreground service requires the appropriate Google Play declaration and review before distribution.

No active network produces an unknown skipped result. Enable **Require validated internet** for public targets to skip checks during captive portals or unvalidated connectivity; leave it off for LAN targets without internet access. Network changes during failed probes can also produce unknown results. This cannot distinguish every local-network fault from a remote outage. Results reflect this phone and network, rather than independent public availability. Maintenance windows record unknown skips and suspend endpoint alerts. Manual editor tests bypass scheduling and maintenance and do not record history or send alerts. Manual tests of paused monitors also leave history unchanged.

Add the Pocket Upwatch home-screen widget through your launcher, or add its Quick Settings tile to pause/resume all monitors. The widget displays a timestamped snapshot; opening the app refreshes live state. Process-local service status is considered stopped after process death until the service starts again.

## Backup and restore

Use **Backup and settings** to export or import versioned JSON through Android's file picker. Without a passphrase, exports omit headers and request/expected bodies and redact URL query values. These exports need review before use. A passphrase of at least 12 characters creates a full portable backup encrypted with PBKDF2-HMAC-SHA256 (210,000 iterations) and AES-256-GCM. Remember the passphrase; it cannot be recovered. Device-specific Keystore ciphertext is never exported as a portable credential.

Import shows a confirmation and adds paused copies without replacing existing monitors or importing history. Review credentials and redacted fields before enabling them. Imports are bounded in size and monitor count, validated, and committed transactionally. Android automatic backup is disabled.

## Development and tests

Open with Android Studio, SDK 35, and JDK 17. Alternatively set `ANDROID_HOME` to an installed SDK, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Unit tests cover JSON/XML matching, paths, alert thresholds, unknown state, scheduling, timing under concurrency, HTTP behavior against MockWebServer, backups, CSV safety, period queries, and migration chains from database versions 1, 2, and 3. Robolectric tests exercise Room, the editor, and idle-service shutdown. Tests download Robolectric's Android runtime from Maven Central. Exported Room schemas are committed under `app/schemas`.

Pull requests run tests, Android lint, and the debug build without signing secrets. The release workflow calls the same checks before building a signed APK. Physical-device validation of Doze, reboot, OEM battery restrictions, widgets, and Quick Settings is still recommended before distribution.

## Android releases

Version 0.1.1 uses version code 7. Before the next release, update `versionName` and increase `versionCode` in `app/build.gradle.kts`. Every push to `main` validates the project, then builds a signed APK. If `v<versionName>` does not exist, the workflow creates a tag and GitHub release. Existing version tags skip publication.

Configure repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEY_PASSWORD`: signing key password.

Keep the keystore and passwords safe, and reuse the same signing key for future app updates. Never commit them. To create a key, use `keytool -genkeypair -v -keystore release.keystore -alias pocketupwatch -keyalg RSA -keysize 2048 -validity 10000`.
